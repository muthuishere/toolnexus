package io.github.muthuishere.toolnexus.mcp2026;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Opens a browser and listens on http://127.0.0.1:&lt;random&gt;/callback (RFC 8252 §7.3). */
public record LoopbackAuthorizer(Opener opener) implements Authorizer {
    /** Shows the authorization URL to the user (may block, e.g. a headless browser following redirects). */
    @FunctionalInterface
    public interface Opener {
        void open(String url) throws Exception;
    }

    /** The system browser. */
    public LoopbackAuthorizer() { this(null); }

    @Override
    public Session begin() {
        CompletableFuture<String> cb = new CompletableFuture<>();
        LocalServer[] holder = new LocalServer[1];
        holder[0] = LocalServer.start(ex -> {
            HttpReq r = HttpReq.of(ex, 0);
            if (!r.path().equals("/callback")) {
                HttpResp.text(404, "404 page not found\n").writeTo(ex);
                return;
            }
            cb.complete("http://" + r.host() + ex.getRequestURI().toString());
            HttpResp.text(200, "Authorization received. You can close this window.").writeTo(ex);
        });
        Opener open = opener != null ? opener : LoopbackAuthorizer::openBrowser;
        return new Session() {
            @Override
            public String redirectUri() { return holder[0].url() + "/callback"; }

            @Override
            public String await(Pending p) throws Exception {
                CompletableFuture<Void> opened = CompletableFuture.runAsync(() -> {
                    try {
                        open.open(p.url());
                    } catch (Exception e) {
                        throw new McpException("open authorization URL: " + e.getMessage(), e);
                    }
                }, r -> Thread.ofVirtual().start(r)); // the opener may block (tests follow redirects synchronously)
                try {
                    try {
                        CompletableFuture.anyOf(cb, opened).get(5, TimeUnit.MINUTES);
                    } catch (ExecutionException e) { // a failed opener must not leave us waiting for a callback that cannot come
                        if (!cb.isDone()) throw e.getCause() instanceof McpException m ? m : new McpException(String.valueOf(e.getCause()), e);
                    }
                    return cb.get(5, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    throw new McpException("authorization timed out");
                }
            }

            @Override
            public void close() { holder[0].close(); }
        };
    }

    static void openBrowser(String u) throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) new ProcessBuilder("open", u).start();
        else if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", u).start();
        else new ProcessBuilder("xdg-open", u).start();
    }
}
