package io.github.muthuishere.toolnexus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Built-in tool source (source {@code "builtin"}). The default toolset toolnexus
 * ships so an agent can act with zero custom wiring — opencode's built-ins, ported
 * with identical tool names + input schemas. See {@code ../../SPEC.md §4A} and the
 * JS reference ({@code js/src/builtin.ts}).
 *
 * <p>Every tool obeys the uniform {@link Tool}/{@link ToolResult} contract: a
 * failure is a {@code ToolResult{isError:true}}, never a thrown exception across
 * the boundary. Paths resolve relative to the process working directory unless
 * absolute.
 */
public final class BuiltinTools {
    private BuiltinTools() {}

    private static final List<String> IGNORE_DIRS = List.of("node_modules", ".git");

    /**
     * Whether the builtin source is on. Default ON. Same precedence as MCP:
     * {@code disabled:true} wins, else {@code enabled:false} disables, otherwise
     * enabled. The config may be {@code null}, a {@link Boolean}, or a {@link Map}
     * (mirrors the JS {@code BuiltinsConfig = boolean | {enabled?, disabled?}}).
     */
    public static boolean enabled(Object cfg) {
        if (cfg == null) return true;
        if (cfg instanceof Boolean) return (Boolean) cfg;
        if (cfg instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) cfg;
            if (Boolean.TRUE.equals(m.get("disabled"))) return false;
            if (Boolean.FALSE.equals(m.get("enabled"))) return false;
            return true;
        }
        return true;
    }

    /**
     * Build the ten built-in tools (each {@code source:"builtin"}). The order is
     * fixed for parity: bash, read, write, edit, grep, glob, webfetch,
     * question, apply_patch, todowrite.
     */
    public static List<Tool> create() {
        List<Tool> tools = new ArrayList<>();
        tools.add(bashTool());
        tools.add(readTool());
        tools.add(writeTool());
        tools.add(editTool());
        tools.add(grepTool());
        tools.add(globTool());
        tools.add(webfetchTool());
        tools.add(questionTool());
        tools.add(applyPatchTool());
        tools.add(todowriteTool());
        return tools;
    }

    /**
     * Resolve the active builtin tools for a config. Whole-source-off wins and
     * returns an empty list. Otherwise all ten are on; a {@code tools}
     * name→bool map drops any tool mapped to {@code false} (all-on baseline;
     * {@code true}/absent stay on; unknown names are ignored). See SPEC §4A and
     * the JS reference ({@code selectBuiltins} in {@code js/src/builtin.ts}).
     */
    public static List<Tool> select(Object cfg) {
        if (!enabled(cfg)) return List.of();
        Map<?, ?> map = null;
        if (cfg instanceof Map) {
            Object t = ((Map<?, ?>) cfg).get("tools");
            if (t instanceof Map) map = (Map<?, ?>) t;
        }
        List<Tool> all = create();
        if (map == null) return all;
        List<Tool> selected = new ArrayList<>();
        for (Tool tool : all) {
            if (!Boolean.FALSE.equals(map.get(tool.name()))) selected.add(tool);
        }
        return selected;
    }

    // -----------------------------------------------------------------------
    // uniform result + wrapper
    // -----------------------------------------------------------------------

    private static ToolResult err(String output) {
        return ToolResult.error(output);
    }

    private static ToolResult err(String output, Map<String, Object> metadata) {
        return ToolResult.error(output, metadata);
    }

    private static ToolResult ok(String output) {
        return ToolResult.ok(output);
    }

    private static ToolResult ok(String output, Map<String, Object> metadata) {
        return ToolResult.ok(output, metadata);
    }

    private static Map<String, Object> meta(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** A builtin body that may throw; the wrapper turns any throw into an error result. */
    @FunctionalInterface
    private interface Runner {
        ToolResult run(Map<String, Object> args, ToolContext ctx) throws Exception;
    }

    private static Tool builtin(String name, String description, Map<String, Object> inputSchema, Runner run) {
        return new BuiltinTool(name, description, inputSchema, run);
    }

    private static final class BuiltinTool implements Tool {
        private final String name;
        private final String description;
        private final Map<String, Object> inputSchema;
        private final Runner run;

        BuiltinTool(String name, String description, Map<String, Object> inputSchema, Runner run) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.run = run;
        }

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
            return inputSchema;
        }

        @Override
        public String source() {
            return "builtin";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            try {
                return run.run(args == null ? new LinkedHashMap<>() : args, ctx);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                return err(name + ": " + msg);
            }
        }
    }

    // -----------------------------------------------------------------------
    // small arg helpers (mirror the JS String(x ?? "") / typeof number checks)
    // -----------------------------------------------------------------------

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean isNumber(Object o) {
        return o instanceof Number;
    }

    private static long truncToLong(Object o) {
        return (long) ((Number) o).doubleValue();
    }

    // -----------------------------------------------------------------------
    // schema builders
    // -----------------------------------------------------------------------

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        if (description != null) p.put("description", description);
        return p;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    // -----------------------------------------------------------------------
    // bash
    // -----------------------------------------------------------------------

    private static Tool bashTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", prop("string", "The shell command to run"));
        props.put("workdir", prop("string", "Working directory (default: process cwd)"));
        props.put("timeout", prop("number", "Timeout in milliseconds (default 60000)"));
        props.put("description", prop("string", "Human-readable description of the command"));
        return builtin(
                "bash",
                "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
                schema(props, List.of("command")),
                (args, ctx) -> {
                    String command = str(args.get("command"));
                    if (command.isEmpty()) return err("bash: command is required");
                    long timeout = isNumber(args.get("timeout")) ? truncToLong(args.get("timeout")) : 60_000L;
                    ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
                    pb.redirectErrorStream(true);
                    if (args.get("workdir") != null) pb.directory(new java.io.File(str(args.get("workdir"))));
                    Process proc;
                    try {
                        proc = pb.start();
                    } catch (IOException e) {
                        return err("bash: " + e.getMessage());
                    }
                    StringBuilder out = new StringBuilder();
                    Thread reader = new Thread(() -> {
                        try (InputStream in = proc.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                synchronized (out) {
                                    out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
                    reader.start();
                    boolean finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS);
                    if (!finished) {
                        proc.destroyForcibly();
                        reader.join(500);
                        synchronized (out) {
                            return err("bash: command timed out after " + timeout + "ms\n" + out);
                        }
                    }
                    reader.join();
                    int code = proc.exitValue();
                    synchronized (out) {
                        if (code != 0) {
                            return err(out + "\nbash: command exited with code " + code, meta("exitCode", code));
                        }
                        return ok(out.toString(), meta("exitCode", code));
                    }
                });
    }

    // -----------------------------------------------------------------------
    // read
    // -----------------------------------------------------------------------

    private static Tool readTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", prop("string", "Path to the file to read"));
        props.put("offset", prop("number", "1-based line to start from"));
        props.put("limit", prop("number", "Maximum number of lines to read"));
        return builtin(
                "read",
                "Read a UTF-8 text file. With offset/limit, return only that line window.",
                schema(props, List.of("path")),
                (args, ctx) -> {
                    String p = str(args.get("path"));
                    if (p.isEmpty()) return err("read: path is required");
                    String content;
                    try {
                        content = Files.readString(Path.of(p));
                    } catch (IOException e) {
                        return err("read: " + e.getMessage());
                    }
                    Object offsetArg = args.get("offset");
                    Object limitArg = args.get("limit");
                    if (offsetArg == null && limitArg == null) return ok(content);
                    String[] lines = content.split("\n", -1);
                    long offset = isNumber(offsetArg) ? Math.max(1, truncToLong(offsetArg)) : 1;
                    int start = (int) Math.min(offset - 1, lines.length);
                    long limit = isNumber(limitArg) ? Math.max(0, truncToLong(limitArg)) : (lines.length - start);
                    int end = (int) Math.min((long) start + limit, lines.length);
                    if (end < start) end = start;
                    List<String> window = new ArrayList<>();
                    for (int i = start; i < end; i++) window.add(lines[i]);
                    return ok(String.join("\n", window));
                });
    }

    // -----------------------------------------------------------------------
    // write
    // -----------------------------------------------------------------------

    private static Tool writeTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", prop("string", "Path to write to"));
        props.put("content", prop("string", "Content to write"));
        return builtin(
                "write",
                "Write content to a file (create/overwrite), creating parent directories.",
                schema(props, List.of("path", "content")),
                (args, ctx) -> {
                    String p = str(args.get("path"));
                    if (p.isEmpty()) return err("write: path is required");
                    String content = args.get("content") instanceof String
                            ? (String) args.get("content")
                            : str(args.get("content"));
                    Path pp = Path.of(p);
                    Path parent = pp.toAbsolutePath().getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.writeString(pp, content);
                    int bytes = content.getBytes(StandardCharsets.UTF_8).length;
                    return ok("Wrote " + bytes + " bytes to " + p, meta("bytes", bytes));
                });
    }

    // -----------------------------------------------------------------------
    // edit
    // -----------------------------------------------------------------------

    private static Tool editTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", prop("string", "Path to the file to edit"));
        props.put("oldString", prop("string", "Exact string to replace"));
        props.put("newString", prop("string", "Replacement string"));
        props.put("replaceAll", prop("boolean", "Replace all occurrences"));
        return builtin(
                "edit",
                "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
                schema(props, List.of("path", "oldString", "newString")),
                (args, ctx) -> {
                    String p = str(args.get("path"));
                    if (p.isEmpty()) return err("edit: path is required");
                    Object oldArg = args.get("oldString");
                    if (!(oldArg instanceof String) || ((String) oldArg).isEmpty()) {
                        return err("edit: oldString is required");
                    }
                    String oldString = (String) oldArg;
                    String newString = args.get("newString") instanceof String
                            ? (String) args.get("newString")
                            : str(args.get("newString"));
                    String content;
                    try {
                        content = Files.readString(Path.of(p));
                    } catch (IOException e) {
                        return err("edit: " + e.getMessage());
                    }
                    int count = countOccurrences(content, oldString);
                    if (count == 0) return err("edit: oldString not found in " + p);
                    boolean replaceAll = Boolean.TRUE.equals(args.get("replaceAll"));
                    String next;
                    if (replaceAll) {
                        next = content.replace(oldString, newString);
                    } else {
                        if (count > 1) {
                            return err("edit: oldString is not unique in " + p + " (" + count
                                    + " occurrences); use replaceAll");
                        }
                        next = replaceFirstLiteral(content, oldString, newString);
                    }
                    Files.writeString(Path.of(p), next);
                    int replacements = replaceAll ? count : 1;
                    return ok("Edited " + p + " (" + replacements + " replacement"
                            + (replacements == 1 ? "" : "s") + ")", meta("replacements", replacements));
                });
    }

    // -----------------------------------------------------------------------
    // grep
    // -----------------------------------------------------------------------

    private static Tool grepTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", prop("string", "Regular expression to search for"));
        props.put("path", prop("string", "Directory to search (default: process cwd)"));
        props.put("include", prop("string", "Glob filter for file names"));
        props.put("limit", prop("number", "Maximum number of matches (default 100)"));
        return builtin(
                "grep",
                "Search file contents by regex under a directory. Output is file:line:text matches.",
                schema(props, List.of("pattern")),
                (args, ctx) -> {
                    String pattern = str(args.get("pattern"));
                    if (pattern.isEmpty()) return err("grep: pattern is required");
                    Pattern re;
                    try {
                        re = Pattern.compile(pattern);
                    } catch (PatternSyntaxException e) {
                        return err("grep: invalid regex: " + e.getMessage());
                    }
                    Path root = Path.of(args.get("path") != null ? str(args.get("path")) : ".");
                    String include = args.get("include") != null ? str(args.get("include")) : null;
                    long limit = isNumber(args.get("limit")) ? truncToLong(args.get("limit")) : 100;
                    List<String> matches = new ArrayList<>();
                    for (Path file : walkFiles(root)) {
                        if (matches.size() >= limit) break;
                        String rel = relativize(root, file);
                        if (include != null && !matchGlob(rel, include)) continue;
                        String text;
                        try {
                            text = Files.readString(file);
                        } catch (IOException e) {
                            continue;
                        }
                        String[] lines = text.split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (matches.size() >= limit) break;
                            if (re.matcher(lines[i]).find()) {
                                matches.add(file + ":" + (i + 1) + ":" + lines[i]);
                            }
                        }
                    }
                    return ok(String.join("\n", matches), meta("count", matches.size()));
                });
    }

    // -----------------------------------------------------------------------
    // glob
    // -----------------------------------------------------------------------

    private static Tool globTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", prop("string", "Glob pattern to match"));
        props.put("path", prop("string", "Directory to search (default: process cwd)"));
        props.put("limit", prop("number", "Maximum number of results (default 100)"));
        return builtin(
                "glob",
                "List files matching a glob under a directory. Output is newline-joined relative paths.",
                schema(props, List.of("pattern")),
                (args, ctx) -> {
                    String pattern = str(args.get("pattern"));
                    if (pattern.isEmpty()) return err("glob: pattern is required");
                    Path root = Path.of(args.get("path") != null ? str(args.get("path")) : ".");
                    long limit = isNumber(args.get("limit")) ? truncToLong(args.get("limit")) : 100;
                    List<String> found = new ArrayList<>();
                    for (Path file : walkFiles(root)) {
                        if (found.size() >= limit) break;
                        String rel = relativize(root, file);
                        if (matchGlob(rel, pattern)) found.add(rel);
                    }
                    Collections.sort(found);
                    int end = (int) Math.min((long) found.size(), limit);
                    List<String> out = found.subList(0, end);
                    return ok(String.join("\n", out), meta("count", (int) Math.min(found.size(), limit)));
                });
    }

    // -----------------------------------------------------------------------
    // webfetch
    // -----------------------------------------------------------------------

    private static Tool webfetchTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", prop("string", "URL to fetch"));
        Map<String, Object> fmt = prop("string", "Response format (default markdown)");
        fmt.put("enum", List.of("text", "markdown", "html"));
        props.put("format", fmt);
        props.put("timeout", prop("number", "Timeout in seconds (default 30)"));
        return builtin(
                "webfetch",
                "HTTP GET a URL and return its body as text, markdown, or html.",
                schema(props, List.of("url")),
                (args, ctx) -> {
                    String url = str(args.get("url"));
                    if (url.isEmpty()) return err("webfetch: url is required");
                    String format = "text".equals(args.get("format")) || "html".equals(args.get("format"))
                            ? (String) args.get("format")
                            : "markdown";
                    long timeoutMs = (isNumber(args.get("timeout")) ? truncToLong(args.get("timeout")) : 30) * 1000;
                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMillis(timeoutMs))
                            .GET()
                            .build();
                    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                    int status = res.statusCode();
                    String body = res.body();
                    if (status < 200 || status >= 300) return err("HTTP " + status, meta("status", status));
                    String output = "html".equals(format) ? body : stripHtml(body);
                    return ok(output, meta("status", status, "format", format));
                });
    }

    // -----------------------------------------------------------------------
    // question
    // -----------------------------------------------------------------------

    private static Tool questionTool() {
        Map<String, Object> questionItem = new LinkedHashMap<>();
        Map<String, Object> qProps = new LinkedHashMap<>();
        qProps.put("question", prop("string", null));
        qProps.put("header", prop("string", null));
        Map<String, Object> optionsProp = new LinkedHashMap<>();
        optionsProp.put("type", "array");
        optionsProp.put("items", prop("string", null));
        qProps.put("options", optionsProp);
        qProps.put("multiple", prop("boolean", null));
        questionItem.put("type", "object");
        questionItem.put("properties", qProps);
        questionItem.put("required", List.of("question"));

        Map<String, Object> questionsProp = new LinkedHashMap<>();
        questionsProp.put("type", "array");
        questionsProp.put("description", "Questions to ask");
        questionsProp.put("items", questionItem);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("questions", questionsProp);
        return builtin(
                "question",
                "Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's waitFor resolves it and the answer is returned to the model.",
                schema(props, List.of("questions")),
                (args, ctx) -> {
                    Object q = args.get("questions");
                    List<?> questions = q instanceof List ? (List<?>) q : List.of();
                    // Re-executed after the host's waitFor resolved (§10 loop rule): the resolution
                    // IS the answer, as with kind:"input" — forward it verbatim to the model.
                    if (ctx != null && ctx.answer() != null) {
                        Map<String, Object> data = ctx.answer().data();
                        return ok(Json.stringify(data != null ? data : Map.of()));
                    }
                    // First call: suspend. A question is just a §10 Request with kind:"question".
                    Map<String, Object> reqData = new LinkedHashMap<>();
                    reqData.put("questions", questions);
                    return ToolResult.pending(
                            new Request(null, "question", renderQuestionPrompt(questions), null, reqData, null));
                });
    }

    /**
     * Render the questions into a human-readable {@code Request.prompt} (§10). Byte-identical
     * across ports: each question's text in order, {@code " (options: a, b, c)"} appended when it
     * has non-empty options, joined by {@code "\n"} (no trailing newline). {@code header} is not
     * rendered — it survives in {@code data.questions}.
     */
    private static String renderQuestionPrompt(List<?> questions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) sb.append("\n");
            Object item = questions.get(i);
            Map<?, ?> q = item instanceof Map ? (Map<?, ?>) item : Map.of();
            Object qv = q.get("question");
            sb.append(qv instanceof String s ? s : "");
            Object ov = q.get("options");
            if (ov instanceof List<?> opts && !opts.isEmpty()) {
                List<String> parts = new java.util.ArrayList<>();
                for (Object o : opts) parts.add(String.valueOf(o));
                sb.append(" (options: ").append(String.join(", ", parts)).append(")");
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // todowrite
    // -----------------------------------------------------------------------

    private static Tool todowriteTool() {
        Map<String, Object> todoItem = new LinkedHashMap<>();
        Map<String, Object> tProps = new LinkedHashMap<>();
        tProps.put("id", prop("string", null));
        tProps.put("text", prop("string", null));
        tProps.put("completed", prop("boolean", null));
        todoItem.put("type", "object");
        todoItem.put("properties", tProps);
        todoItem.put("required", List.of("id", "text", "completed"));

        Map<String, Object> todosProp = new LinkedHashMap<>();
        todosProp.put("type", "array");
        todosProp.put("description", "The full todo list to store");
        todosProp.put("items", todoItem);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("todos", todosProp);
        return builtin(
                "todowrite",
                "Replace the session todo list. Returns the rendered list.",
                schema(props, List.of("todos")),
                (args, ctx) -> {
                    Object t = args.get("todos");
                    List<?> todos = t instanceof List ? (List<?>) t : List.of();
                    List<String> rows = new ArrayList<>();
                    for (Object o : todos) {
                        Map<?, ?> todo = o instanceof Map ? (Map<?, ?>) o : Map.of();
                        boolean completed = Boolean.TRUE.equals(todo.get("completed"));
                        rows.add("[" + (completed ? "x" : " ") + "] " + str(todo.get("text")));
                    }
                    String rendered = String.join("\n", rows);
                    return ok(rendered.isEmpty() ? "(no todos)" : rendered, meta("todos", todos));
                });
    }

    // -----------------------------------------------------------------------
    // apply_patch (opencode Begin/End Patch grammar)
    // -----------------------------------------------------------------------

    private static final Pattern FILE_MARKER = Pattern.compile("^\\*\\*\\* (Add|Update|Delete) File: (.+)$");

    private static final class PatchOp {
        final String type; // "add" | "update" | "delete"
        final String path;
        final String content;   // add
        final List<String> body; // update

        private PatchOp(String type, String path, String content, List<String> body) {
            this.type = type;
            this.path = path;
            this.content = content;
            this.body = body;
        }

        static PatchOp add(String path, String content) {
            return new PatchOp("add", path, content, null);
        }

        static PatchOp delete(String path) {
            return new PatchOp("delete", path, null, null);
        }

        static PatchOp update(String path, List<String> body) {
            return new PatchOp("update", path, null, body);
        }
    }

    private static List<PatchOp> parsePatch(String patchText) {
        String[] lines = patchText.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].trim().isEmpty()) i++;
        if (i >= lines.length || !lines[i].trim().equals("*** Begin Patch")) {
            throw new RuntimeException("missing '*** Begin Patch'");
        }
        i++;
        List<PatchOp> ops = new ArrayList<>();
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().equals("*** End Patch")) return ops;
            if (line.trim().isEmpty()) {
                i++;
                continue;
            }
            Matcher m = FILE_MARKER.matcher(line);
            if (!m.matches()) throw new RuntimeException("unexpected line: " + line);
            String kind = m.group(1);
            String p = m.group(2).trim();
            i++;
            List<String> body = new ArrayList<>();
            while (i < lines.length && !lines[i].trim().equals("*** End Patch")
                    && !FILE_MARKER.matcher(lines[i]).matches()) {
                body.add(lines[i]);
                i++;
            }
            if (kind.equals("Add")) {
                StringBuilder content = new StringBuilder();
                for (int k = 0; k < body.size(); k++) {
                    if (k > 0) content.append("\n");
                    String l = body.get(k);
                    content.append(l.startsWith("+") ? l.substring(1) : l);
                }
                ops.add(PatchOp.add(p, content.toString()));
            } else if (kind.equals("Delete")) {
                ops.add(PatchOp.delete(p));
            } else {
                ops.add(PatchOp.update(p, body));
            }
        }
        throw new RuntimeException("missing '*** End Patch'");
    }

    /** Apply an Update hunk-body to file content; throws on a non-match. */
    private static String applyUpdate(String content, List<String> body) {
        List<List<String>> hunks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String l : body) {
            if (l.startsWith("@@")) {
                if (!cur.isEmpty()) hunks.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(l);
            }
        }
        if (!cur.isEmpty()) hunks.add(cur);

        String result = content;
        for (List<String> hunk : hunks) {
            List<String> oldLines = new ArrayList<>();
            List<String> newLines = new ArrayList<>();
            for (String l : hunk) {
                if (l.startsWith("-")) {
                    oldLines.add(l.substring(1));
                } else if (l.startsWith("+")) {
                    newLines.add(l.substring(1));
                } else if (l.startsWith(" ")) {
                    oldLines.add(l.substring(1));
                    newLines.add(l.substring(1));
                } else {
                    oldLines.add(l);
                    newLines.add(l);
                }
            }
            String oldBlock = String.join("\n", oldLines);
            String newBlock = String.join("\n", newLines);
            if (oldBlock.length() > 0) {
                if (!result.contains(oldBlock)) throw new RuntimeException("hunk does not match file contents");
                result = replaceFirstLiteral(result, oldBlock, newBlock);
            } else {
                result = result + (result.endsWith("\n") || result.isEmpty() ? "" : "\n") + newBlock;
            }
        }
        return result;
    }

    private static Tool applyPatchTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("patchText", prop("string", "The patch text in Begin/End Patch format"));
        return builtin(
                "apply_patch",
                "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
                schema(props, List.of("patchText")),
                (args, ctx) -> {
                    String patchText = str(args.get("patchText"));
                    if (patchText.isEmpty()) return err("apply_patch: patchText is required");
                    List<PatchOp> ops;
                    try {
                        ops = parsePatch(patchText);
                    } catch (RuntimeException e) {
                        return err("apply_patch: " + e.getMessage());
                    }
                    // Stage every write/delete first; only touch the filesystem once all hunks apply.
                    List<String[]> writes = new ArrayList<>(); // [path, content]
                    List<String> deletes = new ArrayList<>();
                    try {
                        for (PatchOp op : ops) {
                            if (op.type.equals("add")) {
                                if (Files.exists(Path.of(op.path))) {
                                    throw new RuntimeException("file already exists: " + op.path);
                                }
                                writes.add(new String[]{op.path, op.content});
                            } else if (op.type.equals("delete")) {
                                if (!Files.exists(Path.of(op.path))) {
                                    throw new RuntimeException("file not found: " + op.path);
                                }
                                deletes.add(op.path);
                            } else {
                                String content = Files.readString(Path.of(op.path));
                                writes.add(new String[]{op.path, applyUpdate(content, op.body)});
                            }
                        }
                    } catch (Exception e) {
                        return err("apply_patch: " + e.getMessage());
                    }
                    for (String[] w : writes) {
                        Path pp = Path.of(w[0]);
                        Path parent = pp.toAbsolutePath().getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.writeString(pp, w[1]);
                    }
                    for (String d : deletes) {
                        Files.deleteIfExists(Path.of(d));
                    }
                    int added = 0, updated = 0, deleted = 0;
                    for (PatchOp op : ops) {
                        if (op.type.equals("add")) added++;
                        else if (op.type.equals("update")) updated++;
                        else deleted++;
                    }
                    return ok("Applied patch: " + ops.size() + " file operation" + (ops.size() == 1 ? "" : "s"),
                            meta("added", added, "updated", updated, "deleted", deleted));
                });
    }

    // -----------------------------------------------------------------------
    // glob helpers (shared by grep + glob)
    // -----------------------------------------------------------------------

    /** Convert a glob ({@code *}, {@code **}, {@code ?}) to an anchored regex string. */
    static String globToRegExp(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') i++;
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else if ("\\^$.|+()[]{}".indexOf(c) >= 0) {
                re.append("\\").append(c);
            } else {
                re.append(c);
            }
        }
        return re.toString();
    }

    /** Match a relative path against a glob; slash-less globs test the basename. */
    static boolean matchGlob(String rel, String glob) {
        Pattern re = Pattern.compile("^" + globToRegExp(glob) + "$");
        if (!glob.contains("/")) {
            String base = Path.of(rel).getFileName().toString();
            return re.matcher(base).matches();
        }
        return re.matcher(rel).matches();
    }

    /** Recursively list files under {@code root} (skips node_modules/.git). Returns absolute paths. */
    private static List<Path> walkFiles(Path root) {
        List<Path> out = new ArrayList<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Path dir = stack.pop();
            List<Path> entries = new ArrayList<>();
            try (var s = Files.list(dir)) {
                s.forEach(entries::add);
            } catch (IOException e) {
                continue;
            }
            for (Path entry : entries) {
                String fn = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (IGNORE_DIRS.contains(fn)) continue;
                    stack.push(entry);
                } else if (Files.isRegularFile(entry)) {
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static String relativize(Path root, Path file) {
        return root.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
    }

    // -----------------------------------------------------------------------
    // text helpers
    // -----------------------------------------------------------------------

    /** Number of non-overlapping occurrences (matches JS {@code split(x).length - 1}). */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Replace the first literal occurrence only (matches JS {@code String.replace(str, str)}). */
    private static String replaceFirstLiteral(String content, String oldString, String newString) {
        int idx = content.indexOf(oldString);
        if (idx == -1) return content;
        return content.substring(0, idx) + newString + content.substring(idx + oldString.length());
    }

    /** Very light HTML -> text: drop scripts/styles + tags, collapse whitespace. */
    static String stripHtml(String html) {
        return html
                .replaceAll("(?is)<script.*?</script>", "")
                .replaceAll("(?is)<style.*?</style>", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
