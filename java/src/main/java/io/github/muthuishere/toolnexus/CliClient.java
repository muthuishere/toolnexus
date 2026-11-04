package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has installed — and pays
 * for — becomes the model behind an ordinary in-process {@link LlmClient}, selected by the closed
 * {@link CliStyles} enum, never by a string.
 *
 * <p>Two transports, one constructor:
 * <ul>
 *   <li><b>ACP</b> (primary): when the style declares an ACP launch and the binary speaks it, the
 *       caller's whole toolkit is served as a local MCP server (loopback, per-session bearer token)
 *       and handed to the agent in {@code session/new} mcpServers. The agent calls the tools
 *       natively; {@code beforeTool}/{@code afterTool} hooks run at the server boundary.</li>
 *   <li><b>one-shot CLI</b> (fallback): the CLI is run once per model turn with the conversation
 *       rendered under a fixed preamble; its reply is parsed as an OpenAI-shaped envelope and tool
 *       calls go back to the toolnexus loop.</li>
 * </ul>
 *
 * <p>A style is data ({@code clistyles/*.json}, byte-identical to {@code conformance/cli-styles/})
 * and holds no binary: {@link Options#command} is always the caller's and is required.
 */
public final class CliClient implements AutoCloseable {

    /** One CLI invocation / one ACP prompt is bounded by this unless set (owner decision 2026-11-04). */
    public static final int DEFAULT_CALL_TIMEOUT_MS = 300000;

    /** The name the toolkit's {@code skill} tool is served under to an ACP agent (agents ship their own {@code skill}). */
    public static final String SERVED_SKILL_TOOL_NAME = "load_skill";

    /** Carries the bearer token to a stdio bridge. */
    public static final String MCP_TOKEN_ENV = "TOOLNEXUS_MCP_TOKEN";

    /** The MCP server name the agent sees. */
    static final String ACP_SERVER_NAME = "toolnexus";

    /** The fixed preamble every one-shot prompt starts with (byte-identical to conformance/cli-styles/preamble.txt). */
    public static final String PREAMBLE = resource("preamble.txt");

    // ---------------------------------------------------------------------------------------
    // Style data.
    // ---------------------------------------------------------------------------------------

    /** A style's ACP launch: args appended to the command, and optional project-file / hint data. */
    public static final class AcpLaunch {
        public List<String> args = new ArrayList<>();
        /** For agents that ignore session/new mcpServers: written into a fresh temp session cwd. */
        public String projectMcpFile;
        /** Appended to the system prompt of every ACP turn (a new system message when there is none). */
        public String systemHint;
        public String verified;
        public String notes;
    }

    /**
     * One CLI's invocation recipe — data shared by every port. {@code args} elements are strings or
     * optional groups ({@code List<String>}, kept only when a model is set). {@code prompt}:
     * stdin | arg | file. {@code answer}: stdout | out.
     */
    public static final class StyleDef {
        public String name;
        public List<Object> args;
        public String prompt;
        public String answer;
        public int timeoutMs;
        public Map<String, String> env;
        public String verified;
        public String notes;
        public AcpLaunch acp;

        @SuppressWarnings("unchecked")
        static StyleDef fromMap(Map<String, Object> m) {
            StyleDef d = new StyleDef();
            d.name = str(m.get("name"));
            d.args = m.get("args") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : null;
            d.prompt = str(m.get("prompt"));
            d.answer = str(m.get("answer"));
            d.timeoutMs = m.get("timeoutMs") instanceof Number n ? n.intValue() : 0;
            if (m.get("env") instanceof Map<?, ?> env) {
                d.env = new LinkedHashMap<>();
                env.forEach((k, v) -> d.env.put(String.valueOf(k), String.valueOf(v)));
            }
            d.verified = str(m.get("verified"));
            d.notes = str(m.get("notes"));
            if (m.get("acp") instanceof Map<?, ?> a) {
                AcpLaunch l = new AcpLaunch();
                if (a.get("args") instanceof List<?> xs) for (Object x : xs) l.args.add(String.valueOf(x));
                l.projectMcpFile = str(a.get("projectMcpFile"));
                l.systemHint = str(a.get("systemHint"));
                l.verified = str(a.get("verified"));
                l.notes = str(a.get("notes"));
                d.acp = l;
            }
            return d;
        }

        private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    }

    /** The built-in definition of a style; {@code null} for {@link CliStyles#CUSTOM}. */
    public static StyleDef styleDef(CliStyles style) {
        if (style == null || style == CliStyles.CUSTOM) return null;
        return StyleDef.fromMap(Json.toMap(resource(style.fileName() + ".json")));
    }

    /** Reads a user style from a file path, or from JSON text that starts with "{". Pass it as {@link Options#styleDef}. */
    public static StyleDef loadStyle(String pathOrJson) {
        String text = pathOrJson;
        if (!pathOrJson.strip().startsWith("{")) {
            try {
                text = Files.readString(Path.of(pathOrJson));
            } catch (IOException e) {
                throw new IllegalArgumentException("cli style: " + e.getMessage(), e);
            }
        }
        try {
            return StyleDef.fromMap(Json.toMap(text));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cli style: " + e.getMessage(), e);
        }
    }

    /** The exact bytes of an embedded style file or the preamble (for the drift test). */
    static String resource(String name) {
        try (InputStream in = CliClient.class.getResourceAsStream("clistyles/" + name)) {
            if (in == null) throw new IllegalStateException("toolnexus: missing embedded clistyles/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Options.
    // ---------------------------------------------------------------------------------------

    /**
     * Configures {@link #create}. {@link #command} is REQUIRED: the library never picks or defaults
     * the binary. {@code inProcess.model} is passed to the CLI unchanged (empty ⇒ the CLI's own
     * default model); the rest of {@code inProcess} (system prompt, hooks, store, …) configures the
     * client loop as for {@link InProcess#createClient}.
     */
    public static final class Options {
        public InProcess.Options inProcess = new InProcess.Options();
        /** Picks a built-in recipe. Required unless {@link #styleDef} is set. */
        public CliStyles style;
        /** A user style ({@link #loadStyle}); wins over {@link #style}. */
        public StyleDef styleDef;
        /** The CLI binary, a path or a name on PATH. Required. */
        public String command;
        /** For {@link CliStyles#CUSTOM}: the args (strings, or {@code List<String>} optional groups). */
        public List<Object> args;
        /** For {@link CliStyles#CUSTOM}: stdin | arg | file. */
        public String prompt;
        /** For {@link CliStyles#CUSTOM}: stdout | out. */
        public String answer;
        /** Added to the child's environment; values expand {@code ${VAR}} at call time and are never logged. */
        public Map<String, String> env;
        /** Bounds one CLI invocation or one ACP prompt. Default 300000. */
        public int callTimeoutMs;
        /** The child's working directory (default: this process's). */
        public String cwd;
        /** {@link CliTransports#AUTO} (default), ACP or CLI. */
        public CliTransports transport = CliTransports.AUTO;
        /** Served to an ACP agent as a local MCP server. Pass the same toolkit (or null) to run. */
        public Toolkit toolkit;
        /** If set, the full argv that starts the agent in ACP mode (e.g. an adapter); replaces command + acp.args. */
        public List<String> acpCommand;
        /** Argv of a stdio⇄HTTP MCP bridge (URL appended; token in env TOOLNEXUS_MCP_TOKEN). Default: `toolnexus mcp-bridge` when on PATH. */
        public List<String> bridgeCommand;
        /** Observes every raw ACP session/update params (must not block). */
        public Consumer<Map<String, Object>> onAcpUpdate;

        public Options style(CliStyles v) { this.style = v; return this; }
        public Options styleDef(StyleDef v) { this.styleDef = v; return this; }
        public Options command(String v) { this.command = v; return this; }
        public Options args(List<Object> v) { this.args = v; return this; }
        public Options prompt(String v) { this.prompt = v; return this; }
        public Options answer(String v) { this.answer = v; return this; }
        public Options env(Map<String, String> v) { this.env = v; return this; }
        public Options callTimeoutMs(int v) { this.callTimeoutMs = v; return this; }
        public Options cwd(String v) { this.cwd = v; return this; }
        public Options transport(CliTransports v) { this.transport = v; return this; }
        public Options toolkit(Toolkit v) { this.toolkit = v; return this; }
        public Options acpCommand(List<String> v) { this.acpCommand = v; return this; }
        public Options bridgeCommand(List<String> v) { this.bridgeCommand = v; return this; }
        public Options onAcpUpdate(Consumer<Map<String, Object>> v) { this.onAcpUpdate = v; return this; }
        public Options inProcess(InProcess.Options v) { this.inProcess = v; return this; }
        /** Shorthand for {@code inProcess.model}. */
        public Options model(String v) { this.inProcess.model = v; return this; }
        /** Shorthand for {@code inProcess.systemPrompt}. */
        public Options systemPrompt(String v) { this.inProcess.systemPrompt = v; return this; }
        /** Shorthand for {@code inProcess.hooks}. */
        public Options hooks(LlmClient.Hooks v) { this.inProcess.hooks = v; return this; }
    }

    /** A call-time failure of the CLI client; the message is the pinned {@code cli: …} text. */
    public static final class CliException extends RuntimeException {
        public CliException(String message) { super(message); }
        public CliException(String message, Throwable cause) { super(message, cause); }
    }

    // ---------------------------------------------------------------------------------------
    // State.
    // ---------------------------------------------------------------------------------------

    final StyleDef style;
    final Options opts;
    final String model;
    private final List<Elem> elems;
    private final boolean hasCli;
    final Map<String, String> env;
    final int timeout;
    final List<String> acpArgv;
    private final LlmClient client;

    private final Object lock = new Object();
    private CliTransports chosen;
    private CliAcpSession acp;
    private boolean closed;
    private String lastNote = "";

    private CliClient(StyleDef style, Options o, List<Elem> elems, boolean hasCli, List<String> acpArgv) {
        this.style = style;
        this.opts = o;
        this.model = o.inProcess != null && o.inProcess.model != null ? o.inProcess.model : "";
        this.elems = elems;
        this.hasCli = hasCli;
        this.acpArgv = acpArgv;
        Map<String, String> e = new LinkedHashMap<>();
        if (style.env != null) e.putAll(style.env);
        if (o.env != null) e.putAll(o.env);
        this.env = e;
        int t = o.callTimeoutMs > 0 ? o.callTimeoutMs : style.timeoutMs;
        this.timeout = t > 0 ? t : DEFAULT_CALL_TIMEOUT_MS;
        CliTransports tr = o.transport == null ? CliTransports.AUTO : o.transport;
        if (tr == CliTransports.CLI || acpArgv == null) chosen = CliTransports.CLI;
        else if (tr == CliTransports.ACP) chosen = CliTransports.ACP;
        else chosen = CliTransports.AUTO;

        InProcess.Options ip = copy(o.inProcess);
        if (ip.model == null || ip.model.isEmpty()) ip.model = style.name;
        ip.generate = this::generate;
        this.client = InProcess.createClient(ip, new CliHttpClient(new InProcess.GenerateBackedHttpClient(this::generate)));
    }

    private static InProcess.Options copy(InProcess.Options in) {
        InProcess.Options o = new InProcess.Options();
        if (in == null) return o;
        o.model = in.model;
        o.systemPrompt = in.systemPrompt;
        o.maxTurns = in.maxTurns;
        o.maxToolConcurrency = in.maxToolConcurrency;
        o.hooks = in.hooks;
        o.timeoutMs = in.timeoutMs;
        o.store = in.store;
        o.onMetric = in.onMetric;
        o.waitFor = in.waitFor;
        o.waitForCtx = in.waitForCtx;
        o.requestParams = in.requestParams;
        o.retries = in.retries;
        return o;
    }

    /**
     * Builds a client whose model is an installed agent CLI. Everything that can be validated is
     * validated here ({@link IllegalArgumentException} with the pinned message); the transport is
     * decided on the first model call.
     */
    public static CliClient create(Options o) {
        if (o == null || o.command == null || o.command.isEmpty()) {
            throw new IllegalArgumentException("cli: Command is required (path or name of the CLI binary)");
        }
        StyleDef d;
        if (o.styleDef != null) {
            d = o.styleDef;
        } else if (o.style == CliStyles.CUSTOM) {
            d = new StyleDef();
            d.name = "custom";
            d.args = o.args;
            d.prompt = o.prompt;
            d.answer = o.answer;
        } else {
            d = styleDef(o.style);
            if (d == null) throw new IllegalArgumentException("cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)");
        }
        String model = o.inProcess != null && o.inProcess.model != null ? o.inProcess.model : "";
        CliTransports tr = o.transport == null ? CliTransports.AUTO : o.transport;

        List<String> acpArgv = null;
        if (o.acpCommand != null && !o.acpCommand.isEmpty()) {
            acpArgv = new ArrayList<>(o.acpCommand);
        } else if (d.acp != null) {
            acpArgv = new ArrayList<>();
            acpArgv.add(o.command);
            if (d.acp.args != null) acpArgv.addAll(d.acp.args);
        }
        if (tr == CliTransports.ACP && acpArgv == null) {
            throw new IllegalArgumentException("cli: style " + quote(d.name)
                    + " has no ACP launch; set ACPCommand or use CliTransports.CLI");
        }
        List<Elem> elems = List.of();
        boolean hasCli = false;
        if (tr != CliTransports.ACP) {
            boolean acpOnly = d.args == null && empty(d.prompt) && empty(d.answer) && acpArgv != null
                    && tr == CliTransports.AUTO;
            if (!acpOnly) {
                elems = normalizeArgs(d.args);
                validate(d, elems, model);
                hasCli = true;
            }
        }
        return new CliClient(d, o, elems, hasCli, acpArgv);
    }

    /** The ordinary client: run, stream, memory, hooks — all of {@link LlmClient}. */
    public LlmClient client() { return client; }

    /** Shorthand for {@code client().run(prompt, toolkit)}. */
    public LlmClient.RunResult run(String prompt, Toolkit toolkit) { return client.run(prompt, toolkit); }

    /** Shorthand for {@code client().run(prompt)}. */
    public LlmClient.RunResult run(String prompt) { return client.run(prompt); }

    /** Shorthand for {@code client().stream(prompt, toolkit, onEvent)} (non-incremental: the whole answer arrives as one text event). */
    public LlmClient.RunResult stream(String prompt, Toolkit toolkit, Consumer<LlmClient.StreamEvent> onEvent) {
        return client.stream(prompt, toolkit, onEvent);
    }

    /** The transport in use: "acp", "cli", or "auto" before the first model call has decided. */
    public String transport() {
        synchronized (lock) { return chosen.toString(); }
    }

    /** Why AUTO fell back from ACP to the one-shot CLI ("" when it did not). */
    public String fallbackReason() {
        synchronized (lock) { return lastNote; }
    }

    /** The live ACP session's read-only info (empty when one-shot or no call yet). Secrets are redacted. */
    public Optional<CliAcpSession.Info> acpSession() {
        CliAcpSession s;
        synchronized (lock) { s = acp; }
        return s == null ? Optional.empty() : Optional.of(s.info());
    }

    /** Ends the ACP agent's process tree, stops the served toolkit and removes any temp session dir. Idempotent. */
    @Override
    public void close() {
        CliAcpSession s;
        synchronized (lock) {
            s = acp;
            acp = null;
            closed = true;
        }
        if (s != null) s.close();
    }

    // ---------------------------------------------------------------------------------------
    // The model.
    // ---------------------------------------------------------------------------------------

    private InProcess.Response generate(InProcess.Request req) {
        CliAcpSession s = session();
        if (s != null) return s.generate(req);
        String prompt = renderPrompt(req.messages, req.tools);
        return parseReply(invoke(prompt));
    }

    private CliAcpSession session() {
        synchronized (lock) {
            if (closed) throw new CliException("cli: client is closed");
            if (chosen == CliTransports.CLI) return null;
            if (acp != null) return acp;
            try {
                CliAcpSession s = CliAcpSession.open(this);
                acp = s;
                chosen = CliTransports.ACP;
                return s;
            } catch (CliAcpSession.LaunchException e) {
                if (chosen == CliTransports.AUTO && hasCli) {
                    chosen = CliTransports.CLI;
                    lastNote = e.getMessage();
                    return null;
                }
                throw new CliException(e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Argv templating and validation.
    // ---------------------------------------------------------------------------------------

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    record Elem(boolean group, List<String> parts) {}

    private static List<Elem> normalizeArgs(List<Object> args) {
        List<Elem> out = new ArrayList<>();
        if (args == null) return out;
        for (Object e : args) {
            if (e instanceof String s) {
                out.add(new Elem(false, List.of(s)));
            } else if (e instanceof List<?> l) {
                List<String> parts = new ArrayList<>();
                for (Object x : l) {
                    if (!(x instanceof String s)) throw new IllegalArgumentException("cli: a group may only hold strings");
                    parts.add(s);
                }
                out.add(new Elem(true, parts));
            } else {
                throw new IllegalArgumentException("cli: an arg must be a string or a group of strings");
            }
        }
        return out;
    }

    private static void validate(StyleDef d, List<Elem> elems, String model) {
        String p = d.prompt == null ? "" : d.prompt;
        String a = d.answer == null ? "" : d.answer;
        if (!p.equals("stdin") && !p.equals("arg") && !p.equals("file")) {
            throw new IllegalArgumentException("cli: prompt must be stdin, arg or file, not " + quote(p));
        }
        if (!a.equals("stdout") && !a.equals("out")) {
            throw new IllegalArgumentException("cli: answer must be stdout or out, not " + quote(a));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Elem e : elems) {
            for (String part : e.parts()) {
                Matcher m = PLACEHOLDER.matcher(part);
                while (m.find()) {
                    String k = m.group(1);
                    switch (k) {
                        case "prompt", "file", "out" -> { }
                        case "model" -> {
                            if (!e.group() && model.isEmpty()) {
                                throw new IllegalArgumentException(
                                        "cli: the style needs a model: {{model}} is outside an optional group");
                            }
                        }
                        default -> throw new IllegalArgumentException("cli: unknown placeholder {{" + k + "}}");
                    }
                    seen.add(k);
                }
            }
        }
        if (!model.isEmpty() && !seen.contains("model")) {
            throw new IllegalArgumentException("cli: style " + quote(d.name) + " takes no model");
        }
        if (p.equals("arg") && !seen.contains("prompt")) throw new IllegalArgumentException("cli: prompt \"arg\" needs {{prompt}} in args");
        if (p.equals("file") && !seen.contains("file")) throw new IllegalArgumentException("cli: prompt \"file\" needs {{file}} in args");
        if (a.equals("out") && !seen.contains("out")) throw new IllegalArgumentException("cli: answer \"out\" needs {{out}} in args");
    }

    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    static String quote(String s) { return Json.stringify(s == null ? "" : s); }

    // ---------------------------------------------------------------------------------------
    // The envelope: render and tolerant parse.
    // ---------------------------------------------------------------------------------------

    /**
     * {@code PREAMBLE + "\nREQUEST:\n" + {"messages","tools"}}: the OpenAI-shaped request,
     * unmodified, as compact JSON.
     */
    public static String renderPrompt(List<Object> messages, List<Object> tools) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("messages", messages == null ? List.of() : messages);
        req.put("tools", tools == null ? List.of() : tools);
        return PREAMBLE + "\nREQUEST:\n" + Json.stringify(req);
    }

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * The tolerant six-step envelope parser: (1) trim; (2) strip one markdown fence; (3) parse the
     * whole text as one JSON object, else (4) the span from the first "{" to the last "}"; (5)
     * unwrap {@code choices[0].message} or {@code message}; (6) {@code tool_calls} (flat or
     * {@code {function:{}}}, string or object arguments) win, else {@code content}. Anything that
     * is not an envelope is returned verbatim as content.
     */
    @SuppressWarnings("unchecked")
    public static InProcess.Response parseReply(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int i = s.indexOf('\n');
            s = i >= 0 ? s.substring(i + 1) : "";
            s = s.strip();
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        Map<String, Object> obj = parseObject(s);
        if (obj == null) {
            int i = s.indexOf('{');
            int j = s.lastIndexOf('}');
            if (i >= 0 && j > i) obj = parseObject(s.substring(i, j + 1));
        }
        if (obj == null) return InProcess.Response.content(text);
        if (obj.get("choices") instanceof List<?> ch) {
            if (!ch.isEmpty() && ch.get(0) instanceof Map<?, ?> c0 && c0.get("message") instanceof Map<?, ?> m) {
                obj = (Map<String, Object>) m;
            }
        } else if (obj.get("message") instanceof Map<?, ?> m) {
            obj = (Map<String, Object>) m;
        }
        if (obj.get("tool_calls") instanceof List<?> tcs) {
            List<InProcess.ToolCall> calls = new ArrayList<>();
            for (Object el : tcs) {
                if (!(el instanceof Map<?, ?> e)) continue;
                Map<?, ?> fn = e.get("function") instanceof Map<?, ?> f ? f : e;
                if (!(fn.get("name") instanceof String name) || name.isEmpty()) continue;
                Object args = fn.get("arguments");
                InProcess.ToolCall call = new InProcess.ToolCall(name, args == null ? new LinkedHashMap<>() : args);
                if (e.get("id") instanceof String id && !id.isEmpty()) call.id(id);
                calls.add(call);
            }
            if (!calls.isEmpty()) return InProcess.Response.toolCalls(calls.toArray(new InProcess.ToolCall[0]));
        }
        if (obj.containsKey("content")) {
            Object c = obj.get("content");
            if (c instanceof String str) return InProcess.Response.content(str);
            if (c == null) return InProcess.Response.content("");
            return InProcess.Response.content(Json.stringify(c));
        }
        return InProcess.Response.content(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String s) {
        try {
            Object v = STRICT.readValue(s, Object.class);
            return v instanceof Map ? (Map<String, Object>) v : null;
        } catch (Exception e) {
            return null; // trailing text is a failure, never ignored
        }
    }

    // ---------------------------------------------------------------------------------------
    // One-shot invocation.
    // ---------------------------------------------------------------------------------------

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /** {@code ${VAR}} references expanded from this process's environment (values never logged). */
    static Map<String, String> expandEnv(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        env.forEach((k, v) -> {
            Matcher m = ENV_REF.matcher(v == null ? "" : v);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String val = System.getenv(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : val));
            }
            m.appendTail(sb);
            out.put(k, sb.toString());
        });
        return out;
    }

    /** One rendered invocation: argv, the {{out}} path (or null), and every temp file created. */
    record Argv(List<String> argv, String outPath, List<String> temps) {}

    /** Renders the argv for one invocation; the caller removes {@code temps} on every path. */
    Argv argv(String prompt) throws IOException {
        List<String> temps = new ArrayList<>();
        String[] file = {null};
        String[] out = {null};
        List<String> argv = new ArrayList<>();
        argv.add(opts.command);
        IOException[] err = {null};
        for (Elem e : elems) {
            if (e.group() && model.isEmpty()) continue;
            for (String p : e.parts()) {
                Matcher m = PLACEHOLDER.matcher(p);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String rep;
                    switch (m.group(1)) {
                        case "prompt" -> rep = prompt;
                        case "model" -> rep = model;
                        case "file" -> {
                            if (file[0] == null) {
                                try {
                                    Path f = Files.createTempFile("toolnexus-cli-", "");
                                    temps.add(f.toString());
                                    Files.writeString(f, prompt);
                                    file[0] = f.toString();
                                } catch (IOException ex) {
                                    err[0] = ex;
                                    file[0] = "";
                                }
                            }
                            rep = file[0];
                        }
                        case "out" -> {
                            if (out[0] == null) {
                                try {
                                    Path f = Files.createTempFile("toolnexus-cli-", "");
                                    temps.add(f.toString());
                                    Files.deleteIfExists(f); // the CLI creates it; absence ⇒ "no output file"
                                    out[0] = f.toString();
                                } catch (IOException ex) {
                                    err[0] = ex;
                                    out[0] = "";
                                }
                            }
                            rep = out[0];
                        }
                        default -> rep = m.group(0);
                    }
                    m.appendReplacement(sb, Matcher.quoteReplacement(rep));
                }
                m.appendTail(sb);
                argv.add(sb.toString());
            }
        }
        Argv r = new Argv(argv, out[0], temps);
        if (err[0] != null) {
            removeAll(temps);
            throw err[0];
        }
        return r;
    }

    private static void removeAll(List<String> files) {
        for (String f : files) {
            try {
                Files.deleteIfExists(Path.of(f));
            } catch (IOException ignored) {
            }
        }
    }

    private String invoke(String prompt) {
        Argv a;
        try {
            a = argv(prompt);
        } catch (IOException e) {
            throw new CliException("cli: " + e.getMessage(), e);
        }
        try {
            return run(a, prompt);
        } finally {
            removeAll(a.temps());
        }
    }

    private String run(Argv a, String prompt) {
        ProcessBuilder pb = new ProcessBuilder(a.argv());
        if (opts.cwd != null && !opts.cwd.isEmpty()) pb.directory(new java.io.File(opts.cwd));
        if (!env.isEmpty()) pb.environment().putAll(expandEnv(env));
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new CliException("cli: command not found: " + a.argv().get(0), e);
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread tout = pump(proc.getInputStream(), stdout);
        Thread terr = pump(proc.getErrorStream(), stderr);
        String input = "stdin".equals(style.prompt) ? prompt : "";
        Thread tin = new Thread(() -> {
            try (OutputStream os = proc.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }, "cli-stdin");
        tin.setDaemon(true);
        tin.start();
        boolean done;
        try {
            done = proc.waitFor(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(proc);
            Thread.currentThread().interrupt();
            throw new CliException("cli: interrupted", e);
        }
        if (!done) {
            killTree(proc);
            throw new CliException("cli: timed out after " + timeout + "ms");
        }
        join(tout);
        join(terr);
        int exit = proc.exitValue();
        if (exit != 0) {
            String msg = stderr.toString(StandardCharsets.UTF_8).strip();
            if (msg.isEmpty()) throw new CliException("cli: exit " + exit);
            if (msg.codePointCount(0, msg.length()) > 1000) {
                msg = msg.substring(0, msg.offsetByCodePoints(0, 1000)) + "…";
            }
            throw new CliException("cli: exit " + exit + ": " + msg);
        }
        String text = stdout.toString(StandardCharsets.UTF_8);
        if ("out".equals(style.answer)) {
            try {
                text = Files.readString(Path.of(a.outPath()));
            } catch (IOException | RuntimeException e) {
                throw new CliException("cli: no output file");
            }
        }
        if (text.isBlank()) throw new CliException("cli: empty reply");
        return text;
    }

    /** Kills the process and every descendant (Java's equivalent of the Go port's process group). */
    static void killTree(Process p) {
        List<ProcessHandle> tree = p.descendants().toList();
        tree.forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(out);
            } catch (IOException ignored) {
            }
        }, "cli-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Looks a bare command up on PATH (absolute/relative paths are checked as given). */
    static String lookPath(String cmd) {
        if (cmd == null || cmd.isEmpty()) return null;
        if (cmd.contains("/") || cmd.contains(java.io.File.separator)) {
            return Files.isExecutable(Path.of(cmd)) ? cmd : null;
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Path.of(dir, cmd);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // The HTTP shim: the shared shim, plus a non-incremental stream. Neither transport
    // streams incrementally here, so a stream runs the turn whole and replays it as one
    // SSE chunk: the loop then emits its tool events and the answer as a single text event.
    // ---------------------------------------------------------------------------------------

    private final class CliHttpClient extends HttpClient {
        private final InProcess.GenerateBackedHttpClient inner;

        CliHttpClient(InProcess.GenerateBackedHttpClient inner) { this.inner = inner; }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
            Map<String, Object> body = readBody(req);
            if (body == null || !Boolean.TRUE.equals(body.get("stream"))) return inner.send(req, handler);
            Map<String, Object> plain = new LinkedHashMap<>(body);
            plain.remove("stream");
            plain.remove("stream_options");
            HttpRequest once = HttpRequest.newBuilder(req.uri())
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(plain))).build();
            HttpResponse<String> whole = inner.send(once, HttpResponse.BodyHandlers.ofString());
            return (HttpResponse<T>) new LinesResponse(req, toSse(Json.toMap(whole.body())));
        }

        @SuppressWarnings("unchecked")
        private static List<String> toSse(Map<String, Object> envelope) {
            Map<String, Object> choice = (Map<String, Object>) ((List<Object>) envelope.get("choices")).get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            if (message.get("content") != null) delta.put("content", message.get("content"));
            if (message.get("tool_calls") instanceof List<?> calls) {
                List<Object> indexed = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) calls.get(i));
                    c.put("index", i);
                    indexed.add(c);
                }
                delta.put("tool_calls", indexed);
            }
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("index", 0);
            ch.put("delta", delta);
            ch.put("finish_reason", choice.get("finish_reason"));
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("choices", List.of(ch));
            chunk.put("usage", envelope.get("usage"));
            return List.of("data: " + Json.stringify(chunk), "", "data: [DONE]", "");
        }

        private static Map<String, Object> readBody(HttpRequest req) {
            Optional<HttpRequest.BodyPublisher> bp = req.bodyPublisher();
            if (bp.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            bp.get().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(java.nio.ByteBuffer item) { sb.append(StandardCharsets.UTF_8.decode(item)); }
                @Override public void onError(Throwable t) {}
                @Override public void onComplete() {}
            });
            try {
                return Json.toMap(sb.toString());
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException("cli client"); }
        @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException("cli client"); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            try {
                return CompletableFuture.completedFuture(send(r, h));
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                        HttpResponse.PushPromiseHandler<T> p) {
            return sendAsync(r, h);
        }
    }

    private record LinesResponse(HttpRequest req, List<String> lines) implements HttpResponse<java.util.stream.Stream<String>> {
        @Override public int statusCode() { return 200; }
        @Override public java.util.stream.Stream<String> body() { return lines.stream(); }
        @Override public HttpRequest request() { return req; }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of("content-type", List.of("text/event-stream")), (a, b) -> true);
        }
        @Override public Optional<HttpResponse<java.util.stream.Stream<String>>> previousResponse() { return Optional.empty(); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return req.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
