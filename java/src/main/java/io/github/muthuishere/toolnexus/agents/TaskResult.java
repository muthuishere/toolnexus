package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Request;

import java.util.List;

/**
 * The uniform cross-handle result (SPEC §7D "Errors: one boundary rule" — failures cross the
 * handle boundary as data, never exceptions; only the root may throw to the host).
 *
 * <p>{@code status} is the CLOSED seven-string vocabulary (SPEC §7D): {@code "done" | "pending" |
 * "incomplete" | "interrupted" | "closed" | "timeout" | "error"} — identical strings in every
 * port; a failed run is {@code "error"}, never {@code "done"} + {@code isError}.
 * When {@code status == "pending"}, {@code pending} carries the leaf's Request with the suspended
 * handle's deterministic id path stamped at {@code data.path} (§10 agent-escalation addendum) and
 * {@code pendingPath} mirrors it for direct access.
 */
public record TaskResult(String text, boolean isError, String status, Request pending,
                         List<String> pendingPath, int turns, long totalTokens) {}
