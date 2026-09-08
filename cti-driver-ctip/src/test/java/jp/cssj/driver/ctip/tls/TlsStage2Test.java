package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every concurrency/state-machine probe has a parent-enforced process deadline. */
class TlsStage2Test {
    @TempDir static Path temporary;
    static Path identity;
    @BeforeAll static void setup() throws Exception { identity = LocalTls.generateIdentity(temporary); }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "buffers", "clean-eof", "truncated-eof", "key-update", "close-order", "buffered-socket", "control-wait",
            "idle-read-close", "blocked-close", "preconnect-close", "handshake-timeout", "handshake-eof",
            "handshake-trust", "handshake-task", "engine-faults", "plain-v2", "tls-v2", "plain-v1", "reject-v1",
            "plain-reentry-abort", "tls-reentry-abort", "plain-idle-abort", "tls-idle-abort", "plain-auth-failure", "tls-auth-failure" })
    void regression(String scenario) throws Exception {
        Path log = temporary.resolve(scenario + ".log");
        Process child = new ProcessBuilder(LocalTls.javaTool("java"), "-Xmx128m", "-cp",
                System.getProperty("tls.test.classpath"), TlsStage2Probe.class.getName(),
                scenario, identity.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try { finished = child.waitFor(10, TimeUnit.SECONDS); }
        finally { LocalTls.stop(child); }
        String output = new String(Files.readAllBytes(log), "UTF-8");
        System.out.println(scenario + " reaped=" + !child.isAlive() + "\n" + output);
        assertTrue(finished, "Probe exceeded 10 seconds: " + output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("STAGE2_OK"), output);
    }
}
