// Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
//
// Run: ./gradlew -q runExample -Pex=03
//
// In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
// RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
// The client answers them and re-sends the same call with inputResponses (+ the opaque
// requestState). callTool runs those rounds for you: you only supply a Responder callback.
package ex03;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.Server;

import java.util.Map;

public class Main {
    public static void main(String[] args) {
        try (LocalServer ts = LocalServer.start(Server.fixture())) { // `greet` asks for the user's name
            Client c = new Client(ts.url() + "/mcp");

            // The Responder is called once per input request. key identifies the request inside the
            // round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
            // In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
            Client.Responder askUser = (key, req) -> {
                Map<String, Object> params = Mcp.map(req.get("params"));
                System.out.println("server asks (" + req.get("method") + "): " + params.get("message"));
                // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
                return Mcp.obj("action", "accept", "content", Mcp.obj("name", "Ada"));
            };

            Map<String, Object> res = c.callTool("greet", Mcp.obj(), askUser);
            System.out.println("result: " + Mcp.text(res));
        }
    }
}
