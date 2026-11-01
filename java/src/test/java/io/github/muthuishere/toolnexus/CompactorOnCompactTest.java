package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Change add-go-host-asks (ask #6), Java port of {@code TestCompactorOnCompact},
 * {@code TestCompactorKeepsSoleUserPrompt}, {@code TestCompactorNoOpWhenNotSmaller} and
 * {@code TestCompactorFoldedIsVerbatim} (golang/agents/compaction_test.go).
 */
class CompactorOnCompactTest {

    static final Function<List<Object>, String> SUMMARIZE = older -> "summarized " + older.size() + " messages";

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
        return out;
    }

    static Map<String, Object> callMsg(String id) {
        return m("role", "assistant", "content", null, "tool_calls", List.of(
                m("id", id, "type", "function", "function", m("name", "lookup", "arguments", "{}"))));
    }

    static List<Object> transcript(int userTurns) {
        String pad = "pad ".repeat(40), data = "data ".repeat(40);
        List<Object> out = new ArrayList<>();
        out.add(m("role", "system", "content", "You are Ava. SOUL."));
        for (int i = 0; i < userTurns; i++) {
            String id = "c" + i;
            out.add(m("role", "user", "content", "question " + i + " " + pad));
            out.add(callMsg(id));
            out.add(m("role", "tool", "tool_call_id", id, "content", "result " + i + " " + data));
            out.add(m("role", "assistant", "content", "answer " + i));
        }
        return out;
    }

    static LlmClient.BeforeLLMEvent ev(List<Object> msgs) {
        return new LlmClient.BeforeLLMEvent(msgs, List.of(), "m", 0);
    }

    @Test
    void onCompactReportsWhatChanged() {
        List<Compaction.CompactInfo> got = new ArrayList<>();
        var hook = Compaction.compactor(new Compaction.Options().maxTokens(50).keepTail(20).summarize(SUMMARIZE)
                .onCompact(got::add));
        List<Object> msgs = transcript(10);
        LlmClient.LLMOverride out = hook.apply(ev(msgs));
        assertNotNull(out);
        assertEquals(1, got.size());
        Compaction.CompactInfo i = got.get(0);
        assertEquals(msgs.size(), i.messagesBefore());
        assertEquals(out.messages().size(), i.messagesAfter());
        assertTrue(i.summarized() > 0);
        assertEquals(Compaction.estimateTokens(msgs), i.tokensBefore());
        assertEquals(Compaction.estimateTokens(out.messages()), i.tokensAfter());
        assertTrue(i.tokensAfter() < i.tokensBefore());
        assertTrue(!i.summary().isEmpty());

        var big = Compaction.compactor(new Compaction.Options().maxTokens(1 << 30).summarize(SUMMARIZE)
                .onCompact(x -> fail("onCompact on a no-op")));
        assertNull(big.apply(ev(msgs)));
    }

    @Test
    void keepsSoleUserPrompt() {
        String data = "data ".repeat(40);
        Map<String, Object> prompt = m("role", "user", "content", "do the task");
        List<Object> msgs = new ArrayList<>(List.of(m("role", "system", "content", "SOUL"), prompt));
        for (int i = 0; i < 8; i++) {
            msgs.add(callMsg("c" + i));
            msgs.add(m("role", "tool", "tool_call_id", "c" + i, "content", data));
        }
        Compaction.CompactInfo[] info = new Compaction.CompactInfo[1];
        var hook = Compaction.compactor(new Compaction.Options().maxTokens(100).keepTail(20).summarize(SUMMARIZE)
                .onCompact(x -> info[0] = x));
        LlmClient.LLMOverride out = hook.apply(ev(msgs));
        assertNotNull(out);
        assertEquals(3, out.messages().size(), "want [system, prompt, summary]: " + out.messages());
        assertEquals("do the task", ((Map<?, ?>) out.messages().get(1)).get("content"));
        assertEquals(msgs.size() - 2, info[0].folded().size());
    }

    @Test
    void noOpWhenNotSmaller() {
        List<Object> msgs = transcript(2);
        var hook = Compaction.compactor(new Compaction.Options().maxTokens(10).keepTail(5)
                .summarize(older -> "long summary ".repeat(500))
                .onCompact(x -> fail("onCompact on a no-op")));
        assertNull(hook.apply(ev(msgs)));
    }

    @Test
    void foldedIsVerbatim() {
        List<Object> msgs = transcript(10);
        Compaction.CompactInfo[] info = new Compaction.CompactInfo[1];
        var hook = Compaction.compactor(new Compaction.Options().maxTokens(50).keepTail(20).summarize(SUMMARIZE)
                .onCompact(x -> info[0] = x));
        assertNotNull(hook.apply(ev(msgs)));
        assertEquals(info[0].summarized(), info[0].folded().size());
        assertTrue(info[0].folded().size() > 0);
        for (int i = 0; i < info[0].folded().size(); i++) {
            assertTrue(info[0].folded().get(i) == msgs.get(1 + i), "folded[" + i + "] is the input message itself");
        }
    }
}
