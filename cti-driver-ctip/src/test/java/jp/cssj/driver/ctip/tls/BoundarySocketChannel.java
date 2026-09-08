package jp.cssj.driver.ctip.tls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import jp.cssj.driver.ctip.v2.TLSSocketChannel;

/** In-memory wire: each queued chunk is exactly one read (unless dst is smaller).
 * No Selector emulation: integration tests use real sockets for that boundary.
 */
final class BoundarySocketChannel extends SocketChannel {
    final RecordingEngine server;
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final List<Integer> reads = new ArrayList<Integer>();
    final List<Integer> writes = new ArrayList<Integer>();
    final Deque<Integer> writeLimits = new ArrayDeque<Integer>();
    private final Deque<ByteBuffer> inbound = new ArrayDeque<ByteBuffer>();
    private final ByteBuffer serverInput = ByteBuffer.allocate(256 * 1024);
    private final ByteBuffer serverOutput = ByteBuffer.allocate(256 * 1024);
    private final ByteBuffer serverPlain = ByteBuffer.allocate(256 * 1024);
    private final ByteArrayOutputStream flight = new ByteArrayOutputStream();
    boolean eof;
    int readCalls;
    private boolean connected;
    private boolean observing;
    private TLSSocketChannel client;
    private boolean scripted;
    RecordingEngine clientEngine;
    java.util.function.UnaryOperator<SSLEngine> decorator = engine -> engine;

    BoundarySocketChannel(SSLEngine engine) throws IOException {
        super(SelectorProvider.provider());
        server = new RecordingEngine(engine, "server", false);
        server.beginHandshake();
    }

    void observe(TLSSocketChannel client, boolean scripted) {
        this.client = client;
        this.scripted = scripted;
    }

    /** Test-only reflection: observe the engine created by connect, without a production hook. */
    private void installObserver() throws IOException {
        if (observing || client == null) {
            return;
        }
        try {
            Field field = TLSSocketChannel.class.getDeclaredField("engine");
            field.setAccessible(true);
            clientEngine = new RecordingEngine((SSLEngine) field.get(client), "client", scripted);
            field.set(client, decorator.apply(clientEngine));
            observing = true;
        } catch (ReflectiveOperationException e) {
            throw new IOException("Test observation hook needs updating for the new implementation", e);
        }
    }

    /** Join any number of real server records into a single read. */
    void enqueue(byte[] bytes) {
        if (bytes.length != 0) {
            inbound.add(ByteBuffer.wrap(bytes));
        }
    }

    /** Split at arbitrary byte offsets, including within a TLS record header. */
    void enqueueSplit(byte[] bytes, int... cuts) {
        int start = 0;
        for (int cut : cuts) {
            if (cut <= start || cut >= bytes.length) {
                throw new IllegalArgumentException("Cuts must be increasing interior offsets");
            }
            enqueue(java.util.Arrays.copyOfRange(bytes, start, cut));
            start = cut;
        }
        enqueue(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
    }

    byte[] takeFlight() {
        byte[] bytes = flight.toByteArray();
        flight.reset();
        return bytes;
    }

    /** Drive a real server until it needs more encrypted input. All output forms one flight. */
    private void pump() throws IOException {
        for (int step = 0; step < 1000; step++) {
            HandshakeStatus hs = server.getHandshakeStatus();
            if (hs == HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = server.getDelegatedTask()) != null) {
                    task.run();
                }
                continue;
            }
            SSLEngineResult result;
            if (hs == HandshakeStatus.NEED_WRAP) {
                serverOutput.clear();
                result = server.wrap(ByteBuffer.allocate(0), serverOutput);
                serverOutput.flip();
                byte[] bytes = new byte[serverOutput.remaining()];
                serverOutput.get(bytes);
                flight.write(bytes, 0, bytes.length);
            } else {
                if (serverInput.position() == 0) {
                    return;
                }
                serverInput.flip();
                serverPlain.clear();
                result = server.unwrap(serverInput, serverPlain);
                serverInput.compact();
                serverPlain.flip();
                byte[] bytes = new byte[serverPlain.remaining()];
                serverPlain.get(bytes);
                received.write(bytes, 0, bytes.length);
                if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
                    return;
                }
            }
            if (result.getStatus() == Status.CLOSED) { return; }
            if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                throw new IOException("Unexpected server engine result: " + result);
            }
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0
                    && result.getHandshakeStatus() == hs) {
                throw new IOException("Server engine made no progress: " + result);
            }
        }
        throw new IOException("Server fixture exceeded progress bound");
    }

    byte[] serverData(byte[] plain) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(plain);
        while (source.hasRemaining()) {
            serverOutput.clear();
            server.wrap(source, serverOutput);
            serverOutput.flip();
            byte[] bytes = new byte[serverOutput.remaining()];
            serverOutput.get(bytes);
            flight.write(bytes, 0, bytes.length);
            pump();
        }
        return takeFlight();
    }

    byte[] serverClose() throws IOException {
        server.closeOutbound();
        pump();
        return takeFlight();
    }

    public int read(ByteBuffer dst) throws IOException {
        readCalls++;
        installObserver();
        if (inbound.isEmpty()) {
            enqueue(takeFlight());
        }
        if (inbound.isEmpty()) {
            return eof ? -1 : 0;
        }
        ByteBuffer chunk = inbound.peek();
        int n = Math.min(dst.remaining(), chunk.remaining());
        for (int i = 0; i < n; i++) {
            dst.put(chunk.get());
        }
        if (!chunk.hasRemaining()) {
            inbound.remove();
        }
        reads.add(n);
        System.out.println("WIRE_READ bytes=" + n);
        return n;
    }

    public int write(ByteBuffer src) throws IOException {
        installObserver();
        int n = Math.min(src.remaining(), writeLimits.isEmpty() ? Integer.MAX_VALUE : writeLimits.remove());
        if (n < 0) {
            throw new IllegalArgumentException("Negative write limit");
        }
        for (int i = 0; i < n; i++) {
            serverInput.put(src.get());
        }
        writes.add(n);
        pump();
        return n;
    }

    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        for (int i = offset; i < offset + length; i++) {
            if (dsts[i].hasRemaining()) { return read(dsts[i]); }
        }
        return 0;
    }
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        for (int i = offset; i < offset + length; i++) {
            if (srcs[i].hasRemaining()) { return write(srcs[i]); }
        }
        return 0;
    }
    public boolean connect(SocketAddress remote) { connected = true; return true; }
    public boolean finishConnect() { return connected; }
    public boolean isConnected() { return connected && isOpen(); }
    public boolean isConnectionPending() { return false; }
    protected void implCloseSelectableChannel() { connected = false; }
    protected void implConfigureBlocking(boolean block) { }
    public SocketChannel bind(SocketAddress local) { throw new UnsupportedOperationException(); }
    public <T> SocketChannel setOption(SocketOption<T> name, T value) { throw new UnsupportedOperationException(); }
    public <T> T getOption(SocketOption<T> name) { throw new UnsupportedOperationException(); }
    public Set<SocketOption<?>> supportedOptions() { return Collections.emptySet(); }
    public SocketChannel shutdownInput() { throw new UnsupportedOperationException(); }
    public SocketChannel shutdownOutput() { throw new UnsupportedOperationException(); }
    public Socket socket() { throw new UnsupportedOperationException(); }
    public SocketAddress getRemoteAddress() { return null; }
    public SocketAddress getLocalAddress() { return null; }
}
