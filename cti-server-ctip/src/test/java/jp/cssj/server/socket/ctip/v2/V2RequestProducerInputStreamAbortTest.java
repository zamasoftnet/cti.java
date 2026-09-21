package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import jp.cssj.cti2.CTISession;
import jp.cssj.driver.ctip.v2.V2ClientPackets;

/**
 * 本文・資源の読み取り中に届いた中断({@link V2ClientPackets#ABORT})の扱いです(2026-09-21)。
 *
 * <p>
 * 従来は {@link V2RequestProducerInputStream} の想定外パケットとして
 * {@code IllegalStateException("不正なリクエストです: 32")} になっていた。client には中断ではなく
 * 内部エラーが届き、先読みバッファを持つエンジン(copper4)では
 * 「I/O error. I/O error. prefetch read-ahead terminated」まで化けていた。
 * 正しくはセッションへ中断を伝え、この入力を終端する。
 * </p>
 */
class V2RequestProducerInputStreamAbortTest {

	/** DATA 1 つ、続けて ABORT(mode 1=強制)を送るバイト列。 */
	private static byte[] dataThenAbort(final byte[] data, final int abortMode) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(1 + data.length);
		out.writeByte(V2ClientPackets.DATA);
		out.write(data);
		out.writeInt(2);
		out.writeByte(V2ClientPackets.ABORT);
		out.writeByte(abortMode);
		out.flush();
		return bytes.toByteArray();
	}

	/** 中断が来たら、セッションへ中断を伝えて入力は EOF になる。 */
	@Test
	void mainBodyAbortIsForwardedToTheSessionAndEndsTheInput() throws Exception {
		final byte[] data = "<html><body><p>a".getBytes("UTF-8");
		final V2RequestProducer producer = new V2RequestProducer("UTF-8",
				new ByteArrayInputStream(dataThenAbort(data, 1)));
		producer.next();
		final byte[] seen = new byte[1];
		final V2RequestProducerInputStream in = new V2RequestProducerInputStream(producer, mode -> {
			seen[0] = mode;
		});

		final byte[] buf = new byte[data.length];
		int off = 0;
		for (int n; off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0;) {
			off += n;
		}
		assertEquals(data.length, off, "DATA を読み切れていない");
		assertEquals(new String(data, "UTF-8"), new String(buf, "UTF-8"));

		// 続きを読むと ABORT に当たる。例外ではなく EOF で終わる
		assertEquals(-1, in.read(), "中断のあと入力が終端していない");
		assertEquals(CTISession.ABORT_FORCE, seen[0], "強制中断としてセッションへ伝わっていない");
	}

	/** mode 0(きりのよいところまで)は {@code ABORT_NORMAL} として伝わる。 */
	@Test
	void normalAbortModeIsMapped() throws Exception {
		final V2RequestProducer producer = new V2RequestProducer("UTF-8",
				new ByteArrayInputStream(dataThenAbort("x".getBytes("UTF-8"), 0)));
		producer.next();
		final byte[] seen = new byte[1];
		final V2RequestProducerInputStream in = new V2RequestProducerInputStream(producer, mode -> {
			seen[0] = mode;
		});
		while (in.read() >= 0) {
			// DATA を読み進める
		}
		assertEquals(CTISession.ABORT_NORMAL, seen[0]);
	}

	/** 受け口を渡さない場合も、落ちずに終端する(資源の読み取り等)。 */
	@Test
	void abortWithoutHandlerStillEndsTheInput() throws Exception {
		final V2RequestProducer producer = new V2RequestProducer("UTF-8",
				new ByteArrayInputStream(dataThenAbort("x".getBytes("UTF-8"), 1)));
		producer.next();
		final V2RequestProducerInputStream in = new V2RequestProducerInputStream(producer);
		int n = 0;
		while (in.read() >= 0) {
			++n;
		}
		assertEquals(1, n);
	}

	/** 中断以外の想定外パケットは従来どおり断る。 */
	@Test
	void otherUnexpectedPacketsStillFail() throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(2);
		out.writeByte(V2ClientPackets.DATA);
		out.writeByte('x');
		out.writeInt(1);
		out.writeByte(V2ClientPackets.CLOSE);
		out.flush();
		final V2RequestProducer producer = new V2RequestProducer("UTF-8",
				new ByteArrayInputStream(bytes.toByteArray()));
		producer.next();
		final V2RequestProducerInputStream in = new V2RequestProducerInputStream(producer, mode -> {
		});
		final IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
			while (in.read() >= 0) {
				// CLOSE に当たるまで読む
			}
		});
		assertTrue(e.getMessage().contains("不正なリクエストです"), e.getMessage());
	}
}
