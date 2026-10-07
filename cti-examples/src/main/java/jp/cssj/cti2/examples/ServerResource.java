package jp.cssj.cti2.examples;

import java.io.File;
import java.net.URI;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTIMessageHelper;
import jp.cssj.cti2.helpers.CTISessionHelper;

/**
 * Retrieves data on the server and converts it.
 */
public class ServerResource {
	/** Server URI. */
	private static final URI SERVER_URI = URI.create("ctip://127.0.0.1:8099/");

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

			// Display error messages on standard error
			session.setMessageHandler(CTIMessageHelper.createStreamMessageHandler(System.err));

			// Create hyperlinks and bookmarks
			session.property("output.pdf.hyperlinks", "true");
			session.property("output.pdf.bookmarks", "true");

			// Allow access to resources under http://copper-pdf.com/
			session.property("input.include", "http://copper-pdf.com/**");

			// Convert the web page
			session.transcode(URI.create("http://copper-pdf.com/"));
		}
	}
}