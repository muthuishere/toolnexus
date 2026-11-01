package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolHints;
import io.github.muthuishere.toolnexus.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * go-agent-cli follow-up to ask #7, Java port of {@code golang/agents/astool_ctx_test.go}:
 * asTool is cancelled with the calling turn, and asToolWith runs the child under the host's gate
 * (hooks) and ask bridge (runtime waitFor).
 */
class AsToolCtxTest {

    /** A model that never answers until its thread is interrupted. */
    static InProcess.Response blocking(InProcess.Request req) {
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return InProcess.Response.content("too late");
    }

    @Test
    void asToolCancelledByInterrupt() throws Exception {
        Agents.Agent child = Agents.agent("child", new Agents.AgentSpec().does("d").soul("s"));
        Tool tool = child.asTool(new RuntimeOptions().inProcess(AsToolCtxTest::blocking));
        AtomicReference<ToolResult> got = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> got.set(tool.execute(Map.of("prompt", "go"), new ToolContext())));
        Thread.sleep(100);
        long t0 = System.currentTimeMillis();
        caller.interrupt();
        caller.join(3000);
        assertFalse(caller.isAlive(), "child not cancelled");
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        assertTrue(got.get().isError() && got.get().output().startsWith("cancelled"), got.get().output());
    }

    @Test
    void asToolCancelledByToolContext() {
        Agents.Agent child = Agents.agent("child", new Agents.AgentSpec().does("d").soul("s"));
        Tool tool = child.asTool(new RuntimeOptions().inProcess(AsToolCtxTest::blocking));
        ToolContext ctx = new ToolContext();
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            ctx.cancel();
        });
        long t0 = System.currentTimeMillis();
        ToolResult r = tool.execute(Map.of("prompt", "go"), ctx);
        assertTrue(System.currentTimeMillis() - t0 < 3000, "child not cancelled");
        assertTrue(r.isError() && r.output().startsWith("cancelled"), r.output());
    }

    @Test
    void asToolWithGateAndAskBridge() {
        AtomicBoolean ran = new AtomicBoolean();
        Tool danger = Tool.withHints(NativeTool.of("danger", "mutates", null, (Map<String, Object> a) -> {
            ran.set(true);
            return "did it";
        }), ToolHints.DESTRUCTIVE);
        Agents.Agent child = Agents.agent("child", new Agents.AgentSpec().does("d").soul("s").tools(danger));
        List<String> gated = new CopyOnWriteArrayList<>();
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Tool tool = child.asToolWith(ctx -> new RuntimeOptions()
                .inProcess(req -> calls.incrementAndGet() == 1
                        ? InProcess.Response.toolCalls(new InProcess.ToolCall("danger", Map.of()).id("d1"))
                        : InProcess.Response.content("child done"))
                .hooks(new LlmClient.Hooks().beforeTool(ev -> {
                    gated.add(ev.name());
                    if (ev.hints() == null || !ev.hints().readOnly()) {
                        return LlmClient.ToolOverride.withResult(ToolResult.pending(
                                new Request("r1", "approval", "allow " + ev.name() + "?")));
                    }
                    return null;
                }))
                .waitFor(req -> {
                    asked.incrementAndGet();
                    return new Answer(req.id(), true);
                }));
        ToolResult r = tool.execute(Map.of("prompt", "go"), new ToolContext());
        assertFalse(r.isError(), r.output());
        assertEquals("child done", r.output());
        assertEquals(List.of("danger"), gated);
        assertEquals(1, asked.get());
        assertTrue(ran.get());
    }
}
