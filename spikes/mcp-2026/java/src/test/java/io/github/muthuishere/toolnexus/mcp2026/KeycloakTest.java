package io.github.muthuishere.toolnexus.mcp2026;

import ex09.HeadlessLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Live tests against a real Keycloak (../golang/keycloak/docker-compose.yml). Skipped unless KEYCLOAK=1.
 * The realm's audience mapper pins aud = http://127.0.0.1:7199/mcp, so the server must listen there.
 */
class KeycloakTest {
    static final String ISSUER = "http://localhost:8180/realms/toolnexus";
    static final String URL = "http://127.0.0.1:7199/mcp";
    LocalServer server;
    HeadlessLogin browser;
    MemoryTokenStore store;

    @BeforeEach
    void up() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("KEYCLOAK")), "set KEYCLOAK=1 with keycloak/docker-compose.yml running");
        Server srv = Server.fixture().auth(new ServerAuth(ISSUER).audience(URL)
                .scopes(Map.of("*", List.of("mcp:tools"), "greet", List.of("mcp:greet"))));
        srv.tool("whoami", "who is calling", (ctx, a) -> {
            Principal p = ctx.principal().orElseThrow();
            return p.subject() + " " + String.join(",", p.scopes());
        });
        for (int i = 0; ; i++) { // 7199 is shared with the other ports' Keycloak suites: wait for it
            try {
                server = LocalServer.start(srv, 7199);
                break;
            } catch (RuntimeException e) {
                if (i > 120) throw e;
                Thread.sleep(1000);
            }
        }
        browser = new HeadlessLogin("alice", "alice-test-pw"); // local-only throwaway test user from the realm file
        store = new MemoryTokenStore();
    }

    @AfterEach
    void down() {
        if (server != null) server.close();
    }

    Client client(Consumer<OAuth> mod) {
        OAuth o = new OAuth().allowHttp(true).clientIds(Map.of(ISSUER, "toolnexus-mcp")).store(store)
                .authorizer(new LoopbackAuthorizer(browser));
        if (mod != null) mod.accept(o);
        return new Client(URL).auth(o);
    }

    static Map<String, Object> claims(String jwt) throws Exception {
        return map(parse(Base64.getUrlDecoder().decode(jwt.split("\\.")[1])));
    }

    @Test
    void endToEnd() throws Exception {
        String who = text(client(null).callTool("whoami", obj(), null));
        assertTrue(who.contains("mcp:tools"), who);
        Map<String, Object> c = claims(store.load(URL).orElseThrow().accessToken());
        System.out.println("keycloak: aud=" + c.get("aud") + " scope=" + c.get("scope") + " azp=" + c.get("azp"));
        assertEquals(ISSUER, c.get("iss"));
    }

    @Test
    void eightSteps() {
        assertTrue(Checks.run(() -> client(null)));
    }

    @Test
    void refresh() {
        for (boolean omit : new boolean[]{false, true}) {
            store = new MemoryTokenStore();
            browser.forms = 0;
            Client c = client(o -> o.omitRefreshResource(omit));
            c.callTool("echo", obj("text", "a"), null);
            Token old = store.load(URL).orElseThrow();
            store.save(URL, old.withExpiry(Instant.now().minusSeconds(60)));
            assertEquals("b", text(c.callTool("echo", obj("text", "b"), null)));
            assertNotEquals(old.accessToken(), store.load(URL).orElseThrow().accessToken(), "omit=" + omit);
            assertTrue(browser.forms <= 1, "refresh must not need a second login form (omit=" + omit + ")");
        }
    }

    @Test
    void stepUp() {
        Client c = client(null);
        c.callTool("echo", obj("text", "a"), null);
        assertEquals("Hello, Alice!", text(c.callTool("greet", obj(), (k, q) -> obj("action", "accept", "content", obj("name", "Alice")))));
        assertTrue(store.load(URL).orElseThrow().scopes().contains("mcp:greet"));
    }

    @Test
    void pauseResume() throws Exception {
        Client c = client(o -> o.authorizer(new PauseAuthorizer("http://127.0.0.1:9/callback")));
        Pending p = AuthEnv.pending(() -> c.callTool("echo", obj("text", "after login"), null));
        String cb = browser.login(p.url());
        assertEquals(ISSUER, HttpReq.parseQuery(URI.create(cb).getRawQuery()).get("iss"), "RFC 9207 iss");
        assertEquals("after login", text(c.auth().resume(p.id(), cb)));
    }

    @Test
    void wrongAudienceRejected() {
        Client c = client(null);
        c.callTool("echo", obj("text", "a"), null);
        String tok = store.load(URL).orElseThrow().accessToken();
        try (LocalServer other = LocalServer.start(Server.fixture().auth(new ServerAuth(ISSUER).audience("http://127.0.0.1:7200/mcp")
                .scopes(Map.of("*", List.of("mcp:tools")))))) {
            Client raw = new Client(other.url() + "/mcp").header("Authorization", "Bearer " + tok);
            var resp = raw.post("tools/list", obj(), json(obj("jsonrpc", "2.0", "id", 1, "method", "tools/list",
                    "params", obj("_meta", obj(META_VERSION, PROTOCOL_VERSION)))).getBytes(), "");
            assertEquals(401, resp.statusCode());
            assertTrue(resp.headers().firstValue("WWW-Authenticate").orElse("").contains("error=\"invalid_token\""));
        }
    }
}
