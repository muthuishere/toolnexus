package io.github.muthuishere.toolnexus.mcp2026;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;

/** The 8 cross-port conformance checks, printing PASS/FAIL/SKIP lines. */
public final class Checks {
    private Checks() {}

    private static final class Skip extends RuntimeException {}

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    /** Runs the checks against url with plain clients. */
    public static boolean run(String url) { return run(() -> new Client(url)); }

    /** Runs the checks with clients from mk (e.g. TLS + OAuth for a protected server, or stdio). */
    public static boolean run(Supplier<Client> mk) {
        boolean[] ok = {true};
        Client c = mk.get();
        java.util.function.BiConsumer<String, Step> step = (name, f) -> {
            try {
                f.run();
                System.out.println("PASS " + name);
            } catch (Skip s) {
                System.out.println("SKIP " + name + " (not applicable over stdio)");
            } catch (Exception e) {
                ok[0] = false;
                System.out.println("FAIL " + name + ": " + e.getMessage());
            }
        };
        step.accept("server/discover", () -> {
            Map<String, Object> r = c.call("server/discover", null);
            Map<String, Object> caps = map(r.get("capabilities"));
            Map<String, Object> ext = caps == null ? null : map(caps.get("extensions"));
            if (ext == null || !ext.containsKey(SKILLS_EXT)) throw new Exception("skills extension not advertised: " + caps);
            if (!list(r.get("supportedVersions")).contains(PROTOCOL_VERSION))
                throw new Exception("supportedVersions lacks " + PROTOCOL_VERSION + ": " + r.get("supportedVersions"));
        });
        step.accept("unsupported version -> -32022", () -> expectCode(() -> mk.get().version("1900-01-01").call("server/discover", null), CODE_UNSUPPORTED_PROTOCOL));
        step.accept("header mismatch -> -32020", () -> {
            Client bad = mk.get();
            if (bad.stdio != null) {
                bad.close();
                throw new Skip(); // stdio has no HTTP headers to mismatch
            }
            bad.header("Mcp-Name", "greet");
            expectCode(() -> bad.call("tools/call", obj("name", "echo", "arguments", obj("text", "x"))), CODE_HEADER_MISMATCH);
        });
        step.accept("tools/list", () -> {
            Map<String, Object> r = c.call("tools/list", null);
            List<Object> names = list(r.get("tools")).stream().map(t -> map(t).get("name")).toList();
            if (!names.contains("echo") || !names.contains("greet")) throw new Exception("tools: " + names);
            if (r.get("ttlMs") == null || r.get("cacheScope") == null || !"complete".equals(r.get("resultType")))
                throw new Exception("missing ttlMs/cacheScope/resultType: " + r);
        });
        step.accept("tools/call echo", () -> {
            Map<String, Object> r = c.callTool("echo", obj("text", "hi toolnexus"), null);
            if (!text(r).equals("hi toolnexus")) throw new Exception("got " + r);
        });
        step.accept("tools/call greet (MRTR input_required round trip)", () -> {
            int[] asked = {0};
            Map<String, Object> r = c.callTool("greet", obj(), (key, req) -> {
                asked[0]++;
                if (!"elicitation/create".equals(req.get("method"))) throw new Exception("unexpected input request " + req.get("method"));
                return obj("action", "accept", "content", obj("name", "toolnexus"));
            });
            if (asked[0] != 1 || !text(r).equals("Hello, toolnexus!")) throw new Exception("asked=" + asked[0] + " result=" + r);
        });
        String uri = "skill://hello-world/SKILL.md";
        step.accept("skills/list", () -> {
            Map<String, Object> r = c.call("skills/list", null);
            for (Object s : list(r.get("skills"))) if (uri.equals(map(s).get("uri"))) return;
            throw new Exception("hello-world not listed: " + r);
        });
        step.accept("skills/get hello-world", () -> {
            Map<String, Object> r = c.call("skills/get", obj("uri", uri));
            Map<String, Object> sk = map(r.get("skill"));
            Map<String, Object> fm = sk == null ? null : map(sk.get("frontmatter"));
            if (fm == null || !"hello-world".equals(fm.get("name")) || list(sk.get("resources")).isEmpty()) throw new Exception("got " + r);
        });
        c.close();
        return ok[0];
    }

    private static void expectCode(Runnable r, int code) throws Exception {
        try {
            r.run();
        } catch (RpcError e) {
            if (e.code == code) return;
            throw new Exception("want error " + code + ", got " + e.code + " " + e.getMessage());
        }
        throw new Exception("want error " + code + ", got success");
    }
}
