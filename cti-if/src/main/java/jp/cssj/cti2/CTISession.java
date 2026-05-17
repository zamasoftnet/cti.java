package jp.cssj.cti2;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;

/**
 * ドキュメント変換処理を実行するためのサーバーとの接続です。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTISession.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface CTISession extends Closeable {
	/** きりのよいところまで処理する中断処理の定数です。abortメソッドに渡します。 */
	public static final byte ABORT_NORMAL = 1;

	/** 強制的に中断する処理の定数です。abortメソッドに渡します。 */
	public static final byte ABORT_FORCE = 2;

	/**
	 * サーバー情報を返します。 詳細は<a href=
	 * "http://dl.cssj.jp/docs/copper/3.0/html/3410_ctip2.html#prog-ctip2-server-info"
	 * >Copper PDF ドキュメント</a>を参照して下さい。
	 * 
	 * @param uri
	 *            サーバー情報を選択するためのURI。
	 * @return サーバー情報データのストリーム。
	 */
	public InputStream getServerInfo(URI uri) throws IOException;

	/**
	 * <p>
	 * 出力先を設定します。
	 * </p>
	 * <p>
	 * このメソッドは各transcodeメソッドの前に呼ぶ必要があります。
	 * </p>
	 * 
	 * @param results
	 *            出力先。
	 */
	public void setResults(Results results) throws IOException;

	/**
	 * <p>
	 * メッセージを受け取るためのオブジェクトを設定します。
	 * </p>
	 * 
	 * <p>
	 * このメソッドは各transcodeメソッドの前に呼ぶ必要があります。
	 * </p>
	 * 
	 * @see MessageHandler
	 * @param messageHandler
	 *            メッセージハンドラ
	 */
	public void setMessageHandler(MessageHandler messageHandler) throws IOException;

	/**
	 * <p>
	 * 進行状況を監視するためのオブジェクトを設定します。
	 * </p>
	 * <p>
	 * このメソッドは各transcodeメソッドの前に呼ぶ必要があります。
	 * </p>
	 * 
	 * @see ProgressListener
	 * @param progressListener
	 *            進行状況リスナ
	 */
	public void setProgressListener(ProgressListener progressListener) throws IOException;

	/**
	 * <p>
	 * プロパティを設定します。
	 * </p>
	 * <p>
	 * このメソッドは各transcodeメソッドの前に呼ぶ必要があります。
	 * </p>
	 * 
	 * @param name
	 *            プロパティ名
	 * @param value
	 *            値
	 * @throws IOException
	 */
	public void property(String name, String value) throws IOException;

	/**
	 * <p>
	 * リソースを送信するための出力ストリームを返します。
	 * </p>
	 * <p>
	 * <strong>リソースを送信した後、出力ストリームは必ずクローズしてください。 </strong>
	 * </p>
	 * <p>
	 * このメソッドは各transcodeメソッドの前に呼ぶ必要があります。
	 * </p>
	 * 
	 * @param SourceMetadata
	 *            リソースデータのメタ情報。
	 * @return サーバーへの出力ストリーム。
	 * @throws IOException
	 */
	public OutputStream resource(SourceMetadata metaSource) throws IOException;

	/**
	 * <p>
	 * リソースを送信します。
	 * </p>
	 * <p>
	 * このメソッドは各transcodeメソッドの前に呼ぶ必要があります。
	 * </p>
	 * 
	 * @param source
	 *            リソースのデータソース。
	 * @throws IOException
	 */
	public void resource(Source source) throws IOException;

	/**
	 * <p>
	 * リソースを読み込むためのオブジェクトを設定します。
	 * </p>
	 * 
	 * @param resolver
	 *            サーバー側から要求したリソースを取得するためのSourceResolver。
	 */
	public void setSourceResolver(SourceResolver resolver) throws IOException;

	/**
	 * <p>
	 * メインドキュメントを送信するための出力ストリームを返します。
	 * </p>
	 * <p>
	 * <strong>本体を送信した後、出力ストリームは必ずクローズしてください。 </strong>
	 * </p>
	 * 
	 * @param SourceMetadata
	 *            メインドキュメントのメタ情報。
	 * @return サーバーへの出力ストリーム。
	 * @throws IOException
	 */
	public OutputStream transcode(SourceMetadata metaSource) throws IOException;

	/**
	 * <p>
	 * 指定されたアドレスへサーバー側からアクセスしてメインドキュメントを取得して変換します。
	 * resourceメソッドで事前に送信したリソースに対しても有効です。
	 * </p>
	 * 
	 * @param uri
	 *            メインドキュメントのURI。
	 * @throws IOException
	 */
	public void transcode(URI uri) throws IOException, TranscoderException;

	/**
	 * <p>
	 * メインドキュメントをデータソースから取得して変換します。
	 * </p>
	 * 
	 * @param source
	 *            メインドキュメントのデータソース。
	 * @throws IOException
	 */
	public void transcode(Source source) throws IOException, TranscoderException;

	/**
	 * <p>
	 * 複数の結果を結合するモードに切り替えます。
	 * </p>
	 * 
	 * @param continuous
	 *            trueであればjoinにより結果を結合するモードにします。
	 * @throws IOException
	 */
	public void setContinuous(boolean continuous) throws IOException;

	/**
	 * <p>
	 * setContinues(true) が設定された状態で、複数回のtranscodeにより生成された結果を結合して出力します。
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void join() throws IOException;

	/**
	 * <p>
	 * 変換を中断します。 このメソッドは非同期的に（別スレッドから）呼び出す必要があります。
	 * 実際に処理が中断された場合は、変換処理を行なっている（transcodeを呼び出した
	 * ）スレッドで、TranscoderExceptionがスローされます。
	 * </p>
	 * 
	 * @param mode
	 *            きりのよいところまで出力する場合はABORT_NORMAL、強制的に処理を停止するにはABORT_FORCEを指定します。
	 * @throws IOException
	 */
	public void abort(byte mode) throws IOException;

	/**
	 * <p>
	 * 送られたリソースと、プロパティ、メッセージハンドラ等の全ての設定をクリアして、セッションが作られた時点と同じ初期状態に戻します。
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void reset() throws IOException;

	/**
	 * <p>
	 * セッションをクローズします。
	 * <p>
	 * 
	 * このメソッドを呼び出した後は、セッションに対して何も出来ません。
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException;
}