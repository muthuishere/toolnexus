package io.github.muthuishere.toolnexus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.yaml.snakeyaml.Yaml;

/**
 * Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts
 * and the JS reference ({@code js/src/skill.ts}): discover {@code **}/SKILL.md,
 * parse YAML frontmatter, and expose ONE {@code skill} tool that loads a skill's
 * instructions + sampled resources on demand (progressive disclosure).
 *
 * <p>Beyond on-disk discovery the source also accepts skills supplied as data
 * ({@link SkillDef}) — SPEC.md §3. Directory-sourced skills keep the exact
 * {@code file://} base + on-disk sibling sampling (byte-identical); data-sourced
 * skills use a logical {@code skill://name/} base + a supplied resource list and
 * never touch disk.
 */
public final class SkillSource {
    public static final String SKILL_TOOL_DESCRIPTION =
            "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n"
            + "\n"
            + "Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.\n"
            + "\n"
            + "The skill name must match one of the skills listed in your system prompt.";

    /**
     * Instruction preamble prepended to {@link #prompt()} when >=1 described skill
     * exists. Byte-identical across all four ports — do not reword. See SPEC.md §3.
     */
    public static final String SKILLS_PROMPT_PREAMBLE =
            "Skills provide specialized instructions and workflows for specific tasks.\n"
            + "Use the skill tool to load a skill when a task matches its description.";

    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?([\\s\\S]*)$");

    public static final class SkillInfo {
        public final String name;
        public final String description; // may be null
        public final String location;    // absolute path to SKILL.md (fs) or logical base (data)
        public final String content;     // body after frontmatter
        public final String origin;      // "fs" (default) or "logical"
        public final List<String> resources; // logical resources (data skills)
        public final String base;        // logical base (data skills)

        SkillInfo(String name, String description, String location, String content) {
            this(name, description, location, content, "fs", null, null);
        }

        SkillInfo(String name, String description, String location, String content,
                  String origin, List<String> resources, String base) {
            this.name = name;
            this.description = description;
            this.location = location;
            this.content = content;
            this.origin = origin;
            this.resources = resources;
            this.base = base;
        }
    }

    /** A skill supplied directly as data, bypassing the filesystem (SPEC.md §3, S1). */
    public static final class SkillDef {
        public final String name;
        public final String description; // may be null
        public final String content;
        public final List<String> resources; // may be null ⇒ instruction-only
        public final String base;        // may be null ⇒ skill://<name>/

        public SkillDef(String name, String description, String content) {
            this(name, description, content, null, null);
        }

        public SkillDef(String name, String description, String content, List<String> resources, String base) {
            this.name = name;
            this.description = description;
            this.content = content;
            this.resources = resources;
            this.base = base;
        }
    }

    /** Why a candidate SKILL.md did not become a skill (S3). */
    public static final class SkillSkip {
        public static final String MISSING_NAME = "missing-name";
        public static final String MALFORMED = "malformed-frontmatter";
        public static final String DUPLICATE_NAME = "duplicate-name";
        public static final String UNREADABLE = "unreadable";

        public final String location;
        public final String reason;

        SkillSkip(String location, String reason) {
            this.location = location;
            this.reason = reason;
        }
    }

    /** Result of a list-only validate pass (S3). */
    public static final class SkillInventory {
        public final List<SkillInfo> skills;
        public final List<SkillSkip> skipped;

        SkillInventory(List<SkillInfo> skills, List<SkillSkip> skipped) {
            this.skills = skills;
            this.skipped = skipped;
        }
    }

    /** Options for {@link #loadWith} / {@link #listSkills} (SPEC.md §3, S1/S2/S5). */
    public static final class LoadOptions {
        public List<String> dirs;
        public List<SkillDef> skills;
        public Map<String, Boolean> filter;
        public int sampleLimit; // 0 ⇒ default 10, n>0 ⇒ cap, -1 ⇒ omit <skill_files>

        public LoadOptions dirs(List<String> v) { this.dirs = v; return this; }
        public LoadOptions dirs(String... v) { this.dirs = List.of(v); return this; }
        public LoadOptions skills(List<SkillDef> v) { this.skills = v; return this; }
        public LoadOptions filter(Map<String, Boolean> v) { this.filter = v; return this; }
        public LoadOptions sampleLimit(int v) { this.sampleLimit = v; return this; }
    }

    private final Map<String, SkillInfo> skills;
    private final Tool tool;

