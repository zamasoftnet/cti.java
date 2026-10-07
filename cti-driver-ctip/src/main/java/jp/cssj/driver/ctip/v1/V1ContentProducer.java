package jp.cssj.driver.ctip.v1;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashMap;
import java.util.Map;

import jp.cssj.driver.ctip.common.ChannelIO;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V1ContentProducer.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public class V1ContentProducer {
	/**
	 * A packet that adds a fragment.
	 */
	public static final byte ADD = 1;

	/**
	 * A packet that inserts a fragment.
	 * 
	 * Use getAnchorId to obtain the ID of the immediately following fragment.
	 */
	public static final byte INSERT = 2;

	/**
	 * An error message packet.
	 * 
	 * Use getLevel and getMessage to obtain the error level and message.
	 */
	public static final byte MESSAGE = 3;

	/**
	 * A data packet.
	 * 
	 * Use getId and read to obtain the fragment ID and data.
	 */
	public static final byte DATA = 4;

	/**
	 * Indicates a warning, such as a CSS syntax error.
	 */
	public static final byte ERROR_WARN = 1;

	/**
	 * An error that causes information to be missing from the generated document, such as a resource retrieval failure.
	 */
	public static final byte ERROR_ERROR = 2;

	/**
	 * A fatal error that prevents processing from continuing.
	 */
	public static final byte ERROR_FATAL = 3;

	protected final String encoding;

	protected final URI uri;

	protected ChannelIO io;

	public V1ContentProducer(URI uri, String encoding) throws IOException {
		// Accepting ctips: in v1 would establish a plaintext connection (V1Session#rejectSecureScheme).
		V1Session.rejectSecureScheme(uri);
		this.encoding = encoding;
		this.uri = uri;
	}

	/**
	 * Connects to the server and starts a request.
	 * 
	 * @throws IOException
	 */
	public V1RequestConsumer connect() throws IOException {
		String host = this.uri.getHost();
		int port = this.uri.getPort();
		if (port == -1) {
			port = 8099;
		}
		SocketChannel channel = SelectorProvider.provider().openSocketChannel();
		channel.connect(new InetSocketAddress(host, port));
		channel.configureBlocking(true);

		byte[] header = ("CTIP/1.0 " + this.encoding + "\n").getBytes("ISO-8859-1");
		this.io = new ChannelIO(channel, 0);
		this.io.writeAll(ByteBuffer.wrap(header));
		return new V1RequestConsumer(this.io, this.encoding);
	}

	private byte type;

	private int id, anchorId;

	private int progress;

	private short code;

	private String[] args = new String[1];

	private String message;

	private ByteBuffer data;

	private ByteBuffer destInt = ByteBuffer.allocate(4);

	private ByteBuffer destShort = ByteBuffer.allocate(2);

	private ByteBuffer destByte = ByteBuffer.allocate(1);

	protected void close() throws IOException {
		if (this.io != null) {
			this.io.close();
			this.io = null;
		}
	}

	private static final Map<String, Short> NAME_TO_CODE = new HashMap<String, Short>();
	static {
		NAME_TO_CODE.put("page-number", new Short((short) 0x1801/* INFO_PAGE_NUMBER */));
		NAME_TO_CODE.put("heading-title", new Short((short) 0x1802/* INFO_HEADING_TITLE */));
		NAME_TO_CODE.put("broken-image-uri", new Short((short) 0x2811/* WARN_MISSING_IMAGE */));
		NAME_TO_CODE.put("pass-remainder", new Short((short) 0x1803/* INFO_PASS_REMAINDER */));
		NAME_TO_CODE.put("annot", new Short((short) 0x1804/* INFO_ANNOTATION */));
	}

	/**
	 * Moves the cursor to the next packet.
	 * 
	 * @return false if an end packet is received; otherwise true.
	 * @throws IOException
	 */
	public boolean next() throws IOException {
		int payload = this.io.readInt(this.destInt);
		if (payload == 0) {
			this.close();
			return false;
		}

		this.type = this.io.readByte(this.destByte);
		switch (this.type) {
		case ADD:
			break;

		case INSERT:
			this.anchorId = this.io.readInt(this.destInt);
			break;

		case DATA:
			this.id = this.io.readInt(this.destInt);
			this.progress = this.io.readInt(this.destInt);
			payload -= 9;
			this.data = ByteBuffer.allocate(payload);
			this.io.readAll(this.data);
			this.data.position(0);
			break;

		case MESSAGE:
			this.code = this.io.readByte(this.destByte);
			if (this.code == 4) {
				this.code = 1;
			} else if (this.code == 2) {
				this.code = 2;
			} else {
				this.code += 1;
			}
			this.code <<= 12;
			this.message = this.args[0] = this.io.readString(this.destShort, this.encoding);
			int colon = this.args[0].indexOf(':');
			if (colon != -1) {
				String name = this.args[0].substring(0, colon);
				Short scode = (Short) NAME_TO_CODE.get(name);
				if (scode != null) {
					this.code = scode.shortValue();
					this.args[0] = this.args[0].substring(colon + 1);
				}
			}

			break;

		default:
			throw new IOException("Bad response: type " + this.type);
		}

		return true;
	}

	/**
	 * Returns the fragment ID.
	 * 
	 * @return The fragment ID.
	 * @throws IOException
	 */
	public int getId() throws IOException {
		return this.id;
	}

	/**
	 * Returns the ID of the anchor fragment.
	 * 
	 * @return The fragment ID.
	 * @throws IOException
	 */
	public int getAnchorId() throws IOException {
		return this.anchorId;
	}

	/**
	 * Returns the data type of the current packet.
	 * 
	 * @return The packet type.
	 * @throws IOException
	 */
	public byte getType() throws IOException {
		return this.type;
	}

	/**
	 * Returns the progress.
	 * 
	 * @return The number of bytes read by the server.
	 * @throws IOException
	 */
	public long getProgress() throws IOException {
		return this.progress;
	}

	/**
	 * Returns the message value.
	 * 
	 * @return The message value.
	 * @throws IOException
	 */
	public String[] getArgs() throws IOException {
		return this.args;
	}

	/**
	 * Returns the message.
	 * 
	 * @return The message string.
	 * @throws IOException
	 */
	public String getMessage() throws IOException {
		return this.message;
	}

	/**
	 * Returns the message code.
	 * 
	 * @return The message code.
	 * @throws IOException
	 */
	public short getCode() throws IOException {
		return this.code;
	}

	/**
	 * Retrieves data.
	 * 
	 * @param b
	 *            The buffer in which to store the data.
	 * @param off
	 *            The starting position in the buffer.
	 * @param len
	 *            The number of bytes the buffer can hold.
	 * @return The length of the retrieved data, or -1 if there is no data.
	 * @throws IOException
	 */
	public int read(byte[] b, int off, int len) throws IOException {
		if (this.data.remaining() <= 0) {
			return -1;
		}
		len = Math.min(len, this.data.remaining());
		this.data.get(b, off, len);
		return len;
	}
}