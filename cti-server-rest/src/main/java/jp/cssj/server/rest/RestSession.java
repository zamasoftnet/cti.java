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
	 */
	protected record Message(short code, String[] args, String text) {
		protected Message {
			args = args == null ? null : args.clone();
		}
	}

	/**
	 * メッセージを受信します。
	 * 
	 * @author MIYABE Tatsuhiko
	 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
	 */
	protected class Messages implements MessageHandler {
		private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

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
			return this.messages.remove(0);
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
		private volatile boolean transcoding = false;
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

			// 検査と待機を同じ監視の中で行う(2026-09-02)。以前は検査が監視の外で、
			// 完了側の finally が transcoding=false にしてから notifyAll するまでの
			// 隙間に検査が通ると、通知を取り逃して次のメッセージまで眠っていた
			synchronized (RestSession.this) {
				while (this.transcoding) {
					try {
						RestSession.this.wait(1000);
					} catch (InterruptedException e) {
						// ignore
					}
				}
				this.transcoding = true;
			}
			if (async) {
				this.th = Thread.ofVirtual().name(RestServlet.class.getName()).start(this);
				RestServlet.sendMessage(req, res, RestServlet.INFO_OK);
			} else {
				this.syncTranscode(res);
			}
		}

		/**
		 * 出力を一時ファイルへ受けてから応答へ流します(2026-08-06新設)。
		 *
		 * <p>
		 * <b>なぜ直接応答へ書かないか。</b>以前は
		 * {@link ServletResponseResults}でサーブレットの出力ストリームへ
		 * 直接書いていた。ところが変換が<b>出力を始めたあとで中断</b>すると
		 * ——{@code output.page-limit}(中断の既定は{@code force})・
		 * {@code output.size-limit}・エンジン側の中断のいずれでも起こる——
		 * 後始末の{@code builder.close()}が
		 * <b>途中までの出力を「正しいContent-Lengthを持つ完結した応答」として
		 * 確定させて</b>しまう。サーブレットが例外を受け取ったときには
		 * {@code isCommitted()}が既に真で、訂正のしようがない。
		 * 結果、クライアントには<b>HTTP 200 + application/pdf + 壊れた本文</b>
		 * が返り、「エンジンが壊れたPDFを出した」としか見えなかった
		 * (2026-08-06に実測で確認)。説明書
		 * (CopperPDFの「動作の制限」)は「出力の制限が働いた場合…
		 * エラーが通知されます」と書いており、実装がそれに反していた。
		 * </p>
		 *
		 * <p>
		 * <b>この変更で失うもの。</b>実測では失うものがない。同期経路は
		 * 変換中クライアントへ1バイトも届いておらず(23MBの文書を1秒ごとに
		 * 観測して確認、2026-08-06)、既に実質ストリーミングしていない。
		 * 非同期経路は以前から一時ファイルを使っている。
		 * </p>
		 */
		private class SpooledResults implements Results {
			private final File file;
			private final long time = System.currentTimeMillis();
			private FragmentedOutput builder = null;
			private SourceMetadata metaSource = null;

			SpooledResults(File file) {
				this.file = file;
			}

			public boolean hasNext() {
				return this.builder == null;
			}

			public FragmentedOutput nextBuilder(SourceMetadata metaSource) throws IOException {
				if (this.builder != null) {
					throw new IllegalStateException();
				}
				this.metaSource = metaSource;
				// **閉じるのは1回だけ**にする。中身が一時ファイルへ確定するのは
				// close の時なので{@link #sendTo}が先に閉じるが、そのあとで
				// エンジンの後始末({@code PDFUserAgent.dispose})がもう一度
				// 閉じにくる。2度目に断片を組み直させない。
				this.builder = new FileFragmentedOutput(this.file) {
					private boolean closed = false;

					public void close() throws IOException {
						if (this.closed) {
							return;
						}
						this.closed = true;
						super.close();
					}
				};
				return this.builder;
			}

			public void end() {
				// NOP
			}

			/**
			 * <b>変換が成功したときだけ</b>呼びます。ここで初めて応答へ触るので、
			 * 失敗したときの応答は未確定のまま残り、エラーを返せます。
			 */
			void sendTo(HttpServletResponse res) throws IOException {
				if (this.builder == null) {
					// 出力が1つも作られなかった
					return;
				}
				// **先に閉じる。**一時ファイルへ中身が確定するのは close の時で、
				// エンジンは変換の完了時点ではまだ閉じていない(旧実装は応答へ
				// 直接書いていたので閉じる前からバイトが出ていた)。
				this.builder.close();
				long length = this.file.length();
				RestSession.this.done(length, System.currentTimeMillis() - this.time);
				if (this.metaSource != null && this.metaSource.getMimeType() != null) {
					res.setContentType(ServletHelper.getContentType(this.metaSource));
				}
				res.setContentLengthLong(length);
				try (InputStream in = new FileInputStream(this.file)) {
					IOUtils.copy(in, res.getOutputStream());
				}
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
			final File spool = File.createTempFile("copper-rest-sync-", ".dat");
			try {
				RestSession.this.session.setProgressListener(this);
				// １つだけ結果を取得する
				SpooledResults results = new SpooledResults(spool);
				RestSession.this.session.setResults(results);
				if (this.uri != null) {
					RestSession.this.session.transcode(this.uri);
				} else {
					RestSession.this.session.transcode(this.source);
				}
				// **ここまで来たら成功**。応答へ触るのはこの一点だけ
				results.sendTo(res);
			} catch (IOException e) {
				this.ex = e;
				throw e;
			} finally {
				this.transcoding = false;
				// 一時ファイルは成功・失敗によらず必ず消す
				if (!spool.delete()) {
					spool.deleteOnExit();
				}
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
				this.resultList = new ArrayList<>();
				this.uriToResult = new HashMap<>();
				this.uriToSourceMetadata = new HashMap<>();
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
				for (File file : this.uriToResult.values()) {
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

	/**
	 * クライアントがリソースを見つけられなかったことを伝えます。
	 *
	 * <p>
	 * CTIP2 の {@code MISSING_RESOURCE} パケットに相当します。待っている
	 * {@link Transcode#resolve(URI)} を <b>見つからなかった</b>として
	 * 終わらせます(要求を取り下げると {@code FileNotFoundException} に
	 * なります)。
	 * </p>
	 *
	 * @param uri 見つからなかったリソースのURI。nullなら要求中のもの。
	 * @return 要求中のリソースと一致して取り下げたならtrue。
	 */
	private boolean resourceNotFound(final URI uri) {
		if (this.transcode == null) {
			return false;
		}
		synchronized (this.transcode) {
			final URI required = this.transcode.requiredResource;
			if (required == null || (uri != null && !required.equals(uri))) {
				return false;
			}
			this.transcode.requiredResource = null;
			this.transcode.notify();
			return true;
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

		if ("yes".equals(restReq.getParameter("rest.notFound"))) {
			// **クライアントがリソースを見つけられなかった。**
			// これを読まないと、本体の無いこの要求が「0バイトのリソース」
			// として扱われ、見つからなかったはずのCSSや画像が空の内容で
			// 解決されてしまう(2026-08-03)。CTIP2の MISSING_RESOURCE と
			// 同じ意味にする
			URI missing = null;
			if (uri != null) {
				try {
					missing = URIHelper.create(RestServlet.CHARSET, uri);
				} catch (URISyntaxException e) {
					this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
				}
			}
			this.resourceNotFound(missing);
			return;
		}

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
					String text = RestUtils.htmlEscape(message.text());
					out.print("<message code=\"" + Integer.toHexString(message.code()) + "\"");
					if (message.args() != null) {
						for (int i = 0; i < message.args().length; ++i) {
							out.print(" arg" + i + "=\"" + RestUtils.htmlEscape(message.args()[i]) + "\"");
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
					for (URI uri : this.transcode.resultList) {
						final SourceMetadata metaSource = this.transcode.uriToSourceMetadata.get(uri);
						out.print("<result uri=\"");
						out.print(RestUtils.htmlEscape(uri.toString()));
						out.print("\"");
						if (metaSource != null) {
							final String mimeType = metaSource.getMimeType();
							if (mimeType != null) {
								out.print(" mimeType=\"");
								out.print(RestUtils.htmlEscape(mimeType));
								out.print("\"");
							}
							final String encoding = metaSource.getEncoding();
							if (encoding != null) {
								out.print(" encoding=\"");
								out.print(RestUtils.htmlEscape(encoding));
								out.print("\"");
							}
						}
						final File resultFile = this.transcode.uriToResult.get(uri);
						if (resultFile != null) {
							out.print(" length=\"");
							out.print(resultFile.length());
							out.print("\"");
						}
						out.println("/>");
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
		String uri = restReq.getParameter("rest.uri");
		if (uri == null) {
			uri = ".";
		}
		this.writeResult(req, res, uri, false);
	}

	/**
	 * パス形式({@code /result/<セッションID>/<相対URI>})で結果を返します
	 * (2026-08-28)。
	 *
	 * <p>
	 * 結果集合(ページ分割SVG等)は相対URIで互いを参照します。問い合わせ
	 * 形式({@code ?rest.uri=…})では、受け取ったページの中の
	 * {@code ../assets/…}をクライアントが自分で書き換えるしかありません。
	 * パス形式なら<b>ブラウザの相対解決がそのまま当たる</b>ので、
	 * 書き換えも資源の先読みも要りません。
	 * </p>
	 */
	void resultByPath(HttpServletRequest req, HttpServletResponse res, String uri)
			throws IOException, FileUploadException, ServletException {
		this.accessed = System.currentTimeMillis();
		if (this.transcode == null || this.transcode.uriToResult == null) {
			RestServlet.sendMessage(req, res, RestServlet.ERROR_NO_RESULT);
			return;
		}
		this.writeResult(req, res, uri, true);
	}

	/**
	 * @param declareEncoding gzipで縮めた結果に{@code Content-Encoding}を
	 *                        付けるか。パス形式だけで付ける——問い合わせ形式は
	 *                        既存クライアントが生バイトを受け取る前提のため
	 */
	private void writeResult(HttpServletRequest req, HttpServletResponse res, String uri, boolean declareEncoding)
			throws IOException, FileUploadException, ServletException {
		try {
			URI resultURI = URIHelper.create(RestServlet.CHARSET, uri);
			File file = this.transcode.uriToResult.get(resultURI);
			if (file == null) {
				throw new FileNotFoundException(resultURI.toString());
			}
			SourceMetadata metaSource = this.transcode.uriToSourceMetadata.get(resultURI);
			res.setContentLengthLong(file.length());
			res.setContentType(ServletHelper.getContentType(metaSource));
			if (declareEncoding) {
				final String name = resultURI.toString();
				if (name.endsWith(".gz") || name.endsWith(".svgz")) {
					// 中身はgzip。宣言しておけばブラウザが解いて渡す
					res.setHeader("Content-Encoding", "gzip");
				}
			}
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
