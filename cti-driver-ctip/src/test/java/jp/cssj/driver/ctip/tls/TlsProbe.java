package jp.cssj.driver.ctip.tls;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import jp.cssj.driver.ctip.v2.TLSSocketChannel;

/** Child JVM entry point. A stuck production monitor never prevents parent-side cleanup. */
public final class TlsProbe {
    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        boolean handshake = scenario.equals("tls13") || scenario.equals("scripted");
        System.setProperty("jp.cssj.driver.tls.trust", "true");
        System.setProperty("jp.cssj.driver.tls.insecure", "true");
        LocalTls tls = new LocalTls(Paths.get(args[1]));
        if (scenario.equals("socket")) {
            socketExchange(tls);
            System.out.println("PROBE_OK");
            return;
        }
        SSLEngine server = tls.server.createSSLEngine();
        server.setUseClientMode(false);
        server.setEnabledProtocols(new String[] { scenario.equals("tls13") ? "TLSv1.3" : "TLSv1.2" });
        try (BoundarySocketChannel wire = new BoundarySocketChannel(server)) {
            TLSSocketChannel client = new TLSSocketChannel(wire);
            wire.observe(client, scenario.equals("scripted"));
            System.out.println("CONNECT_START " + scenario);
            System.out.flush();
            if (!client.connect(new InetSocketAddress("127.0.0.1", 1))) {
                throw new AssertionError("In-memory connect failed");
            }
            System.out.println("CONNECTED " + server.getSession().getProtocol());
            if (!handshake) {
                checkUpload(client, wire, scenario);
            }
            // Close only the wire; production TLS close has separate stage 2 defects.
        }
        System.out.println("PROBE_OK");
    }

    private static void socketExchange(LocalTls tls) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SSLServerSocket listener = tls.listen("TLSv1.2"); SocketChannel wire = SocketChannel.open()) {
            Future<Integer> peer = worker.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(3000);
                    int value = socket.getInputStream().read();
                    socket.getOutputStream().write(value);
                    return value;
                }
            });
            TLSSocketChannel client = new TLSSocketChannel(wire);
            client.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
            client.write(ByteBuffer.wrap(new byte[] { 73 }));
            ByteBuffer echo = ByteBuffer.allocate(1);
            while (echo.hasRemaining()) {
                if (client.read(echo) < 0) { throw new AssertionError("Premature TLS EOF"); }
            }
            if (echo.get(0) != 73 || peer.get(5, TimeUnit.SECONDS) != 73) {
                throw new AssertionError("Production channel / SSLServerSocket echo mismatch");
            }
        } finally {
            worker.shutdownNow();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Loopback peer not reaped");
            }
        }
    }

    private static void checkUpload(TLSSocketChannel client, BoundarySocketChannel wire, String scenario)
            throws Exception {
        byte[] expected = new byte[4096];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 31 + 7);
        }
        wire.received.reset();
        wire.writes.clear();
        int limit = scenario.equals("zero") ? 0 : scenario.equals("partial") ? 7 : Integer.MAX_VALUE;
        wire.writeLimits.add(limit);
        ByteBuffer source = ByteBuffer.wrap(expected);
        // Same completion condition as ChannelIO.writeAll / V2RequestConsumer.flush.
        for (int attempt = 0; source.hasRemaining() && attempt < 100; attempt++) {
            int start = source.position();
            int count = client.write(source);
            if (count != source.position() - start) { throw new AssertionError("write contract"); }
        }
        if (source.hasRemaining()) {
            throw new AssertionError("Upload failed to consume plaintext");
        }
        int firstWrite = wire.writes.get(0);
        if (limit != Integer.MAX_VALUE && firstWrite != limit) {
            throw new AssertionError("Wire did not apply requested write limit");
        }
        // No subsequent application data. An empty retry gives retained ciphertext an
        // opportunity to drain; current write() clears and discards it instead.
        for (int attempt = 0; wire.received.size() < expected.length && attempt < 10; attempt++) {
            client.flushOutbound();
        }
        byte[] actual = wire.received.toByteArray();
        System.out.println("UPLOAD consumed=" + source.position() + " received=" + actual.length
                + " firstLowerWrite=" + firstWrite + " lowerWrites=" + wire.writes);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("STAGE_2_TARGET ciphertext lost after plaintext consumed: expected="
                    + expected.length + " received=" + actual.length + " firstLowerWrite=" + firstWrite);
        }
    }
}
