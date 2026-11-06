// TEST TOOLING: MCP 2026-07-28 interop runners (interop/mcp2026/README.md). Not shipped.
using Mcp2026Interop;

return args.FirstOrDefault() switch
{
    "server" => await InteropServer.RunAsync(args[1..]),
    "client" => await InteropClient.RunAsync(args[1..]),
    _ => Usage(),
};

static int Usage()
{
    Console.Error.WriteLine("usage: Mcp2026Interop server [--keycloak] <skillsDir> | client <base> <skillsDir> | client --keycloak <mcpURL> <skillsDir>");
    return 2;
}
