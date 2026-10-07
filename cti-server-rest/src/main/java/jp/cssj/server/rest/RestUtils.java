package jp.cssj.server.rest;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.servlet.ServletFileUpload;

/**
 * Utilities for the REST interface.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: RestUtils.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class RestUtils {
	private RestUtils() {
		// unused
	}

	/**
	 * Escapes an HTML string.
	 * 
	 * @param strVar
	 * @return
	 */
	public static String htmlEscape(String strVar) {
		if (strVar == null) {
			return "";
		}
		StringBuffer strEsc = new StringBuffer();
		for (int i = 0; i < strVar.length(); i++) {
			switch (strVar.charAt(i)) {
			case '<':
				strEsc.append("&lt;");
				break;
			case '>':
				strEsc.append("&gt;");
				break;
			case '&':
				strEsc.append("&amp;");
				break;
			case '"':
				strEsc.append("&quot;");
				break;
			case '\'':
				strEsc.append("&apos;");
				break;
			default:
				strEsc.append(strVar.charAt(i));
				break;
			}
		}
		return strEsc.toString();
	}

	public static boolean isForm(HttpServletRequest req) {
		if (ServletFileUpload.isMultipartContent(req)) {
			return true;
		}
		if ("application/x-www-form-urlencoded".equals(req.getContentType())) {
			return true;
		}
		return false;
	}
}
