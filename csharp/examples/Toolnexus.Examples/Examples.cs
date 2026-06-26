namespace Toolnexus.Examples;

/// <summary>Shared helpers for the examples (resolve the shared agentskillsmcp/examples fixtures).</summary>
internal static class Examples
{
    public static string Fixture(string relative)
    {
        foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            var dir = new DirectoryInfo(start);
            while (dir != null)
            {
                var candidate = Path.Combine(dir.FullName, "examples", relative);
                if (File.Exists(candidate) || Directory.Exists(candidate))
                    return Path.GetFullPath(candidate);
                dir = dir.Parent;
            }
        }
        return Path.GetFullPath(Path.Combine("..", "examples", relative));
    }
}
