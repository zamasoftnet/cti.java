package jp.cssj.server.socket.ctip.v2;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jp.cssj.cti2.CTIDriver;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.helpers.CTIMessageHelper;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import jp.cssj.driver.ctip.common.ChannelIO;
import jp.cssj.driver.ctip.v2.V2ClientPackets;
import jp.cssj.driver.ctip.v2.V2ServerPackets;
import jp.cssj.driver.ctip.v2.V2Session;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.cache.CachedSourceResolver;
import net.zamasoft.zstream.resolver.util.URIHelper;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.SequentialOutput;
import jp.cssj.server.socket.ProtocolProcessor;
import jp.cssj.server.socket.ctip.helpers.ResponseConsumer;
import jp.cssj.server.socket.ctip.helpers.ServerMessageHandler;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V2ProtocolProcessor.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class V2ProtocolProcessor implements ResponseConsumer, ProtocolProcessor, SequentialOutput, Results, ProgressListener {
	private static final Logger LOG = Logger.getLogger(V2ProtocolHandler.class.getName());

	private final byte[] buff = new byte[V2Session.BUFFER_SIZE];

	private final URI uri;

	private final CTIDriver driver;

	private CTISession session;

	private InputStream in;

	private DataOutputStream out;

	private String charset;

	private int bufferLength = 0;

	private int cursorId = -1;

	private long srcLength = -1L, srcRead = -1L, prevSrcRead = -1L;

	private V2RequestProducer request = null;

	private ClientSourceResolver clientResolver = null;

	private List<MessageFilter> messageFilters = new ArrayList<MessageFilter>();

	private static class MessageFilter {
		private final char[] pattern = new char[4];
		private final boolean include;

		MessageFilter(String pattern, boolean include) {
			pattern = pattern.toUpperCase();
			for (int i = 0; i < this.pattern.length; ++i) {
				this.pattern[i] = pattern.charAt(i);
			}
			this.include = include;
		}

		boolean match(String code) {
			for (int i = 0; i < this.pattern.length; ++i) {
				if (this.pattern[i] == '?') {
					continue;
				}
				if (this.pattern[i] == code.charAt(i)) {
					continue;
				}
				return false;
			}
			return true;
		}
	}

	private class ClientSourceResolver extends CachedSourceResolver {
		protected URI mainURI = null;

		public Source resolve(URI uri) throws IOException, FileNotFoundException {
			if (uri.equals(this.mainURI)) {
				return super.resolve(uri);
			}

			// data:スキームは除外する
			if ("data".equalsIgnoreCase(uri.getScheme())) {
				return super.resolve(uri);
			}

			// リソース要求パケット送信
			V2ProtocolProcessor v2pp = V2ProtocolProcessor.this;
			byte[] uriBytes = ChannelIO.toBytes(uri.toString(), v2pp.charset);
			{
				int length = uriBytes.length;
				if (length > Short.MAX_VALUE) {
					length = Short.MAX_VALUE;
				}
				int payload = 1 + 2 + length;
				v2pp.out.writeInt(payload);
				v2pp.out.writeByte(V2ServerPackets.RESOURCE_REQUEST);
				v2pp.out.writeShort((short) length);
				v2pp.out.write(uriBytes, 0, length);
			}

			// クライアントから送られるまで待つ
			v2pp.request.next();
			byte type = v2pp.request.getType();
			if (type == V2ClientPackets.MISSING_RESOURCE) {
				throw new FileNotFoundException(uri.toString());
			}
			if (type != V2ClientPackets.START_RESOURCE) {
				throw new IllegalStateException(uri.toString());
			}

			String uriStr = v2pp.request.getURI();
			try {
				uri = URIHelper.create(charset, uriStr);
			} catch (URISyntaxException e) {
				short code = CTIMessageCodes.WARN_BAD_RESOURCE_URI;
				String[] args = new String[] { uriStr };
				message(code, args, CTIMessageHelper.toString(code, args));
				uri = URI.create(".");
			}
			String mimeType = v2pp.request.getMimeType();
			String encoding = v2pp.request.getEncoding();
			long length = v2pp.request.getLength();
			v2pp.request.next();
			InputStream in = new V2RequestProducerInputStream(v2pp.request);
			try (StreamSource source = new StreamSource(uri, in, mimeType, encoding, length)) {
				super.putSource(source);
			}
			return super.resolve(uri);
		}
	}

	public V2ProtocolProcessor(URI uri, CTIDriver driver) throws IOException {
		this.uri = uri;
		this.driver = driver;
	}

	public void process(Socket socket, InputStream in, OutputStream out, String firstLine) throws IOException {
		this.in = in;
		this.out = new DataOutputStream(out);
		this.charset = firstLine.substring(firstLine.indexOf(' ') + 1);
		V2RequestProducer request = new V2RequestProducer(this.charset, this.in);

		// 認証前
		BufferedReader reader = new BufferedReader(new InputStreamReader(this.in, this.charset));
		String line = reader.readLine();
		int colon = line.indexOf(':');
		if (colon == -1) {
			throw new IOException("不正なリクエスト");
		}
		Map<String, String> props = new HashMap<String, String>();
		String method = line.substring(0, colon);

		if (method.equals("PLAIN")) {
			int lf = line.indexOf(' ', colon + 2);
			if (lf != -1) {
				props.put("user", line.substring(colon + 2, lf));
				props.put("password", line.substring(lf + 1));
			}
		} else if (method.equals("OPTIONS")) {
			String[] options = line.substring(colon + 2).split("&");
			for (int i = 0; i < options.length; ++i) {
				String[] pair = options[i].split("=");
				props.put(pair[0], pair.length <= 1 ? "" : pair[1]);
			}

		} else {
			throw new IOException("不正なリクエスト");
		}
		try {
			props.put("remote-addr", socket.getInetAddress().getHostAddress());
			this.session = this.driver.getSession(this.uri, props);
		} catch (SecurityException e) {
			this.out.write("NG \n".getBytes(this.charset));
			return;
		}
		this.out.write("OK \n".getBytes(this.charset));

		// 認証後
		try {
			MessageHandler messageHandler = new ServerMessageHandler(this);
			this.session.setMessageHandler(messageHandler);

			request.next();
			FOR: for (;;) {
				// System.err.println(Integer.toHexString(request.getType()));
				switch (request.getType()) {
				case V2ClientPackets.PROPERTY:
					// プロパティ受信
					String name = request.getName();
					if (name != null && name.length() > 0) {
						String value = request.getValue();
						this.session.property(name, value);
						if (name.charAt(0) == 'p') {
							if (name.equals("processing.include-message")) {
								this.messageFilters.add(new MessageFilter(value, true));
							} else if (name.equals("processing.exclude-message")) {
								this.messageFilters.add(new MessageFilter(value, false));
							}
						}
					}

					request.next();
					break;

				case V2ClientPackets.START_MAIN: {
					// パイプライン変換開始
					URI uri;
					String uriStr = request.getURI();
					try {
						uri = URIHelper.create(this.charset, uriStr);
					} catch (URISyntaxException e) {
						short code = CTIMessageCodes.WARN_BAD_BASE_URI;
						String[] args = new String[] { uriStr };
						message(code, args, CTIMessageHelper.toString(code, args));
						uri = URI.create(".");
					}
					String mimeType = request.getMimeType();
					String encoding = request.getEncoding();
					long length = request.getLength();
					request.next();
					V2RequestProducerInputStream min = new V2RequestProducerInputStream(request);
					Source source = new StreamSource(uri, min, mimeType, encoding, length);
					this.srcLength = this.srcRead = this.prevSrcRead = -1L;
					this.session.setProgressListener(this);
					this.session.setResults(this);
					try {
						if (this.clientResolver != null) {
							this.clientResolver.putSource(source);
							this.request = request;
							try {
								this.clientResolver.mainURI = uri;
								this.session.transcode(uri);
							} finally {
								this.request = null;
								this.clientResolver.mainURI = null;
							}
						} else {
							this.session.transcode(source);
						}
						this.next();
					} catch (TranscoderException e) {
						// 中断
						switch (e.getState()) {
						case TranscoderException.STATE_BROKEN:
							this.abort((byte) 1, e.getCode(), e.getArgs(), e.getMessage());
							break;
						case TranscoderException.STATE_READABLE:
							this.eof();
							this.abort((byte) 0, e.getCode(), e.getArgs(), e.getMessage());
							break;
						default:
							throw new IllegalStateException();
						}
					}
					request.next();
				}
					break;

				case V2ClientPackets.SERVER_MAIN: {
					// サーバー側データ変換
					URI uri;
					String uriStr = request.getURI();
					try {
						uri = URIHelper.create(this.charset, uriStr);
					} catch (URISyntaxException e) {
						short code = CTIMessageCodes.ERROR_BAD_DOCUMENT_URI;
						String[] args = new String[] { uriStr };
						String mes = CTIMessageHelper.toString(code, args);
						message(code, args, mes);
						throw new TranscoderException(code, args, mes);
					}
					if (LOG.isLoggable(Level.FINE)) {
						LOG.fine("'" + uri + "'を変換します");
					}
					this.srcLength = this.srcRead = this.prevSrcRead = -1L;
					this.session.setProgressListener(this);
					this.session.setResults(this);
					this.request = request;
					try {
						this.session.transcode(uri);
						this.next();
					} catch (TranscoderException e) {
						// 中断
						switch (e.getState()) {
						case TranscoderException.STATE_BROKEN:
							this.abort((byte) 1, e.getCode(), e.getArgs(), e.getMessage());
							break;
						case TranscoderException.STATE_READABLE:
							this.eof();
							this.abort((byte) 0, e.getCode(), e.getArgs(), e.getMessage());
							break;
						default:
							throw new IllegalStateException();
						}
					} finally {
						this.request = null;
					}
					request.next();
					break;
				}

				case V2ClientPackets.CLIENT_RESOURCE: {
					if (request.getMode() == 1) {
						this.clientResolver = new ClientSourceResolver();
					} else {
						this.clientResolver = null;
					}
					this.session.setSourceResolver(this.clientResolver);
					request.next();
				}
					break;

				case V2ClientPackets.CONTINUOUS:
					this.session.setContinuous(request.getMode() == 1);
					request.next();
					break;

				case V2ClientPackets.START_RESOURCE: {
					URI uri;
					String uriStr = request.getURI();
					try {
						uri = URIHelper.create(this.charset, uriStr);
					} catch (URISyntaxException e) {
						short code = CTIMessageCodes.WARN_BAD_RESOURCE_URI;
						String[] args = new String[] { uriStr };
						message(code, args, CTIMessageHelper.toString(code, args));
						uri = URI.create(".");
					}
					String mimeType = request.getMimeType();
					String encoding = request.getEncoding();
					long length = request.getLength();
					request.next();
					InputStream rin = new V2RequestProducerInputStream(request);
					this.session.resource(new StreamSource(uri, rin, mimeType, encoding, length));
					request.next();
				}
					break;

				case V2ClientPackets.DATA:
					// ここでDATAチャンクが来るのは、
					// 処理が中断されて残りのデータが送られている場合なので無視する。
				case V2ClientPackets.MISSING_RESOURCE:
				case V2ClientPackets.EOF:
					request.next();
					break;

				case V2ClientPackets.ABORT:
					this.session.abort((byte) (this.request.getMode() + 1));
					request.next();
					break;

				case V2ClientPackets.JOIN:
					this.request = request;
					try {
						this.session.join();
					} finally {
						this.request = null;
					}
					request.next();
					break;

				case V2ClientPackets.RESET:
					this.reset();
					this.session.reset();
					request.next();
					break;

				case V2ClientPackets.CLOSE:
					this.reset();
					break FOR;

				case V2ClientPackets.SERVER_INFO: {
					URI uri;
					String uriStr = request.getURI();
					try {
						uri = URIHelper.create(this.charset, uriStr);
					} catch (URISyntaxException e) {
						uri = URI.create(".");
					}
					try (InputStream infoIn = this.session.getServerInfo(uri)) {
						byte[] ibuf = new byte[1024];
						for (int len = infoIn.read(ibuf); len != -1; len = infoIn.read(ibuf)) {
							byte[] buff = new String(ibuf, 0, len).getBytes(this.charset);
							this.data(buff, 0, buff.length);
						}
					}
					this.eof();
					request.next();
				}
					break;
				default:
					throw new IOException("不正なリクエストです: " + Integer.toHexString(request.getType()));
				}
			}
		} finally {
			this.session.close();
		}
	}

	public void finishFragment(int id) throws IOException {
		this.flush(this.cursorId);
		this.out.writeInt(1 + 4);
		this.out.writeByte(V2ServerPackets.CLOSE_BLOCK);
		this.out.writeInt(id);
		this.out.flush();
	}

	protected void abort(byte mode, short code, String[] args, String message) throws IOException {
		message = stringLimit(message);
		byte[] messageBytes = ChannelIO.toBytes(message, this.charset);
		int payload = 1 + 1 + 2 + 2 + messageBytes.length;

		byte[][] argsBytes = null;
		if (args != null) {
			argsBytes = new byte[args.length][];
			for (int i = 0; i < args.length; ++i) {
				String arg = stringLimit(args[i]);
				argsBytes[i] = ChannelIO.toBytes(arg, this.charset);
				payload += 2 + argsBytes[i].length;
			}
		}

		this.out.writeInt(payload);
		this.out.writeByte(V2ServerPackets.ABORT);
		this.out.writeByte(mode);
		this.out.writeShort(code);
		this.out.writeShort((short) messageBytes.length);
		this.out.write(messageBytes);
		if (args != null) {
			for (int i = 0; i < args.length; ++i) {
				this.out.writeShort((short) argsBytes[i].length);
				this.out.write(argsBytes[i]);
			}
		}
	}

	protected void mainLength(long srcLength) throws IOException {
		this.out.writeInt(1 + 8);
		this.out.writeByte(V2ServerPackets.MAIN_LENGTH);
		this.out.writeLong(srcLength);
		this.out.flush();
	}

	protected void mainRead(long srcRead) throws IOException {
		this.out.writeInt(1 + 8);
		this.out.writeByte(V2ServerPackets.MAIN_READ);
		this.out.writeLong(srcRead);
		this.out.flush();
	}

	protected void eof() throws IOException {
		this.out.writeInt(1);
		this.out.writeByte(V2ServerPackets.EOF);
		this.out.flush();
		this.cursorId = -2;
	}

	protected void next() throws IOException {
		if (this.cursorId == -2) {
			return;
		}
		this.out.writeInt(1);
		this.out.writeByte(V2ServerPackets.NEXT);
		this.out.flush();
	}

	public void sourceLength(long sourceLength) {
		this.srcLength = sourceLength;
	}

	public void progress(long serverRead) {
		this.srcRead = serverRead;
	}

	public void finish() throws IOException {
		if (this.cursorId == -1) {
			this.flush();
		} else {
			this.flush(this.cursorId);
		}
	}

	public void end() throws IOException {
		this.eof();
	}

	public void dispose() {
		// ignore
	}

	public void close() {
		if (this.out != null) {
			try {
				this.out.close();
			} catch (Exception e) {
				// ignore
			}
			this.out = null;
		}
		if (this.in != null) {
			try {
				this.in.close();
			} catch (Exception e) {
				// ignore
			}
			this.in = null;
		}
		this.reset();
	}

	protected void reset() {
		this.bufferLength = 0;
		if (this.clientResolver != null) {
			this.clientResolver.dispose();
			this.clientResolver = null;
		}
		this.messageFilters.clear();
		this.cursorId = -1;
	}

	public void addFragment() throws IOException {
		this.out.writeInt(1);
		this.out.writeByte(V2ServerPackets.ADD_BLOCK);
	}

	public void insertFragmentBefore(int anchorId) throws IOException {
		this.out.writeInt(1 + 4);
		this.out.writeByte(V2ServerPackets.INSERT_BLOCK);
		this.out.writeInt(anchorId);
	}

	public void write(int id, byte[] b, int off, int len) throws IOException {
		if (this.srcLength != -1L) {
			this.mainLength(this.srcLength);
			this.srcLength = -1L;
		}
		if (this.srcRead != this.prevSrcRead) {
			this.mainRead(this.srcRead);
			this.prevSrcRead = this.srcRead;
		}

		for (int i = 0; i < len; ++i) {
			if (this.bufferLength >= this.buff.length || this.cursorId != id) {
				this.flush(id);
			}
			this.buff[this.bufferLength++] = b[i + off];
		}
	}

	public void write(byte[] b, int off, int len) throws IOException {
		assert this.cursorId == -1;
		if (this.srcLength != -1L) {
			this.mainLength(this.srcLength);
			this.srcLength = -1L;
		}
		if (this.srcRead != this.prevSrcRead) {
			this.mainRead(this.srcRead);
			this.prevSrcRead = this.srcRead;
		}

		for (int i = 0; i < len; ++i) {
			if (this.bufferLength >= this.buff.length) {
				this.flush();
			}
			this.buff[this.bufferLength++] = b[i + off];
		}
	}

	protected void data(byte[] b, int off, int len) throws IOException {
		int payload = 1 + len;
		this.out.writeInt(payload);
		this.out.writeByte(V2ServerPackets.DATA);
		this.out.write(b, off, len);
	}

	protected static String stringLimit(String str) {
		// 3000字に制限
		if (str != null && str.length() > 3000) {
			str = str.substring(0, 3000 - 3) + "...";
		}
		return str;
	}

	public void message(short code, String[] args, String message) throws IOException {
		if (!this.messageFilters.isEmpty()) {
			String codeStr = Integer.toHexString(code).toUpperCase();
			if (codeStr.length() == 4) {
				for (int i = 0; i < this.messageFilters.size(); ++i) {
					MessageFilter filter = (MessageFilter) this.messageFilters.get(i);
					if (filter.match(codeStr)) {
						if (filter.include) {
							break;
						} else {
							return;
						}
					}
				}
			}
		}

		message = stringLimit(message);
		byte[] messageBytes = ChannelIO.toBytes(message, this.charset);
		int payload = 1 + 2 + 2 + messageBytes.length;

		byte[][] argsBytes = null;
		if (args != null) {
			argsBytes = new byte[args.length][];
			for (int i = 0; i < args.length; ++i) {
				String arg = stringLimit(args[i]);
				argsBytes[i] = ChannelIO.toBytes(arg, this.charset);
				payload += 2 + argsBytes[i].length;
			}
		}

		this.out.writeInt(payload);
		this.out.writeByte(V2ServerPackets.MESSAGE);
		this.out.writeShort(code);
		this.out.writeShort((short) messageBytes.length);
		this.out.write(messageBytes);
		if (args != null) {
			for (int i = 0; i < args.length; ++i) {
				this.out.writeShort((short) argsBytes[i].length);
				this.out.write(argsBytes[i]);
			}
		}
	}

	protected void flush(int newId) throws IOException {
		if (this.bufferLength > 0) {
			int payload = 1 + 4 + this.bufferLength;
			this.out.writeInt(payload);
			this.out.writeByte(V2ServerPackets.BLOCK_DATA);
			this.out.writeInt(this.cursorId);
			this.out.write(this.buff, 0, this.bufferLength);
			this.bufferLength = 0;
		}
		this.cursorId = newId;
	}

	protected void flush() throws IOException {
		if (this.bufferLength > 0) {
			int payload = 1 + this.bufferLength;
			this.out.writeInt(payload);
			this.out.writeByte(V2ServerPackets.DATA);
			this.out.write(this.buff, 0, this.bufferLength);
			this.bufferLength = 0;
		}
	}

	public PositionInfo getPositionInfo() {
		throw new UnsupportedOperationException();
	}

	public boolean supportsPositionInfo() {
		return false;
	}

	// Resultsのメソッド

	public boolean hasNext() {
		return true;
	}

	public FragmentedOutput nextBuilder(SourceMetadata metaSource) throws IOException {
		this.cursorId = -1;
		URI uri = metaSource.getURI();
		String mimeType = metaSource.getMimeType();
		String encoding = metaSource.getEncoding();
		long length = metaSource.getLength();
		byte[] uriBytes = ChannelIO.toBytes(uri.toString(), this.charset);
		byte[] mimeTypeBytes = ChannelIO.toBytes(mimeType, this.charset);
		byte[] encodingBytes = ChannelIO.toBytes(encoding, this.charset);

		int payload = 1 + 2 + uriBytes.length + 2 + mimeTypeBytes.length + 2 + encodingBytes.length + 8;
		this.out.writeInt(payload);
		this.out.write(V2ServerPackets.START_DATA);
		this.out.writeShort((short) uriBytes.length);
		this.out.write(uriBytes);
		this.out.writeShort((short) mimeTypeBytes.length);
		this.out.write(mimeTypeBytes);
		this.out.writeShort((short) encodingBytes.length);
		this.out.write(encodingBytes);
		this.out.writeLong(length);
		return this;
	}
}
