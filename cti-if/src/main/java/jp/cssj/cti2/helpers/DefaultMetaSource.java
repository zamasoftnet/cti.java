package jp.cssj.cti2.helpers;

import java.io.IOException;
import java.net.URI;

import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.util.URIHelper;

/**
 * デフォルトのデータのメタ情報です。
 * 
 * @deprecated net.zamasoft.zstream.resolver.util.SimpleSourceMetadataを使ってください。
 * @author MIYABE Tatsuhiko
 * @version $Id: DefaultMetaSource.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class DefaultMetaSource implements SourceMetadata {
	private URI uri;
	private String encoding;
	private String mimeType;
	private long length;

	public DefaultMetaSource() {
		this((URI) null);
	}

	public DefaultMetaSource(URI uri) {
		this(uri, null);
	}

	public DefaultMetaSource(URI uri, String mimeType) {
		this(uri, mimeType, null);
	}

	public DefaultMetaSource(URI uri, String mimeType, String encoding) {
		this(uri, mimeType, encoding, -1L);
	}

	public DefaultMetaSource(URI uri, String mimeType, String encoding, long length) {
		if (uri == null) {
			uri = URIHelper.CURRENT_URI;
		}
		this.uri = uri;
		this.mimeType = mimeType;
		this.encoding = encoding;
		this.length = length;
	}

	public DefaultMetaSource(Source source) throws IOException {
		this(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength());
	}

	public URI getURI() {
		return this.uri;
	}

	public void setURI(URI uri) {
		this.uri = uri;
	}

	public String getEncoding() {
		return this.encoding;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	public String getMimeType() {
		return this.mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public long getLength() {
		return this.length;
	}

	public void setLength(long length) {
		this.length = length;
	}
}
