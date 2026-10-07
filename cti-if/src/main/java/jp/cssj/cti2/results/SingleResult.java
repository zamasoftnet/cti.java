package jp.cssj.cti2.results;

import java.io.File;
import java.io.OutputStream;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;
import net.zamasoft.zstream.io.impl.NoOpFragmentedOutput;
import net.zamasoft.zstream.io.impl.StreamFragmentedOutput;

/**
 * A Results implementation that outputs a single result.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: SingleResult.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class SingleResult implements Results {
	/**
	 * The data builder object that receives output.
	 */
	protected FragmentedOutput builder;

	/**
	 * Outputs to a single data builder object.
	 * 
	 * @param builder
	 */
	public SingleResult(FragmentedOutput builder) {
		this.builder = builder;
	}

	/**
	 * Outputs data to an OutputStream.
	 * 
	 * @param out
	 */
	public SingleResult(OutputStream out) {
		this(new StreamFragmentedOutput(out));
	}

	/**
	 * Outputs data to a file.
	 * 
	 * @param file
	 */
	public SingleResult(File file) {
		this(new FileFragmentedOutput(file));
	}

	public boolean hasNext() {
		return this.builder != null;
	}

	public FragmentedOutput nextBuilder(SourceMetadata metaSource) {
		if (this.builder == null) {
			return NoOpFragmentedOutput.INSTANCE;
		}
		try {
			return this.builder;
		} finally {
			this.builder = null;
		}
	}

	public void end() {
		// NOP
	}
}
