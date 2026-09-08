package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Green controls proving the test wire and temporary certificate setup work. */
class LocalTlsFixtureTest {
    @TempDir static Path temporary;
    private static LocalTls tls;

    @BeforeAll static void identity() throws Exception {
        tls = new LocalTls(LocalTls.generateIdentity(temporary));
    }

    @Test void realServerFlightCanBeCoalescedAndSplitInsideRecords() throws Exception {
        SSLEngine server = tls.server.createSSLEngine();
        server.setUseClientMode(false);
        server.setEnabledProtocols(new String[] { "TLSv1.3" });
        SSLEngine client = tls.client(true).createSSLEngine();
        client.setUseClientMode(true);
        client.setEnabledProtocols(new String[] { "TLSv1.3" });
        client.beginHandshake();
        ByteBuffer hello = ByteBuffer.allocate(65536);
        client.wrap(ByteBuffer.allocate(0), hello);
        hello.flip();
        try (BoundarySocketChannel wire = new BoundarySocketChannel(server)) {
            wire.write(hello);
            byte[] flight = wire.takeFlight();
            int records = 0;
            for (int position = 0; position < flight.length; records++) {
                assertTrue(position + 5 <= flight.length, "Complete TLS header");
                int length = ((flight[position + 3] & 255) << 8) | (flight[position + 4] & 255);
                position += 5 + length;
                assertTrue(position <= flight.length, "Complete TLS record");
            }
            assertTrue(records > 1, "Real server must produce multiple records");
            assertTrue(wire.server.events.stream().anyMatch(e -> e.result.bytesProduced() > 0));

            ByteBuffer copy = ByteBuffer.allocate(flight.length);
            wire.enqueue(flight);
            assertEquals(flight.length, wire.read(copy), "Whole flight in exactly one client read");
            assertArrayEquals(flight, copy.array());
            copy.clear();
            wire.enqueueSplit(flight, 1, 4, flight.length - 1);
            assertEquals(1, wire.read(copy));
            assertEquals(3, wire.read(copy));
            assertEquals(flight.length - 5, wire.read(copy));
            assertEquals(1, wire.read(copy));
            assertArrayEquals(flight, copy.array());
            assertEquals(Arrays.asList(flight.length, 1, 3, flight.length - 5, 1), wire.reads);
        }
    }

    @Test void insecureTrueAcceptsEphemeralSelfSignedCertificate() throws Exception {
        exchange(tls.client(true), true, false);
    }

    @Test void insecureFalseRejectsUntrustedEphemeralCertificate() throws Exception {
        exchange(tls.client(false), false, true);
    }

    @Test void insecureFalseWithExplicitTrustAndMatchingIpSanSucceeds() throws Exception {
        exchange(tls.trustedClient, true, true);
    }

    private void exchange(SSLContext context, boolean succeeds, boolean verifyName) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SSLServerSocket listener = tls.listen("TLSv1.2")) {
            Future<Boolean> peer = worker.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(3000);
                    try {
                        socket.startHandshake();
                    } catch (SSLException e) {
                        if (!succeeds) { return false; }
                        throw e;
                    }
                    int value = socket.getInputStream().read();
                    assertEquals(73, value);
                    socket.getOutputStream().write(value);
                    return true;
                }
            });
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 3000);
                socket.setSoTimeout(3000);
                socket.setEnabledProtocols(new String[] { "TLSv1.2" });
                if (verifyName) {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                }
                if (succeeds) {
                    socket.startHandshake();
                    socket.getOutputStream().write(73);
                    assertEquals(73, socket.getInputStream().read());
                } else {
                    SSLException error = assertThrows(SSLException.class, socket::startHandshake);
                    assertTrue(hasCertificateCause(error), "Must fail certificate validation: " + error);
                }
            }
            assertEquals(succeeds, peer.get(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "Loopback server thread reaped");
        }
    }

    private static boolean hasCertificateCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.security.cert.CertificateException) { return true; }
        }
        return false;
    }
}
