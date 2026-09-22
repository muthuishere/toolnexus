package io.github.muthuishere.toolnexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host boundary (ADR 0034, issues #100/#101/#102): which interpreter runs,
 * what a relative path means, and what a timeout kills.
 *
 * <p>Every assertion carries its control. The spike that produced these fixes
 * twice reported a clean kill from a broken probe, so "no orphan" is evidence
 * only next to a run proving the command can write the marker at all
 * (spikes/builtin-host-boundary/SPIKE.md §1).
 */
class BuiltinHostBoundaryTest {

    private static Tool tool(Object cfg, String name) {
        for (Tool t : BuiltinTools.create(cfg)) {
            if (t.name().equals(name)) return t;
        }
        throw new AssertionError("builtin " + name + " not found");
    }

    private static ToolResult run(Tool t, Map<String, Object> args) {
        return t.execute(args, null);
    }

    /**
     * The GRANDCHILD writes the marker, and {@code sleep 0.2} in front stops the
     * shell exec-optimising the single command away — the difference between
     * measuring an orphan and measuring nothing.
     */
    private static String orphanCommand(Path marker) {
        return "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'";
    }

    // -----------------------------------------------------------------------
    // #102 — a timeout kills the job, not the shell
    // -----------------------------------------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutKillsTheWholeJob(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("orphan.marker");
        ToolResult res = run(tool(null, "bash"),
                Map.of("command", orphanCommand(marker), "timeout", 300));

        assertTrue(res.isError(), "a timed-out command must be an error result");
        assertTrue(res.output().contains("timed out"), res.output());
        assertEquals(Boolean.TRUE, res.metadata().get("timedOut"));
        assertEquals(Boolean.TRUE, res.metadata().get("killedTree"));

        Thread.sleep(2000);
        assertFalse(Files.exists(marker), "the grandchild outlived the kill");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void controlTheProbeCommandCanWriteTheMarker(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("control.marker");
        ToolResult res = run(tool(null, "bash"),
                Map.of("command", orphanCommand(marker), "timeout", 20_000));
        assertFalse(res.isError(), res.output());
        assertTrue(Files.exists(marker),
                "the command cannot write the marker at all — the orphan test proves nothing");
    }

    /**
     * The 2000 ms window bounds how long the KILL may take, not how long the
     * CALLER waits. Two ports used to sleep through it whether or not the job had
     * already died, turning a 300 ms timeout into a 2.3 s call
     * (spikes/builtin-host-boundary/stress/STRESS.md §3).
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aTimeoutReturnsAsSoonAsTheJobIsGone(@TempDir Path dir) {
        Path marker = dir.resolve("grace.marker");
        long started = System.currentTimeMillis();
        ToolResult res = run(tool(null, "bash"),
                Map.of("command", orphanCommand(marker), "timeout", 300));
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(res.isError());
        assertTrue(elapsed < 1500,
                "a 300ms timeout took " + elapsed + "ms — the caller waited out the grace window");
    }

    // -----------------------------------------------------------------------
    // #100 — the interpreter is chosen, and reported
    // -----------------------------------------------------------------------

    @Test
    void theResolvedInterpreterIsReported() {
        ToolResult res = run(tool(null, "bash"), Map.of("command", "echo hi"));
        assertNotNull(res.metadata().get("shell"), "metadata.shell must name the interpreter that ran");
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("win")) {
            assertEquals("/bin/sh -c", res.metadata().get("shell"));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aHostSuppliedShellIsUsedVerbatim() {
        ToolResult res = run(tool(Map.of("shell", List.of("/bin/sh", "-c")), "bash"),
                Map.of("command", "echo verbatim"));
        assertFalse(res.isError(), res.output());
        assertTrue(res.output().contains("verbatim"));
        assertEquals("/bin/sh -c", res.metadata().get("shell"));
    }

    @Test
    void disablingBashMakesAnInterpreterUnnecessary() {
        List<Tool> tools = BuiltinTools.select(Map.of("tools", Map.of("bash", false)));
        assertTrue(tools.stream().noneMatch(t -> t.name().equals("bash")));
        assertFalse(BuiltinTools.shell(null).isEmpty());
    }

    // -----------------------------------------------------------------------
    // #101 — one base directory, and optional confinement
    // -----------------------------------------------------------------------

    @Test
    void baseDirScopesRelativePaths(@TempDir Path base) throws IOException {
        Object cfg = Map.of("baseDir", base.toString());
        ToolResult res = run(tool(cfg, "write"), Map.of("path", "sub/nested.txt", "content", "landed"));
        assertFalse(res.isError(), res.output());
        assertTrue(Files.exists(base.resolve("sub/nested.txt")));
        assertFalse(Files.exists(Path.of(System.getProperty("user.dir"), "sub", "nested.txt")),
                "relative write leaked into the process working directory");

        ToolResult read = run(tool(cfg, "read"), Map.of("path", "sub/nested.txt"));
        assertEquals("landed", read.output());
    }

    @Test
    void anAbsolutePathWithNoBaseDirBehavesAsBefore(@TempDir Path dir) {
        Path target = dir.resolve("absolute.txt");
        ToolResult res = run(tool(null, "write"), Map.of("path", target.toString(), "content", "x"));
        assertFalse(res.isError(), res.output());
        assertTrue(Files.exists(target));
    }

    @Test
    void applyPatchResolvesPathsInsideThePatchText(@TempDir Path base) {
        String patch = "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch";
        ToolResult res = run(tool(Map.of("baseDir", base.toString()), "apply_patch"),
                Map.of("patchText", patch));
        assertFalse(res.isError(), res.output());
        assertTrue(Files.exists(base.resolve("pkg/new.txt")),
                "the path inside the patch text was not resolved");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashDefaultsItsWorkdirToBaseDir(@TempDir Path base) throws IOException {
        Files.writeString(base.resolve("marker.txt"), "x");
        ToolResult res = run(tool(Map.of("baseDir", base.toString()), "bash"),
                Map.of("command", "ls marker.txt"));
        assertFalse(res.isError(), res.output());
        assertTrue(res.output().contains("marker.txt"));
    }

    @Test
    void confinementRefusesEscapesAndStillServesWhatIsInside(@TempDir Path dir) throws IOException {
        Path base = Files.createDirectory(dir.resolve("base"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Tool read = tool(Map.of("baseDir", base.toString(), "confineToBaseDir", true), "read");

        for (String p : List.of("../outside/secret.txt", outside.resolve("secret.txt").toString())) {
            ToolResult res = run(read, Map.of("path", p));
            assertTrue(res.isError(), p + " should be refused");
            assertTrue(res.output().contains("outside baseDir"), res.output());
        }

        Files.writeString(base.resolve("ok.txt"), "fine");
        assertFalse(run(read, Map.of("path", "ok.txt")).isError(),
                "a path inside baseDir must still be read");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void confinementFollowsSymlinksBeforeDeciding(@TempDir Path dir) throws IOException {
        Path base = Files.createDirectory(dir.resolve("base"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(base.resolve("link"), outside);

        ToolResult res = run(tool(Map.of("baseDir", base.toString(), "confineToBaseDir", true), "read"),
                Map.of("path", "link/secret.txt"));
        assertTrue(res.isError(), "a symlink out of baseDir must be refused");
    }

    @Test
    void confinementCoversPathsThatDoNotExistYet(@TempDir Path base) {
        Tool write = tool(Map.of("baseDir", base.toString(), "confineToBaseDir", true), "write");
        assertTrue(run(write, Map.of("path", "../escape.txt", "content", "x")).isError());
        assertFalse(run(write, Map.of("path", "deep/new/file.txt", "content", "x")).isError());
    }

    // -----------------------------------------------------------------------
    // §4A — grep emits the string it sorted by
    // -----------------------------------------------------------------------

    @Test
    void grepEmitsWalkRootRelativeForwardSlashedPaths(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("tree/sub"));
        Files.writeString(base.resolve("tree/sub/a.txt"), "needle\n");

        ToolResult res = run(tool(Map.of("baseDir", base.toString()), "grep"),
                Map.of("pattern", "needle", "path", "tree"));
        assertFalse(res.isError(), res.output());
        assertEquals("sub/a.txt:1:needle", res.output());
        assertFalse(res.output().contains("\\"), "§4A requires `/` on every platform");
    }
}
