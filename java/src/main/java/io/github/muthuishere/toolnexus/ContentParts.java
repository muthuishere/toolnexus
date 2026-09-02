package io.github.muthuishere.toolnexus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * §8A — content-part emission: one {@link ContentPart} to one provider block.
 *
 * <p>Emission lives here (called from {@link LlmClient}'s message assembly and from
 * {@link Translate}), <b>not</b> in {@link Adapters}, which is tool-<i>schema</i> only in every
 * port. Two client styles exist: {@code "openai"} (Chat Completions) and {@code "anthropic"};
 * Gemini request emission is out of scope — no port has a Gemini request path.
 *
 * <p><b>Positive allowlist.</b> For each {@code (style, part.type)} there is either a defined block
 * shape or an explicit refusal, and the encoded block is asserted against the allowlist
 * <i>before</i> the request goes out. This is normative: an unknown block type sent upstream
 * returns HTTP 200 with the content silently discarded, not an error — map-and-hope reproduces
 * the exact bug this layer exists to remove.
 */
public final class ContentParts {

    private ContentParts() {}

    /** Block {@code type}s the OpenAI Chat Completions content array accepts from us. */
    private static final Set<String> OPENAI_ALLOWED = Set.of("text", "image_url", "file", "input_audio");
    /** Block {@code type}s the Anthropic messages content array accepts from us. */
    private static final Set<String> ANTHROPIC_ALLOWED = Set.of("text", "image", "document");

    /** True when {@code style} is the Anthropic wire shape. */
    static boolean isAnthropic(String style) {
        return "anthropic".equals(style);
    }

    /**
     * Encode one part for {@code style}, or return {@code null} when the style defines no shape
     * for it (an explicit refusal: {@code anthropic} × {@code audio}, {@code openai} × a
     * {@code file} carrying a URL). A block that is not on the style's allowlist is treated as a
     * refusal too, so a part that produced no allowlisted block can never reach the wire.
     */
    public static Map<String, Object> toBlock(String style, ContentPart p) {
        Map<String, Object> block = isAnthropic(style) ? anthropicBlock(p) : openAIBlock(p);
        if (block == null) return null;
        Set<String> allowed = isAnthropic(style) ? ANTHROPIC_ALLOWED : OPENAI_ALLOWED;
        return allowed.contains(String.valueOf(block.get("type"))) ? block : null;
    }

    private static Map<String, Object> openAIBlock(ContentPart p) {
        switch (p.type()) {
            case ContentPart.TEXT -> {
                return textBlock(p.text());
            }
            case ContentPart.IMAGE -> {
                Map<String, Object> img = new LinkedHashMap<>();
                img.put("url", p.data() != null ? dataUrl(p) : p.url());
                return block("image_url", "image_url", img);
            }
            case ContentPart.FILE -> {
                // Chat Completions has no URL form for `file`, and `file_data` REQUIRES the
                // `data:<mime>;base64,` prefix — a bare base64 string is a 400.
                if (p.data() == null) return null;
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("filename", p.name() != null && !p.name().isEmpty() ? p.name() : "file");
                file.put("file_data", dataUrl(p));
                return block("file", "file", file);
            }
            case ContentPart.AUDIO -> {
                if (p.data() == null) return null; // no URL form for input_audio
                Map<String, Object> audio = new LinkedHashMap<>();
                audio.put("data", p.data());
                audio.put("format", audioFormat(p.mimeType()));
                return block("input_audio", "input_audio", audio);
            }
            default -> {
                return null;
            }
        }
    }

    private static Map<String, Object> anthropicBlock(ContentPart p) {
        switch (p.type()) {
            case ContentPart.TEXT -> {
                return textBlock(p.text());
            }
            case ContentPart.IMAGE -> {
                return block("image", "source", source(p));
            }
            case ContentPart.FILE -> {
                return block("document", "source", source(p));
            }
            case ContentPart.AUDIO -> {
                return null; // Anthropic defines no audio block — a named refusal (§8A).
            }
            default -> {
                return null;
            }
        }
    }

    private static Map<String, Object> source(ContentPart p) {
        Map<String, Object> src = new LinkedHashMap<>();
        if (p.data() != null) {
            src.put("type", "base64");
            src.put("media_type", p.mimeType());
            src.put("data", p.data());
        } else {
            src.put("type", "url");
            src.put("url", p.url());
        }
        return src;
    }

    /** {@code {type:"text", text}} — the one block shape both styles share. */
    public static Map<String, Object> textBlock(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", text == null ? "" : text);
        return m;
    }

    private static Map<String, Object> block(String type, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put(key, value);
        return m;
    }

    private static String dataUrl(ContentPart p) {
        return "data:" + p.mimeType() + ";base64," + p.data();
    }

    /** OpenAI's {@code input_audio.format}: {@code mp3}/{@code wav}, else the mime subtype. */
    static String audioFormat(String mimeType) {
        String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (m.equals("audio/mpeg") || m.equals("audio/mp3")) return "mp3";
        if (m.equals("audio/wav") || m.equals("audio/x-wav") || m.equals("audio/wave")) return "wav";
        int slash = m.indexOf('/');
        return slash >= 0 ? m.substring(slash + 1) : m;
    }

    /**
     * The text a part is replaced by when the style cannot represent it and the run is allowed to
     * degrade (a tool/MCP-derived part, §8A "unsupported parts are handled by provenance").
     * Byte-identical across all seven ports (SPEC §1B): {@code [unsupported <type> part
     * (<mimeType>, <bytes> bytes)]}.
     */
    public static String placeholder(ContentPart p) {
        return "[unsupported " + p.type() + " part (" + p.mimeType() + ", " + p.bytes() + " bytes)]";
    }

    /** Human-readable {@code output} for a result that carried only non-text parts (§2). */
    static String describeForOutput(List<ContentPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (ContentPart p : parts) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(describe(p));
        }
        return sb.toString();
    }

    /**
     * A part described in text (SPEC §1B): {@code <type> (<mimeType>, <bytes> bytes)}, byte-
     * identical across all seven ports.
     */
    static String describe(ContentPart p) {
        return p.type() + " (" + p.mimeType() + ", " + p.bytes() + " bytes)";
    }
}
