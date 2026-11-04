package io.github.muthuishere.toolnexus.mcp;

/** TEST ONLY: the fixture as a stdio MCP 2026-07-28 server. MODE=exit exits at once; MODE=silent never answers. */
public final class StdioTestServer {
    public static void main(String[] args) throws Exception {
        String mode = System.getenv().getOrDefault("MODE", "");
        if ("exit".equals(mode)) System.exit(3);
        if ("silent".equals(mode)) {
            System.in.transferTo(java.io.OutputStream.nullOutputStream());
            return;
        }
        System.err.println("stdio-test-server ready {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"); // noise, never parsed
        Server.fixture().serveStdio(System.in, System.out);
    }
}
