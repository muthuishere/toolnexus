// Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
//
// Run: ./gradlew -q runExample -Pex=04
//
// A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
// skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
// files themselves through resources/read on skill:// URIs.
package ex04;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.Server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public class Main {
    public static void main(String[] args) throws Exception {
        try (LocalServer ts = LocalServer.start(Server.fixture())) { // serves examples/skills/hello-world
            Client c = new Client(ts.url() + "/mcp");

            // 1. Does the server speak the skills extension?
            Map<String, Object> d = c.call("server/discover", null);
            Map<String, Object> ext = Mcp.map(Mcp.map(d.get("capabilities")).get("extensions"));
            System.out.println("skills extension: " + ext.containsKey(Mcp.SKILLS_EXT));

            // 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
            Map<String, Object> list = c.call("skills/list", null);
            for (Object s : Mcp.list(list.get("skills"))) {
                Map<String, Object> sk = Mcp.map(s);
                System.out.println("skill " + sk.get("uri") + ": " + Mcp.map(sk.get("frontmatter")).get("name"));
            }

            // 3. Load one skill's instructions: resources/read on its SKILL.md.
            String uri = "skill://hello-world/SKILL.md";
            Map<String, Object> r = c.call("resources/read", Mcp.obj("uri", uri));
            String body = Mcp.str(Mcp.map(Mcp.list(r.get("contents")).get(0)).get("text"));
            System.out.println("first heading: " + body.lines().filter(l -> l.startsWith("# ")).findFirst().orElse(""));

            // 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
            Path dir = Files.createTempDirectory("skills");
            c.downloadSkill(uri, dir);
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile).sorted()
                        .forEach(p -> System.out.println("downloaded: " + dir.relativize(p).toString().replace('\\', '/')));
            }
            try (Stream<Path> all = Files.walk(dir)) {
                all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
