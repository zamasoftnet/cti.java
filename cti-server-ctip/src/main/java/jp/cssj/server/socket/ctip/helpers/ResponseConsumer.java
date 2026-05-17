package jp.cssj.server.socket.ctip.helpers;

import java.io.IOException;

import net.zamasoft.zstream.io.FragmentedOutput;

/**
 * サーバーからクライアントへデータを送るためのインターフェースです。
 * 
 * 断片化されたデータの受信に加えて、進行状況を把握し、エラーメッセージを受け取ります。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ResponseConsumer.java 1554 2018-04-26 03:34:02Z miyabe $
 */
public interface ResponseConsumer extends FragmentedOutput {
	/**
	 * メッセージを送信します。
	 * 
	 * @param code
	 * @param args
	 * @param message
	 * @throws IOException
	 */
	public void message(short code, String[] args, String message) throws IOException;
}