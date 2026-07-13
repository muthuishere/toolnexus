package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the JS "toolkit: register, get, execute, duplicate-name keeps first"
 * test. Built with only a skillsDir so there is no MCP / network involvement.
 */
class ToolkitTest {

    private Toolkit build() {
        Toolkit.Options opts = new Toolkit.Options().skillsDir(TestFixtures.skillsDir());
        return Toolkit.create(opts);
    }

    @Test
    void registerGetExecuteAndDuplicateKeepsFirst() {
        try (Toolkit tk = build()) {
            tk.register(NativeTool.of("add", "", null, args -> {
                Number a = (Number) args.get("a");
                Number b = (Number) args.get("b");
                return String.valueOf(a.intValue() + b.intValue());
            }));
            // duplicate name — must be ignored (first wins).
            tk.register(NativeTool.of("add", "dup", null, args -> "SHOULD_NOT_WIN"));

            assertNotNull(tk.get("add"));
            assertNotNull(tk.get("skill"), "skill tool registered from skillsDir");

            ToolResult r = tk.execute("add", Map.of("a", 2, "b", 3));
            assertEquals("5", r.output(), "first registration wins over duplicate");
        }
    }

    @Test
    void unknownToolIsError() {
        try (Toolkit tk = build()) {
            ToolResult miss = tk.execute("ghost", Map.of());
            assertTrue(miss.isError());
            assertTrue(miss.output().contains("Unknown tool"), "message: " + miss.output());
        }
    }

    @Test
    void noMcpMeansEmptyStatus() {
        try (Toolkit tk = build()) {
            assertTrue(tk.mcpStatus().isEmpty(), "no MCP config => no network, empty status");
        }
    }

    // extend-skill-source (§3, S1/S2): the toolkit wires data skills, a lazy
    // provider (failure isolated), and the per-agent filter.
    @Test
    void skillProviderFailureIsolatedAndDataSkillsWired() {
        Toolkit.Options opts = new Toolkit.Options()
                .skillsDir(TestFixtures.skillsDir())
                .skills(java.util.List.of(new SkillSource.SkillDef("extra", "e", "e-body")))
                .skillProvider(() -> { throw new RuntimeException("boom"); })
                .builtins(false);
        try (Toolkit tk = Toolkit.create(opts)) {
            // Provider threw, but the dir skill AND the data skill still loaded.
            assertTrue(tk.skillsPrompt().contains("hello-world"), "dir skill survives provider failure");
            assertTrue(tk.skillsPrompt().contains("extra"), "data skill wired via toolkit");
        }
    }

    @Test
    void skillsFilterAppliedThroughToolkit() {
        Toolkit.Options opts = new Toolkit.Options()
                .skills(java.util.List.of(
                        new SkillSource.SkillDef("a", "A", "a"),
                        new SkillSource.SkillDef("b", "B", "b")))
                .skillsFilter(Map.of("a", true))
                .builtins(false);
        try (Toolkit tk = Toolkit.create(opts)) {
            assertTrue(tk.skillsPrompt().contains("**a**"), "allowlisted skill present");
            assertTrue(!tk.skillsPrompt().contains("**b**"), "non-allowlisted skill filtered out");
        }
    }
}
