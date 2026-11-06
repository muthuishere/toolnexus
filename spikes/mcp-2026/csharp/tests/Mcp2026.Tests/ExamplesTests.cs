using System.Diagnostics;
using System.Text.RegularExpressions;

namespace Mcp2026.Tests;

// Runs every program under examples/ end to end and checks its exact output (identical to the Go
// port's), so the docs (which copy code from these programs) cannot rot. 01-08 are hermetic;
// 09 needs KEYCLOAK=1 + the Keycloak compose, 10 needs GITHUB_OAUTH=1 + sec-injected creds.
[Collection("port-7199")]
public class ExamplesTests
{
    public static readonly Dictionary<string, string> Hermetic = new()
    {
        ["01-connect-http"] = "supported versions: [2026-07-28]\ntools: echo, greet\necho: hello over HTTP",
        ["02-connect-stdio"] = "tool count: 2\necho: hello over stdio",
        ["03-tool-input"] = "server asks (elicitation/create): What is your name?\nresult: Hello, Ada!",
        ["04-skills"] = "skills extension: true\nskill skill://hello-world/SKILL.md: hello-world\nfirst heading: # Hello World Skill\ndownloaded: hello-world/SKILL.md\ndownloaded: hello-world/scripts/greet.sh",
        ["05-tool-search"] = "eager: 100 tools, 9721 bytes of schema\nlazy:  2 tools, 519 bytes of schema\ntop hit: slack_create\nresult: slack_create ran with hi team",
        ["06-oauth-zero-config"] = "echo: hello after login\ngreet: Hello, Ada!\nbrowser logins: 2\ntoken stored for this issuer: true",
        ["07-oauth-web-pause-resume"] = "(a) WebHandler: 200 resumed after consent\n(b) paused: authorization - true - has URL: true\n(b) resumed: resumed by hand",
        ["08-serve-protected"] = "no token: 401 | WWW-Authenticate has resource_metadata: true\nmetadata: issuer ok: true | scopes: [mcp:read]\nwhoami: hello dev-user (mcp:read)\ndelete_all after step-up: deleted\nwhoami: hello dev-user (mcp:read mcp:admin)\nverify hook: hello billing-service\nwrong key rejected: true",
    };

    public static TheoryData<string> Names => new(Hermetic.Keys);

    public static async Task<string> Run(string name, TimeSpan timeout, params (string, string)[] env)
    {
        var psi = new ProcessStartInfo("dotnet", [Paths.Dll("examples/" + name, name)]) { RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var (k, v) in env) psi.Environment[k] = v;
        using var p = Process.Start(psi)!;
        var outT = p.StandardOutput.ReadToEndAsync();
        var errT = p.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(timeout);
        try { await p.WaitForExitAsync(cts.Token); }
        catch (OperationCanceledException) { p.Kill(true); throw new Exception($"{name}: timed out"); }
        var (o, e) = (await outT, await errT);
        if (p.ExitCode != 0) throw new Exception($"run {name}: exit {p.ExitCode}\nstdout:\n{o}\nstderr:\n{e}");
        return o.Replace("\r\n", "\n").Trim();
    }

    [Theory]
    [MemberData(nameof(Names))]
    public async Task HermeticExampleOutput(string name) => Assert.Equal(Hermetic[name], await Run(name, TimeSpan.FromSeconds(60)));

    [Fact]
    public async Task ExampleKeycloak()
    {
        if (Environment.GetEnvironmentVariable("KEYCLOAK") != "1") return; // gated
        var got = await Run("09-keycloak", TimeSpan.FromSeconds(90), ("HEADLESS_LOGIN", "1"));
        Console.WriteLine(got);
        Assert.Contains("whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true", got);
        Assert.Contains("greet: Hello, Alice!", got);
    }

    [Fact]
    public async Task ExampleGitHub()
    {
        if (Environment.GetEnvironmentVariable("GITHUB_OAUTH") != "1") return; // gated: sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET
        var got = await Run("10-github", TimeSpan.FromMinutes(3), ("BROWSER", "chrome-agent"));
        Assert.Equal("tools listed: true\nget_me: complete | isError: false", got);
    }
}

// Every code block in docs/*.md is preceded by <!-- from: <path> --> and must appear verbatim in that
// file (an example ExamplesTests runs). Edit the example, then re-copy the block.
public class DocsTests
{
    [Fact]
    public void SnippetsMatchExamples()
    {
        var pages = Directory.GetFiles(Path.Combine(Paths.Root, "docs"), "*.md");
        Assert.Equal(6, pages.Length);
        var total = 0;
        foreach (var page in pages)
        {
            var b = File.ReadAllText(page);
            Assert.True(b.Contains("C# port of the MCP 2026-07-28 spike"), $"{page}: missing the spike banner");
            foreach (Match m in Regex.Matches(b, "<!-- from: (\\S+) -->\n```[a-z]*\n(.*?)\n```", RegexOptions.Singleline))
            {
                total++;
                var src = File.ReadAllText(Path.Combine(Paths.Root, m.Groups[1].Value));
                Assert.True(src.Contains(m.Groups[2].Value), $"{page}: block from {m.Groups[1].Value} no longer matches the example:\n{m.Groups[2].Value}");
            }
        }
        Assert.True(total >= 12, $"only {total} code blocks checked");
    }
}
