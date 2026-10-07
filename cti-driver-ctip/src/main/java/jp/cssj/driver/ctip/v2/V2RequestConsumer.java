package jp.cssj.driver.ctip.v2;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import jp.cssj.cti2.TranscoderException;
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
	 * Sends a property.
	 * 
	 * @param name
	 *            The property name.
	 * @param value
	 *            The value.
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
	 * Sets the mode for resolving resources on the client.
	 * 
	 * @param on
	 *            true to enable the mode, false to disable it.
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
	 * Signals the start of the main document.
	 * 
	 * @param uri
	 *            The virtual URI.
	 * @param mimeType
	 *            The MIME type.
	 * @param encoding
	 *            The character encoding.
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
	 * Retrieves the main document on the server.
	 * 
	 * @param uri
	 *            The URI of the main document.
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
	 * Sends a data packet.
	 * 
	 * @param b
	 *            The byte array buffer.
	 * @param off
	 *            The starting offset of the data.
	 * @param len
	 *            The length of the data.
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
        } catch (TranscoderException e) {
            // The server's ABORT, read by pollResponse, is a complete response, not a
            // transport failure: the inbound stream is in sync, and the queued packets
            // (the rest of the body, possibly a partly written frame) are still sent in
            // order by the next send and ignored by the server. Latching it here closed
            // the connection and made close()/reset() rethrow the abort (2026-09-28).
            throw e;
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
	 * Signals the start of a resource.
	 * 
	 * @param uri
	 *            The virtual URI.
	 * @param mimeType
	 *            The MIME type.
	 * @param encoding
	 *            The character encoding.
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
	 * Reports a resource as missing.
	 * 
	 * @param uri
	 *            The URI of the resource.
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
	 * Signals the end of data.
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
	 * Switches the mode for combining multiple results.
	 * 
	 * @param continuous
	 *            The mode for combining results.
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
	 * Requests that the results be combined.
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
	 * Requests that processing be aborted.
	 * 
	 * @param mode
	 *            The abort mode.
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
	 * Resets the state.
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
	 * Ends communication.
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
	 * Requests server information.
	 * 
	 * @param uri
	 *            The URI of the server information.
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
