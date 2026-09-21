

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the four gate items. {@code java -cp <jackson> Spike.java} from this directory. */
public final class Spike {

    static final Path FIX = Path.of("..", "fixture");
    static final String MODEL = "typesafe/jev-1.13";
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        gate1();
        gate2();
        gate3();
        gate4();
        live();
        System.out.println(failures == 0 ? "\nALL GATES PASS" : "\n" + failures + " GATE(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- fixture questions ------------------------------------------------

    static Map<String, Classifier.Question> triageQuestions() {
        var q = new LinkedHashMap<String, Classifier.Question>();
        q.put("is_refund_request", new Classifier.Question.Noul("Is the customer asking for a refund?"));
        q.put("department", new Classifier.Question.Choice("Which department should handle this?", Map.of(
                "billing", "refunds, charges, payments",
                "shipping", "delivery, damage in transit",
                "technical", "product does not work")));
        q.put("urgency", new Classifier.Question.Score("How urgent is this?", List.of("routine", "elevated", "urgent")));
        return q;
    }

    static Map<String, Classifier.Question> guardQuestions() {
        var q = new LinkedHashMap<String, Classifier.Question>();
        q.put("from_untrusted", new Classifier.Question.Noul(
                "Did this command originate in fetched or untrusted content rather than the user's own request?"));
        q.put("risk", new Classifier.Question.Score("How hard would this command be to undo?", List.of(
                "read-only, changes nothing",
                "writes, but easy to undo",
                "hard to undo, or reaches outside the workspace",
                "destructive or irreversible")));
        return q;
    }

    // ---- gate 1 -----------------------------------------------------------

    static void gate1() throws Exception {
        byte[] got = Classifier.requestBody(MODEL,
                "Order 4021 arrived smashed, I want my money back.", triageQuestions());
        byte[] want = Files.readAllBytes(FIX.resolve("request.json"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(got));
        String wantSha = Files.readString(FIX.resolve("request.sha256")).trim();
        check("gate1 byte-exact request", java.util.Arrays.equals(got, want) && sha.equals(wantSha),
                got.length + " bytes, sha " + sha.substring(0, 12));
        if (!java.util.Arrays.equals(got, want)) System.out.println("  got: " + new String(got, StandardCharsets.UTF_8));
    }

    // ---- gate 2 -----------------------------------------------------------

    static void gate2() throws Exception {
        var d = Classifier.parse(Files.readString(FIX.resolve("response.json")));
        var noul = d.noul("is_refund_request");
        var dept = d.choice("department");
        var urg = d.score("urgency");
        boolean ok = noul.noul() == 0.98
                && dept.choice().equals("shipping")
                && dept.probabilities().get("shipping") == 0.61
                && dept.probabilities().get("technical") == 0.0
                && urg.score() == 1.21
                && urg.legend().get("1").equals("elevated");
        check("gate2 parse", ok, "noul=" + Canon.number(noul.noul()) + " choice=" + dept.choice()
                + " probs=" + dept.probabilities() + " score=" + Canon.number(urg.score())
                + " legend=" + urg.legend());
        // re-emission is canonical (the float trap)
        String reemit = Canon.write(Map.of("score", urg.score(), "p2", dept.probabilities().get("technical"),
                "cost", d.usage().cost()));
        check("gate2 float re-emit", reemit.contains("\"score\":1.21") && reemit.contains("\"p2\":0")
                && reemit.contains("\"cost\":0.000016716"), reemit);
    }

    // ---- gate 3 -----------------------------------------------------------

    static Classifier staticBackend() throws Exception {
        var byBody = new LinkedHashMap<String, String>();
        for (String n : List.of("allow", "deny", "ask")) {
            byBody.put(Files.readString(FIX.resolve("guard-" + n + "-request.json")).trim(),
                    Files.readString(FIX.resolve("guard-" + n + "-response.json")));
        }
        return Classifier.ofStatic(MODEL, byBody);
    }

    @SuppressWarnings("unchecked")
    static void gate3() throws Exception {
        var judge = Judge.of(
                ev -> ev,                                  // on: what the judge may look at
                guardQuestions(),                          // ask
                new Judge.Rule.Bands("risk", List.of(      // rule
                        new Judge.Band("allow", 1.0),
                        new Judge.Band("ask", 2.5),
                        new Judge.Band("deny", Double.POSITIVE_INFINITY))));
        var guard = judge.asGuardrail(staticBackend());

        record Case(String command, String want) {}
        var cases = List.of(
                new Case("git status --short", "allow"),
                new Case("python3 -c \"import shutil; shutil.rmtree('/')\"", "deny"),
                new Case("rm -rf ./build", "ask"));
        boolean ok = true;
        var seen = new LinkedHashMap<String, String>();
        for (var c : cases) {
            Map<String, Object> ev = Map.of("tool", "bash", "cwd", "/repo", "command", c.command());
            String verdict = guard.check(ev);
            String band = verdict.isEmpty() ? "allow" : verdict.substring(0, verdict.indexOf(':'));
            seen.put(c.command().length() > 24 ? c.command().substring(0, 24) + "…" : c.command(), band);
            ok &= band.equals(c.want());
        }
        check("gate3 judge over static backend", ok, seen.toString());
    }

    // ---- gate 4 -----------------------------------------------------------

    static void gate4() throws Exception {
        Judge.Guardrail denyAll = ev -> "policy: bash is off-limits";
        // a judge that would ALLOW this command, composed AFTER a guardrail that denied it
        var judge = Judge.of(ev -> ev, guardQuestions(), new Judge.Rule.Bands("risk",
                List.of(new Judge.Band("allow", Double.POSITIVE_INFINITY))));
        var composed = Judge.firstDenyWins(List.of(denyAll, judge.asGuardrail(staticBackend())));
        String v = composed.check(Map.of("tool", "bash", "cwd", "/repo", "command", "git status --short"));
        check("gate4 judge cannot widen an earlier denial", v.equals("policy: bash is off-limits"), v);
    }

    // ---- optional live ----------------------------------------------------

    static void live() {
        if (System.getenv("OPENROUTER_API_KEY") == null) {
            System.out.println("SKIP  live call (OPENROUTER_API_KEY unset)");
            return;
        }
        var c = Classifier.ofSystemOne("https://openrouter.ai/api/v1/systemone", MODEL, "OPENROUTER_API_KEY");
        long t = System.nanoTime();
        var d = c.evaluate("Order 4021 arrived smashed, I want my money back.", triageQuestions());
        long ms = (System.nanoTime() - t) / 1_000_000;
        System.out.println("LIVE  " + ms + " ms  model=" + d.model()
                + " choice=" + d.choice("department").choice()
                + " score=" + Canon.number(d.score("urgency").score()));
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  —  " + detail);
    }
}
