package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.Tools;
import io.github.muthuishere.toolnexus.acp.AcpClient;
import io.github.muthuishere.toolnexus.annotations.Param;
import io.github.muthuishere.toolnexus.annotations.ToolMethod;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ACP (Agent Client Protocol) as the model behind the unified client (issue #96, ADR 0025):
 * a local coding-agent CLI — {@code devin acp} or {@code opencode acp} — is spawned ONCE, and
 * every turn below reuses that same warm session instead of paying process-startup cost again.
 *
 * <p><b>Honesty check, read before you time anything:</b> whatever speedup turn 2/3 show over
 * turn 1 here is the CLI's own process-startup cost amortised across turns of ONE warm session —
 * it is NOT a protocol-level speedup, and ACP itself adds framing overhead, not less. This
 * example needs the agent CLI installed and already authenticated on this machine (real `devin`
 * or `opencode` credentials), so it is not hermetic and CI does not run it — see
 * {@code AcpClientTest} for the hermetic, fake-server-backed coverage of the same client.
 *
 * <p>Agent CLI is selectable, not hardcoded:
 * <pre>
 *   gradle runAcp                                   # default: devin acp
 *   ACP_AGENT_CMD=opencode ACP_AGENT_ARGS=acp gradle runAcp   # or: opencode acp
 *   gradle runAcp --args="opencode acp"             # or pass the whole command as args
 * </pre>
 */
public final class Acp {

    /** A plain object with an annotated tool method — proves the tool-calling loop is
     * unchanged: the ACP agent calls this exactly like it would call any MCP/HTTP/native tool. */
    public static final class ClockTools {
        @ToolMethod(name = "clock", description = "Return the current UTC time (ISO-8601).")
        public String clock() {
            return Instant.now().toString();
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> command = resolveCommand(args);
        System.out.println("ACP agent command: " + String.join(" ", command));

        AcpClient client;
        try {
            client = AcpClient.start(command);
        } catch (Exception e) {
            System.out.println("(could not spawn ACP agent " + command + " — is it installed and on PATH? "
                    + e.getMessage() + ")");
            System.exit(0);
            return;
        }

        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
            for (Tool t : Tools.fromObject(new ClockTools())) {
                tk.register(t);
            }

            LlmClient agent = InProcess.createClient(new InProcess.Options()
                    .model("acp-agent")
                    .generate(client)
                    .systemPrompt("You are a precise agent. Use the clock tool when asked for the time."));

            String[] turns = {
                    "What time is it right now? Use the clock tool.",
                    "Thanks. Now just say 'ready' — no tool needed.",
            };

            for (int i = 0; i < turns.length; i++) {
                long start = System.nanoTime();
                LlmClient.RunResult res = agent.run(turns[i], tk);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                System.out.println("turn " + (i + 1) + " (" + elapsedMs + "ms), session=" + client.sessionId()
                        + ": " + clip(res.text, 120));
            }
        } finally {
            client.close();
        }

        System.out.println("\nOK ACP-backed client completed its turns on one warm session");
    }

    /**
     * Resolve the ACP agent's command line: CLI args win if given (a whole command, e.g.
     * {@code opencode acp}), else {@code ACP_AGENT_CMD}/{@code ACP_AGENT_ARGS} env vars, else
     * the default {@code devin acp}. {@code opencode acp} works exactly the same way — swap
     * the command, nothing else in this example changes.
     */
    static List<String> resolveCommand(String[] args) {
        if (args != null && args.length > 0) {
            return new ArrayList<>(Arrays.asList(args));
        }
        String cmd = System.getenv("ACP_AGENT_CMD");
        if (cmd == null || cmd.isEmpty()) cmd = "devin";
        String argsEnv = System.getenv("ACP_AGENT_ARGS");
        if (argsEnv == null || argsEnv.isEmpty()) argsEnv = "acp";
        List<String> full = new ArrayList<>();
        full.add(cmd);
        for (String a : argsEnv.trim().split("\\s+")) {
            if (!a.isEmpty()) full.add(a);
        }
        return full;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ");
        return oneLine.length() > n ? oneLine.substring(0, n) : oneLine;
    }
}
