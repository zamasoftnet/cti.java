package jp.cssj.cti2.examples;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import org.apache.commons.io.IOUtils;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTISessionHelper;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

/**
 * クライアントから送ったデータを変換します。
 */
public class ClientResource {
	/** 接続先。 */
	private static final URI SERVER_URI = URI.create("ctip://192.168.10.21:8101/");

	/** ユーザー。 */
	private static final String USER = "user";

	/** パスワード。 */
	private static final String PASSWORD = "kappa";

	public static void main(String[] args) throws Exception {
		// 接続する
		try (CTISession session = CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD)) {
			// test.pdfに結果を出力する
			File file = new File("test.pdf");
			File inFile = new File("sample2.html");
			CTISessionHelper.setResultFile(session, file);

			SourceResolver sourceResolver = new SourceResolver() {
				public Source resolve(URI uri) throws IOException,FileNotFoundException {
					System.out.println("resolve: " + uri);
					return null;
				}

				public void release(Source source) {
					// ignore
				}
			};
    		session.setSourceResolver( sourceResolver );

			// 出力先ストリームを取得
			try (OutputStream out = 
					session.transcode(new SimpleSourceMetadata(URI.create("."), "text/html", "UTF-8", -1))) {
				// 入力ファイルを読み込み、変換結果を出力する
				try (InputStream in = new FileInputStream(inFile)) {
					IOUtils.copy(in, out);
				}
			}
		}
	}
}