    private SkillSource(Map<String, SkillInfo> skills, Tool tool) {
        this.skills = skills;
        this.tool = tool;
    }

    public Map<String, SkillInfo> skills() {
        return skills;
    }

    public Tool tool() {
        return tool;
    }

    /** Markdown catalog for the system prompt (mirrors opencode Skill.fmt). */
    public String prompt() {
        List<SkillInfo> described = skills.values().stream()
                .filter(s -> s.description != null)
                .sorted(Comparator.comparing(s -> s.name))
                .collect(Collectors.toList());
        if (described.isEmpty()) return "No skills are currently available.";
        StringBuilder sb = new StringBuilder(SKILLS_PROMPT_PREAMBLE);
        sb.append("\n\n## Available Skills");
        for (SkillInfo s : described) {
            sb.append("\n- **").append(s.name).append("**: ").append(s.description);
        }
        return sb.toString();
    }

    public static SkillSource load(String... dirs) {
        return load(List.of(dirs));
    }

    /** Discover skills under one or more roots and build the `skill` loader tool. */
    public static SkillSource load(List<String> dirs) {
        LoadOptions opts = new LoadOptions();
        opts.dirs = dirs;
        return loadWith(opts);
    }

    /** One candidate before cross-source dedupe: a parsed skill OR a typed skip. */
    private static final class RawCandidate {
        final SkillInfo info;
        final SkillSkip skip;

        RawCandidate(SkillInfo info, SkillSkip skip) {
            this.info = info;
            this.skip = skip;
        }
    }

