package jp.cssj.cti2.results;

import java.io.File;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;

/**
 * A Results implementation that outputs multiple results to a directory.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: DirectoryResults.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class DirectoryResults implements Results {
	protected final File dir;
	protected final String prefix, suffix;
	protected int counter = 0;

	/**
	 * Constructs an object with the specified output directory and file name prefix and suffix.
	 * <p>
	 * Outputs files to dir, with file names formed by concatenating prefix,
	 * a sequence number starting at 1, and suffix.
	 * </p>
	 * 
	 * @param dir
	 *            The output directory.
	 * @param prefix
	 *            The string to prepend to the file name.
	 * @param suffix
	 *            The string to append to the file name.
	 */
	public DirectoryResults(File dir, String prefix, String suffix) {
		this.dir = dir;
		this.prefix = prefix;
		this.suffix = suffix;
	}

	public boolean hasNext() {
		return true;
	}

	public FragmentedOutput nextBuilder(SourceMetadata metaSource) {
		++this.counter;
		File file = new File(this.dir, this.prefix + this.counter + this.suffix);
		return new FileFragmentedOutput(file);
	}

	public void end() {
		// NOP
	}
}
