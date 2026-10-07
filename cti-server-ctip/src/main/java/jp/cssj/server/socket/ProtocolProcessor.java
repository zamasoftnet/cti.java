package jp.cssj.server.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Communicates with the client after a connection is established.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ProtocolProcessor.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface ProtocolProcessor {
	/**
	 * Communicates with the client.
	 * 
	 * @param in
	 * @param out
	 * @param firstLine
	 *            The first line sent by the client.
	 * @throws IOException
	 */
	public void process(Socket socket, InputStream in, OutputStream out, String firstLine) throws IOException;

	/**
	 * Sends a message to the client.
	 * 
	 * @param code
	 * @param args
	 * @param message
	 * @throws IOException
	 */
	public void message(short code, String[] args, String message) throws IOException;

	/**
	 * Ends communication.
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException;
}
