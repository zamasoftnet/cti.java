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
 * Strings longer than 32,767 bytes (2026-08-28). The length is an unsigned 16-bit value;
 * reading it as signed corrupted the stream without skipping the body.
 * Sending a 48 KB {@code input.image-metrics} (data:URI) caused {@code ':'} within the body
 * to be read as the next packet type, dropping the entire connection with "Bad request: type 3a".
 */
class V2RequestProducerLongStringTest {

	/** A 40,000-byte property value can be reconstructed unchanged. */
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
		// Subsequent data can be read from the correct position (the stream is not corrupted)
		producer.next();
		assertEquals(V2ClientPackets.EOF, producer.getType());
	}

	/** Reject strings that do not fit in 16 bits without corrupting the stream. */
	@Test
	void refusesStringThatDoesNotFitTheLengthField() {
		final String tooLong = "A".repeat(ChannelIO.MAX_STRING_BYTES + 1);
		final IOException e = assertThrows(IOException.class, () -> ChannelIO.toBytes(tooLong, "UTF-8"));
		assertEquals(true, e.getMessage().contains("too long"));
	}

	/** Builds PROPERTY and EOF packets in the same format as the client. */
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
