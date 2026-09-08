package jp.cssj.cti2;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * サーバー証明書を検証するかどうかの設定です。
 *
 * <p>
 * <b>既定は検証します。</b>以前は検証しないのが既定で、しかも設定の名前は
 * {@code jp.cssj.driver.tls.trust}(既定 {@code true})——<b>名前と意味が
 * 反転していました</b>。「信頼する」が「何でも通す」を意味していたのです。
 * </p>
 *
 * <p>
 * 新しい名前は {@link #INSECURE}({@code jp.cssj.driver.tls.insecure})で、
 * 既定は {@code false}(＝検証する)。自己署名の証明書を使う試験用サーバーへ
 * 繋ぐときだけ {@code true} にしてください。
 * </p>
 *
 * <h2>優先順位</h2>
 *
 * <p>
 * 新しい名前が指定されていれば<b>それだけ</b>を見ます。指定されていないときだけ
 * 旧名を見て、{@code true} なら検証しないものとして扱います。
 * <b>{@code insecure=false} が旧名の {@code true} に負けることはありません。</b>
 * </p>
 *
 * <h2>CTIP と REST で意味が違う</h2>
 *
 * <p>
 * {@code insecure=true} のとき、CTIP は<b>何でも通します</b>。RESTは
 * <b>証明書チェーンが1つだけのものを信頼扱いにし</b>、それ以外は通常の検証へ
 * 委ねます(HttpClient の {@code TrustSelfSignedStrategy})。どちらも
 * 試験用の逃げ道であって、本番で使うものではありません。
 * </p>
 */
public final class TLSPolicy {

	/** サーバー証明書を検証しないかどうか。既定は{@code false}(検証する)。 */
	public static final String INSECURE = "jp.cssj.driver.tls.insecure";

	/**
	 * 旧名です。意味は「何でも通す」で、名前と反転していました。
	 *
	 * @deprecated {@link #INSECURE}を使ってください。
	 */
	@Deprecated
	public static final String LEGACY_TRUST = "jp.cssj.driver.tls.trust";

	private static final Logger LOG = Logger.getLogger(TLSPolicy.class.getName());

	/** 旧名の警告はJVMごとに1回だけ。CTIPとRESTの両方を使っても1回。 */
	private static final AtomicBoolean WARNED = new AtomicBoolean();

	private TLSPolicy() {
		// インスタンスを作らない
	}

	/** サーバー証明書の検証を省くかどうかです。 */
	public static boolean isInsecure() {
		final String value = System.getProperty(INSECURE);
		if (value != null) {
			// **新しい名前が指定されたら、それだけを見る**
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
