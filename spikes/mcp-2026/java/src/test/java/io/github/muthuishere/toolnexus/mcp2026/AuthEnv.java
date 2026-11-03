package io.github.muthuishere.toolnexus.mcp2026;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.obj;

/** A dev AS + a protected TLS MCP server + a CIMD document host (all 127.0.0.1). */
final class AuthEnv implements AutoCloseable {
    final DevAuthServer as;
    final Server server;
    final LocalServer mcp, cimd;
    final String url, cimdUrl;
    private final List<AutoCloseable> closers = new ArrayList<>();

    @SafeVarargs
    AuthEnv(Consumer<Server>... cfg) {
        as = DevAuthServer.start();
        String[] cimdHolder = new String[1];
        cimd = LocalServer.startTls(ex -> HttpResp.json(200, obj("client_id", cimdHolder[0], "client_name", "toolnexus spike",
                "redirect_uris", List.of("http://127.0.0.1/callback"), "grant_types", List.of("authorization_code", "refresh_token"),
                "response_types", List.of("code"), "token_endpoint_auth_method", "none")).writeTo(ex));
        cimdUrl = cimd.url() + "/client.json";
        cimdHolder[0] = cimdUrl;
        server = Server.fixture().auth(new ServerAuth(as.issuer()).scopes(Map.of("*", List.of("mcp:read"), "greet", List.of("mcp:greet"))));
        server.tool("whoami", "who is calling", (ctx, a) -> {
            Principal p = ctx.principal().orElseThrow(() -> new IllegalStateException("no principal"));
            return p.subject() + " " + String.join(",", p.scopes());
        });
        for (Consumer<Server> f : cfg) f.accept(server);
        mcp = LocalServer.startTls(server);
        url = mcp.url() + "/mcp";
    }

    /** Auto mode: CIMD client, the "browser" follows every redirect to the loopback listener. */
    Client autoClient() {
        return new Client(url).auth(new OAuth().clientMetadataUrl(cimdUrl).authorizer(new LoopbackAuthorizer(as::approve)));
    }

    Client pauseClient() {
        as.clients.put("spike-prereg", List.of("http://127.0.0.1:9/callback"));
        return new Client(url).auth(new OAuth().clientIds(Map.of(as.issuer(), "spike-prereg"))
                .authorizer(new PauseAuthorizer("http://127.0.0.1:9/callback")));
    }

    /** The AS redirect Location without following it (pause mode: the host "receives" the callback). */
    String visit(String authUrl) {
        HttpResponse<String> r = as.get(authUrl, false);
        if (r.statusCode() != 302) throw new AssertionError("authorize: HTTP " + r.statusCode());
        return r.headers().firstValue("Location").orElseThrow();
    }

    String claims(Consumer<Map<String, Object>> mut) {
        Map<String, Object> c = obj("iss", as.issuer(), "sub", "alice", "aud", url, "scope", "mcp:read",
                "exp", System.currentTimeMillis() / 1000 + 3600);
        if (mut != null) mut.accept(c);
        return as.mint(c);
    }

    static Pending pending(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Pending p = Pending.of(e);
            if (p != null) return p;
            throw new AssertionError("want PendingException, got " + e, e);
        }
        throw new AssertionError("want PendingException, got success");
    }

    @Override
    public void close() {
        mcp.close();
        cimd.close();
        as.close();
    }
}
