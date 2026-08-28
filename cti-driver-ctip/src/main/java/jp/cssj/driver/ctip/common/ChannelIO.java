package jp.cssj.driver.ctip.common;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * SocketChannelから各種データを取得します。
 * それぞれのメソッドは非ブロッキングI/Oに対して動作しますが、データの取得が完了するまでブロックします。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ChannelIO.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class ChannelIO {
	private final ByteChannel channel;
	private final long timeout;
	private final Selector rwselector, rselector, wselector;

	public ChannelIO(ByteChannel channel, long timeout) throws IOException {
		this.channel = channel;
		this.timeout = timeout;

		if (this.getSelectable().isBlocking()) {
			this.rwselector = this.rselector = this.wselector = null;
		} else {
			this.rwselector = this.getSelectable().provider().openSelector();
			this.getSelectable().register(this.rwselector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

			this.rselector = this.getSelectable().provider().openSelector();
			this.getSelectable().register(this.rselector, SelectionKey.OP_READ);

			this.wselector = this.getSelectable().provider().openSelector();
			this.getSelectable().register(this.wselector, SelectionKey.OP_WRITE);
		}
	}

	public ByteChannel getChannel() {
		return this.channel;
	}

	public SelectableChannel getSelectable() {
		return (SelectableChannel) this.channel;
	}

	public void close() throws IOException {
		try {
			if (this.rwselector != null) {
				this.rwselector.close();
			}
			if (this.rselector != null) {
				this.rselector.close();
			}
			if (this.wselector != null) {
				this.wselector.close();
			}
		} finally {
			this.channel.close();
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

	/**
	 * 読み込みまたは書き込みが可能になるまで待ちます。
	 * 
	 * @return チャンネルの状態のキー。
	 * @throws IOException
	 */
	public SelectionKey rwselect() throws IOException {
		if (this.rwselector.select(this.timeout) <= 0) {
			throw new IOException("Read-write timeout");
		}
		SelectionKey key = this.rwselector.selectedKeys().iterator().next();
		this.rwselector.selectedKeys().clear();
		return key;
	}

	/**
	 * バッファがいっぱいになるまでデータを読み込みます。
	 * 
	 * @param dest
	 * @throws IOException
	 */
	public void readAll(ByteBuffer dest) throws IOException {
		dest.position(0);
		do {
			if (this.rselector != null) {
				if (this.rselector.select(this.timeout) <= 0) {
					throw new IOException("Read timeout");
				}
				this.rselector.selectedKeys().clear();
			}
			if (this.channel.read(dest) == -1) {
				throw new EOFException();
			}
		} while (dest.remaining() > 0);
	}

	/**
	 * バッファが空になるまでデータを書き込みます。
	 * 
	 * @param src
	 * @throws IOException
	 */
	public void writeAll(ByteBuffer src) throws IOException {
		src.position(0);
		do {
			if (this.wselector != null) {
				if (this.wselector.select(this.timeout) <= 0) {
					throw new IOException("Write timeout");
				}
				this.wselector.selectedKeys().clear();
			}
			this.channel.write(src);
		} while (src.remaining() > 0);
	}

	/**
	 * 文字列をバイト列に変換します。 null文字列は空文字列として変換します。
	 * 
	 * @param str
	 * @param encoding
	 * @return 変換後のバイト列。
	 * @throws IOException
	 */
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