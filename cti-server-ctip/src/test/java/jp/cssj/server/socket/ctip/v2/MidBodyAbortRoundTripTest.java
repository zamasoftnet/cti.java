package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.cssj.cti2.CTIDriver;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.NopResults;
import jp.cssj.cti2.results.Results;
import jp.cssj.driver.ctip.CTIPDriver;
import jp.cssj.driver.ctip.v2.V2Session;
import jp.cssj.server.socket.CTIServer;
import jp.cssj.server.socket.ProtocolHandler;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

/**
 * Exercises terminal responses when conversion ends midway through the body, with round trips between a real
 * CTIP server and the Java driver (2026-09-28).
 *
 * <p>
 * Even if conversion ends midway through the body, the server drains input until the client finishes sending
 * (EOF) before returning ABORT. Previously it returned ABORT immediately, causing drivers expecting a terminal
 * response after EOF to wait indefinitely (Python, PHP, Perl) or discard the connection (Java). It does not send
 * ABORT to a client that resets without sending EOF (an unread ABORT would remain at the start of the next
 * conversion's response).
 * </p>
 */
class MidBodyAbortRoundTripTest {
	private static final byte[] CHUNK = new byte[V2Session.BUFFER_SIZE * 4];
	static {
		Arrays.fill(CHUNK, (byte) 'a');
	}

	private Engine engine;
	private CTIServer server;
	private CTISession session;

	@BeforeEach
	void start() throws IOException {
		this.engine = new Engine();
		final int port = freePort();
		this.server = new CTIServer();
		final Properties props = new Properties();
		props.setProperty("jp.cssj.cssjd.port", String.valueOf(port));
		props.setProperty("jp.cssj.cssjd.maxThreads", "2");
		props.setProperty("jp.cssj.cssjd.timeout", "5");
		this.server.setConfigFile(new File("."), props);
		this.server.setProtocolHandlers(
				new ProtocolHandler[] { new V2ProtocolHandler(URI.create("ctip://fake/"), this.engine) });
		this.server.startup();
		this.session = new CTIPDriver().getSession(URI.create("ctip://127.0.0.1:" + port + "/"),
				new HashMap<String, String>());
	}

	@AfterEach
	void stop() throws IOException {
		try {
			// Even after an abort, closing does not throw the abort exception again
			this.session.close();
		} finally {
			this.server.shutdown();
		}
	}

	@Test
	void clientAbortIsReportedOnceAfterTheBody() throws Exception {
		this.session.setResults(NopResults.SHARED_INSTANCE);
		final OutputStream out = this.session.transcode(meta());
		out.write(CHUNK);
		out.flush();
		this.session.abort(CTISession.ABORT_FORCE);
		this.sendRestAfterTheEngineStopped(out);

		final TranscoderException reported = assertThrows(TranscoderException.class, out::close);
		assertEquals(CTIMessageCodes.INFO_ABORT, reported.getCode());
		this.assertTheSessionGoesOn();
	}

	@Test
	void engineFailureMidBodyIsReportedAfterTheBody() throws Exception {
		this.engine.failAfter = CHUNK.length / 2;
		this.session.setResults(NopResults.SHARED_INSTANCE);
		final OutputStream out = this.session.transcode(meta());
		out.write(CHUNK);
		out.flush();
		this.sendRestAfterTheEngineStopped(out);

		final TranscoderException reported = assertThrows(TranscoderException.class, out::close);
		assertEquals(CTIMessageCodes.ERROR_IO, reported.getCode());
		this.engine.failAfter = -1;
		this.assertTheSessionGoesOn();
	}

	@Test
	void resetWithoutEofLeavesNoAbortForTheNextConversion() throws Exception {
		this.session.setResults(NopResults.SHARED_INSTANCE);
		final OutputStream out = this.session.transcode(meta());
		out.write(CHUNK);
		out.flush();
		this.session.abort(CTISession.ABORT_FORCE);
		assertTrue(this.engine.stopped.await(5, TimeUnit.SECONDS), "サーバーの変換が止まらなかった");
		Thread.sleep(200);
		// Reset without closing the body (without sending EOF)
		this.assertTheSessionGoesOn();
	}

