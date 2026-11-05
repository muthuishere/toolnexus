package io.github.muthuishere.toolnexus.mcp2026;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A transport-neutral HTTP response. */
public record HttpResp(int status, Map<String, String> headers, byte[] body) {

    public static HttpResp json(int status, Object v) {
        return new HttpResp(status, new LinkedHashMap<>(Map.of("Content-Type", "application/json")), Mcp.jsonBytes(v));
    }

    public static HttpResp text(int status, String s) {
        return new HttpResp(status, new LinkedHashMap<>(Map.of("Content-Type", "text/plain; charset=utf-8")),
                s.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResp redirect(String location) {
        return new HttpResp(302, new LinkedHashMap<>(Map.of("Location", location)), new byte[0]);
    }

    public static HttpResp status(int status) { return new HttpResp(status, new LinkedHashMap<>(), new byte[0]); }

    void writeTo(HttpExchange ex) throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        }
        ex.close();
    }
}
