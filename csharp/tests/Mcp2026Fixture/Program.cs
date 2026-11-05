// TEST SUPPORT: the MCP 2026-07-28 fixture server as a stdio child process.
using Mcp2026Fixture;

switch (args.FirstOrDefault())
{
    case "stdio-fixture":
        Console.Error.WriteLine("stdio-test-server ready {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"); // log noise, must not be parsed
        await StdioServer.ServeAsync(Server.Fixture(), Console.In, Console.Out);
        return 0;
    case "stdio-exit":
        return 3;
    case "stdio-silent": // an old server that ignores everything before initialize
        await Console.In.ReadToEndAsync();
        return 0;
    default:
        Console.Error.WriteLine("usage: Mcp2026Fixture stdio-fixture | stdio-exit | stdio-silent");
        return 2;
}
