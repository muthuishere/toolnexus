package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-turn translation types and the pure translation functions (SPEC.md §11, ADR-0011).
 *
 * <p>{@code LlmClient.translate} is toolnexus used as a pure wire-format translator: OpenAI
 * shapes in, exactly ONE provider call, OpenAI shapes out. No agent loop, no tool execution,
 * no conversation state — so a caller can run it statelessly.
 *
 * <p>It is the INBOUND half of the §5 adapters: {@code toOpenAI}/{@code toAnthropic}/{@code
 * toGemini} send declarations out, this reads the provider's tool calls back in.
 *
 * <p>Use it when the CALLER owns the conversation and executes tools itself (the standard
 * OpenAI function-calling posture). When toolnexus owns the conversation, use the agent loop
 * with relay tools instead (§10).
 */
public final class Translate {
    private Translate() {}

    /** An OpenAI-shaped chat request, handed over verbatim (§11). */
    public static final class Request {
        /**
         * The OpenAI {@code messages} array, verbatim — including assistant turns carrying
         * {@code tool_calls} and {@code tool}-role results carrying {@code tool_call_id}.
         */
        public List<Object> messages = new ArrayList<>();
        /**
         * The OpenAI {@code tools} array, verbatim. Declaration-only — nothing here is ever
         * executed.
         */
        public List<Object> tools;
        /**
         * Declares an ordinary toolkit's tools to the provider WITHOUT executing any of them —
         * MCP tools, skills, native functions, A2A agents, builtins. Composes with
         * {@link #tools}; toolkit declarations come first.
         */
        public Toolkit toolkit;
        /** The OpenAI {@code tool_choice}, verbatim. Null omits it. */
        public Object toolChoice;
        /** Overrides the system prompt. Null uses any {@code system} message in the list. */
        public String system;
        /** Overrides the per-provider default max tokens. 0 uses the default. */
        public int maxTokens;

        public Request messages(List<Object> m) { this.messages = m; return this; }
        public Request tools(List<Object> t) { this.tools = t; return this; }
        public Request toolkit(Toolkit tk) { this.toolkit = tk; return this; }
        public Request toolChoice(Object c) { this.toolChoice = c; return this; }
        public Request system(String s) { this.system = s; return this; }
        public Request maxTokens(int n) { this.maxTokens = n; return this; }
    }

    /**
     * One tool call the model asked for, in OpenAI shape (§11).
     *
     * @param id        the tool-call id the caller must echo on its {@code tool} result message
     * @param name      the function name
     * @param arguments arguments as a JSON <b>string</b> — the OpenAI wire form, echoable
     *                  byte-for-byte
     */
    public record ToolCall(String id, String name, String arguments) {}

    /** An OpenAI-shaped single-turn result (§11). */
    public static final class Result {
        /** Assistant text ("" when the model only called tools). */
        public String text = "";
        /** The tool calls the model emitted, in provider order. None is ever dropped. */
        public List<ToolCall> toolCalls = new ArrayList<>();
        /** The OpenAI finish reason. A turn with any tool call is always {@code "tool_calls"}. */
        public String finishReason = "stop";
        /** This single call's token usage. */
        public LlmClient.Usage usage = new LlmClient.Usage();
        /** The model that answered. */
        public String model = "";
        /** The provider's decoded response, for fields this type does not model. */
        public Map<String, Object> raw;

