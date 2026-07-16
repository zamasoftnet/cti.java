package jp.cssj.server.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileCountLimitExceededException;
import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.junit.jupiter.api.Test;

class RestRequestTest {
	private static final String BOUNDARY = "cti-test-boundary";

	@Test
	void acceptsNormalMultipartRequest() throws Exception {
		MockRequest mock = multipartRequest(multipart(field("rest.id", "session-1")), true);

		RestRequest request = new RestRequest(mock.request, limits(8L));

		assertEquals("session-1", request.getParameter("rest.id"));
	}

	@Test
	void acceptsMultipleFormFields() throws Exception {
		MockRequest mock = multipartRequest(multipart(field("first", "one"), field("second", "two")), true);
		RestRequest request = new RestRequest(mock.request, limits(8L));

		assertEquals("one", request.getParameter("first"));
		assertEquals("two", request.getParameter("second"));
	}

	@Test
	void acceptsNormalFileUpload() throws Exception {
		byte[] content = "file-content".getBytes(StandardCharsets.UTF_8);
		MockRequest mock = multipartRequest(multipart(file("rest.main", "input.html", content)), true);
		RestRequest request = new RestRequest(mock.request, limits(8L));

		assertNull(request.getParameter("missing"));
		assertEquals(RestRequest.FILE, request.getType());
		FileItemStream item = (FileItemStream) request.getItem();
		try (InputStream in = item.openStream()) {
			assertArrayEquals(content, in.readAllBytes());
		}
		request.nextItem();
		assertEquals(RestRequest.NONE, request.getType());
	}

	@Test
	void acceptsPartCountExactlyAtLimit() throws Exception {
		MockRequest mock = multipartRequest(multipart(field("a", "1"), field("b", "2"), field("c", "3")), true);
		RestRequest request = new RestRequest(mock.request, limits(3L));

		assertNull(request.getParameter("missing"));
		assertEquals("1", request.getParameter("a"));
		assertEquals("2", request.getParameter("b"));
		assertEquals("3", request.getParameter("c"));
	}

