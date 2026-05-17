package jp.cssj.cti2.results;

import java.io.File;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;

/**
 * ディレクトリに複数の結果を出力するResultsです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: DirectoryResults.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class DirectoryResults implements Results {
	protected final File dir;
	protected final String prefix, suffix;
	protected int counter = 0;

	/**
	 * 出力先ディレクトリとファイル名の前後を指定してオブジェクトを構築します。
	 * <p>
	 * ファイルはdirに出力され、ファイル名は prefix, 1から始まる通し番号, suffixを連結したものとなります。
	 * </p>
	 * 
	 * @param dir
	 *            出力先ディレクトリです。
	 * @param prefix
	 *            ファイル名の前に付ける文字列です。
	 * @param suffix
	 *            ファイル名の後に付ける文字列です。
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
