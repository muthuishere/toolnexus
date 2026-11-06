using System.Text.Json.Nodes;

namespace Mcp2026;

/// <summary>The 8 cross-port conformance checks, printing PASS/FAIL/SKIP lines.</summary>
public static class Checks
{
    private sealed class SkipException : Exception;

    /// <summary>Runs the checks against url. make (optional) builds every client the checks use
    /// (e.g. TLS + OAuth for a protected server, or a stdio client).</summary>
    public static async Task<bool> RunAsync(string url, Func<McpClient>? make = null)
    {
        make ??= () => new McpClient(url);
        var ok = true;
        async Task Step(string name, Func<Task> f)
        {
            try { await f(); Console.WriteLine($"PASS {name}"); }
            catch (SkipException) { Console.WriteLine($"SKIP {name} (not applicable over stdio)"); }
            catch (Exception e) { ok = false; Console.WriteLine($"FAIL {name}: {e.Message}"); }
        }
        static async Task ExpectCode(Func<Task> f, int code)
        {
            try { await f(); }
            catch (McpRpcException e) when (e.Code == code) { return; }
            catch (Exception e) { throw new Exception($"want error {code}, got {e.Message}"); }
            throw new Exception($"want error {code}, got success");
        }
        var c = make();
        try
        {
            await Step("server/discover", async () =>
            {
                var r = await c.CallAsync("server/discover");
                if (r["capabilities"]?["extensions"] is not JsonObject ext || !ext.ContainsKey(Mcp.SkillsExt))
                    throw new Exception($"skills extension not advertised: {r["capabilities"]?.ToJsonString()}");
                if (!J.Strings(r["supportedVersions"]).Contains(Mcp.ProtocolVersion))
                    throw new Exception($"supportedVersions lacks {Mcp.ProtocolVersion}: {r["supportedVersions"]?.ToJsonString()}");
            });
            await Step("unsupported version -> -32022", async () =>
            {
                await using var bad = make();
                bad.Version = "1900-01-01";
                await ExpectCode(() => bad.CallAsync("server/discover"), Mcp.CodeUnsupportedProtocol);
            });
            await Step("header mismatch -> -32020", async () =>
            {
                await using var bad = make();
                if (bad.Stdio != null) throw new SkipException(); // stdio has no HTTP headers to mismatch
                bad.HeaderOverride = new() { ["Mcp-Name"] = "greet" };
                await ExpectCode(() => bad.CallAsync("tools/call", new JsonObject { ["name"] = "echo", ["arguments"] = new JsonObject { ["text"] = "x" } }), Mcp.CodeHeaderMismatch);
            });
            await Step("tools/list", async () =>
            {
                var r = await c.CallAsync("tools/list");
                var names = (r["tools"] as JsonArray ?? []).Select(t => J.Str(t?["name"])).ToList();
                if (!names.Contains("echo") || !names.Contains("greet")) throw new Exception("tools: " + string.Join(",", names));
                if (r["ttlMs"] == null || r["cacheScope"] == null || J.Str(r["resultType"]) != "complete")
                    throw new Exception("missing ttlMs/cacheScope/resultType: " + r.ToJsonString());
            });
            await Step("tools/call echo", async () =>
            {
                var r = await c.CallToolAsync("echo", new JsonObject { ["text"] = "hi toolnexus" });
                if (Mcp.Text(r) != "hi toolnexus") throw new Exception("got " + r.ToJsonString());
            });
            await Step("tools/call greet (MRTR input_required round trip)", async () =>
            {
                var asked = 0;
                var r = await c.CallToolAsync("greet", [], (key, req) =>
                {
                    asked++;
                    if (J.Str(req["method"]) != "elicitation/create") throw new Exception("unexpected input request " + req["method"]);
                    return Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "toolnexus" } });
                });
                if (asked != 1 || Mcp.Text(r) != "Hello, toolnexus!") throw new Exception($"asked={asked} result={r.ToJsonString()}");
            });
            const string uri = "skill://hello-world/SKILL.md";
            await Step("skills/list", async () =>
            {
                var r = await c.CallAsync("skills/list");
                if (!(r["skills"] as JsonArray ?? []).Any(s => J.Str(s?["uri"]) == uri)) throw new Exception("hello-world not listed: " + r.ToJsonString());
            });
            await Step("skills/get hello-world", async () =>
            {
                var r = await c.CallAsync("skills/get", new JsonObject { ["uri"] = uri });
                if (J.Str(r["skill"]?["frontmatter"]?["name"]) != "hello-world" || r["skill"]?["resources"] is not JsonArray { Count: > 0 })
                    throw new Exception("got " + r.ToJsonString());
            });
        }
        finally { await c.DisposeAsync(); }
        return ok;
    }
}
