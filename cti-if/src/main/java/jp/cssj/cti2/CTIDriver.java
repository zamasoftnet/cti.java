package jp.cssj.cti2;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * A driver (client library) for connecting to a document conversion server.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIDriver.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface CTIDriver {
	/**
	 * Returns whether this driver supports the specified URI.
	 * 
	 * @param uri
	 *            The connection URI.
	 * @return true if supported.
	 */
	public boolean match(URI uri);
	/**
	 * Creates a session.
	 * <p>
	 * The URI format depends on the driver type. Drivers for the CTIP and HTTP/REST protocols are currently available.
	 * </p>
	 * <p>
	 * For a CTIP connection, use a URI in the form "ctip://hostname:port/". To connect to a server that only
	 * supports CTIP 1.0 (Copper PDF 2.0 or earlier, or CSSJ), add the query parameter version=1,
	 * using the form "ctip://hostname:port/?version=1".
	 * </p>
	 * <p>
	 * For an HTTP/REST connection, use a URI in the form "http://hostname:port/".
	 * <strong>Remember the trailing slash.</strong>
	 * </p>
	 * 
	 * @param uri
	 *            The connection URI.
	 * @param props
	 *            Connection property key-value pairs. The "user" key specifies the username;
	 *            the "password" key specifies the password.
	 * @return The session.
	 * @throws IOException
	 * @throws SecurityException
	 *             if authentication fails.
	 */
	public CTISession getSession(URI uri, Map<String, String> props) throws IOException, SecurityException;
}