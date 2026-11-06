// run (Java 22+, multi-file source launcher): java -cp 'lib/*' Main.java python3 fake_cli.py
//      (lib/ = toolnexus-0.26.0.jar + its runtime deps; OneShot.java sits next to this file)
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {
    @SuppressWarnings("unchecked")
    public static void main(String[] argv) throws Exception {
        ObjectMapper json = new ObjectMapper();
        // Parsed from JSON so key order (and so the rendered prompt) matches every other port.
        Map<String, Object> schema = json.readValue("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},"
                + "\"b\":{\"type\":\"number\"}},\"required\":[\"a\",\"b\"]}", Map.class);
        NativeTool add = NativeTool.of("add", "Add two numbers.", schema,
                args -> String.valueOf(((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue()));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(add))) {
            LlmClient.RunResult r = OneShot.client(List.of(argv))
                    .run("Use the add tool to add 19 and 23, then tell me the sum.", tk);
            List<Map<String, Object>> calls = r.toolCalls.stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", c.name); m.put("args", c.args); m.put("output", c.output); m.put("isError", c.isError);
                        return m;
                    }).toList();
            System.out.println("text: " + json.writeValueAsString(r.text) + " toolCalls: " + json.writeValueAsString(calls));
        }
    }
}
