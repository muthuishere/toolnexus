package io.github.muthuishere.toolnexus.interop;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.McpSource;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.mcp.MemoryTokenStore;
import io.github.muthuishere.toolnexus.mcp.Token;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java CLIENT runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md). Mirrors
 * golang/internal/interop/mcp2026client scenario-for-scenario. Prints {@code PASS <id>} /
 * {@code FAIL <id>: <reason>}; exit 0 iff all passed. Test tooling, never shipped.
 *
 * <pre>
 * Mcp2026Client &lt;base&gt; &lt;skillsDir&gt;
 * Mcp2026Client --keycloak &lt;mcpURL&gt; &lt;skillsDir&gt;
 * </pre>
 */
public final class Mcp2026Client {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static boolean failed;

    interface Body { void run() throws Exception; }

    private static void scenario(String id, Body fn) {
        try {
            fn.run();
            System.out.println("PASS " + id);
        } catch (Throwable e) {
            failed = true;
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("FAIL " + id + ": " + m.replace("\n", " "));
        }
        System.out.flush();
    }

    private static Exception fail(String m) { return new Exception(m); }

    static Answer answers(Request r) {
        return switch (r.prompt()) {
            case "What is your name?" -> new Answer(r.id(), true, Map.of("name", "toolnexus"));
            case "Really wipe?" -> new Answer(r.id(), true, Map.of("confirm", true));
            default -> new Answer(r.id(), false);
        };
    }

    static McpSource load(Map<String, Object> cfg, McpSource.LoadOptions opts) throws Exception {
        McpSource src = McpSource.load(Map.of("srv", cfg), opts);
        if (!"connected".equals(src.status().get("srv"))) {
            src.close();
            throw fail("not connected: " + src.status());
        }
        return src;
    }

    static Tool tool(McpSource src, String name) throws Exception {
        for (Tool t : src.tools()) if (t.name().equals(name)) return t;
        throw fail("no tool " + name);
    }

    static ToolResult run(McpSource src, String name, Map<String, Object> args) throws Exception {
        return tool(src, name).execute(args == null ? Map.of() : args, new ToolContext());
    }

    static void expect(McpSource src, String name, Map<String, Object> args, String want) throws Exception {
        ToolResult r = run(src, name, args);
        if (r.isError() || !want.equals(r.output())) {
            throw fail(name + ": got " + q(r.output()) + " (isError=" + r.isError() + "), want " + q(want));
        }
    }

    static String q(String s) { return s == null ? "null" : "\"" + s + "\""; }

    static void get(String u) throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    static boolean hasScope(Token t, String s) { return t != null && t.scopes() != null && t.scopes().contains(s); }

