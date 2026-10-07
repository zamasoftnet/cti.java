package jp.cssj.cti2.helpers;

import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ResourceBundle;

import jp.cssj.cti2.message.MessageHandler;

/**
 * Message utilities.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIMessageHelper.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class CTIMessageHelper {
	private CTIMessageHelper() {
		// unused
	}

	/**
	 * A message handler that writes to standard output.
	 * 
	 * @deprecated Use createStreamMessageHandler(System.out).
	 */
	public static final MessageHandler STDOUT = new StreamMessageHandler(System.out);

	/**
	 * A message handler that writes to standard error.
	 * 
	 * @deprecated Use createStreamMessageHandler(System.err).
	 */
	public static final MessageHandler STDERR = new StreamMessageHandler(System.err);

	public static MessageHandler createStreamMessageHandler(PrintStream out) {
		return new StreamMessageHandler(out);
	}

	/**
	 * A message handler that displays nothing.
	 */
	public static final MessageHandler NULL = new MessageHandler() {
		public void message(short code, String[] args, String mes) {
			// ignore
		}
	};

	/**
	 * An information-level message.
	 */
	public static final short INFO = 1;
	/**
	 * A warning-level message.
	 */
	public static final short WARN = 2;
	/**
	 * An error-level message.
	 */
	public static final short ERROR = 3;
	/**
	 * A fatal error message.
	 */
	public static final short FATAL = 4;

	/**
	 * Returns the error level.
	 * 
	 * @param code
	 * @return The error level value.
	 */
	public static final short getLevel(short code) {
		return (short) (code >> 12 & 0xF);
	}

	private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(CTIMessageCodes.class.getName());

	/**
	 * Returns the message format for the message code.
	 * 
	 * @param code
	 * @return The java.text.MessageFormat pattern for the message code.
	 */
	public static String getFormat(short code) {
		String str = Integer.toHexString(code).toUpperCase();
		str = BUNDLE.getString(str);
		return str;
	}

	/**
	 * Converts the message to a string.
	 * 
	 * @param code
	 * @return The message as a string.
	 */
	public static String toString(short code, String[] args) {
		String str = getFormat(code);
		if (args != null) {
			for (int i = 0; i < args.length; ++i) {
				if (args[i] != null && args[i].length() > 2083) {
					args[i] = args[i].substring(0, 2080) + "...";
				}
			}
		}
		str = MessageFormat.format(str, (Object[]) args);
		return str;
	}
}

class StreamMessageHandler implements MessageHandler {
	final PrintStream out;

	StreamMessageHandler(PrintStream out) {
		this.out = out;
	}

	public void message(short code, String[] args, String mes) {
		if (mes != null) {
			this.out.println(mes);
			return;
		}
		// Even if a remote implementation or custom driver does not supply formatted text,
		// do not silently turn the diagnostic into "null". Always retain the message code and raw arguments.
		final StringBuilder fallback = new StringBuilder(32);
		fallback.append('[').append(Integer.toHexString(code & 0xFFFF).toUpperCase()).append(']');
		if (args != null) {
			for (String arg : args) {
				fallback.append(' ').append(String.valueOf(arg));
			}
		}
		this.out.println(fallback.toString());
	}
}
