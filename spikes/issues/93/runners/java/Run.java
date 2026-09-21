// Runner: print the Java port's skill inventory as JSON for the issue-93 harness.
// Compile/run with the port's runtime classpath (see harness.py).
import io.github.muthuishere.toolnexus.SkillSource;
import java.util.List;
import java.util.stream.Collectors;

public class Run {
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    public static void main(String[] args) {
        var opts = new SkillSource.LoadOptions().dirs(List.of(args));
        var inv = SkillSource.listSkills(opts);
        String skills = inv.skills.stream()
                .map(s -> "{\"location\":\"" + esc(s.location) + "\"}")
                .collect(Collectors.joining(","));
        String skipped = inv.skipped.stream()
                .map(s -> "{\"location\":\"" + esc(s.location) + "\",\"reason\":\"" + esc(s.reason) + "\"}")
                .collect(Collectors.joining(","));
        System.out.println("{\"skills\":[" + skills + "],\"skipped\":[" + skipped + "]}");
    }
}
