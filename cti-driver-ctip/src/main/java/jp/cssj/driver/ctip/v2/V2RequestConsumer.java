package jp.cssj.driver.ctip.v2;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import jp.cssj.driver.ctip.common.ChannelIO;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V2RequestConsumer.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public class V2RequestConsumer {
	private final String charset;

	private final byte[] buff = new byte[V2Session.BUFFER_SIZE + 5];

	private final ChannelIO io;

	private int pos = 0;
    private final Object packetLock = new Object();
    private final Deque<Packet> packets = new ArrayDeque<Packet>();
    private IOException sendFailure;
    private boolean closing;

    private static final class Packet {
        final ByteBuffer bytes;
        volatile boolean done;
        Packet(ByteBuffer bytes) { this.bytes = bytes; }
    }

	private V2Session session;

	V2RequestConsumer(ChannelIO io, String charset) throws IOException {
		this.io = io;
		this.charset = charset;
	}

	protected void setCTIPSession(V2Session session) {
		this.session = session;
	}

	/**
	 * プロパティを送ります。
	 * 
	 * @param name
	 *            プロパティ名。
	 * @param value
	 *            値。
	 * @throws IOException
	 */
	public void property(String name, String value) throws IOException {
		byte[] nameBytes = ChannelIO.toBytes(name, this.charset);
		byte[] valueBytes = ChannelIO.toBytes(value, this.charset);

		int payload = 1 + 2 + nameBytes.length + 2 + valueBytes.length;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.PROPERTY);
		src.putShort((short) nameBytes.length);
		src.put(nameBytes);
		src.putShort((short) valueBytes.length);
		src.put(valueBytes);
		this.send(src, true);
	}

	/**
	 * クライアント側でリソースを解決するモードを設定します。
	 * 
	 * @param on
	 *            trueであれば切り替え、falseであれば解除。
	 * @throws IOException
	 */
	public void clientResource(boolean on) throws IOException {

		int payload = 2;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.CLIENT_RESOURCE);
		src.put((byte) (on ? 1 : 0));
		this.send(src, true);
	}

	/**
	 * 本体の開始を通知します。
	 * 
	 * @param uri
	 *            仮想URI。
	 * @param mimeType
	 *            MIME型。
	 * @param encoding
	 *            キャラクタ・エンコーディング。
	 * @throws IOException
	 */
	public void startMain(URI uri, String mimeType, String encoding, long length) throws IOException {
		byte[] uriBytes = ChannelIO.toBytes(uri.toString(), this.charset);
		byte[] mimeTypeBytes = ChannelIO.toBytes(mimeType, this.charset);
		byte[] encodingBytes = ChannelIO.toBytes(encoding, this.charset);

		int payload = 1 + 2 + uriBytes.length + 2 + mimeTypeBytes.length + 2 + encodingBytes.length + 8;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.START_MAIN);
		src.putShort((short) uriBytes.length);
		src.put(uriBytes);
		src.putShort((short) mimeTypeBytes.length);
		src.put(mimeTypeBytes);
		src.putShort((short) encodingBytes.length);
		src.put(encodingBytes);
		src.putLong(length);
		this.send(src, true);
	}

	/**
	 * サーバー側でメインドキュメントを取得します。
	 * 
	 * @param uri
	 *            メインドキュメントのURI。
	 * @throws IOException
	 */
	public void serverMain(URI uri) throws IOException {
		byte[] uriBytes = ChannelIO.toBytes(uri.toString(), this.charset);

		int payload = 1 + 2 + uriBytes.length;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.SERVER_MAIN);
		src.putShort((short) uriBytes.length);
		src.put(uriBytes);
		this.send(src, true);
	}

	/**
	 * データパケットを送ります。
	 * 
	 * @param b
	 *            バイト列バッファ。
	 * @param off
	 *            データの開始位置。
	 * @param len
	 *            データの長さ。
	 * @throws IOException
	 */
	public void data(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            Packet packet = null;
            synchronized (packetLock) {
                checkSending();
                int count = Math.min(len, V2Session.BUFFER_SIZE - pos);
                System.arraycopy(b, off, buff, 5 + pos, count);
                pos += count;
                off += count;
                len -= count;
                if (pos == V2Session.BUFFER_SIZE) { packet = detachData(); }
            }
            if (packet != null) { sendUntil(packet, true, io.deadline()); }
        }
    }

    private void checkSending() throws IOException {
        if (sendFailure != null) { throw sendFailure; }
        if (closing) { throw new java.nio.channels.ClosedChannelException(); }
    }

    /** Called only with packetLock. Transfer ownership before any callback can run. */
    private Packet detachData() {
        if (pos == 0) { return null; }
        ByteBuffer bytes = ByteBuffer.allocate(pos + 5);
        bytes.putInt(pos + 1).put(V2ClientPackets.DATA).put(buff, 5, pos);
        bytes.flip();
        pos = 0;
        Packet packet = new Packet(bytes);
        packets.add(packet);
        return packet;
    }

    private void send(ByteBuffer bytes, boolean callbacks) throws IOException {
        // Finish the buffered DATA and its callbacks before enqueuing a following
        // control frame (especially main EOF). A resource reply must precede that EOF.
        if (callbacks) {
            Packet buffered;
            synchronized (packetLock) { checkSending(); buffered = detachData(); }
            if (buffered != null) { sendUntil(buffered, true, io.deadline()); }
        }
        Packet target;
        synchronized (packetLock) {
            checkSending();
            detachData();
            bytes.position(0);
            target = new Packet(bytes);
            packets.add(target);
        }
        io.wakeup();
        sendUntil(target, callbacks, io.deadline());
    }

    /** Every thread may advance the head, but no thread can interleave packet bytes. */
    private void sendUntil(Packet target, boolean callbacks, long deadline) throws IOException {
        try {
            while (true) {
                ChannelIO.checkDeadline(deadline);
                boolean progress = false;
                synchronized (packetLock) {
                    if (sendFailure != null) { throw sendFailure; }
                    if (target.done) { return; }
                    Packet head = packets.peek();
                    if (head != null) {
                        progress = io.writeSome(head.bytes) > 0;
                        if (!head.bytes.hasRemaining() && !io.hasPendingOutbound()) {
                            packets.remove();
                            head.done = true;
                            progress = true;
                            io.wakeup();
                        }
                    }
                }
                // No packet or transport lock is held while processing a complete response.
                if (callbacks && session != null) { progress |= session.pollResponse(); }
                if (target.done) { return; }
                if (!progress) {
                    int ops = io.writeOps();
                    if (callbacks && session != null && session.canPollResponse()) { ops |= io.readOps(); }
                    io.await(ops, deadline);
                }
            }
        } catch (IOException | RuntimeException e) {
            synchronized (packetLock) {
                if (sendFailure == null) { sendFailure = e instanceof IOException ? (IOException) e : new IOException(e); }
            }
            // A partial frame cannot safely be retried after a timeout/failure.
            try { io.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

	/**
	 * リソースの開始を通知します。
	 * 
	 * @param uri
	 *            仮想URI。
	 * @param mimeType
	 *            MIME型。
	 * @param encoding
	 *            キャラクタ・エンコーディング。
	 * @throws IOException
	 */
	public void startResource(URI uri, String mimeType, String encoding, long length) throws IOException {
		byte[] uriBytes = ChannelIO.toBytes(uri.toString(), this.charset);
		byte[] mimeTypeBytes = ChannelIO.toBytes(mimeType, this.charset);
		byte[] encodingBytes = ChannelIO.toBytes(encoding, this.charset);

		int payload = 1 + 2 + uriBytes.length + 2 + mimeTypeBytes.length + 2 + encodingBytes.length + 8;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.START_RESOURCE);
		src.putShort((short) uriBytes.length);
		src.put(uriBytes);
		src.putShort((short) mimeTypeBytes.length);
		src.put(mimeTypeBytes);
		src.putShort((short) encodingBytes.length);
		src.put(encodingBytes);
		src.putLong(length);
		this.send(src, true);
	}

	/**
	 * 存在しないリソースとして通知します。
	 * 
	 * @param uri
	 *            リソースのURI。
	 * @throws IOException
	 */
	public void missingResource(URI uri) throws IOException {
		byte[] uriBytes = ChannelIO.toBytes(uri.toString(), this.charset);

		int payload = 1 + 2 + uriBytes.length;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.MISSING_RESOURCE);
		src.putShort((short) uriBytes.length);
		src.put(uriBytes);
		this.send(src, true);
	}

	/**
	 * データの終了を通知します。
	 * 
	 * @throws IOException
	 */
	public void eof() throws IOException {

		int payload = 1;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.EOF);
		this.send(src, true);
	}

	/**
	 * 複数の結果を結合するモードを切り替えます。
	 * 
	 * @param continuous
	 *            結合モード。
	 * @throws IOException
	 */
	public void continuous(boolean continuous) throws IOException {

		int payload = 2;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.CONTINUOUS);
		src.put((byte) (continuous ? 1 : 0));
		this.send(src, true);
	}

	/**
	 * 結果の結合を要求します。
	 * 
	 * @throws IOException
	 */
	public void join() throws IOException {
		int payload = 1;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.JOIN);
		this.send(src, true);
	}

	/**
	 * 処理の中断を要求します。
	 * 
	 * @param mode
	 *            中断モード。
	 * @throws IOException
	 */
	public void abort(byte mode) throws IOException {

		int payload = 2;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.ABORT);
		src.put(mode);
		this.send(src, false);
	}

	/**
	 * 状態をリセットします。
	 * 
	 * @throws IOException
	 */
	public void reset() throws IOException {

		int payload = 1;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.RESET);
		this.send(src, true);
	}

	/**
	 * 通信を終了します。
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException {
        Packet target;
        synchronized (packetLock) {
            if (closing) { return; }
            closing = true;
            detachData();
            ByteBuffer bytes = ByteBuffer.allocate(5);
            bytes.putInt(1).put(V2ClientPackets.CLOSE).flip();
            target = new Packet(bytes);
            packets.add(target);
        }
        io.wakeup();
        try { sendUntil(target, false, ChannelIO.deadline(TLSSocketChannel.CLOSE_TIMEOUT)); }
        finally { io.close(); }
    }

	/**
	 * サーバー情報を要求します。
	 * 
	 * @param uri
	 *            サーバー情報のURI。
	 * @throws IOException
	 */
	public void serverInfo(URI uri) throws IOException {
		byte[] uriBytes = ChannelIO.toBytes(uri.toString(), this.charset);

		int payload = 1 + 2 + uriBytes.length;
		ByteBuffer src = ByteBuffer.allocate(4 + payload);
		src.putInt(payload);
		src.put(V2ClientPackets.SERVER_INFO);
		src.putShort((short) uriBytes.length);
		src.put(uriBytes);
		this.send(src, true);
	}
}
