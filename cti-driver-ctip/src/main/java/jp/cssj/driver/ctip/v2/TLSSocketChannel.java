package jp.cssj.driver.ctip.v2;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import jp.cssj.cti2.TLSPolicy;

import jp.cssj.driver.ctip.common.ChannelIO;

/** Nonblocking TLS transport. Waiting belongs to ChannelIO, never to an engine lock. */
public class TLSSocketChannel extends SelectableChannel implements ByteChannel {
    public static final long DEFAULT_CONNECT_TIMEOUT = 30000;
    public static final long CLOSE_TIMEOUT = 1000;
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();
    private final SocketChannel channel;
    private final ReentrantLock receiveLock = new ReentrantLock();
    private final ReentrantLock sendLock = new ReentrantLock();
    private final Object taskLock = new Object();
    private final Set<Selector> waiters = ConcurrentHashMap.newKeySet();
    // All buffers are in read mode between engine calls. Each direction owns its buffers.
    private ByteBuffer peerAppData, peerNetData, netData;
    private volatile SSLEngine engine;
    private volatile boolean closing, connected, pendingOutput, plaintext;
    private volatile boolean handshakeFinished;
    private volatile IOException readFailure;

    public TLSSocketChannel(SocketChannel sc) {
        this.channel = sc;
    }

    public boolean connect(SocketAddress remote) throws IOException {
        return this.connect(remote, null, -1, DEFAULT_CONNECT_TIMEOUT);
    }

