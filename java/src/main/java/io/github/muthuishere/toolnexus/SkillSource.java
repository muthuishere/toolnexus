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
        public final String location;    // absolute path to SKILL.md
        public final String content;     // body after frontmatter

        SkillInfo(String name, String description, String location, String content) {
            this.name = name;
            this.description = description;
            this.location = location;
            this.content = content;
        }
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
        Map<String, SkillInfo> skills = new LinkedHashMap<>();

        for (String root : dirs) {
            Path rootPath = Path.of(root);
            if (!Files.isDirectory(rootPath)) {
                System.err.println("[toolnexus] skills dir not found: " + root);
                continue;
            }
            for (Path file : walkSkillFiles(rootPath)) {
                String text;
                try {
                    text = Files.readString(file);
                } catch (IOException e) {
                    continue;
                }
                Map<String, Object> parsed = parseFrontmatter(text);
                @SuppressWarnings("unchecked")
                Map<String, String> data = (Map<String, String>) parsed.get("data");
                String content = (String) parsed.get("content");
                String name = data.get("name");
                if (name == null || name.isEmpty()) continue;
                if (skills.containsKey(name)) {
                    System.err.println("[toolnexus] duplicate skill name \"" + name + "\" ("
                            + file.toAbsolutePath() + ") — keeping first");
                    continue;
                }
                skills.put(name, new SkillInfo(
                        name,
                        data.get("description"),
                        file.toAbsolutePath().toString(),
                        content));
            }
        }

        Tool tool = new SkillTool(skills);
        return new SkillSource(skills, tool);
    }

    private static final class SkillTool implements Tool {
        private final Map<String, SkillInfo> skills;
        private final Map<String, Object> inputSchema;

        SkillTool(Map<String, SkillInfo> skills) {
            this.skills = skills;
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
            Path dir = Path.of(info.location).getParent();
            String base = pathToFileUrl(dir);
            List<String> files = sampleSiblingFiles(dir, 10);

            StringBuilder filesBlock = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) filesBlock.append("\n");
                filesBlock.append("<file>").append(files.get(i)).append("</file>");
            }

            String output = String.join("\n",
                    "<skill_content name=\"" + info.name + "\">",
                    "# Skill: " + info.name,
                    "",
                    info.content.trim(),
                    "",
                    "Base directory for this skill: " + base,
                    "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
                    "Note: file list is sampled.",
                    "",
                    "<skill_files>",
                    filesBlock.toString(),
                    "</skill_files>",
                    "</skill_content>");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", info.name);
            meta.put("dir", dir.toString());
            return new ToolResult(output, false, meta);
        }
    }

    /**
     * Parse YAML frontmatter (between the leading {@code ---} fences) with a real
     * YAML parser (SnakeYAML), so folded ({@code >})/literal ({@code |}) block
     * scalars, quoting, and multi-line values all resolve correctly. Scalar values
     * are coerced to strings and trimmed. Mirrors the JS reference
     * ({@code js/src/skill.ts}). See SPEC.md §3.
     */
    static Map<String, Object> parseFrontmatter(String text) {
        Matcher m = FRONTMATTER.matcher(text);
        Map<String, String> data = new LinkedHashMap<>();
        String content;
        if (!m.find()) {
            content = text;
        } else {
            Object parsed;
            try {
                parsed = new Yaml().load(m.group(1));
            } catch (RuntimeException e) {
                // Malformed YAML must never crash discovery — fall back to empty.
                parsed = null;
            }
            if (parsed instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (key == null) continue;
                    // Only scalar values (String/Number/Boolean). Trim so block-scalar
                    // trailing newlines (chomping differs subtly between YAML libs) don't
                    // leak — keeps the five ports byte-identical.
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
        return out;
    }

    private static List<Path> walkSkillFiles(Path root) {
        List<Path> out = new ArrayList<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(root);
        // Follow symlinked directories (like opencode's `symlink: true` glob); guard
        // against symlink cycles by tracking resolved real paths already visited.
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            Path dir = stack.pop();
            try (Stream<Path> entries = Files.list(dir)) {
                List<Path> list = entries.collect(Collectors.toList());
                for (Path entry : list) {
                    String fn = entry.getFileName().toString();
                    // Files.isDirectory/isRegularFile follow symlinks by default, so a
                    // symlinked directory/file is classified by its target. A broken
                    // symlink resolves to neither, so it is skipped entirely.
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
        // Follow symlinked directories/files; guard against symlink cycles by
        // tracking resolved real paths already visited.
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
        // Node produces file:// + absolute POSIX path (already starts with /).
        return "file://" + abs;
    }
}
