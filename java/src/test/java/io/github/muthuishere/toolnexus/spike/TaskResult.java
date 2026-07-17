package io.github.muthuishere.toolnexus.spike;

import io.github.muthuishere.toolnexus.Request;

import java.util.List;

/**
 * SPIKE — the uniform cross-handle result (law 3: failures are data, never exceptions).
 * Mirrors {@code js/spike/runtime.ts TaskResult}. Where the JS spike attaches
 * {@code pending.path} on the open JS object, Java carries {@code pendingPath} explicitly
 * (and mirrors it into {@code pending.data.path} so it also survives the §10 wire shape).
 */
public record TaskResult(String text, boolean isError, String status, Request pending,
                         List<String> pendingPath, int turns, long totalTokens) {
    // status: "done" | "pending" | "incomplete" | "interrupted" | "closed"
}
