package jp.cssj.cti2.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MimeTypeHelperTest {
	@Test
	void getTypePartはセミコロン以降を除去できる() {
		assertEquals("text/html", MimeTypeHelper.getTypePart("text/html; charset=UTF-8"));
		assertEquals("application/json", MimeTypeHelper.getTypePart(" application/json \n"));
	}

	@Test
	void equalsはパラメータを無視して比較できる() {
		assertEquals(true, MimeTypeHelper.equals("text/html; charset=UTF-8", "text/html"));
		assertEquals(false, MimeTypeHelper.equals("text/html", "text/plain"));
	}
}
