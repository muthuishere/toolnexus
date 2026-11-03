package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs every program under examples/ end to end and checks its exact output (same text as the Go port),
 * so the docs (which copy code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only);
 * 09 needs KEYCLOAK=1 + the keycloak compose stack, 10 needs GITHUB_OAUTH=1 + sec-injected creds.
 */
class ExamplesTest {
    static final Map<String, String> HERMETIC = new LinkedHashMap<>();

    static {
        HERMETIC.put("01", "supported versions: [2026-07-28]\ntools: echo, greet\necho: hello over HTTP");
        HERMETIC.put("02", "tool count: 2\necho: hello over stdio");
        HERMETIC.put("03", "server asks (elicitation/create): What is your name?\nresult: Hello, Ada!");
        HERMETIC.put("04", "skills extension: true\nskill skill://hello-world/SKILL.md: hello-world\nfirst heading: # Hello World Skill\n"
                + "downloaded: hello-world/SKILL.md\ndownloaded: hello-world/scripts/greet.sh");
        HERMETIC.put("05", "eager: 100 tools, 9721 bytes of schema\nlazy:  2 tools, 519 bytes of schema\ntop hit: slack_create\n"
                + "result: slack_create ran with hi team");
        HERMETIC.put("06", "echo: hello after login\ngreet: Hello, Ada!\nbrowser logins: 2\ntoken stored for this issuer: true");
        HERMETIC.put("07", "(a) WebHandler: 200 resumed after consent\n(b) paused: authorization - true - has URL: true\n(b) resumed: resumed by hand");
        HERMETIC.put("08", "no token: 401 | WWW-Authenticate has resource_metadata: true\nmetadata: issuer ok: true | scopes: [mcp:read]\n"
                + "whoami: hello dev-user (mcp:read)\ndelete_all after step-up: deleted\nwhoami: hello dev-user (mcp:read mcp:admin)\n"
                + "verify hook: hello billing-service\nwrong key rejected: true");
    }

    static String run(String nn, int timeoutSec, String... env) throws Exception {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("examples.classpath"), "ex" + nn + ".Main")
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        for (int i = 0; i + 1 < env.length; i += 2) pb.environment().put(env[i], env[i + 1]);
        Process p = pb.start();
        var out = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return p.getInputStream().readAllBytes();
            } catch (java.io.IOException e) {
                return new byte[0];
            }
        });
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("example " + nn + " timed out");
        }
        String s = new String(out.join(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.exitValue(), "example " + nn + " failed:\n" + s);
        return s;
    }

    @TestFactory
    List<DynamicTest> hermetic() {
        return HERMETIC.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(),
                () -> assertEquals(e.getValue(), run(e.getKey(), 60), "output drifted"))).toList();
    }

    @Test
    void keycloak() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("KEYCLOAK")), "set KEYCLOAK=1 with keycloak/docker-compose.yml running");
        String got = run("09", 90, "HEADLESS_LOGIN", "1");
        System.out.println(got);
        assertTrue(got.contains("whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true") && got.contains("greet: Hello, Alice!"), got);
    }

    @Test
    void github() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("GITHUB_OAUTH")), "set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)");
        String got = run("10", 180, "BROWSER", "chrome-agent");
        System.out.println(got);
        assertEquals("tools listed: true\nget_me: complete | isError: false", got);
    }
}
