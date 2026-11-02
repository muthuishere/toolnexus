package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Runs the hermetic {@code PromptCacheTiers} example in CI, so the README's runnable example cannot rot. */
class PromptCacheExampleTest {
    @Test
    void promptCacheTiersExampleRuns() {
        assertDoesNotThrow(() -> io.github.muthuishere.toolnexus.examples.PromptCacheTiers.main(new String[0]));
    }
}
