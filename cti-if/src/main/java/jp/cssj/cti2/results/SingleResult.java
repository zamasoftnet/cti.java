package jp.cssj.cti2.results;

import java.io.File;
import java.io.OutputStream;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;
import net.zamasoft.zstream.io.impl.NoOpFragmentedOutput;
import net.zamasoft.zstream.io.impl.StreamFragmentedOutput;

/**
 * 単一の結果を出力するResultsです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: SingleResult.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class SingleResult implements Results {
	/**
	 * 出力先のデータ構築オブジェクトです。
	 */
	protected FragmentedOutput builder;

	/**
	 * 1つのデータ構築オブジェクトに対して出力します。
	 * 
	 * @param builder
	 */
	public SingleResult(FragmentedOutput builder) {
		this.builder = builder;
	}

	/**
	 * OutputStreamにデータを出力します。
	 * 
	 * @param out
	 */
	public SingleResult(OutputStream out) {
		this(new StreamFragmentedOutput(out));
	}

	/**
	 * ファイルにデータを出力します。
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
