using Microsoft.Extensions.Logging.Abstractions;
using ModelContextProtocol.Server;
using Toolnexus;

// A genuine outbound stdio MCP server for the ADR-0007 regression test. It advertises
// three trivial tools (a, b, c) — each returns its own name — and serves them over the
// SDK's StdioServerTransport via the library's own McpServe. The test launches this as a
// child process and asserts tools/list + tools/call keep working AFTER connect (the v0.9.0
// Go regression killed the stdio child the moment connect returned).

var schema = new Dictionary<string, object?>
{
    ["type"] = "object",
    ["properties"] = new Dictionary<string, object?>(),
    ["additionalProperties"] = false,
};

ITool Mk(string name) => NativeTool.Of(name, name, schema, (IDictionary<string, object?> _) => name);

var tools = new List<ITool> { Mk("a"), Mk("b"), Mk("c") };

var transport = new StdioServerTransport("toolnexus-stdio-fixture", NullLoggerFactory.Instance);
var server = McpServe.BuildMcpServer(transport, tools);
await server.RunAsync();
