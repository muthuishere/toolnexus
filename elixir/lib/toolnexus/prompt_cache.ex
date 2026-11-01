defmodule Toolnexus.PromptCache do
  @moduledoc """
  Prompt caching (SPEC §4C "Prompt caching", change `add-prompt-cache`).

  Set `Client.create(prompt_cache: %{key: "session-1", long_ttl: false})` and the
  client marks the stable request prefix for the provider's cache:

    * **anthropic** — `cache_control: %{"type" => "ephemeral"}` (plus `"ttl" => "1h"`
      with `long_ttl: true`) on the last tool, on the system prompt (a string becomes
      one text block) and on the last content block of the latest message — 3 of the
      4 breakpoints Anthropic allows.
    * **openai** — `prompt_cache_key = key` (when set). The prefix is cached automatically.
    * gemini — nothing yet (explicit `cachedContent` is a separate resource; deferred).

  It is applied before `request_params` and `body_transform`, so both can still
  override it. Absent ⇒ the body is byte-identical. The transcript is never touched:
  only the assembled request body is rewritten.
  """

  @doc false
  @spec apply(map(), String.t(), map() | keyword()) :: map()
  def apply(body, "anthropic", opts) do
    opts = Map.new(opts)
    mark = %{"type" => "ephemeral"}
    mark = if opts[:long_ttl], do: Map.put(mark, "ttl", "1h"), else: mark

    body
    |> mark_last("tools", mark)
    |> mark_system(mark)
    |> mark_latest_message(mark)
  end

  def apply(body, "openai", opts) do
    case Map.new(opts)[:key] do
      k when is_binary(k) and k != "" -> Map.put(body, "prompt_cache_key", k)
      _ -> body
    end
  end

  def apply(body, _style, _opts), do: body

  defp mark_last(body, key, mark) do
    case body[key] do
      [_ | _] = list -> Map.put(body, key, List.update_at(list, -1, &with_mark(&1, mark)))
      _ -> body
    end
  end

  defp mark_system(body, mark) do
    case body["system"] do
      s when is_binary(s) and s != "" ->
        Map.put(body, "system", [%{"type" => "text", "text" => s, "cache_control" => mark}])

      [_ | _] ->
        mark_last(body, "system", mark)

      _ ->
        body
    end
  end

  defp mark_latest_message(body, mark) do
    case body["messages"] do
      [_ | _] = msgs ->
        Map.put(body, "messages", List.update_at(msgs, -1, &mark_content(&1, mark)))

      _ ->
        body
    end
  end

  defp mark_content(%{"content" => c} = m, mark) when is_binary(c),
    do: Map.put(m, "content", [%{"type" => "text", "text" => c, "cache_control" => mark}])

  defp mark_content(%{"content" => [_ | _] = blocks} = m, mark),
    do: Map.put(m, "content", List.update_at(blocks, -1, &with_mark(&1, mark)))

  defp mark_content(m, _mark), do: m

  defp with_mark(v, mark) when is_map(v), do: Map.put(v, "cache_control", mark)
  defp with_mark(v, _mark), do: v
end
