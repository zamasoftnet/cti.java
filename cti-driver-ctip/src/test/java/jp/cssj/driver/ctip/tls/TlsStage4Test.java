package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jp.cssj.cti2.TLSPolicy;
import jp.cssj.driver.ctip.v2.TLSSocketChannel;

/**
 * 段階4: サーバー証明書の検証とSNI。
 *
 * <p>
 * これまでは<b>検証しないのが既定</b>で、しかも設定の名前
 * ({@code jp.cssj.driver.tls.trust}、既定 {@code true})は意味と反転して
 * いました。SNIも送らず、ホスト名も検証していませんでした。
 * </p>
 *
 * <p>
 * ここでは<b>実際のクライアント経路</b>({@link TLSSocketChannel#connect})で
 * 測ります。生の{@code SSLSocket}で測っても、製品の経路がその設定を使って
 * いる証拠にはならないためです。
 * </p>
 */
class TlsStage4Test {

	@TempDir
	static Path temporary;

	private static Path identity;

	private final List<String> saved = new ArrayList<String>();

	@BeforeAll
	static void identity() throws Exception {
		identity = LocalTls.generateIdentity(temporary);
	}

	@BeforeEach
	void rememberProperties() {
		this.saved.add(System.getProperty(TLSPolicy.INSECURE));
		this.saved.add(System.getProperty(TLSPolicy.LEGACY_TRUST));
		this.saved.add(System.getProperty("javax.net.ssl.trustStore"));
		this.saved.add(System.getProperty("javax.net.ssl.trustStorePassword"));
	}

	@AfterEach
	void restoreProperties() {
		restore(TLSPolicy.INSECURE, this.saved.get(0));
		restore(TLSPolicy.LEGACY_TRUST, this.saved.get(1));
		restore("javax.net.ssl.trustStore", this.saved.get(2));
		restore("javax.net.ssl.trustStorePassword", this.saved.get(3));
		this.saved.clear();
	}

	private static void restore(final String name, final String value) {
		if (value == null) {
			System.clearProperty(name);
		} else {
			System.setProperty(name, value);
		}
	}

	// ------------------------------------------------------------ 設定の優先順位

	/** 新しい名前が指定されたら、それだけを見ること。 */
	@Test
	void newNameWinsOverTheLegacyName() {
		System.setProperty(TLSPolicy.INSECURE, "false");
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		assertFalse(TLSPolicy.isInsecure(), "insecure=false が旧名の true に負けてはいけない");

		System.setProperty(TLSPolicy.INSECURE, "true");
		System.setProperty(TLSPolicy.LEGACY_TRUST, "false");
		assertTrue(TLSPolicy.isInsecure(), "insecure=true が旧名の false に負けてはいけない");
	}

	/** 何も指定しなければ検証すること(既定の反転)。 */
	@Test
	void verificationIsTheDefault() {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		assertFalse(TLSPolicy.isInsecure(), "既定は検証する");
	}

	/** 旧名だけを指定したときは、その意味(検証しない)で効くこと。 */
	@Test
	void legacyNameStillWorksAlone() {
		System.clearProperty(TLSPolicy.INSECURE);
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		assertTrue(TLSPolicy.isInsecure(), "旧名の true は「検証しない」");
	}

	/**
	 * 旧名の警告はJVMごとに1回だけであること。
	 *
	 * <p>
	 * 静的な状態なので、他の試験が先に警告を出していれば0回になります。
	 * <b>2回続けて呼んで2回出ない</b>ことを見ます。
	 * </p>
	 */
	@Test
	void theLegacyWarningIsNotRepeated() {
		System.clearProperty(TLSPolicy.INSECURE);
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		final Logger logger = Logger.getLogger(TLSPolicy.class.getName());
		final List<LogRecord> records = new ArrayList<LogRecord>();
		final Handler handler = new Handler() {
			public void publish(final LogRecord record) {
				records.add(record);
			}

			public void flush() {
			}

			public void close() {
			}
		};
		logger.addHandler(handler);
		try {
			TLSPolicy.isInsecure();
			TLSPolicy.isInsecure();
		} finally {
			logger.removeHandler(handler);
		}
		assertTrue(records.size() <= 1, "旧名の警告が繰り返された: " + records.size() + " 回");
		for (final LogRecord record : records) {
			assertEquals(Level.WARNING, record.getLevel());
			assertTrue(record.getMessage().contains(TLSPolicy.INSECURE),
					"警告に新しい名前が書かれていない: " + record.getMessage());
		}
	}

	// ------------------------------------------------------------ 実際の接続

