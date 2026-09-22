// Runner: print the C# port's skill inventory as JSON for the issue-93 harness.
using Toolnexus;

static string Esc(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");

var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = args });
var skills = string.Join(",", inv.Skills.Select(s => $"{{\"location\":\"{Esc(s.Location)}\"}}"));
var skipped = string.Join(",", inv.Skipped.Select(s => $"{{\"location\":\"{Esc(s.Location)}\",\"reason\":\"{Esc(s.Reason)}\"}}"));
Console.WriteLine($"{{\"skills\":[{skills}],\"skipped\":[{skipped}]}}");
