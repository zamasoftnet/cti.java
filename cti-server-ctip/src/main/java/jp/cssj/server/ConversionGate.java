package jp.cssj.server;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 同時に走る変換の数の上限です(2026-10-03、共有サービスの資源の上限 増分3)。
 *
 * <p>
 * REST と CTIP が 1 つのゲートを共有し、<b>実行中の変換</b>を数えます(待機しているだけのセッションは
 * 数えない)。空きが無ければ<b>待たずに断ります</b>——待つと要求を受けるスレッドや接続の枠を塞ぎ、
 * 待っている間は中断も読めないためです。断られた利用者には「混雑しています。少し待ってからやり直して
 * ください」とだけ伝わります。
 * </p>
 *
 * <p>
 * 許可({@link Permit})は 1 回だけ効く{@link Permit#close()}で返します。2 回返しても上限は増えません。
 * </p>
 */
public final class ConversionGate {
	/** 上限なし(数えるだけ)。 */
	public static final ConversionGate UNLIMITED = new ConversionGate(0);

	private final int limit;

	private final Semaphore permits;

	private final AtomicInteger running = new AtomicInteger();

	private final LongAdder refused = new LongAdder();

	/**
	 * @param limit 同時に走る変換の数。0 以下は上限なし
	 */
	public ConversionGate(final int limit) {
		this.limit = Math.max(0, limit);
		this.permits = this.limit > 0 ? new Semaphore(this.limit) : null;
	}

	/**
	 * 待たずに許可を取ります。
	 *
	 * @return 許可。空きが無ければ null(断った件数に数える)
	 */
	public Permit tryEnter() {
		if (this.permits != null && !this.permits.tryAcquire()) {
			this.refused.increment();
			return null;
		}
		this.running.incrementAndGet();
		return new Permit();
	}

	/** @return 同時に走る変換の数の上限(0 は上限なし) */
	public int limit() {
		return this.limit;
	}

	/** @return 実行中の変換の数 */
	public int running() {
		return this.running.get();
	}

	/** @return 空きが無くて断った数(起動からの累計) */
	public long refused() {
		return this.refused.sum();
	}

	/** 変換 1 件分の許可です。 */
	public final class Permit implements AutoCloseable {
		private final AtomicBoolean closed = new AtomicBoolean();

		private Permit() {
			// ConversionGate#tryEnter だけが作る
		}

		/** 許可を返します。2 回目以降は何もしません。 */
		@Override
		public void close() {
			if (this.closed.compareAndSet(false, true)) {
				ConversionGate.this.running.decrementAndGet();
				if (ConversionGate.this.permits != null) {
					ConversionGate.this.permits.release();
				}
			}
		}
	}
}
