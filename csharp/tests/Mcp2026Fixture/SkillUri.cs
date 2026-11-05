using System.Text;

namespace Mcp2026Fixture;

// TEST SUPPORT: ported from spikes/mcp-2026/csharp (server side of SEP-2640 URI containment).
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
