package jp.cssj.cti2.helpers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.results.Results;
import jp.cssj.cti2.results.SingleResult;
import net.zamasoft.zstream.resolver.protocol.file.FileSource;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;
import net.zamasoft.zstream.resolver.protocol.url.URLSource;

/**
 * Utilities for sending client-side files, URLs, and streams.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTISessionHelper.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class CTISessionHelper {
	private CTISessionHelper() {
		// unused
	}

	/**
	 * Sets the output file.
	 * 
	 * @param session
	 * @param file
	 * @throws IOException
	 */
	public static void setResultFile(CTISession session, File file) throws IOException {
		Results results = new SingleResult(file);
		session.setResults(results);
	}

	/**
	 * Sets the output stream.
	 * 
	 * @param session
	 * @param out
	 * @throws IOException
	 */
	public static void setResultStream(CTISession session, OutputStream out) throws IOException {
		Results results = new SingleResult(out);
		session.setResults(results);
	}

	/**
	 * Sets multiple properties at once.
	 * 
	 * @param session
	 * @param props
	 * @throws IOException
	 */
	public static void properties(CTISession session, Properties props) throws IOException {
		for (Iterator<Map.Entry<Object, Object>> i = props.entrySet().iterator(); i.hasNext();) {
			Map.Entry<Object, Object> e = i.next();
			session.property((String) e.getKey(), (String) e.getValue());
		}
	}

	/**
	 * Sends a file as a resource.
	 * 
	 * @param session
	 *            The session
	 * @param file
	 *            The file
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void sendResourceFile(CTISession session, File file, String mimeType, String encoding)
			throws IOException {
		session.resource(new FileSource(file, mimeType, encoding));
	}

	/**
	 * Sends a URL as a resource.
	 * 
	 * @param session
	 *            The session
	 * @param url
	 *            URL
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void sendResourceURL(CTISession session, URL url, String mimeType, String encoding)
			throws IOException {
		try {
			session.resource(new URLSource(url, mimeType, encoding));
		} catch (URISyntaxException e) {
			IOException ioe = new IOException();
			ioe.initCause(e);
			throw ioe;
		}
	}

	/**
	 * Sends data read from a stream as a resource.
	 * 
	 * @param session
	 *            The session
	 * @param in
	 *            The input stream
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void sendResourceStream(CTISession session, InputStream in, URI uri, String mimeType, String encoding)
			throws IOException {
		session.resource(new StreamSource(uri, in, mimeType, encoding, -1L));
	}

	/**
	 * Sends all files in a directory as resources. This method also processes subdirectories recursively.
	 * 
	 * @param session
	 *            The session
	 * @param dir
	 *            The directory
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void sendResourceDir(CTISession session, File dir, String mimeType, String encoding)
			throws IOException {
		if (dir.isDirectory()) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (int i = 0; i < files.length; ++i) {
					sendResourceDir(session, files[i], mimeType, encoding);
				}
			}
		} else {
			sendResourceFile(session, dir, mimeType, encoding);
		}
	}

	/**
	 * Sends a file as the document body.
	 * 
	 * @param session
	 *            The session
	 * @param file
	 *            The file
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void transcodeFile(CTISession session, File file, String mimeType, String encoding)
			throws IOException, TranscoderException {
		session.transcode(new FileSource(file, mimeType, encoding));
	}

	/**
	 * Sends a URL as the document body.
	 * 
	 * @param session
	 *            The session
	 * @param url
	 *            URL
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void transcodeURL(CTISession session, URL url, String mimeType, String encoding)
			throws IOException, TranscoderException {
		try {
			session.transcode(new URLSource(url, mimeType, encoding));
		} catch (URISyntaxException e) {
			IOException ioe = new IOException();
			ioe.initCause(e);
			throw ioe;
		}
	}

	/**
	 * Sends data read from a stream as the document body.
	 * 
	 * @param session
	 *            The session
	 * @param in
	 *            The input stream
	 * @param mimeType
	 *            The MIME type (null if omitted)
	 * @param encoding
	 *            The encoding (null if omitted)
	 * @throws IOException
	 */
	public static void transcodeStream(CTISession session, InputStream in, URI uri, String mimeType, String encoding)
			throws IOException, TranscoderException {
		session.transcode(new StreamSource(uri, in, mimeType, encoding, -1L));
	}
}