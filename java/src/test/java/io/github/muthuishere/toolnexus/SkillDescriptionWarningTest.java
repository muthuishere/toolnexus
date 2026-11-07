package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC.md §3 / #84: oversized descriptions are warned (code points), never modified. */
class SkillDescriptionWarningTest {

    private static Path caseDir() {
        Path[] candidates = {
                Path.of("..", "conformance", "skill_description"),
                Path.of("conformance", "skill_description"),
        };
        for (Path p : candidates) if (Files.exists(p)) return p.toAbsolutePath().normalize();
        throw new IllegalStateException("conformance/skill_description not found");
    }

    private static void assertWarnings(JsonNode expect, List<SkillSource.SkillWarning> got) {
        assertEquals(expect.size(), got.size(), "warnings: " + got);
        for (int i = 0; i < expect.size(); i++) {
            JsonNode e = expect.get(i);
            SkillSource.SkillWarning w = got.get(i);
            assertTrue(w.location().replace('\\', '/').endsWith(e.get("location_suffix").asText()), w.location());
            assertEquals(e.get("reason").asText(), w.reason());
            assertEquals(e.get("chars").asInt(), w.chars());
        }
    }

    @Test
    void oversizedDescriptionWarnsOnLoaderAndInventory() throws Exception {
        Path dir = caseDir();
        JsonNode cases = new ObjectMapper().readTree(dir.resolve("cases.json").toFile());
        JsonNode expect = cases.get("expect_warnings");
        String skills = dir.resolve("skills").toString();

        SkillSource src = SkillSource.loadWith(new SkillSource.LoadOptions().dirs(skills));
        assertWarnings(expect, src.warnings());
        SkillSource.SkillInventory inv = SkillSource.listSkills(new SkillSource.LoadOptions().dirs(skills));
        assertWarnings(expect, inv.warnings);

        String prompt = src.prompt();
        Iterator<Map.Entry<String, JsonNode>> it = cases.get("full_descriptions").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            assertTrue(prompt.contains(e.getValue().asText()), "description modified: " + e.getKey());
            assertEquals(e.getValue().asText(), src.skills().get(e.getKey()).description);
        }
    }

    @Test
    void warningsFollowTheAllowlist() {
        String skills = caseDir().resolve("skills").toString();
        SkillSource src = SkillSource.loadWith(new SkillSource.LoadOptions().dirs(skills)
                .filter(Map.of("short-desc", true)));
        assertEquals(List.of(), src.warnings());
    }
}
