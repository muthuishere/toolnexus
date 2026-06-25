package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Toolkit;

/**
 * Conversation memory: a {@link LlmClient.Conversation} retains history across {@code send()}
 * calls, so turn 2 recalls facts stated in turn 1. Mirrors {@code js/examples/memory.ts}.
 *
 *   OPENROUTER_API_KEY=... gradle runMemory
 */
public final class Memory {

    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options());

        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("no OPENROUTER_API_KEY — skipping");
            tk.close();
            System.exit(0);
        }
        String model = System.getenv("OPENROUTER_MODEL");
        if (model == null || model.isEmpty()) model = "openai/gpt-4o-mini";

        LlmClient agent = LlmClient.create(new LlmClient.Options()
                .baseUrl("https://openrouter.ai/api/v1")
                .style("openai")
                .model(model)
                .apiKey(key));

        LlmClient.Conversation convo = agent.conversation(tk);

        LlmClient.RunResult a = convo.send(
                "My name is Muthu and my favorite number is 7. Reply with just 'noted'.");
        System.out.println("turn 1: " + clip(a.text, 60) + " | messages: " + convo.messages().size());

        LlmClient.RunResult b = convo.send("What is my name and favorite number?");
        System.out.println("turn 2: " + clip(b.text, 80) + " | messages: " + convo.messages().size());

        tk.close();

        boolean remembered = b.text.toLowerCase().contains("muthu")
                && (b.text.contains("7") || b.text.toLowerCase().contains("seven"));
        if (!remembered) {
            System.err.println("FAIL conversation did not retain memory across turns");
            System.exit(1);
        }
        System.out.println("\nOK conversation memory verified — turn 2 recalled facts from turn 1");
        System.exit(0);
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ");
        return oneLine.length() > n ? oneLine.substring(0, n) : oneLine;
    }
}
