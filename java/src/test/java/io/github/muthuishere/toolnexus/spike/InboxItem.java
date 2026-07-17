package io.github.muthuishere.toolnexus.spike;

/**
 * SPIKE — one unsolicited-rail item with provenance.
 * {@code from} = handle path or {@code "external"}; {@code channel} = "peer" | "timer" | "external" | ...
 */
public record InboxItem(String from, String channel, String text) {}
