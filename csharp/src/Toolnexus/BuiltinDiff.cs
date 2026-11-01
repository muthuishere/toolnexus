using System.Text;

namespace Toolnexus;

/// <summary>
/// SPEC §4 "Edit diffs": the structured change record <c>edit</c>, <c>write</c> and
/// <c>apply_patch</c> attach as <c>metadata.files</c> + <c>metadata.diff</c>. Counts come from a
/// minimal line diff (LCS), so they agree across ports; the diff text is a valid unified diff but
/// is not pinned byte-for-byte.
/// </summary>
public static partial class BuiltinTools
{
    private const int DiffContext = 3;

    /// <summary>A file's change: one <c>metadata.files</c> entry plus its unified-diff section.</summary>
    private sealed record FileChange(string Path, string Status, long Additions, long Deletions, string Diff)
    {
        public Dictionary<string, object?> Entry() => new()
        {
            ["path"] = Path,
            ["status"] = Status,
            ["additions"] = Additions,
            ["deletions"] = Deletions,
        };
    }

    /// <summary>The display path: relative to <c>baseDir</c> when set, else as the caller gave it.</summary>
    private static string DiffPath(BuiltinEnv env, string given, string full)
    {
        if (env.BaseDir.Length == 0) return given.Replace('\\', '/');
        return System.IO.Path.GetRelativePath(System.IO.Path.GetFullPath(env.BaseDir), System.IO.Path.GetFullPath(full))
            .Replace('\\', '/');
    }

    /// <summary>Attach <c>files</c> and <c>diff</c> to a result's metadata.</summary>
    private static void AddDiffMeta(Dictionary<string, object?> meta, IEnumerable<FileChange> changes)
    {
        var list = changes.ToList();
        meta["files"] = list.Select(c => (object?)c.Entry()).ToList();
        meta["diff"] = string.Concat(list.Select(c => c.Diff));
    }

    /// <summary>Lines of a file body; a trailing newline terminates the last line, it is not an extra empty one.</summary>
    private static string[] DiffLines(string? text)
    {
        if (string.IsNullOrEmpty(text)) return Array.Empty<string>();
        var lines = text.Replace("\r\n", "\n").Split('\n');
        return text.EndsWith('\n') ? lines[..^1] : lines;
    }

    /// <summary>Diff <paramref name="before"/> (null = file did not exist) against <paramref name="after"/> (null = deleted).</summary>
    private static FileChange ComputeChange(string path, string? before, string? after)
    {
        var status = before == null ? "added" : after == null ? "deleted" : "modified";
        var a = DiffLines(before);
        var b = DiffLines(after);
        var ops = LineOps(a, b); // (' '|'-'|'+', line)
        long adds = ops.Count(o => o.Op == '+'), dels = ops.Count(o => o.Op == '-');
        var sb = new StringBuilder();
        if (adds + dels > 0)
        {
            sb.Append("--- ").Append(before == null ? "/dev/null" : "a/" + path).Append('\n');
            sb.Append("+++ ").Append(after == null ? "/dev/null" : "b/" + path).Append('\n');
            AppendHunks(sb, ops);
        }
        return new FileChange(path, status, adds, dels, sb.ToString());
    }

    private static List<(char Op, string Line)> LineOps(string[] a, string[] b)
    {
        // Trim the common prefix/suffix, then LCS the middle (a minimal diff).
        int pre = 0;
        while (pre < a.Length && pre < b.Length && a[pre] == b[pre]) pre++;
        int suf = 0;
        while (suf < a.Length - pre && suf < b.Length - pre && a[a.Length - 1 - suf] == b[b.Length - 1 - suf]) suf++;
        var ops = new List<(char, string)>();
        for (var i = 0; i < pre; i++) ops.Add((' ', a[i]));
        int n = a.Length - pre - suf, m = b.Length - pre - suf;
        if ((long)n * m > 16_000_000L)
        {
            // Too large for the quadratic table: report the middle as a full replacement.
            for (var i = 0; i < n; i++) ops.Add(('-', a[pre + i]));
            for (var j = 0; j < m; j++) ops.Add(('+', b[pre + j]));
        }
        else
        {
            var lcs = new int[n + 1, m + 1];
            for (var i = n - 1; i >= 0; i--)
                for (var j = m - 1; j >= 0; j--)
                    lcs[i, j] = a[pre + i] == b[pre + j] ? lcs[i + 1, j + 1] + 1 : Math.Max(lcs[i + 1, j], lcs[i, j + 1]);
            int x = 0, y = 0;
            while (x < n || y < m)
            {
                if (x < n && y < m && a[pre + x] == b[pre + y]) { ops.Add((' ', a[pre + x])); x++; y++; }
                else if (y < m && (x == n || lcs[x, y + 1] >= lcs[x + 1, y])) { ops.Add(('+', b[pre + y])); y++; }
                else { ops.Add(('-', a[pre + x])); x++; }
            }
        }
        for (var i = a.Length - suf; i < a.Length; i++) ops.Add((' ', a[i]));
        return ops;
    }

    private static void AppendHunks(StringBuilder sb, List<(char Op, string Line)> ops)
    {
        var i = 0;
        while (i < ops.Count)
        {
            while (i < ops.Count && ops[i].Op == ' ') i++;
            if (i >= ops.Count) break;
            var start = Math.Max(0, i - DiffContext);
            // Extend the hunk while changes are within 2*context of each other.
            var end = i;
            var k = i;
            while (k < ops.Count)
            {
                if (ops[k].Op != ' ') { end = k; k++; continue; }
                var run = k;
                while (run < ops.Count && ops[run].Op == ' ') run++;
                if (run >= ops.Count || run - k > 2 * DiffContext) break;
                k = run;
            }
            var stop = Math.Min(ops.Count, end + 1 + DiffContext);
            // Line numbers (1-based) at the hunk start.
            int oldLine = 1, newLine = 1;
            for (var j = 0; j < start; j++)
            {
                if (ops[j].Op != '+') oldLine++;
                if (ops[j].Op != '-') newLine++;
            }
            int oldCount = 0, newCount = 0;
            for (var j = start; j < stop; j++)
            {
                if (ops[j].Op != '+') oldCount++;
                if (ops[j].Op != '-') newCount++;
            }
            sb.Append("@@ -").Append(oldCount == 0 ? oldLine - 1 : oldLine).Append(',').Append(oldCount)
              .Append(" +").Append(newCount == 0 ? newLine - 1 : newLine).Append(',').Append(newCount).Append(" @@\n");
            for (var j = start; j < stop; j++) sb.Append(ops[j].Op).Append(ops[j].Line).Append('\n');
            i = stop;
        }
    }
}
