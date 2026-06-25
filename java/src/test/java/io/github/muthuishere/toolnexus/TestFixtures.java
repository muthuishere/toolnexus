package io.github.muthuishere.toolnexus;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared test helpers. Resolves the shared {@code agentskillsmcp/examples}
 * fixtures the same way {@code examples/Examples.java} does, regardless of
 * whether tests run from the repo root or from {@code java/}.
 */
final class TestFixtures {
    private TestFixtures() {}

    static String fixture(String relative) {
        Path[] candidates = {
                Path.of("examples", relative),
                Path.of("..", "examples", relative),
                Path.of(System.getProperty("user.dir"), "..", "examples", relative),
                Path.of(System.getProperty("user.dir"), "examples", relative),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) return p.toAbsolutePath().normalize().toString();
        }
        return Path.of("..", "examples", relative).toAbsolutePath().normalize().toString();
    }

    /** Absolute path to the shared skills fixtures directory. */
    static String skillsDir() {
        return fixture("skills");
    }
}
