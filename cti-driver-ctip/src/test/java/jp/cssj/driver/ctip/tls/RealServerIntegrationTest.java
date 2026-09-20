package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TLSPolicy;
import jp.cssj.cti2.helpers.CTISessionHelper;
import jp.cssj.cti2.results.SingleResult;
import jp.cssj.driver.ctip.CTIPDriver;
import jp.cssj.cti2.helpers.DefaultMetaSource;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.DirectoryResults;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;
import net.zamasoft.zstream.io.impl.StreamFragmentedOutput;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;

/**
 * 実サーバーとの統合(段階5)。<b>既定では走りません。</b>
 *
 * <p>
 * 外部のサーバーに依存するので、明示的に指定したときだけ走ります。
 * </p>
 *
 * <pre>
 * ./gradlew :cti-driver-ctip:tlsTest --tests '*RealServerIntegrationTest*' \
 *     -Dcti.integration.uri=ctips://cti.li:8499/ \
 *     -Dcti.integration.user=... -Dcti.integration.password=...
 * </pre>
 *
 * <p>
 * 確かめるのは<b>証明書の検証を有効にしたまま繋がること</b>です。
 * 実サーバーの証明書は公的な CA のものなので、独自の設定は要りません。
 * これが通れば、既定を安全側へ倒したあとも通常の運用が壊れていないと言えます。
 * </p>
 */
@EnabledIfSystemProperty(named = "cti.integration.uri", matches = ".+")
class RealServerIntegrationTest {

	private static final String HTML = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
			+ "<title>integration</title></head><body><p>ctips integration</p></body></html>";

	/** 証明書を検証したまま、実サーバーで変換できること。 */
	/**
	 * 接続試験マトリクスの共通契約(copperpdf4/docs/design/2026-09-20-cti-driver-tls-test-matrix-design.md §2)を
	 * システムプロパティで受ける(build.gradle の tlsTest が試験 JVM へ転送する):
	 * {@code cti.integration.insecure=true} で証明書を検証しない、{@code cti.integration.trustStore}(+ {@code trustStorePassword})で
	 * 信頼させる証明書、{@code cti.integration.expectReject=true} で「証明書の検証で拒否されること」だけを試験する。
	 */
	private static void applyMatrixContract() {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		final boolean insecure = Boolean.getBoolean("cti.integration.insecure");
		final boolean expectReject = Boolean.getBoolean("cti.integration.expectReject");
		if (insecure && expectReject) {
			throw new IllegalStateException("cti.integration.insecure と cti.integration.expectReject は同時に指定できません");
		}
		if (insecure) {
			System.setProperty(TLSPolicy.INSECURE, "true");
		}
		final String trustStore = System.getProperty("cti.integration.trustStore");
		if (trustStore != null && !trustStore.isEmpty()) {
			System.setProperty("javax.net.ssl.trustStore", trustStore);
			System.setProperty("javax.net.ssl.trustStorePassword", System.getProperty("cti.integration.trustStorePassword", ""));
		}
	}

	private static java.util.Map<String, String> credentials() {
		final String user = System.getProperty("cti.integration.user");
		final String password = System.getProperty("cti.integration.password");
		final java.util.Map<String, String> props = new java.util.HashMap<String, String>();
		if (user != null) {
			props.put("user", user);
		}
		if (password != null) {
			props.put("password", password);
		}
		return props;
	}

