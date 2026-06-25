package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the JS "httpTool: placeholder, env header, GET query, non-2xx" test
 * using a real ephemeral {@link HttpServer}. The {@code ${ENV}} header expansion
 * uses an existing process env var (PATH-style) so no in-process env mutation is
 * required — the value reaching the server is asserted against {@code System.getenv}.
 */
class HttpToolTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> seenUrl = new AtomicReference<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();

    // An env var that exists in basically every environment, used to prove
    // ${ENV} header expansion reaches the server.
    private static final String ENV_NAME = System.getenv("PATH") != null ? "PATH" : "HOME";
    private static final String ENV_VALUE = System.getenv(ENV_NAME);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String uri = exchange.getRequestURI().toString();
            seenUrl.set(uri);
            List<String> auth = exchange.getRequestHeaders().get("Authorization");
            seenAuth.set(auth == null || auth.isEmpty() ? null : auth.get(0));
            byte[] body;
            int status;
            if (uri.startsWith("/posts/5")) {
                status = 200;
                body = "{\"id\":5,\"title\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            } else {
                status = 404;
                body = "nope".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private Map<String, Object> schema(boolean withQ) {
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "number");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", idProp);
        if (withQ) {
            Map<String, Object> qProp = new LinkedHashMap<>();
            qProp.put("type", "string");
            props.put("q", qProp);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Test
    void placeholderEnvHeaderAndGetQuery() {
        assertNotNull(ENV_VALUE, "need a real env var to prove header expansion");

        HttpTool.Options opts = new HttpTool.Options();
        opts.name = "get_post";
        opts.description = "";
        opts.method = "GET";
        opts.url = "http://127.0.0.1:" + port + "/posts/{id}";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer ${" + ENV_NAME + "}");
        opts.headers = headers;
        opts.inputSchema = schema(true);

        HttpTool ok = HttpTool.of(opts);
        ToolResult r1 = ok.execute(Map.of("id", 5, "q", "x"), null);

        assertFalse(r1.isError());
        assertTrue(r1.output().contains("hello"));
        assertEquals("Bearer " + ENV_VALUE, seenAuth.get(), "env header expanded and reached server");
        assertTrue(seenUrl.get().matches("/posts/5\\?q=x"), "placeholder + querystring: " + seenUrl.get());
        assertEquals("http", ok.source());
        assertEquals(200, r1.metadata().get("status"));
    }

    @Test
    void non2xxIsError() {
        HttpTool.Options opts = new HttpTool.Options();
        opts.name = "b";
        opts.description = "";
        opts.method = "GET";
        opts.url = "http://127.0.0.1:" + port + "/posts/{id}";
        opts.inputSchema = schema(false);

        HttpTool bad = HttpTool.of(opts);
        ToolResult r2 = bad.execute(Map.of("id", 99), null);
        assertTrue(r2.isError());
        assertTrue(r2.output().startsWith("HTTP 404:"), "404 message: " + r2.output());
    }
}
