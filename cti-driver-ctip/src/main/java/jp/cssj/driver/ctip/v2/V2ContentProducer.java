package jp.cssj.driver.ctip.v2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;

import jp.cssj.driver.ctip.common.ChannelIO;
import net.zamasoft.zstream.resolver.util.URIHelper;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V2ContentProducer.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public class V2ContentProducer {
	protected final String charset;

	protected final URI serverURI;

	protected ChannelIO io;

	public V2ContentProducer(URI uri, String encoding) throws IOException {
		this.charset = encoding;
		this.serverURI = uri;
	}

	/**
	 * サーバーに接続し、リクエストを開始します。
	 * 
	 * @param user
	 * @param password
	 * @throws IOException
	 */
	public V2RequestConsumer connect(String user, String password) throws IOException {
		String host = this.serverURI.getHost();
		int port = this.serverURI.getPort();
		if (port == -1) {
			port = 8099;
		}

		long timeout = 0;
		String query = this.serverURI.getQuery();
		if (query != null) {
			String[] params = query.split("&");
			for (int i = 0; i < params.length; ++i) {
				if (params[0].startsWith("timeout=")) {
					timeout = Long.parseLong(params[0].substring(8));
				}
			}
		}

		InetSocketAddress address = new InetSocketAddress(host, port);
		ByteChannel channel = this.createChannel(address);
		this.io = new ChannelIO(channel, timeout);

		byte[] header = ("CTIP/2.0 " + this.charset + "\n").getBytes("ISO-8859-1");
		this.io.writeAll(ByteBuffer.wrap(header));

		String message = "PLAIN: " + user + " " + password + "\n";
		byte[] data = message.getBytes(this.charset);
		ByteBuffer src = ByteBuffer.allocate(data.length);
		src.put(data);
		this.io.writeAll(src);
		data = this.io.readBytes(4);
		String response = new String(data, this.charset);
		if (response.equals("NG \n")) {
			throw new SecurityException("認証に失敗しました");
		}
		if (!response.equals("OK \n")) {
			throw new IOException("不正なレスポンスです:" + response);
		}

		return new V2RequestConsumer(this.io, this.charset);
	}

	protected ByteChannel createChannel(InetSocketAddress address) throws IOException {
		SocketChannel socketChannel = SelectorProvider.provider().openSocketChannel();
		socketChannel.connect(address);
		socketChannel.configureBlocking(false);
		return socketChannel;
	}

	private byte type, mode;

	private int blockId, anchorId;

	private long length;

	private short code;

	private URI uri;

	private String mimeType;

	private String message;

	private String encoding;

	private List<String> args = new ArrayList<String>();

	private ByteBuffer data;

	private ByteBuffer destLong = ByteBuffer.allocate(8);

	private ByteBuffer destInt = ByteBuffer.allocate(4);

	private ByteBuffer destShort = ByteBuffer.allocate(2);

	private ByteBuffer destByte = ByteBuffer.allocate(1);

	protected void close() throws IOException {
		if (this.io != null) {
			this.io.close();
			this.io = null;
		}
	}

	/**
	 * 次のパケットにカーソルを移します。
	 * 
	 * @throws IOException
	 */
	public void next() throws IOException {
		int payload = this.io.readInt(this.destInt);
		this.type = this.io.readByte(this.destByte);
		// System.err.println(Integer.toHexString(this.type));
		switch (this.type) {
		case V2ServerPackets.START_DATA:
			try {
				this.uri = URIHelper.create(this.charset, this.io.readString(this.destShort, this.charset));
			} catch (URISyntaxException e) {
				throw new IOException(e.getMessage());
			}
			this.mimeType = this.io.readString(this.destShort, this.charset);
			if (this.mimeType.length() == 0) {
				this.mimeType = null;
			}
			this.encoding = this.io.readString(this.destShort, this.charset);
			if (this.encoding.length() == 0) {
				this.encoding = null;
			}
			this.length = this.io.readLong(this.destLong);
			break;

		case V2ServerPackets.BLOCK_DATA:
			this.blockId = this.io.readInt(this.destInt);
			payload -= 1 + 4;
			this.data = ByteBuffer.allocate(payload);
			this.io.readAll(this.data);
			this.data.position(0);
			break;

		case V2ServerPackets.ADD_BLOCK:
			break;

		case V2ServerPackets.INSERT_BLOCK:
		case V2ServerPackets.CLOSE_BLOCK:
			this.anchorId = this.io.readInt(this.destInt);
			break;

		case V2ServerPackets.MESSAGE:
			this.code = this.io.readShort(this.destShort);
			payload -= 1 + 2; {
			short len = this.io.readShort(this.destShort);
			byte[] buff = new byte[len];
			ByteBuffer dest = ByteBuffer.wrap(buff);
			this.io.readAll(dest);
			this.message = new String(buff, this.charset);
			payload -= 2 + len;
		}
			this.args.clear();
			while (payload > 0) {
				short len = this.io.readShort(this.destShort);
				byte[] buff = new byte[len];
				ByteBuffer dest = ByteBuffer.wrap(buff);
				this.io.readAll(dest);
				String arg = new String(buff, this.charset);
				this.args.add(arg);
				payload -= 2 + len;
			}

			break;
		case V2ServerPackets.ABORT:
			this.mode = this.io.readByte(this.destByte);
			this.code = this.io.readShort(this.destShort);
			payload -= 1 + 3; {
			short len = this.io.readShort(this.destShort);
			byte[] buff = new byte[len];
			ByteBuffer dest = ByteBuffer.wrap(buff);
			this.io.readAll(dest);
			this.message = new String(buff, this.charset);
			payload -= 2 + len;
		}
			while (payload > 0) {
				short len = this.io.readShort(this.destShort);
				byte[] buff = new byte[len];
				ByteBuffer dest = ByteBuffer.wrap(buff);
				this.io.readAll(dest);
				String arg = new String(buff, this.charset);
				this.args.add(arg);
				payload -= 2 + len;
			}

			break;

		case V2ServerPackets.MAIN_LENGTH:
		case V2ServerPackets.MAIN_READ:
			this.length = this.io.readLong(this.destLong);
			break;

		case V2ServerPackets.DATA:
			payload -= 1;
			this.data = ByteBuffer.allocate(payload);
			this.io.readAll(this.data);
			this.data.position(0);
			break;

		case V2ServerPackets.RESOURCE_REQUEST:
			try {
				this.uri = URIHelper.create(this.charset, this.io.readString(this.destShort, this.charset));
			} catch (URISyntaxException e) {
				throw new IOException(e.getMessage());
			}
			break;

		case V2ServerPackets.EOF:
		case V2ServerPackets.NEXT:
			break;

		default:
			throw new IOException("Bad response: type " + Integer.toHexString(this.type));
		}
	}

	/**
	 * 断片のIDを返します。
	 * 
	 * @return 断片のID。
	 * @throws IOException
	 */
	public int getBlockId() throws IOException {
		return this.blockId;
	}

	/**
	 * アンカーとなる断片のIDを返します。
	 * 
	 * @return 断片のID。
	 * @throws IOException
	 */
	public int getAnchorId() throws IOException {
		return this.anchorId;
	}

	/**
	 * 現在のパケットのデータのタイプを返します。
	 * 
	 * @return パケットのタイプ。
	 * @throws IOException
	 */
	public byte getType() throws IOException {
		return this.type;
	}

	/**
	 * 進行状況を返します。
	 * 
	 * @return バイト数。
	 * @throws IOException
	 */
	public long getLength() throws IOException {
		return this.length;
	}

	/**
	 * メッセージを返します。
	 * 
	 * @return メッセージの文字列。
	 * @throws IOException
	 */
	public String getMessage() throws IOException {
		return this.message;
	}

	/**
	 * メッセージの引数返します。
	 * 
	 * @return メッセージの引数。
	 * @throws IOException
	 */
	public String[] getArgs() throws IOException {
		return (String[]) this.args.toArray(new String[this.args.size()]);
	}

	/**
	 * データのURIを返します。
	 * 
	 * @return データのURI。
	 * @throws IOException
	 */
	public URI getURI() throws IOException {
		return this.uri;
	}

	/**
	 * データのMIME型を返します。
	 * 
	 * @return データのMIME型。
	 * @throws IOException
	 */
	public String getMimeType() throws IOException {
		return this.mimeType;
	}

	/**
	 * データのエンコーディングを返します。
	 * 
	 * @return データのエンコーディング。
	 * @throws IOException
	 */
	public String getEncoding() throws IOException {
		return this.encoding;
	}

	/**
	 * メッセージコードを返します。
	 * 
	 * @return メッセージコード。
	 * @throws IOException
	 */
	public short getCode() throws IOException {
		return this.code;
	}

	/**
	 * 中断処理のモードを返します。
	 * 
	 * @return 中断処理のモード。
	 * @throws IOException
	 */
	public byte getMode() throws IOException {
		return this.mode;
	}

	/**
	 * データを取得します。
	 * 
	 * @param b
	 *            データが格納されるバッファ。
	 * @param off
	 *            バッファの開始位置。
	 * @param len
	 *            バッファに格納可能なバイト数。
	 * @return 取得されたデータの長さ。データがない場合は-1。
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