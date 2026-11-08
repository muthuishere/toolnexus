package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.TranscriptSearch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Level-1 surface (SPEC §7D "Level-1 surface"): {@code agent(name, spec)} →
 * {@link Agent#run(RuntimeOptions, String)} (one-shot) and {@link Agent#asTool(RuntimeOptions)}
 * (the bridge into the classic API's {@code extraTools} — the axiom's other direction: an Agent
 * IS a Tool). Everything compiles down to the six verbs of {@link AgentRuntime}.
 *
 * <p>Team scoping is the wiring model: listing agents in {@link AgentSpec#team} IS the subagent
 * graph. A runtime registry is the transitive closure of the entry agent's team graph —
 * unreachable agents are not present. Children get no {@code task} reach unless their own spec
 * declares a team (recursion is opt-in, never default).
 */
public final class Agents {

    private Agents() {}

    /** The heartbeat sentinel — a wake whose result contains it produces no outbound report. */
    public static final String HEARTBEAT_OK = "HEARTBEAT_OK";

    /** The prompt a heartbeat wakes the persona with (SPEC §7E). */
    public static final String HEARTBEAT_PROMPT =
            "Heartbeat. Read your HEARTBEAT.md section and follow it. "
                    + "If nothing needs attention, reply HEARTBEAT_OK.";

    /** What a dream pass is FOR and the order it works in (SPEC §7E "Dreaming"). Byte-identical in
     * every port; it becomes the dreamer's {@code ## HEARTBEAT.md} section. */
    public static final String DREAM_PROMPT = String.join("\n",
            "You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.",
            "Work in this order:",
            "1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix a claim the transcripts contradict; remove one they no longer support.",
            "2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.",
            "3. Merge: fold duplicate or conflicting notes into one.",
            "4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.",
            "5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why.",
            "If nothing needs changing, write nothing and reply HEARTBEAT_OK.");

    /** Bootstrap discovery order for {@link #agentFromDir}, injected as {@code ## <file>} sections. */
    public static final List<String> BOOTSTRAP_ORDER = List.of(
            "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md");
    static final long MAX_BOOTSTRAP_FILE_BYTES = 2L * 1024 * 1024;

    /** The declarative spec behind {@link #agent(String, AgentSpec)} — SPEC §7D field-for-field:
     * {@code does, uses(tools), soul|soulFile, team, budget, model("inherit"), waitFor, onSpawn,
     * onClose, hooks, onMetric}. */
    public static final class AgentSpec {
        /** Optional explicit name ({@link #agentFromDir} override). */
        public String name;
        /** What the delegating model sees — the required routing description. */
        public String does;
        /** The toolkit VIEW for this agent (scoping is the whole security model). */
        public List<Tool> tools;
        /** Identity: inline soul text… */
        public String soul;
        /** …or a soul file path (AGENTS.md / SOUL.md), read at registry-build time. */
        public Path soulFile;
        /** {@code task}-tool targets: listing agents here IS the subagent wiring. */
        public List<Agent> team;
        public Budget budget;
        /** Model id; {@code null} ⇒ {@code "inherit"} (the runtime default). */
        public String model;
        /** §10 interpreter authority for this agent's subtree. */
        public Function<Request, Answer> waitFor;
        public Consumer<Handle> onSpawn;
        public BiConsumer<Handle, String> onClose;
        /** §8 lifecycle hooks for this agent's per-turn client (SPEC §7D "The §8 seams on an agent
         * run"); REPLACES the runtime-wide {@code hooks} for this agent. */
        public io.github.muthuishere.toolnexus.LlmClient.Hooks hooks;
        /** §8 observability sink for this agent; REPLACES the runtime-wide {@code onMetric}.
         * Resolves independently of {@link #hooks}. */
        public Consumer<io.github.muthuishere.toolnexus.LlmClient.MetricEvent> onMetric;
        /** POLICY checks on tool calls — "may it?", never "is it right?". Compiled into one
         * {@code beforeTool} with FIRST-DENY-WINS: a later guardrail can never widen an earlier
         * denial. Composed with {@code hooks.beforeTool}, which runs only if every guardrail
         * allows. Empty ⇒ byte-identical. */
        public java.util.List<io.github.muthuishere.toolnexus.Loop.Guardrail> guardrails;
        /** When set, the gate that stops this agent claiming {@code done} before its work
         * verifies. It travels WITH the agent, so a caller that delegates via {@code task}
         * inherits it — which a host-side retry loop cannot do. Null ⇒ byte-identical. */
        public io.github.muthuishere.toolnexus.Loop.Completion completion;
        /** §7E persona surface: {@code false} omits the file-backed {@code memory} builtin that
         * {@link #agentFromDir} otherwise wires (read-only personas). {@code null} ⇒ enabled. */
        public Boolean memory;
        /** §7E memory hierarchy: cap the injected {@code MEMORY.md} (whole lines). {@code null} ⇒ off. */
        public MemoryIndexCap memoryIndexCap;
        /** §7E memory hierarchy: the notes tier of the memory tool. {@code null}/false ⇒ off. */
        public Boolean notes;
        /** §7E dreaming: when started with {@link #startAgent}, a {@link #dreamer} over the same dir
         * runs on the same runtime every {@code everyMs}. {@code null} ⇒ off. */
        public Dream dream;

        public AgentSpec name(String v) { this.name = v; return this; }
        public AgentSpec does(String v) { this.does = v; return this; }
        public AgentSpec tools(Tool... v) { this.tools = List.of(v); return this; }
        public AgentSpec soul(String v) { this.soul = v; return this; }
        public AgentSpec soulFile(Path v) { this.soulFile = v; return this; }
        public AgentSpec team(Agent... v) { this.team = List.of(v); return this; }
        public AgentSpec budget(Budget v) { this.budget = v; return this; }
        public AgentSpec model(String v) { this.model = v; return this; }
        public AgentSpec waitFor(Function<Request, Answer> v) { this.waitFor = v; return this; }
        public AgentSpec onSpawn(Consumer<Handle> v) { this.onSpawn = v; return this; }
        public AgentSpec onClose(BiConsumer<Handle, String> v) { this.onClose = v; return this; }
        public AgentSpec hooks(io.github.muthuishere.toolnexus.LlmClient.Hooks v) { this.hooks = v; return this; }
        public AgentSpec onMetric(Consumer<io.github.muthuishere.toolnexus.LlmClient.MetricEvent> v) { this.onMetric = v; return this; }
        public AgentSpec memory(boolean v) { this.memory = v; return this; }
        public AgentSpec memoryIndexCap(MemoryIndexCap v) { this.memoryIndexCap = v; return this; }
        public AgentSpec notes(boolean v) { this.notes = v; return this; }
        public AgentSpec dream(Dream v) { this.dream = v; return this; }
        public AgentSpec guardrails(io.github.muthuishere.toolnexus.Loop.Guardrail... v) {
            this.guardrails = java.util.List.of(v); return this;
        }
        public AgentSpec completion(io.github.muthuishere.toolnexus.Loop.Completion v) {
            this.completion = v; return this;
        }
    }

    /** The one new noun. */
    public static Agent agent(String name, AgentSpec spec) {
        return new Agent(name, spec);
    }

    public static final class Agent {
        public final String name;
        public final AgentSpec spec;
        /** Set by {@code agentFromDir(…, dream)}; read by {@link #startAgent}. */
        Agent dreamer;
        long dreamEveryMs;

        Agent(String name, AgentSpec spec) {
            this.name = name;
            this.spec = spec;
        }

        /**
         * Open a LIVE EXECUTION of this agent. Takes client OPTIONS rather than a built client,
         * because a per-call {@code model} override must be able to change the model — which is
         * fixed when a client is constructed.
         */
        public io.github.muthuishere.toolnexus.Loop loop(
                io.github.muthuishere.toolnexus.LlmClient.Options options,
                io.github.muthuishere.toolnexus.Toolkit toolkit) {
            // ADR 0024: the loop receives the SPEC, not four loose fields — so soul, guardrails,
            // hooks, completion, model and budget.maxTurns all reach it. What a driver cannot
            // honour is named by Loop.loopUnsupported(spec).
            return new io.github.muthuishere.toolnexus.Loop(options, toolkit, spec);
        }

        /** Collect this agent + its whole team graph into a runtime registry (transitive closure
         * of the entry agent's team graph — unreachable agents are absent). */
        public Map<String, AgentDef> registry(Map<String, AgentDef> acc) {
            if (acc.containsKey(name)) return acc;
            String soul = spec.soul != null ? spec.soul : "";
            if (spec.soulFile != null) {
                try {
                    soul = Files.readString(spec.soulFile);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            AgentDef def = new AgentDef(name, spec.does, soul, spec.model != null ? spec.model : "inherit");
            def.tools = spec.tools;
            def.budget = spec.budget;
            def.waitFor = spec.waitFor;
            def.onSpawn = spec.onSpawn;
            def.onClose = spec.onClose;
            // Guardrails are compiled HERE, at the registry boundary, so a delegated child is
            // governed by them too — not only a directly-driven one.
            def.hooks = io.github.muthuishere.toolnexus.Loop.guardedHooks(spec.guardrails, spec.hooks);
            def.onMetric = spec.onMetric;
            def.completion = spec.completion;
            // Team scoping: task targets = ONLY this agent's team (empty = no delegation at all —
            // recursion is opt-in, never default).
            List<String> targets = new ArrayList<>();
            if (spec.team != null) for (Agent a : spec.team) targets.add(a.name);
            def.team = targets;
            acc.put(name, def);
            if (spec.team != null) for (Agent a : spec.team) a.registry(acc);
            return acc;
        }

        public Map<String, AgentDef> registry() {
            return registry(new LinkedHashMap<>());
        }

        /** One-shot: build a runtime scoped to this agent's team graph, run the prompt to
         * completion, tear down (unless the run parked {@code pending} — a durable pending keeps
         * the tree suspended for {@link AgentRuntime#resume}). */
        public TaskResult run(RuntimeOptions rtOpts, String prompt) {
            AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(registry()));
            AgentRuntime.Spawn sp = rt.spawn(rt.root, name);
            if (sp.error() != null) return new TaskResult(sp.error(), true, TaskResult.STATUS_ERROR, null, null, 0, 0);
            var fut = rt.futureResult(sp.handle());
            rt.wake(sp.handle(), prompt);
            TaskResult r = fut.join();
            if (!"pending".equals(r.status())) rt.close(rt.root);
            return r;
        }

        /** The bridge: an Agent IS a Tool — drop it into the classic API's {@code extraTools}.
         * The tool takes {@code {prompt}}, runs the agent's own loop in isolation, and returns
         * ONLY its final text + metadata {@code {agent, turns, totalTokens}}. Cancelling the calling
         * turn (the tool thread is interrupted, or {@link ToolContext#cancel()}) interrupts the
         * child and the tool returns a {@code cancelled: …} error result (SPEC §4C). */
        public Tool asTool(RuntimeOptions rtOpts) {
            return asToolWith(ctx -> rtOpts);
        }

        /**
         * {@link #asTool} with options built per call from the calling tool's context, so the
         * child runs under the parent turn's gates: set {@code hooks} (the same beforeTool
         * permission gate — it also sees the child's tool calls), {@code waitFor} (the same ask
         * bridge, used when no agent on the child's chain has its own) and {@code onMetric}. An
         * {@link AgentSpec#hooks} on the agent replaces {@code hooks} for that agent, so leave it
         * unset when the gate must hold (SPEC §4C).
         */
        public Tool asToolWith(java.util.function.Function<ToolContext, RuntimeOptions> per) {
            Agent self = this;
            return new Tool() {
                @Override public String name() { return self.name; }
                @Override public String description() { return self.spec.does; }
                @Override public Map<String, Object> inputSchema() {
                    return Map.of("type", "object",
                            "properties", Map.of("prompt", Map.of("type", "string")),
                            "required", List.of("prompt"));
                }
                @Override public String source() { return "custom"; }
                @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                    Map<String, Object> md = new LinkedHashMap<>();
                    md.put("agent", self.name);
                    TaskResult r = self.runCancellable(per.apply(ctx), String.valueOf(args.get("prompt")), ctx);
                    if (r == null) { // the calling turn was cancelled
                        md.put("turns", 0);
                        md.put("totalTokens", 0L);
                        return new ToolResult("cancelled: run cancelled", true, md);
                    }
                    md.put("turns", r.turns());
                    md.put("totalTokens", r.totalTokens());
                    return new ToolResult(r.text(), r.isError(), md);
                }
            };
        }

        /** {@link #run} that interrupts the child when the calling thread is interrupted or
         * {@code ctx} is cancelled; {@code null} = cancelled. */
        private TaskResult runCancellable(RuntimeOptions rtOpts, String prompt, ToolContext ctx) {
            if (Thread.currentThread().isInterrupted() || (ctx != null && ctx.isCancelled())) return null;
            AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(registry()));
            AgentRuntime.Spawn sp = rt.spawn(rt.root, name);
            if (sp.error() != null) return new TaskResult(sp.error(), true, TaskResult.STATUS_ERROR, null, null, 0, 0);
            var fut = rt.futureResult(sp.handle());
            rt.wake(sp.handle(), prompt);
            while (true) {
                try {
                    TaskResult r = fut.get(25, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!"pending".equals(r.status())) rt.close(rt.root);
                    return r;
                } catch (java.util.concurrent.TimeoutException te) {
                    if (ctx != null && ctx.isCancelled()) {
                        rt.close(rt.root, true, "cancelled");
                        return null;
                    }
                } catch (InterruptedException ie) {
                    rt.close(rt.root, true, "cancelled");
                    Thread.currentThread().interrupt();
                    return null;
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause() != null ? ee.getCause() : ee;
                    return new TaskResult(String.valueOf(c.getMessage()), true, TaskResult.STATUS_ERROR, null, null, 0, 0);
                }
            }
        }
    }

    /** The composed soul (frozen snapshot) plus the bootstrap files that contributed to it. */
    public record ComposedSoul(String soul, List<String> found) {}

    /**
     * The {@code MEMORY.md} index cap (SPEC §7E "Memory hierarchy"). A {@code null} field takes
     * the preset's ({@code 200} lines / {@code 25600} bytes); {@link #preset()} is both presets.
     */
    public record MemoryIndexCap(Integer maxLines, Integer maxBytes) {
        public static final int PRESET_MAX_LINES = 200;
        public static final int PRESET_MAX_BYTES = 25600;

        /** {@code memoryIndexCap: true} — 200 lines / 25600 bytes. */
        public static MemoryIndexCap preset() { return new MemoryIndexCap(null, null); }

        int lines() { return maxLines != null ? maxLines : PRESET_MAX_LINES; }

        int bytes() { return maxBytes != null ? maxBytes : PRESET_MAX_BYTES; }

        void validate() {
            if (lines() < 1 || bytes() < 1) {
                throw new IllegalArgumentException("memoryIndexCap: maxLines and maxBytes must be integers >= 1");
            }
        }
    }

    /** {@code fromDir(…, dream)} (SPEC §7E "Dreaming"): dream every {@code everyMs} over {@code store}. */
    public record Dream(long everyMs, LlmClient.ConversationStore store, String instructions) {
        public Dream(long everyMs, LlmClient.ConversationStore store) { this(everyMs, store, null); }
    }

    /** The exact notice appended to a capped {@code MEMORY.md} (SPEC §7E). */
    public static String memoryIndexNotice(int maxLines, int maxBytes, boolean notes) {
        String tail = notes ? "; move detail into notes (memory target \"note:<slug>\")." : " (keep entries short).";
        return "\n\n[MEMORY.md index truncated at " + maxLines + " lines / " + maxBytes
                + " bytes. Keep MEMORY.md a short index" + tail + "]";
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Apply the index cap to {@code MEMORY.md}'s (already 2 MB-capped) content: whole lines;
     * within both bounds ⇒ unchanged; otherwise the first {@code maxLines} lines, trailing lines
     * dropped until the "\n"-joined UTF-8 length fits (a lone over-long line is cut at a code-point
     * boundary), plus {@link #memoryIndexNotice}.
     */
    public static String applyIndexCap(String content, MemoryIndexCap cap, boolean notes) {
        int maxLines = cap.lines();
        int maxBytes = cap.bytes();
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        if (content.endsWith("\n")) lines.remove(lines.size() - 1);
        if (lines.size() <= maxLines && utf8Len(String.join("\n", lines)) <= maxBytes) return content;
        List<String> kept = new ArrayList<>(lines.subList(0, Math.min(maxLines, lines.size())));
        while (kept.size() > 1 && utf8Len(String.join("\n", kept)) > maxBytes) kept.remove(kept.size() - 1);
        String text = String.join("\n", kept);
        if (utf8Len(text) > maxBytes) {
            StringBuilder b = new StringBuilder();
            int bytes = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                int n = utf8Len(new String(Character.toChars(cp)));
                if (bytes + n > maxBytes) break;
                b.appendCodePoint(cp);
                bytes += n;
                i += Character.charCount(cp);
            }
            text = b.toString();
        }
        return text + memoryIndexNotice(maxLines, maxBytes, notes);
    }

    /**
     * Compose the discovered bootstrap files into one soul string (SPEC §7E "The directory is the
     * agent"). Present files in {@link #BOOTSTRAP_ORDER} are each injected as a {@code ## <file>}
     * section; absent files are skipped; each is read with the 2 MB cap ({@link #readCapped}).
     * Composition is a snapshot — the returned soul is fixed for the run (the frozen-snapshot rule).
     */
    public static ComposedSoul composeSoul(Path dir) {
        return composeSoul(dir, null, false);
    }

    /**
     * {@link #composeSoul(Path)} with the opt-in {@code MEMORY.md} index cap (SPEC §7E "Memory
     * hierarchy"): applied to {@code MEMORY.md} only, after the 2 MB cap and before the section
     * trim. {@code notes} picks the notice wording. {@code cap == null} ⇒ byte-identical.
     */
    public static ComposedSoul composeSoul(Path dir, MemoryIndexCap cap, boolean notes) {
        if (cap != null) cap.validate();
        List<String> sections = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (String f : BOOTSTRAP_ORDER) {
            String body = readCapped(dir.resolve(f));
            if (body != null && cap != null && "MEMORY.md".equals(f)) body = applyIndexCap(body, cap, notes);
            if (body != null) {
                sections.add("## " + f + "\n\n" + body.strip());
                found.add(f);
            }
        }
        return new ComposedSoul(String.join("\n\n", sections), found);
    }

    /** Notice appended to a bootstrap file truncated at {@link #MAX_BOOTSTRAP_FILE_BYTES}. */
    static final String TRUNCATION_NOTICE = "\n[truncated: exceeds 2 MB bootstrap cap]";

    /** Read a bootstrap file with the 2 MB cap applied in BYTES; {@code null} if absent. A larger
     * file is injected truncated with an explicit notice (the on-disk file is never touched). */
    private static String readCapped(Path p) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch (java.nio.file.NoSuchFileException e) {
            return null; // absent files are skipped (§7E)
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_BOOTSTRAP_FILE_BYTES) {
            return new String(bytes, 0, (int) MAX_BOOTSTRAP_FILE_BYTES, StandardCharsets.UTF_8)
                    + TRUNCATION_NOTICE;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Write via a temp file in the same dir, flushed (fsync), then an atomic move — a crash never
     * leaves a torn file; an existing file's permission bits are kept. */
    private static void writeAtomic(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        // CREATE_NEW (not createTempFile's 0600) so a new file gets 0666 & ~umask.
        Path tmp = parent.resolve("." + file.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            // Keep an existing file's permission bits (the move would otherwise give it the temp's).
            if (Files.exists(file) && Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file));
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Per resolved-absolute-path write locks (SPEC §7E write safety): read-modify-write is
     * serialized per file within this process, across every memory tool instance. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, java.util.concurrent.locks.ReentrantLock>
            WRITE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.concurrent.locks.ReentrantLock lockFor(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        try {
            // resolve symlinks in the directory when it exists, so two spellings share one lock
            if (parent != null && Files.exists(parent)) abs = parent.toRealPath().resolve(abs.getFileName());
        } catch (IOException ignored) {
            // fall back to the normalized absolute path
        }
        return WRITE_LOCKS.computeIfAbsent(abs, k -> new java.util.concurrent.locks.ReentrantLock());
    }

    /** Replace the FIRST occurrence of {@code find} (SPEC §7E) — never all of them. */
    private static String replaceFirst(String s, String find, String with) {
        int i = s.indexOf(find);
        return i < 0 ? s : s.substring(0, i) + with + s.substring(i + find.length());
    }

    /** Read-modify-write over the FULL file (never the capped view), under the per-path lock.
     * Returns an error result, or {@code null} on success. */
    private static ToolResult rmw(Path file, String action, String text, Object with) {
        java.util.concurrent.locks.ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            String cur = Files.exists(file) ? Files.readString(file) : "";
            String next;
            switch (action) {
                case "add" -> next = (cur.stripTrailing() + "\n- " + text + "\n").stripLeading();
                case "replace" -> {
                    if (!cur.contains(text)) return ToolResult.error("not found: " + text);
                    next = replaceFirst(cur, text, with != null ? String.valueOf(with) : "");
                }
                case "remove" -> {
                    if (!cur.contains(text)) return ToolResult.error("not found: " + text);
                    next = replaceFirst(cur, text, "");
                }
                default -> { return ToolResult.error("unknown action: " + action); }
            }
            writeAtomic(file, next);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    private static final String TEXT_DESC = "For add: the entry. For replace/remove: the existing text.";
    private static final String WITH_DESC = "For replace: the replacement text.";

    /** The notes-off description (fixture-pinned). */
    public static final String MEMORY_DESCRIPTION =
            "Persist durable memory. action=add appends an entry; replace swaps an "
                    + "existing substring (with); remove deletes one. target=self (MEMORY.md, "
                    + "default) or user (USER.md). Writes persist to disk and load at the START of "
                    + "your NEXT session — they do NOT change your current context.";

    /** The notes-on description (SPEC §7E, fixture-pinned). */
    public static final String MEMORY_NOTES_DESCRIPTION =
            "Persist durable memory. MEMORY.md is a short index loaded at the start of every "
                    + "session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never "
                    + "loaded automatically) and point to them from the index. Record what will change a "
                    + "future decision. action=add appends an entry; replace swaps the first occurrence of "
                    + "an existing substring (with); remove deletes it; read returns a file; list lists the "
                    + "notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: "
                    + "lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the "
                    + "START of your NEXT session — they do NOT change your current context.";

    /** A valid note slug (SPEC §7E): lowercase, no path separators, no {@code ..}. */
    public static final java.util.regex.Pattern NOTE_SLUG = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private static boolean validSlug(String slug) {
        return NOTE_SLUG.matcher(slug).matches() && !slug.contains("..");
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (description != null) m.put("description", description);
        return m;
    }

    private static Map<String, Object> memorySchema(boolean notes) {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", notes ? List.of("add", "replace", "remove", "read", "list") : List.of("add", "replace", "remove"));
        props.put("action", action);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "string");
        if (notes) {
            target.put("description", "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md");
        } else {
            target.put("enum", List.of("self", "user"));
            target.put("description", "self=MEMORY.md (default), user=USER.md");
        }
        props.put("target", target);
        props.put("text", prop("string", TEXT_DESC));
        props.put("with", prop("string", WITH_DESC));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", notes ? List.of("action") : List.of("action", "text"));
        return schema;
    }

    /**
     * The {@code memory} builtin (SPEC §7E, §4A note) — file-backed, opt-in, NOT a default builtin.
     * One tool, three actions ({@code add}/{@code replace}/{@code remove}) over {@code MEMORY.md}
     * (target {@code self}, default) or {@code USER.md} (target {@code user}) under {@code dir}.
     *
     * <p>All actions write to DISK. A {@code replace}/{@code remove} whose substring is absent
     * returns a loud {@code isError}; both touch the FIRST occurrence only. The tool does NOT
     * mutate the current session's system prompt: the frozen snapshot in the live prompt is
     * intentionally left stale — the next {@code fromDir} session re-reads the file.
     */
    public static Tool memoryTool(Path dir) {
        return memoryTool(dir, false);
    }

    /**
     * {@link #memoryTool(Path)}; {@code notes=true} turns on the notes tier (SPEC §7E "Memory
     * hierarchy"): {@code note:<slug>} targets ({@code <dir>/memory/<slug>.md}, never injected
     * into the soul) plus {@code read} and {@code list}. {@code notes=false} ⇒ byte-identical.
     */
    public static Tool memoryTool(Path dir, boolean notes) {
        Map<String, Object> schema = memorySchema(notes);
        Path notesDir = dir.resolve("memory");
        return new Tool() {
            @Override public String name() { return "memory"; }
            @Override public String description() { return notes ? MEMORY_NOTES_DESCRIPTION : MEMORY_DESCRIPTION; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return notes ? executeNotes(args) : executePlain(args);
            }

            private ToolResult executePlain(Map<String, Object> args) {
                String target = args.get("target") != null ? String.valueOf(args.get("target")) : "self";
                Path file = dir.resolve("user".equals(target) ? "USER.md" : "MEMORY.md");
                String action = String.valueOf(args.get("action"));
                ToolResult err = rmw(file, action, String.valueOf(args.get("text")), args.get("with"));
                if (err != null) return err;
                return ToolResult.ok("ok (" + action + " → " + file.getFileName() + "); loads next session");
            }

            private ToolResult executeNotes(Map<String, Object> args) {
                String action = String.valueOf(args.get("action"));
                if ("list".equals(action)) return ToolResult.ok(listNotes(notesDir));
                String target = args.get("target") != null ? String.valueOf(args.get("target")) : "self";
                Path file;
                String label;
                if ("self".equals(target) || "user".equals(target)) {
                    file = dir.resolve("user".equals(target) ? "USER.md" : "MEMORY.md");
                    label = file.getFileName().toString();
                } else if (target.startsWith("note:")) {
                    String slug = target.substring(5);
                    if (!validSlug(slug)) return ToolResult.error("invalid note slug: " + slug);
                    file = notesDir.resolve(slug + ".md");
                    label = "memory/" + slug + ".md";
                } else {
                    return ToolResult.error("unknown target: " + target);
                }
                if ("read".equals(action)) {
                    String body = readCapped(file);
                    return body == null ? ToolResult.error("not found: " + target) : ToolResult.ok(body);
                }
                if (!List.of("add", "replace", "remove").contains(action)) {
                    return ToolResult.error("unknown action: " + action);
                }
                if (!(args.get("text") instanceof String text)) return ToolResult.error("text is required");
                ToolResult err = rmw(file, action, text, args.get("with"));
                if (err != null) return err;
                return label.startsWith("memory/")
                        ? ToolResult.ok("ok (" + action + " → " + label + ")")
                        : ToolResult.ok("ok (" + action + " → " + label + "); loads next session");
            }
        };
    }

    /** {@code list} output (SPEC §7E): {@code <slug>\t<bytes>} per note, slug-ascending by an
     * explicit sort (code-point order — never directory order). */
    private static String listNotes(Path notesDir) {
        List<Map.Entry<String, Long>> rows = new ArrayList<>();
        if (Files.isDirectory(notesDir)) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(notesDir)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (!n.endsWith(".md")) continue;
                    String slug = n.substring(0, n.length() - 3);
                    if (!validSlug(slug) || !Files.isRegularFile(p)) continue;
                    rows.add(Map.entry(slug, Files.size(p)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // slugs are ASCII, so String.compareTo is code-point order
        rows.sort(Map.Entry.comparingByKey());
        if (rows.isEmpty()) return "(no notes)";
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> r : rows) lines.add(r.getKey() + "\t" + r.getValue());
        return String.join("\n", lines);
    }

    /**
     * Level 2: the directory IS the agent (SPEC §7E). Discovers the bootstrap files (all optional,
     * in {@link #BOOTSTRAP_ORDER}) into the soul as {@code ## <file>} sections at session start
     * (frozen snapshot), and wires the file-backed {@link #memoryTool} over the same dir — unless
     * {@code overrides.memory(false)} opts out (a read-only persona). Any {@code overrides.tools}
     * are preserved; the memory tool is appended.
     */
    public static Agent agentFromDir(Path dir, AgentSpec overrides) {
        AgentSpec spec = overrides != null ? overrides : new AgentSpec();
        boolean notes = Boolean.TRUE.equals(spec.notes);
        ComposedSoul composed = composeSoul(dir, spec.memoryIndexCap, notes);
        String name = spec.name != null ? spec.name : dir.getFileName().toString();
        if (spec.does == null) spec.does = "persona agent from " + dir;
        spec.soul = composed.soul();
        if (spec.memory == null || spec.memory) {
            List<Tool> tools = new ArrayList<>();
            if (spec.tools != null) tools.addAll(spec.tools);
            tools.add(memoryTool(dir, notes));
            spec.tools = tools;
        }
        Agent persona = agent(name, spec);
        if (spec.dream != null) {
            if (spec.dream.everyMs() <= 0) throw new IllegalArgumentException("fromDir: dream.everyMs must be > 0");
            persona.dreamer = dreamer(dir, spec.dream.store(), spec.dream.instructions(), name + "-dreamer", null);
            persona.dreamEveryMs = spec.dream.everyMs();
        }
        return persona;
    }

    /**
     * The built-in dreamer preset (SPEC §7E "Dreaming"): a plain {@link Agent} whose tools are
     * EXACTLY {@code memory} (notes on, over {@code dir}) and {@code transcript_search} (over
     * {@code store}) — never the persona's own tools. Its soul is one {@code ## HEARTBEAT.md}
     * section holding {@link #DREAM_PROMPT}, then {@code DREAM.md} (trimmed, if non-empty), then
     * {@code instructions} (trimmed, if non-empty), joined by blank lines. Schedule it with
     * {@link #startAgent} or run one pass with {@code run(opts, HEARTBEAT_PROMPT)}.
     *
     * @param name  {@code null} ⇒ {@code <basename(dir)>-dreamer}
     * @param model {@code null} ⇒ {@code "inherit"} (the persona's client; use a cheap model there)
     */
    public static Agent dreamer(Path dir, LlmClient.ConversationStore store, String instructions,
                                String name, String model) {
        if (store == null) throw new IllegalArgumentException("dreamer: store is required");
        Tool search = TranscriptSearch.transcriptSearchTool(store);
        List<String> parts = new ArrayList<>();
        parts.add(DREAM_PROMPT);
        String dreamMd = readCapped(dir.resolve("DREAM.md"));
        if (dreamMd != null && !dreamMd.strip().isEmpty()) parts.add(dreamMd.strip());
        if (instructions != null && !instructions.strip().isEmpty()) parts.add(instructions.strip());
        AgentSpec spec = new AgentSpec()
                .does("dreams over the memory in " + dir)
                .soul("## HEARTBEAT.md\n\n" + String.join("\n\n", parts))
                .model(model != null ? model : "inherit");
        spec.tools = List.of(memoryTool(dir, true), search);
        return agent(name != null ? name : dir.getFileName() + "-dreamer", spec);
    }

    /** {@link #dreamer(Path, LlmClient.ConversationStore, String, String, String)} with defaults. */
    public static Agent dreamer(Path dir, LlmClient.ConversationStore store) {
        return dreamer(dir, store, null, null, null);
    }

    /** A long-lived persona started by {@link #startAgent}: its handle, its runtime, and the
     * heartbeat subscription. {@link #stop()} closes the whole tree. */
    public static final class StartedAgent {
        public final Handle handle;
        public final AgentRuntime rt;
        private final AutoCloseable heartbeat;

        StartedAgent(Handle handle, AgentRuntime rt, AutoCloseable heartbeat) {
            this.handle = handle;
            this.rt = rt;
            this.heartbeat = heartbeat;
        }

        public void stop() {
            if (heartbeat != null) {
                try {
                    heartbeat.close();
                } catch (Exception ignored) {
                    // a timer that fails to close must not block the close cascade
                }
            }
            rt.close(rt.root);
        }
    }

    /**
     * Level 2: start a long-lived persona. The heartbeat (driven by the runtime's injectable
     * {@link RuntimeClock} — virtual in fixtures) posts a tick to the agent's own inbox
     * (unsolicited rail; ticks coalesce) and wakes it; results containing {@link #HEARTBEAT_OK}
     * stay silent, anything else is reported.
     */
    public static StartedAgent startAgent(Agent a, RuntimeOptions rtOpts, Long everyMs,
                                          Consumer<String> onReport) {
        Map<String, AgentDef> registry;
        if (a.dreamer != null) {
            registry = a.dreamer.registry();
            registry.putAll(a.registry());
        } else {
            registry = a.registry();
        }
        AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(registry));
        AgentRuntime.Spawn sp = rt.spawn(rt.root, a.name);
        if (sp.error() != null) throw new IllegalStateException(sp.error());
        Handle handle = sp.handle();
        List<AutoCloseable> beats = new ArrayList<>();
        if (everyMs != null) beats.add(heartbeat(rt, handle, everyMs, onReport));
        if (a.dreamer != null) {
            // fromDir(…, dream): the dreamer runs on the SAME runtime on its own interval. Its
            // replies are housekeeping — they never reach onReport; what it did is in memory/log.md.
            AgentRuntime.Spawn dsp = rt.spawn(rt.root, a.dreamer.name);
            if (dsp.error() != null) throw new IllegalStateException(dsp.error());
            beats.add(heartbeat(rt, dsp.handle(), a.dreamEveryMs, null));
        }
        AutoCloseable all = beats.isEmpty() ? null : () -> {
            for (AutoCloseable c : beats) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // one timer failing to close must not keep the others alive
                }
            }
        };
        return new StartedAgent(handle, rt, all);
    }

    /** One heartbeat on {@code handle}: post a tick (coalesces) and, when idle, wake it with
     * {@link #HEARTBEAT_PROMPT}; a reply containing {@link #HEARTBEAT_OK} stays silent. */
    private static AutoCloseable heartbeat(AgentRuntime rt, Handle handle, long everyMs, Consumer<String> onReport) {
        return rt.clock().schedule(everyMs, () -> {
            rt.post(handle, new InboxItem("clock", "timer", "tick"));
            if (handle.state == Handle.State.IDLE) {
                Thread.startVirtualThread(() -> {
                    TaskResult r = rt.runTurn(handle, HEARTBEAT_PROMPT);
                    if (!r.isError() && !r.text().contains(HEARTBEAT_OK) && onReport != null) {
                        onReport.accept(r.text());
                    }
                });
            }
        });
    }
}
