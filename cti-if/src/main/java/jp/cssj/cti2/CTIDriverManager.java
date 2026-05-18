package jp.cssj.cti2;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * ドライバの窓口クラスです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIDriverManager.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class CTIDriverManager {
	private CTIDriverManager() {
		// hidden
	}

	/**
	 * 指定されたURIへ接続するためのドライバを返します。
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            接続先URI。
	 * @return ドライバ。
	 */
	public static CTIDriver getDriver(URI uri) {
		for (CTIDriver driver : ServiceLoader.load(CTIDriver.class)) {
			if (driver.match(uri)) {
				return driver;
			}
		}
		throw new RuntimeException(uri + " に接続するドライバがありません。");
	}

	/**
	 * セッションを返します。
	 * <p>
	 * これは<tt>CTIDriver.getDriver(uri).getSession(null)</tt>の簡易メソッドです。
	 * </p>
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            接続先URI。
	 * @return セッション。
	 * @throws IOException
	 */
	public static CTISession getSession(URI uri) throws IOException {
		return getSession(uri, null);
	}

	/**
	 * セッションを返します。
	 * <p>
	 * これは<tt>CTIDriver.getDriver(uri).getSession(props)</tt>の簡易メソッドです。
	 * </p>
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            接続先URI。
	 * @return セッション。
	 * @throws IOException
	 */
	public static CTISession getSession(URI uri, Map<String, String> props) throws IOException {
		CTIDriver driver = getDriver(uri);
		return driver.getSession(uri, props);
	}

	/**
	 * セッションを返します。
	 * <p>
	 * これは<tt>CTIDriver.getDriver(uri).getSession(props)</tt>の簡易メソッドです。
	 * プロパティにユーザー、パスワードを設定します。
	 * </p>
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            接続先URI。
	 * @return セッション。
	 * @throws IOException
	 */
	public static CTISession getSession(URI uri, String user, String password) throws IOException {
		CTIDriver driver = getDriver(uri);
		Map<String, String> props = null;
		if (user != null || password != null) {
			props = new HashMap<String, String>();
			props.put("user", user);
			props.put("password", password);
		}
		return driver.getSession(uri, props);
	}
}