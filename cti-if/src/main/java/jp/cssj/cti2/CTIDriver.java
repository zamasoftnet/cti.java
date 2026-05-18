package jp.cssj.cti2;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * ドキュメント変換サーバーに接続するためのドライバです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIDriver.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface CTIDriver {
	/**
	 * 指定されたURIに対応するかどうかを返します。
	 * 
	 * @param uri
	 *            接続先URI。
	 * @return 対応していればtrue。
	 */
	public boolean match(URI uri);
	/**
	 * セッションを作成します。
	 * <p>
	 * URIの形式は、ドライバの種類に依存します。 現在、実装が提供されているのはCTIPとHTTP/RESTプロトコルに対応したドライバです。
	 * </p>
	 * <p>
	 * CTIPによる接続の場合は、"ctip://ホスト名:ポート番号/"という形式のURIを使用してください。 CTIP
	 * 1.0による接続のみに対応したサーバー(Copper PDF 2.0系以前またはCSSJ)に接続する場合は、
	 * クエリパラメータversion=1を加えて、"ctip://ホスト名:ポート番号/?version=1"という形式にしてください。
	 * </p>
	 * <p>
	 * HTTP/RESTによる接続の場合は、"http://ホスト名:ポート番号/"という形式のURIを使用してください。
	 * <strong>末尾のスラッシュを忘れないでください。</strong>
	 * </p>
	 * 
	 * @param uri
	 *            接続先のURI。
	 * @param props
	 *            接続プロパティのキーと値の組み合わせです。キー"user"はユーザー名、キー"password"はパスワードです。
	 * @return セッション。
	 * @throws IOException
	 * @throws SecurityException
	 *             認証に失敗した場合。
	 */
	public CTISession getSession(URI uri, Map<String, String> props) throws IOException, SecurityException;
}