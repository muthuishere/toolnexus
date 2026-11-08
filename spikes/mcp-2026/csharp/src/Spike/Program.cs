// The MCP 2026-07-28 spike CLI: run the fixture server, or the cross-port checks against any server.
using Mcp2026;

switch (args.FirstOrDefault())
{
    case "server":
        var port = int.Parse(Environment.GetEnvironmentVariable("PORT") is { Length: > 0 } p ? p : "7105");
        var host = await HttpHost.StartAsync(Server.Fixture(), port: port);
        Console.WriteLine($"listening on {host.Url}/mcp");
        await host.WaitAsync();
        return 0;
    case "client":
        return await Checks.RunAsync(args.Length > 1 ? args[1] : "http://127.0.0.1:7105/mcp") ? 0 : 1;
    case "selftest":
        await using (var self = await HttpHost.StartAsync(Server.Fixture()))
            return await Checks.RunAsync(self.Url + "/mcp") ? 0 : 1;
    case "stdio-fixture": // TEST ONLY: the fixture over stdio (the product server is HTTP only)
        Console.Error.WriteLine("stdio-test-server ready {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"); // log noise, must not be parsed
        await StdioServer.ServeAsync(Server.Fixture(), Console.In, Console.Out);
        return 0;
    case "stdio-exit": // TEST ONLY: a stdio server that dies at once
        return 3;
    default:
        Console.WriteLine("usage: run.sh server | client <url> | selftest");
        return 2;
}
