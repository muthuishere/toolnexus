package io.github.muthuishere.toolnexus;

/**
 * How a {@link CliClient} reaches the agent (SPEC §8C). {@link #AUTO} (the default) uses ACP when
 * the style has an ACP launch and the binary answers {@code initialize}, else the one-shot CLI;
 * {@link #ACP} and {@link #CLI} force one.
 */
public enum CliTransports {
    AUTO("auto"),
    ACP("acp"),
    CLI("cli");

    private final String wire;

    CliTransports(String wire) { this.wire = wire; }

    @Override public String toString() { return wire; }
}
