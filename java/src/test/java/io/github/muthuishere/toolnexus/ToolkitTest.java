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
}
