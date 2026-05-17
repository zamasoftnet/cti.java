package jp.cssj.cti2.helpers;

import java.io.IOException;

import javax.servlet.ServletResponse;

import jp.cssj.cti2.CTISession;
import net.zamasoft.zstream.resolver.SourceMetadata;

/**
 * サーブレットからドキュメント変換サーバーを利用する際のユーティリティです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ServletHelper.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class ServletHelper {
	private ServletHelper() {
		// unused
	}

	/**
	 * セッションの出力先にServletResponseを設定します。
	 * 
	 * @param session
	 *            出力先を設定するセッション。
	 * @param response
	 *            結果の出力際のレスポンス。
	 * @throws IOException
	 */
	public static void setServletResponse(final CTISession session, final ServletResponse response) throws IOException {
		session.setResults(new ServletResponseResults(response));
	}

	/**
	 * charsetパラメータつきのContent-Typeヘッダ値を返します。
	 * 
	 * @param SourceMetadata
	 *            データのメタ情報。
	 * @return charsetパラメータつきのContent-Typeヘッダ。
	 */
	public static String getContentType(SourceMetadata metaSource) {
		String mimeType;
		try {
			mimeType = metaSource.getMimeType();
			if (mimeType == null) {
				return null;
			}
			String encoding = metaSource.getEncoding();
			if (encoding != null) {
				mimeType += "; charset=" + encoding;
			}
		} catch (Exception e) {
			return null;
		}
		return mimeType;
	}
}
