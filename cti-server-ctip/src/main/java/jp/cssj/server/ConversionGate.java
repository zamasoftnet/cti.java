package jp.cssj.server;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Limits the number of concurrent conversions (2026-10-03, shared service resource limits, increment 3).
 *
 * <p>
 * REST and CTIP share one gate that counts <b>active conversions</b> (sessions that are only waiting do not
 * count). When no capacity is available, it <b>rejects requests without waiting</b>: waiting occupies request
 * threads or connection slots and prevents reading abort requests during the wait. Rejected users receive
 * only the message "The server is busy. Please wait a little and try again."
 * </p>
 *
 * <p>
 * Release a permit ({@link Permit}) with {@link Permit#close()}, which takes effect only once.
 * Releasing it twice does not increase the limit.
 * </p>
 */
public final class ConversionGate {
	/** No limit (count only). */
	public static final ConversionGate UNLIMITED = new ConversionGate(0);

	private final int limit;

	private final Semaphore permits;

	private final AtomicInteger running = new AtomicInteger();

	private final LongAdder refused = new LongAdder();

	/**
	 * @param limit the number of concurrent conversions; zero or less means no limit
	 */
	public ConversionGate(final int limit) {
		this.limit = Math.max(0, limit);
		this.permits = this.limit > 0 ? new Semaphore(this.limit) : null;
	}

	/**
	 * Acquires a permit without waiting.
	 *
	 * @return a permit, or null if no capacity is available (counted as a rejected request)
	 */
	public Permit tryEnter() {
		if (this.permits != null && !this.permits.tryAcquire()) {
			this.refused.increment();
			return null;
		}
		this.running.incrementAndGet();
		return new Permit();
	}

	/** @return the maximum number of concurrent conversions (0 means no limit) */
	public int limit() {
		return this.limit;
	}

	/** @return the number of active conversions */
	public int running() {
		return this.running.get();
	}

	/** @return the number of requests rejected due to lack of capacity (total since startup) */
	public long refused() {
		return this.refused.sum();
	}

	/** A permit for one conversion. */
	public final class Permit implements AutoCloseable {
		private final AtomicBoolean closed = new AtomicBoolean();

		private Permit() {
			// Only ConversionGate#tryEnter creates instances
		}

		/** Releases the permit. Subsequent calls do nothing. */
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
