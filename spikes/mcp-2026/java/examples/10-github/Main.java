// Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
//
// GitHub supports neither DCR nor CIMD, so you register an OAuth App once
// (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
// enable "expiring user tokens" if you want refresh) and pass its client id + secret.
//
//     sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ./gradlew -q runExample -Pex=10
//
// Credentials are read from the environment and never printed; neither is any token.
// BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
// otherwise the system browser opens.
package ex10;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.LoopbackAuthorizer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.OAuth;

import java.util.Map;

public class Main {
    public static void main(String[] args) {
        String id = System.getenv("GITHUB_MCP_CLIENT_ID"), secret = System.getenv("GITHUB_MCP_CLIENT_SECRET");
        if (id == null || id.isEmpty() || secret == null || secret.isEmpty()) {
            System.out.println("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...");
            return;
        }

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
    }
}
