package jp.cssj.cti2.helpers;

/**
 * Message code constants.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIMessageCodes.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface CTIMessageCodes {
	/**
	 * Processing is aborted normally.
	 */
	public static final short INFO_ABORT = 0x1001;

	/**
	 * The resource URI is invalid.
	 */
	public static final short WARN_BAD_RESOURCE_URI = 0x2001;

	/**
	 * The base URI of the main document is invalid.
	 */
	public static final short WARN_BAD_BASE_URI = 0x2002;

	/**
	 * The URI of the main document is invalid.
	 */
	public static final short ERROR_BAD_DOCUMENT_URI = 0x3001;

	/**
	 * Communication error.
	 */
	public static final short ERROR_IO = 0x3002;

	/**
	 * Conversion cannot start because the server is busy (concurrent conversion limit reached; wait briefly and retry).
	 */
	public static final short ERROR_BUSY = 0x3003;

	/**
	 * Unexpected error.
	 */
	public static final short FATAL_UNEXPECTED = 0x4001;
}
