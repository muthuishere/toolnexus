package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * §1B — one non-text content part: {@code text | image | file | audio}.
 *
 * <p>A flat record with a {@code type} discriminator, mirroring {@link Request} (the port's other
 * wire union) rather than a sealed hierarchy — the wire keys are pinned
 * ({@code type, text, mimeType, data, url, name}) and round-trip through the shared Jackson
 * mapper with no custom serializer.
 *
 * <ul>
 *   <li>a non-text part carries {@code mimeType} plus <b>exactly one</b> of {@code data}
 *       (standard base64, padded, no line breaks) or {@code url} — both, or neither, is a
 *       construction error;</li>
 *   <li><b>a part never holds a filesystem path.</b> A path does not survive a persisted and
 *       replayed transcript, nor the MCP / A2A process boundary. The edge constructors
 *       ({@link #ofFile}, {@link #ofBytes}, {@link #ofUrl}, {@link #ofStream}) normalise at
 *       construction so the path, the stream or the raw bytes never enter the part. They accept
 *       broadly — a {@link Path}, a {@link java.io.File}, an {@link InputStream}, bytes, a
 *       {@code data:} URL — and store narrowly: {@code mimeType} + base64 {@code data}.</li>
 * </ul>
 *
 * <p>The mime type of a path comes from the <b>fixed</b> extension table below (§6 {@code read}),
 * never from magic bytes and never from a platform mime database — {@code /etc/mime.types} varies
 * per machine and would break cross-port parity.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(String type, String text, String mimeType, String data,
                          String url, String name) {

    /** The four part types (§1B). */
    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String FILE = "file";
    public static final String AUDIO = "audio";

    /**
     * The fixed media extension table (§6 {@code read} / §1B edge constructors) — shared with
     * every port. {@code ext -> {mimeType, partType}}. No sniffing, no platform mime database.
     */
    private static final Map<String, String[]> MEDIA = Map.of(
            "png", new String[]{"image/png", IMAGE},
            "jpg", new String[]{"image/jpeg", IMAGE},
            "jpeg", new String[]{"image/jpeg", IMAGE},
            "gif", new String[]{"image/gif", IMAGE},
            "webp", new String[]{"image/webp", IMAGE},
            "pdf", new String[]{"application/pdf", FILE},
            "mp3", new String[]{"audio/mpeg", AUDIO},
            "wav", new String[]{"audio/wav", AUDIO});

    /**
     * Edge limit in <b>decoded</b> bytes (not the base64 string, which is +33%), enforced by the
     * edge constructors. {@code 0} (the default) means unlimited. This is a process-wide setting
     * because the edge constructors are static; {@link LlmClient.Options#maxPartBytes} enforces
     * the same limit again at request assembly, for parts that never passed an edge constructor
     * (an MCP server's, say).
     */
    private static volatile long maxPartBytes = 0;

    /** Set the process-wide edge limit in decoded bytes; {@code 0} = unlimited. */
    public static void setMaxPartBytes(long bytes) {
        maxPartBytes = Math.max(0, bytes);
    }

    /** The current process-wide edge limit in decoded bytes; {@code 0} = unlimited. */
    public static long maxPartBytes() {
        return maxPartBytes;
    }

    /** Canonical, validating construction. Prefer the named factories. */
    public ContentPart {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("ContentPart: type is required");
        }
        if (TEXT.equals(type)) {
            if (data != null || url != null) {
                throw new IllegalArgumentException("ContentPart: a text part carries neither data nor url");
            }
        } else {
            if (!IMAGE.equals(type) && !FILE.equals(type) && !AUDIO.equals(type)) {
                throw new IllegalArgumentException("ContentPart: unknown part type \"" + type
                        + "\" (expected text, image, file or audio)");
            }
            if (mimeType == null || mimeType.isEmpty()) {
                throw new IllegalArgumentException("ContentPart: a " + type + " part requires a mimeType");
            }
            boolean hasData = data != null && !data.isEmpty();
            boolean hasUrl = url != null && !url.isEmpty();
            if (hasData && hasUrl) {
                throw new IllegalArgumentException(
                        "ContentPart: a " + type + " part carries exactly one of data or url, not both");
            }
            if (!hasData && !hasUrl) {
                throw new IllegalArgumentException(
                        "ContentPart: a " + type + " part carries exactly one of data or url, got neither");
            }
        }
    }

    // ------------------------------------------------------------------
    // Edge constructors — a path / bytes / a data: URL / an https: URL all
    // normalise into a path-free, bytes-or-url part.
    // ------------------------------------------------------------------

    /** A text part. */
    public static ContentPart text(String text) {
        return new ContentPart(TEXT, text == null ? "" : text, null, null, null, null);
    }

    /**
     * Read {@code path} now, base64 it now; the mime type and part type come from the fixed
     * extension table. The part carries no path.
     *
     * <p>Throws {@link UncheckedIOException} on an unreadable file — forcing a try/catch around a
     * literal argument is hostile, and {@code Files.lines}/{@code Files.walk} set the precedent.
     * Use {@link #ofFileChecked} when a checked exception is wanted. An extension outside the
     * table (with no explicit mime type) is an {@link IllegalArgumentException} naming it — never
     * a silent {@code application/octet-stream}.
     */
    public static ContentPart ofFile(Path path) {
        return ofFile(path, null);
    }

    /** {@link #ofFile(Path)} with an explicit mime type, for an extension outside the table. */
    public static ContentPart ofFile(Path path, String mimeType) {
        try {
            return ofFileChecked(path, mimeType);
        } catch (IOException e) {
            throw new UncheckedIOException("ContentPart.ofFile: " + path, e);
        }
    }

    /** {@link #ofFile(Path)} for callers that want the checked {@link IOException}. */
    public static ContentPart ofFileChecked(Path path) throws IOException {
        return ofFileChecked(path, null);
    }

    /** {@link #ofFile(Path, String)} for callers that want the checked {@link IOException}. */
    public static ContentPart ofFileChecked(Path path, String mimeType) throws IOException {
        if (path == null) throw new IllegalArgumentException("ContentPart.ofFile: path is required");
        String mime = mimeType;
        String type;
        if (mime == null || mime.isEmpty()) {
            String[] hit = mediaFor(path.getFileName() == null ? path.toString() : path.getFileName().toString());
            if (hit == null) {
                throw new IllegalArgumentException("ContentPart.ofFile: unknown extension \""
                        + extensionOf(path.toString()) + "\" for " + path
                        + " — pass an explicit mimeType (mime is never sniffed from content)");
            }
            mime = hit[0];
            type = hit[1];
        } else {
            type = typeForMime(mime);
        }
        return ofBytes(type, mime, Files.readAllBytes(path));
    }

    // ------------------------------------------------------------------
    // The file and stream objects a Java caller already holds (§1B: "a port
    // accepts the file and byte objects its users already hold"). Accept
    // broadly, store narrowly — whatever comes in, the part holds only
    // `mimeType` + base64 `data`, never a File, a stream or a path.
    // ------------------------------------------------------------------

    /**
     * {@link #ofFile(Path)} for the {@link java.io.File} most Java callers actually hold —
     * delegates to the {@code Path} logic via {@link java.io.File#toPath()}.
     */
    public static ContentPart ofFile(java.io.File file) {
        return ofFile(file, null);
    }

    /** {@link #ofFile(java.io.File)} with an explicit mime type, for an extension outside the table. */
    public static ContentPart ofFile(java.io.File file, String mimeType) {
        if (file == null) throw new IllegalArgumentException("ContentPart.ofFile: file is required");
        return ofFile(file.toPath(), mimeType);
    }

    /** {@link #ofFile(java.io.File)} for callers that want the checked {@link IOException}. */
    public static ContentPart ofFileChecked(java.io.File file) throws IOException {
        return ofFileChecked(file, null);
    }

    /** {@link #ofFile(java.io.File, String)} for callers that want the checked {@link IOException}. */
    public static ContentPart ofFileChecked(java.io.File file, String mimeType) throws IOException {
        if (file == null) throw new IllegalArgumentException("ContentPart.ofFile: file is required");
        return ofFileChecked(file.toPath(), mimeType);
    }

    /**
     * Read {@code in} <b>fully, now</b>, and base64 it now; the part carries the encoding, never
     * the stream. A part must never hold an unread stream — a half-read stream would not survive
     * a persisted transcript any better than a path does.
     *
     * <p><b>The stream is not closed.</b> It is the caller's; toolnexus reads it and leaves
     * closing to whoever opened it (so a {@code try}-with-resources around the call still works,
     * and a shared stream is not yanked out from under its owner).
     *
     * <p>Throws {@link UncheckedIOException} on a read failure, matching {@link #ofFile(Path)};
     * use {@link #ofStreamChecked} for the checked {@link IOException}.
     */
    public static ContentPart ofStream(InputStream in, String mimeType) {
        try {
            return ofStreamChecked(in, mimeType);
        } catch (IOException e) {
            throw new UncheckedIOException("ContentPart.ofStream", e);
        }
    }

    /**
     * {@link #ofStream(InputStream, String)} for a stream that has a filename: when
     * {@code mimeType} is {@code null} or empty, the mime and part type come from the fixed
     * extension table (§6 {@code read}), exactly as they do for a path. An extension outside the
     * table with no explicit mime is an {@link IllegalArgumentException} naming it. {@code name}
     * also becomes a {@code file} part's display name.
     */
    public static ContentPart ofStream(InputStream in, String name, String mimeType) {
        try {
            return ofStreamChecked(in, name, mimeType);
        } catch (IOException e) {
            throw new UncheckedIOException("ContentPart.ofStream: " + name, e);
        }
    }

    /** {@link #ofStream(InputStream, String)} for callers that want the checked {@link IOException}. */
    public static ContentPart ofStreamChecked(InputStream in, String mimeType) throws IOException {
        if (in == null) throw new IllegalArgumentException("ContentPart.ofStream: stream is required");
        if (mimeType == null || mimeType.isEmpty()) {
            throw new IllegalArgumentException("ContentPart.ofStream: a stream has no extension to "
                    + "read a mime type from — pass an explicit mimeType, or a name "
                    + "(mime is never sniffed from content)");
        }
        return ofBytes(typeForMime(mimeType), mimeType, in.readAllBytes());
    }

    /** {@link #ofStream(InputStream, String, String)} for callers that want the checked {@link IOException}. */
    public static ContentPart ofStreamChecked(InputStream in, String name, String mimeType) throws IOException {
        if (in == null) throw new IllegalArgumentException("ContentPart.ofStream: stream is required");
        String mime = mimeType;
        String type;
        if (mime == null || mime.isEmpty()) {
            String[] hit = mediaFor(name);
            if (hit == null) {
                throw new IllegalArgumentException("ContentPart.ofStream: unknown extension \""
                        + extensionOf(name) + "\" for " + name
                        + " — pass an explicit mimeType (mime is never sniffed from content)");
            }
            mime = hit[0];
            type = hit[1];
        } else {
            type = typeForMime(mime);
        }
        ContentPart p = ofBytes(type, mime, in.readAllBytes());
        return FILE.equals(type) && name != null && !name.isEmpty() ? p.withName(name) : p;
    }

    /** Base64 {@code bytes} now; the part carries the encoding, never the array. */
    public static ContentPart ofBytes(String type, String mimeType, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("ContentPart.ofBytes: bytes are required");
        checkSize(bytes.length);
        return new ContentPart(type, null, mimeType, Base64.getEncoder().encodeToString(bytes), null, null);
    }

    /** An {@code image} part from raw bytes. */
    public static ContentPart image(String mimeType, byte[] bytes) {
        return ofBytes(IMAGE, mimeType, bytes);
    }

    /** An {@code audio} part from raw bytes. */
    public static ContentPart audio(String mimeType, byte[] bytes) {
        return ofBytes(AUDIO, mimeType, bytes);
    }

    /** A {@code file} part from raw bytes, with an optional display name. */
    public static ContentPart file(String mimeType, byte[] bytes, String name) {
        ContentPart p = ofBytes(FILE, mimeType, bytes);
        return name == null || name.isEmpty() ? p : p.withName(name);
    }

    /** A part from an already-base64'd payload (an MCP server's, say) — taken verbatim. */
    public static ContentPart ofBase64(String type, String mimeType, String base64) {
        if (base64 != null) checkSize(decodedLength(base64));
        return new ContentPart(type, null, mimeType, base64, null, null);
    }

    /**
     * A {@code data:<mime>;base64,<b64>} URL is parsed into {@code {mimeType, data}} at
     * construction — never stored as a {@code url}, so two spellings of the same bytes cannot
     * diverge downstream. Any other URL (an {@code https:} one) is kept as {@code url}, and then
     * {@code mimeType} is required.
     */
    public static ContentPart ofUrl(String type, String url) {
        return ofUrl(type, null, url);
    }

    /** {@link #ofUrl(String, String)} with an explicit mime type (required for a non-data URL). */
    public static ContentPart ofUrl(String type, String mimeType, String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("ContentPart.ofUrl: url is required");
        }
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            int semi = url.indexOf(';');
            if (comma < 0 || semi < 0 || semi > comma
                    || !url.regionMatches(true, semi, ";base64,", 0, ";base64,".length())) {
                throw new IllegalArgumentException(
                        "ContentPart.ofUrl: only data:<mime>;base64,<b64> data URLs are supported");
            }
            String mime = url.substring("data:".length(), semi);
            String b64 = url.substring(comma + 1);
            checkSize(decodedLength(b64));
            return new ContentPart(type, null, mime, b64, null, null);
        }
        return new ContentPart(type, null, mimeType, null, url, null);
    }

    /** A copy carrying {@code name} (the {@code file} part's display name). */
    public ContentPart withName(String newName) {
        return new ContentPart(type, text, mimeType, data, url, newName);
    }

    // ------------------------------------------------------------------
    // Rendering for logs / §9 events — {type, mimeType, bytes}. `data` NEVER.
    // ------------------------------------------------------------------

    /** Decoded byte length of {@code data}, or {@code 0} for a url/text part. */
    public long bytes() {
        return data == null ? 0 : decodedLength(data);
    }

    /** How a part appears in a log line or a §9 event: {@code {type, mimeType, bytes}}, never {@code data}. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("mimeType", mimeType);
        m.put("bytes", bytes());
        return m;
    }

    /** {@link #describe()} over a list; {@code null} when there is nothing to describe. */
    public static List<Map<String, Object>> describeAll(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) return null;
        List<Map<String, Object>> out = new java.util.ArrayList<>(parts.size());
        for (ContentPart p : parts) out.add(p.describe());
        return out;
    }

    /** Never prints {@code data} — part bytes are user content and stay out of every log line. */
    @Override
    public String toString() {
        return "ContentPart{type=" + type + ", mimeType=" + mimeType + ", bytes=" + bytes()
                + (url != null ? ", url=" + url : "") + "}";
    }

    // ------------------------------------------------------------------
    // Media table helpers (shared with the `read` builtin, §6)
    // ------------------------------------------------------------------

    /** {@code {mimeType, partType}} for a filename's extension, or {@code null} when unlisted. */
    static String[] mediaFor(String filename) {
        String ext = extensionOf(filename);
        return ext.isEmpty() ? null : MEDIA.get(ext);
    }

    static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The part type an explicit mime type implies: {@code image/*}, {@code audio/*}, else file. */
    static String typeForMime(String mimeType) {
        String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) return IMAGE;
        if (m.startsWith("audio/")) return AUDIO;
        return FILE;
    }

    /** Decoded length of a standard base64 string, without decoding it. */
    static long decodedLength(String base64) {
        if (base64 == null || base64.isEmpty()) return 0;
        int len = base64.length();
        int pad = 0;
        if (base64.charAt(len - 1) == '=') pad++;
        if (len > 1 && base64.charAt(len - 2) == '=') pad++;
        return (long) len * 3 / 4 - pad;
    }

    private static void checkSize(long decodedBytes) {
        long limit = maxPartBytes;
        if (limit > 0 && decodedBytes > limit) {
            throw new IllegalArgumentException("ContentPart: part is " + decodedBytes
                    + " decoded bytes, over the maxPartBytes limit of " + limit);
        }
    }
}
