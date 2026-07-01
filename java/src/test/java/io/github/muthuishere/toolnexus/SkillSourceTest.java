package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Mirrors the JS "skills: discovery, prompt, and byte-exact skill block" test. */
class SkillSourceTest {

    @Test
    void discoveryPromptAndSkillBlock() {
        SkillSource src = SkillSource.load(TestFixtures.skillsDir());

        assertNotNull(src.skills().get("hello-world"), "hello-world discovered");

        String prompt = src.prompt();
        assertTrue(prompt.contains("## Available Skills"), "prompt has heading");
        assertTrue(prompt.contains("hello-world"), "prompt lists hello-world");

        ToolResult res = src.tool().execute(Map.of("name", "hello-world"), null);
        assertFalse(res.isError(), "loading a known skill is not an error");
        String out = res.output();
        assertTrue(out.startsWith("<skill_content name=\"hello-world\">"), "starts with skill_content tag");
        assertTrue(out.contains("# Skill: hello-world"), "contains skill heading");
        assertTrue(out.contains("Base directory for this skill: file://"), "contains base directory file url");
        assertTrue(out.contains("<skill_files>"), "contains skill_files block");
        assertTrue(out.endsWith("</skill_content>"), "ends with closing skill_content tag");
    }

    @Test
    void discoveryFollowsSymlinkedSkillDirectories() throws IOException {
        // Layout: root/ has a real skill (direct/) and a symlink (linked/) -> an
        // out-of-tree skill dir. The walker must discover both. (opencode parity)
        Path root = Files.createTempDirectory("toolnexus-skills-root");
        Path direct = root.resolve("direct");
        Files.createDirectories(direct);
        Files.writeString(direct.resolve("SKILL.md"),
                "---\nname: direct-skill\ndescription: d\n---\nbody\n");

        Path external = Files.createTempDirectory("toolnexus-skills-external");
        Path target = external.resolve("linked-target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("SKILL.md"),
                "---\nname: linked-skill\ndescription: l\n---\nbody\n");

        // Some OSes/filesystems disallow symlink creation (e.g. Windows without
        // privilege). Skip rather than fail in that case.
        try {
            Files.createSymbolicLink(root.resolve("linked"), target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlink creation not supported on this platform: " + e);
        }

        SkillSource src = SkillSource.load(root.toString());
        assertNotNull(src.skills().get("direct-skill"), "real skill discovered");
        assertNotNull(src.skills().get("linked-skill"), "symlinked skill directory discovered");
    }

    @Test
    void unknownSkillIsError() {
        SkillSource src = SkillSource.load(TestFixtures.skillsDir());
        ToolResult miss = src.tool().execute(Map.of("name", "nope"), null);
        assertTrue(miss.isError(), "unknown skill is an error");
        assertTrue(miss.output().contains("not found"), "message says not found");
    }

    // -----------------------------------------------------------------------
    // Frontmatter consensus matrix — mirrors the JS "YAML folded/literal block
    // scalar descriptions" test. Real skills (huddle, reqsume-kernel) use
    // `description: >`, so the parser MUST use a real YAML parser (SnakeYAML)
    // rather than a hand-rolled key:value split. See SPEC.md §3.
    // -----------------------------------------------------------------------

    /** Write root/<sub>/SKILL.md and return the skills root. */
    private static Path skillRoot(String sub, String skillMd) throws IOException {
        Path root = Files.createTempDirectory("toolnexus-fm-root");
        Path dir = root.resolve(sub);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd);
        return root;
    }

    @Test
    void singleLineDescriptionUnchanged() throws IOException {
        // No regression: a plain single-line description resolves verbatim.
        Path root = skillRoot("single",
                "---\nname: single-skill\ndescription: A simple one-line description.\n---\nbody\n");
        SkillSource src = SkillSource.load(root.toString());
        SkillSource.SkillInfo info = src.skills().get("single-skill");
        assertNotNull(info, "single-line skill discovered");
        assertEquals("A simple one-line description.", info.description);
    }

    @Test
    void foldedBlockScalarCollapsesNewlinesToSpaces() throws IOException {
        // folded `>`: newlines collapse to spaces — NOT the literal ">".
        Path root = skillRoot("folded",
                "---\nname: folded-skill\ndescription: >\n  Runs a repo-aware expert huddle. Trigger when the user says\n  \"start a huddle\" or \"war room\".\n---\nbody\n");
        SkillSource src = SkillSource.load(root.toString());
        SkillSource.SkillInfo info = src.skills().get("folded-skill");
        assertNotNull(info, "folded skill discovered");
        assertEquals(
                "Runs a repo-aware expert huddle. Trigger when the user says \"start a huddle\" or \"war room\".",
                info.description);
        assertNotEquals(">", info.description, "folded lines must not be dropped as \">\"");
    }

    @Test
    void literalBlockScalarPreservesNewlines() throws IOException {
        // literal `|`: newlines preserved.
        Path root = skillRoot("literal",
                "---\nname: literal-skill\ndescription: |\n  line one\n  line two\n---\nbody\n");
        SkillSource src = SkillSource.load(root.toString());
        SkillSource.SkillInfo info = src.skills().get("literal-skill");
        assertNotNull(info, "literal skill discovered");
        assertEquals("line one\nline two", info.description);
    }

    @Test
    void emptyDescriptionDoesNotCrash() throws IOException {
        // A bare `description:` (empty/null value) must not crash; name still wins.
        Path root = skillRoot("empty",
                "---\nname: empty-skill\ndescription:\n---\nbody\n");
        SkillSource src = SkillSource.load(root.toString());
        SkillSource.SkillInfo info = src.skills().get("empty-skill");
        assertNotNull(info, "skill with empty description still discovered");
        assertNull(info.description, "null YAML value is not coerced to a scalar");
    }

    @Test
    void malformedYamlDoesNotCrashDiscovery() throws IOException {
        // Malformed frontmatter must fail gracefully: empty frontmatter -> no
        // `name` -> skill skipped, discovery does not throw.
        Path root = skillRoot("bad",
                "---\nname: [unclosed\n  : : bad\n---\nbody\n");
        // Also drop a valid sibling to prove discovery keeps going.
        Path good = root.resolve("good");
        Files.createDirectories(good);
        Files.writeString(good.resolve("SKILL.md"),
                "---\nname: good-skill\ndescription: fine\n---\nbody\n");

        SkillSource src = SkillSource.load(root.toString());
        assertNotNull(src.skills().get("good-skill"), "valid skill still discovered");
    }

    @Test
    void realHuddleStyleFoldedDescription() throws IOException {
        // A real `name: huddle` + `description: >` SKILL.md, asserting the folded text.
        Path root = skillRoot("huddle",
                "---\nname: huddle\ndescription: >\n  Runs a repo-aware expert huddle for engineering decisions, planning,\n  research, verification, and spec capture. Trigger when the user says\n  \"start a huddle\" or \"assemble the team\".\n---\n# Huddle\nbody\n");
        SkillSource src = SkillSource.load(root.toString());
        SkillSource.SkillInfo info = src.skills().get("huddle");
        assertNotNull(info, "huddle skill discovered");
        assertEquals(
                "Runs a repo-aware expert huddle for engineering decisions, planning, research, verification, and spec capture. "
                        + "Trigger when the user says \"start a huddle\" or \"assemble the team\".",
                info.description);
    }
}
