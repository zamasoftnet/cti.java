package jp.cssj.cti2.results;

import java.io.IOException;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;

/**
 * Processing results.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: Results.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface Results {
	/**
	 * Returns true if the next result can be output.
	 * 
	 * @return true if the next result can be output; false otherwise.
	 */
	public boolean hasNext();

	/**
	 * Returns a builder for constructing the next processing result.
	 * 
	 * @param SourceMetadata
	 *            Metadata for the output data.
	 * @return The data builder object.
	 * @throws IOException
	 */
	public FragmentedOutput nextBuilder(SourceMetadata metaSource) throws IOException;

	/**
	 * Completes the sequence of data outputs.
	 * <p>
	 * For a normal conversion, this is called once after all result builders are closed. For continuous conversion,
	 * it is not called for individual {@code transcode} calls, but is called once at the final {@code join}.
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void end() throws IOException;
}