	/** Even after conversion stops, the client can finish sending the body (no terminal response arrives meanwhile). */
	private void sendRestAfterTheEngineStopped(final OutputStream out) throws Exception {
		assertTrue(this.engine.stopped.await(5, TimeUnit.SECONDS), "サーバーの変換が止まらなかった");
		// If ABORT were returned early, it would reach the client's receiver during this time
		Thread.sleep(200);
		for (int i = 0; i < 8; ++i) {
			out.write(CHUNK);
		}
	}

	/**
	 * Reset on the same connection and complete the next conversion (no ABORT from the previous conversion
	 * remains at the start).
	 */
	private void assertTheSessionGoesOn() throws Exception {
		this.session.reset();
		this.session.setResults(NopResults.SHARED_INSTANCE);
		try (OutputStream next = this.session.transcode(meta())) {
			next.write("after".getBytes("UTF-8"));
		}
		assertEquals(1, this.engine.completed.get(), "次の変換が最後まで通らなかった");
		assertEquals(1, this.engine.connections.get(), "同じ接続で続けられなかった");
	}

	private static SourceMetadata meta() {
		return new SimpleSourceMetadata(URI.create("."), "text/plain", "UTF-8", -1L);
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}

	/**
	 * A conversion engine that only reads the body. If aborted midway through the body, it throws an abort
	 * exception like the real engine. After reading {@link #failAfter} bytes, it throws an I/O failure
	 * (without reading the rest).
	 */
	private static final class Engine implements CTIDriver {
		final CountDownLatch stopped = new CountDownLatch(1);
		final AtomicInteger completed = new AtomicInteger();
		final AtomicInteger connections = new AtomicInteger();
		volatile long failAfter = -1;

		public boolean match(final URI uri) {
			return true;
		}

		public CTISession getSession(final URI uri, final Map<String, String> props) {
			this.connections.incrementAndGet();
			return new Session();
		}

		private final class Session implements CTISession {
			private volatile boolean abortRequested;

			public void transcode(final Source source) throws IOException, TranscoderException {
				long read = 0;
				try (InputStream in = source.getInputStream()) {
					final byte[] buff = new byte[8192];
					for (int len = in.read(buff); len != -1; len = in.read(buff)) {
						read += len;
						if (Engine.this.failAfter >= 0 && read >= Engine.this.failAfter) {
							Engine.this.stopped.countDown();
							throw new TranscoderException(TranscoderException.STATE_BROKEN, CTIMessageCodes.ERROR_IO,
									new String[] { "boom" }, "I/O error. boom");
						}
					}
				}
				if (this.abortRequested) {
					Engine.this.stopped.countDown();
					throw new TranscoderException(TranscoderException.STATE_BROKEN, CTIMessageCodes.INFO_ABORT, null,
							"Aborted.");
				}
				Engine.this.completed.incrementAndGet();
			}

			public void abort(final byte mode) {
				this.abortRequested = true;
			}

			public void reset() {
				this.abortRequested = false;
			}

			public InputStream getServerInfo(final URI uri) {
				return null;
			}

			public void setResults(final Results results) {
				// No output
			}

			public void setMessageHandler(final MessageHandler messageHandler) {
				// No notifications
			}

			public void setProgressListener(final ProgressListener progressListener) {
				// No progress updates
			}

			public void property(final String name, final String value) {
				// Not used
			}

			public OutputStream resource(final SourceMetadata metaSource) {
				throw new UnsupportedOperationException();
			}

			public void resource(final Source source) {
				throw new UnsupportedOperationException();
			}

			public void setSourceResolver(final SourceResolver resolver) {
				// Not used
			}

			public OutputStream transcode(final SourceMetadata metaSource) {
				throw new UnsupportedOperationException();
			}

			public void transcode(final URI uri) {
				throw new UnsupportedOperationException();
			}

			public void setContinuous(final boolean continuous) {
				// Not used
			}

			public void join() {
				// Not used
			}

			public void close() {
				// Holds no resources
			}
		}
	}
}
