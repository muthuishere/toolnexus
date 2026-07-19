using System.Text.Encodings.Web;
using System.Text.Json;

namespace Toolnexus.Agents;

/// <summary>
/// Context compaction (SPEC §7F). A long-lived agent grows its transcript until it overflows the
/// model's window. <see cref="Compactor"/> returns a <c>beforeLLM</c> hook (§8) that summarizes the
/// older transcript and keeps a recent tail. It rides the existing seam — the loop applies a
/// <c>beforeLLM</c> rewrite by REPLACING the working transcript, and that array flows into
/// <c>RunResult.Messages</c> and the <c>ConversationStore</c> — so compaction is a pure
/// <c>messages → messages</c> helper that adds no loop behavior.
///
/// Invariants (do not regress):
/// <list type="number">
///   <item>At/below <c>MaxTokens</c> ⇒ no-op (returns <c>null</c>), byte-identical to no compactor.</item>
///   <item>The retained tail begins at a <c>user</c> turn (tool-pair safety — a <c>tool</c> message
///     is never orphaned from the <c>assistant</c> carrying its <c>tool_call_id</c>); fallback to
///     the most recent user turn (safety over size).</item>
///   <item>A leading <c>system</c> message is preserved verbatim.</item>
///   <item>Pluggable <c>Summarize</c> (MAY call an LLM) + <c>CountTokens</c> (default
///     <c>ceil(chars/4)</c> summed over messages).</item>
///   <item>Optional <c>FlushToMemory</c> injects a pre-compact reminder to persist durable facts.</item>
/// </list>
/// </summary>
public static class Compaction
{
    /// <summary>Message list as the hook sees it: a <see cref="List{T}"/> of JSON-object messages.</summary>
    public delegate string SummarizeFn(IReadOnlyList<object?> older);

    /// <summary>Token estimator over a message list.</summary>
    public delegate int CountTokensFn(IReadOnlyList<object?> messages);

    /// <summary>Options for <see cref="Compactor"/>.</summary>
    public sealed class Options
    {
        /// <summary>Compact only when the estimate exceeds this; at/below ⇒ no-op, byte-identical.</summary>
        public required int MaxTokens { get; init; }

        /// <summary>Keep at least this many tokens of the most recent tail. Default <c>MaxTokens / 2</c>.</summary>
        public int? KeepTail { get; init; }

        /// <summary>Produces the summary of the older messages. MAY call an LLM. Required.</summary>
        public required SummarizeFn Summarize { get; init; }

        /// <summary>Token estimator; default <see cref="EstimateTokens"/> (<c>ceil(chars/4)</c>).</summary>
        public CountTokensFn? CountTokens { get; init; }

        /// <summary>Inject a pre-compact reminder to flush durable notes to memory (§7E). Default off.</summary>
        public bool FlushToMemory { get; init; }
    }

    // Match JS's ceil(len(JSON.stringify(m))/4): compact (no indentation) and DON'T HTML-escape
    // < > & or non-ASCII, so a byte-length estimate lines up with the other ports.
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        WriteIndented = false,
    };

    /// <summary>
    /// Cheap, deterministic token estimate: for each message, <c>ceil(len(JSON) / 4)</c>, summed.
    /// The SAME rule as the JS reference (<c>sum ceil(JSON.stringify(m).length / 4)</c>). An
    /// estimator, not a tokenizer — override via <see cref="Options.CountTokens"/> for exactness.
    /// </summary>
    public static int EstimateTokens(IReadOnlyList<object?> messages)
    {
        var n = 0;
        foreach (var m in messages)
        {
            var len = JsonSerializer.Serialize(m, SerializerOptions).Length;
            n += (len + 3) / 4; // ceil(len / 4)
        }
        return n;
    }

    /// <summary>
    /// Build a <c>beforeLLM</c> hook that compacts the transcript when it grows too large.
    /// Returns an <see cref="LlmClient.LLMOverride"/> (replacing the transcript) only when it
    /// actually compacts; otherwise <c>null</c> (no-op).
    /// </summary>
    public static Func<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride?> Compactor(Options opts)
    {
        var count = opts.CountTokens ?? EstimateTokens;
        var keepTail = opts.KeepTail ?? opts.MaxTokens / 2;

        return ev =>
        {
            var msgs = ev.Messages;
            if (count(msgs) <= opts.MaxTokens) return null; // under budget → byte-identical no-op

            // Preserve a leading system prompt verbatim (identity / soul / skills — never summarized).
            var head0 = Role(msgs.Count > 0 ? msgs[0] : null) == "system" ? 1 : 0;
            var system = msgs.Take(head0).ToList();

            // Find the split: the LARGEST tail (from a clean USER boundary) that fits keepTail.
            // Scanning to a user turn guarantees tool-pair safety — a tail starting at a user
            // message can never orphan a `tool` result from its `tool_calls`.
            var split = msgs.Count;
            for (var i = msgs.Count - 1; i > head0; i--)
            {
                var tailTokens = count(Slice(msgs, i));
                if (Role(msgs[i]) == "user" && tailTokens <= keepTail) split = i;
                if (tailTokens > keepTail) break;
            }
            // If no clean user boundary was found in range, fall back to the most recent user turn so
            // we still never split a tool group (may keep more than keepTail — safety over size).
            if (split == msgs.Count)
            {
                for (var i = msgs.Count - 1; i > head0; i--)
                {
                    if (Role(msgs[i]) == "user") { split = i; break; }
                }
            }
            if (split <= head0) return null; // nothing safely compactible

            var older = Slice(msgs, head0, split);
            var tail = Slice(msgs, split);
            if (older.Count == 0) return null;

            var result = new List<object?>(system);
            var summary = opts.Summarize(older);
            result.Add(new Dictionary<string, object?>
            {
                ["role"] = "system",
                ["content"] = $"[Summary of earlier conversation]\n{summary}",
            });
            if (opts.FlushToMemory)
            {
                result.Add(new Dictionary<string, object?>
                {
                    ["role"] = "system",
                    ["content"] = "Before continuing: if anything from earlier is worth keeping, save it "
                        + "with the memory tool now — the earlier transcript is about to be summarized.",
                });
            }
            result.AddRange(tail);
            return new LlmClient.LLMOverride(Messages: result);
        };
    }

    private static string? Role(object? message)
        => (message as IDictionary<string, object?>)?.Get("role") as string;

    /// <summary>msgs[from..] — the suffix starting at <paramref name="from"/>.</summary>
    private static List<object?> Slice(IReadOnlyList<object?> msgs, int from)
        => Slice(msgs, from, msgs.Count);

    /// <summary>msgs[from..to].</summary>
    private static List<object?> Slice(IReadOnlyList<object?> msgs, int from, int to)
    {
        var outList = new List<object?>(to - from);
        for (var i = from; i < to; i++) outList.Add(msgs[i]);
        return outList;
    }
}
