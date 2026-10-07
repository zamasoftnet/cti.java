package jp.cssj.cti2.examples;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.jakarta.CTIHttpServletResponseWrapper;
import jp.cssj.cti2.helpers.jakarta.ServletHelper;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.protocol.url.URLSource;

public class JakartaSampleFilter implements Filter {
	/** Server URI. */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8099/");

	/** User. */
	private static final String USER = "user";

	/** Password. */
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
			// Set the response as the output destination
			ServletHelper.setServletResponse(session, res);

			// Use resources stored in the servlet context
			session.setSourceResolver(new ServletContextResolver(this.config.getServletContext()));

			// Use the path after the context path as the base URL
			URI uri = URI.create(req.getRequestURI().substring(req.getContextPath().length()));

			// Convert the content output by the servlet
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
