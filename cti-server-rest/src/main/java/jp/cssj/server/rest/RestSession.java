package jp.cssj.server.rest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.helpers.MimeTypeHelper;
import jp.cssj.cti2.helpers.ServletHelper;
import jp.cssj.cti2.helpers.ServletResponseResults;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.composite.CompositeSourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.resolver.util.URIHelper;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;
import jp.cssj.server.rest.RestRequest.FormField;

import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.IOUtils;

/**
 * RESTインターフェースのセッション情報です。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
 */
public class RestSession {
	private final CTISession session;
	public final long timeout;
	private final Messages messages;
	private final SourceResolver resolver;
	private volatile long accessed = System.currentTimeMillis();
	private volatile TranscodeTask transcode = null;

	/**
	 * 受信済みのメッセージです。
	 * 
	 * @author MIYABE Tatsuhiko
	 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
	 */
	protected static class Message {
		public final short code;
		public final String[] args;
		public final String text;

		public Message(short code, String[] args, String message) {
			this.code = code;
			this.args = (String[]) (args == null ? null : args.clone());
			this.text = message;
		}
	}

	/**
	 * メッセージを受信します。
	 * 
	 * @author MIYABE Tatsuhiko
	 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
	 */
	protected class Messages implements MessageHandler {
		private final List<Message> messages = Collections.synchronizedList(new ArrayList<Message>());

		public void message(short code, String[] args, String mes) {
			Message message = new Message(code, args, mes);
			this.add(message);
		}

		public void add(Message message) {
			// System.err.println("message1: "+message.text);
			this.messages.add(message);
			synchronized (RestSession.this) {
				RestSession.this.notifyAll();
			}
			// System.err.println("message2: "+message.text);
		}

		public boolean isEmpty() {
			return this.messages.isEmpty();
		}

		public Message remove() {
			return (Message) this.messages.remove(0);
		}

		public int size() {
			return this.messages.size();
		}
	}

	protected class TranscodeTask implements SourceResolver, ProgressListener, Runnable {
		/** メインドキュメントの長さ。 **/
		private long srcLength = -1L;
		/** 読み込み済みメインドキュメント。 */
		private long srcRead = -1L;
		/** サーバー側のメインドキュメントのURI。 */
		private URI uri = null;
		/** クライアント側のメインドキュメントのソース。 */
		private Source source = null;
		/** 要求されたリソース。 */
		private URI requiredResource = null;
		private Source resolvedResource = null;
		/** 結果のURIのリスト。 */
		private List<URI> resultList = null;
		/** URIと結果ファイルのマップ。 */
		private Map<URI, File> uriToResult = null;
		/** URIと結果SourceMetadataのマップ。 */
		private Map<URI, SourceMetadata> uriToSourceMetadata = null;
		private boolean transcoding = false;
		private IOException ex = null;
		private Thread th = null;

		public void sourceLength(long srcLength) {
			// System.err.println("srcLength: "+srcLength);
			synchronized (RestSession.this) {
				this.srcLength = srcLength;
				RestSession.this.notifyAll();
			}
		}

		public void progress(long srcRead) {
			// System.err.println("srcRead1: "+srcRead);
			synchronized (RestSession.this) {
				this.srcRead = srcRead;
				RestSession.this.notifyAll();
			}
			// System.err.println("srcRead2: "+srcRead);
		}

		public void setSourceURI(URI uri) {
			this.uri = uri;
		}

		public void setSource(Source source) {
			this.source = source;
		}

		public synchronized Source resolve(URI uri) throws IOException, FileNotFoundException {
			synchronized (RestSession.this) {
				this.requiredResource = uri;
				RestSession.this.notifyAll();
			}
			try {
				for (;;) {
					if (this.resolvedResource != null) {
						return this.resolvedResource;
					}
					if (this.requiredResource == null || !this.transcoding) {
						throw new FileNotFoundException(uri.toString());
					}
					try {
						this.wait(1000);
					} catch (InterruptedException e) {
						// ignore
					}
				}
			} finally {
				this.requiredResource = null;
				this.resolvedResource = null;
			}
		}

