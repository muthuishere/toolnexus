package io.github.muthuishere.toolnexus.mcp;

import java.util.Optional;

/**
 * Persists OAuth tokens per MCP resource (the server URL), SPEC §2A.1a. The default is
 * {@link MemoryTokenStore} (one per load). Implementations must keep tokens confidential.
 */
public interface TokenStore {
    Optional<Token> load(String resource);

    void save(String resource, Token t);

    void delete(String resource);
}
