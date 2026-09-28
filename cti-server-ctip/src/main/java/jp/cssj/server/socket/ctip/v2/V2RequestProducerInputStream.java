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
	 * 本文や資源を読んでいる途中で中断({@link V2ClientPackets#ABORT})が来たときの受け口です。
	 *
	 * <p>
	 * 引数は {@link jp.cssj.cti2.CTISession#abort(byte)} に渡す値
	 * ({@code ABORT_NORMAL} / {@code ABORT_FORCE})です。
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

	/** {@link #drain()} の後。読み手が残っていても、次の要求のパケットに触らせない。 */
	private boolean drained = false;

	/**
	 * この入力の EOF を読み手が見た。以後の読み位置は資源のやり取りなど他の用途に進むことがあるので、
	 * {@link #drain()} はそれに触れない。
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
	 * 読み手が途中でやめた入力を、client の EOF まで読み捨てます(2026-09-28)。
	 *
	 * <p>
	 * 中断や変換の失敗で本文の途中で読むのをやめても、client は残りの本文と EOF を送ってくる
	 * (7 言語のドライバとも abort() は送るだけで応答を待たない)。その前に終端の応答(ABORT)を返すと、
	 * 「EOF の後に終端が来る」前提のドライバが待ち続けたり接続を捨てたりしていた。
	 * 呼び手はこれが返ってから終端の応答を送る。
	 * </p>
	 *
	 * <p>
	 * 読み取りと同じ錠で動くので、先読みのスレッドが読みかけの 1 回を終えるまで待つ。
	 * このあとの読み取りは -1 を返し、次の要求のパケットには触れない。
	 * </p>
	 *
	 * @return client の EOF で止まったら true。EOF を送らずに次の要求(RESET・CLOSE など)が来たら false で、
	 *         そのパケットは現在位置に残す(client はこの入力の終端の応答を待っていない)。
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
			// **本文・資源の途中で中断が来た**(2026-09-21)。従来はここが default に落ちて
			// 「不正なリクエストです: 32」の IllegalStateException になっていた。client には
			// 中断ではなく内部エラーとして届き、先読みを持つエンジンでは
			// 「I/O error. I/O error. prefetch read-ahead terminated」にまで化けていた。
			// 中断をセッションへ伝え、この入力はここで終端する(読み手は -1 のあとも呼ぶことがあるので、伝えるのは 1 回)。
			// 残りの本文と EOF は、呼び手が終端の応答を送る前に drain() で読み捨てる。
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