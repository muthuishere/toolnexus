package io.github.muthuishere.toolnexus.mcp2026;

/** CLI: run the spike server, or the cross-port checks against any server. */
public final class Spike {
    private Spike() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: run.sh server | client <url> | selftest");
            System.exit(2);
        }
        switch (args[0]) {
            case "server" -> {
                String port = System.getenv().getOrDefault("PORT", "7104");
                LocalServer.start(Server.fixture(), Integer.parseInt(port));
                System.out.println("listening on http://127.0.0.1:" + port + "/mcp");
                Thread.currentThread().join();
            }
            case "client" -> {
                if (!Checks.run(args.length > 1 ? args[1] : "http://127.0.0.1:7104/mcp")) System.exit(1);
            }
            case "selftest" -> {
                try (LocalServer s = LocalServer.start(Server.fixture())) {
                    if (!Checks.run(s.url() + "/mcp")) System.exit(1);
                }
            }
            default -> {
                System.out.println("usage: run.sh server | client <url> | selftest");
                System.exit(2);
            }
        }
    }
}
