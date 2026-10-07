package jp.cssj.cti2;

import java.io.IOException;

/**
 * An exception that indicates document conversion has been aborted.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: TranscoderException.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class TranscoderException extends IOException {
	private static final long serialVersionUID = 0L;

	private final short code;

	private final String[] args;

	private final byte state;

	/**
	 * The conversion result is incomplete, but the data is usable.
	 */
	public static final byte STATE_READABLE = 1;

	/**
	 * The conversion result data is corrupt.
	 */
	public static final byte STATE_BROKEN = 2;

	public TranscoderException(byte state, short code, String[] args, String message) {
		super(message);
		this.code = code;
		this.args = args;
		this.state = state;
	}

	public TranscoderException(short code, String[] args, String message) {
		this(STATE_BROKEN, code, args, message);
	}

	/**
	 * The message code that caused the abort.
	 * 
	 * @return The message code.
	 */
	public short getCode() {
		return this.code;
	}

	/**
	 * The values associated with the message.
	 * 
	 * @return The message arguments.
	 */
	public String[] getArgs() {
		return this.args;
	}

	/**
	 * Returns the state after conversion (a STATE_XXX constant).
	 * 
	 * @return The state constant after conversion.
	 */
	public byte getState() {
		return this.state;
	}
}
