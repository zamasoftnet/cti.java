package jp.cssj.cti2.helpers.jakarta;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import jp.cssj.cti2.CTISession;
import net.zamasoft.zstream.resolver.SourceMetadata;

/**
 * 転送先のサーブレット/JSPの出力をキャプチャしてCTISessionに渡します。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIHttpServletResponseWrapper.java 694 2011-09-27 11:48:14Z
 *          miyabe $
 */
public class CTIHttpServletResponseWrapper extends HttpServletResponseWrapper implements SourceMetadata, Closeable {
	private final CTISession session;

	private final URI uri;

	private final boolean transcode;

	private long contentLength = -1L;

	private String encoding, contentType;

	private ServletOutputStream servletOut = null;

	private PrintWriter writer = null;

	private class MyServletOutputStream extends ServletOutputStream {
		private OutputStream out;
		private byte[] buff = new byte[8192];
		private int len = 0;

		public void write(int b) throws IOException {
			if (this.buff != null) {
				if (this.len < this.buff.length) {
					this.buff[this.len++] = (byte) b;
					return;
				}
				this.flush();
			}
			if (this.out != null) {
				this.out.write(b);
			}
		}

		public void write(byte[] b) throws IOException {
			this.write(b, 0, b.length);
		}

		public void write(byte[] b, int off, int len) throws IOException {
			if (this.buff != null) {
				if (len <= this.buff.length - this.len) {
					System.arraycopy(b, off, this.buff, this.len, len);
					this.len += len;
					return;
				}
				this.flush();
			}
			if (this.out != null) {
				this.out.write(b, off, len);
			}
		}

		public void flush() throws IOException {
			if (this.out == null) {
				CTIHttpServletResponseWrapper o = CTIHttpServletResponseWrapper.this;
				if (o.transcode) {
					this.out = o.session.transcode(o);
				} else {
					this.out = o.session.resource(o);
				}
				if (this.len > 0) {
					this.out.write(this.buff, 0, this.len);
				}
				this.buff = null;
			}
		}

		public void close() throws IOException {
			this.flush();
			this.out.close();
		}

		public boolean isReady() {
			return true;
		}

		public void setWriteListener(WriteListener writeListener) {
			try {
				writeListener.onWritePossible();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	};

	/**
	 * 指定したレスポンスをラップし、セッションにデータを送るレスポンスを構築します。
	 * 
	 * @param response
	 *            ラップするレスポンス。
	 * @param session
	 *            データ送信先のセッション。
	 * @param uri
	 *            データのURI。
	 * @param transcode
	 *            falseであればリソースとして、trueであればメインドキュメントとして渡す。
	 */
	public CTIHttpServletResponseWrapper(HttpServletResponse response, CTISession session, URI uri, boolean transcode) {
		super(response);
		this.session = session;
		this.uri = uri;
		this.transcode = true;
		this.encoding = "UTF-8";
		this.contentType = response.getContentType();
	}

	/**
	 * new CTIHttpServletResponseWrapper(response, session, uri, true)を呼び出すのと同等です。
	 * 
	 * @param response
	 *            ラップするレスポンス。
	 * @param session
	 *            データ送信先のセッション。
	 * @param uri
	 *            データのURI。
	 */
	public CTIHttpServletResponseWrapper(HttpServletResponse response, CTISession session, URI uri) {
		this(response, session, uri, true);
	}

	public void setContentLength(int contentLength) {
		this.contentLength = contentLength;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public void setCharacterEncoding(String encoding) {
		this.encoding = encoding;
	}

	public String getEncoding() throws IOException {
		return this.encoding;
	}

	public long getLength() throws IOException {
		return this.contentLength;
	}

	public String getMimeType() throws IOException {
		return this.contentType;
	}

	public URI getURI() {
		return this.uri;
	}

	public ServletOutputStream getOutputStream() {
		if (this.servletOut == null) {
			this.servletOut = new MyServletOutputStream();
		}
		return this.servletOut;
	}

	public PrintWriter getWriter() throws IOException {
		if (this.writer == null) {
			this.writer = new PrintWriter(new OutputStreamWriter(this.getOutputStream(), this.encoding));
		}
		return this.writer;
	}

	public void flushBuffer() throws IOException {
		if (this.writer != null) {
			this.writer.flush();
		} else if (this.servletOut != null) {
			this.servletOut.flush();
		}
	}

	public void close() throws IOException {
		if (this.writer != null) {
			this.writer.close();
		} else if (this.servletOut != null) {
			this.servletOut.close();
		}
	}
}