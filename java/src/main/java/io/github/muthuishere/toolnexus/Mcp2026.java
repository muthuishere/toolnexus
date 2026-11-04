package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.mcp.Authorizer;
import io.github.muthuishere.toolnexus.mcp.Client;
import io.github.muthuishere.toolnexus.mcp.LoopbackAuthorizer;
import io.github.muthuishere.toolnexus.mcp.Mcp;
import io.github.muthuishere.toolnexus.mcp.McpException;
import io.github.muthuishere.toolnexus.mcp.MemoryTokenStore;
import io.github.muthuishere.toolnexus.mcp.OAuth;
import io.github.muthuishere.toolnexus.mcp.PauseAuthorizer;
import io.github.muthuishere.toolnexus.mcp.Pending;
import io.github.muthuishere.toolnexus.mcp.PendingException;
import io.github.muthuishere.toolnexus.mcp.RpcError;
import io.github.muthuishere.toolnexus.mcp.TokenStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.muthuishere.toolnexus.mcp.Mcp.list;
import static io.github.muthuishere.toolnexus.mcp.Mcp.map;
import static io.github.muthuishere.toolnexus.mcp.Mcp.obj;
import static io.github.muthuishere.toolnexus.mcp.Mcp.str;

/**
 * The MCP 2026-07-28 path of {@link McpSource} (SPEC §2.2–§2.8, §2A.1). {@link #connect} sends
 * {@code server/discover} first; a {@link Fallback} tells the caller to use the frozen SDK path (§2.F).
 * Owns no MCP SDK.
 */
final class Mcp2026 {
    private Mcp2026() {}

    /** §2.2a: discover says "old server" — use the frozen fallback (§2.F). */
    static final class Fallback extends Exception {
        Fallback(String why) { super(why, null, false, false); }
    }

    private static final int MAX_ROUNDS = 8;

    /** One server connected on the 2026 path. */
    static final class Conn implements AutoCloseable {
        final String name;
        final Client client;
        final long timeout;
        final Function<Request, Answer> waitFor;
        final Map<String, Object> discover;
        /** Paused rounds by §10 request id (MRTR input or OAuth pause). */
        private final Map<String, Round> paused = new ConcurrentHashMap<>();
        Path skillsRoot; // temp dir holding remote skills (deleted on close), or null

        Conn(String name, Client client, long timeout, Function<Request, Answer> waitFor, Map<String, Object> discover) {
            this.name = name;
            this.client = client;
            this.timeout = timeout;
            this.waitFor = waitFor;
            this.discover = discover;
        }

        @Override
        public void close() {
            client.close();
            if (skillsRoot != null) deleteTree(skillsRoot);
        }
    }

    /** The state of one {@code tools/call} across MRTR rounds (§2.5). */
    private static final class Round {
        final String tool;
        final Map<String, Object> args;
        Map<String, Object> responses;      // inputResponses being built for the next send (null on round 0)
        Object requestState;                // echoed verbatim
        Map<String, Object> requests;       // this round's inputRequests (ordered)
        String waitingKey;                  // the input request a paused round waits on
        String authId;                      // set when paused on OAuth (§2A.1 step 10)
        int round;

        Round(String tool, Map<String, Object> args) {
            this.tool = tool;
            this.args = args;
        }
    }

    // ------------------------------------------------------------------ connect

