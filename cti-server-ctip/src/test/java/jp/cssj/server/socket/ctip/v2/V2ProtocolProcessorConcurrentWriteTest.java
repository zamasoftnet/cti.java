package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import jp.cssj.driver.ctip.v2.V2ServerPackets;

/**
 * Verifies that response packets do not interleave even when multiple threads write them (2026-10-04).
 *
 * <p>
 * When a client's ABORT arrives midway through the body, the server receives it on the thread reading the body
 * (input read-ahead in copper4) and writes the abort report (MESSAGE) there immediately. If the conversion thread
 * was writing data at the same time, bytes from one thread were inserted into the middle of the other's packet,
 * causing the client to fail with "Trailing bytes in CTIP response" (copper4's ClientAbortTest occasionally
 * failed in the validation gate).
 * </p>
 */
class V2ProtocolProcessorConcurrentWriteTest {
	/**
	 * Pauses the writer after it writes the data packet's 4-byte length, then has another thread write a message.
	 * With mutual exclusion, the message writer waits and the paused writer resumes after a timeout.
	 */
	private static final class PausingOutput extends OutputStream {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final CountDownLatch dataStarted = new CountDownLatch(1);
		final CountDownLatch messageWritten = new CountDownLatch(1);
		volatile Thread dataThread;
		private int dataBytes;

		@Override
		public void write(final int b) throws IOException {
			synchronized (this) {
				this.bytes.write(b);
			}
			if (Thread.currentThread() == this.dataThread && ++this.dataBytes == 4) {
				this.dataStarted.countDown();
				try {
					this.messageWritten.await(500, TimeUnit.MILLISECONDS);
				} catch (final InterruptedException e) {
					throw new IOException(e);
				}
			}
		}

		@Override
		public void write(final byte[] b, final int off, final int len) throws IOException {
			for (int i = 0; i < len; ++i) {
				this.write(b[off + i]);
			}
		}
	}

	@Test
	void messageDoesNotLandInsideDataPacket() throws Exception {
		final V2ProtocolProcessor processor = new V2ProtocolProcessor(URI.create("ctip://localhost/"), null);
		final PausingOutput sink = new PausingOutput();
		setField(processor, "out", new DataOutputStream(sink));
		setField(processor, "charset", "UTF-8");

		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final Thread data = new Thread(() -> {
			try {
				final byte[] b = "data".getBytes("UTF-8");
				processor.write(b, 0, b.length);
				processor.finish();
			} catch (final Throwable t) {
				failure.set(t);
			}
		});
		// The abort report comes from the thread reading the body
		final Thread message = new Thread(() -> {
			try {
				sink.dataStarted.await();
				processor.message((short) 0x1001, new String[] { "arg" }, "aborted");
				sink.messageWritten.countDown();
			} catch (final Throwable t) {
				failure.set(t);
			}
		});
		sink.dataThread = data;
		data.start();
		message.start();
		data.join();
		message.join();
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}

		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(sink.bytes.toByteArray()));
		int messageCount = 0, dataCount = 0;
		while (in.available() > 0) {
			final int length = in.readInt();
			assertTrue(length > 0 && length <= in.available(), "frame length " + length);
			final byte type = in.readByte();
			if (type == V2ServerPackets.MESSAGE) {
				assertEquals((short) 0x1001, in.readShort());
				final byte[] text = new byte[in.readShort() & 0xffff];
				in.readFully(text);
				assertEquals("aborted", new String(text, "UTF-8"));
				final byte[] arg = new byte[in.readShort() & 0xffff];
				in.readFully(arg);
				assertEquals("arg", new String(arg, "UTF-8"));
				assertEquals(1 + 2 + 2 + text.length + 2 + arg.length, length, "message frame");
				++messageCount;
			} else if (type == V2ServerPackets.DATA) {
				final byte[] payload = new byte[length - 1];
				in.readFully(payload);
				assertEquals("data", new String(payload, "UTF-8"));
				++dataCount;
			} else {
				throw new AssertionError("unexpected packet type " + type);
			}
		}
		assertEquals(1, messageCount);
		assertEquals(1, dataCount);
	}

	private static void setField(final V2ProtocolProcessor processor, final String name, final Object value)
			throws Exception {
		final Field field = V2ProtocolProcessor.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(processor, value);
	}
}
