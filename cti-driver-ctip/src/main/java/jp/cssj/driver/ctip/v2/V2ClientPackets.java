package jp.cssj.driver.ctip.v2;

/**
 * Types of packets sent from the client to the server.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: V2ClientPackets.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface V2ClientPackets {
	/**
	 * Property packet.
	 */
	public static final byte PROPERTY = 0x01;

	/**
	 * Content start packet.
	 */
	public static final byte START_MAIN = 0x02;

	/**
	 * Packet that requests retrieval of the main document on the server.
	 */
	public static final byte SERVER_MAIN = 0x03;

	/**
	 * Packet that switches to a mode that resolves resources on the client.
	 */
	public static final byte CLIENT_RESOURCE = 0x04;

	/**
	 * Packet that switches to a mode that combines multiple results.
	 */
	public static final byte CONTINUOUS = 0x05;

	/**
	 * Data packet.
	 */
	public static final byte DATA = 0x11;

	/**
	 * Resource start packet.
	 */
	public static final byte START_RESOURCE = 0x21;

	/**
	 * Packet that indicates a missing resource.
	 */
	public static final byte MISSING_RESOURCE = 0x22;

	/**
	 * Packet that indicates the end of data.
	 */
	public static final byte EOF = 0x31;

	/**
	 * Processing abort packet.
	 */
	public static final byte ABORT = 0x32;

	/**
	 * Result join packet.
	 */
	public static final byte JOIN = 0x33;

	/**
	 * State reset packet.
	 */
	public static final byte RESET = 0x41;

	/**
	 * Communication end packet.
	 */
	public static final byte CLOSE = 0x42;

	/**
	 * Server information packet.
	 */
	public static final byte SERVER_INFO = 0x51;
}