    /** Connects, then verifies the peer unless the caller asked not to. */
    public boolean connect(SocketAddress remote, String host, int port, long timeout) throws IOException {
        long deadline = ChannelIO.deadline(timeout > 0 ? timeout : DEFAULT_CONNECT_TIMEOUT);
        try {
            checkOpen();
            channel.configureBlocking(false);
            if (!channel.connect(remote)) {
                while (!channel.finishConnect()) {
                    ChannelIO.awaitReady(this, SelectionKey.OP_CONNECT, deadline);
                }
            }
            boolean insecure = TLSPolicy.isInsecure();
            SSLContext context = SSLContext.getInstance("TLS");
            if (insecure) {
                // 何でも通す。**試験用の逃げ道**であって既定ではない
                context.init(null, new TrustManager[] { new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                } }, null);
            } else {
                context.init(null, null, null);
            }
            // **ホストと port を渡す。** 引数なしの createSSLEngine() では SNI を
            // 送らないので、名前で振り分けているサーバーから正しい証明書が返らない
            engine = host != null ? context.createSSLEngine(host, port) : context.createSSLEngine();
            engine.setUseClientMode(true);
            if (!insecure && host != null) {
                // **ホスト名の検証を有効にする。** 証明書チェーンが正しくても、
                // 別のホストの証明書なら受け入れてはいけない
                SSLParameters params = engine.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIPAddress(host)) {
                    // IP リテラルは SNI に載せられない(RFC 6066)
                    params.setServerNames(Collections
                            .<SNIServerName> singletonList(new SNIHostName(host)));
                }
                engine.setSSLParameters(params);
            }
            peerAppData = emptyBuffer(engine.getSession().getApplicationBufferSize());
            peerNetData = emptyBuffer(engine.getSession().getPacketBufferSize());
            netData = emptyBuffer(engine.getSession().getPacketBufferSize());
            engine.beginHandshake();
            while (true) {
                ChannelIO.checkDeadline(deadline);
                flushOutbound();
                if (engine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING && !hasPendingOutbound()) {
                    if (!handshakeFinished) { throw new SSLException("TLS handshake ended without FINISHED"); }
                    connected = true;
                    return true;
                }
                if (engine.isInboundDone()) {
                    throw new SSLException("TLS peer closed during handshake");
                }
                if (engine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
                    if (unwrapStep()) {
                        continue;
                    }
                } else if (engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                    continue;
                } else if (!hasPendingOutbound()) {
                    throw new SSLException("Unsupported TLS handshake state: " + engine.getHandshakeStatus());
                }
                ChannelIO.awaitReady(this, requiredOps(), deadline);
            }
        } catch (GeneralSecurityException e) {
            IOException failure = new IOException("TLS initialization failed", e);
            failConnect(failure);
            throw failure;
        } catch (IOException | RuntimeException e) {
            failConnect(e);
            throw e;
        }
    }

    private void failConnect(Exception failure) {
        closing = true;
        try { channel.close(); } catch (IOException e) { failure.addSuppressed(e); }
        try { close(); } catch (IOException e) { failure.addSuppressed(e); }
    }

    private static ByteBuffer emptyBuffer(int capacity) {
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.flip();
        return buffer;
    }

    /** Grow a read-mode buffer, retaining every unread byte. */
    private static ByteBuffer grow(ByteBuffer buffer, int minimum) throws SSLException {
        long capacity = Math.max((long) buffer.capacity() * 2, minimum);
        if (capacity > Integer.MAX_VALUE) { throw new SSLException("TLS buffer too large"); }
        ByteBuffer larger = ByteBuffer.allocate((int) capacity);
        larger.put(buffer);
        larger.flip();
        return larger;
    }

    private void checkOpen() throws ClosedChannelException {
        if (!isOpen() || closing || !channel.isOpen()) { throw new ClosedChannelException(); }
    }

    private void runTasks() throws SSLException {
        synchronized (taskLock) {
            if (engine.getHandshakeStatus() != HandshakeStatus.NEED_TASK) { return; }
            int count = 0;
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) { task.run(); count++; }
            if (count == 0 && engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                throw new SSLException("TLS NEED_TASK without a delegated task");
            }
        }
    }

    private void progress(SSLEngineResult result, HandshakeStatus before) throws SSLException {
        if (result.getHandshakeStatus() == HandshakeStatus.FINISHED) {
            handshakeFinished = true; // FINISHED is a result event, never an engine state.
            return;
        }
        if (result.bytesConsumed() == 0 && result.bytesProduced() == 0
                && before == engine.getHandshakeStatus() && result.getStatus() == Status.OK) {
            throw new SSLException("TLS engine made no progress: " + result);
        }
    }

    /** One receive step. No wrap or external wait is performed while holding receiveLock. */
    private boolean unwrapStep() throws IOException {
        receiveLock.lock();
        try {
            checkOpen();
            if (engine.isInboundDone()) { return false; }
            HandshakeStatus before = engine.getHandshakeStatus();
            if (before == HandshakeStatus.NEED_WRAP || before == HandshakeStatus.NEED_TASK) { return true; }
            peerAppData.compact();
            SSLEngineResult result;
            try { result = engine.unwrap(peerNetData, peerAppData); }
            finally { peerAppData.flip(); plaintext = peerAppData.hasRemaining(); }
            if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                peerAppData = grow(peerAppData, engine.getSession().getApplicationBufferSize());
                return true;
            }
            if (result.getStatus() == Status.CLOSED) { return true; }
            if (result.getStatus() != Status.BUFFER_UNDERFLOW) {
                progress(result, before);
                return true;
            }
            // Only UNDERFLOW authorizes a lower read. compact retains record fragments.
            if (peerNetData.remaining() == peerNetData.capacity()
                    || peerNetData.capacity() < engine.getSession().getPacketBufferSize()) {
                peerNetData = grow(peerNetData, engine.getSession().getPacketBufferSize());
            }
            peerNetData.compact();
            int count;
            try { count = channel.read(peerNetData); }
            finally { peerNetData.flip(); }
            if (count < 0) {
                engine.closeInbound(); // JSSE throws on a truncated TLS stream.
                return true;
            }
            return count > 0;
        } finally { receiveLock.unlock(); }
    }

    /** The only engine.wrap path. Caller owns sendLock; pending records always go first. */
    private SSLEngineResult wrap(ByteBuffer source) throws IOException {
        while (true) {
            HandshakeStatus before = engine.getHandshakeStatus();
            netData.clear();
            SSLEngineResult result;
            try { result = engine.wrap(source, netData); }
            finally { netData.flip(); pendingOutput = netData.hasRemaining(); }
            if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                if (pendingOutput) { throw new SSLException("TLS wrap overflow produced output"); }
                netData = grow(netData, engine.getSession().getPacketBufferSize());
                continue;
            }
            if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
                throw new SSLException("Unexpected wrap underflow");
            }
            progress(result, before);
            return result;
        }
    }

    private boolean drain() throws IOException {
        while (netData.hasRemaining()) {
            if (channel.write(netData) == 0) { pendingOutput = true; return false; }
        }
        pendingOutput = false;
        return true;
    }

    /** Advance pending ciphertext and TLS control output without more application data. */
    public boolean flushOutbound() throws IOException {
        checkOpen();
        if (engine == null) { throw new java.nio.channels.NotYetConnectedException(); }
        return flush(false);
    }

    private boolean flush(boolean close) throws IOException {
        while (true) {
            if (!close) { checkOpen(); }
            if (engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) { runTasks(); }
            sendLock.lock();
            try {
                if (!drain()) { return false; }
                if (engine.getHandshakeStatus() != HandshakeStatus.NEED_WRAP) { return true; }
                wrap(EMPTY.duplicate());
            } finally { sendLock.unlock(); }
        }
    }

    public int write(ByteBuffer src) throws IOException {
        checkOpen();
        if (engine == null) { throw new java.nio.channels.NotYetConnectedException(); }
        int start = src.position();
        if (!flushOutbound()) { return 0; }
        // A post-handshake exchange may require receive progress even on a write-only caller.
        if (engine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
            unwrapStep();
            flushOutbound();
        }
        sendLock.lock();
        try {
            checkOpen();
            if (engine.isOutboundDone()) { throw new ClosedChannelException(); }
            if (src.hasRemaining() && !pendingOutput
                    && engine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
                wrap(src);
                drain();
            }
            return src.position() - start;
        } finally { sendLock.unlock(); }
    }

    public int read(ByteBuffer dst) throws IOException {
        checkOpen();
        if (engine == null) { throw new java.nio.channels.NotYetConnectedException(); }
        if (!dst.hasRemaining()) { return 0; }
        while (true) {
            receiveLock.lock();
            try {
                if (peerAppData.hasRemaining()) {
                    int count = Math.min(dst.remaining(), peerAppData.remaining());
                    int limit = peerAppData.limit();
                    peerAppData.limit(peerAppData.position() + count);
                    dst.put(peerAppData);
                    peerAppData.limit(limit);
                    plaintext = peerAppData.hasRemaining();
                    return count;
                }
                if (readFailure != null) { throw readFailure; }
                if (engine.isInboundDone()) { return -1; }
            } finally { receiveLock.unlock(); }
            boolean flushed = flushOutbound();
            if (!flushed && engine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) { return 0; }
            if (!unwrapStep()) { return 0; }
            if (plaintext) {
                // A data record can also request KeyUpdate. Generate its response now,
                // even if this is the caller's last read. Never lose already decrypted data
                // when that control send fails; report the failure on the following read.
                try { flushOutbound(); }
                catch (IOException e) { readFailure = e; }
            }
        }
    }

    public boolean hasPlaintext() { return plaintext; }
    public boolean hasPendingOutbound() {
        return pendingOutput || (engine != null && engine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP);
    }
    public int requiredOps() {
        if (engine != null && engine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) { return SelectionKey.OP_WRITE; }
        return hasPendingOutbound() ? SelectionKey.OP_READ | SelectionKey.OP_WRITE : SelectionKey.OP_READ;
    }
    public int requiredWriteOps() {
        return engine != null && engine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP
                && !hasPendingOutbound() ? SelectionKey.OP_READ : SelectionKey.OP_WRITE;
    }

    public boolean isConnected() { return connected && isOpen() && channel.isConnected(); }
    public SelectableChannel configureBlocking(boolean block) throws IOException {
        if (block && engine != null) { throw new IllegalArgumentException("TLS requires nonblocking transport"); }
        channel.configureBlocking(block);
        return this;
    }
    public Object blockingLock() { return channel.blockingLock(); }
    public boolean isBlocking() { return channel.isBlocking(); }
    public boolean isRegistered() { return channel.isRegistered(); }
    public SelectionKey keyFor(Selector selector) { return channel.keyFor(selector); }
    public SelectorProvider provider() { return channel.provider(); }
    public SelectionKey register(Selector selector, int ops, Object attachment) throws ClosedChannelException {
        checkOpen();
        return channel.register(selector, ops, attachment);
    }
    public int validOps() { return channel.validOps(); }

    /** ChannelIO wait registration also makes a direct TLS close wake its selectors. */
    public void addWaiter(Selector selector) { waiters.add(selector); if (closing) { selector.wakeup(); } }
    public void removeWaiter(Selector selector) { waiters.remove(selector); }

    protected void implCloseChannel() throws IOException {
        boolean failed = closing;
        closing = true;
        for (Selector selector : waiters) { selector.wakeup(); }
        long deadline = ChannelIO.deadline(CLOSE_TIMEOUT);
        try {
            if (failed || engine == null || !channel.isConnected()) { return; }
            boolean locked;
            try { locked = sendLock.tryLock(CLOSE_TIMEOUT, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted closing TLS", e);
            }
            if (!locked) { throw new SocketTimeoutException("TLS close timeout"); }
            try { engine.closeOutbound(); } finally { sendLock.unlock(); }
            while (true) {
                ChannelIO.checkDeadline(deadline);
                if (flush(true) && engine.isOutboundDone()) { break; }
                ChannelIO.awaitReady(channel, SelectionKey.OP_WRITE, deadline);
            }
        } finally {
            channel.close();
            for (Selector selector : waiters) { selector.wakeup(); }
        }
    }

    /**
     * ホストが IP リテラルかどうかです。
     *
     * <p>
     * SNI には IP アドレスを載せられません(RFC 6066)。載せると JDK が
     * {@code IllegalArgumentException} を投げます。
     * </p>
     */
    static boolean isIPAddress(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[' || host.indexOf(':') >= 0) {
            return true; // IPv6
        }
        char first = host.charAt(0);
        if (first < '0' || first > '9') {
            return false;
        }
        // 先頭が数字で、数字とドットだけなら IPv4 リテラルとみなす
        for (int i = 0; i < host.length(); ++i) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

}
