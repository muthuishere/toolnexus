using System.Text.Json;
using ModelContextProtocol.Protocol;

namespace Toolnexus.Tests;

public class McpSourceTests
{
    private static Dictionary<string, object?> ServersBlock() => new()
    {
        ["foo"] = new Dictionary<string, object?> { ["command"] = new List<object?> { "x" } },
    };

    [Fact]
    public void ParseConfigAcceptsMcpServersServersMcpAndRaw()
    {
        var s = ServersBlock();

        Assert.True(McpSource.ParseConfig(new Dictionary<string, object?> { ["mcpServers"] = s }).ContainsKey("foo"));
        Assert.True(McpSource.ParseConfig(new Dictionary<string, object?> { ["servers"] = s }).ContainsKey("foo"));
        Assert.True(McpSource.ParseConfig(new Dictionary<string, object?> { ["mcp"] = s }).ContainsKey("foo"));
        // raw (no wrapper) — the dictionary IS the servers map.
        Assert.True(McpSource.ParseConfig(s).ContainsKey("foo"));
    }

    [Fact]
    public void ParseConfigAcceptsRawJsonString()
    {
        const string json = "{\"mcpServers\":{\"foo\":{\"command\":[\"x\"]}}}";
        var parsed = McpSource.ParseConfig(json);
        Assert.True(parsed.ContainsKey("foo"));
    }

    [Fact]
    public void ExpandEnvHeadersExpandsViaLookupStub()
    {
        var headers = new Dictionary<string, string>
        {
            ["Authorization"] = "Bearer ${TN_TEST_TOKEN}",
            ["X"] = "plain",
        };
        var env = new Dictionary<string, string> { ["TN_TEST_TOKEN"] = "secret123" };
        var output = McpSource.ExpandEnvHeaders(headers, k => env.GetValueOrDefault(k))!;

        Assert.Equal("Bearer secret123", output["Authorization"]);
        Assert.Equal("plain", output["X"]);
    }

    [Fact]
    public void ExpandEnvHeadersMissingVarBecomesEmpty()
    {
        var headers = new Dictionary<string, string> { ["Authorization"] = "Bearer ${NOT_SET_VAR}" };
        var output = McpSource.ExpandEnvHeaders(headers, _ => null)!;
        Assert.Equal("Bearer ", output["Authorization"]);
    }

    [Fact]
    public void ExpandEnvHeadersNullInNullOut()
    {
        Assert.Null(McpSource.ExpandEnvHeaders(null));
        Assert.Null(McpSource.ExpandEnvHeaders(null, _ => "x"));
    }

    [Fact]
    public void ExpandEnvHeadersDefaultUsesEnvironment()
    {
        var path = Environment.GetEnvironmentVariable("PATH");
        Assert.False(string.IsNullOrEmpty(path));
        var headers = new Dictionary<string, string> { ["X-Path"] = "p=${PATH}" };
        var output = McpSource.ExpandEnvHeaders(headers)!;
        Assert.Equal("p=" + path, output["X-Path"]);
    }

    // §10 MCP elicitation bridge: form→input (carries data.schema), url→authorization (carries url);
    // Answer→ElicitResult (ok→accept, declined→decline, else→cancel). Byte-parity with the JS mapping.
    // The generated `elc-...` Id is deliberately NOT asserted.
    [Fact]
    public void ElicitationBridgeMapsFormAndUrlAndAcceptDeclineCancel()
    {
        var schema = new ElicitRequestParams.RequestSchema
        {
            Properties = new Dictionary<string, ElicitRequestParams.PrimitiveSchemaDefinition>
            {
                ["name"] = new ElicitRequestParams.StringSchema(),
            },
            Required = new List<string> { "name" },
        };

        // form mode → kind:"input" carrying the schema in data.schema
        var req = McpSource.ElicitationToRequest(new ElicitRequestParams { Message = "Your name?", RequestedSchema = schema });
        Assert.Equal("input", req.Kind);
        Assert.Equal("Your name?", req.Prompt);
        Assert.Same(schema, req.Data!["schema"]);
        Assert.Null(req.Url);

        // URL mode → kind:"authorization" carrying the url, no schema
        var ureq = McpSource.ElicitationToRequest(new ElicitRequestParams { Mode = "url", Message = "Log in", Url = "https://x/auth" });
        Assert.Equal("authorization", ureq.Kind);
        Assert.Equal("https://x/auth", ureq.Url);
        Assert.Null(ureq.Data);

        // Answer → ElicitResult
        var accept = McpSource.AnswerToElicitResult(new Answer { Id = "1", Ok = true, Data = new Dictionary<string, object?> { ["name"] = "Ada" } });
        Assert.Equal("accept", accept.Action);
        Assert.Equal("Ada", accept.Content!["name"].GetString());

        var acceptEmpty = McpSource.AnswerToElicitResult(new Answer { Id = "1", Ok = true });
        Assert.Equal("accept", acceptEmpty.Action);
        Assert.Empty(acceptEmpty.Content!);

        Assert.Equal("decline", McpSource.AnswerToElicitResult(new Answer { Id = "1", Ok = false, Reason = "declined" }).Action);
        Assert.Equal("cancel", McpSource.AnswerToElicitResult(new Answer { Id = "1", Ok = false }).Action);
        Assert.Equal("cancel", McpSource.AnswerToElicitResult(new Answer { Id = "1", Ok = false, Reason = "expired" }).Action);
    }
}
