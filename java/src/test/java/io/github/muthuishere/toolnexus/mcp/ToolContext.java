package io.github.muthuishere.toolnexus.mcp;

import java.util.Optional;

/** Per-call context handed to a {@link ToolFunc}: the verified caller when the server has auth. */
public record ToolContext(Principal principalOrNull) {
    public Optional<Principal> principal() { return Optional.ofNullable(principalOrNull); }
}
