package jp.cssj.cti2.message;

/**
 * An interface for receiving messages.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: MessageHandler.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface MessageHandler {
	/**
	 * Receives a message.
	 * 
	 * @param code
	 *            The message code.
	 * @param args
	 *            The values associated with the message.
	 * @param mes
	 *            The message in a human-readable format.
	 */
	public void message(short code, String[] args, String mes);
}