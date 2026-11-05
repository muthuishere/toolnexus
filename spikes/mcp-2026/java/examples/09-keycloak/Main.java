// Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
//
// Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
//
//     cd ../golang/keycloak && docker compose up -d && cd -
//     KEYCLOAK=1 ./gradlew -q runExample -Pex=09                    # logs in through your system browser
//     KEYCLOAK=1 HEADLESS_LOGIN=1 ./gradlew -q runExample -Pex=09   # scripted login (used by the test)
//
// The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
// aud = http://127.0.0.1:7199/mcp into tokens; the ServerAuth audience must equal it.
package ex09;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.LoopbackAuthorizer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.OAuth;
import io.github.muthuishere.toolnexus.mcp2026.Principal;
import io.github.muthuishere.toolnexus.mcp2026.Server;
import io.github.muthuishere.toolnexus.mcp2026.ServerAuth;

import java.util.List;
import java.util.Map;

public class Main {
    static final String ISSUER = "http://localhost:8180/realms/toolnexus"; // exactly the `iss` Keycloak puts in tokens
    static final int MCP_PORT = 7199;
    static final String RESOURCE = "http://127.0.0.1:" + MCP_PORT + "/mcp";

    public static void main(String[] args) {
        if (!"1".equals(System.getenv("KEYCLOAK"))) {
            System.out.println("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running");
            return;
        }

        // ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
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
        }
    }
}
