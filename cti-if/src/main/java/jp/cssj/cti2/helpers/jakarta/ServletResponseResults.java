package jp.cssj.cti2.helpers.jakarta;

import java.io.IOException;

import jakarta.servlet.ServletResponse;

import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.util.OutputMeasurer;
import net.zamasoft.zstream.io.impl.StreamFragmentedOutput;

/**
 * 構築したデータをサーブレットのレスポンスとして送り出します。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ServletResponseResults.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class ServletResponseResults implements Results {
	protected final ServletResponse response;
	protected OutputMeasurer builder = null;

	public ServletResponseResults(ServletResponse response) {
		this.response = response;
	}

	public boolean hasNext() {
		return this.builder == null;
	}

	public FragmentedOutput nextBuilder(SourceMetadata metaSource) throws IOException {
		if (this.builder != null) {
			throw new IllegalStateException();
		}

		String mimeType = metaSource.getMimeType();
		long length = metaSource.getLength();
		if (mimeType != null) {
			this.response.setContentType(ServletHelper.getContentType(metaSource));
		}
		if (length != -1L) {
			this.response.setContentLengthLong(length);
		}
		FragmentedOutput builder = new StreamFragmentedOutput(this.response.getOutputStream());
		this.builder = new OutputMeasurer(builder) {
			public void close() throws IOException {
				ServletResponseResults.this.finish();
				super.close();
			}
		};
		return this.builder;
	}

	protected void finish() {
		long length = this.builder.getLength();
		this.response.setContentLengthLong(length);
	}

	public void end() {
		// NOP
	}
}
