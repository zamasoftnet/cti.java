package jp.cssj.driver.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageHelper;
import jp.cssj.cti2.helpers.CTISessionHelper;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.results.ResourceDirectoryResults;
import jp.cssj.cti2.results.Results;
import jp.cssj.cti2.results.SingleResult;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.composite.CompositeSourceResolver;
import net.zamasoft.zstream.resolver.protocol.file.FileSource;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: Main.java 1593 2019-12-03 07:02:17Z miyabe $
 */
public final class Main {
	private static final Options OPTIONS = new Options();
	static {
		{
			OptionGroup optGroup = new OptionGroup();

			{
				Option opt = new Option("h", "help", false, "ヘルプメッセージを表示する。");
				optGroup.addOption(opt);
			}

			{
				Option opt = new Option("v", "version", false, "バージョン情報を表示する。");
				optGroup.addOption(opt);
			}

			{
				Option opt = new Option("in", "input-file", true, "入力ファイルを指定する。");
				opt.setArgs(1);
				opt.setArgName("入力ファイル");
				optGroup.addOption(opt);
			}

			{
				Option opt = new Option("uri", "input-uri", true, "入力URIを指定する。");
				opt.setArgs(1);
				opt.setArgName("入力URI");
				optGroup.addOption(opt);
			}

			OPTIONS.addOptionGroup(optGroup);
		}

		{
			Option opt = new Option("if", "input-format", true, "入力形式を指定する(省略時はtext/html)。");
			opt.setArgs(1);
			opt.setArgName("入力形式");
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("ie", "input-encoding", true, "入力エンコーディングを指定する。");
			opt.setArgs(1);
			opt.setArgName("入力エンコーディング");
			OPTIONS.addOption(opt);
		}

		{
			OptionGroup optGroup = new OptionGroup();
			{
				Option opt = new Option("out", "output-file", true, "出力ファイルを指定する。");
				opt.setArgs(1);
				opt.setArgName("出力ファイル");
				optGroup.addOption(opt);
			}
			{
				Option opt = new Option("outdir", "output-directory", true,
						"結果URIを保った複数ファイルの出力先ディレクトリを指定する。");
				opt.setArgs(1);
				opt.setArgName("出力ディレクトリ");
				optGroup.addOption(opt);
			}
			OPTIONS.addOptionGroup(optGroup);
		}

		{
			Option opt = new Option("p", true, "プロパティを指定する。");
			// Unlimited arguments use UNLIMITED_VALUES(-2). Passing Integer.MAX_VALUE caused
			// DefaultParser to conclude that arguments were still missing, so using -p
			// always threw MissingArgumentException (fixed 2026-07-30).
			opt.setArgs(Option.UNLIMITED_VALUES);
			opt.setArgName("プロパティ名=値");
			opt.setValueSeparator('=');
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("pf", "properties-file", true, "プロパティファイルを指定する。");
			opt.setArgs(1);
			opt.setArgName("プロパティファイル");
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("s", "server", true, "サーバーURIを指定する。");
			opt.setArgs(1);
			opt.setArgName("サーバーURI");
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("u", "user", true, "接続ユーザー。");
			opt.setArgs(1);
			opt.setArgName("ユーザー");
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("pw", "pw", true, "接続パスワード。");
			opt.setArgs(1);
			opt.setArgName("パスワード");
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("sv", "server-version", false, "ドキュメント変換サーバーのバージョン情報。");
			OPTIONS.addOption(opt);
		}
		{
			// Means "do not verify." Keep the name for compatibility.
			Option opt = new Option("t", "trust", false,
					"サーバー証明書を検証しません(危険。自己署名の試験用)。--insecureと同じです。");
			OPTIONS.addOption(opt);
		}
		{
			Option opt = new Option(null, "insecure", false,
					"サーバー証明書を検証しません(危険。自己署名の試験用)。");
			OPTIONS.addOption(opt);
		}
	}

	private Main() {
		// unused
	}

