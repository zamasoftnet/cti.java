package jp.cssj.cti2.examples;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.jakarta.CTIHttpServletResponseWrapper;
import jp.cssj.cti2.helpers.jakarta.ServletHelper;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.protocol.url.URLSource;

public class JakartaSampleHttpServlet extends HttpServlet {
	private static final long serialVersionUID = 0L;

	/** Server URI. */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8099/");

	/** User. */
	private static final String USER = "user";

	/** Password. */
	private static final String PASSWORD = "kappa";

	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// Set the response as the output destination
			ServletHelper.setServletResponse(session, res);

			// Use resources stored in the servlet context
			session.setSourceResolver(new ServletContextResolver(this.getServletContext()));

			String path = ((HttpServletRequest) req).getPathInfo();
			URI uri = URI.create(path);
			CTIHttpServletResponseWrapper ctiRes = new CTIHttpServletResponseWrapper(
					res, session, uri);
			try {
				req.getRequestDispatcher(path).forward(req, ctiRes);
			} finally {
				ctiRes.close();
			}
		}
	}

	static class ServletContextResolver implements SourceResolver {
		protected final ServletContext context;

		public ServletContextResolver(ServletContext context) {
			this.context = context;
		}

		public Source resolve(URI uri) throws IOException {
			// Retrieve a file stored in the servlet context
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
