package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import jp.cssj.server.ConversionGate;
import jp.cssj.server.socket.CTIServer;
import jp.cssj.server.socket.ProtocolHandler;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

/**
 * CTIP の同時変換数の上限を、本物の CTIP サーバーと Java ドライバで往復させます
 * (2026-10-03、共有サービスの資源の上限 増分3)。空きが無ければ待たずに中断(0x3003)を返し、
 * 同じ接続はそのまま次の変換に使え、許可は変換の終わりに戻ります。
 */
class ConversionGateRoundTripTest {
	private Engine engine;
	private ConversionGate gate;
	private CTIServer server;
	private int port;

	@BeforeEach
	void start() throws IOException {
		this.engine = new Engine();
		this.gate = new ConversionGate(1);
		this.port = freePort();
		this.server = new CTIServer();
		final Properties props = new Properties();
		props.setProperty("jp.cssj.cssjd.port", String.valueOf(this.port));
		props.setProperty("jp.cssj.cssjd.maxThreads", "4");
		props.setProperty("jp.cssj.cssjd.timeout", "5");
		this.server.setConfigFile(new File("."), props);
		final V2ProtocolHandler handler = new V2ProtocolHandler(URI.create("ctip://fake/"), this.engine);
		handler.setConversionGate(this.gate);
		this.server.setProtocolHandlers(new ProtocolHandler[] { handler });
		this.server.startup();
	}

	@AfterEach
	void stop() {
		this.engine.release.countDown();
		this.server.shutdown();
	}

	@Test
	void secondConversionIsRefusedWithoutWaiting() throws Exception {
		final CTISession first = this.session();
		final CTISession second = this.session();
		try {
			// 1 本目は変換の中で止めておく(許可を持ったまま)
			final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
			final Thread firstThread = new Thread(() -> {
				try {
					first.setResults(NopResults.SHARED_INSTANCE);
					try (OutputStream out = first.transcode(meta())) {
						out.write("first".getBytes("UTF-8"));
					}
				} catch (final Throwable t) {
					firstFailure.set(t);
				}
			});
			firstThread.start();
			assertTrue(this.engine.entered.await(5, TimeUnit.SECONDS), "1 本目が始まらなかった");
			assertEquals(1, this.gate.running());

			// 2 本目は待たずに断られる
			second.setResults(NopResults.SHARED_INSTANCE);
			final OutputStream out = second.transcode(meta());
			out.write("second".getBytes("UTF-8"));
			final TranscoderException refused = assertThrows(TranscoderException.class, out::close);
			assertEquals(CTIMessageCodes.ERROR_BUSY, refused.getCode());
			assertEquals(1L, this.gate.refused());

			// 1 本目を終わらせると許可が戻り、断られた接続のまま次の変換が通る
			this.engine.release.countDown();
			firstThread.join(5000);
			assertNull(firstFailure.get());
			awaitRunning(this.gate, 0);
			second.reset();
			second.setResults(NopResults.SHARED_INSTANCE);
			try (OutputStream next = second.transcode(meta())) {
				next.write("again".getBytes("UTF-8"));
			}
			assertEquals(2, this.engine.completed.get());
			awaitRunning(this.gate, 0);
		} finally {
			first.close();
			second.close();
		}
	}

	@Test
	void permitIsReturnedWhenTheConnectionDrops() throws Exception {
		final CTISession first = this.session();
		final Thread firstThread = new Thread(() -> {
			try {
				first.setResults(NopResults.SHARED_INSTANCE);
				try (OutputStream out = first.transcode(meta())) {
					out.write("first".getBytes("UTF-8"));
				}
			} catch (final Throwable t) {
				// 試験では見ない
			}
		});
		firstThread.start();
		assertTrue(this.engine.entered.await(5, TimeUnit.SECONDS));
		this.engine.release.countDown();
		firstThread.join(5000);
		first.close();
		awaitRunning(this.gate, 0);
	}

	private CTISession session() throws IOException {
		return new CTIPDriver().getSession(URI.create("ctip://127.0.0.1:" + this.port + "/"),
				new HashMap<String, String>());
	}

	private static void awaitRunning(final ConversionGate gate, final int expected) throws InterruptedException {
		final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (gate.running() != expected && System.nanoTime() < until) {
			Thread.sleep(10);
		}
		assertEquals(expected, gate.running());
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

	/** 本文を読むだけの変換エンジン。最初の変換は release まで終えない。 */
	private static final class Engine implements CTIDriver {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger completed = new AtomicInteger();
		final AtomicInteger started = new AtomicInteger();

		public boolean match(final URI uri) {
			return true;
		}

		public CTISession getSession(final URI uri, final Map<String, String> props) {
			return new Session();
		}

		private final class Session implements CTISession {
			public void transcode(final Source source) throws IOException, TranscoderException {
				try (InputStream in = source.getInputStream()) {
					in.readAllBytes();
				}
				if (Engine.this.started.incrementAndGet() == 1) {
					Engine.this.entered.countDown();
					try {
						Engine.this.release.await(30, TimeUnit.SECONDS);
					} catch (final InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				Engine.this.completed.incrementAndGet();
			}

			public void abort(final byte mode) {
				// 使わない
			}

			public void reset() {
				// 使わない
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
