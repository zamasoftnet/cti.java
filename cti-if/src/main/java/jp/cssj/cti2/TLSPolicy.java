package jp.cssj.cti2;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls whether to verify the server certificate.
 *
 * <p>
 * <b>Verification is enabled by default.</b> Previously, verification was disabled by default, and the setting
 * was named {@code jp.cssj.driver.tls.trust} (default {@code true})—<b>the name and meaning
 * were reversed</b>. "Trust" meant "accept anything."
 * </p>
 *
 * <p>
 * The new name is {@link #INSECURE} ({@code jp.cssj.driver.tls.insecure}),
 * with a default of {@code false} (verification enabled). Set it to {@code true} only when
 * connecting to a test server that uses a self-signed certificate.
 * </p>
 *
 * <h2>Precedence</h2>
 *
 * <p>
 * If the new name is specified, <b>only that setting</b> is used. The legacy name is checked only when
 * the new name is absent, and {@code true} disables verification.
 * <b>{@code insecure=false} is never overridden by {@code true} under the legacy name.</b>
 * </p>
 *
 * <h2>Different meanings for CTIP and REST</h2>
 *
 * <p>
 * With {@code insecure=true}, CTIP <b>accepts anything</b>. REST
 * <b>trusts certificate chains containing only one certificate</b> and uses normal verification
 * for all other chains (HttpClient's {@code TrustSelfSignedStrategy}). Both are
 * workarounds for testing and must not be used in production.
 * </p>
 */
public final class TLSPolicy {

	/** Whether to skip server certificate verification. Defaults to {@code false} (verification enabled). */
	public static final String INSECURE = "jp.cssj.driver.tls.insecure";

	/**
	 * The legacy name. It meant "accept anything," which was the opposite of what the name suggested.
	 *
	 * @deprecated Use {@link #INSECURE}.
	 */
	@Deprecated
	public static final String LEGACY_TRUST = "jp.cssj.driver.tls.trust";

	private static final Logger LOG = Logger.getLogger(TLSPolicy.class.getName());

	/** Warn about the legacy name only once per JVM, even when both CTIP and REST are used. */
	private static final AtomicBoolean WARNED = new AtomicBoolean();

	private TLSPolicy() {
		// Prevent instantiation
	}

	/** Returns whether to skip server certificate verification. */
	public static boolean isInsecure() {
		final String value = System.getProperty(INSECURE);
		if (value != null) {
			// **If the new name is specified, use only that setting**
			return Boolean.parseBoolean(value);
		}
		final String legacy = System.getProperty(LEGACY_TRUST);
		if (legacy != null && Boolean.parseBoolean(legacy)) {
			if (WARNED.compareAndSet(false, true)) {
				LOG.log(Level.WARNING, LEGACY_TRUST + " は名前と意味が反転していたため廃止予定です。"
						+ "サーバー証明書を検証しないなら " + INSECURE + "=true を使ってください。"
						+ "検証するのが既定です。");
			}
			return true;
		}
		return false;
	}
}
