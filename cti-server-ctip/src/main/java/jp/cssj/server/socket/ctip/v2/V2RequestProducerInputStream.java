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

	private boolean checkRequest() throws IOException {
		switch (this.request.getType()) {
		case V2ClientPackets.EOF:
			return false;

		case V2ClientPackets.DATA:
			return true;

		case V2ClientPackets.ABORT:
			// **本文・資源の途中で中断が来た**(2026-09-21)。従来はここが default に落ちて
			// 「不正なリクエストです: 32」の IllegalStateException になっていた。client には
			// 中断ではなく内部エラーとして届き、先読みを持つエンジンでは
			// 「I/O error. I/O error. prefetch read-ahead terminated」にまで化けていた。
			// 中断をセッションへ伝え、この入力はここで終端する。
			if (this.onAbort != null) {
				this.onAbort.abort((byte) (this.request.getMode() + 1));
			}
			return false;

		default:
			throw new IllegalStateException("不正なリクエストです: " + Integer.toHexString(this.request.getType()));
		}
	}

	public int read() throws IOException {
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

	public int read(byte[] b, int off, int len) throws IOException {
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

	public int read(byte[] b) throws IOException {
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