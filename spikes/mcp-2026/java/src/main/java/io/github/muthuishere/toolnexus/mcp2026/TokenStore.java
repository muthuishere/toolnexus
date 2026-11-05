package io.github.muthuishere.toolnexus.mcp2026;

import java.util.Optional;

/** Persists tokens per resource. Implementations must keep them confidential. */
public interface TokenStore {
    Optional<Token> load(String resource);

    void save(String resource, Token t);

    void delete(String resource);
}
