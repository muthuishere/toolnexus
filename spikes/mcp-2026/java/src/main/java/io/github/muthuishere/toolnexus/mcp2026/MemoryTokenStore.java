package io.github.muthuishere.toolnexus.mcp2026;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The default in-memory {@link TokenStore}. */
public final class MemoryTokenStore implements TokenStore {
    private final ConcurrentHashMap<String, Token> m = new ConcurrentHashMap<>();

    @Override
    public Optional<Token> load(String resource) { return Optional.ofNullable(m.get(resource)); }

    @Override
    public void save(String resource, Token t) { m.put(resource, t); }

    @Override
    public void delete(String resource) { m.remove(resource); }
}
