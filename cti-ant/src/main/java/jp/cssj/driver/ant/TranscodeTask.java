package jp.cssj.driver.ant;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.helpers.CTIMessageHelper;
import jp.cssj.cti2.results.SingleResult;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.composite.CompositeSourceResolver;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.util.FileNameMapper;
import org.apache.tools.ant.util.IdentityMapper;
import org.apache.tools.ant.util.SourceFileScanner;

/**
 * A task that converts documents with Copper PDF.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: TranscodeTask.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class TranscodeTask extends MatchingTask {
	private SourceResolver resolver = CompositeSourceResolver.createGenericCompositeSourceResolver();

	private Connection conn = new Connection();

	private List<Property> props = new ArrayList<Property>();

	private File srcDir, destDir;

	private String suffix = ".pdf";

	public class Connection {
		private URI uri = URI.create("copper:direct:");

		private Map<String, String> props = new HashMap<String, String>();

		/**
		 * Sets the URI of the document conversion server.
		 * 
		 * @param uri
		 */
		public void setUri(String uri) {
			this.uri = URI.create(uri);
		}

		public void setUser(String user) {
			this.props.put("user", user);
		}

		public void setPassword(String password) {
			this.props.put("password", password);
		}

		public URI getUri() {
			return this.uri;
		}

		public Map<String, String> getProps() {
			return this.props.isEmpty() ? null : this.props;
		}
	}

	/**
	 * A property.
	 * 
	 * @author MIYABE Tatsuhiko
	 * @version $Id: TranscodeTask.java 1552 2018-04-26 01:43:24Z miyabe $
	 */
	public class Property {
		public String name, value;

		public void setName(String name) {
			this.name = name;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	public TranscodeTask() {
		// ignore
	}

	public Connection createConnection() {
		return this.conn = new Connection();
	}

	/**
	 * Creates a &lt;property name="" value=""&gt; element.
	 * 
	 * @return
	 */
	public Property createProperty() {
		Property prop = new Property();
		this.props.add(prop);
		return prop;
	}

	/**
	 * Sets the source directory.
	 * 
	 * @param srcDir
	 */
	public void setSrcDir(File srcDir) {
		this.srcDir = srcDir;
	}

	/**
	 * Sets the output directory.
	 * 
	 * @param destDir
	 */
	public void setDestDir(File destDir) {
		this.destDir = destDir;
	}

	/**
	 * Sets the output file extension.
	 * 
	 * @param suffix
	 */
	public void setSuffix(String suffix) {
		this.suffix = suffix;
	}

	private FileNameMapper myMapper = new IdentityMapper() {
		public String[] mapFileName(String src) {
			return new String[] { TranscodeTask.this.map(src) };
		}
	};

	private String map(String src) {
		String dest;
		int dot = src.lastIndexOf('.');
		if (dot != -1) {
			dest = src.substring(0, dot) + this.suffix;
		} else {
			dest = src + this.suffix;
		}
		return dest;
	}

	public void execute() throws BuildException {
		// Conversion source
		File srcDir;
		if (this.srcDir == null) {
			srcDir = this.getProject().resolveFile(".");
		} else {
			srcDir = this.srcDir;
		}

		// Conversion source
		File destDir;
		if (this.destDir == null) {
			destDir = srcDir;
		} else {
			destDir = this.destDir;
		}

		int count = 0;
		try {
			// Connect
			try (CTISession session = CTIDriverManager.getSession(this.conn.getUri(), this.conn.getProps())) {
				session.setMessageHandler(CTIMessageHelper.createStreamMessageHandler(System.err));

				session.setSourceResolver(this.resolver);
				for (int i = 0; i < this.props.size(); ++i) {
					Property prop = (Property) this.props.get(i);
					session.property(prop.name, prop.value);
				}

				DirectoryScanner ds = this.getDirectoryScanner(srcDir);
				String[] files = ds.getIncludedFiles();
				SourceFileScanner sfs = new SourceFileScanner(this);
				files = sfs.restrict(files, srcDir, this.destDir, myMapper);
				for (int i = 0; i < files.length; ++i) {
					String src = files[i];
					String dest = this.map(src);
					File srcFile = new File(srcDir, src);
					File destFile = new File(destDir, dest);
					if (destFile.exists() && destFile.lastModified() >= srcFile.lastModified()) {
						return;
					}
					System.out.println(srcFile.getName() + " -> " + destFile.getName());

					destFile.getParentFile().mkdirs();

					boolean failure = true;
					OutputStream out = new FileOutputStream(destFile);
					try {
						session.setResults(new SingleResult(out));
						Source source = this.resolver.resolve(srcFile.toURI());
						try {
							session.transcode(source);
							failure = false;
						} finally {
							this.resolver.release(source);
						}
					} finally {
						if (failure) {
							destFile.delete();
						}
					}
					count++;
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
			throw new BuildException(e);
		} finally {
			System.out.println("Transcoded " + count + " file(s).");
		}
	}
}
