package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mirrors the JS "sanitize replaces non [a-zA-Z0-9_-]" test. */
class ToolSanitizeTest {
    @Test
    void sanitizeReplacesNonAllowedChars() {
        assertEquals("a_b_c_d_e", Tool.sanitize("a b/c.d:e"));
        assertEquals("keep-_OK1", Tool.sanitize("keep-_OK1"));
    }

    @Test
    void sanitizeNullIsEmpty() {
        assertEquals("", Tool.sanitize(null));
    }
}
