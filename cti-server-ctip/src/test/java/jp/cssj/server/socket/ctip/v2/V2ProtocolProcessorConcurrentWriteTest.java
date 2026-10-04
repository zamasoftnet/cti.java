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
 * 応答のパケットは、複数のスレッドから書かれても混ざらないことを固定します(2026-10-04)。
 *
 * <p>
 * 本文の途中に client の ABORT が届くと、サーバーは本文を読んでいる側のスレッド(copper4 では
 * 入力の先読み)で中断を受け、その場で中断の報告(MESSAGE)を書く。変換のスレッドが同時に
 * データを書いていると、1 つのパケットの途中にもう一方のバイトが入り、client が
 * 「Trailing bytes in CTIP response」で落ちていた(copper4 の ClientAbortTest がゲートで時々赤)。
 * </p>
 */
class V2ProtocolProcessorConcurrentWriteTest {
	/**
	 * データのパケットを長さの 4 バイトまで書いたところで書き手を止め、その間にメッセージを書かせる。
	 * 排他されていればメッセージ側は待たされ、止めた側が時間切れで続きを書く。
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
		// 中断の報告は、本文を読む側のスレッドから届く
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
