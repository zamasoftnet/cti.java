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
	 * 断片追加パケットです。
	 */
	public static final byte ADD = 1;

	/**
	 * 断片挿入パケットです。
	 * 
	 * getAnchorIdで直後の断片IDを得ることができます。
	 */
	public static final byte INSERT = 2;

	/**
	 * エラーメッセージパケットです。
	 * 
	 * getLevel,getMessageでエラーレベルとメッセージを得ることができます。
	 */
	public static final byte MESSAGE = 3;

	/**
	 * データパケットです。
	 * 
	 * getId,readで断片IDとデータを得ることができます。
	 */
	public static final byte DATA = 4;

	/**
	 * CSSの文法エラーなどの警告を表します。
	 */
	public static final byte ERROR_WARN = 1;

	/**
	 * リソースの取得失敗など、生成される文書の情報が欠落すようなエラーです。
	 */
	public static final byte ERROR_ERROR = 2;

	/**
	 * 処理の続行を妨げるような深刻なエラーです。
	 */
	public static final byte ERROR_FATAL = 3;

	protected final String encoding;

	protected final URI uri;

	protected ChannelIO io;

	public V1ContentProducer(URI uri, String encoding) throws IOException {
		// ctips: を v1 で受けると平文で接続してしまう(V1Session#rejectSecureScheme)
		V1Session.rejectSecureScheme(uri);
		this.encoding = encoding;
		this.uri = uri;
	}

	/**
	 * サーバーに接続し、リクエストを開始します。
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
	 * 次のパケットにカーソルを移します。
	 * 
	 * @return 終了パケットを受信した場合はfalse、それ以外はtrue。
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
	 * 断片のIDを返します。
	 * 
	 * @return 断片のID。
	 * @throws IOException
	 */
	public int getId() throws IOException {
		return this.id;
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
	 * @return サーバー側での読み込みバイト数。
	 * @throws IOException
	 */
	public long getProgress() throws IOException {
		return this.progress;
	}

	/**
	 * メッセージの値を返します。
	 * 
	 * @return メッセージの値。
	 * @throws IOException
	 */
	public String[] getArgs() throws IOException {
		return this.args;
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
	 * メッセージコードを返します。
	 * 
	 * @return メッセージコード。
	 * @throws IOException
	 */
	public short getCode() throws IOException {
		return this.code;
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