        /**
         * Renders the tool calls as an OpenAI {@code tool_calls} array, ready to put on an
         * assistant message. Convenience for assembling a response envelope.
         */
        public List<Object> toolCallsJson() {
            List<Object> out = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments());
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", tc.id());
                e.put("type", "function");
                e.put("function", fn);
                out.add(e);
            }
            return out;
        }
    }

    /**
     * Maps a provider stop reason onto an OpenAI finish reason. Tool calls win: a turn that
     * emitted any tool call is always {@code "tool_calls"} to a conforming client.
     */
    public static String finishReasonFor(boolean hasToolCalls, String providerStop) {
        if (hasToolCalls) return "tool_calls";
        if (providerStop == null) return "stop";
        return switch (providerStop) {
            case "max_tokens", "length" -> "length";
            case "refusal", "content_filter" -> "content_filter";
            default -> "stop";
        };
    }

    /**
     * Flattens an OpenAI {@code content} value to text: the string form and the parts-array
     * form. Non-text parts are ignored.
     */
    @SuppressWarnings("unchecked")
    public static String contentText(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (p instanceof Map<?, ?> pm && pm.get("text") instanceof String t) sb.append(t);
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Parses a tool-call arguments value into an object, tolerating both wire forms — some
     * clients send {@code arguments} as an object rather than a JSON string.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> argsObject(Object args) {
        if (args instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (args instanceof String s && !s.isBlank()) {
            try {
                Object parsed = Json.toMap(s);
                if (parsed instanceof Map<?, ?> pm) return (Map<String, Object>) pm;
            } catch (RuntimeException e) {
                // a malformed arguments string is not fatal — fall through to {}
            }
        }
        return new LinkedHashMap<>();
    }

    /** Renders a tool-call arguments value as the JSON string the OpenAI wire format uses. */
    public static String argsString(Object args) {
        if (args instanceof String s) return s;
        if (args instanceof Map<?, ?> m) return Json.stringify(m);
        return "{}";
    }

    /** Reads an assistant message's OpenAI {@code tool_calls}. */
    @SuppressWarnings("unchecked")
    public static List<ToolCall> toolCallsOf(Map<String, Object> message) {
        Object raw = message.get("tool_calls");
        List<ToolCall> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object e : list) {
            if (!(e instanceof Map<?, ?> tc)) continue;
            Object fnObj = tc.get("function");
            if (!(fnObj instanceof Map<?, ?> fn)) continue;
            String id = tc.get("id") instanceof String s ? s : "";
            String name = fn.get("name") instanceof String s ? s : "";
            out.add(new ToolCall(id, name, argsString(fn.get("arguments"))));
        }
        return out;
    }

    /** The result of converting an OpenAI message list: provider messages + hoisted system. */
    public record Converted(List<Object> messages, String system) {}

    /**
     * Converts an OpenAI {@code messages} array into Anthropic-native messages plus the
     * extracted system prompt, preserving the tool structure a text flattening destroys (§11):
     *
     * <ul>
     *   <li>an assistant turn's {@code tool_calls} become {@code tool_use} blocks, with
     *       {@code arguments} parsed back from its JSON string into an object;
     *   <li>a {@code tool}-role result becomes a {@code tool_result} block keyed by
     *       {@code tool_call_id}, MERGED into a single user turn when consecutive (providers
     *       expect one result-bearing turn answering the preceding assistant turn);
     *   <li>{@code system}/{@code developer} messages are hoisted out, since Anthropic takes
     *       system separately.
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static Converted openAIMessagesToAnthropic(List<Object> messages) {
        List<Object> out = new ArrayList<>();
        List<String> systemParts = new ArrayList<>();
        List<Object> pending = new ArrayList<>();

        Runnable flush = () -> {
            if (!pending.isEmpty()) {
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("role", "user");
                turn.put("content", new ArrayList<>(pending));
                out.add(turn);
                pending.clear();
            }
        };

        for (Object raw : messages == null ? List.of() : messages) {
            if (!(raw instanceof Map<?, ?> mm)) continue;
            Map<String, Object> m = (Map<String, Object>) mm;
            String role = m.get("role") instanceof String s ? s : "";
            switch (role) {
                case "system", "developer" -> {
                    flush.run();
                    String s = contentText(m.get("content"));
                    if (!s.isEmpty()) systemParts.add(s);
                }
                case "tool", "function" -> {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_result");
                    block.put("content", contentText(m.get("content")));
                    if (m.get("tool_call_id") instanceof String id && !id.isEmpty()) {
                        block.put("tool_use_id", id);
                    }
                    pending.add(block);
                }
                case "assistant" -> {
                    flush.run();
                    List<Object> blocks = new ArrayList<>();
                    String s = contentText(m.get("content"));
                    if (!s.isEmpty()) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("type", "text");
                        t.put("text", s);
                        blocks.add(t);
                    }
                    for (ToolCall tc : toolCallsOf(m)) {
                        Map<String, Object> use = new LinkedHashMap<>();
                        use.put("type", "tool_use");
                        use.put("id", tc.id());
                        use.put("name", tc.name());
                        use.put("input", argsObject(tc.arguments()));
                        blocks.add(use);
                    }
                    if (blocks.isEmpty()) continue; // an empty assistant turn would be rejected
                    Map<String, Object> turn = new LinkedHashMap<>();
                    turn.put("role", "assistant");
                    turn.put("content", blocks);
                    out.add(turn);
                }
                default -> {
                    flush.run();
                    String s = contentText(m.get("content"));
                    Map<String, Object> turn = new LinkedHashMap<>();
                    turn.put("role", "user");
                    if (!s.isEmpty()) {
                        turn.put("content", s);
                        out.add(turn);
                    } else if (m.get("content") instanceof List<?> blocks && !blocks.isEmpty()) {
                        turn.put("content", blocks);
                        out.add(turn);
                    }
                }
            }
        }
        flush.run();
        return new Converted(out, String.join("\n\n", systemParts));
    }

    /**
     * Converts an OpenAI {@code tools} array into Anthropic tool declarations. Entries that
     * are already provider-native pass through; anything unrecognized is skipped.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> openAIToolsToAnthropic(List<Object> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : tools == null ? List.of() : tools) {
            if (!(raw instanceof Map<?, ?> tm)) continue;
            Map<String, Object> t = (Map<String, Object>) tm;
            Object fnObj = t.get("function");
            if (!(fnObj instanceof Map<?, ?> fnm)) {
                if (t.get("name") != null) out.add(t); // already native
                continue;
            }
            Map<String, Object> fn = (Map<String, Object>) fnm;
            if (!(fn.get("name") instanceof String name) || name.isEmpty()) continue;
            Map<String, Object> decl = new LinkedHashMap<>();
            decl.put("name", name);
            if (fn.get("description") instanceof String d && !d.isEmpty()) decl.put("description", d);
            if (fn.get("parameters") instanceof Map<?, ?> params) {
                decl.put("input_schema", params);
            } else {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("type", "object");
                empty.put("properties", new LinkedHashMap<String, Object>());
                decl.put("input_schema", empty);
            }
            out.add(decl);
        }
        return out;
    }

    /**
     * Maps OpenAI {@code tool_choice} onto Anthropic's shape. Returns null for absent/"auto"
     * (the provider default) and for anything unrecognized.
     */
    public static Map<String, Object> openAIToolChoiceToAnthropic(Object choice) {
        if (choice instanceof String s) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (s.equals("required") || s.equals("any")) {
                m.put("type", "any");
                return m;
            }
            if (s.equals("none")) {
                m.put("type", "none");
                return m;
            }
            return null;
        }
        if (choice instanceof Map<?, ?> cm && cm.get("function") instanceof Map<?, ?> fn
                && fn.get("name") instanceof String name && !name.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "tool");
            m.put("name", name);
            return m;
        }
        return null;
    }

    /** True when the message list already carries a system-ish message. */
    public static boolean hasSystemMessage(List<Object> messages) {
        for (Object raw : messages == null ? List.of() : messages) {
            if (raw instanceof Map<?, ?> m && m.get("role") instanceof String r
                    && (r.equals("system") || r.equals("developer"))) {
                return true;
            }
        }
        return false;
    }
}
