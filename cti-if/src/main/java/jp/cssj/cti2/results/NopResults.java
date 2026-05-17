package jp.cssj.cti2.results;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.NoOpFragmentedOutput;

/**
 * 何も出力しないResultsです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: NopResults.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class NopResults implements Results {
	public static final NopResults SHARED_INSTANCE = new NopResults();

	private NopResults() {
		// private
	}

	public boolean hasNext() {
		return true;
	}

	public FragmentedOutput nextBuilder(SourceMetadata metaSource) {
		return NoOpFragmentedOutput.INSTANCE;
	}

	public void end() {
		// NOP
	}
}
