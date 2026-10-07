package jp.cssj.server.socket.ctip.helpers;

import java.io.IOException;

import net.zamasoft.zstream.io.FragmentedOutput;

/**
 * An interface for sending data from the server to the client.
 * 
 * In addition to receiving fragmented data, it tracks progress and receives error messages.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ResponseConsumer.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public interface ResponseConsumer extends FragmentedOutput {
	/**
	 * Sends a message.
	 * 
	 * @param code
	 * @param args
	 * @param message
	 * @throws IOException
	 */
	public void message(short code, String[] args, String message) throws IOException;
}