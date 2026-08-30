package jp.cssj.cti2.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CTIMessageHelperTest {
	@Test
	void missingDecodedMessageFallsBackToCodeAndArguments() throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8.name())) {
			CTIMessageHelper.createStreamMessageHandler(out).message((short) 0x2802,
					new String[] { "unknown-property" }, null);
		}
		assertEquals("[2802] unknown-property" + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8.name()));
	}
}
