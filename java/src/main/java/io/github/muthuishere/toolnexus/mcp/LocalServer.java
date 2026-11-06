package io.github.muthuishere.toolnexus.mcp;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A running JDK HttpServer on 127.0.0.1 (the Java analogue of Go's httptest.Server).
 */
public final class LocalServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService pool;
    private final String url;

    private LocalServer(HttpServer server, String scheme) {
        this.server = server;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(pool);
        server.start();
        this.url = scheme + "://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Serves handler on 127.0.0.1:port (0 = random) over plain HTTP. */
    public static LocalServer start(HttpHandler handler, int port) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            s.createContext("/", handler);
            return new LocalServer(s, "http");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static LocalServer start(HttpHandler handler) { return start(handler, 0); }

    /** scheme://127.0.0.1:port (no trailing slash). */
    public String url() { return url; }

    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
