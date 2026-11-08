package io.github.muthuishere.toolnexus.mcp2026;

/** TEST ONLY: a tiny stdio MCP server (the product server is HTTP only). MODE=exit exits with 3 at once. */
public final class StdioTestServer {
    public static void main(String[] args) throws Exception {
        if ("exit".equals(System.getenv("MODE"))) System.exit(3);
        System.err.println("stdio-test-server ready {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"); // log noise, must not be parsed
        Server.fixture().serveStdio(System.in, System.out);
    }
}
