package jp.cssj.driver.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.SequentialOutput;
import net.zamasoft.zstream.resolver.SourceMetadata;

class V2SessionResultsTest {
	@Test
	void eofFinalizesTheResultSeries() throws Exception {
		final TestContext context = context(Packet.start(), Packet.data(new byte[] { 1, 2, 3 }), Packet.eof());

		context.session.next();

		assertEquals(1, context.results.endCount);
		assertEquals(1, context.output.closeCount);
		assertEquals(Arrays.asList(Byte.valueOf((byte) 1), Byte.valueOf((byte) 2), Byte.valueOf((byte) 3)),
				context.output.bytes);
	}

	@Test
	void continuousNextDefersFinalizationUntilJoinEof() throws Exception {
		final TestContext context = context(Packet.start(), Packet.data(new byte[] { 1 }), Packet.next());

		context.session.next();

		assertEquals(0, context.results.endCount);
		assertEquals(0, context.output.closeCount);
		context.producer.add(Packet.data(new byte[] { 2 }), Packet.eof());
		context.session.join();

		assertTrue(context.request.joinCalled);
		assertEquals(1, context.results.endCount);
		assertEquals(1, context.output.closeCount);
		assertEquals(Arrays.asList(Byte.valueOf((byte) 1), Byte.valueOf((byte) 2)), context.output.bytes);
	}

	@Test
	void readableAbortFinalizesBeforeReportingTheInterruption() throws Exception {
		final TestContext context = context(Packet.start(), Packet.data(new byte[] { 1 }), Packet.abort((byte) 0));

		final TranscoderException e = assertThrows(TranscoderException.class, () -> context.session.next());

		assertEquals(TranscoderException.STATE_READABLE, e.getState());
		assertEquals(1, context.output.closeCount);
		assertEquals(1, context.results.endCount);
	}

	@Test
	void readableAbortWithoutOutputStillFinalizesTheResultSeries() throws Exception {
		final TestContext context = context(Packet.abort((byte) 0));

		final TranscoderException e = assertThrows(TranscoderException.class, () -> context.session.next());

		assertEquals(TranscoderException.STATE_READABLE, e.getState());
		assertEquals(0, context.output.closeCount);
		assertEquals(1, context.results.endCount);
	}

	@Test
	void brokenAbortDoesNotFinalizeTheResultSeries() throws Exception {
		final TestContext context = context(Packet.start(), Packet.data(new byte[] { 1 }), Packet.abort((byte) 1));

		final TranscoderException e = assertThrows(TranscoderException.class, () -> context.session.next());

		assertEquals(TranscoderException.STATE_BROKEN, e.getState());
		assertEquals(0, context.results.endCount);
		assertNull(context.session.builder);
	}

	private static TestContext context(final Packet... packets) throws Exception {
		final V2Session session = new V2Session(URI.create("ctip://localhost/"), "UTF-8", null, null);
		final ScriptedProducer producer = new ScriptedProducer(packets);
		final FakeRequest request = new FakeRequest();
		final CountingOutput output = new CountingOutput();
		final CountingResults results = new CountingResults(output);
		session.producer = producer;
		session.request = request;
		session.results = results;
		session.state = 2;
		return new TestContext(session, producer, request, results, output);
	}

	private static final class TestContext {
		final V2Session session;
		final ScriptedProducer producer;
		final FakeRequest request;
		final CountingResults results;
		final CountingOutput output;

		TestContext(final V2Session session, final ScriptedProducer producer, final FakeRequest request,
				final CountingResults results, final CountingOutput output) {
			this.session = session;
			this.producer = producer;
			this.request = request;
			this.results = results;
			this.output = output;
		}
	}

	private static final class Packet {
		final byte type;
		final byte mode;
		final byte[] data;

		Packet(final byte type, final byte mode, final byte[] data) {
			this.type = type;
			this.mode = mode;
			this.data = data;
		}

		static Packet start() {
			return new Packet(V2ServerPackets.START_DATA, (byte) 0, null);
		}

		static Packet data(final byte[] data) {
			return new Packet(V2ServerPackets.DATA, (byte) 0, data);
		}

		static Packet eof() {
			return new Packet(V2ServerPackets.EOF, (byte) 0, null);
		}

		static Packet next() {
			return new Packet(V2ServerPackets.NEXT, (byte) 0, null);
		}

		static Packet abort(final byte mode) {
			return new Packet(V2ServerPackets.ABORT, mode, null);
		}
	}

	private static final class ScriptedProducer extends V2ContentProducer {
		private final List<Packet> packets = new ArrayList<Packet>();
		private int index = -1;
		private int dataOffset;

		ScriptedProducer(final Packet... packets) throws IOException {
			super(URI.create("ctip://localhost/"), "UTF-8");
			this.add(packets);
		}

		void add(final Packet... packets) {
			this.packets.addAll(Arrays.asList(packets));
		}

		private Packet current() {
			return this.packets.get(this.index);
		}

		@Override
		public void next() {
			++this.index;
			this.dataOffset = 0;
		}

		@Override
		public byte getType() {
			return this.current().type;
		}

		@Override
		public URI getURI() {
			return URI.create("result.bin");
		}

		@Override
		public String getMimeType() {
			return "application/octet-stream";
		}

		@Override
		public String getEncoding() {
			return null;
		}

		@Override
		public long getLength() {
			return -1;
		}

		@Override
		public int read(final byte[] b, final int off, final int len) {
			final byte[] data = this.current().data;
			if (data == null || this.dataOffset >= data.length) {
				return -1;
			}
			final int count = Math.min(len, data.length - this.dataOffset);
			System.arraycopy(data, this.dataOffset, b, off, count);
			this.dataOffset += count;
			return count;
		}

		@Override
		public byte getMode() {
			return this.current().mode;
		}

		@Override
		public short getCode() {
			return 1;
		}

		@Override
		public String[] getArgs() {
			return new String[0];
		}

		@Override
		public String getMessage() {
			return "interrupted";
		}
	}

	private static final class FakeRequest extends V2RequestConsumer {
		boolean joinCalled;

		FakeRequest() throws IOException {
			super(null, "UTF-8");
		}

		@Override
		public void join() {
			this.joinCalled = true;
		}
	}

	private static final class CountingResults implements Results {
		final CountingOutput output;
		int endCount;

		CountingResults(final CountingOutput output) {
			this.output = output;
		}

		@Override
		public boolean hasNext() {
			return true;
		}

		@Override
		public FragmentedOutput nextBuilder(final SourceMetadata metaSource) {
			assertEquals(URI.create("result.bin"), metaSource.getURI());
			return this.output;
		}

		@Override
		public void end() {
			++this.endCount;
		}
	}

	private static final class CountingOutput implements SequentialOutput {
		final List<Byte> bytes = new ArrayList<Byte>();
		int closeCount;

		@Override
		public void write(final byte[] b, final int off, final int len) {
			for (int i = 0; i < len; ++i) {
				this.bytes.add(Byte.valueOf(b[off + i]));
			}
		}

		@Override
		public void write(final int id, final byte[] b, final int off, final int len) {
			this.write(b, off, len);
		}

		@Override
		public void addFragment() {
			// no-op
		}

		@Override
		public void insertFragmentBefore(final int anchorId) {
			// no-op
		}

		@Override
		public void finishFragment(final int id) {
			// no-op
		}

		@Override
		public boolean supportsPositionInfo() {
			return false;
		}

		@Override
		public PositionInfo getPositionInfo() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			++this.closeCount;
		}
	}
}
