package jp.cssj.cti2.examples;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import org.apache.commons.io.IOUtils;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTISessionHelper;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

/**
 * Converts data sent from the client.
 */
public class ClientResource {
	/** Server URI. */
	private static final URI SERVER_URI = URI.create("ctip://localhost:8101/");

	/** User. */
	private static final String USER = "user";

	/** Password. */
	private static final String PASSWORD = "kappa";

	public static void main(String[] args) throws Exception {
		// Connect to the server
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// Write the result to test.pdf
			File file = new File("test.pdf");
			File inFile = new File("sample2.html");
			CTISessionHelper.setResultFile(session, file);

			SourceResolver sourceResolver = new SourceResolver() {
				public Source resolve(URI uri) throws IOException,FileNotFoundException {
					System.out.println("resolve: " + uri);
					return null;
				}

				public void release(Source source) {
					// ignore
				}
			};
    		session.setSourceResolver( sourceResolver );

			// Get the output stream
			try (OutputStream out = 
					session.transcode(new SimpleSourceMetadata(URI.create("."), "text/html", "UTF-8", -1))) {
				// Read the input file and write the conversion result
				try (InputStream in = new FileInputStream(inFile)) {
					IOUtils.copy(in, out);
				}
			}
		}
	}
}