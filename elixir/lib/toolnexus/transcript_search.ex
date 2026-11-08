defmodule Toolnexus.TranscriptSearch do
  @moduledoc """
  `transcript_search` — a read-only tool over a conversation store (SPEC §7E "Memory
  hierarchy" → transcript search). The ground-truth tier of agent memory: a dreaming
  persona checks its notes against what actually happened, not against its own notes.

  The store is any `Toolnexus.Client.ConversationStore` struct whose module also
  exports `list/1` (both shipped stores do). Messages may be plain maps or
  `Jason.OrderedObject`s; a `tool_use` input is encoded as compact JSON with object keys
  sorted by code point at every depth (§7E), so stored key order never matters.
  """

  alias Toolnexus.ToolResult

  @description "Search past conversation transcripts — the ground truth of what actually happened. " <>
                 "Case-insensitive substring match over message text. Returns one line per matching " <>
                 "message: <conversationId>#<index> <role>: <snippet>. Read-only."

  @doc "The tool description (byte-exact)."
  @spec description() :: String.t()
  def description, do: @description

  @doc """
  Build the tool over `store`. Raises `ArgumentError` unless the store's module exports
  `list/1`, or when `:max_results`/`:snippet_chars` is not an integer `>= 1`.

  Options: `:max_results` (20), `:snippet_chars` (200), `:name` (`"transcript_search"`).
  """
  @spec tool(term(), keyword() | map()) :: Toolnexus.Tool.t()
  def tool(store, opts \\ []) do
    unless listable?(store),
      do: raise(ArgumentError, "transcriptSearchTool: the store must implement list()")

    opts = Map.new(opts)
    max_results = Map.get(opts, :max_results, 20)
    snippet_chars = Map.get(opts, :snippet_chars, 200)

    unless is_integer(max_results) and max_results >= 1,
      do: raise(ArgumentError, "transcriptSearchTool: maxResults must be an integer >= 1")

    unless is_integer(snippet_chars) and snippet_chars >= 1,
      do: raise(ArgumentError, "transcriptSearchTool: snippetChars must be an integer >= 1")

    Toolnexus.define_tool(%{
      name: Map.get(opts, :name, "transcript_search"),
      description: @description,
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "query" => %{
            "type" => "string",
            "description" => "Text to find (case-insensitive substring)."
          },
          "conversationId" => %{
            "type" => "string",
            "description" => "Search only this conversation."
          },
          "limit" => %{
            "type" => "integer",
            "description" => "Most hits to return (default and max #{max_results})."
          }
        },
        "required" => ["query"]
      },
      execute: fn args -> search(store, args, max_results, snippet_chars) end
    })
  end

  defp listable?(%mod{}), do: Code.ensure_loaded?(mod) and function_exported?(mod, :list, 1)
  defp listable?(_), do: false

  defp search(%mod{} = store, args, max_results, snippet_chars) do
    query = if is_binary(args["query"]), do: normalize(args["query"]), else: ""

    with {:query, false} <- {:query, query == ""},
         {:ok, limit} <- limit(Map.fetch(args, "limit"), max_results) do
      ids =
        case args["conversationId"] do
          id when is_binary(id) -> [id]
          _ -> Enum.map(mod.list(store), & &1.id)
        end

      needle = query |> fold() |> String.codepoints()

      {hits, total} =
        Enum.reduce(ids, {[], 0}, fn id, acc ->
          (mod.get(store, id) || [])
          |> Enum.with_index()
          |> Enum.reduce(acc, fn {msg, index}, {hits, total} ->
            cps = msg |> message_text() |> String.codepoints()

            case index_of(Enum.map(cps, &fold/1), needle) do
              nil ->
                {hits, total}

              at when length(hits) < limit ->
                role = if is_binary(field(msg, "role")), do: field(msg, "role"), else: "unknown"

                line =
                  "#{id}##{index} #{role}: #{snippet(cps, at, length(needle), snippet_chars)}"

                {[line | hits], total + 1}

              _ ->
                {hits, total + 1}
            end
          end)
        end)

      hits = Enum.reverse(hits)

      cond do
        total == 0 -> "no matches"
        total > length(hits) -> Enum.join(hits ++ ["(+#{total - length(hits)} more)"], "\n")
        true -> Enum.join(hits, "\n")
      end
    else
      {:query, true} -> %ToolResult{output: "query is required", is_error: true}
      :bad_limit -> %ToolResult{output: "limit must be an integer >= 1", is_error: true}
    end
  end

  # absent ⇒ the default; a present JSON null is an error (§7E).
  defp limit(:error, max), do: {:ok, max}
  defp limit({:ok, n}, max) when is_integer(n) and n >= 1, do: {:ok, min(n, max)}

  defp limit({:ok, n}, max) when is_float(n) and n >= 1 and n == trunc(n) / 1,
    do: {:ok, min(trunc(n), max)}

  defp limit(_, _), do: :bad_limit

  @doc """
  The searchable text of one stored message: string content, text parts, `tool_use`
  input as compact JSON, `tool_result` content (recursively), then each OpenAI tool
  call's `arguments` string — joined by "\\n", then whitespace-normalized.
  """
  @spec message_text(term()) :: String.t()
  def message_text(msg) do
    if is_map(msg) do
      calls =
        case field(msg, "tool_calls") do
          list when is_list(list) ->
            for tc <- list,
                is_map(tc),
                fun = field(tc, "function"),
                is_map(fun),
                a = field(fun, "arguments"),
                is_binary(a),
                do: a

          _ ->
            []
        end

      normalize(Enum.join(parts_text(field(msg, "content")) ++ calls, "\n"))
    else
      ""
    end
  end

  defp parts_text(s) when is_binary(s), do: [s]

  defp parts_text(list) when is_list(list) do
    Enum.flat_map(list, fn
      part when is_map(part) ->
        case field(part, "type") do
          "text" -> if is_binary(field(part, "text")), do: [field(part, "text")], else: []
          "tool_use" -> [Jason.encode!(sort_keys(field(part, "input") || %{}))]
          "tool_result" -> parts_text(field(part, "content"))
          _ -> []
        end

      _ ->
        []
    end)
  end

  defp parts_text(_), do: []

  # Object keys sorted by code point (UTF-8 byte order is code-point order) at every
  # depth; lists keep their order (§7E).
  defp sort_keys(%Jason.OrderedObject{values: kvs}), do: sort_pairs(kvs)
  defp sort_keys(%_{} = s), do: s
  defp sort_keys(m) when is_map(m), do: sort_pairs(Map.to_list(m))
  defp sort_keys(l) when is_list(l), do: Enum.map(l, &sort_keys/1)
  defp sort_keys(v), do: v

  defp sort_pairs(kvs) do
    kvs
    |> Enum.map(fn {k, v} -> {to_string(k), sort_keys(v)} end)
    |> Enum.sort_by(&elem(&1, 0))
    |> Jason.OrderedObject.new()
  end

  # Plain maps and Jason.OrderedObject (which implements Access) read the same way.
  defp field(%Jason.OrderedObject{} = o, k), do: o[k]
  defp field(%_{}, _k), do: nil
  defp field(m, k) when is_map(m), do: Map.get(m, k)

  # Collapse every run of ASCII whitespace to one space, then trim.
  defp normalize(s) do
    s
    |> String.replace(~r/[ \t\n\r\f\x0b]+/, " ")
    |> String.trim_leading(" ")
    |> String.trim_trailing(" ")
  end

  # ASCII-only case fold — never Unicode lowercasing.
  defp fold(s), do: for(<<c <- s>>, into: "", do: <<if(c in ?A..?Z, do: c + 32, else: c)>>)

  defp index_of(hay, needle), do: index_of(hay, needle, length(hay) - length(needle), 0)

  defp index_of(hay, needle, last, i) do
    cond do
      i > last -> nil
      List.starts_with?(hay, needle) -> i
      true -> index_of(tl(hay), needle, last, i + 1)
    end
  end

  defp snippet(cps, at, qlen, size) do
    len = length(cps)

    if len <= size do
      Enum.join(cps)
    else
      start = (at + div(qlen, 2) - div(size, 2)) |> min(len - size) |> max(0)
      stop = start + size

      if(start > 0, do: "…", else: "") <>
        Enum.join(Enum.slice(cps, start, size)) <> if(stop < len, do: "…", else: "")
    end
  end
end
