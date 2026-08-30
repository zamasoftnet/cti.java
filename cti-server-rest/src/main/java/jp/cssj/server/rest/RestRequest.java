package jp.cssj.server.rest;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileCountLimitExceededException;
import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

public class RestRequest {
	static final String MAX_PARTS_PROPERTY = "jp.cssj.server.rest.multipart.maxParts";
	static final String MAX_PART_HEADER_BYTES_PROPERTY = "jp.cssj.server.rest.multipart.maxPartHeaderBytes";
	static final String MAX_REQUEST_BYTES_PROPERTY = "jp.cssj.server.rest.multipart.maxRequestBytes";
	static final String MAX_FILE_BYTES_PROPERTY = "jp.cssj.server.rest.multipart.maxFileBytes";
	static final String MAX_FORM_FIELD_BYTES_PROPERTY = "jp.cssj.server.rest.multipart.maxFormFieldBytes";

	static final long DEFAULT_MAX_MULTIPART_PARTS = 128L;
	static final int DEFAULT_MAX_PART_HEADER_BYTES = 512;
	static final long DEFAULT_MAX_REQUEST_BYTES = 512L * 1024L * 1024L;
	static final long DEFAULT_MAX_FILE_BYTES = 256L * 1024L * 1024L;
	static final long DEFAULT_MAX_FORM_FIELD_BYTES = 1024L * 1024L;
	private static final int MAX_CONFIGURABLE_PART_HEADER_BYTES = 8 * 1024;
	private static final int READ_BUFFER_SIZE = 8 * 1024;

	static final MultipartLimits DEFAULT_MULTIPART_LIMITS = new MultipartLimits(
			positiveLongProperty(MAX_PARTS_PROPERTY, DEFAULT_MAX_MULTIPART_PARTS),
			positiveIntProperty(MAX_PART_HEADER_BYTES_PROPERTY, DEFAULT_MAX_PART_HEADER_BYTES),
			positiveLongProperty(MAX_REQUEST_BYTES_PROPERTY, DEFAULT_MAX_REQUEST_BYTES),
			positiveLongProperty(MAX_FILE_BYTES_PROPERTY, DEFAULT_MAX_FILE_BYTES),
			positiveLongProperty(MAX_FORM_FIELD_BYTES_PROPERTY, DEFAULT_MAX_FORM_FIELD_BYTES));

	static final class MultipartLimits {
		final long maxParts;
		final int maxPartHeaderBytes;
		final long maxRequestBytes;
		final long maxFileBytes;
		final long maxFormFieldBytes;

		MultipartLimits(long maxParts, int maxPartHeaderBytes, long maxRequestBytes, long maxFileBytes,
				long maxFormFieldBytes) {
			if (maxParts <= 0L) {
				throw new IllegalArgumentException("maxParts must be positive");
			}
			if (maxPartHeaderBytes <= 0 || maxPartHeaderBytes > MAX_CONFIGURABLE_PART_HEADER_BYTES) {
				throw new IllegalArgumentException("maxPartHeaderBytes must be between 1 and 8192");
			}
			if (maxRequestBytes <= 0L || maxFileBytes <= 0L || maxFormFieldBytes <= 0L) {
				throw new IllegalArgumentException("multipart byte limits must be positive");
			}
			if (maxRequestBytes <= maxFileBytes) {
				throw new IllegalArgumentException("maxRequestBytes must be greater than maxFileBytes");
			}
			if (maxFormFieldBytes >= maxFileBytes) {
				throw new IllegalArgumentException("maxFormFieldBytes must be smaller than maxFileBytes");
			}
			this.maxParts = maxParts;
			this.maxPartHeaderBytes = maxPartHeaderBytes;
			this.maxRequestBytes = maxRequestBytes;
			this.maxFileBytes = maxFileBytes;
			this.maxFormFieldBytes = maxFormFieldBytes;
		}
	}

	static final class FormFieldSizeLimitExceededException extends FileUploadException {
		private static final long serialVersionUID = 1L;

		FormFieldSizeLimitExceededException() {
			super("Multipart form field size limit exceeded");
		}
	}

	static final class FileSizeLimitIOException extends IOException {
		private static final long serialVersionUID = 1L;

		FileSizeLimitIOException() {
			super("Multipart file size limit exceeded");
		}
	}

	// クエリ文字列、または読み込み済みのフィールド
	public static class FormField {
		public final String name, value;
		public final byte[] data;

