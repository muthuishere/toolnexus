# MCP authentication: provider notes

> **C# port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/csharp` (project `src/Mcp2026`, namespace `Mcp2026`). Every code block is copied verbatim from a
> program under `examples/` that `ExamplesTests` runs and checks; `DocsTests` fails if a block here stops matching
> its source. Behaviour and example output are identical to the Go reference spike.

| provider | client identity | tested (C#) | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client | live, docker compose (`../golang/keycloak`) | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live | works |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | set `OmitRefreshResource` |

## Keycloak

```sh
cd ../golang/keycloak && docker compose up -d && cd -
KEYCLOAK=1 dotnet run --project examples/09-keycloak                       # logs in through your browser
KEYCLOAK=1 dotnet test tests/Mcp2026.Tests --filter "Keycloak"             # the live suite, headless
```

<!-- from: examples/09-keycloak/Program.cs -->
```csharp
srv.Auth = new ServerAuth
{
    Issuer = issuer,
    Audience = resource, // = the audience mapper's included.custom.audience
    Scopes = new() { ["*"] = ["mcp:tools"], ["greet"] = ["mcp:greet"] },
};
await using var host = await HttpHost.StartAsync(srv, port: 7199);

// ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
var open = new LoopbackAuthorizer // Open = null: system browser
{
    Open = Environment.GetEnvironmentVariable("HEADLESS_LOGIN") == "1" ? HeadlessLogin.Open("alice", "alice-test-pw") : null, // throwaway local test user
};
var c = new McpClient(resource)
{
    Auth = new OAuth
    {
        ClientIds = new() { [issuer] = "toolnexus-mcp" }, // client id bound to THIS issuer
        AllowHttp = true,                                  // dev Keycloak is plain HTTP; never in production
        Authorizer = open,
    },
};

var res = await c.CallToolAsync("whoami");
Console.WriteLine("whoami: " + Mcp.Text(res));
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

Setup checklist (same realm as the Go spike): public client with PKCE S256 and redirect `http://127.0.0.1/*`; one
client scope per permission; an `oidc-audience-mapper` whose audience equals `ServerAuth.Audience` (Keycloak does
not derive `aud` from `resource`); the `basic` scope re-declared in the realm import. See
`../golang/docs/mcp-providers.md` for the CIMD policy notes (not exercised by the C# suite).

## GitHub

Register an **OAuth App** (callback `http://127.0.0.1/callback`, expiring user tokens on for refresh).

<!-- from: examples/10-github/Program.cs -->
```csharp
var c = new McpClient("https://api.githubcopilot.com/mcp/")
{
    Auth = new OAuth
    {
        ClientId = id,
        ClientSecret = secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
        Authorizer = login,
    },
};

var tools = await c.ToolsAsync();
Console.WriteLine("tools listed: " + (tools.Count > 0 ? "true" : "false"));

var res = await c.CallToolAsync("get_me", []); // read-only: who am I
Console.WriteLine($"get_me: {res["resultType"]} | isError: {(J.True(res["isError"]) ? "true" : "false")}"); // content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- dotnet run --project examples/10-github
```

The client sends `Accept: application/json` to the token endpoint and also parses a form-encoded body;
`isError` missing is treated as false.

## Microsoft Entra ID — UNTESTED

Entra rejects `resource` on the `refresh_token` grant (python-sdk #2578). Set `OmitRefreshResource = true`;
covered only by the simulation (`DevAuthServer.RejectRefreshResource`, `RetryTests.RefreshResourceConfigurable`).
