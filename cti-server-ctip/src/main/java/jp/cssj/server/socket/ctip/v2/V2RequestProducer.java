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
	 * 次のパケットへカーソルを移動します。
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
		// **長さは符号なし16bit**(2026-08-28)。符号付きで読むと32,767バイトを
		// 超える値が負になり、本体を読み飛ばさないままストリームが崩れる——
		// 48KBのinput.image-metrics(data:URI)を送ると、本文途中の':'を
		// 次のパケット種別として読み"Bad request: type 3a"で接続が落ちていた
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
	 * パケットのタイプを返します。
	 * 
	 * @return
	 */
	public byte getType() {
		return this.type;
	}

	/**
	 * プロパティ名を返します。
	 * 
	 * @return
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * プロパティの値を返します。
	 * 
	 * @return
	 */
	public String getValue() {
		return this.value;
	}

	/**
	 * データの仮想URIを返します。
	 * 
	 * @return
	 */
	public String getURI() {
		return this.uri;
	}

	/**
	 * データのMIME型を返します。
	 * 
	 * @return
	 */
	public String getMimeType() {
		return this.mimeType;
	}

	/**
	 * データのキャラクタ・エンコーディングを返します。
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
	 * データを受け取ります。
	 * 
	 * @param b
	 *            バイト列バッファ。
	 * @param off
	 *            受け取ったデータの書き込み開始位置。
	 * @param len
	 *            受け取るデータの最大長さ。
	 * @return 受け取ったデータのバイト数。
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