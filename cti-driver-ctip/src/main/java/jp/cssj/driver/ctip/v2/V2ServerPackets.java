package jp.cssj.driver.ctip.v2;

/**
 * Types of packets sent from the server to the client.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: V2ServerPackets.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface V2ServerPackets {
	/**
	 * Starts the data.
	 */
	public static final byte START_DATA = 0x01;

	/**
	 * Data packet.
	 */
	public static final byte BLOCK_DATA = 0x11;

	/**
	 * Fragment addition packet.
	 */
	public static final byte ADD_BLOCK = 0x12;

	/**
	 * Fragment insertion packet.
	 */
	public static final byte INSERT_BLOCK = 0x13;

	/**
	 * Error message packet.
	 */
	public static final byte MESSAGE = 0x14;

	/**
	 * Packet that reports the length of the main document.
	 */
	public static final byte MAIN_LENGTH = 0x15;

	/**
	 * Packet that reports the number of bytes read from the main document.
	 */
	public static final byte MAIN_READ = 0x16;

	/**
	 * Data packet independent of fragmentation.
	 */
	public static final byte DATA = 0x17;

	/**
	 * Packet that signals the closure of a fragment.
	 */
	public static final byte CLOSE_BLOCK = 0x18;

	/**
	 * Resource request packet.
	 */
	public static final byte RESOURCE_REQUEST = 0x21;

	/**
	 * End-of-data packet.
	 */
	public static final byte EOF = 0x31;

	/**
	 * Data abort packet.
	 */
	public static final byte ABORT = 0x32;

	/**
	 * Data continuation packet.
	 */
	public static final byte NEXT = 0x33;
}
