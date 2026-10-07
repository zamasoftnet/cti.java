package jp.cssj.driver.ctip;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import jp.cssj.cti2.CTIDriver;
import jp.cssj.cti2.CTISession;
import jp.cssj.driver.ctip.v1.V1Session;
import jp.cssj.driver.ctip.v2.V2Session;

/**
 * <p>
 * A driver (client library) that uses socket communication.
 * </p>
 * <p>
 * The URI format is <tt>ctip://hostname:port/</tt>.
 * </p>
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIPDriver.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class CTIPDriver implements CTIDriver {
	public static final String ENCODING = "UTF-8";

	public boolean match(URI uri) {
		if (uri == null) {
			return false;
		}
		// A hierarchical URI starting with ctip: or ctips:
		return !uri.isOpaque() && ("ctip".equals(uri.getScheme()) || "ctips".equals(uri.getScheme()));
	}

	public CTISession getSession(URI uri, Map<String, String> props) throws IOException {
		String query = uri.getQuery();
		String user = null;
		String password = null;
		if (props != null) {
			user = (String) props.get("user");
			password = (String) props.get("password");
		}
		if (query != null) {
			String[] params = query.split("&");
			for (int i = 0; i < params.length; ++i) {
				if (params[0].equals("version=1")) {
					// Only v2 supports ctips:. Accepting it in v1 falls back to plaintext.
					V1Session.rejectSecureScheme(uri);
					return new V1Session(uri, ENCODING, user, password);
				}
			}
		}
		return new V2Session(uri, ENCODING, user, password);
	}
}