package io.github.muthuishere.toolnexus;

import java.util.List;
import java.util.Map;

/**
 * The API base of the built-in code clients (§7G.9): key lookup at call time (no key ⇒
 * {@code unavailable}, no request), the auth header, base URL, loop and gather defaults. Every
 * request goes through {@link MediaSupport.Job} (§7G.8 rules and the unavailable mapping).
 */
abstract class ApiMediaClient implements MediaClient {

    final String name;
    final Media.Config config;
    final String keyEnv;
    final String defaultUrl;
    final long defaultIntervalMs;

    ApiMediaClient(String name, Media.Config config, String keyEnv, String defaultUrl, long defaultIntervalMs) {
        this.name = name;
        this.config = Media.Config.orEmpty(config);
        this.keyEnv = keyEnv;
        this.defaultUrl = defaultUrl;
        this.defaultIntervalMs = defaultIntervalMs;
    }

    @Override public String name() { return name; }

    /** The auth header for {@code key}. */
    abstract Map<String, String> authHeaders(String key);

    /** Accepted args per verb: {@code {verb: [name, required, …]}} in canonical order. */
    abstract Map<String, List<Media.ArgSpec>> argTable();

    @Override public boolean supports(String verb) {
        return argTable().containsKey(verb);
    }

    @Override public List<Media.ArgSpec> args(String verb) {
        return argTable().getOrDefault(verb, List.of());
    }

    String url() {
        return MediaSupport.trimSlash(config.url != null && !config.url.isEmpty() ? config.url : defaultUrl);
    }

    long intervalMs() {
        return config.loop != null && config.loop.intervalMs != null && config.loop.intervalMs > 0
                ? config.loop.intervalMs : defaultIntervalMs;
    }

    long timeoutMs() {
        return config.loop != null && config.loop.timeoutMs != null && config.loop.timeoutMs > 0
                ? config.loop.timeoutMs : MediaSupport.DEFAULT_TIMEOUT_MS;
    }

    /** Start a job: the key check (before any request), then headers and the deadline. */
    MediaSupport.Job job(String verb, Media.Ctx ctx) {
        String key = MediaSupport.keyOf(config, keyEnv);
        if (key == null) throw MediaSupport.noKey(name, keyEnv);
        return new MediaSupport.Job(name, verb, config, url(), authHeaders(key), ctx == null ? new Media.Ctx() : ctx,
                timeoutMs());
    }

    MediaSupport.Gather gather(String verb, MediaSupport.Job job) {
        return new MediaSupport.Gather(name, verb, MediaSupport.kindOf(verb), config, "download", job);
    }

    long maxBytes() {
        return config.gather != null && config.gather.maxBytes != null && config.gather.maxBytes > 0
                ? config.gather.maxBytes : MediaSupport.DEFAULT_MAX_BYTES;
    }

    static Map<String, Object> options(Media.Ctx ctx) {
        return ctx == null ? null : ctx.providerOptions;
    }

    static Media.ArgSpec req(String name) {
        return new Media.ArgSpec(name, true);
    }

    static Media.ArgSpec opt(String name) {
        return new Media.ArgSpec(name, false);
    }
}
