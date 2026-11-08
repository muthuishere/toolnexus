package io.github.muthuishere.toolnexus.interop;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.McpDevAuthServer;
import io.github.muthuishere.toolnexus.McpHandler;
import io.github.muthuishere.toolnexus.McpServerAuth;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Java SERVER runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md). Mirrors
 * golang/internal/interop/mcp2026server. Test tooling: lives in the {@code interop} source set and
 * never ships in the published jar.
 *
 * <pre>Mcp2026Server [--keycloak] &lt;skillsDir&gt;</pre>
 */
public final class Mcp2026Server {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String KEYCLOAK_ISSUER = "http://localhost:8180/realms/toolnexus";
    private static final int KEYCLOAK_PORT = 7199;

    private static Tool tool(String name, String desc, Map<String, Object> schema,
                             BiFunction<Map<String, Object>, ToolContext, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String source() { return "native"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return fn.apply(args, ctx); }
        };
    }

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(List.of(argv));
        boolean keycloak = !args.isEmpty() && args.get(0).equals("--keycloak");
        if (keycloak) args.remove(0);
        Path skillsDir = Path.of(args.isEmpty() ? "../examples/skills" : args.get(0));

        String issuer = KEYCLOAK_ISSUER;
        McpDevAuthServer as = null;
        if (!keycloak) {
            as = McpDevAuthServer.start(new McpDevAuthServer.Options()
                    .clients(Map.of("interop-client", List.of("http://127.0.0.1/callback"))));
            issuer = as.issuer();
        }

        Map<String, Object> obj = Map.of("type", "object");
        Map<String, Object> nameSchema = Map.of("type", "object",
                "properties", Map.of("name", Map.of("type", "string")), "required", List.of("name"));
        List<Tool> tools = List.of(
                tool("echo", "Echo the given text back.", Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text")),
                        (a, c) -> {
                            String s = a != null && a.get("text") instanceof String t ? t : "";
                            return ToolResult.ok(s).withStructured(Map.of("text", s));
                        }),
                tool("greet", "Greet the user; asks for their name.", obj, (a, c) -> {
                    if (c != null && c.answer() != null && c.answer().ok()) {
                        Object n = c.answer().data() == null ? null : c.answer().data().get("name");
                        return ToolResult.ok("Hello, " + (n instanceof String s ? s : "") + "!");
                    }
                    return ToolResult.pending(new Request("name", "input", "What is your name?", null,
                            Map.of("schema", nameSchema), null));
                }),
                tool("wipe", "Destructive; asks for confirmation first.", obj, (a, c) -> ToolResult.ok("wiped")),
                tool("whoami", "Who is calling.", obj, (a, c) -> {
                    Object id = McpHandler.identityFrom(c);
                    return ToolResult.ok(id instanceof McpServerAuth.Principal p ? p.subject() : "");
                }));

        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));

        List<String> seen = new ArrayList<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        BiFunction<String, HttpHandler, HttpHandler> logged = (mount, h) -> ex -> {
            synchronized (seen) {
                seen.add(mount + " " + header(ex, "MCP-Protocol-Version") + " " + header(ex, "Mcp-Method"));
            }
            h.handle(ex);
        };
        java.util.function.Supplier<McpHandler.Config> cfg = () -> new McpHandler.Config()
                .name("interop").version("1.0.0")
                .protocolVersions(McpHandler.PROTOCOL_2026)
                .listTools(ctx -> tools)
                .listSkills(ctx -> McpHandler.loadMcpSkills(skillsDir))
                .beforeCall((ctx, t, raw) -> t.name().equals("wipe")
                        ? McpHandler.Decision.confirm("Really wipe?") : McpHandler.Decision.allow())
                .onCall(ev -> { synchronized (seen) { calls.merge(ev.name(), 1, Integer::sum); } });

        McpServerAuth auth = new McpServerAuth(issuer)
                .scopes(Map.of("*", List.of("mcp"), "whoami", List.of("mcp:whoami")));
        int port = 0;
        if (keycloak) {
            // The realm's audience mapper pins aud = http://127.0.0.1:7199/mcp.
            port = KEYCLOAK_PORT;
            auth.audience("http://127.0.0.1:" + KEYCLOAK_PORT + "/mcp")
                    .scopes(Map.of("*", List.of("mcp:tools"), "whoami", List.of("mcp:greet")));
        }

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.createContext("/open/mcp", logged.apply("open", tk.mcpHandler(cfg.get())));
        srv.createContext("/mcp", logged.apply("auth", tk.mcpHandler(cfg.get().authenticate(auth::authenticate))));
        srv.createContext("/legacy/mcp", logged.apply("legacy", tk.mcpHandler(new McpHandler.Config()
                .stateless(true).listTools(ctx -> tools))));
        srv.createContext("/.well-known/oauth-protected-resource", auth.metadata());
        srv.createContext("/log", ex -> {
            byte[] b;
            synchronized (seen) {
                b = M.writeValueAsBytes(Map.of("seen", seen, "calls", calls));
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); }
        });
        srv.start();

        Map<String, String> line = new LinkedHashMap<>();
        line.put("base", "http://127.0.0.1:" + srv.getAddress().getPort());
        line.put("issuer", issuer);
        System.out.println(M.writeValueAsString(line));
        System.out.flush();
        // Serve until stdin closes (the runner owns our lifetime).
        while (System.in.read() >= 0) { /* drain */ }
        srv.stop(0);
        if (as != null) as.close();
        tk.close();
        System.exit(0);
    }

    private static String header(com.sun.net.httpserver.HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        return v == null ? "" : v;
    }
}
