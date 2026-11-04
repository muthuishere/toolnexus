package io.github.muthuishere.toolnexus.mcp;

import io.github.muthuishere.toolnexus.A2AServer;
import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.McpServe;
import io.github.muthuishere.toolnexus.McpSource;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.muthuishere.toolnexus.mcp.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 2026-07-28 client path of the shipped library (SPEC §2, §2A), hermetic: every server is an
 * in-test 127.0.0.1 fixture (examples/mcp-2026/fixture.json) or a stdio child of this JVM.
 */
class Mcp2026ClientTest {

    private static Map<String, Object> expected() throws Exception {
        return map(parse(Files.readAllBytes(repoFile("examples/mcp-2026/expected.json"))));
    }

    private static Path repoFile(String rel) {
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent())
            if (Files.exists(d.resolve(rel))) return d.resolve(rel);
        throw new IllegalStateException(rel + " not found");
    }

    /** A recorded fixture server: every request's method + headers, and tools/call executions. */
    static final class Recorded implements AutoCloseable {
        final Server server;
        final LocalServer http;
        final List<HttpReq> reqs = new CopyOnWriteArrayList<>();
        final AtomicInteger executions = new AtomicInteger();
        volatile int failCallsWith; // >0: run the tool, then answer tools/call with this HTTP status

        Recorded(Server s) {
            server = s;
            http = LocalServer.start(ex -> {
                HttpReq r = HttpReq.of(ex, Server.MAX_BODY_BYTES);
                reqs.add(r);
                HttpResp resp = server.handle(r);
                boolean call = "tools/call".equals(r.header("Mcp-Method")) && resp.status() == 200;
                if (call) executions.incrementAndGet();
                if (call && failCallsWith > 0) resp = HttpResp.text(failCallsWith, "upstream broke\n");
                resp.writeTo(ex);
            });
        }

        Recorded() { this(Server.fixture()); }

        String url() { return http.url() + "/mcp"; }

        List<String> methods() {
            List<String> out = new ArrayList<>();
            for (HttpReq r : reqs) if (r.method().equals("POST")) out.add(r.header("Mcp-Method"));
            return out;
        }

        @Override
        public void close() { http.close(); }
    }

    private static Map<String, Object> cfg(String name, Map<String, Object> server) {
        return obj(name, server);
    }

    private static Tool tool(McpSource src, String name) {
        for (Tool t : src.tools()) if (t.name().equals(name)) return t;
        throw new AssertionError("no tool " + name + " in " + names(src));
    }

    private static List<String> names(McpSource src) {
        List<String> out = new ArrayList<>();
        for (Tool t : src.tools()) out.add(t.name());
        Collections.sort(out);
        return out;
    }

    private static ToolContext answering(Answer a) { return new ToolContext(null, null, a, null); }

    // ---------------------------------------------------------------- §2.2 / §2.3 HTTP

    @Test
    void httpDiscoverFirstHeadersAndResults() throws Exception {
        Map<String, Object> ex = expected();
        try (Recorded s = new Recorded();
             McpSource src = McpSource.load(cfg("fx", obj("url", s.url())))) {
            assertEquals("connected", src.status().get("fx"));
            assertEquals(List.of("fx_echo", "fx_greet"), names(src));
            assertEquals("server/discover", s.methods().get(0), "discover must be the first request");
            assertFalse(s.methods().contains("initialize"), "no initialize on the 2026 path");

            ToolResult r = tool(src, "fx_echo").execute(obj("text", "hi toolnexus"), null);
            assertFalse(r.isError(), r.output());
            Map<String, Object> echo = map(list(ex.get("steps")).get(4));
            assertEquals(json(map(echo.get("expect")).get("structuredContent")), json(map(parse(r.output().getBytes()))));
            assertEquals("fx", r.metadata().get("server"));

            HttpReq call = s.reqs.get(s.reqs.size() - 1);
            assertEquals(PROTOCOL_VERSION, call.header("MCP-Protocol-Version"));
            assertEquals("tools/call", call.header("Mcp-Method"));
            assertEquals("echo", call.header("Mcp-Name"));
            Map<String, Object> meta = map(map(map(parse(call.body())).get("params")).get("_meta"));
            assertEquals(PROTOCOL_VERSION, meta.get(META_VERSION));
            assertEquals(obj("elicitation", obj("form", obj())), meta.get(META_CLIENT_CAPS));
            assertEquals("toolnexus", map(meta.get(META_CLIENT_INFO)).get("name"));
        }
    }

    @Test
    void mcpNameSentinel() {
        assertEquals("echo", Client.encodeHeader("echo"));
        assertEquals("=?base64?IGxlYWQ=?=", Client.encodeHeader(" lead"));
        assertEquals("=?base64?w6k=?=", Client.encodeHeader("é"));
        assertTrue(Client.encodeHeader("=?base64?x?=").startsWith("=?base64?PT9iYXNlNjQ"));
    }

    @Test
    void perServerToolFilterAndInventory() {
        try (Recorded s = new Recorded()) {
            try (McpSource src = McpSource.load(cfg("fx", obj("url", s.url(), "tools", obj("echo", true))))) {
                assertEquals(List.of("fx_echo"), names(src));
            }
            McpSource.McpInventory inv = McpSource.listMcpTools(cfg("fx", obj("url", s.url(), "tools", obj("echo", true))));
            assertEquals("connected", inv.status().get("fx"));
            assertEquals(2, inv.tools().get("fx").size(), "inventory is unfiltered");
        }
    }

    // ---------------------------------------------------------------- §2.6 no hidden re-execution

    @Test
    void noRetryOn5xx() {
        try (Recorded s = new Recorded(); McpSource src = McpSource.load(cfg("fx", obj("url", s.url())))) {
            s.failCallsWith = 502;
            ToolResult r = tool(src, "fx_echo").execute(obj("text", "x"), null);
            assertTrue(r.isError());
            assertTrue(r.output().contains("502"), r.output());
            assertEquals(1, s.executions.get(), "the tool ran once and was never re-sent");
        }
    }

    @Test
    void serverErrorOnDiscoverIsALoadFailureNotAFallback() {
        try (LocalServer bad = LocalServer.start(ex -> HttpResp.text(503, "down\n").writeTo(ex))) {
            try (McpSource src = McpSource.load(cfg("bad", obj("url", bad.url() + "/mcp", "timeout", 2000)))) {
                assertEquals("failed", src.status().get("bad"));
            }
        }
    }

    // ---------------------------------------------------------------- §2.2a frozen fallback

    @Test
    void oldServerFallsBackToTheFrozenPath() {
        Toolkit serverTk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(
                new Tool() {
                    @Override public String name() { return "ping"; }
                    @Override public String description() { return "ping"; }
                    @Override public String source() { return "custom"; }
                    @Override public Map<String, Object> inputSchema() { return obj("type", "object", "properties", obj()); }
                    @Override public ToolResult execute(Map<String, Object> a, ToolContext c) { return ToolResult.ok("pong"); }
                }));
        A2AServer.ServeHandle h = serverTk.serve("127.0.0.1:0", new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()));
        try (McpSource src = McpSource.load(cfg("old", obj("url", h.url() + "/mcp")))) {
            assertEquals("connected", src.status().get("old"));
            assertEquals("pong", tool(src, "old_ping").execute(obj(), null).output());
        } finally {
            h.stop();
        }
    }

    // ---------------------------------------------------------------- stdio

    private static Map<String, Object> stdioCfg(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Map<String, Object> c = obj("command", List.of(java, "-cp", System.getProperty("java.class.path"),
                StdioTestServer.class.getName()), "environment", obj("MODE", mode,
                "FIXTURE", repoFile("examples/mcp-2026/fixture.json").toString()));
        return c;
    }

    @Test
    void stdioDirectWithMrtrAndConcurrency() throws Exception {
        List<Request> asked = new CopyOnWriteArrayList<>();
        Function<Request, Answer> waitFor = rq -> {
            asked.add(rq);
            return new Answer(rq.id(), true, Map.of("name", "toolnexus"));
        };
        try (McpSource src = McpSource.load(cfg("io", stdioCfg("")), new McpSource.LoadOptions().waitFor(waitFor))) {
            assertEquals("connected", src.status().get("io"));
            assertEquals(List.of("io_echo", "io_greet"), names(src));
            ToolResult g = tool(src, "io_greet").execute(obj(), null);
            assertTrue(g.output().contains("Hello, toolnexus!"), g.output());
            assertEquals(1, asked.size());
            assertEquals("input", asked.get(0).kind());
            assertEquals("What is your name?", asked.get(0).prompt());
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<ToolResult>> fs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String t = "n" + i;
                fs.add(pool.submit(() -> tool(src, "io_echo").execute(obj("text", t), null)));
            }
            for (int i = 0; i < 16; i++) assertTrue(fs.get(i).get().output().contains("n" + i));
            pool.shutdown();
        }
    }

    @Test
    void stdioChildThatExitsIsAFailure() {
        try (McpSource src = McpSource.load(cfg("dead", stdioCfg("exit")))) {
            assertEquals("failed", src.status().get("dead"));
        }
    }

    @Test
    void silentStdioServerFallsBackWithinFiveSeconds() {
        // A server that ignores a pre-initialize request: discover times out ⇒ frozen path, which then
        // fails too (it never answers initialize) — the point is a bounded, isolated outcome.
        long t0 = System.currentTimeMillis();
        try (McpSource src = McpSource.load(cfg("quiet", merge(stdioCfg("silent"), obj("timeout", 1500))))) {
            assertEquals("failed", src.status().get("quiet"));
        }
        assertTrue(System.currentTimeMillis() - t0 < 15_000);
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> m = new LinkedHashMap<>(a);
        m.putAll(b);
        return m;
    }

    // ---------------------------------------------------------------- §2.5 MRTR → §10

    @Test
    void mrtrPendingWithoutWaitForResumesTheSameRound() {
        try (Recorded s = new Recorded(); McpSource src = McpSource.load(cfg("fx", obj("url", s.url())))) {
            Tool greet = tool(src, "fx_greet");
            ToolResult p = greet.execute(obj(), null);
            Request rq = ToolResult.pendingOf(p);
            assertNotNull(rq, p.output());
            assertEquals("input", rq.kind());
            assertEquals("What is your name?", rq.prompt());
            assertNotNull(rq.data().get("schema"));
            ToolResult done = greet.execute(obj(), answering(new Answer(rq.id(), true, Map.of("name", "toolnexus"))));
            assertTrue(done.output().contains("Hello, toolnexus!"), done.output());
            HttpReq last = s.reqs.get(s.reqs.size() - 1);
            Map<String, Object> params = map(map(parse(last.body())).get("params"));
            assertEquals(obj("name", obj("action", "accept", "content", obj("name", "toolnexus"))), params.get("inputResponses"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void mrtrDeclineMapsToDecline() throws Exception {
        try (Recorded s = new Recorded(); McpSource src = McpSource.load(cfg("fx", obj("url", s.url())))) {
            Tool greet = tool(src, "fx_greet");
            Request rq = ToolResult.pendingOf(greet.execute(obj(), null));
            ToolResult again = greet.execute(obj(), answering(Answer.declined(rq.id(), "declined")));
            assertNotNull(ToolResult.pendingOf(again), "the fixture re-asks after a decline");
            Map<String, Object> params = map(map(parse(s.reqs.get(s.reqs.size() - 1).body())).get("params"));
            assertEquals(obj("name", obj("action", "decline")), params.get("inputResponses"));
        }
    }

    // ---------------------------------------------------------------- §2.8 tool search

    @Test
    void toolSearch() throws Exception {
        try (Recorded s = new Recorded();
             McpSource src = McpSource.load(cfg("fx", obj("url", s.url(), "toolSearch", true, "description", "the demo server")))) {
            assertEquals(List.of("fx_call_tool", "fx_search_tools"), names(src));
            Tool search = tool(src, "fx_search_tools");
            assertEquals("Search the tools of the demo server by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
                    search.description());
            assertEquals("{\"properties\":{\"limit\":{\"type\":\"integer\"},\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"type\":\"object\"}",
                    json(search.inputSchema()));
            assertEquals("Run a tool found with search_tools.", tool(src, "fx_call_tool").description());
            String out = search.execute(obj("query", "echo text"), null).output();
            assertTrue(out.startsWith("[{\"name\":\"echo\",\"description\":\"Echo the given text back.\",\"inputSchema\":{\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"type\":\"object\"},\"server\":\"the demo server\"}"), out);
            assertEquals("[]", search.execute(obj("query", "zzzqqq"), null).output());
            List<Object> hits = list(parse(out.getBytes()));
            Map<String, Object> top = map(hits.get(0));
            assertEquals("echo", top.get("name"));
            assertEquals(List.of("description", "inputSchema", "name", "server"), new ArrayList<>(new java.util.TreeSet<>(top.keySet())));
            assertEquals("the demo server", top.get("server"));
            ToolResult r = tool(src, "fx_call_tool").execute(obj("name", "echo", "arguments", obj("text", "found")), null);
            assertTrue(r.output().contains("found"), r.output());
            ToolResult u = tool(src, "fx_call_tool").execute(obj("name", "nope", "arguments", obj()), null);
            assertTrue(u.isError());
            assertEquals("unknown tool \"nope\" on fx", u.output());
        }
        try (Recorded s = new Recorded(); McpSource src = McpSource.load(cfg("fx", obj("url", s.url(), "toolSearch", true)))) {
            assertTrue(tool(src, "fx_search_tools").description().startsWith("Search the tools of fx by keywords."));
        }
    }

    // ---------------------------------------------------------------- §2.7 skills

    @Test
    void remoteSkillsJoinTheSkillToolByteIdentical() throws Exception {
        String local;
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).skillsDir(repoFile("examples/skills").toString()))) {
            local = tk.get("skill").execute(obj("name", "hello-world"), null).output();
        }
        try (Recorded s = new Recorded();
             Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).mcpConfig(cfg("fx", obj("url", s.url()))))) {
            String remote = tk.get("skill").execute(obj("name", "hello-world"), null).output();
            assertFalse(tk.get("skill").execute(obj("name", "hello-world"), null).isError(), remote);
            assertEquals(stripBase(local), stripBase(remote));
            assertTrue(s.methods().contains("skills/list") && s.methods().contains("skills/get") && s.methods().contains("resources/read"));
        }
    }

    private static String stripBase(String s) {
        return s.replaceAll("file:[^\\s<\"]*hello-world/?", "BASE/").replaceAll("BASE//", "BASE/")
                .replaceAll("<file>[^<]*/hello-world/", "<file>BASE/");
    }

    @Test
    void skillUriContainment() throws Exception {
        for (Object bad : list(expected().get("skillUriRejected")))
            assertThrows(IllegalArgumentException.class, () -> SkillUri.parse((String) bad), (String) bad);
        assertEquals("hello-world", SkillUri.parse("skill://hello-world/SKILL.md").name());
    }

    // ---------------------------------------------------------------- §2A OAuth

    /** A dev AS + a protected fixture server (plain HTTP on 127.0.0.1; clients set auth.allowHttp). */
    static final class AuthEnv implements AutoCloseable {
        final DevAuthServer as = DevAuthServer.start();
        final Recorded mcp;
        final LocalServer cimd;
        final String cimdUrl;

        AuthEnv() {
            String[] holder = new String[1];
            cimd = LocalServer.start(ex -> HttpResp.json(200, obj("client_id", holder[0], "client_name", "toolnexus test",
                    "redirect_uris", List.of("http://127.0.0.1/callback"), "grant_types", List.of("authorization_code", "refresh_token"),
                    "response_types", List.of("code"), "token_endpoint_auth_method", "none")).writeTo(ex));
            cimdUrl = cimd.url() + "/client.json";
            holder[0] = cimdUrl;
            Server srv = Server.fixture().auth(new ServerAuth(as.issuer()).scopes(Map.of("*", List.of("mcp:read"), "greet", List.of("mcp:greet"))));
            srv.tool("whoami", "who is calling", (ctx, a) -> ctx.principal().orElseThrow().subject());
            mcp = new Recorded(srv);
        }

        String url() { return mcp.url(); }

        Map<String, Object> autoCfg() {
            return cfg("sec", obj("url", url(), "auth", obj("clientMetadataUrl", cimdUrl, "allowHttp", true)));
        }

        Map<String, Object> pauseCfg() {
            as.clients.put("prereg", List.of("http://127.0.0.1:9/callback"));
            return cfg("sec", obj("url", url(), "auth", obj("mode", "pause", "redirectUri", "http://127.0.0.1:9/callback",
                    "clientIds", obj(as.issuer(), "prereg"), "allowHttp", true)));
        }

        McpSource.LoadOptions auto(TokenStore store) {
            return new McpSource.LoadOptions().tokenStore(store).openUrl(as::approve);
        }

        /** The host "receives" the AS redirect (pause mode). */
        String visit(String authUrl) {
            HttpResponse<String> r = as.get(authUrl, false);
            assertEquals(302, r.statusCode());
            return r.headers().firstValue("Location").orElseThrow();
        }

        Function<Request, Answer> waitForVisit(List<Request> seen) {
            return rq -> {
                seen.add(rq);
                return new Answer(rq.id(), true, Map.of("callbackUrl", visit(rq.url())));
            };
        }

        @Override
        public void close() {
            mcp.close();
            cimd.close();
            as.close();
        }
    }

    private static Throwable loadError(Map<String, Object> config, McpSource.LoadOptions o) {
        List<Throwable> errs = new CopyOnWriteArrayList<>();
        try (McpSource src = McpSource.load(config, o.onServer(r -> { if (r.error() != null) errs.add(r.error()); }))) {
            assertEquals("failed", src.status().values().iterator().next());
        }
        assertEquals(1, errs.size());
        return errs.get(0);
    }

    @Test
    void oauthAutoModeCimdLogin() {
        try (AuthEnv e = new AuthEnv(); McpSource src = McpSource.load(e.autoCfg(), e.auto(new MemoryTokenStore()))) {
            assertEquals("connected", src.status().get("sec"));
            assertEquals("dev-user", tool(src, "sec_whoami").execute(obj(), null).output());
            Map<String, String> q = e.as.lastAuthorizeQuery();
            assertEquals(e.url(), q.get("resource"));
            assertEquals("S256", q.get("code_challenge_method"));
            assertEquals(e.cimdUrl, q.get("client_id"));
            assertEquals(1, e.as.authorizations());
        }
    }

    @Test
    void oauthPauseModeThroughWaitFor() {
        try (AuthEnv e = new AuthEnv()) {
            List<Request> seen = new CopyOnWriteArrayList<>();
            try (McpSource src = McpSource.load(e.pauseCfg(), new McpSource.LoadOptions().waitFor(e.waitForVisit(seen)))) {
                assertEquals("connected", src.status().get("sec"));
                Request rq = seen.get(0);
                assertEquals("authorization", rq.kind());
                assertTrue(rq.id().startsWith("auth-"), rq.id());
                assertEquals("Authorize access to " + e.url(), rq.prompt());
                assertNotNull(rq.expiresAt());
                assertTrue(tool(src, "sec_echo").execute(obj("text", "after login"), null).output().contains("after login"));
            }
        }
    }

    @Test
    void oauthPauseWithoutWaitForFailsTheLoad() {
        try (AuthEnv e = new AuthEnv()) {
            assertEquals("authorization pending", loadError(e.pauseCfg(), new McpSource.LoadOptions()).getMessage());
        }
    }

    @Test
    void oauthPauseDeniedFailsTheLoad() {
        try (AuthEnv e = new AuthEnv()) {
            Throwable t = loadError(e.pauseCfg(), new McpSource.LoadOptions().waitFor(rq -> Answer.declined(rq.id(), "declined")));
            assertEquals("authorization denied: declined", t.getMessage());
        }
    }

    @Test
    void stepUpPauseDuringToolCallThenMrtrResumesOnce() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            TokenStore store = new MemoryTokenStore();
            List<Request> seen = new CopyOnWriteArrayList<>();
            try (McpSource first = McpSource.load(e.pauseCfg(), new McpSource.LoadOptions().tokenStore(store).waitFor(e.waitForVisit(seen)))) {
                assertEquals("connected", first.status().get("sec"));
            }
            // A second load reuses the stored token (no waitFor); greet needs mcp:greet ⇒ 403 step-up ⇒ pause.
            try (McpSource src = McpSource.load(e.pauseCfg(), new McpSource.LoadOptions().tokenStore(store))) {
                assertEquals("connected", src.status().get("sec"));
                Tool greet = tool(src, "sec_greet");
                int before = e.mcp.executions.get();
                Request auth = ToolResult.pendingOf(greet.execute(obj(), null));
                assertNotNull(auth);
                assertEquals("authorization", auth.kind());
                assertTrue(auth.id().startsWith("auth-"));
                assertEquals(before, e.mcp.executions.get(), "nothing ran before authorization");
                String cb = e.visit(auth.url());
                assertEquals("mcp:read mcp:greet", e.as.lastAuthorizeQuery().get("scope"));
                ToolResult r = greet.execute(obj(), answering(new Answer(auth.id(), true, Map.of("callbackUrl", cb))));
                Request input = ToolResult.pendingOf(r);
                assertNotNull(input, r.output());
                assertEquals("input", input.kind());
                ToolResult done = greet.execute(obj(), answering(new Answer(input.id(), true, Map.of("name", "toolnexus"))));
                assertTrue(done.output().contains("Hello, toolnexus!"), done.output());
                ToolResult unknown = greet.execute(obj(), answering(new Answer("auth-nope", true, Map.of())));
                assertNotNull(ToolResult.pendingOf(unknown), "an unknown answer id starts a fresh call");
            }
        }
    }

    @Test
    void issMismatchRejectedNothingStored() {
        try (AuthEnv e = new AuthEnv()) {
            e.as.issOverride = "https://evil.example";
            TokenStore store = new MemoryTokenStore();
            Throwable t = loadError(e.autoCfg(), e.auto(store));
            assertEquals("authorization response iss mismatch (possible mix-up attack)", t.getMessage());
            assertTrue(store.load(e.url()).isEmpty());
        }
    }

    @Test
    void stateMismatchRejected() {
        try (AuthEnv e = new AuthEnv()) {
            Function<Request, Answer> forged = rq -> new Answer(rq.id(), true,
                    Map.of("callbackUrl", e.visit(rq.url()).replaceFirst("state=[^&]*", "state=forged")));
            assertEquals("authorization response state mismatch", loadError(e.pauseCfg(), new McpSource.LoadOptions().waitFor(forged)).getMessage());
        }
    }

    @Test
    void noClientIdentityIsAnError() {
        try (AuthEnv e = new AuthEnv()) {
            e.as.noCimd = true;
            String m = loadError(cfg("sec", obj("url", e.url(), "auth", obj("allowHttp", true))), e.auto(new MemoryTokenStore())).getMessage();
            assertTrue(m.startsWith("no client identity for " + e.as.issuer()), m);
            assertTrue(m.contains("register an app with") && m.contains("dynamic client registration is not supported"), m);
        }
    }

    @Test
    void refreshOnExpirySingleFlight() throws Exception {
        try (AuthEnv e = new AuthEnv()) {
            TokenStore store = new MemoryTokenStore();
            try (McpSource src = McpSource.load(e.autoCfg(), e.auto(store))) {
                Token t = store.load(e.url()).orElseThrow();
                store.save(e.url(), t.withExpiresAt(Instant.now().minusSeconds(60)));
                int before = e.mcp.executions.get();
                ExecutorService pool = Executors.newFixedThreadPool(20);
                List<Future<ToolResult>> fs = new ArrayList<>();
                for (int i = 0; i < 40; i++) fs.add(pool.submit(() -> tool(src, "sec_echo").execute(obj("text", "r"), null)));
                for (Future<ToolResult> f : fs) assertFalse(f.get().isError(), f.get().output());
                pool.shutdown();
                assertEquals(1, e.as.refreshes(), "concurrent callers share one refresh");
                assertEquals(40, e.mcp.executions.get() - before);
                assertEquals(e.url(), e.as.lastTokenForm().get("resource"), "resource is sent on refresh by default");
                assertEquals(1, e.as.authorizations());
            }
        }
    }

    @Test
    void omitRefreshResource() {
        try (AuthEnv e = new AuthEnv()) {
            e.as.rejectRefreshResource = true;
            TokenStore store = new MemoryTokenStore();
            Map<String, Object> c = cfg("sec", obj("url", e.url(), "auth", obj("clientMetadataUrl", e.cimdUrl, "allowHttp", true, "omitRefreshResource", true)));
            try (McpSource src = McpSource.load(c, e.auto(store))) {
                store.save(e.url(), store.load(e.url()).orElseThrow().withExpiresAt(Instant.now().minusSeconds(60)));
                assertFalse(tool(src, "sec_echo").execute(obj("text", "x"), null).isError());
                assertEquals(1, e.as.refreshes());
                assertNull(e.as.lastTokenForm().get("resource"));
            }
        }
    }

    @Test
    void tokensNeverLoggedOrInErrors() {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (AuthEnv e = new AuthEnv()) {
            TokenStore store = new MemoryTokenStore();
            System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
            String out;
            try (McpSource src = McpSource.load(e.autoCfg(), e.auto(store))) {
                e.mcp.failCallsWith = 502;
                out = tool(src, "sec_echo").execute(obj("text", "x"), null).output();
            } finally {
                System.setErr(old);
            }
            Token t = store.load(e.url()).orElseThrow();
            String logs = buf.toString(StandardCharsets.UTF_8) + out + t;
            assertFalse(logs.contains(t.accessToken()));
            assertFalse(logs.contains(t.refreshToken()));
        }
    }
}
