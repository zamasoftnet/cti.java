package jp.cssj.driver.ctip.common;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import jp.cssj.driver.ctip.v2.TLSSocketChannel;

/** Nonblocking channel operations and deadline-based waits. */
public final class ChannelIO {
    private final ByteChannel channel;
    private final long timeout;
    private Selector rwselector;
    private volatile boolean closed;
    private final Set<Selector> waiters = ConcurrentHashMap.newKeySet();

    public ChannelIO(ByteChannel channel, long timeout) throws IOException {
        this.channel = channel;
        this.timeout = timeout;
        try { getSelectable().configureBlocking(false); }
        catch (IOException | RuntimeException e) {
            try { channel.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    public ByteChannel getChannel() { return channel; }
    public SelectableChannel getSelectable() { return (SelectableChannel) channel; }

    public void close() throws IOException {
        closed = true;
        wakeup();
        try { channel.close(); }
        finally {
            wakeup();
            if (rwselector != null) { rwselector.close(); }
        }
    }

    public void wakeup() {
        for (Selector selector : waiters) { selector.wakeup(); }
        if (rwselector != null) { rwselector.wakeup(); }
    }

    private void checkOpen() throws ClosedChannelException {
        if (closed || !channel.isOpen()) { throw new ClosedChannelException(); }
    }

    public static long deadline(long timeout) {
        return timeout <= 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    }

    public static void checkDeadline(long deadline) throws SocketTimeoutException {
        if (deadline != Long.MAX_VALUE && deadline - System.nanoTime() <= 0) {
            throw new SocketTimeoutException("Channel I/O timeout");
        }
    }

    public long deadline() { return deadline(timeout); }

    private static void select(SelectableChannel channel, Selector selector, long deadline) throws IOException {
        if (!channel.isOpen()) { throw new ClosedChannelException(); }
        checkDeadline(deadline);
        long millis = deadline == Long.MAX_VALUE ? 0 : Math.max(1,
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        selector.select(millis);
        selector.selectedKeys().clear();
        if (!channel.isOpen()) { throw new ClosedChannelException(); }
        if (Thread.currentThread().isInterrupted()) { throw new IOException("Interrupted waiting for channel"); }
        checkDeadline(deadline);
    }

    /** Also used before ChannelIO exists (TCP/TLS connect) and after it closes (TLS shutdown). */
    public static void awaitReady(SelectableChannel channel, int ops, long deadline) throws IOException {
        try (Selector selector = channel.provider().openSelector()) {
            TLSSocketChannel tls = channel instanceof TLSSocketChannel ? (TLSSocketChannel) channel : null;
            if (tls != null) { tls.addWaiter(selector); }
            try {
                channel.register(selector, ops);
                select(channel, selector, deadline);
            } finally { if (tls != null) { tls.removeWaiter(selector); } }
        }
    }

    /** Each waiter owns its selector: a concurrent abort never waits for the reader's monitor. */
    public void await(int ops, long deadline) throws IOException {
        checkOpen();
        try (Selector selector = getSelectable().provider().openSelector()) {
            waiters.add(selector);
            TLSSocketChannel tls = tls();
            if (tls != null) { tls.addWaiter(selector); }
            try {
                checkOpen();
                getSelectable().register(selector, ops);
                select(getSelectable(), selector, deadline);
                checkOpen();
            } finally {
                waiters.remove(selector);
                if (tls != null) { tls.removeWaiter(selector); }
            }
        }
    }

    private TLSSocketChannel tls() {
        return channel instanceof TLSSocketChannel ? (TLSSocketChannel) channel : null;
    }
    public int readSome(ByteBuffer dest) throws IOException { checkOpen(); return channel.read(dest); }
    public int writeSome(ByteBuffer src) throws IOException { checkOpen(); return channel.write(src); }
    public boolean flushOutbound() throws IOException {
        checkOpen();
        return tls() == null || tls().flushOutbound();
    }
    public boolean hasPendingOutbound() { return tls() != null && tls().hasPendingOutbound(); }
    public boolean hasPlaintext() { return tls() != null && tls().hasPlaintext(); }
    public int readOps() { return tls() == null ? SelectionKey.OP_READ : tls().requiredOps(); }
    public int writeOps() { return tls() == null ? SelectionKey.OP_WRITE : tls().requiredWriteOps(); }

    /** Legacy v1 readiness API. v2 uses internal progress before await(). */
    public SelectionKey rwselect() throws IOException {
        checkOpen();
        if (rwselector == null) {
            rwselector = getSelectable().provider().openSelector();
            getSelectable().register(rwselector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
        long deadline = deadline();
        while (true) {
            checkDeadline(deadline);
            long millis = deadline == Long.MAX_VALUE ? 0 : Math.max(1,
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (rwselector.select(millis) > 0) {
                SelectionKey key = rwselector.selectedKeys().iterator().next();
                rwselector.selectedKeys().clear();
                return key;
            }
            checkOpen();
        }
    }

    public void readAll(ByteBuffer dest) throws IOException {
        dest.position(0);
        long deadline = deadline();
        while (dest.hasRemaining()) {
            checkDeadline(deadline);
            int count = readSome(dest);
            if (count < 0) { throw new EOFException(); }
            if (count == 0) { await(readOps(), deadline); }
        }
    }

    public void writeAll(ByteBuffer src) throws IOException {
        src.position(0);
        long deadline = deadline();
        while (src.hasRemaining() || hasPendingOutbound()) {
            checkDeadline(deadline);
            int count = writeSome(src);
            if (!src.hasRemaining() && !hasPendingOutbound()) { return; }
            if (count == 0) { await(writeOps(), deadline); }
        }
    }

	/**
	 * 1バイト整数を読み込みます。
	 * 
	 * @param destByte
	 * @return 読み込んだ値。
	 * @throws IOException
	 */
	public byte readByte(ByteBuffer destByte) throws IOException {
		this.readAll(destByte);
		return destByte.get(0);
	}

	/**
	 * 2バイト整数を読み込みます。
	 * 
	 * @param destShort
	 * @return 読み込んだ値。
	 * @throws IOException
	 */
	public short readShort(ByteBuffer destShort) throws IOException {
		this.readAll(destShort);
		return destShort.getShort(0);
	}

	/**
	 * 4バイト整数を読み込みます。
	 * 
	 * @param destInt
	 * @return 読み込んだ値。
	 * @throws IOException
	 */
	public int readInt(ByteBuffer destInt) throws IOException {
		this.readAll(destInt);
		return destInt.getInt(0);
	}

	/**
	 * 8バイト整数を読み込みます。
	 * 
	 * @param destLong
	 * @return 読み込んだ値。
	 * @throws IOException
	 */
	public long readLong(ByteBuffer destLong) throws IOException {
		this.readAll(destLong);
		return destLong.getLong(0);
	}

	/**
	 * 文字列を読み込みます。 文字列は2バイトの文字列長(バイト数)に続く文字列本体のバイト列で構成されます。
	 * バイト列は指定したエンコーディングで文字列に変換します。
	 * 
	 * @param destShort
	 * @param encoding
	 * @return 読み込んだ文字列。
	 * @throws IOException
	 */
	public String readString(ByteBuffer destShort, String encoding) throws IOException {
		// 長さは符号なし16bit(2026-08-28。V2RequestProducer.readStringと同じ理由)
		int len = this.readShort(destShort) & 0xFFFF;
		if (len == 0) {
			return "";
		}
		byte[] buff = this.readBytes(len);
		return new String(buff, encoding);
	}

	/**
	 * 指定された長さだけバイト列を読み込みます。
	 * 
	 * @param len
	 * @return 読み込んだデータ。
	 * @throws IOException
	 */
	public byte[] readBytes(int len) throws IOException {
		byte[] buff = new byte[len];
		ByteBuffer dest = ByteBuffer.wrap(buff);
		this.readAll(dest);
		return buff;
	}

	/** 文字列1つの上限(長さは符号なし16bitで送るため)。 */
	public static final int MAX_STRING_BYTES = 0xFFFF;

	public static byte[] toBytes(String str, String encoding) throws IOException {
		if (str == null) {
			str = "";
		}
		final byte[] bytes = str.getBytes(encoding);
		// **収まらない値は送らない**(2026-08-28)。長さが16bitに入らないまま
		// 送るとサーバー側で本体が読み飛ばされずストリームが崩れ、
		// 「Bad request」で接続ごと落ちる。壊すより断る方が直せる
		if (bytes.length > MAX_STRING_BYTES) {
			throw new IOException(
					"CTIP string too long: " + bytes.length + " bytes (max " + MAX_STRING_BYTES + ")");
		}
		return bytes;
	}
}