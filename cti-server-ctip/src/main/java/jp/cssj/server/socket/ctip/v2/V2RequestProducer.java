package jp.cssj.server.socket.ctip.v2;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import jp.cssj.driver.ctip.v2.V2ClientPackets;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V2RequestProducer.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public class V2RequestProducer {
	private final String charset;

	private final DataInputStream in;

	private int off, len;

	private byte[] buffer;

	private byte type, mode;

	private String uri;

	private String mimeType, encoding, name, value;

	private long length;

	V2RequestProducer(String charset, InputStream in) {
		this.charset = charset;
		this.in = new DataInputStream(in);
	}

	/**
	 * Moves the cursor to the next packet.
	 * 
	 * @throws IOException
	 */
	public void next() throws IOException {
		this.len = this.in.readInt();
		this.type = this.in.readByte();
		// System.err.println(Integer.toHexString(this.type));
		switch (this.type) {
		case V2ClientPackets.PROPERTY:
			this.name = this.readString();
			this.value = this.readString();
			break;

		case V2ClientPackets.START_RESOURCE:
		case V2ClientPackets.START_MAIN:
			this.uri = this.readString();
			this.mimeType = this.readString();
			this.encoding = this.readString();
			this.length = this.in.readLong();
			break;

		case V2ClientPackets.MISSING_RESOURCE:
		case V2ClientPackets.SERVER_MAIN:
		case V2ClientPackets.SERVER_INFO:
			this.uri = this.readString();
			break;

		case V2ClientPackets.DATA:
			this.len -= 1;
			if (this.buffer == null || this.buffer.length < this.len) {
				this.buffer = new byte[this.len];
			}
			for (int off = 0; off < this.len; off += this.in.read(this.buffer, off, this.len - off))
				;
			this.off = 0;
			break;

		case V2ClientPackets.CLIENT_RESOURCE:
		case V2ClientPackets.ABORT:
		case V2ClientPackets.CONTINUOUS:
			this.mode = this.in.readByte();
			break;

		case V2ClientPackets.EOF:
		case V2ClientPackets.JOIN:
		case V2ClientPackets.RESET:
		case V2ClientPackets.CLOSE:
			break;

		default:
			throw new IOException("Bad request: type " + Integer.toHexString(this.type));
		}
	}

	private String readString() throws IOException {
		// **The length is an unsigned 16-bit value** (2026-08-28). Reading it as signed made values
		// above 32,767 bytes negative and corrupted the stream without skipping the body.
		// Sending a 48 KB input.image-metrics (data:URI) caused ':' within the body to be read
		// as the next packet type, dropping the connection with "Bad request: type 3a"
		int len = this.in.readUnsignedShort();
		if (len <= 0) {
			return null;
		}
		byte[] bytes = new byte[len];
		for (int off = 0; off < len; off += this.in.read(bytes, off, len - off))
			;
		return new String(bytes, this.charset);
	}

	/**
	 * Returns the packet type.
	 * 
	 * @return
	 */
	public byte getType() {
		return this.type;
	}

	/**
	 * Returns the property name.
	 * 
	 * @return
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Returns the property value.
	 * 
	 * @return
	 */
	public String getValue() {
		return this.value;
	}

	/**
	 * Returns the virtual URI of the data.
	 * 
	 * @return
	 */
	public String getURI() {
		return this.uri;
	}

	/**
	 * Returns the MIME type of the data.
	 * 
	 * @return
	 */
	public String getMimeType() {
		return this.mimeType;
	}

	/**
	 * Returns the character encoding of the data.
	 * 
	 * @return
	 */
	public String getEncoding() {
		return this.encoding;
	}

	public long getLength() {
		return this.length;
	}

	public long getMode() {
		return this.mode;
	}

	/**
	 * Receives data.
	 * 
	 * @param b
	 *            The byte buffer.
	 * @param off
	 *            The starting offset at which to write the received data.
	 * @param len
	 *            The maximum length of data to receive.
	 * @return The number of bytes received.
	 */
	public int read(byte[] b, int off, int len) {
		int remainder = this.len - this.off;
		if (remainder <= 0) {
			return -1;
		}
		int length = Math.min(len, remainder);
		System.arraycopy(this.buffer, this.off, b, off, length);
		this.off += length;
		return length;
	}

	public byte[] getDataBuffer() {
		return this.buffer;
	}

	public int getDataOffset() {
		return this.off;
	}

	public int getDataLength() {
		return this.len;
	}
}