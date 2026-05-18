package jp.cssj.driver.rest;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import jp.cssj.cti2.CTIDriver;
import jp.cssj.cti2.CTISession;

/**
 * <p>
 * HTTP/REST通信を利用するドライバです。
 * </p>
 * <p>
 * URIの形式は <tt>http://ホスト名:ポート番号/</tt> です。
 * </p>
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: RestDriver.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class RestDriver implements CTIDriver {
	public boolean match(URI uri) {
		if (uri == null) {
			return false;
		}
		// http:またはhttps:で始まる透明URI
		return !uri.isOpaque() && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
	}

	public CTISession getSession(URI uri, Map<String, String> props) throws IOException {
		String user = null, password = null;
		if (props != null) {
			user = (String) props.get("user");
			password = (String) props.get("password");
		}
		return new RestSession(uri, user, password);
	}
}