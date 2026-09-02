using System.Text.Json.Serialization;

namespace Toolnexus;

/// <summary>
/// §1B ContentPart — the one non-text channel, shared by every port. A part is
/// <c>text | image | file | audio</c>; a non-text part carries a <c>mimeType</c> plus
/// <b>exactly one</b> of <c>data</c> (standard base64, padded, no line breaks) or <c>url</c>.
///
/// <para><b>A part never holds a filesystem path.</b> A path does not survive a persisted and
/// replayed transcript, nor the MCP / A2A process boundary — so the edge constructors
/// (<see cref="FromFile(string,string?,long?)"/>, <see cref="FromBytes(byte[],string,string?,long?)"/>,
/// <see cref="FromUrl"/>) read and base64 the bytes at construction time.</para>
///
/// <para><b>Accept broadly, store narrowly.</b> The edge takes the objects a .NET caller already
/// holds — a path <c>string</c>, a <see cref="FileInfo"/>, a <see cref="Stream"/>,
/// <c>byte[]</c>/<see cref="ReadOnlySpan{T}"/>/<see cref="ReadOnlyMemory{T}"/>, a <c>data:</c> URL
/// or an <c>https:</c> URL — and what lands in the part is always just a <c>mimeType</c> and
/// base64 <c>data</c>. A stream is consumed eagerly and is <b>not</b> disposed: it stays the
/// caller's to close.</para>
///
/// <para>Wire keys are pinned lower-camel, exactly as <see cref="Request"/> (§10) pins its own —
/// this data crosses languages and processes unchanged.</para>
/// </summary>
public sealed record ContentPart
{
    /// <summary>"text" | "image" | "file" | "audio".</summary>
    [JsonPropertyName("type")]
    public string Type { get; init; } = "";

