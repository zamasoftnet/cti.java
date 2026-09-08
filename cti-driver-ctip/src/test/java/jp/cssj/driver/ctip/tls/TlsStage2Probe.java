package jp.cssj.driver.ctip.tls;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Paths;
import java.security.Security;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import jp.cssj.driver.ctip.CTIPDriver;
import jp.cssj.driver.ctip.common.ChannelIO;
import jp.cssj.driver.ctip.v1.V1ContentProducer;
import jp.cssj.driver.ctip.v1.V1Session;
import jp.cssj.driver.ctip.v2.*;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;

public final class TlsStage2Probe {
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tls-probe"); t.setDaemon(true); return t;
    });

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        System.setProperty("jp.cssj.driver.tls.trust", "true");
        if (scenario.equals("key-update") || scenario.equals("control-wait")) {
            Security.setProperty("jdk.tls.keyLimits", "AES/GCM/NoPadding KeyUpdate 1024");
        }
        LocalTls tls = new LocalTls(Paths.get(args[1]));
        try {
            switch (scenario) {
            case "buffers": case "clean-eof": case "truncated-eof": case "key-update": case "engine-faults": case "handshake-task": case "close-order":
                boundary(tls, scenario); break;
            case "buffered-socket": bufferedSocket(tls); break;
            case "control-wait": controlWait(tls); break;
            case "idle-read-close": case "blocked-close": shutdown(tls, scenario); break;
            case "preconnect-close":
                SocketChannel wire = SocketChannel.open();
                TLSSocketChannel client = new TLSSocketChannel(wire);
                client.close(); client.close();
                check(!wire.isOpen(), "preconnect close leaked socket");
                expectClosed(client); break;
            case "handshake-timeout": case "handshake-eof": case "handshake-trust":
                handshakeFailure(tls, scenario); break;
            case "reject-v1": rejectV1(); break;
            case "plain-v1": plainV1(); break;
            default: protocol(tls, scenario); break;
            }
            System.out.println("STAGE2_OK " + scenario);
        } finally {
            WORKERS.shutdownNow();
            check(WORKERS.awaitTermination(3, TimeUnit.SECONDS), "worker leaked");
        }
    }

    static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    static byte[] bytes(int count) {
        byte[] result = new byte[count];
        for (int i = 0; i < count; i++) { result[i] = (byte) (i * 31 + 7); }
        return result;
    }
    static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static Object get(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void boundary(LocalTls tls, String scenario) throws Exception {
        SSLEngine server = tls.server.createSSLEngine();
        server.setUseClientMode(false);
        server.setEnabledProtocols(new String[] { "TLSv1.3" });
        server.setEnabledCipherSuites(new String[] { "TLS_AES_128_GCM_SHA256" });
        try (BoundarySocketChannel wire = new BoundarySocketChannel(server)) {
            TLSSocketChannel client = new TLSSocketChannel(wire);
            wire.observe(client, false);
            if (scenario.equals("handshake-task")) {
                wire.decorator = engine -> new RecordingEngine(engine, "failing-task", false) {
                    public HandshakeStatus getHandshakeStatus() { return HandshakeStatus.NEED_TASK; }
                    public Runnable getDelegatedTask() { return () -> { throw new IllegalStateException("task failed"); }; }
                };
                try { client.connect(new InetSocketAddress("127.0.0.1", 1)); throw new AssertionError("task failure accepted"); }
                catch (IllegalStateException expected) {
                    check(!client.isOpen() && !wire.isOpen(), "task failure leaked connection");
                }
                return;
            }
            client.connect(new InetSocketAddress("127.0.0.1", 1));
            ChannelIO io = new ChannelIO(client, 500);
            if (scenario.equals("close-order")) {
                wire.received.reset();
                wire.writeLimits.add(0); wire.writeLimits.add(7);
                byte[] data = bytes(4096);
                check(client.write(ByteBuffer.wrap(data)) == data.length, "close test did not consume plaintext");
                check(client.hasPendingOutbound(), "close test did not retain ciphertext");
                client.close();
                check(Arrays.equals(data, wire.received.toByteArray()), "close overtook pending DATA");
                check(wire.server.isInboundDone(), "client close_notify missing");
                check(!wire.isOpen(), "close did not release wire");
                return;
            }
            if (scenario.equals("engine-faults")) { engineFaults(client, wire.clientEngine); return; }
            if (scenario.equals("key-update")) {
                // Consume the TLS 1.3 ticket without application bytes, then induce a real KeyUpdate.
                check(client.read(ByteBuffer.allocate(1)) == 0, "ticket delivered application data");
                wire.clientEngine.events.clear();
                byte[] data = bytes(2048);
                wire.enqueue(wire.serverData(data));
                check(Arrays.equals(data, io.readBytes(data.length)), "KeyUpdate data mismatch");
                check(client.read(ByteBuffer.allocate(1)) == 0, "control-only read must return zero");
                client.flushOutbound();
                boolean update = false;
                for (RecordingEngine.Event event : wire.clientEngine.events) {
                    if (event.operation.endsWith("unwrap") && event.result.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
                        update = true;
                    }
                }
                check(update, "real inbound KeyUpdate not observed");
                check(wire.server.getHandshakeStatus() != HandshakeStatus.NEED_UNWRAP, "KeyUpdate response missing");
                wire.enqueue(wire.serverData(new byte[] { 91 }));
                check(io.readBytes(1)[0] == 91, "application data after KeyUpdate lost");
                return;
            }
            byte[] expected = bytes(4101);
            ByteArrayOutputStream flight = new ByteArrayOutputStream();
            flight.write(wire.serverData(Arrays.copyOfRange(expected, 0, 2000)));
            flight.write(wire.serverData(Arrays.copyOfRange(expected, 2000, expected.length)));
            if (scenario.equals("clean-eof")) { flight.write(wire.serverClose()); }
            wire.enqueueSplit(flight.toByteArray(), 1, 4, flight.size() - 2);
            int reads = wire.readCalls;
            check(client.read(ByteBuffer.allocate(0)) == 0 && wire.readCalls == reads, "empty dst performed read");
            // Force both network and application growth, without replacing real JSSE.
            if (scenario.equals("buffers")) {
                set(client, TLSSocketChannel.class, "peerNetData", ByteBuffer.allocate(2));
                ByteBuffer tiny = ByteBuffer.allocate(1); tiny.flip();
                set(client, TLSSocketChannel.class, "peerAppData", tiny);
                ByteBuffer empty = (ByteBuffer) get(client, TLSSocketChannel.class, "peerNetData"); empty.flip();
                ByteBuffer tinyOutput = ByteBuffer.allocate(1); tinyOutput.flip();
                set(client, TLSSocketChannel.class, "netData", tinyOutput);
                wire.received.reset();
                check(client.write(ByteBuffer.wrap(new byte[] { 17, 18 })) == 2, "wrap overflow contract");
                client.flushOutbound();
                check(Arrays.equals(new byte[] { 17, 18 }, wire.received.toByteArray()), "wrap buffer expansion lost data");
            }
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            while (actual.size() < expected.length) {
                int n = Math.min(3, expected.length - actual.size());
                actual.write(io.readBytes(n));
            }
            check(Arrays.equals(expected, actual.toByteArray()), "record fragments/plaintext lost");
            if (scenario.equals("clean-eof")) {
                check(client.read(ByteBuffer.allocate(1)) == -1, "close_notify was not EOF");
            } else if (scenario.equals("truncated-eof")) {
                wire.eof = true;
                try { client.read(ByteBuffer.allocate(1)); throw new AssertionError("truncated TLS accepted"); }
                catch (SSLException expectedFailure) { }
            }
        }
    }

    private static void engineFaults(TLSSocketChannel client, SSLEngine engine) throws Exception {
        RecordingEngine stalled = new RecordingEngine(engine, "stalled", false) {
            public SSLEngineResult wrap(ByteBuffer[] src, int offset, int length, ByteBuffer dst) {
                return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
            }
        };
        set(client, TLSSocketChannel.class, "engine", stalled);
        try { client.write(ByteBuffer.wrap(new byte[] { 1 })); throw new AssertionError("stalled wrap accepted"); }
        catch (SSLException expected) { }
        RecordingEngine task = new RecordingEngine(engine, "task", false) {
            public HandshakeStatus getHandshakeStatus() { return HandshakeStatus.NEED_TASK; }
            public Runnable getDelegatedTask() { return () -> { throw new IllegalStateException("task failure"); }; }
        };
        set(client, TLSSocketChannel.class, "engine", task);
        try { client.flushOutbound(); throw new AssertionError("task failure swallowed"); }
        catch (IllegalStateException expected) { }
        set(client, TLSSocketChannel.class, "engine", new RecordingEngine(engine, "close-fault", false) {
            public SSLEngineResult wrap(ByteBuffer[] src, int offset, int length, ByteBuffer dst) throws SSLException {
                throw new SSLException("wrap failed closing TLS");
            }
        });
        try { client.close(); throw new AssertionError("close wrap failure swallowed"); }
        catch (SSLException expected) { }
        check(!((SocketChannel) get(client, TLSSocketChannel.class, "channel")).isOpen(), "wrap failure leaked socket");
    }

    private static void bufferedSocket(LocalTls tls) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        byte[] data = bytes(4096);
        try (SSLServerSocket listener = tls.listen("TLSv1.2"); SocketChannel wire = SocketChannel.open()) {
            Future<?> peer = WORKERS.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.getOutputStream().write(data); socket.getOutputStream().flush();
                    check(release.await(3, TimeUnit.SECONDS), "buffered peer not released");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            TLSSocketChannel client = new TLSSocketChannel(wire);
            try {
                client.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
                ChannelIO io = new ChannelIO(client, 500);
                check(io.readBytes(1)[0] == data[0], "first byte");
                check(client.hasPlaintext(), "no internal plaintext to test");
                try (Selector selector = Selector.open()) {
                    client.register(selector, SelectionKey.OP_READ);
                    check(selector.selectNow() == 0, "raw socket still readable");
                }
                check(Arrays.equals(Arrays.copyOfRange(data, 1, data.length), io.readBytes(data.length - 1)),
                        "internal plaintext waited on selector");
            } finally { release.countDown(); client.close(); }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    private static void expectClosed(TLSSocketChannel client) throws Exception {
        try { client.read(ByteBuffer.allocate(1)); throw new AssertionError("read after close"); }
        catch (ClosedChannelException expected) { }
        try { client.write(ByteBuffer.wrap(new byte[] { 1 })); throw new AssertionError("write after close"); }
        catch (ClosedChannelException expected) { }
    }

    private static void controlWait(LocalTls tls) throws Exception {
        CountDownLatch startRead = new CountDownLatch(1);
        try (SSLServerSocket listener = tls.listen("TLSv1.3"); SocketChannel wire = SocketChannel.open()) {
            listener.setEnabledCipherSuites(new String[] { "TLS_AES_128_GCM_SHA256" });
            Future<?> peer = WORKERS.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.startHandshake();
                    check(startRead.await(2, TimeUnit.SECONDS), "reader not started");
                    Thread.sleep(350);
                    socket.getOutputStream().write(63); socket.getOutputStream().flush();
                    check(socket.getInputStream().read() == 62, "control exchange blocked application write");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            TLSSocketChannel client = new TLSSocketChannel(wire);
            client.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
            RecordingEngine engine = new RecordingEngine((SSLEngine) get(client, TLSSocketChannel.class, "engine"), "control", false);
            set(client, TLSSocketChannel.class, "engine", engine);
            ChannelIO io = new ChannelIO(client, 1500);
            long cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            long wall = System.nanoTime();
            startRead.countDown();
            check(io.readBytes(1)[0] == 63, "delayed application byte missing");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wall);
            long cpuElapsed = TimeUnit.NANOSECONDS.toMillis(ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpu);
            check(elapsed >= 250 && cpuElapsed < 150, "control-only wait spun: wall=" + elapsed + " cpu=" + cpuElapsed);
            boolean response = false;
            for (RecordingEngine.Event event : engine.events) {
                response |= event.operation.endsWith("wrap") && event.result.bytesProduced() > 0;
            }
            check(response, "Ticket/KeyUpdate control response not observed");
            io.writeAll(ByteBuffer.wrap(new byte[] { 62 }));
            io.close(); peer.get(2, TimeUnit.SECONDS);
            System.out.println("CONTROL_WAIT wallMs=" + elapsed + " cpuMs=" + cpuElapsed);
        }
    }

    private static void shutdown(LocalTls tls, String scenario) throws Exception {
        CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        try (SSLServerSocket listener = tls.listen("TLSv1.3"); SocketChannel wire = SocketChannel.open()) {
            Future<?> peer = WORKERS.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setReceiveBufferSize(1024);
                    socket.startHandshake(); ready.countDown();
                    check(release.await(5, TimeUnit.SECONDS), "shutdown peer not released");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            TLSSocketChannel client = new TLSSocketChannel(wire);
            try {
                wire.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
                client.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
                check(ready.await(2, TimeUnit.SECONDS), "peer handshake");
                ChannelIO io = new ChannelIO(client, 0);
                if (scenario.equals("blocked-close")) {
                    ByteBuffer source = ByteBuffer.wrap(bytes(8 * 1024 * 1024));
                    for (int i = 0; i < 10000 && !client.hasPendingOutbound(); i++) { client.write(source); }
                    check(client.hasPendingOutbound(), "did not force pending ciphertext");
                }
                CountDownLatch reading = new CountDownLatch(1);
                Future<?> reader = WORKERS.submit(() -> {
                    reading.countDown();
                    try { io.readBytes(1); throw new AssertionError("unexpected plaintext"); }
                    catch (IOException expected) { }
                });
                check(reading.await(1, TimeUnit.SECONDS), "reader not started");
                Thread.sleep(100);
                long start = System.nanoTime();
                try { client.close(); } catch (SocketTimeoutException expected) { }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(elapsed < 2200, "close blocked: " + elapsed);
                check(!wire.isOpen(), "close leaked socket");
                reader.get(2, TimeUnit.SECONDS);
                client.close(); expectClosed(client);
            } finally { release.countDown(); wire.close(); }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    private static void handshakeFailure(LocalTls tls, String scenario) throws Exception {
        boolean trust = scenario.equals("handshake-trust");
        try (ServerSocket listener = trust ? tls.listen("TLSv1.3") : new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                SocketChannel wire = SocketChannel.open()) {
            CountDownLatch release = new CountDownLatch(1);
            Future<?> peer = WORKERS.submit(() -> {
                try (Socket socket = listener.accept()) {
                    if (trust) {
                        try { ((SSLSocket) socket).startHandshake(); } catch (IOException expected) { }
                    } else if (scenario.equals("handshake-timeout")) { release.await(2, TimeUnit.SECONDS); }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            TLSSocketChannel client = new TLSSocketChannel(wire);
            if (trust) { System.setProperty("jp.cssj.driver.tls.trust", "false"); }
            try {
                client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()), "localhost", listener.getLocalPort(), trust ? 2000 : 200);
                throw new AssertionError("handshake should fail");
            } catch (IOException expected) {
                if (scenario.equals("handshake-timeout")) { check(expected instanceof SocketTimeoutException, "wrong timeout"); }
                check(!wire.isOpen() && !client.isOpen(), "failed connect leaked channel");
            } finally { release.countDown(); client.close(); }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    // CTIP framing and reentry tests below use real socket readiness, with both plain and TLS transports.
    private static byte[] frame(byte type, byte[] body) {
        ByteBuffer packet = ByteBuffer.allocate(body.length + 5);
        packet.putInt(body.length + 1).put(type).put(body);
        return packet.array();
    }
    private static byte[] readFrame(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 1 || size > 100000) { throw new IOException("bad frame size " + size); }
        byte[] data = new byte[size]; input.readFully(data); return data;
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int ch; while ((ch = input.read()) != '\n') {
            if (ch < 0) { throw new EOFException(); } bytes.write(ch);
        }
        return new String(bytes.toByteArray(), "UTF-8");
    }
    private static final class Session extends V2Session {
        Session(URI uri) throws IOException { super(uri, "UTF-8", "probe", "fixture"); }
        V2RequestConsumer open() throws IOException { init(); return request; }
        void converting() { state = 2; }
        void finishResponses() throws IOException { next(); }
    }

    /** A real selectable socket with deterministic 0/partial application writes. */
    private static final class LimitedChannel extends TLSSocketChannel {
        final ByteChannel delegate;
        final TLSSocketChannel tls;
        int writes;
        LimitedChannel(ByteChannel delegate) throws Exception {
            super(delegate instanceof TLSSocketChannel
                    ? (SocketChannel) get(delegate, TLSSocketChannel.class, "channel") : (SocketChannel) delegate);
            this.delegate = delegate;
            this.tls = delegate instanceof TLSSocketChannel ? (TLSSocketChannel) delegate : null;
        }
        public int write(ByteBuffer src) throws IOException {
            if (++writes % 3 == 0) { return 0; }
            int limit = src.limit();
            src.limit(Math.min(limit, src.position() + 127));
            try { return delegate.write(src); } finally { src.limit(limit); }
        }
        public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        public boolean flushOutbound() throws IOException { return tls == null || tls.flushOutbound(); }
        public boolean hasPendingOutbound() { return tls != null && tls.hasPendingOutbound(); }
        public boolean hasPlaintext() { return tls != null && tls.hasPlaintext(); }
        public int requiredOps() { return tls == null ? SelectionKey.OP_READ : tls.requiredOps(); }
        public int requiredWriteOps() { return tls == null ? SelectionKey.OP_WRITE : tls.requiredWriteOps(); }
        protected void implCloseChannel() throws IOException { delegate.close(); }
    }

    private static void protocol(LocalTls tls, String scenario) throws Exception {
        boolean secure = scenario.startsWith("tls");
        boolean reentry = scenario.contains("reentry");
        boolean rejectAuth = scenario.contains("auth-failure");
        boolean idleAbort = scenario.contains("idle-abort");
        CountDownLatch resourceCallback = new CountDownLatch(1), abortReceived = new CountDownLatch(1);
        CountDownLatch sentRequest = new CountDownLatch(1);
        List<Byte> types = Collections.synchronizedList(new ArrayList<Byte>());
        byte[] upload = bytes(V2Session.BUFFER_SIZE * 3 + 13);
        byte[] resource = bytes(V2Session.BUFFER_SIZE + 37);
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        ByteArrayOutputStream resourceUploaded = new ByteArrayOutputStream();
        try (ServerSocket listener = secure ? tls.listen("TLSv1.3") : new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<?> peer = WORKERS.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(4000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    check(line(input).equals("CTIP/2.0 UTF-8"), "v2 header");
                    check(line(input).equals("PLAIN: probe fixture"), "authentication frame");
                    output.write((rejectAuth ? "NG \n" : "OK \n").getBytes("UTF-8")); output.flush();
                    if (rejectAuth) { check(input.read() == -1, "authentication failure leaked socket"); return; }
                    boolean requested = false, inResource = false;
                    while (true) {
                        byte[] packet = readFrame(input); byte type = packet[0]; types.add(type);
                        if (type == V2ClientPackets.DATA) {
                            (inResource ? resourceUploaded : uploaded).write(packet, 1, packet.length - 1);
                            if (reentry && !requested) {
                                requested = true;
                                byte[] uri = "urn:fixture:resource".getBytes("UTF-8");
                                ByteBuffer body = ByteBuffer.allocate(uri.length + 2); body.putShort((short) uri.length).put(uri);
                                byte[] request = frame(V2ServerPackets.RESOURCE_REQUEST, body.array());
                                // Split header and body into separate TLS records / TCP writes.
                                output.write(request, 0, 2); output.flush();
                                Thread.sleep(50);
                                output.write(request, 2, 4); output.flush();
                                Thread.sleep(50);
                                output.write(request, 6, request.length - 6); output.flush();
                                sentRequest.countDown();
                            }
                        } else if (type == V2ClientPackets.ABORT) {
                            abortReceived.countDown();
                            if (idleAbort) { output.write(frame(V2ServerPackets.NEXT, new byte[0])); output.flush(); }
                        }
                        else if (type == V2ClientPackets.START_RESOURCE) { inResource = true; }
                        else if (type == V2ClientPackets.SERVER_INFO) {
                            byte[] response = frame(V2ServerPackets.DATA, bytes(321));
                            for (int i = 0; i < response.length; i += 3) {
                                output.write(response, i, Math.min(3, response.length - i)); output.flush();
                            }
                            output.write(frame(V2ServerPackets.EOF, new byte[0])); output.flush();
                        } else if (type == V2ClientPackets.EOF) {
                            if (inResource) { inResource = false; }
                            else { output.write(frame(V2ServerPackets.NEXT, new byte[0])); output.flush(); }
                        } else if (type == V2ClientPackets.CLOSE) { break; }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            URI uri = new URI((secure ? "ctips" : "ctip") + "://127.0.0.1:" + listener.getLocalPort() + "/?version=2&timeout=2500");
            Session session = new Session(uri);
            if (rejectAuth) {
                try { session.open(); throw new AssertionError("authentication accepted"); }
                catch (SecurityException expected) { }
                peer.get(3, TimeUnit.SECONDS); return;
            }
            V2RequestConsumer request = session.open();
            if (reentry) {
                ChannelIO original = (ChannelIO) get(request, V2RequestConsumer.class, "io");
                ChannelIO limited = new ChannelIO(new LimitedChannel(original.getChannel()), 2500);
                set(request, V2RequestConsumer.class, "io", limited);
                set(get(session, V2Session.class, "producer"), V2ContentProducer.class, "io", limited);
            }
            try {
                byte[] info = new byte[321];
                new DataInputStream(session.getServerInfo(URI.create("urn:fixture:info"))).readFully(info);
                check(Arrays.equals(bytes(321), info), "v2 server info record boundaries");
                request.property("large", new String(new char[40000]).replace('\0', 'x'));
                if (reentry) {
                    session.setSourceResolver(new SourceResolver() {
                        public Source resolve(URI uri) throws IOException {
                            try {
                                Deque<?> queue = (Deque<?>) get(request, V2RequestConsumer.class, "packets");
                                Object head = queue.peek();
                                check(head != null && ((ByteBuffer) get(head, head.getClass(), "bytes")).hasRemaining(),
                                        "RESOURCE_REQUEST must interrupt a partial DATA frame");
                            } catch (ReflectiveOperationException e) { throw new IOException(e); }
                            catch (Exception e) { throw new IOException(e); }
                            resourceCallback.countDown();
                            try { check(abortReceived.await(2, TimeUnit.SECONDS), "abort blocked by resource callback"); }
                            catch (InterruptedException e) { throw new IOException(e); }
                            return new net.zamasoft.zstream.resolver.protocol.stream.StreamSource(uri,
                                    new ByteArrayInputStream(resource), "application/octet-stream", resource.length);
                        }
                        public void release(Source source) { }
                    });
                }
                request.startMain(URI.create("urn:fixture:main"), "text/plain", "UTF-8", upload.length);
                session.converting();
                if (idleAbort) {
                    CountDownLatch reading = new CountDownLatch(1);
                    Future<?> reader = WORKERS.submit(() -> {
                        reading.countDown();
                        try { session.finishResponses(); } catch (IOException e) { throw new RuntimeException(e); }
                    });
                    check(reading.await(1, TimeUnit.SECONDS), "v2 reader not started");
                    Thread.sleep(100);
                    session.abort((byte) 1);
                    reader.get(1, TimeUnit.SECONDS);
                } else {
                Future<?> abort = reentry ? WORKERS.submit(() -> {
                    try {
                        check(resourceCallback.await(3, TimeUnit.SECONDS), "resource callback missing");
                        session.abort((byte) 1);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }) : null;
                request.data(upload, 0, V2Session.BUFFER_SIZE);
                if (reentry) { check(sentRequest.await(2, TimeUnit.SECONDS), "resource request not sent"); }
                request.data(upload, V2Session.BUFFER_SIZE, upload.length - V2Session.BUFFER_SIZE);
                request.eof(); session.finishResponses();
                if (abort != null) { abort.get(3, TimeUnit.SECONDS); }
                }
            } finally { session.close(); }
            peer.get(3, TimeUnit.SECONDS);
            if (idleAbort) {
                check(Collections.frequency(types, V2ClientPackets.ABORT) == 1, "abort during blocking next");
                return;
            }
            check(Arrays.equals(upload, uploaded.toByteArray()), "DATA duplicated, lost or mixed");
            check(Collections.frequency(types, V2ClientPackets.DATA) == (reentry ? 6 : 4), "wrong DATA frame count: " + types);
            if (reentry) {
                check(Collections.frequency(types, V2ClientPackets.ABORT) == 1, "abort missing/duplicated");
                check(Collections.frequency(types, V2ClientPackets.START_RESOURCE) == 1, "resource callback reentry");
                check(Arrays.equals(resource, resourceUploaded.toByteArray()), "resource DATA duplicated, lost or mixed");
            }
        }
    }

    private static void rejectV1() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            URI uri = URI.create("ctips://127.0.0.1:" + listener.getLocalPort() + "/?version=1");
            try { new CTIPDriver().getSession(uri, Collections.<String, String>emptyMap()); throw new AssertionError("driver accepted TLS v1"); }
            catch (IOException | IllegalArgumentException expected) { }
            try { new V1Session(uri, "UTF-8", "probe", "fixture"); throw new AssertionError("session accepted TLS v1"); }
            catch (IOException | IllegalArgumentException expected) { }
            try { new V1ContentProducer(uri, "UTF-8"); throw new AssertionError("producer accepted TLS v1"); }
            catch (IOException | IllegalArgumentException expected) { }
            listener.setSoTimeout(150);
            try { listener.accept().close(); throw new AssertionError("TLS v1 connected"); }
            catch (SocketTimeoutException expected) { }
        }
    }

    private static void plainV1() throws Exception {
        // Exercise the legacy ChannelIO readiness API on the blocking input used by v1.
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                SocketChannel client = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
                Socket peer = listener.accept(); ChannelIOHolder holder = new ChannelIOHolder(client)) {
            ChannelIO io = holder.io;
            check(io.rwselect().isWritable(), "legacy rwselect");
            io.writeAll(ByteBuffer.wrap(new byte[] { 11, 12 }));
            check(peer.getInputStream().read() == 11 && peer.getInputStream().read() == 12, "legacy write");
            peer.getOutputStream().write(new byte[] { 0, 0, 0, 7 });
            check(io.readInt(ByteBuffer.allocate(4)) == 7, "legacy read");
        }
        byte[] uploaded = bytes(9000);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<?> peer = WORKERS.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    check(line(input).equals("CTIP/1.0 UTF-8"), "v1 protocol header");
                    check(readFrame(input)[0] == 1, "v1 property");
                    check(readFrame(input)[0] == 3, "v1 main");
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    while (data.size() < uploaded.length) {
                        byte[] packet = readFrame(input);
                        check(packet[0] == 4, "v1 DATA type"); data.write(packet, 1, packet.length - 1);
                    }
                    check(Arrays.equals(uploaded, data.toByteArray()), "v1 DATA regression");
                    check(input.readInt() == 0, "v1 end");
                    OutputStream out = socket.getOutputStream();
                    out.write(frame((byte) 1, new byte[0]));
                    out.write(new byte[4]); out.flush();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            V1ContentProducer producer = new V1ContentProducer(URI.create("ctip://127.0.0.1:" + listener.getLocalPort()), "UTF-8");
            jp.cssj.driver.ctip.v1.V1RequestConsumer request = producer.connect();
            request.property("input.include", "urn:fixture");
            request.main(URI.create("urn:fixture"), "text/plain", "UTF-8");
            request.write(uploaded, 0, uploaded.length); request.end();
            check(producer.next() && producer.getType() == 1, "v1 response");
            check(!producer.next(), "v1 response end");
            peer.get(2, TimeUnit.SECONDS);
        }
    }
    private static final class ChannelIOHolder implements AutoCloseable {
        final ChannelIO io;
        ChannelIOHolder(SocketChannel channel) throws IOException { io = new ChannelIO(channel, 500); }
        public void close() throws IOException { io.close(); }
    }
}
