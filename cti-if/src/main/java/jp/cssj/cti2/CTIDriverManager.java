package jp.cssj.cti2;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The entry point for drivers.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIDriverManager.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class CTIDriverManager {
	private CTIDriverManager() {
		// hidden
	}

	/**
	 * Returns a driver for connecting to the specified URI.
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            The connection URI.
	 * @return The driver.
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
	 * Returns a session.
	 * <p>
	 * This is a convenience method for <tt>CTIDriver.getDriver(uri).getSession(null)</tt>.
	 * </p>
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            The connection URI.
	 * @return The session.
	 * @throws IOException
	 */
	public static CTISession getSession(URI uri) throws IOException {
		return getSession(uri, null);
	}

	/**
	 * Returns a session.
	 * <p>
	 * This is a convenience method for <tt>CTIDriver.getDriver(uri).getSession(props)</tt>.
	 * </p>
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            The connection URI.
	 * @return The session.
	 * @throws IOException
	 */
	public static CTISession getSession(URI uri, Map<String, String> props) throws IOException {
		CTIDriver driver = getDriver(uri);
		return driver.getSession(uri, props);
	}

	/**
	 * Returns a session.
	 * <p>
	 * This is a convenience method for <tt>CTIDriver.getDriver(uri).getSession(props)</tt>.
	 * Sets the user and password in the properties.
	 * </p>
	 * 
	 * @see CTIDriver#getSession(URI, Map)
	 * @param uri
	 *            The connection URI.
	 * @return The session.
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