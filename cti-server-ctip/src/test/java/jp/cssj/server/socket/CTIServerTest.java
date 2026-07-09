package jp.cssj.server.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CTIServerTest {
	@Test
	void shutdownDrainsActiveConnectionBeforeForceClosingSocket() throws Exception {
		ProcessorControl control = new ProcessorControl();
		StartedServer startedServer = startServer(1, control);
		CTIServer server = startedServer.server;

		try (Socket socket = connect(startedServer.port);
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
			assertEquals("started 1", reader.readLine());

			ExecutorService shutdownThread = Executors.newSingleThreadExecutor();
			Future<?> shutdown = shutdownThread.submit(server::shutdown);
			try {
				Thread.sleep(300);
				assertFalse(shutdown.isDone(), "shutdown should wait for the active processor to finish");

				control.finishPermits.release();
				assertEquals("done 1", reader.readLine());
				shutdown.get(5, TimeUnit.SECONDS);
				assertEquals(1, server.getFreeThreads());
			} finally {
				control.finishPermits.release(10);
				shutdownThread.shutdownNow();
			}
		} finally {
			server.shutdown();
		}
	}

	@Test
	void maxThreadsLimitsConcurrentProcessorsAndRestoresPermit() throws Exception {
		ProcessorControl control = new ProcessorControl();
		StartedServer startedServer = startServer(1, control);
		CTIServer server = startedServer.server;

		try (Socket first = connect(startedServer.port);
				BufferedReader firstReader = reader(first);
				Socket second = connect(startedServer.port);
				BufferedReader secondReader = reader(second)) {
			assertEquals("started 1", firstReader.readLine());
			assertEquals(0, server.getFreeThreads());

			second.setSoTimeout(300);
			assertThrows(SocketTimeoutException.class, secondReader::readLine);
			second.setSoTimeout(5000);

			control.finishPermits.release();
			assertEquals("done 1", firstReader.readLine());

			assertEquals("started 2", secondReader.readLine());
			control.finishPermits.release();
			assertEquals("done 2", secondReader.readLine());
			assertEquals(1, server.getFreeThreads());
		} finally {
			control.finishPermits.release(10);
			server.shutdown();
		}
	}

	@Test
	void shutdownForcesActiveConnectionAfterConfiguredTimeout() throws Exception {
		ProcessorControl control = new ProcessorControl();
		StartedServer startedServer = startServer(1, control, "0");
		CTIServer server = startedServer.server;

		try (Socket socket = connect(startedServer.port); BufferedReader reader = reader(socket)) {
			assertEquals("started 1", reader.readLine());

			ExecutorService shutdownThread = Executors.newSingleThreadExecutor();
			Future<?> shutdown = shutdownThread.submit(server::shutdown);
			try {
				shutdown.get(5, TimeUnit.SECONDS);
				assertNull(reader.readLine());
				assertEquals(1, server.getFreeThreads());
			} finally {
				control.finishPermits.release(10);
				shutdownThread.shutdownNow();
			}
		} finally {
			server.shutdown();
		}
	}

	@Test
	void forcedShutdownClosesSocketBlockedInRead() throws Exception {
		StartedServer startedServer = startServer(1, new ReadBlockingProtocolHandler(), "0");
		CTIServer server = startedServer.server;

		try (Socket socket = connect(startedServer.port, "READ");
				BufferedReader reader = reader(socket)) {
			assertEquals("started read", reader.readLine());

			ExecutorService shutdownThread = Executors.newSingleThreadExecutor();
			Future<?> shutdown = shutdownThread.submit(server::shutdown);
			try {
				shutdown.get(5, TimeUnit.SECONDS);
				assertNull(reader.readLine());
				assertEquals(1, server.getFreeThreads());
			} finally {
				shutdownThread.shutdownNow();
			}
		} finally {
			server.shutdown();
		}
	}

	private static StartedServer startServer(int maxThreads, ProcessorControl control) throws IOException {
		return startServer(maxThreads, control, null);
	}

	private static StartedServer startServer(int maxThreads, ProcessorControl control, String shutdownTimeoutSeconds)
			throws IOException {
		return startServer(maxThreads, new BlockingProtocolHandler(control), shutdownTimeoutSeconds);
	}

	private static StartedServer startServer(int maxThreads, ProtocolHandler handler, String shutdownTimeoutSeconds)
			throws IOException {
		int port = freePort();
		CTIServer server = new CTIServer();
		Properties props = new Properties();
		props.setProperty("jp.cssj.cssjd.port", String.valueOf(port));
		props.setProperty("jp.cssj.cssjd.maxThreads", String.valueOf(maxThreads));
		props.setProperty("jp.cssj.cssjd.timeout", "5");
		if (shutdownTimeoutSeconds != null) {
			props.setProperty("jp.cssj.cssjd.shutdownTimeout", shutdownTimeoutSeconds);
		}
		server.setConfigFile(new File("."), props);
		server.setProtocolHandlers(new ProtocolHandler[] { handler });
		server.startup();
		return new StartedServer(server, port);
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}

	private static Socket connect(int port) throws IOException {
		return connect(port, "TEST");
	}

	private static Socket connect(int port, String firstLine) throws IOException {
		Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
		socket.setSoTimeout(5000);
		OutputStream out = socket.getOutputStream();
		out.write((firstLine + "\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
		return socket;
	}

	private static BufferedReader reader(Socket socket) throws IOException {
		return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
	}

	private static final class BlockingProtocolHandler implements ProtocolHandler {
		private final ProcessorControl control;

		BlockingProtocolHandler(ProcessorControl control) {
			this.control = control;
		}

		public boolean accepts(String firstLine) {
			return firstLine.equals("TEST");
		}

		public ProtocolProcessor newProcesor() {
			return new BlockingProtocolProcessor(this.control);
		}
	}

	private static final class BlockingProtocolProcessor implements ProtocolProcessor {
		private final ProcessorControl control;

		BlockingProtocolProcessor(ProcessorControl control) {
			this.control = control;
		}

		public void process(Socket socket, InputStream in, OutputStream out, String firstLine) throws IOException {
			int id = this.control.started.incrementAndGet();
			out.write(("started " + id + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			try {
				if (!this.control.finishPermits.tryAcquire(5, TimeUnit.SECONDS)) {
					throw new IOException("Timed out waiting for test release");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
			out.write(("done " + id + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		}

		public void message(short code, String[] args, String message) throws IOException {
			// Test protocol does not emit server messages.
		}

		public void close() throws IOException {
			// Socket ownership stays with CTIServer.
		}
	}

	private static final class ReadBlockingProtocolHandler implements ProtocolHandler {
		public boolean accepts(String firstLine) {
			return firstLine.equals("READ");
		}

		public ProtocolProcessor newProcesor() {
			return new ReadBlockingProtocolProcessor();
		}
	}

	private static final class ReadBlockingProtocolProcessor implements ProtocolProcessor {
		public void process(Socket socket, InputStream in, OutputStream out, String firstLine) throws IOException {
			out.write("started read\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
			in.read();
		}

		public void message(short code, String[] args, String message) throws IOException {
			// Test protocol does not emit server messages.
		}

		public void close() throws IOException {
			// Socket ownership stays with CTIServer.
		}
	}

	private static final class ProcessorControl {
		final AtomicInteger started = new AtomicInteger();
		final Semaphore finishPermits = new Semaphore(0);
	}

	private static final class StartedServer {
		final CTIServer server;
		final int port;

		StartedServer(CTIServer server, int port) {
			this.server = server;
			this.port = port;
		}
	}
}
