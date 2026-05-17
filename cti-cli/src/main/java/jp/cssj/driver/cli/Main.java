package jp.cssj.driver.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import jp.cssj.cti2.helpers.CTIMessageHelper;
import jp.cssj.cti2.helpers.CTISessionHelper;
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
			Option opt = new Option("out", "output-file", true, "出力ファイルを指定する。");
			opt.setArgs(1);
			opt.setArgName("出力ファイル");
			OPTIONS.addOption(opt);
		}

		{
			Option opt = new Option("p", true, "プロパティを指定する。");
			opt.setArgs(Integer.MAX_VALUE);
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
			Option opt = new Option("t", "trust", false, "SSL接続で常に証明書を信頼します。");
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
			System.err.println(e.getMessage());
			System.exit(0);
			return;
		}

		// ヘルプの表示
		if (line.hasOption("h")) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("copper", OPTIONS, true);
			System.exit(0);
			return;
		}

		// バージョン情報の表示
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

		// タイプ
		String inputType;
		if (line.hasOption("if")) {
			inputType = line.getOptionValue("if");
		} else {
			inputType = "text/html";
		}

		// エンコーディング
		String encoding;
		if (line.hasOption("ie")) {
			encoding = line.getOptionValue("ie");
		} else {
			encoding = null;
		}

		// 入力
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

		// 出力
		OutputStream out;
		if (line.hasOption("out")) {
			File file = new File(line.getOptionValue("out"));
			out = new FileOutputStream(file);
		} else {
			out = System.out;
		}

		// プロパティ
		Properties props = new Properties();
		if (line.hasOption("pf")) {
			File file = new File(line.getOptionValue("pf"));
			try (InputStream pin = new FileInputStream(file)) {
				props.load(pin);
			}
		}
		if (line.hasOption("p")) {
			String[] values = line.getOptionValues("p");
			for (int i = 0; i < values.length; i += 2) {
				props.setProperty(values[i], values[i + 1]);
			}
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
		
		if (line.hasOption("t")) {
			System.setProperty("jp.cssj.driver.tls.trust", "true");
		}
		
		boolean sv = line.hasOption("sv");

		SourceResolver resolver = CompositeSourceResolver.createGenericCompositeSourceResolver();

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
				session.setResults(new SingleResult(out));
				session.setMessageHandler(CTIMessageHelper.createStreamMessageHandler(System.err));
				session.setSourceResolver(resolver);
				CTISessionHelper.properties(session, props);

				if (source != null) {
					session.transcode(source);
				} else {
					session.transcode(uri);
				}
			}
		}
	}
}