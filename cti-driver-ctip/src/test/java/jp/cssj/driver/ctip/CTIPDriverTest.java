package jp.cssj.driver.ctip;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTISessionHelper;

/**
 * ctip://cti.li/ を使ったPDF生成テスト。
 * 生成されたPDFは test-output/ ディレクトリに保存し、PdfBoxTest で検証する。
 */
@Order(1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CTIPDriverTest {

	private static final URI SERVER_URI = URI.create("ctip://cti.li/");
	private static final String USER = "user";
	private static final String PASSWORD = "kappa";
	private static final URI SOURCE_URI = URI.create("http://cti.li/");

	static final File OUTPUT_DIR = new File("../../test-output");

	/** サーバーに接続してセッションを取得する。接続できなければテストをスキップする。 */
	private CTISession openSession() throws IOException {
		try {
			return CTIDriverManager.getSession(SERVER_URI, USER, PASSWORD);
		} catch (IOException e) {
			Assumptions.assumeTrue(false, "ctip://cti.li/ に接続できないためスキップ: " + e.getMessage());
			throw e; // unreachable
		}
	}

	private void prepareOutput(CTISession session, String filename) throws IOException {
		OUTPUT_DIR.mkdirs();
		CTISessionHelper.setResultFile(session, new File(OUTPUT_DIR, filename));
	}

	// TC-01: 基本URL変換
	@Test
	@Order(1)
	void TC01_基本URL変換() throws Exception {
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-url.pdf");
			session.transcode(SOURCE_URI);
		}
	}

	// TC-02: ハイパーリンク有効
	@Test
	@Order(2)
	void TC02_ハイパーリンク有効() throws Exception {
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-hyperlinks.pdf");
			session.property("output.pdf.hyperlinks", "true");
			session.transcode(SOURCE_URI);
		}
	}

	// TC-03: ブックマーク有効
	@Test
	@Order(3)
	void TC03_ブックマーク有効() throws Exception {
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-bookmarks.pdf");
			session.property("output.pdf.bookmarks", "true");
			session.transcode(SOURCE_URI);
		}
	}

	// TC-04: ハイパーリンクとブックマーク両方有効
	@Test
	@Order(4)
	void TC04_ハイパーリンクとブックマーク有効() throws Exception {
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-hyperlinks-bookmarks.pdf");
			session.property("output.pdf.hyperlinks", "true");
			session.property("output.pdf.bookmarks", "true");
			session.transcode(SOURCE_URI);
		}
	}

	// TC-05: クライアント側HTML変換（ASCII）
	@Test
	@Order(5)
	void TC05_クライアント側HTML変換() throws Exception {
		String html = "<html><body><h1>Hello</h1><p>Client-side HTML transcoding test.</p></body></html>";
		byte[] bytes = html.getBytes("UTF-8");
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-client-html.pdf");
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(bytes),
					URI.create("dummy:///test.html"),
					"text/html", "UTF-8");
		}
	}

	// TC-06: 日本語HTMLコンテンツ
	@Test
	@Order(6)
	void TC06_日本語HTMLコンテンツ() throws Exception {
		String html = "<html><head><meta charset=\"UTF-8\"/></head>"
				+ "<body><h1>日本語テスト</h1><p>こんにちは世界。クライアント側から日本語コンテンツを送信します。</p></body></html>";
		byte[] bytes = html.getBytes("UTF-8");
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-client-japanese.pdf");
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(bytes),
					URI.create("dummy:///japanese.html"),
					"text/html", "UTF-8");
		}
	}

	// TC-07: 最小HTML（境界条件）
	@Test
	@Order(7)
	void TC07_最小HTML() throws Exception {
		String html = "<html><body><p>.</p></body></html>";
		byte[] bytes = html.getBytes("UTF-8");
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-client-minimal.pdf");
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(bytes),
					URI.create("dummy:///minimal.html"),
					"text/html", "UTF-8");
		}
	}

	// TC-09: 大規模テーブル（2MB超えを狙い、ドライバのメモリ→ファイル切り替えを確認）
	// 2000行→約294KB。閾値2MBを超えるには約15000行必要。
	@Test
	@Order(9)
	void TC09_大規模テーブル() throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("<html><head><meta charset=\"UTF-8\"/></head><body>");
		sb.append("<h1>大規模テーブルテスト</h1>");
		sb.append("<table border=\"1\"><tr><th>番号</th><th>名前</th><th>説明</th><th>備考</th></tr>");
		for (int i = 1; i <= 15000; i++) {
			sb.append("<tr><td>").append(i).append("</td>")
			  .append("<td>項目").append(i).append("</td>")
			  .append("<td>これはテスト項目 ").append(i).append(" の詳細説明テキストです。</td>")
			  .append("<td>備考テキスト ").append(i).append("</td></tr>");
		}
		sb.append("</table></body></html>");
		byte[] bytes = sb.toString().getBytes("UTF-8");
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-large-table.pdf");
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(bytes),
					URI.create("dummy:///large-table.html"),
					"text/html", "UTF-8");
		}
	}

	// TC-10: 長文テキスト文書（多数のセクション・長段落）
	// テキストはPDF上で圧縮されるため、段落数を多くして2MB超えを狙う。
	@Test
	@Order(10)
	void TC10_長文テキスト文書() throws Exception {
		// 1段落あたり約400文字 × 20段落 × 200セクション = 1,600,000文字分のコンテンツ
		String[] sentences = {
			"Copper PDFはHTMLやXMLをPDFに変換するサーバーサイドのソフトウェアです。",
			"CTIプロトコルを通じてクライアントからドキュメントを送信し、変換結果をPDFとして受け取ります。",
			"このテストは大量のテキストコンテンツを含む文書を生成します。",
			"ドライバはPDF出力が2MBを超えた際にメモリからファイル書き出しへ切り替わります。",
			"このテストはその動作を確認するために設計されています。",
		};
		StringBuilder sb = new StringBuilder();
		sb.append("<html><head><meta charset=\"UTF-8\"/></head><body>");
		for (int section = 1; section <= 500; section++) {
			sb.append("<h2>セクション ").append(section).append("</h2>");
			for (int para = 0; para < 20; para++) {
				sb.append("<p>");
				for (String s : sentences) {
					sb.append(s);
				}
				sb.append("（セクション").append(section).append("、段落").append(para + 1).append("）</p>");
			}
		}
		sb.append("</body></html>");
		byte[] bytes = sb.toString().getBytes("UTF-8");
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-large-text.pdf");
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(bytes),
					URI.create("dummy:///large-text.html"),
					"text/html", "UTF-8");
		}
	}

	// TC-08: 連続モード（2文書を結合）
	@Test
	@Order(8)
	void TC08_連続モード() throws Exception {
		String html1 = "<html><body><h1>Page 1</h1><p>First document in continuous mode.</p></body></html>";
		String html2 = "<html><body><h1>Page 2</h1><p>Second document in continuous mode.</p></body></html>";
		try (CTISession session = openSession()) {
			prepareOutput(session, "ctip-java-continuous.pdf");
			session.setContinuous(true);
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(html1.getBytes("UTF-8")),
					URI.create("dummy:///page1.html"),
					"text/html", "UTF-8");
			CTISessionHelper.transcodeStream(session,
					new ByteArrayInputStream(html2.getBytes("UTF-8")),
					URI.create("dummy:///page2.html"),
					"text/html", "UTF-8");
			session.join();
		}
	}
}
