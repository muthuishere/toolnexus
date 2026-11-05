// Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
//
// Run: ./gradlew -q runExample -Pex=07   (hermetic: everything runs on 127.0.0.1)
//
// A server-side app must never block a request waiting for a login. With PauseAuthorizer the
// call throws a PendingException instead ({id, kind:"authorization", prompt, url, expiresAt},
// the shape of toolnexus SPEC §10 Request). The app sends the browser to Pending.url; the
// authorization server redirects back; the app resumes and gets the ORIGINAL call's result.
//
// webHandler packages that whole dance as one HttpHandler. The manual version is shown too.
package ex07;

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.DevAuthServer;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.OAuth;
import io.github.muthuishere.toolnexus.mcp2026.PauseAuthorizer;
import io.github.muthuishere.toolnexus.mcp2026.Pending;
import io.github.muthuishere.toolnexus.mcp2026.Server;
import io.github.muthuishere.toolnexus.mcp2026.ServerAuth;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        // ---- demo harness: dev authorization server + protected MCP server ----
        try (DevAuthServer as = DevAuthServer.start(); // DEV/TEST ONLY
             LocalServer mcpTS = LocalServer.start(Server.fixture().auth(new ServerAuth(as.issuer())))) {
            as.clients.put("my-web-app", List.of("http://127.0.0.1/tool", "http://127.0.0.1/callback"));
            // ---- end harness ----

            // ---- the web app ----
            HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String app = "http://127.0.0.1:" + web.getAddress().getPort();
            Client c = new Client(mcpTS.url() + "/mcp")
                    .auth(new OAuth().clientId("my-web-app").authorizer(new PauseAuthorizer(app + "/tool")));

            // (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
            // with ?code&state, webHandler resumes, and the tool's text is written to the response.
            web.createContext("/tool", c.auth().webHandler(query ->
                    c.callTool("echo", Mcp.obj("text", query.get("text")), null)));
            web.start();
            // ---- end web app ----

            // A person's browser opens /tool?text=... (as.get follows redirects and trusts the dev TLS).
            HttpResponse<String> resp = as.get(app + "/tool?text=resumed+after+consent", true);
            System.out.println("(a) WebHandler: " + resp.statusCode() + " " + resp.body());

            // (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
            Client c2 = new Client(mcpTS.url() + "/mcp")
                    .auth(new OAuth().clientId("my-web-app").authorizer(new PauseAuthorizer("http://127.0.0.1/callback")));
            Pending p = null;
            try {
                c2.callTool("echo", Mcp.obj("text", "resumed by hand"), null);
            } catch (RuntimeException e) {
                p = Pending.of(e); // null when the call failed for another reason
            }
            if (p == null) throw new IllegalStateException("expected a pending authorization");
            System.out.println("(b) paused: " + p.kind() + " - " + !p.prompt().isEmpty() + " - has URL: " + !p.url().isEmpty());

            // The user logs in; you receive the callback URL (here: the AS redirect, not followed).
            String callbackUrl = as.get(p.url(), false).headers().firstValue("Location").orElseThrow();
            Map<String, Object> res = c2.auth().resume(p.id(), callbackUrl);
            System.out.println("(b) resumed: " + Mcp.text(res));
            web.stop(0);
        }
    }
}
