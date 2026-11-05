package io.github.muthuishere.toolnexus.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A transport-neutral HTTP request (headers lower-cased, first value) so handlers are testable without sockets. */
public record HttpReq(String method, String path, String rawQuery, Map<String, String> headers, byte[] body,
                      boolean tls, String host) {

    public String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }

    public Map<String, String> query() { return parseQuery(rawQuery); }

    /** Parses a=b&c=d (first value wins; '+' is a space). */
    public static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return out;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    /** Reads an exchange; the body is capped at limit+1 bytes so callers can detect "too large". */
    static HttpReq of(HttpExchange ex, int limit) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> { if (!v.isEmpty()) h.put(k.toLowerCase(Locale.ROOT), v.get(0)); });
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(limit + 1);
        }
        return new HttpReq(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), h, body,
                ex instanceof HttpsExchange, h.getOrDefault("host", ""));
    }
}
