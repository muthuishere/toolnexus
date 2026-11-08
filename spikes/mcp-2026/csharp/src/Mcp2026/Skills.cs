using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Mcp2026;

/// <summary>
/// Skill URI safety (python-sdk #3685): a skill:// URI is percent-decoded ONCE, then rejected if it still
/// contains '%' (double encoding), a backslash, NUL, an absolute/empty path, or a "."/".." segment.
/// Both the server (before lookup) and the client (before writing files) use it.
/// </summary>
public static class SkillUri
{
    public static bool TryParse(string uri, out string name, out string rel, out string why)
    {
        name = rel = "";
        if (!uri.StartsWith("skill://")) { why = "not a skill:// URI"; return false; }
        if (!TryPathUnescape(uri[8..], out var dec)) { why = "bad percent-encoding"; return false; }
        if (dec.IndexOfAny(['%', '\\', '\0']) >= 0) { why = "skill URI has encoded, backslash or NUL characters"; return false; }
        var segs = dec.Split('/');
        if (segs.Length < 2) { why = "skill URI needs skill://<name>/<path>"; return false; }
        if (segs.Any(s => s is "" or "." or ".." || s.Contains(':')))
        {
            why = "skill URI path escapes the skill (empty, '.', '..' or ':' segment)";
            return false;
        }
        (name, rel, why) = (segs[0], string.Join('/', segs[1..]), "");
        return true;
    }

    // Strict like Go's url.PathUnescape: a '%' must be followed by two hex digits.
    private static bool TryPathUnescape(string s, out string result)
    {
        result = "";
        var bytes = new List<byte>(s.Length);
        for (var i = 0; i < s.Length; i++)
        {
            if (s[i] != '%') { bytes.AddRange(Encoding.UTF8.GetBytes(s[i].ToString())); continue; }
            if (i + 2 >= s.Length || !Uri.IsHexDigit(s[i + 1]) || !Uri.IsHexDigit(s[i + 2])) return false;
            bytes.Add(Convert.ToByte(s.Substring(i + 1, 2), 16));
            i += 2;
        }
        result = Encoding.UTF8.GetString(bytes.ToArray());
        return true;
    }
}

public sealed partial class McpClient
{
    /// <summary>Fetches a skill (skills/get + resources/read for every file) into dir/&lt;name&gt;/, verifying each
    /// file's sha256 digest. Nothing is written unless every resource URI is inside the skill.</summary>
    public async Task DownloadSkillAsync(string uri, string dir, CancellationToken ct = default)
    {
        if (!SkillUri.TryParse(uri, out var name, out _, out var why)) throw new McpException(why);
        var r = await CallAsync("skills/get", new JsonObject { ["uri"] = uri }, ct);
        var files = new List<(string Path, byte[] Data)>();
        foreach (var x in r["skill"]?["resources"] as JsonArray ?? [])
        {
            var fu = J.Str(x?["uri"], "");
            if (!SkillUri.TryParse(fu, out var n, out var rel, out _) || n != name || Path.IsPathRooted(rel))
                throw new McpException($"refusing skill resource \"{fu}\": outside skill {name}");
            var rr = await CallAsync("resources/read", new JsonObject { ["uri"] = fu }, ct);
            if (rr["contents"] is not JsonArray { Count: > 0 } cs) throw new McpException($"{fu}: empty resources/read");
            var data = Encoding.UTF8.GetBytes(J.Str(cs[0]?["text"], ""));
            var d = J.Str(x?["digest"], "");
            if (d != "" && d != "sha256:" + Convert.ToHexStringLower(SHA256.HashData(data))) throw new McpException($"{fu}: digest mismatch");
            files.Add((Path.Combine(dir, name, rel.Replace('/', Path.DirectorySeparatorChar)), data));
        }
        foreach (var (p, data) in files)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(p)!);
            await File.WriteAllBytesAsync(p, data, ct);
        }
    }
}
