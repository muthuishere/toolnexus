// run: java -cp 'lib/*' Main.java   (lib/ = toolnexus-0.30.0.jar + its runtime deps)
import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;

import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] argv) throws Exception {
        NativeTool add = NativeTool.of("add", "Add two numbers.",
                Map.of("type", "object",
                        "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                        "required", List.of("a", "b")),
                args -> String.valueOf(((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue()));

        // Your "model": first turn asks for a tool, second turn streams the answer.
        LlmClient client = InProcess.createClient(new InProcess.Options()
                .model("my-model")
                .generateStream((req, emit) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> last = (Map<String, Object>) req.messages.get(req.messages.size() - 1);
                    if (!"tool".equals(last.get("role")))
                        return InProcess.Response.toolCalls(new InProcess.ToolCall("add", Map.of("a", 2, "b", 3)));
                    List<String> words = List.of("The ", "sum ", "is ", String.valueOf(last.get("content")), ".");
                    for (String w : words) emit.accept(LlmClient.StreamEvent.text(w)); // deltas must add up to content
                    return InProcess.Response.content(String.join("", words));
                }));

        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(add))) {
            client.stream("What is 2 + 3?", toolkit, ev -> {
                switch (ev.type()) {
                    case TEXT -> System.out.print(ev.delta());
                    case TOOL_CALL -> System.out.println("[calling " + ev.name() + "]");
                    default -> { }
                }
            });
            System.out.println(); // The sum is 5.
        }
    }
}
