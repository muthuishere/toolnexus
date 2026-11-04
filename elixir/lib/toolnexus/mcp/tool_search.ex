defmodule Toolnexus.Mcp.ToolSearch do
  @moduledoc """
  Lazy tool exposure (SPEC §2.8): BM25 over a server's tools — the name counts
  twice (split on `_` and camelCase) plus the description.
  """

  @doc "`search_tools` description for a server described as `about`."
  def search_description(about),
    do:
      "Search the tools of " <>
        about <>
        " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."

  def search_schema,
    do: %{
      "type" => "object",
      "properties" => %{"query" => %{"type" => "string"}, "limit" => %{"type" => "integer"}},
      "required" => ["query"]
    }

  def call_description, do: "Run a tool found with search_tools."

  def call_schema,
    do: %{
      "type" => "object",
      "properties" => %{"name" => %{"type" => "string"}, "arguments" => %{"type" => "object"}},
      "required" => ["name"]
    }

  @doc "Rank `tools` against `query`; hits are `{name, description, inputSchema, server}`."
  def rank(tools, query, limit, server) do
    docs =
      Enum.map(tools, fn t ->
        nt = tokens(to_string(t["name"] || ""))
        nt ++ nt ++ tokens(if is_binary(t["description"]), do: t["description"], else: "")
      end)

    n = length(tools)
    df = docs |> Enum.flat_map(&Enum.uniq/1) |> Enum.frequencies()
    avg = Enum.sum(Enum.map(docs, &length/1)) / max(1, n)
    q = tokens(query)

    tools
    |> Enum.zip(docs)
    |> Enum.map(fn {t, d} ->
      tf = Enum.frequencies(d)

      s =
        Enum.reduce(q, 0.0, fn w, acc ->
          case Map.get(tf, w, 0) do
            0 ->
              acc

            f ->
              idf = :math.log(1 + (n - df[w] + 0.5) / (df[w] + 0.5))
              acc + idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * length(d) / avg))
          end
        end)

      {t, s}
    end)
    |> Enum.filter(fn {_, s} -> s > 0 end)
    |> Enum.sort_by(fn {_, s} -> s end, :desc)
    |> Enum.take(limit)
    |> Enum.map(fn {t, _} ->
      # key order is pinned (name, description, inputSchema, server)
      Jason.OrderedObject.new([
        {"name", t["name"]},
        {"description", t["description"]},
        {"inputSchema", t["inputSchema"]},
        {"server", server}
      ])
    end)
  end

  @doc false
  def tokens(s) do
    {out, cur, _} =
      s
      |> String.codepoints()
      |> Enum.reduce({[], [], false}, fn ch, {out, cur, prev_lower} ->
        upper = ch != String.downcase(ch)
        lower = ch != String.upcase(ch)
        alnum = String.match?(ch, ~r/^[\p{L}\p{N}]$/u)

        cond do
          upper and prev_lower -> {flush(out, cur), [ch], lower}
          alnum -> {out, [ch | cur], lower}
          true -> {flush(out, cur), [], lower}
        end
      end)

    Enum.reverse(flush(out, cur))
  end

  defp flush(out, []), do: out
  defp flush(out, cur), do: [cur |> Enum.reverse() |> Enum.join() |> String.downcase() | out]
end
