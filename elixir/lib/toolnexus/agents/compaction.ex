defmodule Toolnexus.Agents.Compaction do
  @moduledoc """
  Context compaction (SPEC §7F) as a `before_llm` helper — no core-loop change.

  A long-lived agent grows its transcript until it overflows the model's window.
  `compactor/1` returns a `before_llm` hook (§8) that summarizes the older transcript
  and keeps a recent tail. It rides the existing seam: the loop applies a `before_llm`
  message rewrite by **replacing the working transcript**, and that list flows into
  `RunResult.messages` and the `ConversationStore` — so compaction is a pure
  `messages → messages` helper that adds no loop behavior.

  Below `max_tokens` the hook is a **no-op** — it returns `nil`, the loop keeps the
  transcript unchanged, and the run is byte-identical to one with no compactor. Above
  it, the transcript becomes:

      [leading system prompt (verbatim), summary system message, (flush reminder?), …tail]

  Two invariants hold:

    * **Tool-pair safety.** The retained tail begins at a `user` turn, so no `tool`
      message is ever orphaned from the `assistant` carrying its `tool_call_id`. The
      split is the largest user-boundary tail that fits `keep_tail`; if none fits, it
      extends to the most recent user turn (safety over size).
    * **System prompt preserved.** A leading `system` message (identity / soul / skills)
      is kept unchanged; only the body between it and the tail is summarized.

  Messages are plain maps with **string** keys (`"role"`, `"content"`, `"tool_calls"`,
  `"tool_call_id"`), matching the transcripts `Toolnexus.Client` builds.

  ## Options (keyword list)

    * `:max_tokens` — compact only when the estimate exceeds this; at/below ⇒ no-op. **Required.**
    * `:keep_tail` — keep at least this many tokens of the most recent tail (default `div(max_tokens, 2)`).
    * `:summarize` — `(older :: [map] -> String.t())`; produces the summary. MAY call an
      LLM — the library makes no model call on the host's behalf by default. **Required.**
    * `:count_tokens` — `([map] -> non_neg_integer())`; token estimate, default `estimate_tokens/1`
      (`ceil(byte_size(json)/4)` summed over messages — an estimator, not a tokenizer).
    * `:flush_to_memory` — when set, inject a pre-compact system reminder to persist durable
      facts via the §7E `memory` tool before the head is summarized (off by default).
  """

  @flush_note "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized."
  @summary_prefix "[Summary of earlier conversation]\n"

  @doc """
  Cheap, deterministic token estimate: `ceil(byte_size(json)/4)` summed over messages.

  This is an estimator, not a tokenizer — override it via the `:count_tokens` option
  when exactness matters.
  """
  @spec estimate_tokens([map()]) :: non_neg_integer()
  def estimate_tokens(messages) when is_list(messages) do
    Enum.reduce(messages, 0, fn m, acc -> acc + ceil_div(byte_size(Jason.encode!(m)), 4) end)
  end

  @doc """
  Build a `before_llm` hook that compacts the transcript when it grows too large.

  Returns a 1-arity function taking the `before_llm` event
  (`%{messages:, tools:, model:, turn:}`). It returns `%{messages: compacted}` only when
  it actually compacts; otherwise `nil` (a no-op the loop leaves untouched).
  """
  @spec compactor(keyword()) :: (map() -> %{messages: [map()]} | nil)
  def compactor(opts) do
    max_tokens = Keyword.fetch!(opts, :max_tokens)
    summarize = Keyword.fetch!(opts, :summarize)
    count = Keyword.get(opts, :count_tokens, &estimate_tokens/1)
    keep_tail = Keyword.get(opts, :keep_tail, div(max_tokens, 2))
    flush? = Keyword.get(opts, :flush_to_memory, false)

    fn %{messages: msgs} ->
      compact(msgs, max_tokens, keep_tail, count, summarize, flush?)
    end
  end

  # --- internals ---------------------------------------------------------------

  defp compact(msgs, max_tokens, keep_tail, count, summarize, flush?) do
    # Under budget → byte-identical no-op.
    if count.(msgs) <= max_tokens do
      nil
    else
      len = length(msgs)
      # Preserve a leading system prompt verbatim (never summarized).
      head0 = if role_at(msgs, 0) == "system", do: 1, else: 0

      split = find_split(msgs, len, head0, keep_tail, count)

      if split <= head0 do
        # Nothing safely compactible above the preserved head.
        nil
      else
        # split > head0 ⇒ `older` is always non-empty.
        older = Enum.slice(msgs, head0, split - head0)
        tail = Enum.drop(msgs, split)
        system = Enum.take(msgs, head0)
        summary_msg = %{"role" => "system", "content" => @summary_prefix <> summarize.(older)}
        flush = if flush?, do: [%{"role" => "system", "content" => @flush_note}], else: []
        %{messages: system ++ [summary_msg] ++ flush ++ tail}
      end
    end
  end

  # Find the split index: the largest tail starting at a USER boundary that fits keep_tail.
  # Scanning to a user turn guarantees tool-pair safety. If no clean boundary fits within
  # keep_tail, fall back to the most recent user turn (favoring safety over size).
  defp find_split(msgs, len, head0, keep_tail, count) do
    case scan_down(msgs, len - 1, head0, keep_tail, count, len) do
      ^len -> fallback_user(msgs, len - 1, head0, len)
      split -> split
    end
  end

  defp scan_down(_msgs, i, head0, _keep, _count, split) when i <= head0, do: split

  defp scan_down(msgs, i, head0, keep, count, split) do
    tail_count = count.(Enum.drop(msgs, i))
    split = if role_at(msgs, i) == "user" and tail_count <= keep, do: i, else: split

    if tail_count > keep do
      split
    else
      scan_down(msgs, i - 1, head0, keep, count, split)
    end
  end

  defp fallback_user(_msgs, i, head0, default) when i <= head0, do: default

  defp fallback_user(msgs, i, head0, default) do
    if role_at(msgs, i) == "user",
      do: i,
      else: fallback_user(msgs, i - 1, head0, default)
  end

  defp role_at(msgs, i) do
    case Enum.at(msgs, i) do
      %{"role" => r} -> r
      _ -> nil
    end
  end

  defp ceil_div(n, d), do: div(n + d - 1, d)
end
