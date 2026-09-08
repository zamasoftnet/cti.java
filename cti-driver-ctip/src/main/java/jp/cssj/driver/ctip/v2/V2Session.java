package jp.cssj.driver.ctip.v2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.AbstractCTISession;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.SequentialOutput;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V2Session.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class V2Session extends AbstractCTISession implements CTISession {
	public static final int BUFFER_SIZE = 8192;

	private final byte[] writeBuff = new byte[BUFFER_SIZE];

	private final byte[] readBuff = new byte[BUFFER_SIZE];

	protected final String encoding;

	protected final URI uri;

	protected final String user, password;

	protected volatile V2ContentProducer producer = null;

	protected volatile V2RequestConsumer request = null;

	protected Results results = null;

	protected SourceResolver resolver = null;

	protected MessageHandler messageHandler = null;

	protected ProgressListener progressListener = null;

	// 1=変換準備OK, 2=変換中, 3=クローズ
	protected volatile int state = 1;
	private final java.util.concurrent.locks.ReentrantLock responseLock = new java.util.concurrent.locks.ReentrantLock();

	protected FragmentedOutput builder = null;

	private void closeBuilder() throws IOException {
		FragmentedOutput builder = this.builder;
		this.builder = null;
		if (builder != null) {
			builder.close();
		}
	}

	public V2Session(URI uri, String encoding, String user, String password) throws IOException {
		this.uri = uri;
		this.encoding = encoding;
		this.user = user == null ? "" : user;
		this.password = password == null ? "" : password;
	}

	protected void init() throws IOException {
		// 認証
		if (this.producer == null) {
			V2ContentProducer producer;
			if (this.uri.getScheme().equals("ctips")) {
				producer = new TLSV2ContentProducer(this.uri, this.encoding);
			} else {
				producer = new V2ContentProducer(this.uri, this.encoding);
			}
			V2RequestConsumer request = (V2RequestConsumer) producer.connect(this.user, this.password);
			request.setCTIPSession(this);
			this.producer = producer;
			this.request = request;
		}
	}

	public InputStream getServerInfo(URI uri) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.serverInfo(uri);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (;;) {
			this.producer.next();
			byte type = this.producer.getType();
			if (type == V2ServerPackets.EOF) {
				break;
			}
			if (type != V2ServerPackets.DATA) {
				throw new IOException("不正なパケットタイプです: " + type);
			}
			for (int len = this.producer.read(this.readBuff, 0, this.readBuff.length); len != -1; len = this.producer
					.read(this.readBuff, 0, this.readBuff.length)) {
				out.write(this.readBuff, 0, len);
			}
		}
		return new ByteArrayInputStream(out.toByteArray());
	}

	public void setResults(Results results) throws IOException {
		this.results = results;
	}

	public void setMessageHandler(MessageHandler eh) {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.messageHandler = eh;
	}

	public void setProgressListener(ProgressListener l) {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.progressListener = l;
	}

	public void property(String key, String value) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.property(key, value);
	}

	public OutputStream resource(SourceMetadata metaSource) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.startResource(metaSource.getURI(), metaSource.getMimeType(), metaSource.getEncoding(),
				metaSource.getLength());
		return new V2RequestConsumerOutputStream(this.request);
	}

	public void resource(Source source) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.startResource(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength());
		try {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.writeBuff, 0, this.writeBuff.length); len != -1; len = in
						.read(this.writeBuff, 0, this.writeBuff.length)) {
					this.request.data(this.writeBuff, 0, len);
				}
			}
		} finally {
			this.request.eof();
		}
	}

	protected boolean buildNext() throws IOException, TranscoderException {
        responseLock.lock();
        try {
            if (this.state != 2) { return false; }
            this.producer.next();
            return processResponse();
        } finally { responseLock.unlock(); }
    }

    /** Upload polling never blocks on an incomplete frame or another response consumer. */
    boolean canPollResponse() {
        return state == 2 && !responseLock.isLocked();
    }

    boolean pollResponse() throws IOException {
        if (state != 2 || responseLock.isHeldByCurrentThread() || !responseLock.tryLock()) { return false; }
        try {
            if (state != 2 || !producer.pollNext()) { return false; }
            processResponse();
            return true;
        } finally { responseLock.unlock(); }
    }

    private boolean processResponse() throws IOException {
        if (state != 2) { return false; }
		// System.err.println("type="+Integer.toHexString(this.producer.getType()));
		boolean serial = false;
		switch (this.producer.getType()) {
		case V2ServerPackets.START_DATA: {
			this.closeBuilder();
			URI uri = this.producer.getURI();
			String mimeType = this.producer.getMimeType();
			String encoding = this.producer.getEncoding();
			long length = this.producer.getLength();
			SourceMetadata metaSource = new SimpleSourceMetadata(uri, mimeType, encoding, length);
			this.builder = this.results.nextBuilder(metaSource);
		}
			break;

		case V2ServerPackets.BLOCK_DATA: {
			assert this.builder != null;
			assert !serial;
			// 結果データ
			int blockId = this.producer.getBlockId();
			for (int len = this.producer.read(this.readBuff, 0, this.readBuff.length); len != -1; len = this.producer
					.read(this.readBuff, 0, this.readBuff.length)) {
				this.builder.write(blockId, this.readBuff, 0, len);
			}
		}
			break;

		case V2ServerPackets.ADD_BLOCK: {
			assert this.builder != null;
			assert !serial;
			this.builder.addFragment();
		}
			break;

		case V2ServerPackets.INSERT_BLOCK: {
			assert this.builder != null;
			assert !serial;
			int anchorId = this.producer.getAnchorId();
			this.builder.insertFragmentBefore(anchorId);
		}
			break;

		case V2ServerPackets.CLOSE_BLOCK: {
			assert this.builder != null;
			assert !serial;
			int anchorId = this.producer.getAnchorId();
			this.builder.finishFragment(anchorId);
		}
			break;

		case V2ServerPackets.MESSAGE: {
			if (this.messageHandler != null) {
				short code = this.producer.getCode();
				String mes = this.producer.getMessage();
				String[] args = this.producer.getArgs();
				this.messageHandler.message(code, args, mes);
			}
		}
			break;

		case V2ServerPackets.MAIN_LENGTH: {
			if (this.progressListener != null) {
				long sourceLength = this.producer.getLength();
				this.progressListener.sourceLength(sourceLength);
			}
		}
			break;

		case V2ServerPackets.MAIN_READ: {
			if (this.progressListener != null) {
				long serverRead = this.producer.getLength();
				this.progressListener.progress(serverRead);
			}
		}
			break;

		case V2ServerPackets.DATA: {
			// 結果データ
			assert this.builder != null;
			if (this.builder instanceof SequentialOutput) {
				SequentialOutput builder = (SequentialOutput) this.builder;
				for (int len = this.producer.read(this.readBuff, 0,
						this.readBuff.length); len != -1; len = this.producer.read(this.readBuff, 0,
								this.readBuff.length)) {
					builder.write(this.readBuff, 0, len);
				}
			} else {
				if (!serial) {
					this.builder.addFragment();
				}
				for (int len = this.producer.read(this.readBuff, 0,
						this.readBuff.length); len != -1; len = this.producer.read(this.readBuff, 0,
								this.readBuff.length)) {
					this.builder.write(0, this.readBuff, 0, len);
				}
			}
			if (!serial) {
				serial = true;
			}
		}
			break;

		case V2ServerPackets.RESOURCE_REQUEST: {
			// リソース要求。再入したメイン送信の入力配列を上書きしない。
            byte[] resourceBuffer = new byte[BUFFER_SIZE];
			URI uri = this.producer.getURI();
			if (this.resolver != null) {
				Source source;
				try {
					source = this.resolver.resolve(uri);
				} catch (IOException e) {
					this.request.missingResource(uri);
					source = null;
				}
				if (source != null) {
					try {
						if (source.exists()) {
							this.request.startResource(source.getURI(), source.getMimeType(), source.getEncoding(),
									source.getLength());
							try (InputStream in = source.getInputStream()) {
								try (OutputStream out = new V2RequestConsumerOutputStream(this.request)) {
									for (int len = in.read(resourceBuffer); len != -1; len = in.read(resourceBuffer)) {
										out.write(resourceBuffer, 0, len);
									}
								}
							}
						} else {
							this.request.missingResource(uri);
						}
					} finally {
						this.resolver.release(source);
					}
				}
			} else {
				this.request.missingResource(uri);
			}
		}
			break;

		case V2ServerPackets.EOF:
			this.closeBuilder();
			this.results.end();
		case V2ServerPackets.NEXT: {
			this.finishResponse();
		}
			return false;

		case V2ServerPackets.ABORT: {
			byte state;
			if (this.producer.getMode() == 0) {
				this.closeBuilder();
				this.results.end();
				state = TranscoderException.STATE_READABLE;
			} else {
				state = TranscoderException.STATE_BROKEN;
			}
			this.builder = null;
			this.finishResponse();
			throw new TranscoderException(state, this.producer.getCode(), this.producer.getArgs(),
					this.producer.getMessage());
		}

		default:
			throw new IOException("不正なレスポンスです: " + Integer.toHexString(this.producer.getType()));
		}
		return true;
	}

	public OutputStream transcode(SourceMetadata metaSource) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.startMain(metaSource.getURI(), metaSource.getMimeType(), metaSource.getEncoding(),
				metaSource.getLength());
		this.beginResponse();
		return new V2RequestConsumerOutputStream(this.request) {
			boolean closed = false;

			public void close() throws IOException {
				if (this.closed) {
					return;
				}
				this.closed = true;
				try {
					super.close();
				} finally {
					V2Session.this.next();
				}
			}
		};
	}

	public void transcode(URI uri) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		this.request.serverMain(uri);
		this.beginResponse();
		this.next();
	}

	public void transcode(Source source) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		this.init();
		try (OutputStream out = this.transcode(new SimpleSourceMetadata(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength()))) {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.writeBuff); len != -1; len = in.read(this.writeBuff)) {
					out.write(this.writeBuff, 0, len);
				}
			}
		}
	}

	protected void next() throws IOException {
		try {
			while (this.buildNext()) {
				// do nothing
			}
		} finally {
			this.finishResponse();
		}
	}

	public void setContinuous(boolean continuous) throws IOException {
		this.init();
		this.request.continuous(continuous);
	}

	public void join() throws IOException {
		this.request.join();
		this.beginResponse();
		this.next();
	}

	public void setSourceResolver(SourceResolver resolver) throws IOException {
		this.resolver = resolver;
		this.init();
		this.request.clientResource(resolver != null);
	}

	public void sendResource(Source source) throws IOException {
		this.init();
		try (OutputStream out = this.resource(new SimpleSourceMetadata(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength()))) {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.writeBuff); len != -1; len = in.read(this.writeBuff)) {
					out.write(this.writeBuff, 0, len);
				}
			}
		}
	}

	public void abort(byte mode) throws IOException {
		this.request.abort((byte) (mode - 1));
	}

	public void reset() throws IOException {
		if (this.request != null) {
			this.request.reset();
		}
		this.resolver = null;
		this.results = null;
		this.finishResponse();
		this.builder = null;
	}

    private synchronized void finishResponse() {
        if (this.state != 3) { this.state = 1; }
    }

    private synchronized void beginResponse() throws IOException {
        if (this.state == 3) { throw new java.nio.channels.ClosedChannelException(); }
        this.state = 2;
    }

	public void close() throws IOException {
        synchronized (this) {
            if (this.state == 3) { return; }
            this.state = 3;
        }
		if (this.producer != null) {
			try {
				this.request.close();
			} finally {
				this.producer.close();
			}
		}
	}
}
