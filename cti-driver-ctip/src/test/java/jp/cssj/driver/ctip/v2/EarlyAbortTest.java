package jp.cssj.driver.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.results.NopResults;
import jp.cssj.driver.ctip.CTIPDriver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

/**
 * Handling a server ABORT received while sending the body (2026-09-28).
 *
 * <p>
 * When read-ahead during sending ({@code V2RequestConsumer.sendUntil}'s {@code pollResponse}) read an ABORT,
 * it stored the {@code TranscoderException} (a subclass of {@code IOException}) as a send failure and closed
 * the connection. Although the abort was reported once, subsequent {@code close()} and {@code reset()} calls
 * threw the same exception again, and the same session could not perform another conversion.
 * ABORT is a complete response received in full, so it is not a send failure.
 * </p>
 *
 * <p>
 * The peer is a scripted fake server: it returns ABORT as soon as it receives the client's ABORT
 * (while the body is still being sent), discards subsequent DATA and EOF, and returns EOF for the next body.
 * </p>
 */
class EarlyAbortTest {
	@Test
	void abortReadWhileSendingIsReportedOnceAndTheSessionGoesOn() throws Exception {
		final byte[] chunk = new byte[V2Session.BUFFER_SIZE * 4];
		Arrays.fill(chunk, (byte) 'a');
		try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			final ScriptedServer server = new ScriptedServer(listener);
			server.start();
			try (CTISession session = new CTIPDriver().getSession(
					URI.create("ctip://127.0.0.1:" + listener.getLocalPort() + "/"), new HashMap<String, String>())) {
				session.setResults(NopResults.SHARED_INSTANCE);
				final OutputStream out = session.transcode(new SimpleSourceMetadata(URI.create("."), "text/plain",
						"UTF-8", -1L));
				TranscoderException reported = null;
				try {
					out.write(chunk);
					out.flush();
					session.abort(CTISession.ABORT_FORCE);
					assertTrue(server.abortSent.await(5, TimeUnit.SECONDS), "偽サーバーが ABORT を返さなかった");
					Thread.sleep(200);
					for (int i = 0; i < 8 && reported == null; ++i) {
						try {
							out.write(chunk);
						} catch (final TranscoderException e) {
							reported = e;
						}
					}
				} finally {
					// Do not throw an already reported abort again.
					out.close();
				}
				assertNotNull(reported, "送信中に ABORT を読まなかった(試験の前提が崩れている)");
				assertEquals(CTIMessageCodes.INFO_ABORT, reported.getCode());

				session.reset();
				session.setResults(NopResults.SHARED_INSTANCE);
				try (OutputStream next = session.transcode(new SimpleSourceMetadata(URI.create("."), "text/plain",
						"UTF-8", -1L))) {
					next.write("after".getBytes(StandardCharsets.UTF_8));
				}
			}
			server.join(5000);
			assertEquals(1, server.completed, "中断のあとの変換が同じ接続で通らなかった");
			assertTrue(server.closed, "CLOSE が届かなかった");
		}
	}

	private static final class ScriptedServer extends Thread {
		private final ServerSocket listener;
		final CountDownLatch abortSent = new CountDownLatch(1);
		volatile int completed = 0;
		volatile boolean closed = false;

		ScriptedServer(final ServerSocket listener) {
			this.listener = listener;
			this.setDaemon(true);
		}

		@Override
		public void run() {
			try (Socket socket = this.listener.accept()) {
				socket.setSoTimeout(10000);
				final InputStream rawIn = socket.getInputStream();
				final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
				readLine(rawIn); // CTIP/2.0 UTF-8
				readLine(rawIn); // PLAIN: user password
				out.write("OK \n".getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
				final DataInputStream in = new DataInputStream(rawIn);
				boolean aborting = false;
				for (;;) {
					final int len = in.readInt();
					final byte type = in.readByte();
					in.readFully(new byte[len - 1]);
					if (type == V2ClientPackets.ABORT) {
						// Return ABORT immediately, while the body is still being sent.
						final byte[] message = "Aborted.".getBytes(StandardCharsets.UTF_8);
						out.writeInt(1 + 1 + 2 + 2 + message.length);
						out.writeByte(V2ServerPackets.ABORT);
						out.writeByte(1);
						out.writeShort(CTIMessageCodes.INFO_ABORT);
						out.writeShort(message.length);
						out.write(message);
						out.flush();
						aborting = true;
						this.abortSent.countDown();
					} else if (type == V2ClientPackets.EOF) {
						if (!aborting) {
							out.writeInt(1);
							out.writeByte(V2ServerPackets.EOF);
							out.flush();
							++this.completed;
						}
						aborting = false;
					} else if (type == V2ClientPackets.RESET) {
						aborting = false;
					} else if (type == V2ClientPackets.CLOSE) {
						this.closed = true;
						return;
					}
				}
			} catch (final IOException e) {
				// Leave the outcome to the test's assertions.
			}
		}

		private static String readLine(final InputStream in) throws IOException {
			final ByteArrayOutputStream line = new ByteArrayOutputStream();
			for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
				line.write(b);
			}
			return line.toString("ISO-8859-1");
		}
	}
}
