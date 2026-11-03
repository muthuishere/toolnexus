// Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
//
// Run: ./gradlew -q runExample -Pex=06   (hermetic: everything runs on 127.0.0.1)
//
// The client learns everything from the server's 401: Protected Resource Metadata -> the
// authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
// You only say WHO the client is: a pre-registered clientId, or a CIMD clientMetadataUrl.
// There is no Dynamic Client Registration.
package ex06;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.DevAuthServer;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.LoopbackAuthorizer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.MemoryTokenStore;
import io.github.muthuishere.toolnexus.mcp2026.OAuth;
import io.github.muthuishere.toolnexus.mcp2026.Server;
import io.github.muthuishere.toolnexus.mcp2026.ServerAuth;
import io.github.muthuishere.toolnexus.mcp2026.Token;

import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        // ---- demo harness: an authorization server + a protected MCP server, both in-process ----
        try (DevAuthServer as = DevAuthServer.start()) { // DEV/TEST ONLY: auto-approves everyone as "dev-user"
            as.clients.put("my-app", List.of("http://127.0.0.1/callback")); // the AS admin registered "my-app"

            Server srv = Server.fixture().auth(new ServerAuth(as.issuer())
                    .scopes(Map.of("*", List.of("mcp:read"), "greet", List.of("mcp:greet")))); // greet needs more
            try (LocalServer ts = LocalServer.start(srv)) {
                // ---- end harness ----

                MemoryTokenStore store = new MemoryTokenStore(); // implement TokenStore to persist tokens (keyed by resource)

                // The user code. A real app leaves the authorizer unset: LoopbackAuthorizer opens the system
                // browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
                Client c = new Client(ts.url() + "/mcp").auth(new OAuth()
                        .clientId("my-app")
                        .store(store)
                        .authorizer(new LoopbackAuthorizer(as::approve)));

                Map<String, Object> res = c.callTool("echo", Mcp.obj("text", "hello after login"), null);
                System.out.println("echo: " + Mcp.text(res));

                // Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
                // re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
                res = c.callTool("greet", Mcp.obj(), (key, req) -> Mcp.obj("action", "accept", "content", Mcp.obj("name", "Ada")));
                System.out.println("greet: " + Mcp.text(res));

                // A second client sharing the token store needs no new login.
                Client c2 = new Client(ts.url() + "/mcp")
                        .auth(new OAuth().clientId("my-app").store(store).authorizer(new LoopbackAuthorizer(as::approve)));
                c2.callTool("echo", Mcp.obj("text", "again"), null);
                System.out.println("browser logins: " + as.authorizations()); // 1 login + 1 step-up

                Token tok = store.load(ts.url() + "/mcp").orElseThrow();
                System.out.println("token stored for this issuer: " + tok.issuer().equals(as.issuer())); // toString() is redacted
            }
        }
    }
}
