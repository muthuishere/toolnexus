using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>SPEC §3 / #84: oversized descriptions are warned (code points), never modified.</summary>
public class SkillDescriptionWarningTests
{
    private static string CaseDir()
    {
        foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            var dir = new DirectoryInfo(start);
            while (dir != null)
            {
                var candidate = Path.Combine(dir.FullName, "conformance", "skill_description");
                if (Directory.Exists(candidate)) return Path.GetFullPath(candidate);
                dir = dir.Parent;
            }
        }
        throw new DirectoryNotFoundException("conformance/skill_description not found");
    }

    private static void AssertWarnings(JsonElement expect, IReadOnlyList<SkillSource.SkillWarning> got)
    {
        Assert.Equal(expect.GetArrayLength(), got.Count);
        var i = 0;
        foreach (var e in expect.EnumerateArray())
        {
            var w = got[i++];
            Assert.EndsWith(e.GetProperty("location_suffix").GetString()!, w.Location.Replace('\\', '/'));
            Assert.Equal(e.GetProperty("reason").GetString(), w.Reason);
            Assert.Equal(e.GetProperty("chars").GetInt32(), w.Chars);
        }
    }

    [Fact]
    public void OversizedDescriptionWarnsOnLoaderAndInventory()
    {
        var dir = CaseDir();
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(dir, "cases.json")));
        var cases = doc.RootElement;
        var expect = cases.GetProperty("expect_warnings");
        var skills = Path.Combine(dir, "skills");

        var src = SkillSource.LoadWith(new SkillSource.LoadOptions { Dirs = new[] { skills } });
        AssertWarnings(expect, src.Warnings);
        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { skills } });
        AssertWarnings(expect, inv.Warnings);

        var prompt = src.Prompt();
        foreach (var p in cases.GetProperty("full_descriptions").EnumerateObject())
        {
            Assert.Contains(p.Value.GetString()!, prompt);
            Assert.Equal(p.Value.GetString(), src.Skills[p.Name].Description);
        }
    }

    [Fact]
    public void WarningsFollowTheAllowlist()
    {
        var skills = Path.Combine(CaseDir(), "skills");
        var src = SkillSource.LoadWith(new SkillSource.LoadOptions
        {
            Dirs = new[] { skills },
            Filter = new Dictionary<string, bool> { ["short-desc"] = true },
        });
        Assert.Empty(src.Warnings);
    }
}