		public void release(Source source) {
			try {
				((StreamSource) source).close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		public void transcode(final HttpServletRequest req, final HttpServletResponse res, boolean async,
				boolean resolverMode, boolean continuous) throws ServletException, IOException, TranscoderException {
			if (resolverMode) {
				RestSession.this.session.setSourceResolver(this);
			} else if (RestSession.this.resolver != null) {
				RestSession.this.session.setSourceResolver(RestSession.this.resolver);
			}
			RestSession.this.session.setContinuous(continuous);

			while (this.transcoding) {
				synchronized (RestSession.this) {
					try {
						RestSession.this.wait();
					} catch (InterruptedException e) {
						// ignore
					}
				}
			}
			this.transcoding = true;
			if (async) {
				this.th = new Thread(this, RestServlet.class.getName());
				this.th.start();
				RestServlet.sendMessage(req, res, RestServlet.INFO_OK);
			} else {
				this.syncTranscode(res);
			}
		}

		/**
		 * 同期的な変換処理を実行します。
		 * 
		 * @param res
		 * @throws ServletException
		 * @throws IOException
		 */
		private void syncTranscode(final HttpServletResponse res)
				throws ServletException, IOException, TranscoderException {
			try {
				RestSession.this.session.setProgressListener(this);
				// １つだけ結果を取得する
				ServletResponseResults results = new ServletResponseResults(res) {
					long time = System.currentTimeMillis();

					protected void finish() {
						long length = this.builder.getLength();
						RestSession.this.done(length, System.currentTimeMillis() - this.time);
						super.finish();
					}

				};
				RestSession.this.session.setResults(results);
				if (this.uri != null) {
					RestSession.this.session.transcode(this.uri);
				} else {
					RestSession.this.session.transcode(this.source);
				}
			} catch (IOException e) {
				this.ex = e;
				throw e;
			} finally {
				this.transcoding = false;
				synchronized (RestSession.this) {
					RestSession.this.notifyAll();
				}
			}
		}

		/**
		 * 非同期の変換処理を実行します。
		 */
		public void run() {
			try {
				this.resultList = new ArrayList<URI>();
				this.uriToResult = new HashMap<URI, File>();
				this.uriToSourceMetadata = new HashMap<URI, SourceMetadata>();
				Results results = new Results() {
					public boolean hasNext() {
						return true;
					}

					public FragmentedOutput nextBuilder(final SourceMetadata metaSource) throws IOException {
						final URI uri = metaSource.getURI();
						final File file = File.createTempFile("copper-rest-result-", ".dat");
						FragmentedOutput builder = new FileFragmentedOutput(file) {
							public void close() throws IOException {
								super.close();
								synchronized (RestSession.this) {
									resultList.add(uri);
									uriToResult.put(uri, file);
									uriToSourceMetadata.put(uri, metaSource);
									RestSession.this.notifyAll();
								}
							}
						};
						return builder;
					}

					public void end() {
						// NOP
					}
				};

				RestSession.this.session.setResults(results);
				RestSession.this.session.setProgressListener(this);
				if (this.uri != null) {
					RestSession.this.session.transcode(this.uri);
				} else {
					RestSession.this.session.transcode(this.source);
				}
			} catch (TranscoderException e) {
				if (e.getState() == TranscoderException.STATE_BROKEN) {
					this.th = null;
					this.dispose();
				}
				this.ex = e;
			} catch (IOException e) {
				this.th = null;
				this.dispose();
				this.ex = e;
			} finally {
				this.transcoding = false;
				synchronized (RestSession.this) {
					RestSession.this.notifyAll();
				}
			}
		}

		public void dispose() {
			if (this.th != null) {
				try {
					this.th.join();
				} catch (InterruptedException e) {
					// ignore;
				}
			}
			if (this.uriToResult != null) {
				for (Iterator<File> i = this.uriToResult.values().iterator(); i.hasNext();) {
					File file = (File) i.next();
					file.delete();
				}
				this.resultList = null;
				this.uriToResult = null;
				this.uriToSourceMetadata = null;
			}
		}
	}

	RestSession(CTISession session, boolean messages, boolean restResolver, long timeout) throws IOException {
		this.session = session;
		if (messages) {
			this.messages = new Messages();
		} else {
			this.messages = null;
		}
		if (this.messages != null) {
			this.session.setMessageHandler(this.messages);
		}
		if (restResolver) {
			this.resolver = CompositeSourceResolver.createGenericCompositeSourceResolver();
		} else {
			this.resolver = null;
		}
		this.timeout = timeout;
	}

	private void resource(SourceMetadata metaSource, byte[] data) throws IOException {
		if (this.transcode != null) {
			synchronized (this.transcode) {
				if (metaSource.getURI().equals(this.transcode.requiredResource)) {
					this.transcode.resolvedResource = new StreamSource(metaSource.getURI(),
							new ByteArrayInputStream(data), metaSource.getMimeType(), metaSource.getEncoding(),
							data.length);
					this.transcode.notify();
					return;
				}
			}
		}
		try (OutputStream out = this.session.resource(metaSource)) {
			out.write(data);
		}
	}

	private void resource(Source source) throws IOException {
		if (this.transcode != null) {
			synchronized (this.transcode) {
				if (source.getURI().equals(this.transcode.requiredResource)) {
					this.transcode.resolvedResource = source;
					this.transcode.notify();
					return;
				}
			}
		}
		this.session.resource(source);
	}

	/**
	 * 処理を完了します。
	 * 
	 * @param length
	 * @param time
	 */
	private void done(long length, long time) {
		String size;
		if (length < 1024) {
			size = length + "B";
		} else if (length < 1024 * 1024) {
			size = (length / 1024) + "KB";
		} else {
			size = (length / 1024 / 1024) + "MB";
		}
		if (this.messages != null) {
			Message message = new Message((short) 0, null, "Done: " + size + " / " + time + "ms");
			this.messages.add(message);
		}
	}

	/**
	 * 直前のアクセス時刻を返します。
	 * 
	 * @return
	 */
	long getAccessed() {
		return this.accessed;
	}

	void info(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, FileUploadException, IOException, URISyntaxException {
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uriStr = restReq.getParameter("rest.uri");
		if (uriStr == null) {
			uriStr = ".";
		}
		URI uri = URIHelper.create(RestServlet.CHARSET, uriStr);
		try (InputStream in = this.session.getServerInfo(uri)) {
			OutputStream out = res.getOutputStream();
			IOUtils.copy(in, out);
		}
	}

	/**
	 * プロパティを設定します。
	 * 
	 * @param req
	 * @throws ServletException
	 * @throws FileUploadException
	 * @throws IOException
	 */
	void properties(final HttpServletRequest req) throws ServletException, FileUploadException, IOException {
		RestRequest restReq = RestRequest.getRestRequest(req);
		String charset = req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}
		while (restReq.getType() != RestRequest.NONE) {
			if (restReq.getType() != RestRequest.FIELD) {
				restReq.getItem();
				restReq.nextItem();
				continue;
			}
			FormField field = (FormField) restReq.getItem();
			if (field.name.startsWith("rest.")) {
				restReq.nextItem();
				continue;
			}
			this.property(req, field.name, field.value);
			restReq.nextItem();
		}
	}

	private void property(HttpServletRequest req, String name, String value) throws IOException {
		if (name.equals("webapp.user-agent")) {
			String ua = req.getHeader("User-Agent");
			if (ua != null) {
				this.session.property("input.http.header.0.name", "User-Agent");
				this.session.property("input.http.header.0.value", ua);
			}
		}
		this.session.property(name, value);
	}

	/**
	 * リソースを送信します。
	 * 
	 * @param req
	 * @param res
	 * @throws ServletException
	 * @throws IOException
	 * @throws TranscoderException
	 * @throws FileUploadException
	 */
	void resources(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, IOException, TranscoderException, FileUploadException {
		this.accessed = System.currentTimeMillis();
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uri = restReq.getParameter("rest.uri");
		String mimeType = restReq.getParameter("rest.mimeType");
		String encoding = restReq.getParameter("rest.encoding");

		// System.err.println(req.getContentType());
		String charset = req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}

		// System.err.println("resources");
		while (restReq.getType() != RestRequest.NONE) {
			if (restReq.getType() == RestRequest.FIELD) {
				// フォームの値
				FormField field = (FormField) restReq.getItem();
				// System.err.println("form: "+field.name);
				if (field.name.startsWith("rest.")) {
					if (field.name.equals("rest.resource")) {
						URI rsrcURI;
						if (uri == null) {
							rsrcURI = URIHelper.CURRENT_URI;
						} else {
							try {
								rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
							} catch (URISyntaxException e) {
								this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri },
										null);
								rsrcURI = URIHelper.CURRENT_URI;
							}
						}
						byte[] data = field.data;
						String e = encoding;
						if (data == null) {
							data = field.value.getBytes(charset);
							e = charset;
						}
						this.resource(new SimpleSourceMetadata(rsrcURI, mimeType, e, data.length), data);
					} else if (field.name.equals("rest.uri")) {
						uri = field.value;
					} else if (field.name.equals("rest.mimeType")) {
						mimeType = field.value;
					} else if (field.name.equals("rest.encoding")) {
						encoding = field.value;
					}
				} else {
					this.property(req, field.name, field.value);
				}
			} else {
				// ファイル
				FileItemStream item = (FileItemStream) restReq.getItem();
				String name = item.getFieldName();
				// System.err.println("file: "+name+"/"+item);
				if (name.equals("rest.resource")) {
					FileItemHeaders headers = item.getHeaders();
					if (uri == null && headers != null) {
						uri = headers.getHeader("X-URI");
					}
					if (uri == null) {
						uri = item.getName();
					}
					if (mimeType == null) {
						mimeType = item.getContentType();
					}
					if (encoding == null && mimeType != null) {
						encoding = MimeTypeHelper.getParameter(mimeType, "charset");
					}
					mimeType = MimeTypeHelper.getTypePart(mimeType);
					long length = -1L;
					if (headers != null) {
						String value = headers.getHeader("Content-Length");
						if (value != null) {
							length = Long.parseLong(value);
						}
					}
					URI rsrcURI;
					try {
						rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
					} catch (URISyntaxException e) {
						this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
						rsrcURI = URIHelper.CURRENT_URI;
					}
					try (InputStream in = item.openStream()) {
						this.resource(new StreamSource(rsrcURI, in, mimeType, encoding, length));
					}
					uri = null;
					mimeType = null;
					encoding = null;
					length = -1L;
				}
			}
			restReq.nextItem();
		}
		if (!RestUtils.isForm(req)) {
			// 内容がリソース
			if (uri == null) {
				uri = req.getHeader("X-URI");
			}
			if (mimeType == null) {
				mimeType = req.getContentType();
			}
			if (encoding == null && mimeType != null) {
				encoding = MimeTypeHelper.getParameter(mimeType, "charset");
			}
			mimeType = MimeTypeHelper.getTypePart(mimeType);
			long length = -1L;
			String value = req.getHeader("Content-Length");
			if (value != null) {
				length = Long.parseLong(value);
			}
			URI rsrcURI;
			if (uri == null) {
				rsrcURI = URIHelper.CURRENT_URI;
			} else {
				try {
					rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
				} catch (URISyntaxException e) {
					this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
					rsrcURI = URIHelper.CURRENT_URI;
				}
			}
			StreamSource source = new StreamSource(rsrcURI, req.getInputStream(), mimeType, encoding, length);
			this.resource(source);
		}
	}

