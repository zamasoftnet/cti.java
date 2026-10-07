package jp.cssj.cti2.progress;

/**
 * An adapter that simplifies implementing ProgressListenter.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ProgressAdapter.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class ProgressAdapter implements ProgressListener {

	public void sourceLength(long sourceLength) {
		// ignore
	}

	public void progress(long serverRead) {
		// ignore
	}
}
