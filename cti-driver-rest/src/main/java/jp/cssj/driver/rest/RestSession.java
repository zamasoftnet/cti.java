package jp.cssj.driver.rest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.AbstractContentBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.AbstractCTISession;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.protocol.file.FileSource;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.SequentialOutput;
import net.zamasoft.zstream.io.util.FragmentOutputAdapter;
import net.zamasoft.zstream.io.util.SequentialOutputAdapter;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
 */
public class RestSession extends AbstractCTISession implements CTISession {
	protected static final String CHARSET = "UTF-8";
	protected static final ContentType NIO_CHARSET = ContentType.DEFAULT_TEXT.withCharset(CHARSET);
	
	protected final String uri;

	protected final String user, password;

	protected Results results = null;

	protected SourceResolver resolver = null;

	protected boolean continuous = false;

	protected MessageHandler messageHandler = null;

	protected ProgressListener progressListener = null;

	protected List<NameValuePair> props = new ArrayList<NameValuePair>();

	protected int state = 1;

	protected FragmentedOutput builder = null;

	protected HttpClient client;

	protected final DocumentBuilder docBuilder;

	protected final String sessionId;

	protected long srcRead = 0L;

	protected long srcLength = 0L;

	protected Collection<URI> resultSet = new HashSet<URI>();

	public RestSession(URI uri, String user, String password) throws IOException {
		this.uri = uri.toString();
		this.user = user;
		this.password = password;

		HttpClientBuilder builder = HttpClientBuilder.create();
		if (uri.getScheme().equals("https")) {
			try {
				if (System.getProperty("jp.cssj.driver.tls.trust", "true").equalsIgnoreCase("true")) {
					SSLContext ssl = org.apache.http.ssl.SSLContextBuilder.create()
							.loadTrustMaterial(new TrustSelfSignedStrategy()).build();
					builder.setSSLContext(ssl);
					SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(ssl,
							NoopHostnameVerifier.INSTANCE);
					builder.setSSLSocketFactory(sslsf);
				} else {
					SSLContext ssl = org.apache.http.ssl.SSLContextBuilder.create().build();
					builder.setSSLContext(ssl);
				}
			} catch (Exception e) {
				IOException(e);
			}
		}
		this.client = builder.build();
		try {
			this.docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			throw new RuntimeException(e);
		}
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		if (this.user != null) {
			list.add(new BasicNameValuePair("rest.user", this.user));
		}
		if (this.password != null) {
			list.add(new BasicNameValuePair("rest.password", this.password));
		}
		HttpGet method = new HttpGet(this.uri + "open?" + URLEncodedUtils.format(list, CHARSET));
		Element message = this.getMessage(method);
		String code = message.getAttribute("code");
		if (!"1012".equals(code)) {
			throw new SecurityException(message.getFirstChild().getNodeValue());
		}
		this.sessionId = message.getFirstChild().getNodeValue();
	}

	public void setHttpClient(HttpClient client) {
		this.client = client;
	}

	public HttpClient getHttpClient() {
		return this.client;
	}

	private static IOException IOException(Throwable cause) throws IOException {
		IOException e = new IOException();
		e.initCause(cause);
		return e;
	}

	private Reader getReader(HttpUriRequest req) throws IOException {
		return new InputStreamReader(this.getInputStream(req), CHARSET);
	}

	private InputStream getInputStream(HttpUriRequest req) throws IOException {
		HttpResponse res = this.client.execute(req);
		HttpEntity entity = res.getEntity();
		return entity.getContent();
	}

	private Document parseResponse(HttpUriRequest req) throws IOException {
		try (Reader reader = this.getReader(req);) {
			InputSource source = new InputSource(reader);
			source.setSystemId(req.getURI().toString());
			try {
				return this.docBuilder.parse(source);
			} catch (SAXException e) {
				req.abort();
				throw IOException(e);
			}
		}
	}

	private Element getMessage(HttpUriRequest method) throws IOException {
		Document doc = this.parseResponse(method);
		NodeList nl = doc.getElementsByTagName("message");
		if (nl == null || nl.getLength() == 0) {
			throw new IOException();
		}
		return (Element) nl.item(0);
	}