		public FormField(String name, String value, byte[] data) {
			this.name = name;
			this.value = value;
			this.data = data;
		}

		public String toString() {
			return "" + this.name + ";length=" + this.value.length();
		}
	}

	public final HttpServletRequest req;
	public final FileItemIterator iter;
	private final MultipartLimits multipartLimits;
	private long multipartPartCount = 0L;
	private Object nextItem = null;
	private byte nextType = NONE;

	// 読み込み済みの値
	private Map<String, String> nameToValue = null;
	// クエリ文字列、読み込み済みのフィールドのリスト
	private List<FormField> fields = new ArrayList<FormField>();

	public static final byte NONE = 0;
	public static final byte FIELD = 1;
	public static final byte FILE = 2;
	private int pos = 0;

	private static long positiveLongProperty(String name, long defaultValue) {
		String value = System.getProperty(name);
		if (value == null) {
			return defaultValue;
		}
		try {
			long parsed = Long.parseLong(value);
			if (parsed > 0L) {
				return parsed;
			}
		} catch (NumberFormatException e) {
			// handled below
		}
		throw new IllegalArgumentException(name + " must be a positive integer");
	}

	private static int positiveIntProperty(String name, int defaultValue) {
		long value = positiveLongProperty(name, defaultValue);
		if (value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(name + " is too large");
		}
		return (int) value;
	}

	public static final RestRequest getRestRequest(HttpServletRequest req) {
		RestRequest restReq = (RestRequest) req.getAttribute(RestRequest.class.getName());
		return restReq;
	}

	public RestRequest(HttpServletRequest req) throws IOException, FileUploadException {
		this(req, DEFAULT_MULTIPART_LIMITS);
	}

	RestRequest(HttpServletRequest req, MultipartLimits multipartLimits) throws IOException, FileUploadException {
		this.req = req;
		this.multipartLimits = multipartLimits;
		if (ServletFileUpload.isMultipartContent(req)) {
			ServletFileUpload upload = createServletFileUpload(multipartLimits);
			this.iter = upload.getItemIterator(req);
		} else {
			this.iter = null;
		}
		req.setAttribute(RestRequest.class.getName(), this);
		for (@SuppressWarnings("unchecked")
		Enumeration<String> i = req.getParameterNames(); i.hasMoreElements();) {
			String name = i.nextElement();
			if (name.startsWith("rest.")) {
				this.fields.add(new FormField(name, req.getParameter(name), null));
			} else {
				String[] values = req.getParameterValues(name);
				for (int j = 0; j < values.length; ++j) {
					this.fields.add(new FormField(name, values[j], null));
				}
			}
		}
	}

	static ServletFileUpload createServletFileUpload(MultipartLimits limits) {
		ServletFileUpload upload = new ServletFileUpload();
		upload.setFileCountMax(limits.maxParts);
		upload.setPartHeaderSizeMax(limits.maxPartHeaderBytes);
		upload.setSizeMax(limits.maxRequestBytes);
		upload.setFileSizeMax(limits.maxFileBytes);
		return upload;
	}

	private FileItemStream nextMultipartItem() throws IOException, FileUploadException {
		if (this.iter == null || !this.iter.hasNext()) {
			return null;
		}
		if (this.multipartPartCount >= this.multipartLimits.maxParts) {
			throw new FileCountLimitExceededException("multipart parts", this.multipartLimits.maxParts);
		}
		++this.multipartPartCount;
		FileItemStream item = this.iter.next();
		if (item.isFormField()) {
			return item;
		}
		return new BoundedFileItemStream(item, this.multipartLimits.maxFileBytes);
	}