	public static void main(String[] args) throws Exception {
		CommandLineParser parser = new DefaultParser();
		CommandLine line;
		try {
			line = parser.parse(OPTIONS, args);
		} catch (Exception e) {
			// Returning success (0) on failure caused the calling build or script to
			// report success even though nothing was converted (fixed 2026-07-30).
			System.err.println(e.getMessage());
			System.exit(1);
			return;
		}

		// Display help
		if (line.hasOption("h")) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("copper", OPTIONS, true);
			System.exit(0);
			return;
		}

		// Display version information
		if (line.hasOption("v")) {
			System.out.print("Copper PDF CLI ");
			try (InputStream in = Main.class.getResourceAsStream("VERSION")) {
				byte[] buff = new byte[100];
				int len = in.read(buff);
				System.out.write(buff, 0, len);
			}
			System.out.println();
			System.out.println("Java: "+System.getProperty("java.version")+" "+System.getProperty("java.vendor"));
			System.out.println("Java VM: "+System.getProperty("java.vm.version")+" "+System.getProperty("java.vm.vendor"));
			System.exit(0);
			return;
		}

		// Arguments that belong to no option are not read. "copper file.html" used to read standard input
		// without a word (2026-10-08); keep that behavior, but say so.
		if (!line.getArgList().isEmpty()) {
			String rest = String.join(" ", line.getArgList());
			if (line.hasOption("in") || line.hasOption("uri")) {
				System.err.println("余った引数を無視します: " + rest);
			} else {
				System.err.println("入力ファイルは -in で指定してください。標準入力を読みます(無視した引数: " + rest + ")");
			}
		}

		// Type
		String inputType;
		if (line.hasOption("if")) {
			inputType = line.getOptionValue("if");
		} else {
			inputType = "text/html";
		}

		// Encoding
		String encoding;
		if (line.hasOption("ie")) {
			encoding = line.getOptionValue("ie");
		} else {
			encoding = null;
		}

		// Input
		URI uri = new File(".").toURI();
		Source source;
		if (line.hasOption("in")) {
			File file = new File(line.getOptionValue("in"));
			if (line.hasOption("uri")) {
				uri = uri.resolve(line.getOptionValue("uri"));
			} else {
				uri = file.toURI();
			}
			source = new FileSource(file, uri, inputType, encoding);
		} else if (line.hasOption("uri")) {
			uri = uri.resolve(line.getOptionValue("uri"));
			source = null;
		} else {
			if (line.hasOption("uri")) {
				uri = uri.resolve(line.getOptionValue("uri"));
			}
			source = new StreamSource(uri, System.in, inputType, encoding);
		}

		// Properties
		Properties props = new Properties();
		if (line.hasOption("pf")) {
			File file = new File(line.getOptionValue("pf"));
			try (InputStream pin = new FileInputStream(file)) {
				props.load(pin);
			}
		}
		if (line.hasOption("p")) {
			String[] values = line.getOptionValues("p");
			if (values.length % 2 != 0) {
				// A -p value without '=' breaks the pairing. Do not silently continue.
				System.err.println("-p はプロパティ名=値 の形式で指定してください: " + values[values.length - 1]);
				System.exit(1);
				return;
			}
			for (int i = 0; i < values.length; i += 2) {
				props.setProperty(values[i], values[i + 1]);
			}
		}
		if ("application/vnd.copper.paged-svg".equals(props.getProperty("output.type"))
				&& !line.hasOption("outdir")) {
			System.err.println("Paged SVG出力には -outdir を指定してください。");
			System.exit(1);
			return;
		}

		// Output. -outdir safely saves results while preserving the relative URIs in their metadata.
		Results results;
		LazyFileOutputStream outFile = null;
		if (line.hasOption("outdir")) {
			results = new ResourceDirectoryResults(new File(line.getOptionValue("outdir")));
		} else {
			OutputStream out;
			if (line.hasOption("out")) {
				outFile = new LazyFileOutputStream(new File(line.getOptionValue("out")));
				out = outFile;
			} else {
				out = System.out;
			}
			results = new SingleResult(out);
		}

