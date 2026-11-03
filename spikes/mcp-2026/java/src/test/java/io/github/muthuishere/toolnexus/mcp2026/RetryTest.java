package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.github.muthuishere.toolnexus.mcp2026.AuthEnv.pending;
import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

/** Side effects run exactly once across auth pauses, refresh-after-401 and step-up; a lost response is never retried. */
class RetryTest {
    final AtomicInteger tick = new AtomicInteger(), charge = new AtomicInteger();

    /** "tick" (base scope) and "charge" (needs mcp:charge), each counting executions. */
    Consumer<Server> counted() {
        return s -> {
            s.tool("tick", "counts", (ctx, a) -> { tick.incrementAndGet(); return "tick"; });
            s.tool("charge", "counts, extra scope", (ctx, a) -> { charge.incrementAndGet(); return "charged"; });
            if (s.auth() != null) s.auth().scopes().put("charge", List.of("mcp:charge"));
        };
    }

    @Test
    void pauseResumeExecutesOnce() {
        try (AuthEnv e = new AuthEnv(counted())) {
            Client c = e.pauseClient();
            Pending p = pending(() -> c.callTool("tick", obj(), null));
            assertEquals(0, tick.get(), "paused call must not have executed");
            String cb = e.visit(p.url());
            assertEquals("tick", text(c.auth().resume(p.id(), cb)));
            assertThrows(McpException.class, () -> c.auth().resume(p.id(), cb));
            assertEquals(1, tick.get());
        }
    }

    @Test
    void refreshAfter401ExecutesOnce() {
        try (AuthEnv e = new AuthEnv(counted())) {
            Client c = e.autoClient();
            c.callTool("tick", obj(), null);
            Token tok = c.auth().store().load(e.url).orElseThrow();
            // not expired client-side: the server's 401 invalid_token drives the refresh
            c.auth().store().save(e.url, tok.withAccessToken("revoked-by-server"));
            assertEquals("tick", text(c.callTool("tick", obj(), null)));
            assertEquals(2, tick.get());
            assertEquals(1, e.as.refreshes());
            assertEquals(1, e.as.authorizations());
        }
    }

    @Test
    void stepUpExecutesOnce() {
        try (AuthEnv e = new AuthEnv(counted())) {
            // 401 (base scope) -> login -> 403 insufficient_scope -> step-up login -> run.
            assertEquals("charged", text(e.autoClient().callTool("charge", obj(), null)));
            assertEquals(1, charge.get());
            assertEquals(2, e.as.authorizations());
        }
    }

    /** A pause in a later MRTR round resumes THAT round: the user is not re-asked, round 0 is not replayed. */
    @Test
    void pauseMidMrtrResumesSameRound() {
        try (AuthEnv e = new AuthEnv(s -> s.auth().scopes().remove("greet"))) {
            Client c = e.pauseClient();
            Pending p0 = pending(() -> c.callTool("echo", obj("text", "login"), null));
            c.auth().resume(p0.id(), e.visit(p0.url()));
            int[] asked = {0};
            Pending p = pending(() -> c.callTool("greet", obj(), (k, q) -> {
                asked[0]++;
                c.auth().store().delete(e.url); // token lost between rounds -> round 1 gets 401
                return obj("action", "accept", "content", obj("name", "once"));
            }));
            assertEquals("Hello, once!", text(c.auth().resume(p.id(), e.visit(p.url()))));
            assertEquals(1, asked[0]);
        }
    }

