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
import net.zamasoft.zstream.io.impl.StreamFragmentedOutput;

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
}