		String server = "copper:direct:";
		if (line.hasOption("s")) {
			server = line.getOptionValue("s");
		}
		URI serverURI = URI.create(server);

		String user = null, password = null;
		if (line.hasOption("u")) {
			user = line.getOptionValue("u");
		}
		if (line.hasOption("pw")) {
			password = line.getOptionValue("pw");
		}
		
		if (line.hasOption("t") || line.hasOption("insecure")) {
			// **Set the new name.** Specifying the old name produces a warning.
			System.setProperty(jp.cssj.cti2.TLSPolicy.INSECURE, "true");
		}
		
		boolean sv = line.hasOption("sv");

		SourceResolver resolver = CompositeSourceResolver.createGenericCompositeSourceResolver();

		final ErrorTrackingHandler messages = new ErrorTrackingHandler(
				CTIMessageHelper.createStreamMessageHandler(System.err));
		try (CTISession session = CTIDriverManager.getSession(serverURI, user, password)) {
			if (sv) {
				try (InputStream in = session.getServerInfo(URI.create("http://www.cssj.jp/ns/ctip/version"))) {
					DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
					Document doc = builder.parse(in);
					{
						Element e = (Element) doc.getElementsByTagName("long-version").item(0);
						System.out.println(e.getFirstChild().getNodeValue());
					}
					{
						Element e = (Element) doc.getElementsByTagName("copyrights").item(0);
						System.out.println(e.getFirstChild().getNodeValue());
					}
					{
						Element e = (Element) doc.getElementsByTagName("credits").item(0);
						System.out.println(e.getFirstChild().getNodeValue());
					}
				}
			} else {
				session.setResults(results);
				session.setMessageHandler(messages);
				session.setSourceResolver(resolver);
				CTISessionHelper.properties(session, props);

				if (source != null) {
					session.transcode(source);
				} else {
					session.transcode(uri);
				}
			}
		} catch (Exception e) {
			// A failed conversion leaves no output file (2026-10-08). A conversion error the server has reported as a
			// message ends with that line instead of a stack trace; anything else keeps its trace for the report.
			if (outFile != null) {
				outFile.discard();
			}
			if (!(e instanceof TranscoderException)) {
				e.printStackTrace();
			} else if (!messages.errorReported) {
				System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
			}
			System.exit(1);
			return;
		}
	}

	/** Remembers whether an error or a fatal error was reported. */
	private static final class ErrorTrackingHandler implements MessageHandler {
		private final MessageHandler delegate;
		boolean errorReported = false;

		ErrorTrackingHandler(MessageHandler delegate) {
			this.delegate = delegate;
		}

		public void message(short code, String[] args, String message) {
			if (CTIMessageHelper.getLevel(code) >= CTIMessageHelper.ERROR) {
				this.errorReported = true;
			}
			this.delegate.message(code, args, message);
		}
	}

	/** Creates the -out file at the first write, so a conversion that fails before writing leaves no file. */
	private static final class LazyFileOutputStream extends OutputStream {
		private final File file;
		private OutputStream out = null;

		LazyFileOutputStream(File file) {
			this.file = file;
		}

		private OutputStream out() throws IOException {
			if (this.out == null) {
				this.out = new FileOutputStream(this.file);
			}
			return this.out;
		}

		public void write(int b) throws IOException {
			this.out().write(b);
		}

		public void write(byte[] b, int off, int len) throws IOException {
			this.out().write(b, off, len);
		}

		public void flush() throws IOException {
			if (this.out != null) {
				this.out.flush();
			}
		}

		public void close() throws IOException {
			if (this.out != null) {
				this.out.close();
			}
		}

		/** Removes what a failed conversion wrote. */
		void discard() {
			try {
				this.close();
			} catch (IOException e) {
				// Deleting matters more than closing
			}
			if (this.out != null) {
				this.file.delete();
			}
		}
	}
}
