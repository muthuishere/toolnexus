package io.github.muthuishere.toolnexus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A2A (agent-to-agent) — OUTBOUND: call remote A2A agents. Mirrors the JS
 * reference ({@code js/src/a2a.ts}) byte-for-byte on the wire.
 *
 * <p>A remote agent publishes an Agent Card at {@code /.well-known/agent-card.json}
 * describing its skills. Each advertised skill becomes a uniform {@link Tool}
 * ({@code source:"a2a"}) whose {@code execute} performs one JSON-RPC
 * {@code SendMessage} (non-blocking) to get a Task id, then polls {@code GetTask}
 * until the Task reaches a terminal state — mapping the Task's artifact/message
 * text to a {@link ToolResult}.
 *
 * <p>This is a genuine subset of real A2A (JSON-RPC 2.0 over one HTTP POST
 * endpoint), so a toolnexus agent interoperates with real A2A peers. See
 * {@code ../../SPEC.md §7A} and {@code openspec/changes/add-a2a-agents}. Reuses
 * the {@code ${ENV}} header expansion from {@link McpSource}; secrets live in the
 * environment and are never logged.
 */
public final class A2A {
    private A2A() {}

    private static final long DEFAULT_TIMEOUT = 300_000L;
    private static final long DEFAULT_POLL_EVERY = 1_000L;

