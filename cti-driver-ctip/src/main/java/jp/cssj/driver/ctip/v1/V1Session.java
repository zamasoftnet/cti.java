package jp.cssj.driver.ctip.v1;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.AbstractCTISession;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V1Session.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class V1Session extends AbstractCTISession implements CTISession {
	public static final int BUFFER_SIZE = 1024;

	private final byte[] buff1 = new byte[BUFFER_SIZE];

	private final byte[] buff2 = new byte[BUFFER_SIZE];

	protected final URI uri;

	protected final String encoding;

	protected final String user, password;

	protected V1ContentProducer producer = null;

	protected V1RequestConsumer request = null;

	protected Results results = null;

	protected FragmentedOutput builder = null;

	protected MessageHandler messageHandler = null;

	protected ProgressListener progressListener = null;

	protected int state = 1;

	protected long srcPos = 0L;

	public V1Session(URI uri, String encoding, String user, String password) throws IOException {
		this.uri = uri;
		this.encoding = encoding;
		this.user = user == null ? "" : user;
		this.password = password == null ? "" : password;
	}

	protected void init() throws IOException {
		// 認証
		if (this.producer == null) {
			this.producer = new V1ContentProducer(this.uri, this.encoding);
			this.request = (V1RequestConsumer) this.producer.connect();
			this.request.setCTIPSession(this);
			this.request.property("ctip.auth", "PLAIN:" + this.user + (char) 0x0A + this.password);
			this.producer.next();
			if (this.producer.getType() != V1ContentProducer.MESSAGE) {
				throw new IOException("不正なレスポンスです:" + this.producer.getType());
			}
			String[] response = this.producer.getArgs();
			if (!response[0].equals("OK")) {
				throw new SecurityException("認証に失敗しました:" + response);
			}
			this.srcPos = 0L;
		}
	}

	public InputStream getServerInfo(URI uri) throws IOException {
		return new ByteArrayInputStream("CTIP/1.0".getBytes("ISO-8859-1"));
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
		this.request.resource(metaSource.getURI(), metaSource.getMimeType(), metaSource.getEncoding());
		return new V1RequestConsumerOutputStream(this.request);
	}

	public void resource(Source source) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.resource(source.getURI(), source.getMimeType(), source.getEncoding());
		try (InputStream in = source.getInputStream()) {
			for (int len = in.read(this.buff1, 0, this.buff1.length); len != -1; len = in.read(this.buff1, 0,
					this.buff1.length)) {
				this.request.write(this.buff1, 0, len);
			}
		}
	}

	public OutputStream transcode(SourceMetadata metaSource) throws IOException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.main(metaSource.getURI(), metaSource.getMimeType(), metaSource.getEncoding());
		this.state = 2;
		this.builder = this.results.nextBuilder(null);
		return new V1RequestConsumerOutputStream(this.request) {
			public void close() throws IOException {
				try {
					V1Session.this.request.end();
					while (V1Session.this.buildNext()) {
						// do nothing
					}
					V1Session.this.builder.close();
				} finally {
					V1Session.this.builder = null;
					V1Session.this.state = 3;
				}
			}
		};
	}

	public void transcode(URI uri) throws IOException {
		this.property("ctip.main", uri.toString());
		this.state = 2;
		this.request.end();
		this.builder = this.results.nextBuilder(null);
		try {
			while (this.buildNext()) {
				// do nothing
			}
			this.builder.close();
		} finally {
			this.builder = null;
		}
		this.state = 3;
	}

	public void transcode(Source source) throws IOException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		this.init();
		try (OutputStream out = this.transcode(new SimpleSourceMetadata(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength()))) {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.buff1); len != -1; len = in.read(this.buff1)) {
					out.write(this.buff1, 0, len);
				}
			}
		}
	}

	public void setContinuous(boolean continuous) throws IOException {
		throw new UnsupportedOperationException();
	}

	public void join() throws IOException {
		throw new UnsupportedOperationException();
	}

	protected boolean buildNext() throws IOException {
		if (this.producer.next()) {
			switch (this.producer.getType()) {
			case V1ContentProducer.ADD: {
				this.builder.addFragment();
			}
				break;

			case V1ContentProducer.INSERT: {
				int anchorId = this.producer.getAnchorId();
				this.builder.insertFragmentBefore(anchorId);
			}
				break;

			case V1ContentProducer.DATA: {
				int id = this.producer.getId();
				if (this.progressListener != null) {
					long srcPos = this.producer.getProgress();
					if (this.srcPos != srcPos) {
						this.progressListener.progress(srcPos);
						this.srcPos = srcPos;
					}
				}
				for (int len = this.producer.read(this.buff2, 0, this.buff2.length); len != -1; len = this.producer
						.read(this.buff2, 0, this.buff2.length)) {
					this.builder.write(id, this.buff2, 0, len);
				}
			}
				break;

			case V1ContentProducer.MESSAGE: {
				if (this.messageHandler != null) {
					short code = this.producer.getCode();
					String mes = this.producer.getMessage();
					String[] args = this.producer.getArgs();
					this.messageHandler.message(code, args, mes);
				}
			}
				break;

			default:
				throw new IOException("不正なレスポンスです。");
			}
			return true;
		}
		return false;
	}

	public void setSourceResolver(SourceResolver resolver) {
		throw new UnsupportedOperationException();
	}

	public void sendResource(Source source) throws IOException {
		this.init();
		try (OutputStream out = this.resource(new SimpleSourceMetadata(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength()))) {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.buff1); len != -1; len = in.read(this.buff1)) {
					out.write(this.buff1, 0, len);
				}
			}
		}
	}

	public void abort(byte mode) throws IOException {
		throw new UnsupportedOperationException();
	}

	public void reset() throws IOException {
		this.close();
		this.producer = null;
		this.request = null;
		this.state = 1;
	}

	public void close() throws IOException {
		if (this.state >= 3) {
			return;
		}
		if (this.producer != null) {
			this.producer.close();
		}
		this.state = 3;
	}
}