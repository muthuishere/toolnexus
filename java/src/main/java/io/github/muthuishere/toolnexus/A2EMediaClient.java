package io.github.muthuishere.toolnexus;

import java.util.Map;

/**
 * §7G.9 — a2e.ai (image, video, speech, talk): a style client over the embedded preset
 * {@code examples/media-styles/a2e.json} (shipped verbatim in the jar), named {@code a2e}, with
 * built-in defaults: key from {@code config.key} or {@code A2E_API_KEY} at call time (no key ⇒
 * {@code unavailable}, no request), gather mode {@code download}. {@code url} replaces the style's
 * {@code baseUrl}; {@code model}, when set, is written to the image body's {@code model_type}.
 * Errors keep the style engine's texts.
 */
public final class A2EMediaClient extends StyleMediaClient {

    /** The embedded a2e preset, byte-identical to {@code examples/media-styles/a2e.json}. */
    public static String presetJson() {
        return MediaSupport.A2E_JSON;
    }

    public A2EMediaClient() {
        this(null);
    }

    public A2EMediaClient(Media.Config config) {
        super(Media.Style.parse(MediaSupport.A2E_JSON), config, "A2E_API_KEY", "download",
                config != null && config.model != null && !config.model.isEmpty()
                        ? Map.of("image", Map.of("model_type", config.model)) : Map.of());
    }
}
