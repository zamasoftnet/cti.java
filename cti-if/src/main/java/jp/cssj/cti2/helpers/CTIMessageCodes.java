package jp.cssj.cti2.helpers;

/**
 * メッセージコードの定数です。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIMessageCodes.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface CTIMessageCodes {
	/**
	 * 処理を正常に中断した。
	 */
	public static final short INFO_ABORT = 0x1001;

	/**
	 * リソースのURIが不正である。
	 */
	public static final short WARN_BAD_RESOURCE_URI = 0x2001;

	/**
	 * メインドキュメントのベースURIが不正である。
	 */
	public static final short WARN_BAD_BASE_URI = 0x2002;

	/**
	 * メインドキュメントのURIが不正である。
	 */
	public static final short ERROR_BAD_DOCUMENT_URI = 0x3001;

	/**
	 * 通信エラー。
	 */
	public static final short ERROR_IO = 0x3002;

	/**
	 * 混雑していて変換を始められない(サーバーの同時変換数の上限。少し待ってからやり直す)。
	 */
	public static final short ERROR_BUSY = 0x3003;

	/**
	 * 予期しないエラー。
	 */
	public static final short FATAL_UNEXPECTED = 0x4001;
}