	/**
	 * メインドキュメントを送信します。
	 * 
	 * @param req
	 * @param res
	 * @return
	 * @throws ServletException
	 * @throws IOException
	 * @throws TranscoderException
	 * @throws FileUploadException
	 * @throws URISyntaxException
	 */
	boolean transcode(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, IOException, TranscoderException, FileUploadException, URISyntaxException {
		this.accessed = System.currentTimeMillis();
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uri = restReq.getParameter("rest.uri");
		String mimeType = restReq.getParameter("rest.mimeType");
		String encoding = restReq.getParameter("rest.encoding");
		String mainURI = restReq.getParameter("rest.mainURI");

		final boolean async = "true".equals(restReq.getParameter("rest.async"));
		boolean resolverMode = "true".equals(restReq.getParameter("rest.requestResource"));
		boolean continuous = "true".equals(restReq.getParameter("rest.continuous"));
		if (!async) {
			resolverMode = continuous = false;
		}

		String charset = req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}

		// フォームデータ
		while (restReq.getType() != RestRequest.NONE) {
			if (restReq.getType() == RestRequest.FIELD) {
				FormField field = (FormField) restReq.getItem();
				if (field.name.startsWith("rest.")) {
					if (field.name.equals("rest.mainURI")) {
						mainURI = field.value;
					} else if (field.name.equals("rest.main")) {
						if (this.transcode == null) {
							this.transcode = new TranscodeTask();
						}
						byte[] data = field.data;
						String enc = encoding;
						if (data == null) {
							data = field.value.getBytes(charset);
							enc = charset;
						}
						URI rsrcURI;
						if (uri == null) {
							rsrcURI = URIHelper.CURRENT_URI;
						} else {
							try {
								rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
							} catch (URISyntaxException e) {
								this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri },
										null);
								rsrcURI = URIHelper.CURRENT_URI;
							}
						}
						Source source = new StreamSource(rsrcURI, new ByteArrayInputStream(data), mimeType, enc,
								data.length);
						this.transcode.setSource(source);
						this.transcode.transcode(req, res, async, resolverMode, continuous);
						return true;
					} else if (field.name.equals("rest.resource")) {
						byte[] data = field.data;
						String enc = encoding;
						if (data == null) {
							data = field.value.getBytes(charset);
							enc = charset;
						}
						URI rsrcURI;
						if (uri == null) {
							rsrcURI = URIHelper.CURRENT_URI;
						} else {
							try {
								rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
							} catch (URISyntaxException e) {
								this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri },
										null);
								rsrcURI = URIHelper.CURRENT_URI;
							}
						}
						SourceMetadata metaSource = new SimpleSourceMetadata(rsrcURI, mimeType, enc, data.length);
						this.resource(metaSource, data);
					} else if (field.name.equals("rest.uri")) {
						uri = field.value;
					} else if (field.name.equals("rest.mimeType")) {
						mimeType = field.value;
					} else if (field.name.equals("rest.encoding")) {
						encoding = field.value;
					}
				} else {
					this.property(req, field.name, field.value);
				}
			} else {
				FileItemStream item = (FileItemStream) restReq.getItem();
				String name = item.getFieldName();
				if (name.equals("rest.resource") || name.equals("rest.main")) {
					FileItemHeaders headers = item.getHeaders();
					if (uri == null && headers != null) {
						uri = headers.getHeader("X-URI");
					}
					if (uri == null) {
						uri = item.getName();
					}
					if (mimeType == null) {
						mimeType = item.getContentType();
					}
					if (encoding == null && mimeType != null) {
						encoding = MimeTypeHelper.getParameter(mimeType, "charset");
					}
					mimeType = MimeTypeHelper.getTypePart(mimeType);
					long length = -1L;
					if (headers != null) {
						String value = headers.getHeader("Content-Length");
						if (value != null) {
							length = Long.parseLong(value);
						}
					}
					URI rsrcURI;
					if (uri == null) {
						rsrcURI = URIHelper.CURRENT_URI;
					} else {
						try {
							rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
						} catch (URISyntaxException e) {
							this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
							rsrcURI = URIHelper.CURRENT_URI;
						}
					}
					try (InputStream in = item.openStream()) {
						if (name.equals("rest.main")) {
							if (this.transcode == null) {
								this.transcode = new TranscodeTask();
							}
							Source source = new StreamSource(rsrcURI, in, mimeType, encoding, length);
							this.transcode.setSource(source);
							this.transcode.transcode(req, res, async, resolverMode, continuous);
							return true;
						} else {
							StreamSource source = new StreamSource(rsrcURI, in, mimeType, encoding, length);
							this.resource(source);
							uri = null;
							mimeType = null;
							encoding = null;
							length = -1L;
						}
					}
				}
			}
			restReq.nextItem();
		}
		if (mainURI != null) {
			if (this.transcode == null) {
				this.transcode = new TranscodeTask();
			}
			URI mainURIParsed = URIHelper.create(RestServlet.CHARSET, mainURI);
			this.transcode.setSourceURI(mainURIParsed);
			this.transcode.transcode(req, res, async, resolverMode, continuous);
			return true;
		}
		if (!RestUtils.isForm(req)) {
			// 内容がメインドキュメント
			if (uri == null) {
				uri = req.getHeader("X-URI");
			}
			if (mimeType == null) {
				mimeType = req.getContentType();
			}
			if (encoding == null && mimeType != null) {
				encoding = MimeTypeHelper.getParameter(mimeType, "charset");
			}
			mimeType = MimeTypeHelper.getTypePart(mimeType);
			long length = -1L;
			String value = req.getHeader("Content-Length");
			if (value != null) {
				length = Long.parseLong(value);
			}
			URI rsrcURI;
			if (uri == null) {
				rsrcURI = URIHelper.CURRENT_URI;
			} else {
				try {
					rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
				} catch (URISyntaxException e) {
					this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
					rsrcURI = URIHelper.CURRENT_URI;
				}
			}
			StreamSource source = new StreamSource(rsrcURI, req.getInputStream(), mimeType, encoding, length);
			if (this.transcode == null) {
				this.transcode = new TranscodeTask();
			}
			this.transcode.setSource(source);
			this.transcode.transcode(req, res, async, resolverMode, continuous);
			return true;
		}
		return false;
	}

	void noResource(HttpServletRequest req)
			throws ServletException, IOException, TranscoderException, FileUploadException, URISyntaxException {
		if (this.transcode != null) {
			synchronized (this.transcode) {
				RestRequest restReq = RestRequest.getRestRequest(req);
				String uri = restReq.getParameter("rest.uri");
				if (uri == null) {
					uri = ".";
				}
				if (URIHelper.create(RestServlet.CHARSET, uri).equals(this.transcode.requiredResource)) {
					this.transcode.requiredResource = null;
					this.transcode.resolvedResource = null;
					this.transcode.notify();
					return;
				}
			}
		}
	}

	/**
	 * メッセージを返します。
	 * 
	 * @param req
	 * @param res
	 * @throws ServletException
	 * @throws IOException
	 */
	synchronized void messages(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, IOException, FileUploadException {
		// System.err.println("messages1");

		this.accessed = System.currentTimeMillis();
		res.setContentType("text/xml");
		res.setCharacterEncoding("UTF-8");
		try (PrintWriter out = res.getWriter()) {
			out.println("<?xml version=\"1.0\"?>");
			out.println("<response>");

			boolean transcoding = this.transcode != null && this.transcode.transcoding;
			if (transcoding && this.messages.isEmpty()) {
				RestRequest restReq = RestRequest.getRestRequest(req);
				String waitStr = restReq.getParameter("rest.wait");
				if (waitStr != null) {
					// メッセージが溜まるまで待つ。
					int wait = 0;
					try {
						wait = Integer.parseInt(waitStr);
					} catch (NumberFormatException e1) {
						// ignore
					}
					try {
						this.wait(wait);
					} catch (InterruptedException e) {
						// ignore
					}
				}
			}

			String code = Integer.toHexString(transcoding ? RestServlet.INFO_TRANSCODING : RestServlet.INFO_TRANDCODED);
			out.print("<message code=\"");
			out.print(code);
			out.println("\" />");

			// メッセージを送る
			if (!this.messages.isEmpty()) {
				out.println("<messages>");
				do {
					Message message = this.messages.remove();
					String text = RestUtils.htmlEscape(message.text);
					out.print("<message code=\"" + Integer.toHexString(message.code) + "\"");
					if (message.args != null) {
						for (int i = 0; i < message.args.length; ++i) {
							out.print(" arg" + i + "=\"" + RestUtils.htmlEscape(message.args[i]) + "\"");
						}
					}
					out.print(">");
					out.print(text);
					out.println("</message>");
				} while (!this.messages.isEmpty());
				out.println("</messages>");
			}
			if (this.transcode != null) {
				// 中断
				if (this.transcode.ex != null) {
					TranscoderException e;
					if (this.transcode.ex instanceof TranscoderException) {
						e = (TranscoderException) this.transcode.ex;
					} else {
						e = new TranscoderException(CTIMessageCodes.FATAL_UNEXPECTED,
								new String[] { this.transcode.ex.getMessage() }, "");
					}
					String text = RestUtils.htmlEscape(e.getMessage());
					out.print("<interrupted code=\"" + Integer.toHexString(e.getCode()) + "\"");
					if (e.getArgs() != null) {
						for (int i = 0; i < e.getArgs().length; ++i) {
							out.print(" arg" + i + "=\"" + RestUtils.htmlEscape(e.getArgs()[i]) + "\"");
						}
					}
					out.print(">");
					out.print(text);
					out.println("</interrupted>");
				}

				// 要求されたリソース
				if (this.transcode.requiredResource != null) {
					out.println("<resources>");
					out.print("<resource uri=\"");
					out.print(RestUtils.htmlEscape(this.transcode.requiredResource.toString()));
					out.println("\"/>");
					out.println("</resources>");
				}
				// 変換結果
				if (this.transcode.uriToResult != null && !this.transcode.uriToResult.isEmpty()) {
					out.println("<results>");
					for (Iterator<URI> i = this.transcode.resultList.iterator(); i.hasNext();) {
						URI uri = (URI) i.next();
						out.print("<result uri=\"");
						out.print(RestUtils.htmlEscape(uri.toString()));
						out.println("\"/>");
					}
					out.println("</results>");
				}
				// 進行状況
				if (this.transcode.srcLength != -1L || this.transcode.srcRead != -1L) {
					out.print("<progress");
					if (this.transcode.srcLength != -1L) {
						out.print(" length=\"" + this.transcode.srcLength + "\"");
					}
					if (this.transcode.srcRead != -1L) {
						out.print(" read=\"" + this.transcode.srcRead + "\"");
					}
					out.println(" />");
				}
			}
			out.println("</response>");
		}
	}

	/**
	 * 結果を受信します。
	 * 
	 * @param req
	 * @param res
	 * @throws IOException
	 * @throws FileUploadException
	 */
	void result(HttpServletRequest req, HttpServletResponse res)
			throws IOException, FileUploadException, ServletException {
		this.accessed = System.currentTimeMillis();
		if (this.transcode == null || this.transcode.uriToResult == null) {
			RestServlet.sendMessage(req, res, RestServlet.ERROR_NO_RESULT);
			return;
		}
		RestRequest restReq = RestRequest.getRestRequest(req);
		try {
			String uri = restReq.getParameter("rest.uri");
			if (uri == null) {
				uri = ".";
			}
			URI resultURI = URIHelper.create(RestServlet.CHARSET, uri);
			File file = (File) this.transcode.uriToResult.get(resultURI);
			if (file == null) {
				throw new FileNotFoundException(resultURI.toString());
			}
			SourceMetadata metaSource = (SourceMetadata) this.transcode.uriToSourceMetadata.get(resultURI);
			res.setContentLengthLong(file.length());
			res.setContentType(ServletHelper.getContentType(metaSource));
			try (InputStream in = new FileInputStream(file)) {
				IOUtils.copy(in, res.getOutputStream());
			}
		} catch (URISyntaxException e) {
			throw new FileNotFoundException();
		}
	}

	/**
	 * 変換処理を中断します。
	 * 
	 * @param req
	 * @throws IOException
	 * @throws FileUploadException
	 */
	void abort(HttpServletRequest req) throws IOException, FileUploadException {
		this.accessed = System.currentTimeMillis();
		RestRequest restReq = RestRequest.getRestRequest(req);
		byte mode = CTISession.ABORT_NORMAL;
		String modeStr = restReq.getParameter("rest.mode");
		if (modeStr != null && modeStr.equals("force")) {
			mode = CTISession.ABORT_FORCE;
		}
		this.session.abort(mode);
	}

	/**
	 * 結果を結合します。
	 * 
	 * @throws IOException
	 * @throws FileUploadException
	 */
	void join() throws IOException, FileUploadException {
		this.accessed = System.currentTimeMillis();
		this.session.join();
	}

	/**
	 * セッションをリセットします。
	 * 
	 * @throws IOException
	 */
	void reset() throws IOException {
		this.accessed = System.currentTimeMillis();
		if (this.transcode != null) {
			this.transcode.dispose();
			this.transcode = null;
		}
		this.session.reset();
	}

	/**
	 * セッションを終了します。
	 * 
	 * @throws IOException
	 */
	void close() throws IOException {
		if (this.transcode != null) {
			this.transcode.dispose();
			this.transcode = null;
		}
		this.session.close();
	}
}
