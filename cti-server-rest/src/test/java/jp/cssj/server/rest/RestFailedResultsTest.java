package jp.cssj.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import jp.cssj.server.ConversionGate;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

/**
 * Verifies how results are handled when asynchronous conversion fails
 * (2026-10-04, TECH-20261003-004, item ⑦).
 *
 * <p>
 * When image output exceeds {@code output.page-limit}, the engine outputs results for completed pages,
 * then reports 3805 and aborts. Previously, failure deleted all results, so {@code /result} returned 404
 * even for pages already announced by {@code /messages}. Keep results closed before the error notification
 * and discard those closed afterward (partial output closed during the abort).
 * </p>
 */
class RestFailedResultsTest {

	@Test
	void resultsBeforeTheErrorSurviveAFailedConversion() throws Exception {
		final FailingEngine engine = new FailingEngine();
		final RestSession session = new RestSession(engine, true, false, 60000L, ConversionGate.UNLIMITED);
		assertTrue(session.transcode(request("X", Map.of("rest.async", "true")), new MockResponse().response));
		assertTrue(engine.done.await(10, TimeUnit.SECONDS), "変換が終わらない");
		// Wait for the conversion thread to finish cleanup
		Thread.sleep(200);

		final MockResponse first = new MockResponse();
		session.result(request("", Map.of("rest.uri", "#1")), first.response);
		assertEquals(200, first.status, "通知済みの結果 #1 が取れない");
		assertEquals("page-1", first.bytes.toString(StandardCharsets.ISO_8859_1));

		final MockResponse second = new MockResponse();
		session.result(request("", Map.of("rest.uri", "#2")), second.response);
		assertEquals(404, second.status, "中断で閉じられた途中までの結果 #2 が返った");
		session.close();
	}

	@Test
	void failureWithoutAnErrorMessageDiscardsEverything() throws Exception {
		final FailingEngine engine = new FailingEngine();
		engine.notifyError = false;
		final RestSession session = new RestSession(engine, true, false, 60000L, ConversionGate.UNLIMITED);
		assertTrue(session.transcode(request("X", Map.of("rest.async", "true")), new MockResponse().response));
		assertTrue(engine.done.await(10, TimeUnit.SECONDS), "変換が終わらない");
		Thread.sleep(200);

		final MockResponse first = new MockResponse();
		session.result(request("", Map.of("rest.uri", "#1")), first.response);
		assertEquals(404, first.status, "どれが完成品か分からない失敗なのに結果が残った");
		session.close();
	}

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
				// Unused
			}
		};
		final Map<String, Object> attributes = new HashMap<>();
		final HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
				RestFailedResultsTest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class },
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
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int status = 200;
		final HttpServletResponse response;

		MockResponse() {
			final PrintWriter writer = new PrintWriter(this.body);
			final ServletOutputStream output = new ServletOutputStream() {
				public void write(final int b) {
					MockResponse.this.bytes.write(b);
				}

				public boolean isReady() {
					return true;
				}

				public void setWriteListener(final WriteListener listener) {
					// Unused
				}
			};
			this.response = (HttpServletResponse) Proxy.newProxyInstance(
					RestFailedResultsTest.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
						case "getWriter":
							return writer;
						case "getOutputStream":
							return output;
						case "setStatus":
						case "sendError":
							this.status = (Integer) args[0];
							return null;
						case "getStatus":
							return this.status;
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

	/** A conversion that outputs result #1, reports an error, closes partial result #2, and fails. */
	private static final class FailingEngine implements CTISession {
		final CountDownLatch done = new CountDownLatch(1);
		volatile boolean notifyError = true;
		private Results results;
		private MessageHandler messageHandler;

		public void transcode(final Source source) throws IOException, TranscoderException {
			try (InputStream in = source.getInputStream()) {
				in.readAllBytes();
			}
			try {
				this.result("#1", "page-1");
				if (this.notifyError) {
					this.messageHandler.message((short) 0x3805, new String[] { "8" }, "Page count exceeding limit 8.");
				}
				this.result("#2", "partial");
				throw new TranscoderException(TranscoderException.STATE_BROKEN, (short) 0x3805, new String[] { "8" },
						"Page count exceeding limit 8.");
			} finally {
				this.done.countDown();
			}
		}

		private void result(final String uri, final String content) throws IOException {
			final SourceMetadata meta = new SimpleSourceMetadata(URI.create(uri), "image/png", null, -1L);
			final FragmentedOutput out = this.results.nextBuilder(meta);
			out.addFragment();
			final byte[] b = content.getBytes(StandardCharsets.ISO_8859_1);
			out.write(0, b, 0, b.length);
			out.finishFragment(0);
			out.close();
		}

		public void setResults(final Results results) {
			this.results = results;
		}

		public void setMessageHandler(final MessageHandler messageHandler) {
			this.messageHandler = messageHandler;
		}

		public void setSourceResolver(final SourceResolver resolver) {
			// Unused
		}

		public void abort(final byte mode) {
			// Unused
		}

		public void reset() {
			// Unused
		}

		public void close() {
			// Unused
		}

		public InputStream getServerInfo(final URI uri) {
			return null;
		}

		public void setProgressListener(final ProgressListener progressListener) {
			// Reports no progress
		}

		public void property(final String name, final String value) {
			// Unused
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
			// Unused
		}

		public void join() {
			// Unused
		}
	}
}
