package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;

import org.junit.jupiter.api.Test;
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
	@Test
	void convertsOverCtipsWithVerificationOn() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		final URI uri = URI.create(System.getProperty("cti.integration.uri"));
		final String user = System.getProperty("cti.integration.user");
		final String password = System.getProperty("cti.integration.password");
		final java.util.Map<String, String> props = new java.util.HashMap<String, String>();
		if (user != null) {
			props.put("user", user);
		}
		if (password != null) {
			props.put("password", password);
		}
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
