package jp.cssj.rsr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jp.cssj.rsr.impl.StreamRandomBuilder;
import org.junit.jupiter.api.Test;

class StreamRandomBuilderTest {
	@Test
	void ブロックを追加順に出力できる() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamRandomBuilder builder = new StreamRandomBuilder(out);

		builder.addBlock();
		builder.addBlock();

		builder.write(0, bytes("Hello "), 0, 6);
		builder.write(1, bytes("World!"), 0, 6);

		builder.finish();

		assertEquals("Hello World!", out.toString("UTF-8"));
	}

	@Test
	void 中間にブロックを挿入して順序を保てる() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamRandomBuilder builder = new StreamRandomBuilder(out);

		builder.addBlock();
		builder.write(0, bytes("A"), 0, 1);

		builder.addBlock();
		builder.write(1, bytes("C"), 0, 1);

		builder.insertBlockBefore(1);
		builder.write(2, bytes("B"), 0, 1);

		builder.finish();

		assertEquals("ABC", out.toString("UTF-8"));
	}

	@Test
	void 大きなデータでも破損しない() throws IOException {
		byte[] large = new byte[300];
		Arrays.fill(large, (byte) 'A');

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamRandomBuilder builder = new StreamRandomBuilder(out);

		builder.addBlock();
		builder.write(0, large, 0, large.length);

		builder.finish();

		assertEquals(300, out.size());
		assertArrayEquals(large, out.toByteArray());
	}

	private byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
