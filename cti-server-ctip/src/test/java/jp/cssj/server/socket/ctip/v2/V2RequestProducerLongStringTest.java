package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import jp.cssj.driver.ctip.common.ChannelIO;
import jp.cssj.driver.ctip.v2.V2ClientPackets;

/**
 * 32,767バイトを超える文字列(2026-08-28)。長さは符号なし16bitで、
 * 符号付きで読むと本体を読み飛ばさないままストリームが崩れていた——
 * 48KBの{@code input.image-metrics}(data:URI)を送ると、本文途中の
 * {@code ':'}を次のパケット種別として読み「Bad request: type 3a」で
 * 接続ごと落ちていた。
 */
class V2RequestProducerLongStringTest {

	/** 40,000バイトのプロパティ値が、そのまま復元できること。 */
	@Test
	void readsPropertyValueLongerThanSignedShort() throws Exception {
		final String name = "input.image-metrics";
		final String value = "data:application/json;base64," + "A".repeat(40000);
		final V2RequestProducer producer = new V2RequestProducer("UTF-8",
				new ByteArrayInputStream(propertyPacket(name, value)));
		producer.next();
		assertEquals(V2ClientPackets.PROPERTY, producer.getType());
		assertEquals(name, producer.getName());
		assertEquals(value, producer.getValue());
		// 続きが正しい位置から読めること(ストリームが崩れていない)
		producer.next();
		assertEquals(V2ClientPackets.EOF, producer.getType());
	}

	/** 16bitに収まらない文字列は、壊さずに断ること。 */
	@Test
	void refusesStringThatDoesNotFitTheLengthField() {
		final String tooLong = "A".repeat(ChannelIO.MAX_STRING_BYTES + 1);
		final IOException e = assertThrows(IOException.class, () -> ChannelIO.toBytes(tooLong, "UTF-8"));
		assertEquals(true, e.getMessage().contains("too long"));
	}

	/** クライアントと同じ書式でPROPERTYとEOFを組み立てます。 */
	private static byte[] propertyPacket(final String name, final String value) throws IOException {
		final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		final byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
		final int payload = 1 + 2 + nameBytes.length + 2 + valueBytes.length;
		final ByteBuffer src = ByteBuffer.allocate(4 + payload + 4 + 1);
		src.putInt(payload);
		src.put(V2ClientPackets.PROPERTY);
		src.putShort((short) nameBytes.length);
		src.put(nameBytes);
		src.putShort((short) valueBytes.length);
		src.put(valueBytes);
		src.putInt(1);
		src.put(V2ClientPackets.EOF);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(src.array(), 0, src.position());
		return out.toByteArray();
	}
}
