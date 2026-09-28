package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Batteries.OnError;
import io.github.muthuishere.toolnexus.Judge.Answer;
import io.github.muthuishere.toolnexus.Judge.Named;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Keeps the parts of a tool's output relevant to the query (SPEC §8B Batteries): one
 * {@code noul} per chunk keyed {@code "0"}, {@code "1"}, …; band no drops.
 */
public final class ToolResultFilterClassifier {

    private static final String SEP = "\n\n";

    public static final class Options extends Batteries.PolicyOptions<Options> {}

    /** {@code kept} / {@code dropped} are chunk indices in order. */
    public record Verdict(List<Integer> kept, List<Integer> dropped, boolean calibrated, String error) {}

    private final Classifier c;
    private final Options opts;

    public ToolResultFilterClassifier(Classifier c, Options o) {
        Batteries.requireOnError("ToolResultFilter", o == null ? null : o.onError);
        this.c = c;
        this.opts = o;
    }

    /** {@code query} is a string or an object. */
    public Verdict filter(Object query, List<String> chunks) {
        List<Integer> kept = new ArrayList<>(), dropped = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) return new Verdict(kept, dropped, true, null);
        Map<String, Object> cm = new LinkedHashMap<>();
        List<Named> qs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String k = Integer.toString(i);
            cm.put(k, chunks.get(i));
            qs.add(Judge.noul(k, "Is `chunks." + k + "` relevant to `query`?", new Classifier.NoulCriteria(
                    "this part helps answer the query", "this part does not help answer the query")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("chunks", cm);
        Batteries.Asked a;
        try {
            a = Batteries.ask(c, Batteries.state(Batteries.roleOr(opts.role, Batteries.ROLE_TOOL_RESULT_FILTER), data), qs, opts.bands);
        } catch (RuntimeException e) {
            for (int i = 0; i < chunks.size(); i++) (opts.onError == OnError.OPEN ? kept : dropped).add(i);
            return new Verdict(kept, dropped, false, Batteries.message(e));
        }
        for (int i = 0; i < chunks.size(); i++) {
            Answer x = a.answers().get(Integer.toString(i));
            (x != null && x.band() == Judge.Band.NO ? dropped : kept).add(i);
        }
        return new Verdict(kept, dropped, a.calibrated(), null);
    }

    /**
     * An {@code afterTool} hook: a non-error text result with ≥ 2 {@code "\n\n"} chunks and no
     * content parts keeps only the relevant chunks. {@code next} sees the filtered result and its
     * override wins. {@code next} may be null.
     */
    public Function<LlmClient.AfterToolEvent, LlmClient.ToolOverride> asHook(
            Function<LlmClient.AfterToolEvent, LlmClient.ToolOverride> next) {
        return ev -> {
            LlmClient.ToolOverride own = null;
            ToolResult r = ev.result();
            String[] chunks = r.output().split(Pattern.quote(SEP), -1);
            if (!r.isError() && r.parts() == null && chunks.length >= 2) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("tool", ev.name());
                q.put("arguments", ev.args() == null ? Map.of() : ev.args());
                Verdict v = filter(q, List.of(chunks));
                if (!v.dropped().isEmpty()) {
                    List<String> keep = new ArrayList<>();
                    for (int i : v.kept()) keep.add(chunks[i]);
                    own = LlmClient.ToolOverride.withResult(
                            new ToolResult(String.join(SEP, keep), r.isError(), r.metadata(), r.parts()));
                }
            }
            if (next == null) return own;
            LlmClient.AfterToolEvent seen = own == null ? ev
                    : new LlmClient.AfterToolEvent(ev.name(), ev.args(), own.result(), ev.id(), ev.turn());
            LlmClient.ToolOverride nx = next.apply(seen);
            return nx == null || nx.result() == null ? own : nx;
        };
    }
}