	public InputStream getServerInfo(URI uri) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		list.add(new BasicNameValuePair("rest.uri", uri.toString()));
		final HttpGet req = new HttpGet(this.uri + "info?" + URLEncodedUtils.format(list, CHARSET));
		return this.getInputStream(req);
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
		this.props.add(new BasicNameValuePair(key, value));
	}

	public void setSourceResolver(SourceResolver resolver) throws IOException {
		this.resolver = resolver;
	}

	public void setContinuous(boolean continuous) throws IOException {
		this.continuous = continuous;
	}

	private void resourceNotFound(URI uri) throws IOException {
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		list.add(new BasicNameValuePair("rest.uri", uri.toString()));
		list.add(new BasicNameValuePair("rest.notFound", "yes"));
		HttpGet method = new HttpGet(this.uri + "resources?" + URLEncodedUtils.format(list, CHARSET));
		Element message = this.getMessage(method);
		String code = message.getAttribute("code");
		if (!"1011".equals(code)) {
			throw new IOException(message.getFirstChild().getNodeValue());
		}
	}

	public OutputStream resource(final SourceMetadata metaSource) throws IOException {
		final File file = File.createTempFile("copper-rest-rsrc-", ".dat");
		return new FilterOutputStream(new FileOutputStream(file)) {
			public void close() throws IOException {
				try {
					super.close();
					try (FileSource source = new FileSource(file, metaSource.getURI(), metaSource.getMimeType(),
							metaSource.getEncoding())) {
						RestSession.this.resource(source);
					}
				} finally {
					file.delete();
				}
			}
		};
	}

	public void resource(Source source) throws IOException {
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		list.add(new BasicNameValuePair("rest.uri", source.getURI().toString()));
		if (source.getMimeType() != null) {
			list.add(new BasicNameValuePair("rest.mimeType", source.getMimeType()));
		}
		if (source.getEncoding() != null) {
			list.add(new BasicNameValuePair("rest.encoding", source.getEncoding()));
		}
		HttpPost method = new HttpPost(this.uri + "resources?" + URLEncodedUtils.format(list, CHARSET));

		HttpEntity entity = MultipartEntityBuilder.create().addPart("rest.resource", new SourceBody(source)).build();
		method.setEntity(entity);

		Element message = this.getMessage(method);
		String code = message.getAttribute("code");
		if (!"1011".equals(code)) {
			throw new IOException(message.getFirstChild().getNodeValue());
		}
	}

	private void waitResults() throws IOException, TranscoderException {
		for (;;) {
			List<NameValuePair> list = new ArrayList<NameValuePair>();
			list.add(new BasicNameValuePair("rest.id", this.sessionId));
			list.add(new BasicNameValuePair("rest.wait", "5000"));
			HttpPost method = new HttpPost(this.uri + "messages?" + URLEncodedUtils.format(list, CHARSET));
			Document doc = this.parseResponse(method);
			NodeList messages = doc.getElementsByTagName("message");
			Element message = (Element) messages.item(0);
			if (this.messageHandler != null) {
				for (int i = 1; i < messages.getLength(); ++i) {
					Element m = (Element) messages.item(i);
					short code = (short) Integer.parseInt(m.getAttribute("code"), 16);
					Text textNode = (Text) m.getFirstChild();
					List<String> argsList = new ArrayList<String>();
					for (int j = 0;; ++j) {
						Attr arg = m.getAttributeNode("arg" + j);
						if (arg == null) {
							break;
						}
						argsList.add(arg.getValue());
					}
					String[] args = (String[]) argsList.toArray(new String[argsList.size()]);
					String text = textNode == null ? null : textNode.getNodeValue();
					this.messageHandler.message(code, args, text);
				}
			}
			if (this.resolver != null) {
				NodeList resources = doc.getElementsByTagName("resource");
				for (int i = 0; i < resources.getLength(); ++i) {
					Element resource = (Element) resources.item(i);
					URI uri = URI.create(resource.getAttribute("uri"));
					Source source;
					try {
						source = this.resolver.resolve(uri);
					} catch (IOException e) {
						this.resourceNotFound(uri);
						source = null;
					}
					if (source != null) {
						try {
							if (source.exists()) {
								this.resource(source);
							} else {
								this.resourceNotFound(uri);
							}
						} finally {
							this.resolver.release(source);
						}
					}
				}
			}
			if (this.progressListener != null) {
				NodeList progressList = doc.getElementsByTagName("progressList");
				if (progressList.getLength() > 0) {
					Element progress = (Element) progressList.item(0);
					Attr length = progress.getAttributeNode("length");
					if (length != null) {
						long srcLength = Long.parseLong(length.getValue());
						if (this.srcLength != srcLength) {
							this.srcLength = srcLength;
							this.progressListener.sourceLength(this.srcLength);
						}
					}
					Attr read = progress.getAttributeNode("read");
					if (read != null) {
						long srcRead = Long.parseLong(read.getValue());
						if (this.srcRead != srcRead) {
							this.srcRead = srcRead;
							this.progressListener.progress(this.srcLength);
						}
					}
				}
			}
			NodeList results = doc.getElementsByTagName("result");
			for (int i = 0; i < results.getLength(); ++i) {
				Element result = (Element) results.item(i);
				URI uri = URI.create(result.getAttribute("uri"));
				if (this.resultSet.contains(uri)) {
					continue;
				}
				this.resultSet.add(uri);
				Attr mimeType = result.getAttributeNode("mimeType");
				Attr encoding = result.getAttributeNode("encoding");
				Attr length = result.getAttributeNode("length");
				SourceMetadata metaSource = new SimpleSourceMetadata(uri, mimeType == null ? null : mimeType.getValue(),
						encoding == null ? null : encoding.getValue(),
						length == null ? -1L : Long.parseLong(length.getValue()));
				FragmentedOutput builder = this.results.nextBuilder(metaSource);
				this.result(builder, uri);
			}
			NodeList interruptedList = doc.getElementsByTagName("interrupted");
			if (interruptedList.getLength() > 0) {
				Element m = (Element) interruptedList.item(0);
				short code = (short) Integer.parseInt(m.getAttribute("code"), 16);
				Text text = (Text) m.getFirstChild();
				List<String> args = new ArrayList<String>();
				for (int j = 0;; ++j) {
					Attr arg = m.getAttributeNode("arg" + j);
					if (arg == null) {
						break;
					}
					args.add(arg.getValue());
				}
				throw new TranscoderException(
						results.getLength() > 0 ? TranscoderException.STATE_READABLE : TranscoderException.STATE_BROKEN,
						code, (String[]) args.toArray(new String[args.size()]),
						text == null ? null : text.getNodeValue());
			}
			if ("1013".equals(message.getAttribute("code"))) {
				continue;
			}
			break;
		}
	}

	private void result(FragmentedOutput builder, URI uri) throws IOException {
		OutputStream out;
		if (builder instanceof SequentialOutput) {
			out = new SequentialOutputAdapter((SequentialOutput) builder);
		} else {
			out = new FragmentOutputAdapter(builder, 0);
		}
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		list.add(new BasicNameValuePair("rest.uri", uri.toString()));
		HttpGet req = new HttpGet(this.uri + "result?" + URLEncodedUtils.format(list, CHARSET));
		HttpResponse res = this.client.execute(req);
		HttpEntity entity = res.getEntity();
		try (InputStream in = entity.getContent()) {
			IOUtils.copy(in, out);
		}
	}

	public void transcode(URI uri) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.state = 2;
		try {
			List<NameValuePair> list = new ArrayList<NameValuePair>();
			list.add(new BasicNameValuePair("rest.id", this.sessionId));
			list.add(new BasicNameValuePair("rest.async", "true"));
			if (this.resolver != null) {
				list.add(new BasicNameValuePair("rest.requestResource", "true"));
			}
			if (this.continuous) {
				list.add(new BasicNameValuePair("rest.continuous", "true"));
			}
			HttpPost method = new HttpPost(this.uri + "transcode?" + URLEncodedUtils.format(list, CHARSET));

			list.clear();
			list.addAll(this.props);
			this.props.clear();
			list.add(new BasicNameValuePair("rest.mainURI", uri.toString()));
			method.setEntity(new UrlEncodedFormEntity(list, CHARSET));

			Element message = this.getMessage(method);
			String code = message.getAttribute("code");
			if (!"1011".equals(code)) {
				throw new IOException(message.getFirstChild().getNodeValue());
			}
			this.resultSet.clear();
			this.waitResults();
		} finally {
			this.state = 1;
		}
	}

	public OutputStream transcode(final SourceMetadata metaSource) throws IOException, TranscoderException {
		final File file = File.createTempFile("copper-rest-main-", ".dat");
		return new FilterOutputStream(new FileOutputStream(file)) {
			public void close() throws IOException {
				try {
					super.close();
					try (FileSource source = new FileSource(file, metaSource.getURI(), metaSource.getMimeType(),
							metaSource.getEncoding())) {
						RestSession.this.transcode(source);
					}
				} finally {
					file.delete();
				}
			}
		};
	}

	public void transcode(Source source) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.state = 2;
		try {
			List<NameValuePair> list = new ArrayList<NameValuePair>();
			list.add(new BasicNameValuePair("rest.id", this.sessionId));
			list.add(new BasicNameValuePair("rest.async", "true"));
			if (this.resolver != null) {
				list.add(new BasicNameValuePair("rest.requestResource", "true"));
			}
			if (this.continuous) {
				list.add(new BasicNameValuePair("rest.continuous", "true"));
			}
			HttpPost method = new HttpPost(this.uri + "transcode?" + URLEncodedUtils.format(list, CHARSET));

			MultipartEntityBuilder builder = MultipartEntityBuilder.create();
			for (int i = 0; i < this.props.size(); ++i) {
				BasicNameValuePair param = (BasicNameValuePair) this.props.get(i);
				builder.addPart(param.getName(), new StringBody(param.getValue(), NIO_CHARSET));
			}
			this.props.clear();
			builder.addPart("rest.uri", new StringBody(source.getURI().toString(), NIO_CHARSET));
			if (source.getMimeType() != null) {
				builder.addPart("rest.mimeType", new StringBody(source.getMimeType(), NIO_CHARSET));
			}
			if (source.getEncoding() != null) {
				builder.addPart("rest.encoding", new StringBody(source.getEncoding(), NIO_CHARSET));
			}
			builder.addPart("rest.main", new SourceBody(source));
			method.setEntity(builder.build());

			Element message = this.getMessage(method);
			String code = message.getAttribute("code");
			if (!"1011".equals(code)) {
				throw new IOException(message.getFirstChild().getNodeValue());
			}
			this.resultSet.clear();
			this.waitResults();
		} finally {
			this.state = 1;
		}
	}

	public void join() throws IOException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.state = 2;
		try {
			List<NameValuePair> list = new ArrayList<NameValuePair>();
			list.add(new BasicNameValuePair("rest.id", this.sessionId));
			HttpGet method = new HttpGet(this.uri + "join?" + URLEncodedUtils.format(list, CHARSET));
			Element message = this.getMessage(method);
			String code = message.getAttribute("code");
			if (!"1011".equals(code)) {
				throw new IOException(message.getFirstChild().getNodeValue());
			}
			this.waitResults();
		} finally {
			this.state = 1;
		}
	}

	public void abort(byte mode) throws IOException {
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		list.add(new BasicNameValuePair("rest.mode", String.valueOf(mode)));
		HttpGet method = new HttpGet(this.uri + "abort?" + URLEncodedUtils.format(list, CHARSET));
		Element message = this.getMessage(method);
		String code = message.getAttribute("code");
		if (!"1011".equals(code)) {
			throw new IOException(message.getFirstChild().getNodeValue());
		}
	}

	public void reset() throws IOException {
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		HttpGet method = new HttpGet(this.uri + "reset?" + URLEncodedUtils.format(list, CHARSET));
		Element message = this.getMessage(method);
		String code = message.getAttribute("code");
		if (!"1011".equals(code)) {
			throw new IOException(message.getFirstChild().getNodeValue());
		}
		this.resolver = null;
		this.continuous = false;
		this.results = null;
		this.state = 1;
		this.builder = null;
		this.props.clear();
		this.resultSet.clear();
		this.srcRead = 0;
		this.srcLength = 0;
	}

	public void close() throws IOException {
		if (this.state >= 3) {
			return;
		}
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		list.add(new BasicNameValuePair("rest.id", this.sessionId));
		HttpGet method = new HttpGet(this.uri + "close?" + URLEncodedUtils.format(list, CHARSET));
		Element message = this.getMessage(method);
		String code = message.getAttribute("code");
		if (!"1011".equals(code)) {
			throw new IOException(message.getFirstChild().getNodeValue());
		}
		this.state = 3;
	}
}

class SourceBody extends AbstractContentBody {
	protected final Source source;

	public SourceBody(Source source) throws IOException {
		super(source.getMimeType() == null ? ContentType.APPLICATION_OCTET_STREAM
				: ContentType.create(source.getMimeType()));
		this.source = source;
	}

	public void writeTo(OutputStream out) throws IOException {
		try (InputStream in = this.source.getInputStream()) {
			IOUtils.copy(in, out);
		}
	}

	public String getFilename() {
		return null;
	}

	public String getCharset() {
		try {
			return this.source.getEncoding();
		} catch (IOException e) {
			return null;
		}
	}

	public long getContentLength() {
		try {
			return this.source.getLength();
		} catch (IOException e) {
			return -1;
		}
	}

	public String getTransferEncoding() {
		return "8bit";
	}
}