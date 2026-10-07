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
 * Handling of abort requests ({@link V2ClientPackets#ABORT}) received while reading a body or resource (2026-09-21).
 *
 * <p>
 * Previously, {@link V2RequestProducerInputStream} treated this as an unexpected packet and threw
 * {@code IllegalStateException("Bad request: 32")}. The client received an internal error instead of an abort,
 * and engines with a read-ahead buffer (copper4) even reported it as
 * "I/O error. I/O error. prefetch read-ahead terminated".
 * The correct behavior is to notify the session of the abort and end this input.
 * </p>
 */
class V2RequestProducerInputStreamAbortTest {

	/** A byte sequence that sends one DATA packet followed by ABORT (mode 1 = force). */
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

	/** When an abort arrives, notify the session and return EOF for the input. */
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

		// Reading further reaches ABORT. End with EOF instead of an exception
		assertEquals(-1, in.read(), "中断のあと入力が終端していない");
		assertEquals(CTISession.ABORT_FORCE, seen[0], "強制中断としてセッションへ伝わっていない");
	}

	/** Mode 0 (stop at a suitable stopping point) is passed as {@code ABORT_NORMAL}. */
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
			// Read through DATA
		}
		assertEquals(CTISession.ABORT_NORMAL, seen[0]);
	}

	/** Even without a handler, end the input without failing (e.g., when reading a resource). */
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

	/** Reject unexpected packets other than aborts as before. */
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
				// Read until CLOSE
			}
		});
		assertTrue(e.getMessage().contains("不正なリクエストです"), e.getMessage());
	}
}
