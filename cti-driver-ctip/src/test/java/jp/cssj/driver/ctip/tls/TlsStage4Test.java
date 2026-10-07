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
 * Stage 4: Server certificate verification and SNI.
 *
 * <p>
 * Previously, <b>verification was disabled by default</b>, and the setting's name
 * ({@code jp.cssj.driver.tls.trust}, default {@code true}) was the opposite of its meaning.
 * The client also sent no SNI and did not verify hostnames.
 * </p>
 *
 * <p>
 * These tests use <b>the actual client path</b> ({@link TLSSocketChannel#connect}).
 * Testing with a raw {@code SSLSocket} does not prove that the product's path uses those settings.
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

	// ------------------------------------------------------------ Setting precedence

	/** When the new name is specified, only that setting is used. */
	@Test
	void newNameWinsOverTheLegacyName() {
		System.setProperty(TLSPolicy.INSECURE, "false");
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		assertFalse(TLSPolicy.isInsecure(), "insecure=false が旧名の true に負けてはいけない");

		System.setProperty(TLSPolicy.INSECURE, "true");
		System.setProperty(TLSPolicy.LEGACY_TRUST, "false");
		assertTrue(TLSPolicy.isInsecure(), "insecure=true が旧名の false に負けてはいけない");
	}

	/** With no setting specified, verification is enabled (reversing the default). */
	@Test
	void verificationIsTheDefault() {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		assertFalse(TLSPolicy.isInsecure(), "既定は検証する");
	}

	/** When only the old name is specified, it retains its meaning (disabling verification). */
	@Test
	void legacyNameStillWorksAlone() {
		System.clearProperty(TLSPolicy.INSECURE);
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		assertTrue(TLSPolicy.isInsecure(), "旧名の true は「検証しない」");
	}

	/**
	 * The warning for the old name appears only once per JVM.
	 *
	 * <p>
	 * The state is static, so no warning appears if another test has already triggered it.
	 * Checks that <b>two consecutive calls do not produce two warnings</b>.
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

	// ------------------------------------------------------------ Actual connections

	/** A self-signed certificate is rejected by default. */
	@Test
	void selfSignedIsRejectedByDefault() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		System.clearProperty("javax.net.ssl.trustStore");
		final IOException error = assertThrows(IOException.class, () -> connect("localhost"));
		assertTrue(hasCertificateCause(error), "証明書の失敗ではない: " + describe(error));
	}

	/** {@code insecure=true} allows the connection (a test-only escape hatch). */
	@Test
	void selfSignedIsAcceptedWhenInsecure() throws Exception {
		System.setProperty(TLSPolicy.INSECURE, "true");
		assertTrue(connect("localhost"), "insecure=true で繋がらない");
	}

	/** The old name provides the same escape hatch (compatibility). */
	@Test
	void selfSignedIsAcceptedWithTheLegacyName() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.setProperty(TLSPolicy.LEGACY_TRUST, "true");
		assertTrue(connect("localhost"), "旧名の true で繋がらない");
	}

	/**
	 * Registering a custom CA allows the connection with verification enabled.
	 *
	 * <p>
	 * The certificate's SAN is {@code dns:localhost,ip:127.0.0.1}, so
	 * connecting to {@code localhost} also passes hostname verification.
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
	 * Verification fails for a name absent from the certificate's SAN.
	 *
	 * <p>
	 * Even with a trusted certificate chain, <b>a certificate for a different host is rejected</b>.
	 * This is the effect of {@code setEndpointIdentificationAlgorithm}.
	 * </p>
	 */
	@Test
	void aNameOutsideTheSanIsRejected() throws Exception {
		System.clearProperty(TLSPolicy.INSECURE);
		System.clearProperty(TLSPolicy.LEGACY_TRUST);
		System.setProperty("javax.net.ssl.trustStore", identity.toString());
		System.setProperty("javax.net.ssl.trustStorePassword", "ephemeral-test-only");
		// Connect to 127.0.0.1, but use a hostname absent from the SAN.
		final IOException error = assertThrows(IOException.class, () -> connect("not-in-san.example"));
		assertTrue(hasCertificateCause(error), "ホスト名の不一致で落ちていない: " + describe(error));
	}

	// ------------------------------------------------------------ SNI

	/**
	 * Connecting with an IP literal also succeeds.
	 *
	 * <p>
	 * SNI cannot contain IP addresses (RFC 6066). Including one makes the JDK throw
	 * {@code IllegalArgumentException}, so <b>a successful connection itself proves that
	 * the IP address is not included</b>. Hostname verification passes because the certificate's
	 * SAN contains {@code ip:127.0.0.1}.
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

	// ------------------------------------------------------------ Helpers

	/**
	 * Connects to the local TLS server using the given hostname.
	 *
	 * <p>
	 * The destination is always {@code 127.0.0.1}; <b>only the hostname used</b> changes.
	 * This isolates hostname verification for testing.
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
				// This fails if the client rejects the connection, which is the expected behavior.
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
