package jp.cssj.cti2.examples;

import java.io.File;
import java.net.URI;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTISessionHelper;

/**
 * Converts data sent from the client.
 */
public class Continuous {
	/** Server URI. */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8101/");

	/** User. */
	private static final String USER = "user";

	/** Password. */
	private static final String PASSWORD = "kappa";

	public static void main(String[] args) throws Exception {
		// Connect to the server
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// Write the result to test.pdf
			File file = new File("test.pdf");
			CTISessionHelper.setResultFile(session, file);
			session.setContinuous(true);

			// Send resources
			CTISessionHelper.sendResourceFile(session, new File("a/common.css"), "text/css", null);
			CTISessionHelper.sendResourceFile(session, new File("a/MEM093110.css"), "text/css", null);
			session.property("processing.page-references", "true");

			session.property("processing.middle-pass", "true");
			// Send the document
			CTISessionHelper.transcodeFile(session, new File("a/sample1.html"), "text/html", null);

			session.property("processing.middle-pass", "false");
			// Send the document
			CTISessionHelper.transcodeFile(session, new File("a/sample2.html"), "text/html", null);

			// Join the results
			session.join();
		}
	}
}