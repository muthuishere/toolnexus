package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.muthuishere.toolnexus.mcp2026.AuthEnv.pending;
import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest {

    @Test
    void existingStepsUnprotected() {
        try (LocalServer s = LocalServer.start(Server.fixture())) {
            assertTrue(Checks.run(s.url() + "/mcp"));
        }
    }

    @Test
    void existingStepsProtectedAutoMode() {
        try (AuthEnv e = new AuthEnv()) {
            assertTrue(Checks.run(e::autoClient));
        }
    }

    @Test
    void pauseResume() {
        try (AuthEnv e = new AuthEnv()) {
            Client c = e.pauseClient();
            Pending p = pending(() -> c.callTool("echo", obj("text", "after login"), null));
            assertEquals("authorization", p.kind());
            assertTrue(p.url().startsWith(e.as.issuer() + "/authorize?") && !p.prompt().isEmpty() && !p.id().isEmpty());
            String cb = e.visit(p.url());
            Map<String, String> q = e.as.lastAuthorizeQuery();
            assertEquals(e.url, q.get("resource"));
            assertEquals("S256", q.get("code_challenge_method"));
            assertEquals("mcp:read", q.get("scope"));
            assertEquals("after login", text(c.auth().resume(p.id(), cb)));
            assertThrows(McpException.class, () -> c.auth().resume(p.id(), cb), "second resume of the same ID must fail");
            assertEquals("again", text(c.callTool("echo", obj("text", "again"), null)));
            assertEquals(1, e.as.authorizations());
        }
    }

    @Test
    void autoModeWhoamiPrincipal() {
        try (AuthEnv e = new AuthEnv()) {
            assertEquals("dev-user mcp:read", text(e.autoClient().callTool("whoami", obj(), null)));
        }
    }

    @Test
    void issMismatchRejected() {
        try (AuthEnv e = new AuthEnv()) {
            e.as.issOverride = "https://evil.example";
            Client c = e.autoClient();
            McpException x = assertThrows(McpException.class, () -> c.callTool("echo", obj("text", "x"), null));
            assertTrue(x.getMessage().contains("iss mismatch"), x.getMessage());
            assertTrue(c.auth().store().load(e.url).isEmpty(), "no token may be stored");
        }
    }

    @Test
    void stateMismatchRejected() {
        try (AuthEnv e = new AuthEnv()) {
            Client c = e.pauseClient();
            Pending p = pending(() -> c.callTool("echo", obj("text", "x"), null));
            String cb = e.visit(p.url()).replaceFirst("state=[^&]*", "state=forged");
            McpException x = assertThrows(McpException.class, () -> c.auth().resume(p.id(), cb));
            assertTrue(x.getMessage().contains("state mismatch"), x.getMessage());
        }
    }

    @Test
    void pkceVerifiedByAS() {
        try (AuthEnv e = new AuthEnv()) {
            Client c = e.pauseClient();
            Pending p = pending(() -> c.callTool("echo", obj("text", "x"), null));
            String cb = e.visit(p.url());
            c.auth().flow(p.id()).verifier = "not-the-verifier"; // the AS must reject the exchange
            McpException x = assertThrows(McpException.class, () -> c.auth().resume(p.id(), cb));
            assertTrue(x.getMessage().contains("invalid_grant"), x.getMessage());
        }
    }

    @Test
    void refreshOnExpiry() {
        try (AuthEnv e = new AuthEnv()) {
            Client c = e.autoClient();
            c.callTool("echo", obj("text", "1"), null);
            Token tok = c.auth().store().load(e.url).orElseThrow();
            c.auth().store().save(e.url, tok.withExpiry(Instant.now().minusSeconds(60)));
            assertEquals("2", text(c.callTool("echo", obj("text", "2"), null)));
            assertEquals(1, e.as.refreshes());
            assertEquals(1, e.as.authorizations());
            assertNotEquals(tok.accessToken(), c.auth().store().load(e.url).orElseThrow().accessToken());
        }
    }

    @Test
    void insufficientScopeStepUp() {
        try (AuthEnv e = new AuthEnv()) {
            Client c = e.autoClient();
            c.callTool("echo", obj("text", "1"), null);
            Map<String, Object> r = c.callTool("greet", obj(), (k, q) -> obj("action", "accept", "content", obj("name", "scoped")));
            assertEquals("Hello, scoped!", text(r));
            List<String> sc = Arrays.asList(e.as.lastAuthorizeQuery().get("scope").split(" "));
            assertEquals(2, e.as.authorizations());
            assertEquals(2, sc.size());
            assertTrue(sc.contains("mcp:read"), "step-up must request the union");
            c.callTool("echo", obj("text", "still"), null);
            assertEquals(2, e.as.authorizations(), "previous scope lost");
        }
    }

    @Test
    void prmWellKnownFallbackAndNoClientIdentity() {
        try (AuthEnv e = new AuthEnv()) {
            assertEquals(e.url, new OAuth().fetchPRM(e.url, "").get("resource"));
            Client c = new Client(e.url).auth(new OAuth().authorizer(new PauseAuthorizer("http://127.0.0.1:9/callback")));
            McpException x = assertThrows(McpException.class, () -> c.call("tools/list", null));
            assertTrue(x.getMessage().contains("register an app with " + e.as.issuer()), x.getMessage());
        }
    }

    // --- server-side ---

    record Raw(int status, String www, String body) {}

    static Raw rawCall(AuthEnv e, String token, String tool) throws Exception {
        String body = json(obj("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params",
                obj("name", tool, "arguments", obj("text", "x"), "_meta", obj(META_VERSION, PROTOCOL_VERSION))));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(e.url)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", "tools/call").header("Mcp-Name", tool);
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = DevTls.SHARED.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Raw(r.statusCode(), r.headers().firstValue("WWW-Authenticate").orElse(""), r.body());
    }

    @Test
    void serverJwtViaJwksAndPrincipal() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            Raw r = rawCall(e, e.claims(null), "whoami");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("alice mcp:read"), r.body);
        }
    }

    @Test
    void serverRejects() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            Map<String, String> cases = Map.of(
                    "missing", "",
                    "wrong audience", e.claims(c -> c.put("aud", "https://other.example/mcp")),
                    "expired", e.claims(c -> c.put("exp", System.currentTimeMillis() / 1000 - 60)),
                    "wrong issuer", e.claims(c -> c.put("iss", "https://evil.example")),
                    "tampered", e.claims(null) + "x");
            for (var c : cases.entrySet()) {
                Raw r = rawCall(e, c.getValue(), "echo");
                assertEquals(401, r.status, c.getKey());
                assertTrue(r.www.contains("resource_metadata=\"" + e.mcp.url() + "/.well-known/oauth-protected-resource/mcp"), c.getKey() + " " + r.www);
                if (!c.getValue().isEmpty()) assertTrue(r.www.contains("error=\"invalid_token\""), c.getKey() + " " + r.www);
            }
        }
    }

    @Test
    void serverPerToolScope403() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            Raw r = rawCall(e, e.claims(null), "greet");
            assertEquals(403, r.status);
            assertTrue(r.www.contains("error=\"insufficient_scope\"") && r.www.contains("scope=\"mcp:read mcp:greet\""), r.www);
            assertEquals(200, rawCall(e, e.claims(c -> c.put("scope", "mcp:read mcp:greet")), "greet").status);
        }
    }

    @Test
    void serverCustomVerify() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            e.server.auth().verify(tok -> {
                if (!tok.equals("opaque-ok")) throw new SecurityException("nope");
                return new Principal("svc", List.of("mcp:read"));
            });
            Raw r = rawCall(e, "opaque-ok", "whoami");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("svc mcp:read"));
            assertEquals(401, rawCall(e, e.claims(null), "whoami").status, "JWT must not bypass custom verify");
        }
    }

    /** A browser opener that fails must surface its error, not park the caller for the 5-minute callback timeout. */
    @Test
    void loopbackOpenerErrorSurfaces() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            Client c = e.autoClient();
            c.auth().authorizer(new LoopbackAuthorizer(u -> { throw new Exception("no browser"); }));
            CompletableFuture<Throwable> done = CompletableFuture.supplyAsync(() -> {
                try {
                    c.callTool("echo", obj("text", "x"), null);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Throwable t = done.get(5, TimeUnit.SECONDS);
            assertNotNull(t);
            assertTrue(t.getMessage().contains("no browser"), t.getMessage());
        }
    }
}