    /// <summary>Present only on a <c>text</c> part.</summary>
    [JsonPropertyName("text")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Text { get; init; }

    /// <summary>Spelled <c>mimeType</c> in every port and on the wire — never <c>media_type</c>.</summary>
    [JsonPropertyName("mimeType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? MimeType { get; init; }

    /// <summary>Standard base64 (RFC 4648 §4), padded, no line breaks. Never logged.</summary>
    [JsonPropertyName("data")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Data { get; init; }

    /// <summary>An <c>https:</c> URL. A <c>data:</c> URL is normalised into <c>mimeType</c>+<c>data</c>.</summary>
    [JsonPropertyName("url")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Url { get; init; }

    /// <summary>Optional filename for a <c>file</c> part (the provider's <c>filename</c>).</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; init; }

    // ------------------------------------------------------------------ media table (§6)

    /// <summary>
    /// The fixed media extension table (SPEC §6) — identical in every port. Mime types are
    /// <b>never</b> sniffed from content and <b>never</b> resolved through a platform mime
    /// database (<c>/etc/mime.types</c> varies per machine and would break cross-port parity).
    /// </summary>
    public static readonly IReadOnlyDictionary<string, (string MimeType, string Type)> MediaTable =
        new Dictionary<string, (string, string)>(StringComparer.OrdinalIgnoreCase)
        {
            ["png"] = ("image/png", "image"),
            ["jpg"] = ("image/jpeg", "image"),
            ["jpeg"] = ("image/jpeg", "image"),
            ["gif"] = ("image/gif", "image"),
            ["webp"] = ("image/webp", "image"),
            ["pdf"] = ("application/pdf", "file"),
            ["mp3"] = ("audio/mpeg", "audio"),
            ["wav"] = ("audio/wav", "audio"),
        };

    /// <summary>Look up a path's extension in <see cref="MediaTable"/>; null when unknown.</summary>
    public static (string MimeType, string Type)? MediaFor(string path)
    {
        var ext = System.IO.Path.GetExtension(path ?? "");
        if (ext.Length < 2) return null;
        return MediaTable.TryGetValue(ext[1..], out var hit) ? hit : null;
    }

    /// <summary>The part type a mime type implies: <c>image/*</c>, <c>audio/*</c>, else <c>file</c>.</summary>
    public static string TypeForMime(string mimeType)
    {
        if (mimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase)) return "image";
        if (mimeType.StartsWith("audio/", StringComparison.OrdinalIgnoreCase)) return "audio";
        return "file";
    }

    // ------------------------------------------------------------------ construction

    /// <summary>
    /// Thrown by the edge constructors for a malformed part: both/neither of
    /// <c>data</c>/<c>url</c>, an unknown extension, or a part over <c>maxPartBytes</c>.
    /// Never carries the part's bytes.
    /// </summary>
    public sealed class InvalidPartException : Exception
    {
        public InvalidPartException(string message) : base(message) { }
    }

    /// <summary>A <c>text</c> part.</summary>
    public static ContentPart FromText(string text) => new() { Type = "text", Text = text };

    /// <summary>Implicit lift so a collection expression may mix strings and parts:
    /// <c>client.RunAsync(["what's in this?", ContentPart.FromFile("shot.png")], toolkit)</c>.</summary>
    public static implicit operator ContentPart(string text) => FromText(text);

    /// <summary>
    /// Read <paramref name="path"/> now and base64 it now — the path never enters the part.
    /// <paramref name="mimeType"/> defaults to the §6 media table; an unknown extension with no
    /// explicit mime type is a typed error naming the extension.
    /// </summary>
    public static ContentPart FromFile(string path, string? mimeType = null, long? maxPartBytes = null)
        => FromBytes(File.ReadAllBytes(path), ResolveMime(path, mimeType),
            System.IO.Path.GetFileName(path), maxPartBytes);

    /// <summary>Async sibling of <see cref="FromFile(string,string?,long?)"/>.</summary>
    public static async Task<ContentPart> FromFileAsync(string path, string? mimeType = null,
        long? maxPartBytes = null, CancellationToken cancellationToken = default)
    {
        var mime = ResolveMime(path, mimeType);
        var bytes = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
        return FromBytes(bytes, mime, System.IO.Path.GetFileName(path), maxPartBytes);
    }

    /// <summary>
    /// Read <paramref name="file"/> now and base64 it now — the <see cref="FileInfo"/> handle
    /// never enters the part. The mime type defaults to the §6 media table, keyed on
    /// <see cref="FileSystemInfo.Extension"/>.
    /// </summary>
    public static ContentPart FromFile(FileInfo file, string? mimeType = null, long? maxPartBytes = null)
    {
        if (file == null) throw new InvalidPartException("content part: file is required");
        return FromFile(file.FullName, mimeType, maxPartBytes);
    }

    /// <summary>Async sibling of <see cref="FromFile(FileInfo,string?,long?)"/>.</summary>
    public static Task<ContentPart> FromFileAsync(FileInfo file, string? mimeType = null,
        long? maxPartBytes = null, CancellationToken cancellationToken = default)
    {
        if (file == null) throw new InvalidPartException("content part: file is required");
        return FromFileAsync(file.FullName, mimeType, maxPartBytes, cancellationToken);
    }

    /// <summary>
    /// Read <paramref name="stream"/> to the end <b>now</b> and base64 it now — a part never holds
    /// an unread stream, which would not survive a persisted transcript any better than a path.
    ///
    /// <para>Pass <paramref name="mimeType"/> explicitly, or a <paramref name="name"/> whose
    /// extension the §6 media table recognises; neither is a typed error.</para>
    ///
    /// <para><b>The stream is NOT disposed.</b> It belongs to the caller, who is free to keep
    /// reading it or wrap it in their own <c>using</c>. It is read forward from its current
    /// position and never seeked, so a non-seekable stream (a pipe, a network stream) works.</para>
    /// </summary>
    public static ContentPart FromStream(Stream stream, string? mimeType = null, string? name = null,
        long? maxPartBytes = null)
    {
        var mime = ResolveStreamMime(stream, mimeType, name);
        PreflightMax(stream, maxPartBytes);
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return FromBytes(buffer.GetBuffer().AsSpan(0, (int)buffer.Length), mime, name, maxPartBytes);
    }

    /// <summary>Async sibling of <see cref="FromStream(Stream,string?,string?,long?)"/>. Same
    /// contract: read eagerly, read forward, and never dispose the caller's stream.</summary>
    public static async Task<ContentPart> FromStreamAsync(Stream stream, string? mimeType = null,
        string? name = null, long? maxPartBytes = null, CancellationToken cancellationToken = default)
    {
        var mime = ResolveStreamMime(stream, mimeType, name);
        PreflightMax(stream, maxPartBytes);
        using var buffer = new MemoryStream();
        await stream.CopyToAsync(buffer, cancellationToken).ConfigureAwait(false);
        return FromBytes(buffer.GetBuffer().AsSpan(0, (int)buffer.Length), mime, name, maxPartBytes);
    }

    /// <summary>Base64 native bytes now. <paramref name="mimeType"/> is required.</summary>
    public static ContentPart FromBytes(byte[] bytes, string mimeType, string? name = null,
        long? maxPartBytes = null)
    {
        if (bytes == null) throw new InvalidPartException("content part: bytes are required");
        return FromBytes(bytes.AsSpan(), mimeType, name, maxPartBytes);
    }

    /// <summary>
    /// Span sibling of <see cref="FromBytes(byte[],string,string?,long?)"/>, for a caller who
    /// already holds a slice, a pooled buffer or a stack-allocated span and should not have to
    /// copy it into a fresh array first.
    /// </summary>
    public static ContentPart FromBytes(ReadOnlySpan<byte> bytes, string mimeType, string? name = null,
        long? maxPartBytes = null)
    {
        if (string.IsNullOrEmpty(mimeType)) throw new InvalidPartException("content part: mimeType is required for bytes");
        EnforceMax(bytes.Length, maxPartBytes);
        return new ContentPart
        {
            Type = TypeForMime(mimeType),
            MimeType = mimeType,
            Data = Convert.ToBase64String(bytes),
            Name = name,
        };
    }

    /// <summary>Memory sibling of <see cref="FromBytes(byte[],string,string?,long?)"/>.</summary>
    public static ContentPart FromBytes(ReadOnlyMemory<byte> bytes, string mimeType, string? name = null,
        long? maxPartBytes = null)
        => FromBytes(bytes.Span, mimeType, name, maxPartBytes);

    /// <summary>
    /// A URL part. A <c>data:&lt;mime&gt;;base64,&lt;b64&gt;</c> URL is parsed into
    /// <c>{mimeType, data}</c> at construction, so two spellings of the same bytes never diverge
    /// downstream; an <c>https:</c> URL is kept as <c>url</c>.
    /// </summary>
    public static ContentPart FromUrl(string url, string? mimeType = null, string? name = null,
        long? maxPartBytes = null)
    {
        if (string.IsNullOrEmpty(url)) throw new InvalidPartException("content part: url is required");
        if (url.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
        {
            var comma = url.IndexOf(',');
            if (comma < 0) throw new InvalidPartException("content part: malformed data: URL");
            var header = url[5..comma];
            var isB64 = header.EndsWith(";base64", StringComparison.OrdinalIgnoreCase);
            var mime = (isB64 ? header[..^7] : header).Trim();
            if (mime.Length == 0) mime = mimeType ?? "text/plain";
            var payload = url[(comma + 1)..];
            byte[] bytes;
            try { bytes = isB64 ? Convert.FromBase64String(payload) : System.Text.Encoding.UTF8.GetBytes(Uri.UnescapeDataString(payload)); }
            catch (FormatException) { throw new InvalidPartException("content part: data: URL payload is not valid base64"); }
            return FromBytes(bytes, mime, name, maxPartBytes);
        }
        var explicitMime = mimeType ?? MediaFor(url)?.MimeType
            ?? throw new InvalidPartException(
                $"content part: no mimeType for url \"{Truncate(url)}\" — pass one explicitly");
        return new ContentPart { Type = TypeForMime(explicitMime), MimeType = explicitMime, Url = url, Name = name };
    }

    /// <summary>
    /// Validate the §1B invariant: a non-text part carries a <c>mimeType</c> and exactly one of
    /// <c>data</c>/<c>url</c>. Both, or neither, is a typed error.
    /// </summary>
    public ContentPart Validate()
    {
        if (Type == "text")
        {
            if (Text == null) throw new InvalidPartException("content part: a text part requires text");
            return this;
        }
        if (Type is not ("image" or "file" or "audio"))
            throw new InvalidPartException($"content part: unknown type \"{Type}\"");
        if (string.IsNullOrEmpty(MimeType))
            throw new InvalidPartException($"content part: a {Type} part requires a mimeType");
        var hasData = !string.IsNullOrEmpty(Data);
        var hasUrl = !string.IsNullOrEmpty(Url);
        if (hasData && hasUrl)
            throw new InvalidPartException($"content part: a {Type} part carries both data and url — exactly one is allowed");
        if (!hasData && !hasUrl)
            throw new InvalidPartException($"content part: a {Type} part carries neither data nor url — exactly one is required");
        return this;
    }

    // ------------------------------------------------------------------ observability

    /// <summary>Decoded byte length of <c>data</c> (0 for a url/text part). Base64 is +33%.</summary>
    public long ByteLength => string.IsNullOrEmpty(Data) ? 0 : Data!.Length / 4L * 3 - Padding(Data!);

    /// <summary>
    /// Byte-derived token charge, so a 5 MB image is not free to the compactor. Never derived
    /// from the <c>mimeType</c> string, which would score that image at ~3 tokens and make it
    /// uncompactable.
    /// </summary>
    public int EstimatedTokens => Type == "text"
        ? ((Text?.Length ?? 0) + 3) / 4
        : (int)Math.Max(85, ByteLength / 750);

    /// <summary>
    /// How a part renders in every log line, error message and §9 event:
    /// <c>{type, mimeType, bytes}</c>. The part's <c>data</c> is NEVER logged.
    /// </summary>
    public string Describe() => Type == "text"
        ? $"{{type:text, chars:{Text?.Length ?? 0}}}"
        : $"{{type:{Type}, mimeType:{MimeType}, bytes:{(Data != null ? ByteLength : 0)}}}";

    public override string ToString() => Describe();

    /// <summary>
    /// The canonical §8A one-liner used where a part must be named in user-visible text but never
    /// rendered — an image-only tool result, a placeholder for an unsupported part:
    /// <c>"&lt;type&gt; (&lt;mimeType&gt;, &lt;bytes&gt; bytes)"</c>. <c>bytes</c> is the DECODED
    /// byte count; a part carrying <c>url</c> instead of <c>data</c> renders it as 0.
    /// </summary>
    public string DescribeInText() => $"{Type} ({MimeType}, {ByteLength} bytes)";

    /// <summary>
    /// The canonical §8A placeholder text for a part a client style could not represent:
    /// <c>"[unsupported &lt;type&gt; part (&lt;mimeType&gt;, &lt;bytes&gt; bytes)]"</c>. Same
    /// zero-for-url rule as <see cref="DescribeInText"/>.
    /// </summary>
    public string UnsupportedPlaceholderText() => $"[unsupported {Type} part ({MimeType}, {ByteLength} bytes)]";

    // ------------------------------------------------------------------ internals

    private static int Padding(string b64)
    {
        var n = 0;
        for (var i = b64.Length - 1; i >= 0 && b64[i] == '='; i--) n++;
        return n;
    }

    private static string ResolveMime(string path, string? mimeType)
    {
        if (!string.IsNullOrEmpty(mimeType)) return mimeType!;
        var hit = MediaFor(path);
        if (hit != null) return hit.Value.MimeType;
        var ext = System.IO.Path.GetExtension(path ?? "");
        throw new InvalidPartException(
            $"content part: unknown extension \"{(ext.Length > 0 ? ext : "(none)")}\" — pass an explicit mimeType");
    }

    private static string ResolveStreamMime(Stream stream, string? mimeType, string? name)
    {
        if (stream == null) throw new InvalidPartException("content part: stream is required");
        if (!stream.CanRead) throw new InvalidPartException("content part: stream is not readable");
        if (!string.IsNullOrEmpty(mimeType)) return mimeType!;
        if (!string.IsNullOrEmpty(name))
        {
            var hit = MediaFor(name!);
            if (hit != null) return hit.Value.MimeType;
            var ext = System.IO.Path.GetExtension(name!);
            throw new InvalidPartException(
                $"content part: unknown extension \"{(ext.Length > 0 ? ext : "(none)")}\" — pass an explicit mimeType");
        }
        throw new InvalidPartException(
            "content part: mimeType is required for a stream — pass one, or a name whose extension the media table knows");
    }

    /// <summary>Fast-fail on an over-size stream we can measure without reading it. The §1B
    /// guarantee stays the assembly check; this is only the better message.</summary>
    private static void PreflightMax(Stream stream, long? maxPartBytes)
    {
        if (maxPartBytes is not > 0 || !stream.CanSeek) return;
        var remaining = stream.Length - stream.Position;
        if (remaining > maxPartBytes.Value)
            throw new InvalidPartException(
                $"content part: {remaining} decoded bytes exceeds maxPartBytes {maxPartBytes.Value}");
    }

    private static void EnforceMax(long bytes, long? maxPartBytes)
    {
        if (maxPartBytes is > 0 && bytes > maxPartBytes.Value)
            throw new InvalidPartException(
                $"content part: {bytes} decoded bytes exceeds maxPartBytes {maxPartBytes.Value}");
    }

    private static string Truncate(string s) => s.Length <= 64 ? s : s[..64] + "…";
}
