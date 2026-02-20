package jp.cssj.cti2.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.api.Test;

class URIHelperTest {
	@Test
	void createは空白をパーセントエンコードする() throws Exception {
		URI uri = URIHelper.create("UTF-8", "http://localhost:8099/test page.html");
		assertEquals("http://localhost:8099/test%20page.html", uri.toString());
	}

	@Test
	void resolveは相対URIを解決できる() throws Exception {
		URI base = URIHelper.create("UTF-8", "http://localhost:8099/");
		URI resolved = URIHelper.resolve("UTF-8", base, "sub/page.html");
		assertEquals("http://localhost:8099/sub/page.html", resolved.toString());
	}
}
