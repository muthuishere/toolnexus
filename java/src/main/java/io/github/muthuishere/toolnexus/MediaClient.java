package io.github.muthuishere.toolnexus;

import java.util.List;
import java.util.Map;

/**
 * §7G.2 — one media provider: a built-in API client ({@link OpenAIMediaClient}, …), a CLI client
 * ({@link GrokMediaClient}, {@link CodexMediaClient}), a style client
 * ({@link Media#styleMediaClient}) or your own.
 *
 * <p>Every verb is optional: the default methods report the verb as unsupported, and
 * {@link #supports(String)} is the truth. A custom client implements {@link #name()},
 * {@link #supports(String)} and the verbs it serves; {@link #args(String)} narrows the canonical
 * args it accepts (the default, {@code null}, accepts every canonical arg of the verb).
 *
 * <p>{@code args} arrive with nulls removed and already checked by {@link Media} routing. A failed
 * verb throws {@link Media.MediaException}; an account precondition (no key, no credit, no CLI)
 * throws {@link Media.MediaUnavailableException} so {@link Media} can fall through to the next
 * client.
 */
public interface MediaClient {

    /** The client name used in messages and tool metadata ({@code "openai"}, {@code "grok"}, …). */
    String name();

    /** Whether this client serves {@code verb} (one of the seven of §7G.1). */
    boolean supports(String verb);

    /**
     * The canonical args this client accepts for {@code verb}, each with its required flag.
     * {@code null} (the default) ⇒ every canonical arg of the verb, canonical required.
     */
    default List<Media.ArgSpec> args(String verb) {
        return null;
    }

    default Media.MediaResult image(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("image");
    }

    default Media.MediaResult video(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("video");
    }

    default Media.MediaResult speech(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("speech");
    }

    default Media.MediaResult music(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("music");
    }

    default Media.MediaResult transcribe(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("transcribe");
    }

    default Media.MediaResult cloneVoice(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("cloneVoice");
    }

    default Media.MediaResult talk(Map<String, Object> args, Media.Ctx ctx) {
        throw unsupported("talk");
    }

    /** Dispatch {@code verb} to its method. */
    default Media.MediaResult call(String verb, Map<String, Object> args, Media.Ctx ctx) {
        return switch (verb) {
            case "image" -> image(args, ctx);
            case "video" -> video(args, ctx);
            case "speech" -> speech(args, ctx);
            case "music" -> music(args, ctx);
            case "transcribe" -> transcribe(args, ctx);
            case "cloneVoice" -> cloneVoice(args, ctx);
            case "talk" -> talk(args, ctx);
            default -> throw unsupported(verb);
        };
    }

    private Media.MediaException unsupported(String verb) {
        return new Media.MediaException("media: " + name() + " does not support \"" + verb + "\"", "failed", null);
    }
}
