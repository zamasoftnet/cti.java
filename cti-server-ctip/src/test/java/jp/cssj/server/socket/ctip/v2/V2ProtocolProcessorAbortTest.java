package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

import org.junit.jupiter.api.Test;

import jp.cssj.cti2.TranscoderException;
import jp.cssj.driver.ctip.v2.V2ServerPackets;

class V2ProtocolProcessorAbortTest {
	@Test
	void readableAbortFlushesDataAndUsesAbortAsTheOnlyTerminalPacket() throws Exception {
		final V2ProtocolProcessor processor = new V2ProtocolProcessor(URI.create("ctip://localhost/"), null);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		setField(processor, "out", new DataOutputStream(bytes));
		setField(processor, "charset", "UTF-8");
		processor.write(new byte[] { 1, 2, 3 }, 0, 3);

		invokeAbort(processor, new TranscoderException(TranscoderException.STATE_READABLE, (short) 1,
				new String[0], "interrupted"));

		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
		assertEquals(4, in.readInt());
		assertEquals(V2ServerPackets.DATA, in.readByte());
		final byte[] data = new byte[3];
		in.readFully(data);
		assertArrayEquals(new byte[] { 1, 2, 3 }, data);
		final int abortLength = in.readInt();
		assertEquals(V2ServerPackets.ABORT, in.readByte());
		assertEquals(0, in.readByte());
		in.skipBytes(abortLength - 2);
		assertEquals(-1, in.read());
	}

	@Test
	void readableFailureAfterEofDoesNotAppendAnUnreachableAbort() throws Exception {
		final V2ProtocolProcessor processor = new V2ProtocolProcessor(URI.create("ctip://localhost/"), null);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		setField(processor, "out", new DataOutputStream(bytes));
		setField(processor, "charset", "UTF-8");
		processor.end();

		invokeAbort(processor, new TranscoderException(TranscoderException.STATE_READABLE, (short) 1,
				new String[0], "interrupted"));

		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
		assertEquals(1, in.readInt());
		assertEquals(V2ServerPackets.EOF, in.readByte());
		assertEquals(-1, in.read());
	}

	private static void setField(final V2ProtocolProcessor processor, final String name, final Object value)
			throws Exception {
		final Field field = V2ProtocolProcessor.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(processor, value);
	}

	private static void invokeAbort(final V2ProtocolProcessor processor, final TranscoderException failure)
			throws Exception {
		final Method method = V2ProtocolProcessor.class.getDeclaredMethod("abort", TranscoderException.class);
		method.setAccessible(true);
		try {
			method.invoke(processor, failure);
		} catch (InvocationTargetException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw e;
		}
	}
}