    /** Builds the client, sends {@code server/discover}; throws {@link Fallback} for an old server (§2.2a). */
    @SuppressWarnings("unchecked")
    static Conn connect(String name, Map<String, Object> cfg, boolean remote, long timeout,
                        McpSource.LoadOptions o, TokenStore store) throws Exception {
        Function<Request, Answer> waitFor = o.waitFor;
        Client client;
        if (remote) {
            client = new Client(String.valueOf(cfg.get("url")));
            Map<String, String> headers = McpSource.headersOf(cfg);
            if (headers != null) headers.forEach((k, v) -> { if (v != null) client.header(k, v); });
            client.auth(oauthFor(name, cfg, o, store));
            client.timeout(Duration.ofMillis(timeout));
        } else {
            ProcessBuilder pb = new ProcessBuilder(McpSource.commandArgv(name, cfg));
            Map<String, Object> extra = (Map<String, Object>) cfg.get("environment");
            if (extra == null) extra = (Map<String, Object>) cfg.get("env");
            if (extra != null) for (var e : extra.entrySet()) pb.environment().put(e.getKey(), String.valueOf(e.getValue()));
            if (cfg.get("cwd") != null) pb.directory(new File(String.valueOf(cfg.get("cwd"))));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT); // stderr passes through, never parsed
            client = Client.stdio(pb);
            client.timeout(Duration.ofMillis(Math.min(timeout, 5000))); // §2.2a: silent old stdio server ⇒ fallback
        }
        Map<String, Object> d;
        try {
            d = client.discover();
        } catch (RpcError e) {
            client.close();
            throw new Fallback("server/discover: " + e.getMessage());
        } catch (Client.HttpStatusException e) {
            client.close();
            // 400/404/405 per §2.2a. 500 too: the official Java SDK's stateless server (and so toolnexus's own
            // pre-2026 `serve`) answers an unknown pre-initialize method with HTTP 500. A server that is really
            // down then fails on the frozen path instead, so the outcome is still `failed`.
            if (e.status == 400 || e.status == 404 || e.status == 405 || e.status == 500) throw new Fallback(e.getMessage());
            throw e;
        } catch (Client.NotJsonRpcException e) {
            client.close();
            throw new Fallback(e.getMessage());
        } catch (PendingException e) {
            client.close();
            throw new McpException("authorization pending");
        } catch (McpException e) {
            client.close();
            if (!remote && Client.isTimeout(e)) throw new Fallback(e.getMessage());
            throw e;
        }
        if (!Client.supports2026(d)) {
            client.close();
            throw new Fallback("server/discover: 2026-07-28 not supported");
        }
        client.timeout(Duration.ofMillis(timeout));
        return new Conn(name, client, timeout, waitFor, d);
    }

    /** §2A.1 / §2A.1a: the OAuth client for a remote server, from its {@code auth} config + load options. */
    @SuppressWarnings("unchecked")
    static OAuth oauthFor(String server, Map<String, Object> cfg, McpSource.LoadOptions o, TokenStore store) {
        Map<String, Object> a = cfg.get("auth") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        OAuth oauth = new OAuth().store(store);
        if (a.get("clientId") instanceof String s) oauth.clientId(s);
        if (a.get("clientSecret") instanceof String s) oauth.clientSecret(s);
        if (a.get("tokenAuthMethod") instanceof String s) oauth.tokenAuthMethod(s);
        if (a.get("clientMetadataUrl") instanceof String s) oauth.clientMetadataUrl(s);
        if (a.get("clientIds") instanceof Map<?, ?> ids) {
            Map<String, String> m = new LinkedHashMap<>();
            ids.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            oauth.clientIds(m);
        }
        oauth.omitRefreshResource(Boolean.TRUE.equals(a.get("omitRefreshResource")));
        oauth.allowHttp(Boolean.TRUE.equals(a.get("allowHttp")));
        if ("pause".equals(a.get("mode"))) {
            String redirect = a.get("redirectUri") instanceof String s ? s : "";
            if (redirect.isEmpty())
                throw new IllegalArgumentException("mcp server \"" + server + "\": auth.redirectUri is required for mode \"pause\"");
            oauth.authorizer(o.waitFor != null ? new WaitForAuthorizer(redirect, o.waitFor) : new PauseAuthorizer(redirect));
        } else {
            McpSource.OpenUrl open = o.openUrl;
            oauth.authorizer(new LoopbackAuthorizer(open == null ? null : open::open));
        }
        return oauth;
    }

    /** {@code pause} mode with a {@code waitFor}: the §10 authorization request is answered inline. */
    private record WaitForAuthorizer(String redirect, Function<Request, Answer> waitFor)
            implements Authorizer, Authorizer.Session {
        @Override public Session begin() { return this; }
        @Override public String redirectUri() { return redirect; }
        @Override public void close() {}

        @Override
        public String await(Pending p) {
            Answer a = waitFor.apply(toRequest(p));
            return callbackUrl(a);
        }
    }

    static Request toRequest(Pending p) {
        return new Request(p.id(), "authorization", p.prompt(), p.url(), null, p.expiresAt());
    }

    /** §2A.1a: the pause answer → the callback URL, or {@code authorization denied: <reason|cancelled>}. */
    static String callbackUrl(Answer a) {
        if (a == null || !a.ok()) {
            String why = a != null && a.reason() != null && !a.reason().isEmpty() ? a.reason() : "cancelled";
            throw new McpException("authorization denied: " + why);
        }
        String cb = a.data() == null ? "" : str(a.data().get("callbackUrl"));
        if (cb.isEmpty()) throw new McpException("authorization answer has no data.callbackUrl");
        return cb;
    }

    // ------------------------------------------------------------------ tools

    /** tools/list (filtered by the per-server allowlist) → toolkit tools, or the two §2.8 meta tools. */
    @SuppressWarnings("unchecked")
    static List<Tool> tools(Conn c, Map<String, Object> cfg, Map<String, Boolean> filter, java.util.Set<String> disabled) {
        List<Map<String, Object>> defs = c.client.listTools();
        if (Boolean.TRUE.equals(cfg.get("toolSearch"))) {
            String about = cfg.get("description") instanceof String s && !s.isEmpty() ? s : c.name;
            java.util.function.Supplier<List<Map<String, Object>>> allowed = () -> {
                List<Map<String, Object>> keep = new ArrayList<>();
                for (Map<String, Object> d : applyFilter(c.name, c.client.listTools(), filter))
                    if (disabled == null || !disabled.contains(Tool.sanitize(c.name) + "_" + Tool.sanitize(str(d.get("name"))))) keep.add(d);
                return keep;
            };
            return searchTools(c, about, allowed);
        }
        List<Tool> out = new ArrayList<>();
        for (Map<String, Object> d : applyFilter(c.name, defs, filter)) out.add(serverTool(c, d));
        return out;
    }

    static List<Map<String, Object>> applyFilter(String server, List<Map<String, Object>> defs, Map<String, Boolean> filter) {
        if (filter == null || filter.isEmpty()) return defs;
        boolean hasTrue = filter.values().stream().anyMatch(Boolean.TRUE::equals);
        java.util.Set<String> present = new java.util.HashSet<>();
        for (Map<String, Object> d : defs) present.add(str(d.get("name")));
        for (String k : filter.keySet())
            if (!present.contains(k))
                System.err.println("[toolnexus] server \"" + server + "\" tools filter name \"" + k + "\" matched no tool");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : defs) {
            Boolean v = filter.get(str(d.get("name")));
            if (hasTrue ? Boolean.TRUE.equals(v) : !Boolean.FALSE.equals(v)) out.add(d);
        }
        return out;
    }

    static Map<String, Object> normalizeSchema(Object schema) {
        Map<String, Object> base = map(schema) == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map(schema));
        base.put("type", "object");
        Object props = base.get("properties");
        base.put("properties", props == null ? new LinkedHashMap<>() : props);
        base.put("additionalProperties", false);
        return base;
    }

    static ToolHints hints(Object annotations) {
        Map<String, Object> a = map(annotations);
        if (a == null) return null;
        Object ro = a.get("readOnlyHint"), de = a.get("destructiveHint"), ow = a.get("openWorldHint");
        if (ro == null && de == null && ow == null) return null;
        boolean readOnly = Boolean.TRUE.equals(ro);
        boolean destructive = !Boolean.FALSE.equals(de);
        boolean openWorld = !Boolean.FALSE.equals(ow);
        if (readOnly) destructive = false;
        return new ToolHints(readOnly, destructive, openWorld);
    }

    private static Tool serverTool(Conn c, Map<String, Object> def) {
        String mcpName = str(def.get("name"));
        String toolName = Tool.sanitize(c.name) + "_" + Tool.sanitize(mcpName);
        String description = str(def.get("description"));
        Map<String, Object> schema = normalizeSchema(def.get("inputSchema"));
        ToolHints h = hints(def.get("annotations"));
        return new Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String source() { return "mcp"; }
            @Override public ToolHints hints() { return h; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return callTool(c, mcpName, args, ctx);
            }
        };
    }

    /** §2.8: {@code <server>_search_tools} + {@code <server>_call_tool}. */
    @SuppressWarnings("unchecked")
    private static List<Tool> searchTools(Conn c, String about,
                                          java.util.function.Supplier<List<Map<String, Object>>> allowed) {
        List<Map<String, Object>> meta = Client.searchMetaTools(about);
        String prefix = Tool.sanitize(c.name) + "_";
        Map<String, Object> searchDef = meta.get(0), callDef = meta.get(1);
        Tool search = new Tool() {
            @Override public String name() { return prefix + "search_tools"; }
            @Override public String description() { return str(searchDef.get("description")); }
            @Override public Map<String, Object> inputSchema() { return (Map<String, Object>) searchDef.get("inputSchema"); }
            @Override public String source() { return "mcp"; }
            @Override public ToolHints hints() { return ToolHints.READ_ONLY; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                Map<String, Object> meta = new LinkedHashMap<>(Map.of("server", c.name));
                try {
                    Map<String, Object> a = args == null ? Map.of() : args;
                    int limit = a.get("limit") instanceof Number n && n.doubleValue() > 0 ? n.intValue() : 5;
                    List<Map<String, Object>> hits = c.client.searchTools(allowed.get(), str(a.get("query")), limit, about);
                    return new ToolResult(Mcp.jsonOrdered(hits), false, meta);
                } catch (Exception e) {
                    return new ToolResult(msg(e), true, meta);
                }
            }
        };
        Tool call = new Tool() {
            @Override public String name() { return prefix + "call_tool"; }
            @Override public String description() { return str(callDef.get("description")); }
            @Override public Map<String, Object> inputSchema() { return (Map<String, Object>) callDef.get("inputSchema"); }
            @Override public String source() { return "mcp"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                Map<String, Object> a = args == null ? Map.of() : args;
                String name = str(a.get("name"));
                boolean known;
                try {
                    known = allowed.get().stream().anyMatch(t -> name.equals(str(t.get("name"))));
                } catch (Exception e) {
                    return new ToolResult(msg(e), true, new LinkedHashMap<>(Map.of("server", c.name)));
                }
                if (!known) return ToolResult.error("unknown tool \"" + name + "\" on " + c.name,
                        new LinkedHashMap<>(Map.of("server", c.name)));
                Map<String, Object> inner = map(a.get("arguments"));
                return callTool(c, name, inner == null ? new LinkedHashMap<>() : inner, ctx);
            }
        };
        return List.of(search, call);
    }

    // ------------------------------------------------------------------ tools/call + MRTR (§2.5) + OAuth pause

    /** Runs one tools/call through MRTR rounds; a pause without {@code waitFor} returns a §10 pending result. */
    static ToolResult callTool(Conn c, String tool, Map<String, Object> args, ToolContext ctx) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("server", c.name);
        try {
            Answer ans = ctx == null ? null : ctx.answer();
            Round r = ans == null ? null : c.paused.remove(ans.id());
            Object step;
            if (r == null) {
                r = new Round(tool, args == null ? new LinkedHashMap<>() : args);
                step = send(c, r, ctx);
            } else if (r.authId != null) {
                String id = r.authId;
                r.authId = null;
                String cb = callbackUrl(ans);
                Round rr = r;
                step = interruptible(() -> {
                    try {
                        return c.client.auth().resume(id, cb);
                    } catch (PendingException pe) {
                        return pause(c, rr, pe);
                    }
                }, ctx);
            } else {
                r.responses.put(r.waitingKey, toInputResponse(ans));
                r.waitingKey = null;
                step = askOrSend(c, r, ctx);
            }
            while (true) {
                if (step instanceof ToolResult tr) return tr;
                Map<String, Object> res = map(step);
                String type = str(res.get("resultType"));
                if ("complete".equals(type)) return mapResult(res, meta);
                if (!"input_required".equals(type)) return new ToolResult("MCP tools/call " + tool + ": unknown resultType \"" + type + "\"", true, meta);
                if (++r.round >= MAX_ROUNDS) return new ToolResult("tools/call " + tool + ": too many input_required rounds", true, meta);
                r.requestState = res.get("requestState");
                Map<String, Object> reqs = map(res.get("inputRequests"));
                r.requests = reqs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(reqs);
                r.responses = new LinkedHashMap<>();
                step = askOrSend(c, r, ctx);
            }
        } catch (Exception e) {
            return new ToolResult(msg(e), true, meta);
        }
    }

    /** Asks every unanswered input request of the round (inline via waitFor, else pauses), then sends. */
    private static Object askOrSend(Conn c, Round r, ToolContext ctx) throws Exception {
        for (var e : r.requests.entrySet()) {
            if (r.responses.containsKey(e.getKey())) continue;
            Map<String, Object> rq = map(e.getValue());
            if (rq == null || !"elicitation/create".equals(rq.get("method")))
                throw new McpException("tools/call " + r.tool + ": unsupported input request \"" + e.getKey() + "\"");
            Request req = inputToRequest(map(rq.get("params")));
            if (c.waitFor != null) {
                r.responses.put(e.getKey(), toInputResponse(c.waitFor.apply(req)));
            } else {
                r.waitingKey = e.getKey();
                c.paused.put(req.id(), r);
                return ToolResult.pending(req);
            }
        }
        return send(c, r, ctx);
    }

    /** Sends the round's tools/call; an OAuth pause without waitFor becomes a §10 pending result. */
    private static Object send(Conn c, Round r, ToolContext ctx) throws Exception {
        Map<String, Object> p = obj("name", r.tool, "arguments", r.args);
        if (r.responses != null) p.put("inputResponses", r.responses);
        if (r.requestState != null) p.put("requestState", r.requestState);
        return interruptible(() -> {
            try {
                return c.client.call("tools/call", p);
            } catch (PendingException pe) {
                return pause(c, r, pe);
            }
        }, ctx);
    }

    /** §2A.1 step 10: the paused request is this exact round; resume sends it for the first time. */
    private static ToolResult pause(Conn c, Round r, PendingException pe) {
        Pending pd = pe.pending;
        r.authId = pd.id();
        c.paused.put(pd.id(), r);
        Map<String, Object> p = obj("name", r.tool, "arguments", r.args);
        if (r.responses != null) p.put("inputResponses", r.responses);
        if (r.requestState != null) p.put("requestState", r.requestState);
        c.client.auth().setRetry(pd.id(), () -> c.client.call("tools/call", p));
        return ToolResult.pending(toRequest(pd));
    }

    /** §2.5: an {@code elicitation/create} input request → a §10 Request. */
    static Request inputToRequest(Map<String, Object> params) {
        Map<String, Object> pr = params == null ? Map.of() : params;
        String id = "mrtr-" + io.github.muthuishere.toolnexus.mcp.OAuth.randomId();
        String message = str(pr.get("message"));
        if ("url".equals(pr.get("mode"))) return new Request(id, "authorization", message, str(pr.get("url")), null, null);
        Map<String, Object> data = map(pr.get("requestedSchema")) == null ? null : Map.of("schema", pr.get("requestedSchema"));
        return new Request(id, "input", message, null, data, null);
    }

    /** §2.5: Answer → input response (accept / decline / cancel). */
    static Map<String, Object> toInputResponse(Answer a) {
        if (a != null && a.ok()) return obj("action", "accept", "content", a.data() != null ? a.data() : Map.of());
        return obj("action", a != null && "declined".equals(a.reason()) ? "decline" : "cancel");
    }

    /** Runs work on a virtual thread, abandoning it when {@code ctx} is cancelled. */
    private static Object interruptible(Callable<Object> work, ToolContext ctx) throws Exception {
        if (ctx == null) return work.call();
        java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(work);
        Thread.ofVirtual().name("toolnexus-mcp-call").start(task);
        while (true) {
            if (ctx.isCancelled()) {
                task.cancel(true);
                throw new java.util.concurrent.CancellationException("MCP tool call cancelled");
            }
            try {
                return task.get(20, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // poll again
            } catch (java.util.concurrent.ExecutionException ee) {
                if (ee.getCause() instanceof Exception ex) throw ex;
                throw new RuntimeException(ee.getCause());
            }
        }
    }

    /** §2.F result mapping, on the raw 2026 result. */
    static ToolResult mapResult(Map<String, Object> res, Map<String, Object> meta) {
        List<Object> content = list(res.get("content"));
        List<ContentPart> parts = new ArrayList<>();
        List<String> texts = new ArrayList<>(), resourceText = new ArrayList<>();
        for (Object o : content) {
            Map<String, Object> ct = map(o);
            if (ct == null) continue;
            switch (str(ct.get("type"))) {
                case "text" -> texts.add(str(ct.get("text")));
                case "image" -> parts.add(ContentPart.ofBase64(ContentPart.IMAGE, str(ct.get("mimeType")), str(ct.get("data"))));
                case "audio" -> parts.add(ContentPart.ofBase64(ContentPart.AUDIO, str(ct.get("mimeType")), str(ct.get("data"))));
                case "resource_link" -> {
                    String mt = str(ct.get("mimeType"));
                    ContentPart p = ContentPart.ofUrl(ContentPart.FILE, mt.isEmpty() ? "application/octet-stream" : mt, str(ct.get("uri")));
                    String n = str(ct.get("name"));
                    parts.add(n.isEmpty() ? p : p.withName(n));
                }
                case "resource" -> {
                    Map<String, Object> rs = map(ct.get("resource"));
                    if (rs == null) break;
                    if (rs.get("blob") instanceof String blob) {
                        String mt = str(rs.get("mimeType"));
                        ContentPart p = ContentPart.ofBase64(ContentPart.FILE, mt.isEmpty() ? "application/octet-stream" : mt, blob);
                        String u = str(rs.get("uri"));
                        parts.add(u.isEmpty() ? p : p.withName(u));
                    } else if (!str(rs.get("text")).isEmpty()) {
                        resourceText.add(str(rs.get("text")));
                    }
                }
                default -> { }
            }
        }
        if (Boolean.TRUE.equals(res.get("isError"))) {
            List<String> nonBlank = texts.stream().filter(t -> !t.isBlank()).toList();
            String joined = String.join("\n\n", nonBlank);
            return new ToolResult(joined.isEmpty() ? "MCP tool returned an error" : joined, true, meta, parts);
        }
        if (res.get("structuredContent") != null)
            return new ToolResult(Json.stringify(res.get("structuredContent")), false, meta, parts);
        String output = String.join("\n", texts);
        String rt = String.join("\n", resourceText);
        if (!rt.isEmpty()) output = output.isEmpty() ? rt : output + "\n" + rt;
        if (output.isEmpty() && !parts.isEmpty()) output = ContentParts.describeForOutput(parts);
        return new ToolResult(output, false, meta, parts);
    }

    // ------------------------------------------------------------------ skills (§2.7)

    /** Downloads every remote skill into a temp dir when the server advertises the skills extension. */
    static void loadSkills(Conn c) {
        Map<String, Object> caps = map(c.discover.get("capabilities"));
        Map<String, Object> ext = caps == null ? null : map(caps.get("extensions"));
        if (ext == null || !ext.containsKey(Mcp.SKILLS_EXT)) return;
        Path root = null;
        try {
            root = Files.createTempDirectory("toolnexus-mcp-skills-");
            for (String uri : c.client.listSkills()) {
                try {
                    c.client.downloadSkill(uri, root);
                } catch (Exception e) {
                    System.err.println("[toolnexus] MCP server \"" + c.name + "\" skill " + uri + " skipped: " + msg(e));
                }
            }
            c.skillsRoot = root;
        } catch (Exception e) {
            if (root != null) deleteTree(root);
            System.err.println("[toolnexus] MCP server \"" + c.name + "\" skills failed: " + msg(e));
        }
    }

    static TokenStore defaultStore(McpSource.LoadOptions o) {
        return o.tokenStore != null ? o.tokenStore : new MemoryTokenStore();
    }

    static String msg(Throwable e) {
        return e.getMessage() == null ? String.valueOf(e) : e.getMessage();
    }

    static void deleteTree(Path root) {
        try (Stream<Path> w = Files.walk(root)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
