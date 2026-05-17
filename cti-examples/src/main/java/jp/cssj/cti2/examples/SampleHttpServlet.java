package jp.cssj.cti2.examples;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.ServletHelper;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.resolver.protocol.url.URLSource;
import net.zamasoft.zstream.resolver.protocol.url.URLSourceResolver;

public class SampleHttpServlet extends HttpServlet {
	private static final long serialVersionUID = 0L;

	/** 接続先。 */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8099/");

	/** ユーザー。 */
	private static final String USER = "user";

	/** パスワード。 */
	private static final String PASSWORD = "kappa";

	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// 出力先をレスポンスに設定
			ServletHelper.setServletResponse(session, res);
			// リソースを読み込むためのオブジェクトを設定
			session.setSourceResolver(new URLSourceResolver());
			// 目次、ページ参照のための情報収集設定
			session.property("processing.page-references", "true");
			session.property("processing.pass-count", "2");

			// ストリームを定義
			try (OutputStreamWriter outHtmlStr = new OutputStreamWriter(
					session.transcode(new SimpleSourceMetadata(URI.create("."), "text/html", null, -1)), "UTF-8")) {
				// ストリームに文字列を出力
				outHtmlStr.write("xxx<img id=\"arrow\" src=\"arrow.png\" alt=\"arror\" />yyy");
			}
		}
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
