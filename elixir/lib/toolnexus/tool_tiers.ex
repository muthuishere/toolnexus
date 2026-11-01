defmodule Toolnexus.ToolTiers do
  @moduledoc """
  Tool tiers (SPEC §4C "Prompt caching", change `add-prompt-cache` phase 2).

  With `Client.create(tool_tiers: %{core: [...], deferred: [...]})` only the CORE
  tools are sent with full schemas. LISTED tools (every other tool) appear as
  `- name — one line`, and DEFERRED tools by name only, both inside the description
  of a `tool_search` tool that returns full schemas on demand — as a tool result, so
  the tools array (and the cached prefix) never changes mid-session. A loaded tool is
  called directly by name, or through `call_tool %{name, arguments}`, which the loop
  unwraps BEFORE hooks so permission gates and hints always see the real tool.

  Options (map or keyword):

    * `:core` — names always sent with their full schema
    * `:deferred` — names listed by name only; every other tool is listed
    * `:budget_tokens` — cap on the up-front tokens (core schemas + the two meta
      tools); `0`/absent ⇒ 5% of `:context_window`; both absent ⇒ no budget. Over
      budget the least-used core tool moves to listed, then the least-used listed
      tool to deferred (ties: larger schema first, then name)
    * `:context_window` — tokens, for the default budget
    * `:usage_count` — `(name -> integer)`, the host's usage stat, read when the plan is built
    * `:count_tokens` — `(term -> integer)`, default `ceil(json bytes / 4)`

  The plan is built on a toolkit's first request and then FROZEN (per client, per
  toolkit); `Toolnexus.Client.reset_tool_tiers/2` rebuilds it — call it at a session
  start. A tool registered after the plan froze is deferred-by-omission: absent from
  the description, found by search.
  """

  alias Toolnexus.{Tool, ToolHints, ToolResult}

  @intro "Load the full schema of tools that are not shown in full, then call them by name (or with call_tool). Query by keywords, or \"select:a,b\" for exact names."

  @doc false
  # The frozen plan: tier per name, the sorted tier lists, and the two meta tools.
  def build_plan(opts, tools) do
    opts = Map.new(opts)
    count = opts[:count_tokens] || (&default_count_tokens/1)
    usage = fn n -> if is_function(opts[:usage_count], 1), do: opts[:usage_count].(n) || 0, else: 0 end
    by_name = Map.new(tools, &{&1.name, &1})

    tier =
      tools
      |> Map.new(&{&1.name, :listed})
      |> mark(opts[:deferred], :deferred)
      |> mark(opts[:core], :core)

    plan = rebuild(%{tier: tier}, by_name)

    budget =
      case opts[:budget_tokens] do
        b when is_integer(b) and b > 0 -> b
        _ -> if is_integer(opts[:context_window]) and opts[:context_window] > 0, do: div(opts[:context_window], 20), else: 0
      end

    if budget > 0 do
      cost = fn p ->
        count.(tool_def(p.search)) + count.(tool_def(p.call)) +
          Enum.reduce(p.core, 0, &(&2 + count.(tool_def(by_name[&1]))))
      end

      order = fn names ->
        Enum.sort_by(names, fn n -> {usage.(n), -count.(tool_def(by_name[n])), n} end)
      end

      demote = fn p, from_list, to ->
        Enum.reduce_while(order.(from_list), p, fn n, p ->
          if cost.(p) <= budget,
            do: {:halt, p},
            else: {:cont, rebuild(%{p | tier: Map.put(p.tier, n, to)}, by_name)}
        end)
      end

      plan = demote.(plan, plan.core, :listed)
      demote.(plan, plan.listed, :deferred)
    else
      plan
    end
  end

  defp mark(tier, nil, _t), do: tier

  defp mark(tier, names, t),
    do: Enum.reduce(names, tier, fn n, acc -> if Map.has_key?(acc, n), do: Map.put(acc, n, t), else: acc end)

  defp default_count_tokens(v), do: div(byte_size(Jason.encode!(v)) + 3, 4)

  @doc false
  def tool_def(%Tool{} = t),
    do: %{"name" => t.name, "description" => t.description, "inputSchema" => t.input_schema}

  defp first_line(s) do
    s = s |> to_string() |> String.split("\n", parts: 2) |> hd()

    if byte_size(s) > 120 do
      valid_prefix(binary_part(s, 0, 120)) <> "…"
    else
      s
    end
  end

  # A byte cut may split a UTF-8 sequence; keep the valid prefix.
  defp valid_prefix(b) do
    if String.valid?(b), do: b, else: valid_prefix(binary_part(b, 0, byte_size(b) - 1))
  end

  defp rebuild(%{tier: tier} = p, by_name) do
    names = fn t -> for({n, ^t} <- tier, do: n) |> Enum.sort() end
    core = names.(:core)
    listed = names.(:listed)
    deferred = names.(:deferred)

    listed_text =
      if listed == [],
        do: "",
        else:
          "\n\nAvailable tools:" <>
            Enum.map_join(listed, fn n ->
              case first_line(by_name[n].description) do
                "" -> "\n- " <> n
                d -> "\n- " <> n <> " — " <> d
              end
            end)

    deferred_text =
      if deferred == [], do: "", else: "\n\nMore tools (name only): " <> Enum.join(deferred, ", ")

    Map.merge(p, %{
      core: core,
      listed: listed,
      deferred: deferred,
      search: %Tool{
        name: "tool_search",
        description: @intro <> listed_text <> deferred_text,
        input_schema: %{
          "type" => "object",
          "properties" => %{
            "query" => %{"type" => "string", "description" => "Keywords, or select:name1,name2"},
            "limit" => %{"type" => "number", "description" => "Maximum results for a keyword query (default 5)"}
          },
          "required" => ["query"],
          "additionalProperties" => false
        },
        source: "builtin",
        hints: %ToolHints{read_only: true},
        execute: &engine_only/2
      },
      call: %Tool{
        name: "call_tool",
        description: "Call a tool loaded with tool_search, by name, with its arguments.",
        input_schema: %{
          "type" => "object",
          "properties" => %{"name" => %{"type" => "string"}, "arguments" => %{"type" => "object"}},
          "required" => ["name"],
          "additionalProperties" => false
        },
        source: "builtin",
        execute: &engine_only/2
      }
    })
  end

  # The client loop intercepts both meta tools; run outside it they say so.
  defp engine_only(_args, _ctx),
    do: %ToolResult{output: "tool_search/call_tool run only inside the client loop", is_error: true}

  @doc false
  # The tools array for a request: core tools (sorted, present in the toolkit) then
  # tool_search and call_tool.
  def request_tools(plan, tools) do
    by_name = Map.new(tools, &{&1.name, &1})
    Enum.flat_map(plan.core, &List.wrap(by_name[&1])) ++ [plan.search, plan.call]
  end

  @doc false
  # call_tool %{name, arguments} → {real, inner, true}; anything else unchanged.
  def unwrap("call_tool", args) do
    args = args || %{}
    inner = if is_map(args["arguments"]), do: args["arguments"], else: %{}
    {to_string(args["name"] || ""), inner, true}
  end

  def unwrap(name, args), do: {name, args, false}

  @doc false
  # tool_search over every tool of the toolkit, any tier.
  def search(tools, args) do
    q = String.trim(to_string((args || %{})["query"] || ""))

    picked =
      cond do
        q == "" ->
          {:error, "tool_search: query is required"}

        String.starts_with?(q, "select:") ->
          by_name = Map.new(tools, &{&1.name, &1})

          wanted =
            q
            |> String.replace_prefix("select:", "")
            |> String.split(",")
            |> Enum.map(&String.trim/1)
            |> Enum.reject(&(&1 == ""))

          {found, missing} = Enum.split_with(wanted, &Map.has_key?(by_name, &1))

          if found == [] and missing != [],
            do: {:error, "tool_search: no such tool: " <> Enum.join(missing, ", ")},
            else: {:ok, Enum.map(found, &by_name[&1])}

        true ->
          limit =
            case args["limit"] do
              l when is_number(l) and l >= 1 -> trunc(l)
              _ -> 5
            end

          terms = q |> String.downcase() |> String.split()

          hits =
            tools
            |> Enum.map(fn t ->
              name = String.downcase(t.name)
              desc = String.downcase(to_string(t.description))

              score =
                Enum.reduce(terms, 0, fn term, s ->
                  s + if(String.contains?(name, term), do: 3, else: 0) +
                    if(String.contains?(desc, term), do: 1, else: 0)
                end)

              {t, score}
            end)
            |> Enum.filter(fn {_t, s} -> s > 0 end)
            |> Enum.sort_by(fn {t, s} -> {-s, t.name} end)
            |> Enum.take(limit)
            |> Enum.map(&elem(&1, 0))

          {:ok, hits}
      end

    case picked do
      {:error, msg} ->
        %ToolResult{output: msg, is_error: true}

      {:ok, []} ->
        %ToolResult{output: "No matching tools.", metadata: %{count: 0}}

      {:ok, found} ->
        json = found |> Enum.map(&tool_def/1) |> Jason.encode!(pretty: true)

        %ToolResult{
          output: "Loaded tools (call by name, or with call_tool):\n" <> json,
          metadata: %{count: length(found), tools: Enum.map(found, & &1.name)}
        }
    end
  end
end
