package io.github.muthuishere.toolnexus;

import java.util.Map;

/**
 * Built-in CLI presets for {@link Media#loadMediaStyle(String, Media.Config)} (ADR 0042): media
 * through a locally installed, logged-in CLI: the vendor's official CLI in its documented headless mode, signed in by the user; the provider's terms apply. The
 * string values are the cross-language contract.
 */
public final class MediaStyle {

    private MediaStyle() {
    }

    /** The {@code grok} CLI: image and video. */
    public static final String XAI_GROK_CLI = "xai-grok-cli";
    /** The {@code codex} CLI: image only. */
    public static final String OPENAI_CODEX_CLI = "openai-codex-cli";
    /** Google's Antigravity CLI {@code agy}: image only. */
    public static final String GOOGLE_AGY_CLI = "google-agy-cli";

    /**
     * The mock twins: same name, verbs, args, prompt folding and errors as the preset, but no
     * process is started and a fixed file is returned (mock kit).
     */
    public static final String XAI_GROK_CLI_MOCK = "xai-grok-cli-mock";
    public static final String OPENAI_CODEX_CLI_MOCK = "openai-codex-cli-mock";
    public static final String GOOGLE_AGY_CLI_MOCK = "google-agy-cli-mock";

    private static final Map<String, String> LABELS = Map.of(
            XAI_GROK_CLI, "grok", OPENAI_CODEX_CLI, "codex", GOOGLE_AGY_CLI, "agy",
            XAI_GROK_CLI_MOCK, "grok", OPENAI_CODEX_CLI_MOCK, "codex", GOOGLE_AGY_CLI_MOCK, "agy");

    /** Whether {@code name} is a built-in preset. */
    public static boolean isPreset(String name) {
        return name != null && LABELS.containsKey(name);
    }

    /** The client label of a preset ({@code grok}, {@code codex}, {@code agy}), or null. */
    static String label(String preset) {
        return LABELS.get(preset);
    }
}