    private static List<RawCandidate> candidatesFromDir(String root) {
        List<RawCandidate> out = new ArrayList<>();
        Path rootPath = Path.of(root);
        if (!Files.isDirectory(rootPath)) {
            System.err.println("[toolnexus] skills dir not found: " + root);
            return out;
        }
        for (Path file : walkSkillFiles(rootPath)) {
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                out.add(new RawCandidate(null, new SkillSkip(file.toAbsolutePath().toString(), SkillSkip.UNREADABLE)));
                continue;
            }
            Map<String, Object> parsed = parseFrontmatter(text);
            @SuppressWarnings("unchecked")
            Map<String, String> data = (Map<String, String>) parsed.get("data");
            String content = (String) parsed.get("content");
            boolean malformed = Boolean.TRUE.equals(parsed.get("malformed"));
            String abs = file.toAbsolutePath().toString();
            if (malformed) {
                out.add(new RawCandidate(null, new SkillSkip(abs, SkillSkip.MALFORMED)));
                continue;
            }
            String name = data.get("name");
            if (name == null || name.isEmpty()) {
                out.add(new RawCandidate(null, new SkillSkip(abs, SkillSkip.MISSING_NAME)));
                continue;
            }
            out.add(new RawCandidate(
                    new SkillInfo(name, data.get("description"), abs, content, "fs", null, null), null));
        }
        return out;
    }

    private static List<RawCandidate> candidatesFromDefs(List<SkillDef> defs) {
        List<RawCandidate> out = new ArrayList<>();
        for (SkillDef d : defs) {
            if (d.name == null || d.name.isEmpty()) {
                String loc = d.base != null ? d.base : "skill://";
                out.add(new RawCandidate(null, new SkillSkip(loc, SkillSkip.MISSING_NAME)));
                continue;
            }
            String base = (d.base != null && !d.base.isEmpty()) ? d.base : "skill://" + d.name + "/";
            List<String> res = d.resources != null ? new ArrayList<>(d.resources) : new ArrayList<>();
            out.add(new RawCandidate(new SkillInfo(
                    d.name, d.description, base, d.content != null ? d.content : "", "logical", res, base), null));
        }
        return out;
    }

    private static List<RawCandidate> collectCandidates(LoadOptions opts) {
        List<RawCandidate> cands = new ArrayList<>();
        if (opts.dirs != null) {
            for (String root : opts.dirs) cands.addAll(candidatesFromDir(root));
        }
        if (opts.skills != null && !opts.skills.isEmpty()) {
            cands.addAll(candidatesFromDefs(opts.skills));
        }
        return cands;
    }

    private static final class Merged {
        final Map<String, SkillInfo> skills;
        final List<SkillSkip> skipped;

        Merged(Map<String, SkillInfo> skills, List<SkillSkip> skipped) {
            this.skills = skills;
            this.skipped = skipped;
        }
    }

    private static Merged mergeCandidates(List<RawCandidate> cands) {
        Map<String, SkillInfo> skills = new LinkedHashMap<>();
        List<SkillSkip> skipped = new ArrayList<>();
        for (RawCandidate c : cands) {
            if (c.skip != null) {
                skipped.add(c.skip);
                continue;
            }
            SkillInfo info = c.info;
            if (skills.containsKey(info.name)) {
                System.err.println("[toolnexus] duplicate skill name \"" + info.name + "\" ("
                        + info.location + ") — keeping first");
                skipped.add(new SkillSkip(info.location, SkillSkip.DUPLICATE_NAME));
                continue;
            }
            skills.put(info.name, info);
        }
        return new Merged(skills, skipped);
    }

    /**
     * Per-agent skill allowlist (S2): nil/empty ⇒ all; >=1 true ⇒ allowlist;
     * only-false ⇒ drop-list over all-on; unknown names ignored + warned once.
     */
    private static Map<String, SkillInfo> applyFilter(Map<String, SkillInfo> skills, Map<String, Boolean> filter) {
        if (filter == null || filter.isEmpty()) return skills;
        boolean hasTrue = filter.values().stream().anyMatch(Boolean.TRUE::equals);
        for (String k : filter.keySet()) {
            if (!skills.containsKey(k)) {
                System.err.println("[toolnexus] skill filter name \"" + k + "\" matched no skill");
            }
        }
        Map<String, SkillInfo> out = new LinkedHashMap<>();
        for (Map.Entry<String, SkillInfo> e : skills.entrySet()) {
            Boolean v = filter.get(e.getKey());
            boolean keep = hasTrue ? Boolean.TRUE.equals(v) : !Boolean.FALSE.equals(v);
            if (keep) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Discover + validate skills from the same sources {@link #load} accepts,
     * returning parsed skills plus typed skip reasons — no toolkit wired
     * (SPEC.md §3, S3). The inventory is UNFILTERED (it authors the S2 allowlist).
     */
    public static SkillInventory listSkills(LoadOptions opts) {
        Merged merged = mergeCandidates(collectCandidates(opts));
        return new SkillInventory(new ArrayList<>(merged.skills.values()), merged.skipped);
    }

    /** Discover skills (dirs and/or data) and build the `skill` loader tool. */
    public static SkillSource loadWith(LoadOptions opts) {
        Merged merged = mergeCandidates(collectCandidates(opts));
        Map<String, SkillInfo> resolved = applyFilter(merged.skills, opts.filter);
        Tool tool = new SkillTool(resolved, opts.sampleLimit);
        return new SkillSource(resolved, tool);
    }

    private static final class SkillTool implements Tool {
        private final Map<String, SkillInfo> skills;
        private final int sampleLimit;
        private final Map<String, Object> inputSchema;

        SkillTool(Map<String, SkillInfo> skills, int sampleLimit) {
            this.skills = skills;
            this.sampleLimit = sampleLimit;
            Map<String, Object> nameProp = new LinkedHashMap<>();
            nameProp.put("type", "string");
            nameProp.put("description", "The name of the skill to load");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("name", nameProp);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("name"));
            schema.put("additionalProperties", false);
            this.inputSchema = schema;
        }

        @Override
        public String name() {
            return "skill";
        }

        @Override
        public String description() {
            return SKILL_TOOL_DESCRIPTION;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return inputSchema;
        }

        @Override
        public String source() {
            return "skill";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            String name = args == null || args.get("name") == null ? "" : String.valueOf(args.get("name"));
            SkillInfo info = skills.get(name);
            if (info == null) {
                List<String> avail = new ArrayList<>(skills.keySet());
                avail.sort(Comparator.naturalOrder());
                String list = avail.isEmpty() ? "none" : String.join(", ", avail);
                return ToolResult.error("Skill \"" + name + "\" not found. Available skills: " + list);
            }
            // effLimit: 0 ⇒ default 10 (byte-identical), n>0 ⇒ cap, -1 ⇒ omit.
            int effLimit = sampleLimit == 0 ? 10 : sampleLimit;
            boolean emitFiles = effLimit != -1;
            String base;
            String metaDir;
            List<String> files;
            if ("logical".equals(info.origin)) {
                base = (info.base != null && !info.base.isEmpty()) ? info.base : "skill://" + info.name + "/";
                List<String> res = info.resources != null ? info.resources : List.of();
                if (res.isEmpty()) emitFiles = false;
                files = (effLimit > 0 && res.size() > effLimit) ? res.subList(0, effLimit) : res;
                metaDir = base;
            } else {
                Path dir = Path.of(info.location).getParent();
                base = pathToFileUrl(dir);
                files = effLimit == -1 ? List.of() : sampleSiblingFiles(dir, effLimit);
                metaDir = dir.toString();
            }

            StringBuilder filesBlock = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) filesBlock.append("\n");
                filesBlock.append("<file>").append(files.get(i)).append("</file>");
            }

            List<String> lines = new ArrayList<>(List.of(
                    "<skill_content name=\"" + info.name + "\">",
                    "# Skill: " + info.name,
                    "",
                    info.content.trim(),
                    "",
                    "Base directory for this skill: " + base,
                    "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory."));
            if (emitFiles) {
                lines.add("Note: file list is sampled.");
                lines.add("");
                lines.add("<skill_files>");
                lines.add(filesBlock.toString());
                lines.add("</skill_files>");
            }
            lines.add("</skill_content>");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", info.name);
            meta.put("dir", metaDir);
            return new ToolResult(String.join("\n", lines), false, meta);
        }
    }

    /**
     * Parse YAML frontmatter (between the leading {@code ---} fences) with a real
     * YAML parser (SnakeYAML). The returned map carries {@code data}, {@code content},
     * and {@code malformed} — the last true only when fences are present but the YAML
     * fails to parse, so the inventory (S3) reports the right skip reason. load's
     * behavior is unchanged. See SPEC.md §3.
     */
    static Map<String, Object> parseFrontmatter(String text) {
        Matcher m = FRONTMATTER.matcher(text);
        Map<String, String> data = new LinkedHashMap<>();
        String content;
        boolean malformed = false;
        if (!m.find()) {
            content = text;
        } else {
            Object parsed;
            try {
                parsed = new Yaml().load(m.group(1));
            } catch (RuntimeException e) {
                // Malformed YAML must never crash discovery — fall back to empty.
                parsed = null;
                malformed = true;
            }
            if (parsed instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (key == null) continue;
                    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                        data.put(String.valueOf(key), String.valueOf(value).strip());
                    }
                }
            }
            content = m.group(2);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("content", content);
        out.put("malformed", malformed);
        return out;
    }

    private static List<Path> walkSkillFiles(Path root) {
        List<Path> out = new ArrayList<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(root);
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            Path dir = stack.pop();
            try (Stream<Path> entries = Files.list(dir)) {
                List<Path> list = entries.collect(Collectors.toList());
                for (Path entry : list) {
                    String fn = entry.getFileName().toString();
                    boolean isDir = Files.isDirectory(entry);
                    boolean isFile = Files.isRegularFile(entry);
                    if (isDir) {
                        if (fn.equals("node_modules") || fn.equals(".git")) continue;
                        String real;
                        try {
                            real = entry.toRealPath().toString();
                        } catch (IOException e) {
                            continue;
                        }
                        if (!seen.add(real)) continue;
                        stack.push(entry);
                    } else if (isFile && fn.equals("SKILL.md")) {
                        out.add(entry);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static List<String> sampleSiblingFiles(Path dir, int limit) {
        List<String> out = new ArrayList<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(dir);
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty() && out.size() < limit) {
            Path cur = stack.pop();
            try (Stream<Path> entries = Files.list(cur)) {
                List<Path> list = entries.collect(Collectors.toList());
                for (Path entry : list) {
                    if (out.size() >= limit) break;
                    String fn = entry.getFileName().toString();
                    boolean isDir = Files.isDirectory(entry);
                    boolean isFile = Files.isRegularFile(entry);
                    if (isDir) {
                        if (fn.equals("node_modules") || fn.equals(".git")) continue;
                        String real;
                        try {
                            real = entry.toRealPath().toString();
                        } catch (IOException e) {
                            continue;
                        }
                        if (!seen.add(real)) continue;
                        stack.push(entry);
                    } else if (isFile && !fn.equals("SKILL.md")) {
                        out.add(entry.toAbsolutePath().toString());
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** Match Node's pathToFileURL(dir).href: file://<abs path>, no trailing slash. */
    static String pathToFileUrl(Path dir) {
        String abs = dir.toAbsolutePath().toString();
        return "file://" + abs;
    }
}
