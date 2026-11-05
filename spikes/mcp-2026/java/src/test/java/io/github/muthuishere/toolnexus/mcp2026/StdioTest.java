package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

class StdioTest {
    static Client stdioClient(String mode, OutputStream stderr) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("test.runtime.classpath"), StdioTestServer.class.getName());
        pb.environment().put("MODE", mode);
        return Client.stdio(pb, stderr);
    }

    @Test
    void sameEightSteps() {
        assertTrue(Checks.run(() -> {
            try {
                return stdioClient("serve", OutputStream.nullOutputStream());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Test
    void stderrCapturedNotParsedAndConcurrent() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        OutputStream sync = new OutputStream() {
            @Override
            public synchronized void write(int b) { err.write(b); }

            @Override
            public synchronized void write(byte[] b, int off, int len) { err.write(b, off, len); }
        };
        try (Client c = stdioClient("serve", sync)) {
            List<CompletableFuture<Void>> fs = IntStream.range(0, 20).mapToObj(i -> CompletableFuture.runAsync(() -> {
                String want = "msg-" + i;
                assertEquals(want, text(c.callTool("echo", obj("text", want), null)));
            })).toList();
            CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).join();
        }
        Thread.sleep(100);
        synchronized (sync) {
            assertTrue(err.toString().contains("stdio-test-server ready"), "stderr not captured: " + err);
        }
    }

    @Test
    void serverExitIsAnError() throws Exception {
        try (Client c = stdioClient("exit", OutputStream.nullOutputStream())) {
            assertThrows(McpException.class, () -> c.call("tools/list", null));
        }
    }
}
