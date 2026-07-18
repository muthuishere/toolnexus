package io.github.muthuishere.toolnexus.agents;

/**
 * One unsolicited-rail item with provenance (SPEC §7D "Two delivery rails").
 * {@code from} = the sending handle's path or {@code "external"};
 * {@code channel} = {@code "peer" | "timer" | "external" | ...}. Items from non-ancestor or
 * external senders are rendered inside an explicitly untrusted block when drained.
 */
public record InboxItem(String from, String channel, String text) {}
