package io.github.muthuishere.toolnexus.mcp;

import java.time.Instant;
import java.util.List;

/**
 * An OAuth token for one MCP server (SPEC §2A.1a), bound to the issuer that minted it and the client id
 * it was minted for. {@code refreshToken} and {@code expiresAt} may be null. Never log it: its
 * {@link #toString()} never shows a token value.
 */
public record Token(String accessToken, String refreshToken, Instant expiresAt, List<String> scopes,
                    String issuer, String clientId) {

    /** True when the token is expired or expires within 10 s (refresh skew, §2A.1 step 8). */
    public boolean expired() { return expiresAt != null && Instant.now().isAfter(expiresAt.minusSeconds(10)); }

    /** A copy with a different expiry (tests force a refresh with it). */
    public Token withExpiresAt(Instant e) { return new Token(accessToken, refreshToken, e, scopes, issuer, clientId); }

    @Override
    public String toString() { return "Token{issuer=" + issuer + ", clientId=" + clientId + ", scopes=" + scopes + ", redacted}"; }
}
