package jp.cssj.server.rest;

/**
 * Indicates that conversion did not start (it was refused without waiting) (2026-10-03).
 * The servlet returns a message with this code, HTTP 503, and {@code Retry-After}.
 *
 * <ul>
 * <li>{@link jp.cssj.cti2.helpers.CTIMessageCodes#ERROR_BUSY}(0x3003): Server's concurrent conversion limit</li>
 * <li>{@link RestServlet#ERROR_SESSION_BUSY}(0x3017): This session is converting</li>
 * </ul>
 */
public class ConversionRefusedException extends Exception {
	private static final long serialVersionUID = 1L;

	private final short code;

	private final String[] args;

	public ConversionRefusedException(final short code, final String[] args) {
		super(Integer.toHexString(code));
		this.code = code;
		this.args = args;
	}

	public short getCode() {
		return this.code;
	}

	public String[] getArgs() {
		return this.args;
	}
}
