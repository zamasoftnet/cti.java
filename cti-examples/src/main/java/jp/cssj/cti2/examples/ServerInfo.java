package jp.cssj.cti2.examples;

import java.io.InputStream;
import java.net.URI;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;

import org.apache.commons.io.IOUtils;

/**
 * Retrieves server information.
 */
public class ServerInfo {
	/** Server URI. */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8099/");

	/** User. */
	private static final String USER = "user";

	/** Password. */
	private static final String PASSWORD = "kappa";

	public static void main(String[] args) throws Exception {
		// Connect to the server
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// Version information
			{
				URI uri = URI.create("http://www.cssj.jp/ns/ctip/version");
				System.out.println("-- " + uri);
				InputStream in = session.getServerInfo(uri);
				IOUtils.copy(in, System.out);
			}
			// Output types
			{
				URI uri = URI.create("http://www.cssj.jp/ns/ctip/output-types");
				System.out.println("-- " + uri);
				InputStream in = session.getServerInfo(uri);
				IOUtils.copy(in, System.out);
			}
		}
	}
}