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
 * 本文の途中で変換が終わったときの終端の応答を、本物の CTIP サーバーと Java ドライバで往復させる(2026-09-28)。
 *
 * <p>
 * サーバーは本文の途中で終わっても、client が残りを送り終える(EOF)まで読み捨ててから ABORT を返す。
 * 以前はすぐに返していたので、「EOF の後に終端が来る」前提のドライバが待ち続けたり(Python・PHP・Perl)、
 * 接続を捨てたり(Java)していた。EOF を送らずに reset した client には ABORT を送らない
 * (読まれない ABORT が次の変換の応答の頭に残るため)。
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
			// 中断のあとでも、閉じるときに中断をもう一度投げない
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
		// 本文を閉じずに(EOF を送らずに)reset する
		this.assertTheSessionGoesOn();
	}

	/** 変換が止まった後も、client は残りの本文を送り終えられる(その間に終端は届かない)。 */
	private void sendRestAfterTheEngineStopped(final OutputStream out) throws Exception {
		assertTrue(this.engine.stopped.await(5, TimeUnit.SECONDS), "サーバーの変換が止まらなかった");
		// 先に ABORT を返していたら、この間に client の受信側へ届いている
		Thread.sleep(200);
		for (int i = 0; i < 8; ++i) {
			out.write(CHUNK);
		}
	}

	/** 同じ接続のまま reset して、次の変換が最後まで通る(前の変換の ABORT が頭に残っていない)。 */
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
	 * 本文を読むだけの変換エンジン。本文の途中で中断されたら実エンジンと同じく中断を投げ、
	 * {@link #failAfter} バイト読んだら入出力の失敗を投げる(残りは読まない)。
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
				// 出力はしない
			}

			public void setMessageHandler(final MessageHandler messageHandler) {
				// 通知はしない
			}

			public void setProgressListener(final ProgressListener progressListener) {
				// 進捗は出さない
			}

			public void property(final String name, final String value) {
				// 使わない
			}

			public OutputStream resource(final SourceMetadata metaSource) {
				throw new UnsupportedOperationException();
			}

			public void resource(final Source source) {
				throw new UnsupportedOperationException();
			}

			public void setSourceResolver(final SourceResolver resolver) {
				// 使わない
			}

			public OutputStream transcode(final SourceMetadata metaSource) {
				throw new UnsupportedOperationException();
			}

			public void transcode(final URI uri) {
				throw new UnsupportedOperationException();
			}

			public void setContinuous(final boolean continuous) {
				// 使わない
			}

			public void join() {
				// 使わない
			}

			public void close() {
				// 何も持たない
			}
		}
	}
}
