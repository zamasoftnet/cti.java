package jp.cssj.driver.ctip.tls;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Ephemeral loopback identity; no stored key or external certificate service. */
final class LocalTls {
    private static final char[] PASSWORD = "ephemeral-test-only".toCharArray();
    final SSLContext server;
    final SSLContext trustedClient;

    static Path generateIdentity(Path directory) throws Exception {
        Path identity = directory.resolve("identity.p12");
        Path log = directory.resolve("keytool.log");
        Process process = new ProcessBuilder(javaTool("keytool"), "-genkeypair",
                "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA", "-dname", "CN=localhost", "-validity", "2",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1,ip:::1",
                "-storetype", "PKCS12", "-keystore", identity.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Ephemeral keytool exceeded 20 seconds");
            }
            if (process.exitValue() != 0) {
                throw new AssertionError("keytool failed: " + new String(Files.readAllBytes(log), "UTF-8"));
            }
        } finally {
            stop(process);
        }
        return identity;
    }

    LocalTls(Path identity) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(identity)) {
            keys.load(input, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD);
        server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("loopback", keys.getCertificate("loopback"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        trustedClient = SSLContext.getInstance("TLS");
        trustedClient.init(null, tmf.getTrustManagers(), null);
    }

    SSLContext client(boolean insecure) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, insecure ? new TrustManager[] { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } } : null, null);
        return context;
    }

    SSLServerSocket listen(String protocol) throws Exception {
        SSLServerSocket socket = (SSLServerSocket) server.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        socket.setEnabledProtocols(new String[] { protocol });
        socket.setSoTimeout(3000);
        return socket;
    }

    static String javaTool(String name) {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        Path home = Paths.get(System.getProperty("java.home"));
        Path tool = home.resolve("bin").resolve(name + suffix);
        if (!Files.isRegularFile(tool) && home.getParent() != null) {
            tool = home.getParent().resolve("bin").resolve(name + suffix); // Java 8 jre home
        }
        return tool.toString();
    }

    static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.isAlive()) {
            throw new AssertionError("Child process was not reaped within 5 seconds");
        }
    }
}
