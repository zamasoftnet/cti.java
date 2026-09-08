package jp.cssj.driver.ctip.v2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.io.EOFException;
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

	protected volatile ChannelIO io;
	protected long connectTimeout;

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
				if (params[i].startsWith("timeout=")) {
					timeout = Long.parseLong(params[i].substring(8));
				}
			}
		}

		this.connectTimeout = timeout;
		InetSocketAddress address = new InetSocketAddress(host, port);
		ByteChannel channel = this.createChannel(address);
		try {
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
        } catch (IOException | RuntimeException failure) {
            try {
                if (this.io != null) { this.io.close(); } else { channel.close(); }
            } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
	}

	protected ByteChannel createChannel(InetSocketAddress address) throws IOException {
		SocketChannel socketChannel = SelectorProvider.provider().openSocketChannel();
        try {
            socketChannel.configureBlocking(false);
            long deadline = ChannelIO.deadline(connectTimeout);
            if (!socketChannel.connect(address)) {
                while (!socketChannel.finishConnect()) {
                    ChannelIO.awaitReady(socketChannel, java.nio.channels.SelectionKey.OP_CONNECT, deadline);
                }
            }
            return socketChannel;
        } catch (IOException | RuntimeException failure) {
            try { socketChannel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
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

	private final ByteBuffer packetHeader = ByteBuffer.allocate(4);
    private ByteBuffer packet;

    protected void close() throws IOException {
        ChannelIO current = this.io;
        if (current != null) { current.close(); }
    }

    /** Read a complete frame, retaining partial header/body across nonblocking polls. */
    boolean pollNext() throws IOException {
        return receiveNext(false);
    }

    public void next() throws IOException {
        receiveNext(true);
    }

    private boolean receiveNext(boolean wait) throws IOException {
        long deadline = io.deadline();
        while (true) {
            ChannelIO.checkDeadline(deadline);
            ByteBuffer target = packet == null ? packetHeader : packet;
            if (target.hasRemaining()) {
                int count = io.readSome(target);
                if (count < 0) { throw new EOFException("EOF within CTIP response"); }
                if (count == 0) {
                    if (!wait) { return false; }
                    io.await(io.readOps(), deadline);
                    continue;
                }
                if (target.hasRemaining()) { continue; }
            }
            if (packet == null) {
                int length = packetHeader.getInt(0);
                if (length < 1) { throw new IOException("Invalid CTIP response length: " + length); }
                packet = ByteBuffer.allocate(length);
                continue;
            }
            ByteBuffer complete = packet;
            complete.flip();
            packet = null;
            packetHeader.clear();
            parse(complete);
            return true;
        }
    }

    private String string(ByteBuffer packet) throws IOException {
        int length = packet.getShort() & 0xffff;
        byte[] bytes = new byte[length];
        packet.get(bytes);
        return new String(bytes, charset);
    }

    private URI uri(ByteBuffer packet) throws IOException {
        try { return URIHelper.create(charset, string(packet)); }
        catch (URISyntaxException e) { throw new IOException(e.getMessage(), e); }
    }

    private void parse(ByteBuffer packet) throws IOException {
        try {
            this.type = packet.get();
            switch (this.type) {
            case V2ServerPackets.START_DATA:
                this.uri = uri(packet);
                this.mimeType = string(packet);
                if (mimeType.isEmpty()) { mimeType = null; }
                this.encoding = string(packet);
                if (encoding.isEmpty()) { encoding = null; }
                this.length = packet.getLong();
                break;
            case V2ServerPackets.BLOCK_DATA:
                this.blockId = packet.getInt();
                this.data = packet.slice();
                packet.position(packet.limit());
                break;
            case V2ServerPackets.DATA:
                this.data = packet.slice();
                packet.position(packet.limit());
                break;
            case V2ServerPackets.INSERT_BLOCK:
            case V2ServerPackets.CLOSE_BLOCK:
                this.anchorId = packet.getInt();
                break;
            case V2ServerPackets.MESSAGE:
            case V2ServerPackets.ABORT:
                if (type == V2ServerPackets.ABORT) { this.mode = packet.get(); }
                this.code = packet.getShort();
                this.message = string(packet);
                this.args.clear();
                while (packet.hasRemaining()) { this.args.add(string(packet)); }
                break;
            case V2ServerPackets.MAIN_LENGTH:
            case V2ServerPackets.MAIN_READ:
                this.length = packet.getLong();
                break;
            case V2ServerPackets.RESOURCE_REQUEST:
                this.uri = uri(packet);
                break;
            case V2ServerPackets.ADD_BLOCK:
            case V2ServerPackets.EOF:
            case V2ServerPackets.NEXT:
                break;
            default:
                throw new IOException("Bad response: type " + Integer.toHexString(type));
            }
            if (packet.hasRemaining()) { throw new IOException("Trailing bytes in CTIP response"); }
        } catch (BufferUnderflowException e) {
            throw new IOException("Incomplete CTIP response payload", e);
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