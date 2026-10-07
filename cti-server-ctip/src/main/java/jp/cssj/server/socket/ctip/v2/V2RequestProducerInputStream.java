package jp.cssj.server.socket.ctip.v2;

import java.io.IOException;
import java.io.InputStream;

import jp.cssj.cti2.progress.Progressive;
import jp.cssj.driver.ctip.v2.V2ClientPackets;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: RequestProducerInputStream.java,v 1.1 2005/03/26 10:22:30
 *          harumanx Exp $
 */
public class V2RequestProducerInputStream extends InputStream implements Progressive {
	/**
	 * Handles abort requests ({@link V2ClientPackets#ABORT}) received while reading a body or resource.
	 *
	 * <p>
	 * The argument is the value passed to {@link jp.cssj.cti2.CTISession#abort(byte)}
	 * ({@code ABORT_NORMAL} / {@code ABORT_FORCE}).
	 * </p>
	 */
	public interface AbortRequest {
		void abort(byte mode) throws IOException;
	}

	private final V2RequestProducer request;

	private final AbortRequest onAbort;

	private final byte[] buff = new byte[1];

	private int progress = 0;

	private boolean abortNotified = false;

	/** Set after {@link #drain()}. Prevent any remaining readers from accessing packets for the next request. */
	private boolean drained = false;

	/**
	 * A reader has seen EOF for this input. The read position may then advance for other purposes, such as
	 * resource exchange, so {@link #drain()} does not touch it.
	 */
	private boolean ended = false;

	public V2RequestProducerInputStream(V2RequestProducer producer) {
		this(producer, null);
	}

	public V2RequestProducerInputStream(V2RequestProducer producer, AbortRequest onAbort) {
		this.request = producer;
		this.onAbort = onAbort;
	}

	public long getProgress() {
		return this.progress;
	}

	/**
	 * Drains input abandoned by the reader through the client's EOF (2026-09-28).
	 *
	 * <p>
	 * Even if reading stops midway through the body due to an abort or conversion failure, the client sends the
	 * rest of the body and EOF (abort() only sends a request and does not wait for a response in all 7 language
	 * drivers). Returning a terminal response (ABORT) earlier caused drivers expecting a terminal response after
	 * EOF to wait indefinitely or discard the connection. The caller sends the terminal response after this returns.
	 * </p>
	 *
	 * <p>
	 * This uses the same lock as reading, so it waits for any read in progress on the read-ahead thread to finish.
	 * Subsequent reads return -1 without touching packets for the next request.
	 * </p>
	 *
	 * @return true if draining stops at the client's EOF; false if the next request (RESET, CLOSE, etc.) arrives
	 *         without EOF. Leave that packet at the current position (the client is not waiting for this input's
	 *         terminal response).
	 */
	public synchronized boolean drain() throws IOException {
		this.drained = true;
		if (this.ended) {
			return true;
		}
		for (;;) {
			switch (this.request.getType()) {
			case V2ClientPackets.EOF:
				return true;
			case V2ClientPackets.DATA:
			case V2ClientPackets.ABORT:
				this.request.next();
				break;
			default:
				return false;
			}
		}
	}

	private boolean checkRequest() throws IOException {
		if (this.drained) {
			return false;
		}
		switch (this.request.getType()) {
		case V2ClientPackets.EOF:
			this.ended = true;
			return false;

		case V2ClientPackets.DATA:
			return true;

		case V2ClientPackets.ABORT:
			// **Abort during a body or resource** (2026-09-21). This previously fell through to default
			// and threw an IllegalStateException with "Bad request: 32". The client received
			// an internal error instead of an abort, and engines with read-ahead even reported it as
			// "I/O error. I/O error. prefetch read-ahead terminated".
			// Notify the session of the abort once and end this input here (readers may call again after -1).
			// The caller uses drain() to discard the remaining body and EOF before sending the terminal response.
			if (!this.abortNotified) {
				this.abortNotified = true;
				if (this.onAbort != null) {
					this.onAbort.abort((byte) (this.request.getMode() + 1));
				}
			}
			return false;

		default:
			throw new IllegalStateException("不正なリクエストです: " + Integer.toHexString(this.request.getType()));
		}
	}

	public synchronized int read() throws IOException {
		if (!this.checkRequest()) {
			return -1;
		}
		int read = this.request.read(this.buff, 0, 1);
		if (read == 1) {
			++this.progress;
			return this.buff[0];
		}
		this.request.next();
		if (!this.checkRequest()) {
			return -1;
		}
		return this.read();
	}

	public synchronized int read(byte[] b, int off, int len) throws IOException {
		if (!this.checkRequest()) {
			return -1;
		}
		int read = this.request.read(b, off, len);
		if (read != -1) {
			this.progress += read;
			return read;
		}
		this.request.next();
		if (!this.checkRequest()) {
			return -1;
		}
		return this.read(b, off, len);
	}

	public synchronized int read(byte[] b) throws IOException {
		if (!this.checkRequest()) {
			return -1;
		}
		int read = this.request.read(b, 0, b.length);
		if (read != -1) {
			this.progress += read;
			return read;
		}
		this.request.next();
		if (!this.checkRequest()) {
			return -1;
		}
		return this.read(b, 0, b.length);
	}
}