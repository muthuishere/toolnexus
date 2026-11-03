# MCP authentication: provider notes

> **Java port of the spike — the Go spike is the reference (openspec `upgrade-mcp-2026-07-28`).** The API below is
> `spikes/mcp-2026/java` (package `io.github.muthuishere.toolnexus.mcp2026`). Every code block is copied verbatim
> from a program under `examples/` that `ExamplesTest` runs and checks; `DocsTest` fails if a block here stops
> matching its source.

| provider | client identity | tested (Java) | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client (CIMD tested in Go only) | live, docker compose | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live (example 10) | Go: works; Java: written, live run blocked (needs_human, see README) |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: `../golang/keycloak/docker-compose.yml` + `realm-toolnexus.json` (realm `toolnexus`, client
`toolnexus-mcp`, throwaway user `alice`), example `examples/09-keycloak`.

```sh
(cd ../golang/keycloak && docker compose up -d)
KEYCLOAK=1 ./gradlew -q runExample -Pex=09                          # logs in through your browser
KEYCLOAK=1 ./gradlew test --tests '*Keycloak*' --tests '*ExamplesTest.keycloak'   # the live suite, headless
```

<!-- from: examples/09-keycloak/Main.java -->
```java
        Server srv = Server.fixture().tool("whoami", "Who is calling", (ctx, a) -> {
            Principal p = ctx.principal().orElseThrow();
            // subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
            return "client=" + p.claims().get("azp") + " scopes=" + String.join(" ", p.scopes()) + " subject-set=" + !p.subject().isEmpty();
        });
        srv.auth(new ServerAuth(ISSUER)
                .audience(RESOURCE) // = the audience mapper's included.custom.audience
                .scopes(Map.of("*", List.of("mcp:tools"), "greet", List.of("mcp:greet"))));
        try (LocalServer ln = LocalServer.start(srv, MCP_PORT)) {

            // ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
            LoopbackAuthorizer open = new LoopbackAuthorizer(); // system browser
            if ("1".equals(System.getenv("HEADLESS_LOGIN"))) {
                open = new LoopbackAuthorizer(new HeadlessLogin("alice", "alice-test-pw")); // throwaway local test user
            }
            Client c = new Client(RESOURCE).auth(new OAuth()
                    .clientIds(Map.of(ISSUER, "toolnexus-mcp")) // client id bound to THIS issuer
                    .allowHttp(true)                             // dev Keycloak is plain HTTP; never in production
                    .authorizer(open));

            System.out.println("whoami: " + Mcp.text(c.callTool("whoami", null, null)));

            // greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
            Map<String, Object> res = c.callTool("greet", Mcp.obj(),
                    (key, req) -> Mcp.obj("action", "accept", "content", Mcp.obj("name", "Alice")));
            System.out.println("greet: " + Mcp.text(res));
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

### Setup checklist

1. **Client**: public, standard flow, PKCE method `S256`, redirect URI `http://127.0.0.1/*` (Keycloak matches any
   loopback port). Set `fullScopeAllowed: false` or every token request logs a deprecation warning.
2. **Client scopes**: one per permission (`mcp:tools` default, `mcp:greet` optional). Optional scopes are granted
   only when requested, which is what step-up asks for.
3. **Audience mapper**: Keycloak does **not** derive `aud` from `resource`. Add an `oidc-audience-mapper` with
   `included.custom.audience` = your MCP server's canonical URL and set `ServerAuth.audience(…)` to the same value.
4. **`basic` scope**: must exist, or tokens have no `sub`.
5. **CIMD (optional, experimental)**: needs the `cimd` feature plus a client policy + profile; see the Go port's
   provider notes for the full checklist.

Java-specific: Keycloak marks its login-session cookies `Secure` even on `http://localhost`, and
`java.net.CookieManager` then refuses to send them back (HTTP 400 on the login POST). The headless test browser
(`examples/09-keycloak/HeadlessLogin.java`) keeps its own tiny cookie jar for that reason. Real browsers are unaffected.

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**:
github.com → Settings → Developer settings → OAuth Apps → New, callback URL `http://127.0.0.1/callback`
(GitHub ignores the port on loopback). Enable expiring user tokens to get refresh tokens.

<!-- from: examples/10-github/Main.java -->
```java
        LoopbackAuthorizer login = new LoopbackAuthorizer(); // system browser + http://127.0.0.1:<random>/callback
        if ("chrome-agent".equals(System.getenv("BROWSER"))) {
            login = new LoopbackAuthorizer(u -> new ProcessBuilder("chrome-agent", "goto", u)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());
        }

        Client c = new Client("https://api.githubcopilot.com/mcp/").auth(new OAuth()
                .clientId(id)
                .clientSecret(secret) // GitHub OAuth Apps are confidential clients (client_secret_basic)
                .authorizer(login));

        System.out.println("tools listed: " + !c.tools().isEmpty());

        Map<String, Object> res = c.callTool("get_me", Mcp.obj(), null); // read-only: who am I
        System.out.println("get_me: " + res.get("resultType") + " | isError: " + Boolean.TRUE.equals(res.get("isError"))); // content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ./gradlew -q runExample -Pex=10
```

Quirks the client handles without configuration:

- **Form-encoded token response.** Without `Accept: application/json` GitHub's token endpoint answers
  `application/x-www-form-urlencoded` (RFC 6749 requires JSON). The client sends the header and also parses a
  form-encoded body.
- `serverInfo` arrives in result `_meta` (`io.modelcontextprotocol/serverInfo`), not the result body.
- `isError` is omitted on successful results; treat missing as false.

## Microsoft Entra ID — UNTESTED

Not run against a real tenant. Known from python-sdk issue #2578: Entra rejects `resource` on the
`refresh_token` grant, though the MCP spec says it MUST be sent. Set `.omitRefreshResource(true)` for Entra;
authorize and code exchange still send `resource`. Only the simulation
(`DevAuthServer.rejectRefreshResource`, `RetryTest.refreshResourceConfigurable`) covers this.
