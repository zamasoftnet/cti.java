package jp.cssj.cti2.progress;

/**
 * An interface that represents progress.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: Progressive.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface Progressive {
	/**
	 * Returns the number of bytes of the original data that have been processed.
	 * 
	 * @return The number of bytes processed.
	 */
	public long getProgress();
}