    @Test
    void lostOrFailedResponseNotRetried() throws Exception {
        for (String mode : List.of("connection dropped", "502 after execution")) {
            tick.set(0);
            Server s = Server.fixture();
            counted().accept(s);
            try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                Thread.ofVirtual().start(() -> {
                    while (!ss.isClosed()) {
                        try (Socket sock = ss.accept()) {
                            s.handle(readRequest(sock.getInputStream())); // the tool runs; its response is lost
                            OutputStream o = sock.getOutputStream();
                            if (mode.startsWith("502"))
                                o.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 12\r\nConnection: close\r\n\r\nbad gateway\n".getBytes(StandardCharsets.US_ASCII));
                            o.flush();
                        } catch (Exception ignored) {
                            // closed
                        }
                    }
                });
                Client c = new Client("http://127.0.0.1:" + ss.getLocalPort() + "/mcp");
                assertThrows(McpException.class, () -> c.callTool("tick", obj(), null), mode);
                Thread.sleep(50); // a hidden retry would land here
                assertEquals(1, tick.get(), mode + ": no auto-retry of tools/call");
            }
        }
    }

    static HttpReq readRequest(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] rl = lines[0].split(" ");
        Map<String, String> h = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        byte[] body = in.readNBytes(Integer.parseInt(h.getOrDefault("content-length", "0")));
        return new HttpReq(rl[0], rl[1], null, h, body, false, h.getOrDefault("host", ""));
    }

    // --- RFC 8707 resource on refresh (python-sdk #2578) ---

    static void expire(Client c, String url) {
        Token t = c.auth().store().load(url).orElseThrow();
        c.auth().store().save(url, t.withExpiry(Instant.now().minusSeconds(60)));
    }

    @Test
    void refreshResourceConfigurable() {
        for (boolean omit : new boolean[]{false, true}) {
            try (AuthEnv e = new AuthEnv()) {
                e.as.rejectRefreshResource = true; // Entra-like
                Client c = e.autoClient();
                c.auth().omitRefreshResource(omit);
                c.callTool("echo", obj("text", "1"), null);
                assertEquals(e.url, e.as.lastAuthorizeQuery().get("resource"));
                assertEquals(e.url, e.as.lastTokenForm().get("resource"), "code exchange must always send resource");
                expire(c, e.url);
                assertEquals("2", text(c.callTool("echo", obj("text", "2"), null)));
                if (omit) {
                    assertEquals(1, e.as.refreshes());
                    assertEquals(1, e.as.authorizations());
                    assertFalse(e.as.lastTokenForm().containsKey("resource"));
                } else { // default sends resource; Entra rejects -> falls back to a new login
                    assertEquals(0, e.as.refreshes());
                    assertEquals(2, e.as.authorizations());
                }
            }
        }
        try (AuthEnv e = new AuthEnv()) { // default AS accepts resource on refresh and it is sent
            Client c = e.autoClient();
            c.callTool("echo", obj("text", "1"), null);
            expire(c, e.url);
            c.callTool("echo", obj("text", "2"), null);
            assertEquals("refresh_token", e.as.lastTokenForm().get("grant_type"));
            assertEquals(e.url, e.as.lastTokenForm().get("resource"));
        }
    }

    // --- pre-registered client id (+ secret) as the alternative to CIMD; no DCR ---

    @Test
    void preRegisteredConfidentialClient() {
        for (String method : List.of("", "client_secret_basic", "client_secret_post")) {
            try (AuthEnv e = new AuthEnv()) {
                e.as.noCimd = true;
                e.as.clients.put("conf", List.of("http://127.0.0.1/callback"));
                e.as.secrets.put("conf", "test-only-secret");
                OAuth o = new OAuth().clientId("conf").clientSecret("test-only-secret").tokenAuthMethod(method)
                        .authorizer(new LoopbackAuthorizer(e.as::approve));
                Client c = new Client(e.url).auth(o);
                assertEquals("ok", text(c.callTool("echo", obj("text", "ok"), null)), method);
                Map<String, String> f = e.as.lastTokenForm();
                assertEquals(method.equals("client_secret_post"), f.containsKey("client_secret"), method + ": secret placement");
                o.clientSecret("wrong");
                expire(c, e.url);
                o.authorizer(new PauseAuthorizer("http://127.0.0.1:9/callback"));
                // wrong secret must fail the refresh and fall back to a new authorization
                pending(() -> c.callTool("echo", obj("text", "x"), null));
            }
        }
    }

    @Test
    void noCimdAndNoClientIdIsAClearError() {
        try (AuthEnv e = new AuthEnv()) {
            e.as.noCimd = true;
            McpException x = assertThrows(McpException.class, () -> e.autoClient().callTool("echo", obj("text", "x"), null));
            assertTrue(x.getMessage().contains("does not support Client ID Metadata Documents"), x.getMessage());
            assertTrue(x.getMessage().contains("register an app with " + e.as.issuer() + " and set OAuth.ClientID"), x.getMessage());
            assertEquals(0, e.as.authorizations(), "must not start an authorization");
        }
    }
}
