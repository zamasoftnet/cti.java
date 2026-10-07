package jp.cssj.cti2.progress;

/**
 * Receives updates on the server's progress in processing the main document.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ProgressListener.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface ProgressListener {
	/**
	 * <p>
	 * Receives the server's estimate of the main document's size.
	 * </p>
	 * <p>
	 * This method may not be called, and the value passed to it may be inaccurate.
	 * </p>
	 * 
	 * @param sourceLength
	 *            The size of the main document in bytes.
	 */
	public void sourceLength(long sourceLength);

	/**
	 * Receives the number of bytes of the main document that have been processed.
	 * <p>
	 * This method may not be called, and the value passed to it may be inaccurate.
	 * </p>
	 * 
	 * @param serverRead
	 *            The number of bytes read.
	 */
	public void progress(long serverRead);
}