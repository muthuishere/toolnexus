package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void unknownSkillIsError() {
        SkillSource src = SkillSource.load(TestFixtures.skillsDir());
        ToolResult miss = src.tool().execute(Map.of("name", "nope"), null);
        assertTrue(miss.isError(), "unknown skill is an error");
        assertTrue(miss.output().contains("not found"), "message says not found");
    }
}
