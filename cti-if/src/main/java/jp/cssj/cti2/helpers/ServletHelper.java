package jp.cssj.cti2.helpers;

import java.io.IOException;

import javax.servlet.ServletResponse;

import jp.cssj.cti2.CTISession;
import net.zamasoft.zstream.resolver.SourceMetadata;

/**
 * Utilities for using a document conversion server from a servlet.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ServletHelper.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class ServletHelper {
	private ServletHelper() {
		// unused
	}

	/**
	 * Sets a ServletResponse as the session's output destination.
	 * 
	 * @param session
	 *            The session whose output destination you want to set.
	 * @param response
	 *            The response to output the results to.
	 * @throws IOException
	 */
	public static void setServletResponse(final CTISession session, final ServletResponse response) throws IOException {
		session.setResults(new ServletResponseResults(response));
	}

	/**
	 * Returns a Content-Type header value with a charset parameter.
	 * 
	 * @param SourceMetadata
	 *            Metadata for the data.
	 * @return The Content-Type header with a charset parameter.
	 */
	public static String getContentType(SourceMetadata metaSource) {
		String mimeType;
		try {
			mimeType = metaSource.getMimeType();
			if (mimeType == null) {
				return null;
			}
			String encoding = metaSource.getEncoding();
			if (encoding != null) {
				mimeType += "; charset=" + encoding;
			}
		} catch (Exception e) {
			return null;
		}
		return mimeType;
	}
}