	/**
	 * 拒否試験(tls-reject / tls-badname)。接続は遅延なので getServerInfo で起こし、証明書の検証エラー
	 * (SSLHandshakeException)で拒否されることだけを確かめる。変換まで進む・接続拒否・認証失敗は成功に数えない。
	 */
	@Test
	@EnabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void rejectsUnverifiableCertificate() throws Exception {
		applyMatrixContract();
		final URI uri = URI.create(System.getProperty("cti.integration.uri"));
		final CTISession session = new CTIPDriver().getSession(uri, credentials());
		try {
			final Exception error = assertThrows(Exception.class,
					() -> session.getServerInfo(URI.create("http://www.cssj.jp/ns/ctip/version")).close());
			Throwable cause = error;
			while (cause != null && !(cause instanceof javax.net.ssl.SSLHandshakeException)) {
				cause = cause.getCause();
			}
			System.out.println("CTI-MATRIX reject: " + error);
			assertNotNull(cause, "証明書の検証以外の失敗: " + error);
		} finally {
			session.close();
		}
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void convertsOverCtipsWithVerificationOn() throws Exception {
		applyMatrixContract();
		final URI uri = URI.create(System.getProperty("cti.integration.uri"));
		final java.util.Map<String, String> props = credentials();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final CTISession session = new CTIPDriver().getSession(uri, props);
		try {
			session.setResults(new SingleResult(new StreamFragmentedOutput(out)));
			try (java.io.InputStream in = new java.io.ByteArrayInputStream(HTML.getBytes("UTF-8"))) {
				CTISessionHelper.transcodeStream(session, in, URI.create("http://example.invalid/integration.html"),
						"text/html", "UTF-8");
			}
		} finally {
			session.close();
		}
		final byte[] pdf = out.toByteArray();
		assertTrue(pdf.length > 1000, "PDF が小さすぎる: " + pdf.length + " バイト");
		assertEquals("%PDF-", new String(pdf, 0, 5, "ISO-8859-1"), "PDF になっていない");
	}

	// ---- 他の 6 本のドライバと同じ項目(2026-09-20、接続試験マトリクスの拡張)。
	// 主要機能 8 項目(サーバー情報・認証失敗・ファイル出力・ディレクトリ出力・プロパティ・リゾルバ・進行状況・reset)と
	// プロトコルの周辺機能 4 項目(メッセージ受信・中断・ストリーム出力・連続結合)。ストリーム出力は上の
	// convertsOverCtipsWithVerificationOn が兼ねる。

	private static final String MISSING_CSS_HTML = "<html><head><link rel=\"stylesheet\" href=\"missing.css\"></head><body><p>message test</p></body></html>";

	private static CTISession newSession() throws Exception {
		applyMatrixContract();
		return new CTIPDriver().getSession(URI.create(System.getProperty("cti.integration.uri")), credentials());
	}

	private static byte[] bigHtml(final int paragraphs) throws Exception {
		final StringBuilder sb = new StringBuilder("<html><body>");
		final char[] xs = new char[300];
		java.util.Arrays.fill(xs, 'x');
		final String filler = new String(xs);
		for (int i = 0; i < paragraphs; ++i) {
			sb.append("<p>paragraph ").append(i).append(' ').append(filler).append("</p>");
		}
		return sb.append("</body></html>").toString().getBytes("UTF-8");
	}

	private static void transcodeBytes(final CTISession session, final byte[] html) throws Exception {
		try (java.io.InputStream in = new java.io.ByteArrayInputStream(html)) {
			CTISessionHelper.transcodeStream(session, in, URI.create("."), "text/html", "UTF-8");
		}
	}

	private static byte[] transcodeToBytes(final CTISession session, final byte[] html) throws Exception {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		session.setResults(new SingleResult(new StreamFragmentedOutput(out)));
		transcodeBytes(session, html);
		return out.toByteArray();
	}

	private static void assertPdf(final byte[] pdf, final String what) throws Exception {
		assertTrue(pdf.length > 4 && "%PDF".equals(new String(pdf, 0, 4, "ISO-8859-1")), what + ": PDF でない(" + pdf.length + " バイト)");
	}

	private static java.io.File outDir() {
		final java.io.File dir = new java.io.File("build/real-server-out");
		dir.mkdirs();
		return dir;
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void serverInfo() throws Exception {
		try (CTISession session = newSession()) {
			final ByteArrayOutputStream info = new ByteArrayOutputStream();
			try (java.io.InputStream in = session.getServerInfo(URI.create("http://www.cssj.jp/ns/ctip/version"))) {
				final byte[] buf = new byte[4096];
				for (int n; (n = in.read(buf)) > 0;) {
					info.write(buf, 0, n);
				}
			}
			assertTrue(info.size() > 0, "サーバー情報が空");
		}
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void authenticationFailure() throws Exception {
		applyMatrixContract();
		final java.util.Map<String, String> props = new java.util.HashMap<String, String>();
		props.put("user", "invalid-user");
		props.put("password", "invalid-password");
		final CTISession session = new CTIPDriver().getSession(URI.create(System.getProperty("cti.integration.uri")), props);
		try {
			assertThrows(Exception.class, () -> transcodeToBytes(session, MISSING_CSS_HTML.getBytes("UTF-8")), "認証失敗で例外が出ない");
		} finally {
			session.close();
		}
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void outputToFile() throws Exception {
		final java.io.File file = new java.io.File(outDir(), "java-output.pdf");
		file.delete();
		try (CTISession session = newSession()) {
			session.setResults(new SingleResult(new FileFragmentedOutput(file)));
			transcodeBytes(session, HTML.getBytes("UTF-8"));
		}
		assertTrue(file.isFile(), "ファイルが無い");
		assertPdf(java.nio.file.Files.readAllBytes(file.toPath()), "file");
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void outputToDirectory() throws Exception {
		final java.io.File dir = new java.io.File(outDir(), "java-output-dir");
		dir.mkdirs();
		for (final java.io.File f : dir.listFiles()) {
			f.delete();
		}
		try (CTISession session = newSession()) {
			session.property("output.type", "image/jpeg");
			session.setResults(new DirectoryResults(dir, "", ".jpg"));
			transcodeBytes(session, HTML.getBytes("UTF-8"));
		}
		final String[] jpgs = dir.list((d, n) -> n.endsWith(".jpg"));
		assertTrue(jpgs != null && jpgs.length > 0, "出力ディレクトリに画像が無い");
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void propertySetting() throws Exception {
		try (CTISession session = newSession()) {
			session.property("output.type", "application/pdf");
			session.property("output.pdf.version", "1.4");
			assertPdf(transcodeToBytes(session, HTML.getBytes("UTF-8")), "property");
		}
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void resolverCallback() throws Exception {
		final java.util.List<String> resolved = new java.util.ArrayList<String>();
		final String html = "<html><head><link rel=\"stylesheet\" href=\"resolved.css\"></head><body><p>resolver</p></body></html>";
		try (CTISession session = newSession()) {
			session.setSourceResolver(new SourceResolver() {
				public Source resolve(final URI uri) throws java.io.IOException {
					resolved.add(uri.toString());
					return new StreamSource(uri, new java.io.ByteArrayInputStream("p { color: red; }".getBytes("UTF-8")), "text/css", "UTF-8");
				}

				public void release(final Source source) {
				}
			});
			assertPdf(transcodeToBytes(session, html.getBytes("UTF-8")), "resolver");
		}
		assertTrue(resolved.stream().anyMatch(u -> u.endsWith("resolved.css")), "リゾルバが呼ばれていない: " + resolved);
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void progressCallback() throws Exception {
		final java.util.List<Long> progress = new java.util.ArrayList<Long>();
		try (CTISession session = newSession()) {
			session.setProgressListener(new ProgressListener() {
				public void sourceLength(final long sourceLength) {
				}

				public void progress(final long serverRead) {
					progress.add(serverRead);
				}
			});
			// 進行状況はサーバー側で取得する本文(transcode(URI))で届く。他のドライバの試験と同じ URL
			session.property("input.include", "https://www.w3.org/**");
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			session.setResults(new SingleResult(new StreamFragmentedOutput(out)));
			session.transcode(URI.create("https://www.w3.org/TR/xslt-10/"));
			assertPdf(out.toByteArray(), "progress");
		}
		assertFalse(progress.isEmpty(), "進行状況が届いていない");
	}

	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void resetAndReuse() throws Exception {
		try (CTISession session = newSession()) {
			assertPdf(transcodeToBytes(session, HTML.getBytes("UTF-8")), "before reset");
			session.reset();
			assertPdf(transcodeToBytes(session, HTML.getBytes("UTF-8")), "after reset");
		}
	}

	/** 存在しないスタイルシートを参照する文書を変換し、サーバーのエラーメッセージがハンドラに届く(引数にその名前が入る)。 */
	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void messageCallback() throws Exception {
		final java.util.List<String> messages = new java.util.ArrayList<String>();
		final java.util.List<Short> hits = new java.util.ArrayList<Short>();
		try (CTISession session = newSession()) {
			session.setMessageHandler(new MessageHandler() {
				public void message(final short code, final String[] args, final String mes) {
					messages.add(code + " " + mes);
					if (java.util.Arrays.asList(args == null ? new String[0] : args).contains("missing.css")
							|| (mes != null && mes.contains("missing.css"))) {
						hits.add(code);
					}
				}
			});
			assertPdf(transcodeToBytes(session, MISSING_CSS_HTML.getBytes("UTF-8")), "message");
		}
		assertFalse(hits.isEmpty(), "missing.css についてのメッセージが届いていない: " + messages);
		for (final short code : hits) {
			assertTrue(code > 0);
		}
	}

	/**
	 * 本文の送信中に abort を送ると変換が止まり(完全な出力が返らない)、reset 後に同じセッションで再変換できる。
	 * サーバーが中断をどのメッセージ・例外で報告するかは版で違うので見ない。
	 */
	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void abortStopsConversion() throws Exception {
		final byte[] html = bigHtml(3000);
		try (CTISession session = newSession()) {
			final byte[] full = transcodeToBytes(session, html);
			assertPdf(full, "full");
			session.reset();

			final ByteArrayOutputStream aborted = new ByteArrayOutputStream();
			session.setResults(new SingleResult(new StreamFragmentedOutput(aborted)));
			final int half = html.length / 2;
			try {
				try (java.io.OutputStream out = session.transcode(new DefaultMetaSource(URI.create("."), "text/html", "UTF-8", html.length))) {
					out.write(html, 0, half);
					out.flush();
					session.abort(CTISession.ABORT_FORCE);
					out.write(html, half, html.length - half);
				}
			} catch (final Exception e) {
				System.out.println("abort reported as: " + e);
			}
			assertTrue(aborted.size() < full.length, "中断したのに完全な出力が返った");
			session.reset();

			assertPdf(transcodeToBytes(session, "<p>after abort</p>".getBytes("UTF-8")), "after abort");
		}
	}

	/** 連続モードで 2 文書を変換して join すると 1 つの PDF になる(1 文書より大きい)。 */
	@Test
	@DisabledIfSystemProperty(named = "cti.integration.expectReject", matches = "true")
	void continuousJoin() throws Exception {
		final byte[] single;
		try (CTISession session = newSession()) {
			single = transcodeToBytes(session, "<p>doc 0</p>".getBytes("UTF-8"));
		}
		final ByteArrayOutputStream joined = new ByteArrayOutputStream();
		try (CTISession session = newSession()) {
			session.setResults(new SingleResult(new StreamFragmentedOutput(joined)));
			session.setContinuous(true);
			for (int i = 0; i < 2; ++i) {
				transcodeBytes(session, ("<p>doc " + i + "</p>").getBytes("UTF-8"));
			}
			session.join();
		}
		assertPdf(joined.toByteArray(), "joined");
		assertTrue(joined.size() > single.length, "結合した出力が 1 文書より大きくない");
	}
}
