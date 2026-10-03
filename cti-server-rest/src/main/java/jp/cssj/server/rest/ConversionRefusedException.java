package jp.cssj.server.rest;

/**
 * 変換を始めなかった(待たずに断った)ことを表します(2026-10-03)。サーブレットは
 * HTTP 503 と {@code Retry-After} を付けて、このコードのメッセージを返します。
 *
 * <ul>
 * <li>{@link jp.cssj.cti2.helpers.CTIMessageCodes#ERROR_BUSY}(0x3003): サーバーの同時変換数の上限</li>
 * <li>{@link RestServlet#ERROR_SESSION_BUSY}(0x3017): このセッションは変換中</li>
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
