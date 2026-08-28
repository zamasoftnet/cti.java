package jp.cssj.cti2.results;

import java.io.IOException;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;

/**
 * 処理結果です。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: Results.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface Results {
	/**
	 * 次の結果を出力可能であればtrueを返します。
	 * 
	 * @return 次の結果を出力可能であればtrueそうでなければfalse。
	 */
	public boolean hasNext();

	/**
	 * 次の処理結果を構築するためのビルダを返します。
	 * 
	 * @param SourceMetadata
	 *            出力データのメタ情報。
	 * @return データ構築オブジェクト。
	 * @throws IOException
	 */
	public FragmentedOutput nextBuilder(SourceMetadata metaSource) throws IOException;

	/**
	 * 一連のデータ出力を完了します。
	 * <p>
	 * 通常の変換では全結果のビルダが閉じられた後に1回呼ばれます。連続変換では
	 * 個々の{@code transcode}では呼ばれず、最終的な{@code join}で1回呼ばれます。
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void end() throws IOException;
}
