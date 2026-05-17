package jp.cssj.cti2.examples;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTIHttpServletResponseWrapper;
import jp.cssj.cti2.helpers.ServletHelper;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.protocol.url.URLSource;

public class SampleFilter implements Filter {
	/** 接続先。 */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8099/");

	/** ユーザー。 */
	private static final String USER = "user";

	/** パスワード。 */
	private static final String PASSWORD = "kappa";

	private FilterConfig config;

	public void init(FilterConfig config) throws ServletException {
		this.config = config;
	}

	public void doFilter(ServletRequest _req, ServletResponse _res, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest req = (HttpServletRequest) _req;
		HttpServletResponse res = (HttpServletResponse) _res;
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// 出力先をレスポンスに設定
			ServletHelper.setServletResponse(session, res);

			// コンテキスト上に置かれたリソースを使う
			session.setSourceResolver(new ServletContextResolver(this.config.getServletContext()));

			// 基底URLとしてコンテキスト以降のパスを使う
			URI uri = URI.create(req.getRequestURI().substring(req.getContextPath().length()));

			// サーブレットが出力したコンテンツを変換
			try (CTIHttpServletResponseWrapper ctiRes = new CTIHttpServletResponseWrapper((HttpServletResponse) res,
					session, uri)) {
				chain.doFilter(req, ctiRes);
			}
		}
	}

	public void destroy() {
		// ignore
	}

	static class ServletContextResolver implements SourceResolver {
		protected final ServletContext context;

		public ServletContextResolver(ServletContext context) {
			this.context = context;
		}

		public Source resolve(URI uri) throws IOException {
			// コンテキストに置かれたファイルを取得する
			URL url = this.context.getResource(uri.toString());
			if (url == null) {
				throw new FileNotFoundException(uri.toString());
			}
			try {
				return new URLSource(url);
			} catch (URISyntaxException e) {
				IOException ioe = new IOException();
				ioe.initCause(e);
				throw ioe;
			}
		}

		public void release(Source source) {
			((URLSource) source).close();
		}
	}
}
