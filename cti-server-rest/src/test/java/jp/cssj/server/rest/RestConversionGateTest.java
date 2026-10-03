package jp.cssj.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import jp.cssj.server.ConversionGate;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.SourceResolver;

/**
 * REST の同時変換数の上限と、許可が必ず戻ることを固定します(2026-10-03、共有サービスの資源の上限 増分3)。
 */
class RestConversionGateTest {

	/** 空きが無ければ待たずに断る(0x3003)。同じセッションが変換中なら 0x3017。空いたら通る。 */
	@Test
	void refusesWithoutWaitingAndRecovers() throws Exception {
		final ConversionGate gate = new ConversionGate(1);
		final Engine a = new Engine(true);
		final RestSession sessionA = new RestSession(a, false, false, 60000L, gate);
		assertTrue(sessionA.transcode(async("A"), new MockResponse().response));
		assertTrue(a.entered.await(5, TimeUnit.SECONDS), "変換が始まらなかった");
		assertEquals(1, gate.running());

		final ConversionRefusedException sameSession = assertThrows(ConversionRefusedException.class,
				() -> sessionA.transcode(async("A2"), new MockResponse().response));
		assertEquals(RestServlet.ERROR_SESSION_BUSY, sameSession.getCode());

		final Engine b = new Engine(false);
		final RestSession sessionB = new RestSession(b, false, false, 60000L, gate);
		final ConversionRefusedException busy = assertThrows(ConversionRefusedException.class,
				() -> sessionB.transcode(async("B"), new MockResponse().response));
		assertEquals(CTIMessageCodes.ERROR_BUSY, busy.getCode());
		assertEquals(1L, gate.refused());

		a.release.countDown();
		awaitRunning(gate, 0);
		assertTrue(sessionB.transcode(async("B"), new MockResponse().response));
		awaitRunning(gate, 0);
		assertEquals(1, b.completed.get());
		sessionA.close();
		sessionB.close();
	}

	/** 同期の変換も、終われば許可を返す。 */
	@Test
	void syncReleasesThePermit() throws Exception {
		final ConversionGate gate = new ConversionGate(1);
		final RestSession session = new RestSession(new Engine(false), false, false, 60000L, gate);
		assertTrue(session.transcode(request("S", Map.of()), new MockResponse().response));
		assertEquals(0, gate.running());
		assertTrue(session.transcode(request("S2", Map.of()), new MockResponse().response));
		assertEquals(0, gate.running());
		session.close();
	}

	/**
	 * クライアントの資源を待っている変換(rest.requestResource)も close で閉じられ、許可が戻る。
	 * 以前は resolve が割り込みも無視して待ち続け、close が変換の終わりを待って、どちらも終わらなかった。
	 */
	@Test
	void closeEndsAResourceWait() throws Exception {
		final ConversionGate gate = new ConversionGate(1);
		final Engine engine = new Engine(false);
		engine.resolveFirst = true;
		final RestSession session = new RestSession(engine, false, false, 60000L, gate);
		final Map<String, String> params = new HashMap<>();
		params.put("rest.async", "true");
		params.put("rest.requestResource", "true");
		assertTrue(session.transcode(request("R", params), new MockResponse().response));
		assertTrue(engine.entered.await(5, TimeUnit.SECONDS), "変換が始まらなかった");
		assertTimeoutPreemptively(Duration.ofSeconds(10), session::close);
		assertEquals(0, gate.running());
	}