	/** 自己署名の証明書は、既定では拒むこと。 */
	@Test
	void selfSignedIsRejectedByDefault() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		System.clearProperty("javax.net.ssl.trustStore");
		final IOException error = assertThrows(IOException.class, () -> connect("localhost"));
		assertTrue(hasCertificateCause(error), "証明書の失敗ではない: " + describe(error));
	}

	/** {@code insecure=true} なら通ること(試験用の逃げ道)。 */
	@Test
	void selfSignedIsAcceptedWhenInsecure() throws Exception {
		System.setProperty(TLSPolicy.INSECURE, "true");
		assertTrue(connect("localhost"), "insecure=true で繋がらない");
	}

	/** 旧名でも同じ逃げ道が効くこと(互換)。 */
	@Test
	void selfSignedIsAcceptedWithTheLegacyName() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		assertTrue(connect("localhost"), "旧名の true で繋がらない");
	}

	/**
	 * 独自CAを登録すれば、検証したまま通ること。
	 *
	 * <p>
	 * 証明書のSANは {@code dns:localhost,ip:127.0.0.1} なので、
	 * {@code localhost} で繋げばホスト名の検証も通ります。
	 * </p>
	 */
	@Test
	void explicitTrustStorePassesWithVerificationOn() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		System.setProperty("javax.net.ssl.trustStore", identity.toString());
		System.setProperty("javax.net.ssl.trustStorePassword", "ephemeral-test-only");
		assertTrue(connect("localhost"), "独自CAを登録しても繋がらない");
	}

	/**
	 * 証明書のSANに無い名前では、検証が落ちること。
	 *
	 * <p>
	 * 証明書チェーンが信頼できても、<b>別のホストの証明書なら受け入れない</b>。
	 * これが{@code setEndpointIdentificationAlgorithm}の効き目です。
	 * </p>
	 */
	@Test
	void aNameOutsideTheSanIsRejected() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		System.setProperty("javax.net.ssl.trustStore", identity.toString());
		System.setProperty("javax.net.ssl.trustStorePassword", "ephemeral-test-only");
		// 127.0.0.1 へ繋ぐが、名前は SAN に無いものを名乗る
		final IOException error = assertThrows(IOException.class, () -> connect("not-in-san.example"));
		assertTrue(hasCertificateCause(error), "ホスト名の不一致で落ちていない: " + describe(error));
	}

	// ------------------------------------------------------------ SNI

	/**
	 * IPリテラルで繋いでも通ること。
	 *
	 * <p>
	 * SNIにはIPアドレスを載せられません(RFC 6066)。載せるとJDKが
	 * {@code IllegalArgumentException}を投げるので、<b>繋がること自体が
	 * 載せていない証拠</b>になります。証明書のSANには
	 * {@code ip:127.0.0.1}があるので、ホスト名の検証は通ります。
	 * </p>
	 */
	@Test
	void anIpLiteralConnectsWithVerificationOn() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		System.setProperty("javax.net.ssl.trustStore", identity.toString());
		System.setProperty("javax.net.ssl.trustStorePassword", "ephemeral-test-only");
		assertTrue(connect("127.0.0.1"), "IPリテラルで繋がらない(SNIに載せてしまっていないか)");
	}

	// ------------------------------------------------------------ 補助

	/**
	 * ローカルのTLSサーバへ、その名前を名乗って繋ぎます。
	 *
	 * <p>
	 * 接続先は常に {@code 127.0.0.1} で、<b>名乗る名前だけ</b>を変えます。
	 * こうするとホスト名の検証だけを切り分けて測れます。
	 * </p>
	 */
	private static boolean connect(final String name) throws Exception {
		final LocalTls tls = new LocalTls(identity);
		final SSLServerSocket server = tls.listen("TLSv1.3");
		final Thread accepting = new Thread(() -> {
			try (SSLSocket socket = (SSLSocket) server.accept()) {
				socket.startHandshake();
				socket.getInputStream().read();
			} catch (final Exception e) {
				// クライアント側が拒否すればここは落ちる。それが期待の形
			}
		});
		accepting.setDaemon(true);
		accepting.start();
		try {
			final int port = server.getLocalPort();
			try (TLSSocketChannel channel = new TLSSocketChannel(SocketChannel.open())) {
				return channel.connect(new InetSocketAddress("127.0.0.1", port), name, port, 10000L);
			}
		} finally {
			server.close();
			accepting.join(5000);
		}
	}

	private static boolean hasCertificateCause(final Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof java.security.cert.CertificateException) {
				return true;
			}
			if (t instanceof SSLException && t.getMessage() != null
					&& (t.getMessage().contains("certificate") || t.getMessage().contains("PKIX")
							|| t.getMessage().contains("subject alternative"))) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private static String describe(final Throwable error) {
		final StringBuilder buff = new StringBuilder();
		for (Throwable t = error; t != null; t = t.getCause()) {
			buff.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" / ");
			if (t.getCause() == t) {
				break;
			}
		}
		return buff.toString();
	}
}
