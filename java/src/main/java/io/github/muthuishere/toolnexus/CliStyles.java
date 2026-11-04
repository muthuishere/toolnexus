package io.github.muthuishere.toolnexus;

/**
 * The closed set of agent-CLI families a {@link CliClient} can drive (SPEC §8C, ADR 0037). Each is
 * a shape — argv, prompt mode, answer source, ACP launch — never a binary: the command is always
 * the caller's. There is deliberately no lookup from a string; {@link #fileName()} is output only.
 */
public enum CliStyles {
    CLAUDE("claude"),
    CODEX("codex"),
    COPILOT("copilot"),
    OPENCODE("opencode"),
    DEVIN("devin"),
    GEMINI("gemini"),
    GOOSE("goose"),
    /** A caller-described CLI: {@code args}, {@code prompt} and {@code answer} come from the options. */
    CUSTOM("custom");

    private final String fileName;

    CliStyles(String fileName) { this.fileName = fileName; }

    /** The style's shared file name ({@code conformance/cli-styles/<name>.json}). Never accepted as input. */
    public String fileName() { return fileName; }

    @Override public String toString() { return fileName; }
}