	private FormField toFormField(FileItemStream item) throws IOException, FileUploadException {
		String charset = this.req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}
		FormField field;
		try (InputStream in = item.openStream()) {
			byte[] data = readFormField(in, this.multipartLimits.maxFormFieldBytes);
			String value = new String(data, charset);
			field = new FormField(item.getFieldName(), value, data);
		}
		return field;
	}

	private static byte[] readFormField(InputStream in, long limit) throws IOException, FileUploadException {
		int initialSize = (int) Math.min(READ_BUFFER_SIZE, limit);
		ByteArrayOutputStream out = new ByteArrayOutputStream(initialSize);
		byte[] buffer = new byte[READ_BUFFER_SIZE];
		long count = 0L;
		for (;;) {
			long remaining = limit - count;
			if (remaining == 0L) {
				if (in.read() != -1) {
					throw new FormFieldSizeLimitExceededException();
				}
				break;
			}
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read == -1) {
				break;
			}
			out.write(buffer, 0, read);
			count += read;
		}
		return out.toByteArray();
	}

	public byte getType() throws IOException, FileUploadException {
		if (this.pos < this.fields.size()) {
			return FIELD;
		}
		if (this.nextItem != null) {
			return this.nextType;
		}
		return NONE;
	}

	public Object getItem() throws IOException, FileUploadException {
		if (this.pos < this.fields.size()) {
			FormField field = this.fields.get(this.pos);
			return field;
		}
		return this.nextItem;
	}

	public void nextItem() throws IOException, FileUploadException {
		if (this.pos < this.fields.size()) {
			++this.pos;
			if (this.pos < this.fields.size()) {
				return;
			}
			if (this.nextItem != null) {
				return;
			}
		}
		FileItemStream item = this.nextMultipartItem();
		if (item == null) {
			this.nextItem = null;
			return;
		}
		if (!item.isFormField()) {
			this.nextItem = item;
			this.nextType = FILE;
			return;
		}
		this.nextItem = this.toFormField(item);
		this.nextType = FIELD;
	}

	public String[] getParameterNames() {
		if (this.nameToValue == null) {
			return new String[0];
		}
		return this.nameToValue.keySet().toArray(new String[this.nameToValue.size()]);
	}

	public String getParameter(String name) throws IOException, FileUploadException {
		String value = this.req.getParameter(name);
		if (value == null && this.nameToValue != null) {
			value = this.nameToValue.get(name);
		}
		if (value != null || this.iter == null || this.nextItem != null) {
			return value;
		}
		for (;;) {
			FileItemStream item = this.nextMultipartItem();
			if (item == null) {
				break;
			}
			if (!item.isFormField()) {
				this.nextItem = item;
				this.nextType = FILE;
				break;
			}
			this.nextItem = null;
			FormField field = this.toFormField(item);
			if (this.nameToValue == null) {
				this.nameToValue = new HashMap<String, String>();
			}
			this.nameToValue.put(field.name, field.value);
			this.fields.add(field);
			if (field.name.equals(name)) {
				return field.value;
			}
		}
		return value;
	}

	static final class BoundedFileItemStream implements FileItemStream {
		private final FileItemStream delegate;
		private final long limit;

		BoundedFileItemStream(FileItemStream delegate, long limit) {
			this.delegate = delegate;
			this.limit = limit;
		}

		@Override
		public String getContentType() {
			return this.delegate.getContentType();
		}

		@Override
		public String getFieldName() {
			return this.delegate.getFieldName();
		}

		@Override
		public String getName() {
			return this.delegate.getName();
		}

		@Override
		public boolean isFormField() {
			return false;
		}

		@Override
		public InputStream openStream() throws IOException {
			return new BoundedFileInputStream(this.delegate.openStream(), this.limit);
		}

		@Override
		public FileItemHeaders getHeaders() {
			return this.delegate.getHeaders();
		}

		@Override
		public void setHeaders(FileItemHeaders headers) {
			this.delegate.setHeaders(headers);
		}
	}

	private static final class BoundedFileInputStream extends FilterInputStream {
		private final long limit;
		private long count;

		BoundedFileInputStream(InputStream in, long limit) {
			super(in);
			this.limit = limit;
		}

		private int readPastLimit() throws IOException {
			int extra = super.read();
			if (extra == -1) {
				return -1;
			}
			throw new FileSizeLimitIOException();
		}

		@Override
		public int read() throws IOException {
			if (this.count == this.limit) {
				return this.readPastLimit();
			}
			int value = super.read();
			if (value != -1) {
				++this.count;
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (this.count == this.limit) {
				return this.readPastLimit();
			}
			int allowed = (int) Math.min(length, this.limit - this.count);
			int read = super.read(buffer, offset, allowed);
			if (read != -1) {
				this.count += read;
			}
			return read;
		}

		@Override
		public long skip(long count) throws IOException {
			if (count <= 0L) {
				return 0L;
			}
			if (this.count == this.limit) {
				this.readPastLimit();
				return 0L;
			}
			long skipped = super.skip(Math.min(count, this.limit - this.count));
			this.count += skipped;
			return skipped;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(super.available(), this.limit - this.count);
		}
	}
}
