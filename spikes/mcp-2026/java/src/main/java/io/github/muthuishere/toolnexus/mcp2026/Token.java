package io.github.muthuishere.toolnexus.mcp2026;

import java.time.Instant;
import java.util.List;

/** A token bound to the issuer that minted it and the resource it is for. Never log it (toString is redacted). */
public record Token(String accessToken, String refreshToken, Instant expiry, List<String> scopes,
                    String issuer, String resource, String clientId) {

    public Token withExpiry(Instant e) { return new Token(accessToken, refreshToken, e, scopes, issuer, resource, clientId); }

    public Token withAccessToken(String a) { return new Token(a, refreshToken, expiry, scopes, issuer, resource, clientId); }

    boolean expired() { return expiry != null && Instant.now().isAfter(expiry.minusSeconds(10)); }

    @Override
    public String toString() { return "Token{issuer=" + issuer + ", redacted}"; }
}
