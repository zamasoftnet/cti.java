package jp.cssj.server.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
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

import org.apache.commons.fileupload.FileUploadException;

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

	private Map<String, RestSession> idToSession = Collections.synchronizedMap(new HashMap<String, RestSession>());

	private Random rnd = new SecureRandom();

	private CTIDriver driver;

	private URI ctiURI = null;

	private Map<String, String> ctiProps = null;

	private boolean restResolver = false;

	private boolean direct = false;

	private volatile long accessCount;

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
			this.ctiProps = new HashMap<String, String>();
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

		Thread ticker = new Thread(RestServlet.class.getName()) {
			public synchronized void run() {
				for (;;) {
					try {
						this.wait(DEFAULT_SESSION_TIMEOUT);
					} catch (InterruptedException e) {
						// ignore
					}
					RestServlet.this.clean();
				}
			}
		};
		ticker.setDaemon(true);
		ticker.start();
	}

	public long getAccessCount() {
		return this.accessCount;
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
		synchronized (this.idToSession) {
			List<String> toRemove = new ArrayList<String>();
			for (Iterator<Map.Entry<String, RestSession>> i = this.idToSession.entrySet().iterator(); i.hasNext();) {
				Map.Entry<String, RestSession> e = i.next();
				RestSession restSession = (RestSession) e.getValue();
				long timeout = System.currentTimeMillis() - restSession.timeout;
				if (restSession.getAccessed() < timeout) {
					toRemove.add(e.getKey());
					try {
						restSession.close();
					} catch (IOException ex) {
						LOG.log(Level.WARNING, "", ex);
					}
				}
			}
			for (int i = 0; i < toRemove.size(); ++i) {
				this.idToSession.remove(toRemove.get(i));
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
		RestSession restSession = (RestSession) this.idToSession.get(id);
		return restSession;
	}

	protected RestSession createSession(HttpServletRequest req, boolean messages, RestRequest restReq, long timeout)
			throws IOException, SecurityException, FileUploadException {
		Map<String, String> props;
		if (this.direct) {
			// Copper WEBAPP等認証を使わない場合
			props = null;
		} else {
			props = new HashMap<String, String>();
			if (this.ctiProps != null) {
				props.putAll(this.ctiProps);
			}
			props.put("user", restReq.getParameter("rest.user"));
			props.put("password", restReq.getParameter("rest.password"));
			String[] names = restReq.getParameterNames();
			for (int i = 0; i < names.length; ++i) {
				if (names[i].startsWith("rest.")) {
					props.put(names[i].substring(5), restReq.getParameter(names[i]));
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

		this.accessCount++;
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

			if (action.equals("open")) {
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
			} else if (action.equals("info")) {
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
			} else if (action.equals("properties")) {
				// プロパティ
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.properties(req);
			} else if (action.equals("resources")) {
				// リソース
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.resources(req, res);
			} else if (action.equals("transcode")) {
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
							RestServlet.sendMessage(req, res, e.getCode(), e.getMessage());
						}
					}
				} finally {
					if (id == null) {
						restSession.close();
					}
				}
				return;
			} else if (action.equals("noResource")) {
				// リソースなし
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.noResource(req);
			} else if (action.equals("messages")) {
				// メッセージ
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.messages(req, res);
				return;
			} else if (action.equals("result")) {
				// 処理結果
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.result(req, res);
				return;
			} else if (action.equals("abort")) {
				// 中断
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.abort(req);
			} else if (action.equals("join")) {
				// 結合
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.join();
			} else if (action.equals("reset")) {
				// リセット
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					RestServlet.sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				restSession.reset();
			} else if (action.equals("close")) {
				// 終了
				RestSession restSession = this.loadSession(id);
				if (restSession == null) {
					sendMessage(req, res, ERROR_NO_SESSION);
					return;
				}
				this.idToSession.remove(id);
				restSession.close();
			} else {
				RestServlet.sendMessage(req, res, ERROR_BAD_ACTION);
				return;
			}
			RestServlet.sendMessage(req, res, INFO_OK);
		} catch (SecurityException e) {
			RestServlet.sendMessage(req, res, ERROR_AUTHENTICATION_FAILURE);
			LOG.log(Level.FINE, "Authentication failure.", e);
		} catch (FileUploadException e) {
			RestServlet.sendMessage(req, res, ERROR_BAD_REQUEST);
			LOG.log(Level.WARNING, "Bad request.", e);
		} catch (URISyntaxException e) {
			RestServlet.sendMessage(req, res, CTIMessageCodes.ERROR_BAD_DOCUMENT_URI);
			LOG.log(Level.WARNING, "URI Syntax.", e);
		} catch (IOException e) {
			RestServlet.sendMessage(req, res, CTIMessageCodes.ERROR_IO);
			LOG.log(Level.WARNING, "I/O error.", e);
		} catch (Exception e) {
			RestServlet.sendMessage(req, res, CTIMessageCodes.FATAL_UNEXPECTED);
			LOG.log(Level.SEVERE, "Unexpected error.", e);
		}
	}

	private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(RestServlet.class.getName());
	private static final ResourceBundle CTI_BUNDLE = ResourceBundle.getBundle(CTIMessageCodes.class.getName());

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
			String level;
			switch (CTIMessageHelper.getLevel(code)) {
			case CTIMessageHelper.INFO:
				level = "INFO";
				break;
			default:
				level = "ERROR";
			}
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