	/** {@code rest.wait=0} で {@code /messages} が無期限に待たない(以前は wait(0)=無期限)。 */
	@Test
	void messagesDoNotWaitForeverOnZero() throws Exception {
		final Engine engine = new Engine(true);
		final RestSession session = new RestSession(engine, true, false, 60000L, ConversionGate.UNLIMITED);
		assertTrue(session.transcode(async("M"), new MockResponse().response));
		assertTrue(engine.entered.await(5, TimeUnit.SECONDS));
		final HttpServletRequest messages = request("", Map.of("rest.wait", "0"));
		assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> session.messages(messages, new MockResponse().response));
		engine.release.countDown();
		session.close();
	}

	private static void awaitRunning(final ConversionGate gate, final int expected) throws InterruptedException {
		final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (gate.running() != expected && System.nanoTime() < until) {
			Thread.sleep(10);
		}
		assertEquals(expected, gate.running());
	}

	private static HttpServletRequest async(final String body) throws Exception {
		return request(body, Map.of("rest.async", "true"));
	}

	/** 本文を text/html で送る(フォームでない)要求。RestRequest を作って要求に登録する。 */
	private static HttpServletRequest request(final String body, final Map<String, String> params) throws Exception {
		final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
		final ServletInputStream input = new ServletInputStream() {
			public int read() throws IOException {
				return in.read();
			}

			public boolean isFinished() {
				return in.available() == 0;
			}

			public boolean isReady() {
				return true;
			}

			public void setReadListener(final ReadListener listener) {
				// 使わない
			}
		};
		final Map<String, Object> attributes = new HashMap<>();
		final HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
				RestConversionGateTest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
					case "getMethod":
						return "POST";
					case "getContentType":
						return "text/html";
					case "getContentLength":
						return bytes.length;
					case "getContentLengthLong":
						return (long) bytes.length;
					case "getCharacterEncoding":
						return StandardCharsets.UTF_8.name();
					case "getInputStream":
						return input;
					case "getHeader":
						return "Content-Length".equals(args[0]) ? String.valueOf(bytes.length) : null;
					case "getParameter":
						return params.get(args[0]);
					case "getParameterNames":
						return Collections.enumeration(params.keySet());
					case "getParameterValues":
						return params.containsKey(args[0]) ? new String[] { params.get(args[0]) } : null;
					case "getParameterMap": {
						final Map<String, String[]> map = new HashMap<>();
						params.forEach((k, v) -> map.put(k, new String[] { v }));
						return map;
					}
					case "setAttribute":
						attributes.put((String) args[0], args[1]);
						return null;
					case "getAttribute":
						return attributes.get(args[0]);
					case "removeAttribute":
						attributes.remove(args[0]);
						return null;
					case "toString":
						return "MockHttpServletRequest";
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						return defaultValue(method.getReturnType());
					}
				});
		new RestRequest(req);
		return req;
	}

	private static Object defaultValue(final Class<?> type) {
		if (type == boolean.class) {
			return false;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		return null;
	}

	private static final class MockResponse {
		final StringWriter body = new StringWriter();
		final HttpServletResponse response;

		MockResponse() {
			final PrintWriter writer = new PrintWriter(this.body);
			this.response = (HttpServletResponse) Proxy.newProxyInstance(
					RestConversionGateTest.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
						case "getWriter":
							return writer;
						case "getOutputStream":
							return null;
						case "toString":
							return "MockHttpServletResponse";
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						default:
							return defaultValue(method.getReturnType());
						}
					});
		}
	}

	/** 本文を読むだけの変換。{@code hold} なら release まで変換を終えない。 */
	private static final class Engine implements CTISession {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger completed = new AtomicInteger();
		final boolean hold;
		volatile boolean resolveFirst;
		private SourceResolver resolver;

		Engine(final boolean hold) {
			this.hold = hold;
		}

		public void transcode(final Source source) throws IOException, TranscoderException {
			try (InputStream in = source.getInputStream()) {
				in.readAllBytes();
			}
			this.entered.countDown();
			if (this.resolveFirst && this.resolver != null) {
				try {
					this.resolver.resolve(URI.create("missing.css"));
				} catch (final FileNotFoundException e) {
					// close で待ちを抜けた
				}
			}
			if (this.hold) {
				try {
					this.release.await(30, TimeUnit.SECONDS);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			this.completed.incrementAndGet();
		}

		public void setSourceResolver(final SourceResolver resolver) {
			this.resolver = resolver;
		}

		public void abort(final byte mode) {
			this.release.countDown();
		}

		public void reset() {
			// 使わない
		}

		public void close() {
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
	}
}
