package jp.cssj.server.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import jp.cssj.cti2.CTIDriver;
import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.helpers.CTIMessageHelper;
import jp.cssj.server.acl.Acl;

import org.apache.commons.fileupload.FileCountLimitExceededException;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.MultipartStream;

/**
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: RestServlet.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public class RestServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	public static final String CHARSET = "UTF-8";
	private static Logger LOG = Logger.getLogger(RestServlet.class.getName());
	private static final Logger ACCESS = Logger.getLogger("jp.cssj.copper.access");

	private final ConcurrentMap<String, RestSession> idToSession = new ConcurrentHashMap<>();

	private final Random rnd = new SecureRandom();

	private CTIDriver driver;

	private URI ctiURI = null;

	private Map<String, String> ctiProps = null;

	private boolean restResolver = false;

	private boolean direct = false;

	private final LongAdder accessCount = new LongAdder();

	enum MultipartFailure {
		REQUEST_TOO_LARGE,
		FILE_TOO_LARGE,
		FORM_FIELD_TOO_LARGE,
		PART_COUNT_TOO_LARGE,
		PART_HEADERS_TOO_LARGE,
		MALFORMED,
		IO_ERROR
	}

	private Thread cleaner = null;

	private static final long MAX_SESSION_TIMEOUT = 60000L * 60L;

	private static final long DEFAULT_SESSION_TIMEOUT = 60000L * 3L;

	/** 正常に処理された。 */
	public static final short INFO_OK = 0x1011;
	/** 新しいセッションが作られた。 */
	public static final short INFO_NEW_SESSION = 0x1012;
	/** 変換処理を実行中。 */
	public static final short INFO_TRANSCODING = 0x1013;
	/** 変換処理が完了済み。 */
	public static final short INFO_TRANDCODED = 0x1014;
	/** 不正なアクション。 */
	public static final short ERROR_BAD_ACTION = 0x3011;
	/** セッションが存在しない。 */
	public static final short ERROR_NO_SESSION = 0x3012;
	/** 変換対象文書が存在しない */
	public static final short ERROR_NO_DOCUMENT = 0x3013;
	/** 認証に失敗した。 */
	public static final short ERROR_AUTHENTICATION_FAILURE = 0x3014;
	/** 不正なリクエスト。 */
	public static final short ERROR_BAD_REQUEST = 0x3015;
	/** 結果が存在しない。 */
	public static final short ERROR_NO_RESULT = 0x3016;

	public void init(ServletConfig servletConfig) throws ServletException {
		super.init(servletConfig);
		String uri = servletConfig.getInitParameter("uri");
		String user = servletConfig.getInitParameter("user");
		String password = servletConfig.getInitParameter("password");
		String direct = servletConfig.getInitParameter("direct");
		if (uri != null) {
			this.ctiURI = URI.create(uri);
		}
		if (user != null || password != null) {
			this.ctiProps = new HashMap<>();
			if (user != null) {
				this.ctiProps.put("user", user);
			}
			if (password != null) {
				this.ctiProps.put("password", password);
			}
		}
		if ("1".equals(direct) || "true".equalsIgnoreCase(direct)) {
			this.direct = true;
		}
		String restResolver = servletConfig.getInitParameter("rest-resolver");
		if ("1".equals(restResolver) || "true".equalsIgnoreCase(restResolver)) {
			this.restResolver = true;
		}

		this.cleaner = Thread.ofVirtual().name(RestServlet.class.getName() + "-cleaner").start(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(DEFAULT_SESSION_TIMEOUT);
				} catch (InterruptedException e) {
					break;
				}
				this.clean();
			}
		});
	}

	@Override
	public void destroy() {
		Thread cleaner = this.cleaner;
		if (cleaner != null) {
			cleaner.interrupt();
			this.cleaner = null;
		}
		this.clean(true);
		super.destroy();
	}

	public long getAccessCount() {
		return this.accessCount.sum();
	}

	public URI getURI() {
		return this.ctiURI;
	}

	public void setURI(URI uri) {
		this.ctiURI = uri;
	}

	public CTIDriver getDriver() {
		if (this.driver == null) {
			this.driver = CTIDriverManager.getDriver(this.ctiURI);
		}
		return this.driver;
	}

	public void setDriver(CTIDriver driver) {
		this.driver = driver;
	}

	/**
	 * セッションの期限切れをチェックします。
	 */
	protected void clean() {
		this.clean(false);
	}

	private void clean(boolean all) {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, RestSession> e : this.idToSession.entrySet()) {
			RestSession restSession = e.getValue();
			if ((all || restSession.getAccessed() < now - restSession.timeout)
					&& this.idToSession.remove(e.getKey(), restSession)) {
				try {
					restSession.close();
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "", ex);
				}
			}
		}
	}

	/**
	 * セッションを開始します。
	 * 
	 * @param req
	 * @param id
	 * @param props
	 * @param timeout
	 * @throws IOException
	 * @throws SecurityException
	 * @throws FileUploadException
	 */
	protected void startSession(HttpServletRequest req, String id, RestRequest props, long timeout)
			throws IOException, SecurityException, FileUploadException {
		RestSession restSession = this.createSession(req, true, props, timeout);
		this.idToSession.put(id, restSession);
	}

	/**
	 * セッションを取得します。
	 * 
	 * @param id
	 * @return
	 */
	protected RestSession loadSession(String id) {
		// ConcurrentHashMap は null キーを許容しない
		return id == null ? null : this.idToSession.get(id);
	}

	protected RestSession createSession(HttpServletRequest req, boolean messages, RestRequest restReq, long timeout)
			throws IOException, SecurityException, FileUploadException {
		Map<String, String> props;
		if (this.direct) {
			// Copper WEBAPP等認証を使わない場合
			props = null;
		} else {
			props = new HashMap<>();
			if (this.ctiProps != null) {
				props.putAll(this.ctiProps);
			}
			props.put("user", restReq.getParameter("rest.user"));
			props.put("password", restReq.getParameter("rest.password"));
			for (String name : restReq.getParameterNames()) {
				if (name.startsWith("rest.")) {
					props.put(name.substring(5), restReq.getParameter(name));
				}
			}
			props.put("remote-addr", req.getRemoteAddr());
		}
		if (timeout == -1L) {
			timeout = DEFAULT_SESSION_TIMEOUT;
		}
		timeout = Math.min(timeout, MAX_SESSION_TIMEOUT);
		CTISession session = this.getDriver().getSession(this.ctiURI, props);
		RestSession restSession = new RestSession(session, messages, this.restResolver, timeout);
		return restSession;
	}

	protected void service(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, IOException {
		InetAddress remoteHost = InetAddress.getByName(req.getRemoteAddr());
		Acl acl = Acl.find(remoteHost);
		if (acl == null || !acl.checkAccess(remoteHost)) {
			ACCESS.info(remoteHost + "からのアクセスを拒否しました");
			return;
		}

		this.accessCount.increment();
		String method = req.getMethod();
		if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("POST")) {
			return;
		}
		req.setCharacterEncoding(CHARSET);

		try {
			String path = req.getPathInfo();
			if (path == null || !path.startsWith("/") || path.endsWith("/")) {
				RestServlet.sendMessage(req, res, ERROR_BAD_ACTION);
				return;
			}
			int slash = path.lastIndexOf('/');
			String action = path.substring(slash + 1);
			RestRequest restReq = new RestRequest(req);
			String restId = restReq.getParameter("rest.id");
			String id;
			if (restId != null) {
				restId = restId.trim();
				if (restId.length() == 0) {
					restId = null;
				}
			}
			if (restId != null) {
				id = restId;
			} else {
				HttpSession httpSession = req.getSession(false);
				if (httpSession != null) {
					id = httpSession.getId();
				} else {
					id = null;
				}
			}

			switch (action) {
			case "open" -> {
				// セッション開始
				if ("true".equals(restReq.getParameter("rest.httpSession"))) {
					HttpSession httpSession = req.getSession(true);
					id = httpSession.getId();
				} else {
					long idNum = rnd.nextLong();
					id = Long.toHexString(idNum);
				}
				String timeoutStr = restReq.getParameter("rest.timeout");
				long timeout = -1L;
				if (timeoutStr != null) {
					timeout = Long.parseLong(timeoutStr);
				}
				this.startSession(req, id, restReq, timeout);
				RestServlet.sendMessage(req, res, INFO_NEW_SESSION, id);
				return;
			}

			case "info" -> {
				// サーバー情報
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					if (restId != null) {
						RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
						return;
					}
					restSession = this.createSession(req, false, restReq, -1L);
				}
				try {
					res.setContentType("text/xml");
					res.setCharacterEncoding(CHARSET);
					restSession.info(req, res);
				} finally {
					if (id == null) {
						restSession.close();
					}
				}
				return;
			}

			case "properties" -> {
				// プロパティ
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.properties(req);
			}

			case "resources" -> {
				// リソース
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.resources(req, res);
			}

			case "transcode" -> {
				// 文書の変換
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					if (restId != null) {
						RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
						return;
					}
					restSession = this.createSession(req, false, restReq, -1L);
				}
				try {
					try {
						if (!restSession.transcode(req, res)) {
							RestServlet.sendMessage(req, res, ERROR_NO_DOCUMENT);
						}
					} catch (TranscoderException e) {
						if (e.getState() == TranscoderException.STATE_BROKEN) {
							RestServlet.sendBrokenTranscode(req, res, e);
						}
					}
				} finally {
					if (id == null) {
						restSession.close();
					}
				}
				return;
			}

			case "noResource" -> {
				// リソースなし
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.noResource(req);
			}

			case "messages" -> {
				// メッセージ
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.messages(req, res);
				return;
			}

			case "result" -> {
				// 処理結果
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.result(req, res);
				return;
			}

			case "abort" -> {
				// 中断
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.abort(req);
			}

			case "join" -> {
				// 結合
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.join();
			}

			case "reset" -> {
				// リセット
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.reset();
			}

			case "close" -> {
				// 終了
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				this.idToSession.remove(id);
				restSession.close();
			}

			default -> {
				RestServlet.sendMessage(req, res, ERROR_BAD_ACTION);
				return;
			}
			}
			RestServlet.sendMessage(req, res, INFO_OK);
		} catch (SecurityException e) {
			RestServlet.sendMessage(req, res, ERROR_AUTHENTICATION_FAILURE);
			LOG.log(Level.FINE, "Authentication failure.", e);
		} catch (FileUploadException e) {
			RestServlet.sendMultipartFailure(req, res, e);
		} catch (URISyntaxException e) {
			RestServlet.sendMessage(req, res, CTIMessageCodes.ERROR_BAD_DOCUMENT_URI);
			LOG.log(Level.WARNING, "URI Syntax.", e);
		} catch (IOException e) {
			MultipartFailure failure = classifyMultipartFailure(e);
			if (failure == MultipartFailure.IO_ERROR) {
				res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				RestServlet.sendMessage(req, res, CTIMessageCodes.ERROR_IO);
				LOG.log(Level.WARNING, "I/O error.", e);
			} else {
				RestServlet.sendMultipartFailure(req, res, e);
			}
		} catch (Exception e) {
			// **送信より先にログ**(2026-08-06)。逆順だと、応答が既に
			// 開かれている状況で sendMessage 自身が
			// IllegalStateException を投げ、**この記録が一度も残らない**。
			// 失敗が無音になる経路をここで断つ。
			LOG.log(Level.SEVERE, "Unexpected error.", e);
			RestServlet.sendMessage(req, res, CTIMessageCodes.FATAL_UNEXPECTED);
		}
	}

	static MultipartFailure classifyMultipartFailure(Throwable exception) {
		boolean fileUploadFailure = false;
		boolean fileUploadIoFailure = false;
		for (Throwable cause = exception; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
			if (cause instanceof RestRequest.FormFieldSizeLimitExceededException) {
				return MultipartFailure.FORM_FIELD_TOO_LARGE;
			}
			if (cause instanceof RestRequest.FileSizeLimitIOException
					|| cause instanceof FileUploadBase.FileSizeLimitExceededException) {
				return MultipartFailure.FILE_TOO_LARGE;
			}
			if (cause instanceof FileCountLimitExceededException) {
				return MultipartFailure.PART_COUNT_TOO_LARGE;
			}
			if (cause instanceof FileUploadBase.SizeLimitExceededException) {
				String message = cause.getMessage();
				if (message != null && message.startsWith("Header section has more than ")) {
					return MultipartFailure.PART_HEADERS_TOO_LARGE;
				}
				return MultipartFailure.REQUEST_TOO_LARGE;
			}
			if (cause instanceof MultipartStream.MalformedStreamException) {
				return MultipartFailure.MALFORMED;
			}
			if (cause instanceof FileUploadBase.IOFileUploadException) {
				fileUploadIoFailure = true;
			}
			if (cause instanceof FileUploadException) {
				fileUploadFailure = true;
			}
		}
		if (fileUploadIoFailure) {
			return MultipartFailure.IO_ERROR;
		}
		return fileUploadFailure ? MultipartFailure.MALFORMED : MultipartFailure.IO_ERROR;
	}

	static void sendMultipartFailure(HttpServletRequest req, HttpServletResponse res, Throwable exception)
			throws ServletException, IOException {
		MultipartFailure failure = classifyMultipartFailure(exception);
		switch (failure) {
		case REQUEST_TOO_LARGE, FILE_TOO_LARGE, FORM_FIELD_TOO_LARGE, PART_COUNT_TOO_LARGE,
				PART_HEADERS_TOO_LARGE -> {
			res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			RestServlet.sendMessage(req, res, ERROR_BAD_REQUEST);
			LOG.log(Level.FINE, "Multipart request rejected: {0}", failure);
		}
		case MALFORMED -> {
			res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			RestServlet.sendMessage(req, res, ERROR_BAD_REQUEST);
			LOG.log(Level.WARNING, "Malformed multipart request.", exception);
		}
		case IO_ERROR -> {
			res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			RestServlet.sendMessage(req, res, CTIMessageCodes.ERROR_IO);
			LOG.log(Level.WARNING, "Multipart I/O error.", exception);
		}
		}
	}

	private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(RestServlet.class.getName());
	private static final ResourceBundle CTI_BUNDLE = ResourceBundle.getBundle(CTIMessageCodes.class.getName());

	/**
	 * <b>出力を始めたあとで変換が壊れたことを、クライアントへ必ず伝えます</b>
	 * (2026-08-06新設)。
	 *
	 * <p>
	 * <b>直した不具合。</b>ここは以前 {@link #sendMessage} を直接呼んでいた。
	 * ところが変換の出力は{@code ServletResponseResults}が
	 * {@code getOutputStream()}で開いているので、{@code sendMessage}の中の
	 * {@code getWriter()}が
	 * {@code IllegalStateException: getOutputStream() already called}
	 * を投げる。それが外側の{@code catch (Exception)}へ落ち、そこでも
	 * {@code sendMessage}を呼ぶので<b>同じ例外でもう一度死ぬ</b>。
	 * しかも{@code LOG.log(SEVERE, ...)}はその後ろに置かれていたため
	 * 一度も実行されず、<b>失敗が完全に無音になっていた</b>。
	 * 最終的にコンテナがバッファ済みの部分PDFを
	 * <b>HTTP 200・正しいContent-Length</b>で送ってしまい、クライアントには
	 * 「エンジンが壊れたPDFを出した」としか見えなかった。
	 * </p>
	 *
	 * <p>
	 * <b>これは事故ではなく通常経路で踏める。</b>{@code output.page-limit}
	 * (中断の既定は{@code force})と{@code output.size-limit}はどちらも
	 * 出力の途中で変換を中断する。説明書は「出力の制限が働いた場合…
	 * エラーが通知されます」と書いているが、実測では
	 * {@code HTTP 200 + application/pdf + 壊れた本文}が返っていた
	 * (2026-08-06、CopperPDF4の実地コーパスの調査中に判明)。
	 * </p>
	 *
	 * <p>
	 * <b>直し方。</b>まだ送信していなければ{@code reset()}で部分的な出力を
	 * 捨ててからエラーを返す。Undertowの{@code reset()}は
	 * {@code writer}と{@code responseState}を初期状態へ戻すので、
	 * このあと{@code getWriter()}を呼べる(2.2.39のバイトコードで確認)。
	 * 既に送信済みなら訂正はできないので、<b>成功に見せないこと</b>だけを
	 * する——例外を送出して応答を壊し、クライアントに転送エラーとして
	 * 見せる。<b>どちらの経路でも先にログを残す。</b>
	 * </p>
	 */
	static void sendBrokenTranscode(final HttpServletRequest req, final HttpServletResponse res,
			final TranscoderException e) throws ServletException, IOException {
		// **まずログ**。この下の送信が何をしようと、失敗した事実は必ず残す。
		// committed を出すのは、訂正できたのかどうかを事後に区別するため。
		LOG.log(Level.WARNING, "Transcode broke after output had started (committed=" + res.isCommitted() + ").", e);
		if (res.isCommitted()) {
			// 応答は送信済み。訂正できないので、せめて成功に見せない
			throw new IOException("Transcode broke after the response was committed: " + e.getMessage(), e);
		}
		res.reset();
		RestServlet.sendMessage(req, res, e.getCode(), e.getMessage());
	}

	public static void sendMessage(final HttpServletRequest req, final HttpServletResponse res, short code)
			throws ServletException, IOException {
		String str = Integer.toHexString(code).toUpperCase();
		try {
			str = BUNDLE.getString(str);
		} catch (Exception e) {
			str = CTI_BUNDLE.getString(str);
		}
		sendMessage(req, res, code, str);
	}

	public static void sendMessage(final HttpServletRequest req, final HttpServletResponse res, short code,
			String message) throws ServletException, IOException {
		if ("html".equals(req.getParameter("rest.response"))) {
			// HTMLレスポンス
			res.setContentType("text/html");
			res.setCharacterEncoding(CHARSET);
			String level = switch (CTIMessageHelper.getLevel(code)) {
			case CTIMessageHelper.INFO -> "INFO";
			default -> "ERROR";
			};
			PrintWriter out = res.getWriter();
			out.println("<html>");
			out.println("<head>");
			out.println("<title>");
			out.println(level);
			out.println("</title>");
			out.println("<style type='text/css'>");
			out.println("h1 { font-size: 16pt; background-color: black; color: White; }");
			out.println("p.message { font-size: 14pt; }");
			out.println("p.code { position: fixed; bottom: 0; right: 0; font-size: 10pt; }");
			out.println("</style>");
			out.println("</head>");
			out.println("<body>");
			out.println("<h1>");
			out.println(level);
			out.println("</h1>");
			out.print("<p class='message'>");
			out.print(RestUtils.htmlEscape(message));
			out.print("</p>");
			out.print("<hr />");
			out.print("<p class='code'>");
			out.print(Integer.toHexString(code));
			out.print("</p>");
			out.println("</body>");
			out.println("</html>");
		} else {
			// XMLレスポンス
			res.setContentType("text/xml");
			res.setCharacterEncoding(CHARSET);
			PrintWriter out = res.getWriter();
			out.println("<?xml version=\"1.0\"?>");
			out.println("<response>");
			out.print("<message code=\"");
			out.print(Integer.toHexString(code));
			out.print("\">");
			out.print(RestUtils.htmlEscape(message));
			out.println("</message>");
			out.println("</response>");
		}
	}
}
