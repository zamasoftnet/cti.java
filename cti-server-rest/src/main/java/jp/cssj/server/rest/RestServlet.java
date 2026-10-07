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
import jp.cssj.server.ConversionGate;
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

	/** Prefix for retrieving results by path ({@code /result/<sessionID>/<relativeURI>}). */
	private static final String RESULT_PATH_PREFIX = "/result/";

	private static final long MAX_SESSION_TIMEOUT = 60000L * 60L;

	private static final long DEFAULT_SESSION_TIMEOUT = 60000L * 3L;

	/** Successfully processed. */
	public static final short INFO_OK = 0x1011;
	/** A new session has been created. */
	public static final short INFO_NEW_SESSION = 0x1012;
	/** Conversion is in progress. */
	public static final short INFO_TRANSCODING = 0x1013;
	/** Conversion has completed. */
	public static final short INFO_TRANDCODED = 0x1014;
	/** Invalid action. */
	public static final short ERROR_BAD_ACTION = 0x3011;
	/** The session does not exist. */
	public static final short ERROR_NO_SESSION = 0x3012;
	/** The document to convert does not exist. */
	public static final short ERROR_NO_DOCUMENT = 0x3013;
	/** Authentication failed. */
	public static final short ERROR_AUTHENTICATION_FAILURE = 0x3014;
	/** Invalid request. */
	public static final short ERROR_BAD_REQUEST = 0x3015;
	/** The result does not exist. */
	public static final short ERROR_NO_RESULT = 0x3016;
	/** This session is converting (2026-10-03; previously, the request waited for it to finish). */
	public static final short ERROR_SESSION_BUSY = 0x3017;

	/** Recommended delay in seconds before retrying a refused request ({@code Retry-After}). */
	private static final String RETRY_AFTER_SECONDS = "5";

	/** Concurrent conversion limit (shared with CTIP; 2026-10-03). */
	private ConversionGate gate = ConversionGate.UNLIMITED;

	/** Sets the concurrent conversion limit. */
	public void setConversionGate(final ConversionGate gate) {
		this.gate = gate == null ? ConversionGate.UNLIMITED : gate;
	}

	/** Concurrent conversion limit (for status reporting). */
	public ConversionGate getConversionGate() {
		return this.gate;
	}

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
	 * Checks for expired sessions.
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
	 * Starts a session.
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
	 * Returns a session.
	 * 
	 * @param id
	 * @return
	 */
	protected RestSession loadSession(String id) {
		// ConcurrentHashMap does not allow null keys
		return id == null ? null : this.idToSession.get(id);
	}

	/**
	 * Decodes {@code Authorization: Basic …} into {user, password}. Returns {@code null}
	 * if the header is missing or malformed (treated as an authentication failure).
	 */
	static String[] basicCredentials(final String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
			return null;
		}
		try {
			final String decoded = new String(
					java.util.Base64.getDecoder().decode(authorization.substring(6).trim()),
					java.nio.charset.StandardCharsets.UTF_8);
			final int colon = decoded.indexOf(':');
			if (colon <= 0) {
				return null;
			}
			return new String[] { decoded.substring(0, colon), decoded.substring(colon + 1) };
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	protected RestSession createSession(HttpServletRequest req, boolean messages, RestRequest restReq, long timeout)
			throws IOException, SecurityException, FileUploadException {
		Map<String, String> props;
		if (this.direct) {
			// When authentication is not used, as in Copper WEBAPP
			props = null;
		} else {
			props = new HashMap<>();
			if (this.ctiProps != null) {
				props.putAll(this.ctiProps);
			}
			String user = restReq.getParameter("rest.user");
			String password = restReq.getParameter("rest.password");
			if (user == null && password == null) {
				// Check Authorization: Basic if rest.user / rest.password are absent.
				// (2026-09-02, requested by cti.li: credentials in the query string remain in upstream
				// access logs.) Parameters take precedence if present.
				final String[] basic = basicCredentials(req.getHeader("Authorization"));
				if (basic != null) {
					user = basic[0];
					password = basic[1];
				}
			}
			props.put("user", user);
			props.put("password", password);
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
		RestSession restSession = new RestSession(session, messages, this.restResolver, timeout, this.gate);
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
			// Retrieve results by path: /result/<sessionID>/<relativeURI>
			// (2026-08-28). Browsers resolve relative references within the result set directly.
			if (path.startsWith(RESULT_PATH_PREFIX)) {
				final String rest = path.substring(RESULT_PATH_PREFIX.length());
				final int sep = rest.indexOf('/');
				if (sep > 0 && sep + 1 < rest.length()) {
					final RestSession restSession = this.loadSession(rest.substring(0, sep));
					if (restSession == null) {
						RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
						return;
					}
					restSession.resultByPath(req, res, rest.substring(sep + 1));
					return;
				}
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
				// Start a session
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
				// Server information
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
				// Properties
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.properties(req);
			}

			case "resources" -> {
				// Resources
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.resources(req, res);
			}

			case "transcode" -> {
				// Convert a document
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
					} catch (ConversionRefusedException e) {
						// Refused without waiting (2026-10-03). Both clients that check the status code (cti.li's PHP)
						// and those that check the XML code (the Java REST driver) recognize the failure.
						res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
						res.setHeader("Retry-After", RETRY_AFTER_SECONDS);
						if (e.getArgs() == null) {
							RestServlet.sendMessage(req, res, e.getCode());
						} else {
							RestServlet.sendMessage(req, res, e.getCode(),
									CTIMessageHelper.toString(e.getCode(), e.getArgs()));
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
				// Missing resource
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.noResource(req);
			}

			case "messages" -> {
				// Messages
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.messages(req, res);
				return;
			}

			case "result" -> {
				// Processing results
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.result(req, res);
				return;
			}

			case "abort" -> {
				// Abort
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.abort(req);
			}

			case "join" -> {
				// Join
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.join();
			}

			case "reset" -> {
				// Reset
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.reset();
			}

			case "close" -> {
				// Close
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
			// **Log before sending** (2026-08-06). In the reverse order, sendMessage itself
			// throws IllegalStateException when the response is already open,
			// and **this log entry is never written**.
			// Prevent failures from going unreported here.
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
	 * <b>Always informs the client that conversion failed after output started</b>
	 * (added on 2026-08-06).
	 *
	 * <p>
	 * <b>The bug fixed here.</b> This code previously called {@link #sendMessage} directly.
	 * However, {@code ServletResponseResults} had opened the conversion output with
	 * {@code getOutputStream()}, so {@code getWriter()} inside {@code sendMessage} threw
	 * {@code IllegalStateException: getOutputStream() already called}.
	 * The outer {@code catch (Exception)} caught it and also called {@code sendMessage},
	 * <b>failing again with the same exception</b>. Moreover, {@code LOG.log(SEVERE, ...)}
	 * came after that call, so it never ran, and <b>the failure went completely unreported</b>.
	 * The container ultimately sent the buffered partial PDF with
	 * <b>HTTP 200 and a correct Content-Length</b>. To the client, it simply looked as though
	 * "the engine produced a broken PDF."
	 * </p>
	 *
	 * <p>
	 * <b>This can occur through normal operation, not just by accident.</b> Both {@code output.page-limit}
	 * (whose default abort mode is {@code force}) and {@code output.size-limit} abort conversion
	 * during output. The manual says, "When an output limit takes effect … an error is reported,"
	 * but measurements showed a response of {@code HTTP 200 + application/pdf + broken body}
	 * (discovered on 2026-08-06 while investigating the CopperPDF4 real-world corpus).
	 * </p>
	 *
	 * <p>
	 * <b>The fix.</b> If the response has not been sent yet, discard the partial output with
	 * {@code reset()} before returning an error. Undertow's {@code reset()} restores
	 * {@code writer} and {@code responseState} to their initial states, so {@code getWriter()}
	 * can be called afterward (verified in the 2.2.39 bytecode). If the response has already
	 * been sent, it cannot be corrected, so the only action is to <b>avoid presenting it as a success</b>:
	 * throw an exception to break the response and expose a transfer error to the client.
	 * <b>Log first in both paths.</b>
	 * </p>
	 */
	static void sendBrokenTranscode(final HttpServletRequest req, final HttpServletResponse res,
			final TranscoderException e) throws ServletException, IOException {
		// **Log first**. Always record the failure, regardless of what the send operation below does.
		// Log committed so we can tell afterward whether the response could be corrected.
		LOG.log(Level.WARNING, "Transcode broke after output had started (committed=" + res.isCommitted() + ").", e);
		if (res.isCommitted()) {
			// The response has already been sent. It cannot be corrected, so at least avoid presenting it as a success.
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
			// HTML response
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
			// XML response
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
