package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillsTest {
    @Test
    void parseSkillUri() {
        Map<String, List<String>> ok = Map.of(
                "skill://hello-world/SKILL.md", List.of("hello-world", "SKILL.md"),
                "skill://a/refs/doc.md", List.of("a", "refs/doc.md"),
                "skill://a/with%20space.md", List.of("a", "with space.md"),
                "skill://a/..dots-in-name..x.md", List.of("a", "..dots-in-name..x.md"));
        ok.forEach((u, want) -> {
            SkillUri s = SkillUri.parse(u);
            assertEquals(want, List.of(s.name(), s.rel()), u);
        });
        for (String u : List.of("skill://a/../b", "skill://a/%2e%2e/b", "skill://a/%2E%2E%2Fetc", "skill://%2e%2e/x",
                "skill://a/..%2F..%2Fetc%2Fpasswd", "skill://a/%252e%252e/x", "skill://a/%252F", "skill://a/x%5C..%5Cy",
                "skill://a//etc", "skill:///etc/passwd", "skill://a/%2Fetc", "skill://a/%00", "skill://a/C:/x",
                "skill://a/.", "skill://a", "skill://a/%zz", "file:///etc/passwd")) {
            assertThrows(IllegalArgumentException.class, () -> SkillUri.parse(u), u);
        }
    }

    @Test
    void serverRejectsTraversalUris() {
        try (LocalServer ts = LocalServer.start(Server.fixture())) {
            Client c = new Client(ts.url() + "/mcp");
            for (String m : List.of("resources/read", "skills/get")) {
                RpcError e = assertThrows(RpcError.class, () -> c.call(m, obj("uri", "skill://hello-world/%2e%2e/%2e%2e/fixture.json")));
                assertEquals(CODE_INVALID_PARAMS, e.code, m);
            }
        }
    }

    @Test
    void downloadSkill(@TempDir Path dir) throws Exception {
        try (LocalServer ts = LocalServer.start(Server.fixture())) {
            new Client(ts.url() + "/mcp").downloadSkill("skill://hello-world/SKILL.md", dir);
            assertTrue(Files.size(dir.resolve("hello-world/SKILL.md")) > 0);
        }
    }

    /** A hostile server lists a resource that decodes to ../../pwned: nothing may be written. */
    @Test
    void downloadSkillRefusesHostileResource(@TempDir Path root) throws Exception {
        for (String evil : List.of("skill://hello-world/%2e%2e%2f%2e%2e%2fpwned", "skill://hello-world/..%2F..%2Fpwned",
                "skill://hello-world/%252e%252e/pwned", "skill://other-skill/pwned")) {
            try (LocalServer ts = LocalServer.start(ex -> {
                Map<String, Object> req = map(parse(HttpReq.of(ex, 1 << 20).body()));
                Map<String, Object> res = obj("contents", List.of(obj("uri", evil, "text", "pwned")));
                if ("skills/get".equals(req.get("method")))
                    res = obj("skill", obj("uri", "skill://hello-world/SKILL.md", "resources",
                            List.of(obj("uri", "skill://hello-world/SKILL.md"), obj("uri", evil))));
                HttpResp.json(200, obj("jsonrpc", "2.0", "id", req.get("id"), "result", res)).writeTo(ex);
            })) {
                Path dir = root.resolve("a/b");
                assertThrows(McpException.class, () -> new Client(ts.url() + "/mcp").downloadSkill("skill://hello-world/SKILL.md", dir), evil);
                try (Stream<Path> w = Files.walk(root)) {
                    assertEquals(List.of(), w.filter(Files::isRegularFile).toList(), evil + ": wrote files");
                }
            }
        }
    }
}
