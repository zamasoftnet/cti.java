package jp.cssj.cti2.helpers;

import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ResourceBundle;

import jp.cssj.cti2.message.MessageHandler;

/**
 * メッセージ関係の補助ツールです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIMessageHelper.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class CTIMessageHelper {
	private CTIMessageHelper() {
		// unused
	}

	/**
	 * 標準出力に表示するメッセージハンドラです。
	 * 
	 * @deprecated createStreamMessageHandler(System.out)を使用してください。
	 */
	public static final MessageHandler STDOUT = new StreamMessageHandler(System.out);

	/**
	 * 標準出エラー出力に表示するメッセージハンドラです。
	 * 
	 * @deprecated createStreamMessageHandler(System.err)を使用してください。
	 */
	public static final MessageHandler STDERR = new StreamMessageHandler(System.err);

	public static MessageHandler createStreamMessageHandler(PrintStream out) {
		return new StreamMessageHandler(out);
	}

	/**
	 * どこにも表示しないメッセージハンドラです。
	 */
	public static final MessageHandler NULL = new MessageHandler() {
		public void message(short code, String[] args, String mes) {
			// ignore
		}
	};

	/**
	 * 情報レベルメッセージです。
	 */
	public static final short INFO = 1;
	/**
	 * 警告レベルメッセージです。
	 */
	public static final short WARN = 2;
	/**
	 * エラーレベルメッセージです。
	 */
	public static final short ERROR = 3;
	/**
	 * 深刻なエラーレベルメッセージです。
	 */
	public static final short FATAL = 4;

	/**
	 * エラーレベルを返します。
	 * 
	 * @param code
	 * @return エラーレベルの値。
	 */
	public static final short getLevel(short code) {
		return (short) (code >> 12 & 0xF);
	}

	private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(CTIMessageCodes.class.getName());

	/**
	 * メッセージコードに対応するメッセージフォーマットを返します。
	 * 
	 * @param code
	 * @return メッセージコードに対応するjava.text.MessageFormat形式の文字列。
	 */
	public static String getFormat(short code) {
		String str = Integer.toHexString(code).toUpperCase();
		str = BUNDLE.getString(str);
		return str;
	}

	/**
	 * メッセージを文字列化します。
	 * 
	 * @param code
	 * @return 文字列化したメッセージ。
	 */
	public static String toString(short code, String[] args) {
		String str = getFormat(code);
		if (args != null) {
			for (int i = 0; i < args.length; ++i) {
				if (args[i] != null && args[i].length() > 2083) {
					args[i] = args[i].substring(0, 2080) + "...";
				}
			}
		}
		str = MessageFormat.format(str, (Object[]) args);
		return str;
	}
}

class StreamMessageHandler implements MessageHandler {
	final PrintStream out;

	StreamMessageHandler(PrintStream out) {
		this.out = out;
	}

	public void message(short code, String[] args, String mes) {
		if (mes != null) {
			this.out.println(mes);
			return;
		}
		// リモート実装や独自ドライバが展開済み文言を渡さなくても、診断を
		// 黙って "null" にしない。メッセージコードと生の引数は常に残す。
		final StringBuilder fallback = new StringBuilder(32);
		fallback.append('[').append(Integer.toHexString(code & 0xFFFF).toUpperCase()).append(']');
		if (args != null) {
			for (String arg : args) {
				fallback.append(' ').append(String.valueOf(arg));
			}
		}
		this.out.println(fallback.toString());
	}
}
