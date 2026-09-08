package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stage 1 regression targets, retained as positive assertions after the stage 2 fix. */
class TlsRegressionTest {
    @TempDir static Path temporary;
    private static Path identity;

    @BeforeAll static void identity() throws Exception {
        identity = LocalTls.generateIdentity(temporary);
    }

    @Test void stage2Target_realTls13HandshakeMustComplete() throws Exception {
        Probe result = probe("tls13");
        Matcher read = Pattern.compile("WIRE_READ bytes=(\\d+)").matcher(result.output);
        Matcher hello = Pattern.compile("client.unwrap OK/NEED_TASK/(\\d+)/0 remaining=(\\d+)")
                .matcher(result.output);
        assertTrue(read.find() && hello.find(), "Must observe real ServerHello: " + result.output);
        int remaining = Integer.parseInt(hello.group(2));
        assertTrue(remaining > 0, "Subsequent TLS records must remain after ServerHello");
        assertEquals(Integer.parseInt(read.group(1)), Integer.parseInt(hello.group(1)) + remaining,
                "ServerHello and subsequent records must arrive in the same read");
        if (!result.completed) {
            assertTrue(result.output.contains("NO_PROGRESS_10000 client.unwrap OK/NEED_WRAP/0/0"),
                    "Timeout must identify the actual NEED_WRAP spin, not startup/I/O failure:\n" + result.output);
        }
        assertTrue(result.completed,
                "STAGE_2_TARGET TLS 1.3 handshake exceeded 10s: OK/NEED_WRAP/0/0 with input remaining; child reaped=true");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("CONNECTED TLSv1.3"), result.output);
    }

    @Test void stage2Target_scriptedNeedWrapMustLeaveUnwrapLoop() throws Exception {
        Probe result = probe("scripted");
        if (!result.completed) {
            assertTrue(result.output.contains("NO_PROGRESS_10000 client.unwrap OK/NEED_WRAP/0/0"), result.output);
        }
        assertTrue(result.completed,
                "STAGE_2_TARGET scripted NEED_WRAP/0/0 exceeded 10s; child reaped=true");
        assertEquals(0, result.exitCode, result.output);
    }

    @Test void stage2Target_zeroLowerWriteMustDeliverAllPlaintext() throws Exception {
        assertSuccessfulUpload("zero");
    }

    @Test void stage2Target_partialLowerWriteMustDeliverAllPlaintext() throws Exception {
        assertSuccessfulUpload("partial");
    }

    @Test void control_fullLowerWriteDeliversAllPlaintext() throws Exception {
        assertSuccessfulUpload("full");
    }

    @Test void control_productionChannelTalksToLocalSslServerSocket() throws Exception {
        assertSuccessfulUpload("socket");
    }

    private void assertSuccessfulUpload(String scenario) throws Exception {
        Probe result = probe(scenario);
        assertTrue(result.completed, "Unexpected upload timeout; child reaped=true\n" + result.output);
        Matcher failure = Pattern.compile("STAGE_2_TARGET[^\\r\\n]*").matcher(result.output);
        assertEquals(0, result.exitCode, failure.find() ? failure.group() : result.output);
        assertTrue(result.output.contains("PROBE_OK"), result.output);
    }

    private Probe probe(String scenario) throws Exception {
        Path log = temporary.resolve(scenario + ".log");
        Process child = new ProcessBuilder(LocalTls.javaTool("java"), "-Xmx96m", "-cp",
                System.getProperty("tls.test.classpath"), TlsProbe.class.getName(), scenario, identity.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long start = System.nanoTime();
        boolean completed;
        try {
            completed = child.waitFor(10, TimeUnit.SECONDS);
        } finally {
            // destroyForcibly does not rely on interruptible SSLEngine/monitor code.
            LocalTls.stop(child);
        }
        assertFalse(child.isAlive(), "Child must be dead before asserting a regression failure");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String output = new String(Files.readAllBytes(log), "UTF-8");
        System.out.println("PROBE " + scenario + " completed=" + completed + " reaped=" + !child.isAlive()
                + " elapsedMs=" + elapsedMs + " exit=" + child.exitValue());
        System.out.println(output);
        return new Probe(completed, child.exitValue(), output);
    }

    private static final class Probe {
        final boolean completed;
        final int exitCode;
        final String output;
        Probe(boolean completed, int exitCode, String output) {
            this.completed = completed;
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
