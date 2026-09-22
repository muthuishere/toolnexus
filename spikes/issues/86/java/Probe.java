// Spike for issue #86 (Java): the issue's table omits Java entirely. Test it:
// is there an overload without a Toolkit, and does `null` survive the loop?
import io.github.muthuishere.toolnexus.*;

public class Probe {
    public static void main(String[] args) throws Exception {
        String base = System.getenv("SPIKE86_BASE");
        LlmClient.Options o = new LlmClient.Options();
        o.baseUrl = base; o.style = "openai"; o.model = "mock"; o.apiKey = "not-a-real-key";
        LlmClient client = LlmClient.create(o);

        // There is no run(String) overload, so `null` is the only spelling.
        try {
            LlmClient.RunResult r = client.run("write me a haiku", (Toolkit) null);
            System.out.println("java: null-toolkit run OK, text=" + r.text);
        } catch (Throwable e) {
            System.out.println("java: null-toolkit run FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // The workaround that exists today.
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        LlmClient.RunResult r2 = client.run("write me a haiku", tk);
        System.out.println("java: Toolkit builtins(false) OK, tools=" + tk.tools().size() + " text=" + r2.text);
    }
}