    public static void main(String[] args) {
        if (args.length == 3 && args[0].equals("--keycloak")) {
            keycloak(args[1]);
        } else if (args.length == 2) {
            hermetic(args[0], args[1]);
        } else {
            System.err.println("usage: Mcp2026Client <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>");
            System.exit(2);
        }
        System.exit(failed ? 1 : 0);
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    static void hermetic(String base, String skillsDir) {
        Map<String, Object> open = m("url", base + "/open/mcp");

        scenario("discover", () -> {
            try (McpSource src = load(open, new McpSource.LoadOptions().waitFor(Mcp2026Client::answers))) {
                List<String> names = new ArrayList<>();
                for (Tool t : src.tools()) names.add(t.name());
                String got = String.join(",", names);
                if (!got.equals("srv_echo,srv_greet,srv_whoami,srv_wipe")) throw fail("tools " + got);
                expect(src, "srv_echo", m("text", "hi"), "{\"text\":\"hi\"}");
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/log")).build(),
                        HttpResponse.BodyHandlers.ofString());
                for (JsonNode s : M.readTree(r.body()).path("seen")) {
                    String line = s.asText();
                    if (line.startsWith("open ") && !line.startsWith("open 2026-07-28 ")) {
                        throw fail("non-2026 request on the 2026 mount: " + q(line));
                    }
                }
            }
        });

        scenario("mrtr_input", () -> {
            try (McpSource src = load(open, new McpSource.LoadOptions().waitFor(Mcp2026Client::answers))) {
                expect(src, "srv_greet", null, "Hello, toolnexus!");
            }
        });

        scenario("mrtr_confirm", () -> {
            try (McpSource src = load(open, new McpSource.LoadOptions().waitFor(Mcp2026Client::answers))) {
                expect(src, "srv_wipe", null, "wiped");
            }
        });

        scenario("mrtr_pending", () -> {
            try (McpSource src = load(open, new McpSource.LoadOptions())) {
                ToolResult r = run(src, "srv_greet", null);
                Request req = ToolResult.pendingOf(r);
                if (req == null || !"input".equals(req.kind()) || !"What is your name?".equals(req.prompt())) {
                    throw fail("not pending input: " + r);
                }
            }
        });

        scenario("skills", () -> {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().mcpConfig(m("mcpServers", m("srv", open))))) {
                Tool skill = tk.get("skill");
                if (skill == null) throw fail("no skill tool");
                ToolResult r = skill.execute(m("name", "hello-world"), new ToolContext());
                String body = skillBody(skillsDir);
                if (r.isError() || r.output() == null || !r.output().contains(body)) {
                    String o = String.valueOf(r.output());
                    throw fail("remote skill output lacks the SKILL.md body: " + o.substring(0, Math.min(200, o.length())));
                }
            }
        });

        scenario("tool_search", () -> {
            try (McpSource src = load(m("url", base + "/open/mcp", "toolSearch", true, "description", "interop"),
                    new McpSource.LoadOptions().waitFor(Mcp2026Client::answers))) {
                List<Tool> ts = src.tools();
                if (ts.size() != 2) throw fail("tool search exposes " + ts.size() + " tools, want 2");
                ToolResult r = ts.get(0).execute(m("query", "echo text"), new ToolContext());
                if (r.output() == null || !r.output().startsWith("[{\"name\":\"echo\"")) {
                    String o = String.valueOf(r.output());
                    throw fail("search output " + o.substring(0, Math.min(200, o.length())));
                }
                r = ts.get(1).execute(m("name", "greet"), new ToolContext());
                if (!"Hello, toolnexus!".equals(r.output())) throw fail("call_tool greet " + q(r.output()));
            }
        });

        Map<String, Object> authCfg = m("url", base + "/mcp", "auth", m("clientId", "interop-client", "allowHttp", true));
        MemoryTokenStore store = new MemoryTokenStore();
        AtomicReference<McpSource> authed = new AtomicReference<>();
        scenario("oauth_preregistered", () -> {
            McpSource src = load(authCfg, new McpSource.LoadOptions().openUrl(Mcp2026Client::get)
                    .tokenStore(store).waitFor(Mcp2026Client::answers));
            authed.set(src);
            expect(src, "srv_echo", m("text", "authed"), "{\"text\":\"authed\"}");
        });

        scenario("oauth_stepup", () -> {
            McpSource src = authed.get();
            if (src == null) throw fail("needs oauth_preregistered");
            try (src) {
                expect(src, "srv_whoami", null, "dev-user");
                Token tok = store.load(base + "/mcp").orElse(null);
                if (!hasScope(tok, "mcp:whoami")) throw fail("stored token scopes lack mcp:whoami: " + tok);
            }
        });

        scenario("oauth_cimd", () -> {
            HttpServer hs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String docUrl = "http://127.0.0.1:" + hs.getAddress().getPort() + "/client.json";
            hs.createContext("/", ex -> {
                byte[] b = M.writeValueAsBytes(m("client_id", docUrl, "client_name", "interop CIMD",
                        "redirect_uris", List.of("http://127.0.0.1/callback")));
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (var o = ex.getResponseBody()) { o.write(b); }
            });
            hs.start();
            try {
                AtomicReference<String> bad = new AtomicReference<>();
                String want = "client_id=" + URLEncoder.encode(docUrl, StandardCharsets.UTF_8);
                McpSource.OpenUrl follow = u -> {
                    if (!u.contains(want)) bad.set("authorize URL lacks the CIMD client_id: " + u);
                    get(u);
                };
                try (McpSource src = load(m("url", base + "/mcp", "auth", m("clientMetadataUrl", docUrl, "allowHttp", true)),
                        new McpSource.LoadOptions().openUrl(follow))) {
                    expect(src, "srv_echo", m("text", "cimd"), "{\"text\":\"cimd\"}");
                }
                if (bad.get() != null) throw fail(bad.get());
            } finally {
                hs.stop(0);
            }
        });

        scenario("oauth_no_identity", () -> {
            try (McpSource src = McpSource.load(m("srv", m("url", base + "/mcp", "auth", m("allowHttp", true))),
                    new McpSource.LoadOptions().openUrl(u -> { }))) {
                if (!"failed".equals(src.status().get("srv"))) throw fail("status " + src.status() + ", want failed");
            }
        });

        scenario("legacy_fallback", () -> {
            try (McpSource src = load(m("url", base + "/legacy/mcp"), new McpSource.LoadOptions())) {
                expect(src, "srv_echo", m("text", "old"), "{\"text\":\"old\"}");
            }
        });
    }

    /** hello-world's SKILL.md without its frontmatter, trimmed. */
    static String skillBody(String dir) throws Exception {
        String s = Files.readString(Path.of(dir, "hello-world", "SKILL.md")).replace("\r\n", "\n");
        if (s.startsWith("---\n")) {
            int i = s.indexOf("\n---", 4);
            if (i >= 0) s = s.substring(i + 4);
        }
        return s.strip();
    }

    // ---- live Keycloak (interop/mcp2026/keycloak) ----

    static final String KC_CLIENT_ID = "toolnexus-mcp";
    static final String KC_USER = "alice";
    static final String KC_PASSWORD = "alice-test-pw"; // throwaway user of the local-only realm-toolnexus.json
    static final Pattern KC_LOGIN_FORM = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    static final Pattern KC_ANY_FORM = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    static String form(Map<String, String> f) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** A headless browser: follow the authorize URL, submit Keycloak's login (and consent) form, deliver the callback. */
    static McpSource.OpenUrl kcOpener() {
        HttpClient hc = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
        return authUrl -> {
            // Keycloak marks its session cookies Secure even on http://localhost (a browser's secure
            // context); java.net.CookieManager would drop them, so keep a tiny jar by hand.
            Map<String, String> jar = new LinkedHashMap<>();
            HttpResponse<String> resp = follow(hc, jar, HttpRequest.newBuilder(URI.create(authUrl)));
            for (int page = 0; resp.statusCode() == 200 && page < 3; page++) {
                String body = resp.body();
                Matcher lm = KC_LOGIN_FORM.matcher(body);
                Matcher am = KC_ANY_FORM.matcher(body);
                String action;
                Map<String, String> f = new LinkedHashMap<>();
                if (lm.find()) {
                    action = lm.group(1);
                    f.put("username", KC_USER);
                    f.put("password", KC_PASSWORD);
                    f.put("credentialId", "");
                } else if (am.find() && body.contains("name=\"accept\"")) {
                    action = am.group(1);
                    f.put("accept", "Yes");
                } else {
                    break;
                }
                URI target = resp.uri().resolve(unescape(action));
                resp = follow(hc, jar, HttpRequest.newBuilder(target)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(f))));
            }
            if (resp.statusCode() != 302) throw new Exception("keycloak login: HTTP " + resp.statusCode());
            get(resp.headers().firstValue("Location").orElseThrow());
        };
    }

    /** Follows redirects by hand, stopping at (and returning) a redirect to the 127.0.0.1 callback. */
    static HttpResponse<String> follow(HttpClient hc, Map<String, String> jar, HttpRequest.Builder rb) throws Exception {
        HttpResponse<String> resp = send(hc, jar, rb);
        for (int i = 0; i < 10; i++) {
            int sc = resp.statusCode();
            if (sc < 300 || sc > 399) return resp;
            String loc = resp.headers().firstValue("Location").orElse(null);
            if (loc == null) return resp;
            URI next = resp.uri().resolve(loc);
            if ("127.0.0.1".equals(next.getHost())) return resp;
            resp = send(hc, jar, HttpRequest.newBuilder(next));
        }
        return resp;
    }

    static HttpResponse<String> send(HttpClient hc, Map<String, String> jar, HttpRequest.Builder rb) throws Exception {
        if (!jar.isEmpty()) {
            StringBuilder c = new StringBuilder();
            jar.forEach((k, v) -> c.append(c.length() > 0 ? "; " : "").append(k).append('=').append(v));
            rb.header("Cookie", c.toString());
        }
        HttpResponse<String> resp = hc.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : resp.headers().allValues("Set-Cookie")) {
            String nv = sc.split(";", 2)[0];
            int eq = nv.indexOf('=');
            if (eq <= 0) continue;
            String name = nv.substring(0, eq).trim(), value = nv.substring(eq + 1).trim();
            boolean expired = sc.toLowerCase().matches(".*max-age=0(;.*|$)");
            if (expired || value.isEmpty()) jar.remove(name); else jar.put(name, value);
        }
        return resp;
    }

    static void keycloak(String mcpUrl) {
        MemoryTokenStore store = new MemoryTokenStore();
        AtomicReference<McpSource> ref = new AtomicReference<>();
        scenario("kc_login", () -> {
            McpSource src = load(m("url", mcpUrl, "auth", m("clientId", KC_CLIENT_ID, "allowHttp", true)),
                    new McpSource.LoadOptions().openUrl(kcOpener()).tokenStore(store).waitFor(Mcp2026Client::answers));
            ref.set(src);
            expect(src, "srv_echo", m("text", "keycloak"), "{\"text\":\"keycloak\"}");
        });
        scenario("kc_stepup", () -> {
            McpSource src = ref.get();
            if (src == null) throw fail("needs kc_login");
            try (src) {
                ToolResult r = run(src, "srv_whoami", null);
                if (r.isError() || r.output() == null || r.output().isEmpty()) throw fail("whoami " + r);
                Token tok = store.load(mcpUrl).orElse(null);
                if (!hasScope(tok, "mcp:greet")) throw fail("stored token scopes lack mcp:greet: " + tok);
            }
        });
    }
}