	@Test
	void rejectsMixedPartCountAtLimitPlusOne() throws Exception {
		MockRequest mock = multipartRequest(
				multipart(field("a", "1"), file("rest.main", "input.html", new byte[] { 1 }), field("c", "3")), true);
		RestRequest request = new RestRequest(mock.request, limits(2L));

		assertNull(request.getParameter("missing"));
		request.nextItem();
		assertEquals(RestRequest.FILE, request.getType());
		FileCountLimitExceededException exception = assertThrows(FileCountLimitExceededException.class,
				request::nextItem);
		assertEquals(RestServlet.MultipartFailure.PART_COUNT_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void rejectsFieldOnlyPartCountAtLimitPlusOne() throws Exception {
		MockRequest mock = multipartRequest(multipart(field("a", "1"), field("b", "2"), field("c", "3")), true);
		RestRequest request = new RestRequest(mock.request, limits(2L));

		FileCountLimitExceededException exception = assertThrows(FileCountLimitExceededException.class,
				() -> request.getParameter("missing"));
		assertEquals(RestServlet.MultipartFailure.PART_COUNT_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void acceptsPartHeaderExactlyAtLimit() throws Exception {
		int headerLimit = 128;
		MockRequest mock = multipartRequest(multipartWithHeaderBytes(headerLimit), true);
		RestRequest request = new RestRequest(mock.request, limits(8L, headerLimit, 4096L, 1024L, 64L));

		assertEquals("ok", request.getParameter("value"));
	}

	@Test
	void rejectsPartHeaderAtLimitPlusOne() throws Exception {
		int headerLimit = 128;
		MockRequest mock = multipartRequest(multipartWithHeaderBytes(headerLimit + 1), true);

		FileUploadBase.SizeLimitExceededException exception = assertThrows(
				FileUploadBase.SizeLimitExceededException.class,
				() -> new RestRequest(mock.request, limits(8L, headerLimit, 4096L, 1024L, 64L)));
		assertEquals(RestServlet.MultipartFailure.PART_HEADERS_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void rejectsSingleFileAtLimitPlusOne() throws Exception {
		byte[] content = repeatedBytes(17);
		MockRequest mock = multipartRequest(multipart(file("rest.main", "input.bin", content)), true);
		RestRequest request = new RestRequest(mock.request, limits(8L, 512, 1024L, 16L, 8L));
		request.getParameter("missing");
		FileItemStream item = (FileItemStream) request.getItem();

		IOException exception;
		try (InputStream in = item.openStream()) {
			exception = assertThrows(IOException.class, in::readAllBytes);
		}
		assertEquals(RestServlet.MultipartFailure.FILE_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void rejectsKnownRequestSizeAtLimitPlusOne() throws Exception {
		byte[] body = multipart(field("value", "ok"));
		MockRequest mock = multipartRequest(body, true);
		long requestLimit = body.length - 1L;

		FileUploadBase.SizeLimitExceededException exception = assertThrows(
				FileUploadBase.SizeLimitExceededException.class,
				() -> new RestRequest(mock.request, limits(8L, 512, requestLimit, 32L, 16L)));
		assertEquals(RestServlet.MultipartFailure.REQUEST_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void rejectsFormFieldAtLimitPlusOne() throws Exception {
		MockRequest mock = multipartRequest(multipart(field("value", "x".repeat(17))), true);
		RestRequest request = new RestRequest(mock.request, limits(8L, 512, 1024L, 64L, 16L));

		RestRequest.FormFieldSizeLimitExceededException exception = assertThrows(
				RestRequest.FormFieldSizeLimitExceededException.class,
				() -> request.getParameter("value"));
		assertEquals(RestServlet.MultipartFailure.FORM_FIELD_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void enforcesRequestLimitWithoutContentLength() throws Exception {
		byte[] body = multipart(field("value", "ok"));
		MockRequest mock = multipartRequest(body, false);

		Exception exception = assertThrows(Exception.class, () -> new RestRequest(mock.request,
				limits(8L, 512, body.length - 1L, 32L, 16L)));
		assertEquals(RestServlet.MultipartFailure.REQUEST_TOO_LARGE,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void rejectsMissingMultipartBoundaryAsMalformed() {
		MockRequest mock = request(new byte[0], "multipart/form-data", true);

		FileUploadException exception = assertThrows(FileUploadException.class,
				() -> new RestRequest(mock.request, limits(8L)));
		assertEquals(RestServlet.MultipartFailure.MALFORMED,
				RestServlet.classifyMultipartFailure(exception));
	}

	@Test
	void doesNotContinueProcessingAfterLimitFailure() throws Exception {
		MockRequest mock = multipartRequest(multipart(field("oversized", "x".repeat(17)), field("later", "ok")), true);
		RestRequest request = new RestRequest(mock.request, limits(8L, 512, 1024L, 64L, 16L));
		AtomicBoolean continued = new AtomicBoolean();

		assertThrows(RestRequest.FormFieldSizeLimitExceededException.class, () -> {
			request.getParameter("later");
			continued.set(true);
		});
		assertFalse(continued.get());
	}

	@Test
	void closesFileStreamWhenLimitReadFails() throws Exception {
		CloseTrackingInputStream delegate = new CloseTrackingInputStream(repeatedBytes(5));
		RestRequest.BoundedFileItemStream item = new RestRequest.BoundedFileItemStream(
				new StubFileItemStream(delegate), 4L);

		try (InputStream in = item.openStream()) {
			assertThrows(RestRequest.FileSizeLimitIOException.class, in::readAllBytes);
		}
		assertTrue(delegate.closed);
	}

	@Test
	void sendsSafeHttpStatusesForLimitAndMalformedFailures() throws Exception {
		MockRequest request = request(new byte[0], "application/octet-stream", true);
		MockResponse limitResponse = new MockResponse();
		FileCountLimitExceededException limit = new FileCountLimitExceededException("secret-detail", 2L);

		RestServlet.sendMultipartFailure(request.request, limitResponse.response, limit);

		assertEquals(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, limitResponse.status);
		assertFalse(limitResponse.body.toString().contains("secret-detail"));

		MockResponse malformedResponse = new MockResponse();
		RestServlet.sendMultipartFailure(request.request, malformedResponse.response,
				new FileUploadException("internal-path"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, malformedResponse.status);
		assertFalse(malformedResponse.body.toString().contains("internal-path"));

		MockResponse ioResponse = new MockResponse();
		RestServlet.sendMultipartFailure(request.request, ioResponse.response,
				new FileUploadBase.IOFileUploadException("internal-io", new IOException("secret-path")));
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ioResponse.status);
		assertFalse(ioResponse.body.toString().contains("secret-path"));
	}

	private static RestRequest.MultipartLimits limits(long maxParts) {
		return limits(maxParts, 512, 4096L, 1024L, 256L);
	}

	private static RestRequest.MultipartLimits limits(long maxParts, int maxHeaderBytes, long maxRequestBytes,
			long maxFileBytes, long maxFieldBytes) {
		return new RestRequest.MultipartLimits(maxParts, maxHeaderBytes, maxRequestBytes, maxFileBytes,
				maxFieldBytes);
	}

	private static Part field(String name, String value) {
		return new Part(name, null, value.getBytes(StandardCharsets.UTF_8));
	}

	private static Part file(String name, String fileName, byte[] value) {
		return new Part(name, fileName, value);
	}

	private static byte[] multipart(Part... parts) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Part part : parts) {
			writeAscii(out, "--" + BOUNDARY + "\r\n");
			String disposition = "Content-Disposition: form-data; name=\"" + part.name + "\"";
			if (part.fileName != null) {
				disposition += "; filename=\"" + part.fileName + "\"";
			}
			writeAscii(out, disposition + "\r\n");
			if (part.fileName != null) {
				writeAscii(out, "Content-Type: application/octet-stream\r\n");
			}
			writeAscii(out, "\r\n");
			out.write(part.data);
			writeAscii(out, "\r\n");
		}
		writeAscii(out, "--" + BOUNDARY + "--\r\n");
		return out.toByteArray();
	}

	private static byte[] multipartWithHeaderBytes(int headerBytes) throws IOException {
		String disposition = "Content-Disposition: form-data; name=\"value\"\r\n";
		String padPrefix = "X-Pad: ";
		String lineEndAndBlankLine = "\r\n\r\n";
		int padLength = headerBytes - disposition.getBytes(StandardCharsets.ISO_8859_1).length
				- padPrefix.length() - lineEndAndBlankLine.length();
		if (padLength < 0) {
			throw new IllegalArgumentException("headerBytes is too small");
		}
		String headers = disposition + padPrefix + "x".repeat(padLength) + lineEndAndBlankLine;
		assertEquals(headerBytes, headers.getBytes(StandardCharsets.ISO_8859_1).length);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeAscii(out, "--" + BOUNDARY + "\r\n");
		writeAscii(out, headers);
		writeAscii(out, "ok\r\n--" + BOUNDARY + "--\r\n");
		return out.toByteArray();
	}

	private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
		out.write(value.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static byte[] repeatedBytes(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < bytes.length; ++i) {
			bytes[i] = (byte) ('a' + i % 26);
		}
		return bytes;
	}

	private static MockRequest multipartRequest(byte[] body, boolean contentLengthPresent) {
		return request(body, "multipart/form-data; boundary=" + BOUNDARY, contentLengthPresent);
	}

	private static MockRequest request(byte[] body, String contentType, boolean contentLengthPresent) {
		TrackingServletInputStream input = new TrackingServletInputStream(body);
		Map<String, Object> attributes = new HashMap<>();
		HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
				RestRequestTest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
				switch (method.getName()) {
				case "getMethod":
					return "POST";
				case "getContentType":
					return contentType;
				case "getContentLength":
					return contentLengthPresent ? body.length : -1;
				case "getContentLengthLong":
					return contentLengthPresent ? (long) body.length : -1L;
				case "getCharacterEncoding":
					return StandardCharsets.UTF_8.name();
				case "getInputStream":
					return input;
				case "getParameterNames":
					return Collections.emptyEnumeration();
				case "getParameter", "getParameterValues":
					return null;
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
		return new MockRequest(request, input);
	}

	private static Object defaultValue(Class<?> type) {
		if (type == void.class) {
			return null;
		}
		if (!type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == float.class) {
			return 0F;
		}
		if (type == double.class) {
			return 0D;
		}
		if (type == char.class) {
			return '\0';
		}
		throw new IllegalArgumentException(type.getName());
	}

	private record Part(String name, String fileName, byte[] data) {
	}

	private record MockRequest(HttpServletRequest request, TrackingServletInputStream input) {
	}

	private static final class TrackingServletInputStream extends ServletInputStream {
		private final ByteArrayInputStream delegate;

		TrackingServletInputStream(byte[] bytes) {
			this.delegate = new ByteArrayInputStream(bytes);
		}

		@Override
		public int read() {
			return this.delegate.read();
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			return this.delegate.read(buffer, offset, length);
		}

		@Override
		public boolean isFinished() {
			return this.delegate.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener listener) {
			// Synchronous test stream.
		}
	}

	private static final class CloseTrackingInputStream extends ByteArrayInputStream {
		boolean closed;

		CloseTrackingInputStream(byte[] bytes) {
			super(bytes);
		}

		@Override
		public void close() throws IOException {
			this.closed = true;
			super.close();
		}
	}

	private static final class StubFileItemStream implements FileItemStream {
		private final InputStream input;

		StubFileItemStream(InputStream input) {
			this.input = input;
		}

		@Override
		public String getContentType() {
			return "application/octet-stream";
		}

		@Override
		public String getFieldName() {
			return "rest.main";
		}

		@Override
		public String getName() {
			return "input.bin";
		}

		@Override
		public boolean isFormField() {
			return false;
		}

		@Override
		public InputStream openStream() {
			return this.input;
		}

		@Override
		public FileItemHeaders getHeaders() {
			return null;
		}

		@Override
		public void setHeaders(FileItemHeaders headers) {
			// unused
		}
	}

	private static final class MockResponse {
		final StringWriter body = new StringWriter();
		int status;
		final HttpServletResponse response;

		MockResponse() {
			PrintWriter writer = new PrintWriter(this.body);
			this.response = (HttpServletResponse) Proxy.newProxyInstance(RestRequestTest.class.getClassLoader(),
					new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
						switch (method.getName()) {
						case "setStatus":
							this.status = (int) args[0];
							return null;
						case "getStatus":
							return this.status;
						case "getWriter":
							return writer;
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
}
