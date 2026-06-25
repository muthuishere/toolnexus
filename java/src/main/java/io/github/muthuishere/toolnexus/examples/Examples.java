package io.github.muthuishere.toolnexus.examples;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared helpers for the examples (resolve the shared ../examples fixtures). */
final class Examples {
    private Examples() {}

    /**
     * Resolve a path under the shared {@code agentskillsmcp/examples/} fixtures dir,
     * regardless of whether the example is launched from the repo root or from
     * {@code java/}.
     */
    static String fixture(String relative) {
        // Candidate roots: ./examples, ../examples (when cwd is java/).
        Path[] candidates = {
                Path.of("examples", relative),
                Path.of("..", "examples", relative),
                Path.of(System.getProperty("user.dir"), "..", "examples", relative),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) return p.toAbsolutePath().normalize().toString();
        }
        // Default to ../examples relative to cwd (java/).
        return Path.of("..", "examples", relative).toAbsolutePath().normalize().toString();
    }
}
