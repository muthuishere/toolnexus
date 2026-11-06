// Example 08 — serve MCP tools behind OAuth: one issuer line, per-tool scopes, the caller's
// identity in the handler, and a custom verify hook for opaque tokens.
//
// Run: ./gradlew -q runExample -Pex=08   (hermetic: everything runs on 127.0.0.1)
//
// With Server.auth set the server: serves Protected Resource Metadata at
// /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
// token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
// issuer's JWKS (signature, iss, aud = this server's URL, exp).
package ex08;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.DevAuthServer;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.LoopbackAuthorizer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.McpException;
import io.github.muthuishere.toolnexus.mcp2026.OAuth;
import io.github.muthuishere.toolnexus.mcp2026.Principal;
import io.github.muthuishere.toolnexus.mcp2026.Server;
import io.github.muthuishere.toolnexus.mcp2026.ServerAuth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        try (DevAuthServer as = DevAuthServer.start()) { // DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
            as.clients.put("my-app", List.of("http://127.0.0.1/callback"));

            // ---- (1) the server: issuer one-liner + per-tool scope + Principal ----
            Server srv = new Server()
                    .tool("whoami", "Who is calling", (ctx, a) -> {
                        Principal p = ctx.principal().orElseThrow(); // subject, scopes, claims of the verified token
                        return "hello " + p.subject() + " (" + String.join(" ", p.scopes()) + ")";
                    })
                    .tool("delete_all", "Dangerous admin tool", (ctx, a) -> "deleted");
            srv.auth(new ServerAuth(as.issuer()) // the whole required config
                    .scopes(Map.of(
                            "*", List.of("mcp:read"),           // every request
                            "delete_all", List.of("mcp:admin")))); // additionally required for this tool
            LocalServer ts = LocalServer.start(srv); // production: any JDK HttpServer, srv is an HttpHandler
            // ---- end server ----

            // No token: 401 with WWW-Authenticate pointing at the resource metadata.
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(ts.url() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2026-07-28")
                    .header("Mcp-Method", "tools/list")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            System.out.println("no token: " + resp.statusCode() + " | WWW-Authenticate has resource_metadata: "
                    + resp.headers().firstValue("WWW-Authenticate").orElse("").contains("resource_metadata="));

            // The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
            String prmJson = http.send(HttpRequest.newBuilder(URI.create(ts.url() + "/.well-known/oauth-protected-resource/mcp")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Map<String, Object> prm = Mcp.map(new com.fasterxml.jackson.databind.ObjectMapper().readValue(prmJson, Object.class));
            System.out.println("metadata: issuer ok: " + Mcp.list(prm.get("authorization_servers")).get(0).equals(as.issuer())
                    + " | scopes: " + prm.get("scopes_supported"));

            // A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
            Client c = new Client(ts.url() + "/mcp")
                    .auth(new OAuth().clientId("my-app").authorizer(new LoopbackAuthorizer(as::approve)));
            System.out.println("whoami: " + Mcp.text(c.callTool("whoami", null, null)));
            System.out.println("delete_all after step-up: " + Mcp.text(c.callTool("delete_all", null, null)));
            System.out.println("whoami: " + Mcp.text(c.callTool("whoami", null, null)));

            // ---- (2) custom verify: opaque API keys / token introspection instead of JWT ----
            Map<String, Principal> keys = Map.of( // e.g. a lookup in your DB, or an RFC 7662 introspection call
                    "svc-key-123", new Principal("billing-service", List.of("mcp:read")));
            Server srv2 = new Server().tool("whoami", "Who is calling",
                    (ctx, a) -> "hello " + ctx.principal().orElseThrow().subject());
            srv2.auth(new ServerAuth(as.issuer()) // still advertised in the resource metadata
                    .scopes(Map.of("*", List.of("mcp:read")))
                    .verify(token -> {
                        Principal p = keys.get(token);
                        if (p == null) throw new SecurityException("unknown key"); // -> 401 invalid_token
                        return p;
                    }));
            LocalServer ts2 = LocalServer.start(srv2);

            // A service caller with a static key: a header on every request, no OAuth.
            Client svc = new Client(ts2.url() + "/mcp").header("Authorization", "Bearer svc-key-123");
            System.out.println("verify hook: " + Mcp.text(svc.callTool("whoami", null, null)));

            Client bad = new Client(ts2.url() + "/mcp").header("Authorization", "Bearer wrong");
            boolean rejected = false;
            try {
                bad.callTool("whoami", null, null);
            } catch (McpException e) {
                rejected = true;
            }
            System.out.println("wrong key rejected: " + rejected);
            ts.close();
            ts2.close();
        }
    }
}