    /** Terminal A2A task states — polling stops once one of these is reached. */
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "canceled");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    // -----------------------------------------------------------------------
    // Agent descriptor + factory + config parsing.
    // -----------------------------------------------------------------------

    /**
     * An Agent descriptor pointing at a remote Agent Card URL.
     *
     * @param card      URL of the Agent Card ({@code /.well-known/agent-card.json})
     * @param headers   {@code ${ENV_VAR}} header values, expanded at call time, never logged (nullable)
     * @param timeout   overall poll budget in ms (default 300000; nullable)
     * @param pollEvery interval between {@code GetTask} polls in ms (default 1000; nullable)
     */
    public record Agent(String card, Map<String, String> headers, Long timeout, Long pollEvery) {}

    /** Build an Agent descriptor from a bare card URL. */
    public static Agent agent(String card) {
        return new Agent(card, null, null, null);
    }

    /** Build an Agent descriptor pointing at a remote Agent Card URL. */
    public static Agent agent(String card, Map<String, String> headers, Long timeout, Long pollEvery) {
        return new Agent(card, headers, timeout, pollEvery);
    }

    /** MCP {@code isEnabled} precedence: {@code disabled:true} wins, then {@code enabled:false}. */
    private static boolean isEnabled(Map<String, Object> cfg) {
        if (Boolean.TRUE.equals(cfg.get("disabled"))) return false;
        if (Boolean.FALSE.equals(cfg.get("enabled"))) return false;
        return true;
    }

    /**
     * Parse an {@code agents} block ({@code Map<name, AgentConfig>}) into Agent
     * descriptors, skipping disabled entries. The config key is just an
     * identifier — a tool's name prefix comes from the fetched card's {@code name},
     * not the key. Mirrors the JS {@code parseAgentsConfig}.
     */
    @SuppressWarnings("unchecked")
    public static List<Agent> parseAgentsConfig(Map<String, Object> block) {
        List<Agent> out = new ArrayList<>();
        if (block == null) return out;
        for (Object v : block.values()) {
            if (!(v instanceof Map)) continue;
            Map<String, Object> cfg = (Map<String, Object>) v;
            if (!(cfg.get("card") instanceof String)) continue;
            if (!isEnabled(cfg)) continue;
            out.add(agent(
                    (String) cfg.get("card"),
                    stringHeaders(cfg.get("headers")),
                    longOf(cfg.get("timeout")),
                    longOf(cfg.get("pollEvery"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringHeaders(Object o) {
        if (!(o instanceof Map)) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static Long longOf(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }

    // -----------------------------------------------------------------------
    // Resolve an Agent to its tools.
    // -----------------------------------------------------------------------

    /**
     * Resolve an Agent to its tools: fetch the card, read {@code skills[]}, and
     * produce one Tool per skill. The agent name prefix comes from the card's
     * {@code name}. Mirrors the JS {@code agentTools}.
     */
    public static List<Tool> agentTools(Agent ag) throws Exception {
        long timeout = ag.timeout() != null ? ag.timeout() : DEFAULT_TIMEOUT;
        Map<String, String> headers = expandOr(ag.headers());
        Map<String, Object> card = fetchCard(ag.card(), headers, timeout);
        String agentName = card.get("name") instanceof String ? (String) card.get("name") : "agent";
        // The card's `url` is the JSON-RPC endpoint; fall back to the card origin.
        String endpoint = card.get("url") instanceof String ? (String) card.get("url") : originOf(ag.card());
        List<Tool> out = new ArrayList<>();
        for (Object s : asList(card.get("skills"))) {
            Map<String, Object> skill = asMap(s);
            if (skill == null) continue;
            out.add(skillTool(agentName, endpoint, skill, ag));
        }
        return out;
    }

    private static final Map<String, Object> TASK_SCHEMA = taskSchema();

    private static Map<String, Object> taskSchema() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "The task to send to the agent, in natural language.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task", task);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("task"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Build one {@code source:"a2a"} Tool for a single advertised skill of an agent. */
    private static Tool skillTool(String agentName, String endpoint, Map<String, Object> skill, Agent ag) {
        String skillId = skill.get("id") instanceof String
                ? (String) skill.get("id")
                : (skill.get("name") instanceof String ? (String) skill.get("name") : "");
        final String name = Tool.sanitize(agentName) + "_" + Tool.sanitize(skillId);
        final long timeout = ag.timeout() != null ? ag.timeout() : DEFAULT_TIMEOUT;
        final long pollEvery = ag.pollEvery() != null ? ag.pollEvery() : DEFAULT_POLL_EVERY;
        final String description = skill.get("description") instanceof String
                ? (String) skill.get("description")
                : (skill.get("name") instanceof String ? (String) skill.get("name") : skillId);

        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return TASK_SCHEMA;
            }

            @Override
            public String source() {
                return "a2a";
            }

            @Override
            public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                long start = System.currentTimeMillis();
                long budget = ctx != null && ctx.timeoutMs() != null ? ctx.timeoutMs() : timeout;
                Map<String, String> headers = expandOr(ag.headers());
                Object taskArg = args == null ? null : args.get("task");
                String taskText = taskArg == null ? "" : String.valueOf(taskArg);
                int polls = 0;
                String taskId = "";
                String state = "submitted";
                try {
                    // 1. SendMessage (non-blocking) → a submitted Task.
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("role", "user");
                    message.put("messageId", UUID.randomUUID().toString());
                    message.put("parts", List.of(textPart(taskText)));
                    Map<String, Object> sendParams = new LinkedHashMap<>();
                    sendParams.put("message", message);
                    sendParams.put("configuration", Map.of("blocking", false));

                    Map<String, Object> task = asMap(jsonRpc(endpoint, "SendMessage", sendParams, headers, budget));
                    taskId = task != null && task.get("id") instanceof String ? (String) task.get("id") : "";
                    state = stateOf(task, "submitted");

                    // 2. Poll GetTask until terminal / timeout / cancel.
                    while (!TERMINAL.contains(state)) {
                        if (cancelled(ctx)) {
                            state = "canceled";
                            return new ToolResult("A2A task " + taskId + " canceled", true,
                                    meta(agentName, taskId, state, polls, start));
                        }
                        if (System.currentTimeMillis() - start >= budget) {
                            return new ToolResult("A2A task " + taskId + " timed out after " + budget
                                    + "ms (state=" + state + ")", true, meta(agentName, taskId, state, polls, start));
                        }
                        sleep(pollEvery, ctx);
                        // Cancelled during the wait → stop before another GetTask.
                        if (cancelled(ctx)) {
                            state = "canceled";
                            return new ToolResult("A2A task " + taskId + " canceled", true,
                                    meta(agentName, taskId, state, polls, start));
                        }
                        task = asMap(jsonRpc(endpoint, "GetTask", Map.of("id", taskId), headers, budget));
                        polls++;
                        state = stateOf(task, state);
                    }

                    // 3. Map the terminal Task → ToolResult.
                    if ("completed".equals(state)) {
                        return new ToolResult(extractOutput(task), false, meta(agentName, taskId, state, polls, start));
                    }
                    String detail = statusMessageText(task);
                    return new ToolResult("A2A task " + taskId + " " + state + (detail.isEmpty() ? "" : ": " + detail),
                            true, meta(agentName, taskId, state, polls, start));
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    return new ToolResult(msg, true, meta(agentName, taskId, state, polls, start));
                }
            }
        };
    }

    // -----------------------------------------------------------------------
    // JSON-RPC transport (single POST) + card fetch. Reuses McpSource's ${ENV}
    // header expansion, request timeout, and non-2xx → error mapping.
    // -----------------------------------------------------------------------

    /** POST one JSON-RPC 2.0 request and return {@code result} (throws on error/non-2xx). */
    @SuppressWarnings("unchecked")
    private static Object jsonRpc(String endpoint, String method, Object params, Map<String, String> headers,
                                  long timeout) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", UUID.randomUUID().toString());
        req.put("method", method);
        req.put("params", params);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.putAll(headers);

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeout));
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getValue() != null) rb.header(e.getKey(), e.getValue());
        }
        rb.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(req), StandardCharsets.UTF_8));

        HttpResponse<String> res = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        String text = res.body();
        if (status < 200 || status >= 300) throw new RuntimeException("HTTP " + status + ": " + text);
        Map<String, Object> payload = Json.parseObjectLoose(text);
        Object err = payload.get("error");
        if (err instanceof Map) {
            Map<String, Object> e = (Map<String, Object>) err;
            Object msg = e.get("message");
            if (msg instanceof String) throw new RuntimeException((String) msg);
            Object code = e.get("code");
            throw new RuntimeException(("JSON-RPC error " + (code == null ? "" : code)).trim());
        }
        return payload.get("result");
    }

    /** Fetch and parse the Agent Card at its URL (GET). */
    private static Map<String, Object> fetchCard(String cardUrl, Map<String, String> headers, long timeout)
            throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cardUrl))
                .timeout(Duration.ofMillis(timeout))
                .GET();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null) rb.header(e.getKey(), e.getValue());
        }
        HttpResponse<String> res = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        String text = res.body();
        if (status < 200 || status >= 300) throw new RuntimeException("HTTP " + status + ": " + text);
        return Json.toMap(text);
    }

    // -----------------------------------------------------------------------
    // Task text extraction.
    // -----------------------------------------------------------------------

    /** Concatenate a Task's text output: artifact text parts, else last agent message. */
    private static String extractOutput(Map<String, Object> task) {
        if (task == null) return "";
        List<String> parts = new ArrayList<>();
        for (Object art : asList(task.get("artifacts"))) {
            Map<String, Object> artifact = asMap(art);
            if (artifact == null) continue;
            for (Object p : asList(artifact.get("parts"))) {
                String t = textOf(p);
                if (t != null) parts.add(t);
            }
        }
        if (!parts.isEmpty()) return String.join("\n", parts);
        // Fallback: the last agent message in history.
        List<Object> history = asList(task.get("history"));
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = asMap(history.get(i));
            if (msg == null || !"agent".equals(msg.get("role"))) continue;
            String text = joinTextParts(msg.get("parts"));
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    /** Text of a Task's status.message (used for failed/canceled error output). */
    private static String statusMessageText(Map<String, Object> task) {
        if (task == null) return "";
        Map<String, Object> status = asMap(task.get("status"));
        if (status == null) return "";
        Map<String, Object> msg = asMap(status.get("message"));
        if (msg == null) return "";
        return joinTextParts(msg.get("parts"));
    }

    private static String joinTextParts(Object partsObj) {
        List<String> out = new ArrayList<>();
        for (Object p : asList(partsObj)) {
            String t = textOf(p);
            if (t != null) out.add(t);
        }
        return String.join("\n", out);
    }

    /** A part's text iff {@code kind == "text"} and {@code text} is a string, else null. */
    private static String textOf(Object part) {
        Map<String, Object> pm = asMap(part);
        if (pm == null) return null;
        if (!"text".equals(pm.get("kind"))) return null;
        return pm.get("text") instanceof String ? (String) pm.get("text") : null;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "text");
        p.put("text", text);
        return p;
    }

    // -----------------------------------------------------------------------
    // small helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> meta(String agent, String taskId, String state, int polls, long start) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", agent);
        m.put("taskId", taskId);
        m.put("state", state);
        m.put("polls", polls);
        m.put("ms", System.currentTimeMillis() - start);
        return m;
    }

    private static String stateOf(Map<String, Object> task, String fallback) {
        if (task == null) return fallback;
        Map<String, Object> status = asMap(task.get("status"));
        if (status == null) return fallback;
        Object st = status.get("state");
        return st instanceof String ? (String) st : fallback;
    }

    private static boolean cancelled(ToolContext ctx) {
        return ctx != null && ctx.isCancelled();
    }

    /** Cancellation-aware sleep — returns early (without error) if {@code ctx} is cancelled. */
    private static void sleep(long ms, ToolContext ctx) {
        long deadline = System.currentTimeMillis() + ms;
        while (true) {
            if (cancelled(ctx)) return;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return;
            try {
                Thread.sleep(Math.min(remaining, 25));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Map<String, String> expandOr(Map<String, String> headers) {
        Map<String, String> m = McpSource.expandEnvHeaders(headers);
        return m == null ? Map.of() : m;
    }

    private static String originOf(String cardUrl) {
        URI u = URI.create(cardUrl);
        return u.getScheme() + "://" + u.getAuthority();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }
}
