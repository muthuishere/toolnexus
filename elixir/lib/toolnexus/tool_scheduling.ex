defmodule Toolnexus.ToolScheduling do
  @moduledoc """
  Tool scheduling (SPEC §8 "Tool scheduling").

  The calls of one model response run in emitted order as consecutive runs: a run of
  concurrency-safe calls executes concurrently (at most the cap at once); each unsafe call
  runs alone, after every earlier call and before any later one. Results keep emitted order.

  A call is safe when the tool's own `:concurrency_safe` predicate (`fn args -> boolean end`)
  says so; without one, only when its hints say `read_only`. A tool without hints is unsafe.
  """

  alias Toolnexus.{Tool, ToolHints}

  @default 10

  @doc "The cap when neither the option nor `TOOLNEXUS_MAX_TOOL_CONCURRENCY` sets one."
  @spec default_max_tool_concurrency() :: pos_integer()
  def default_max_tool_concurrency, do: @default

  @doc """
  option > env `TOOLNEXUS_MAX_TOOL_CONCURRENCY` > 10. Non-positive or non-numeric values
  fall back. `env` is the lookup (defaults to `System.get_env/1`).
  """
  @spec resolve_max_tool_concurrency(term(), (String.t() -> String.t() | nil)) :: pos_integer()
  def resolve_max_tool_concurrency(option, env \\ &System.get_env/1)
  def resolve_max_tool_concurrency(n, _env) when is_integer(n) and n > 0, do: n

  def resolve_max_tool_concurrency(_option, env) do
    raw = env.("TOOLNEXUS_MAX_TOOL_CONCURRENCY")

    with true <- is_binary(raw),
         trimmed = String.trim(raw),
         true <- Regex.match?(~r/^\+?\d+$/, trimmed),
         {n, ""} <- Integer.parse(String.trim_leading(trimmed, "+")),
         true <- n > 0 do
      n
    else
      _ -> @default
    end
  end

  @doc "Whether a call to `tool` with `args` may run alongside other safe calls."
  @spec concurrency_safe?(Tool.t() | nil, map()) :: boolean()
  def concurrency_safe?(nil, _args), do: false

  def concurrency_safe?(%Tool{concurrency_safe: f}, args) when is_function(f, 1),
    do: f.(args) == true

  def concurrency_safe?(%Tool{hints: %ToolHints{read_only: true}}, _args), do: true
  def concurrency_safe?(%Tool{}, _args), do: false

  @doc """
  Run `fun` over `items` in emitted order: consecutive safe items overlap (at most `cap`),
  an unsafe item runs alone. Results keep item order. Every item runs in its own task, as
  before.
  """
  @spec schedule([term()], (term() -> boolean()), pos_integer(), (term() -> term())) :: [term()]
  def schedule(items, safe?, cap, fun) do
    items
    |> Enum.chunk_while(
      nil,
      fn item, acc ->
        case {safe?.(item), acc} do
          {true, {:safe, run}} -> {:cont, {:safe, [item | run]}}
          {true, nil} -> {:cont, {:safe, [item]}}
          {true, prev} -> {:cont, emit(prev), {:safe, [item]}}
          {false, nil} -> {:cont, {:unsafe, [item]}}
          {false, prev} -> {:cont, emit(prev), {:unsafe, [item]}}
        end
      end,
      fn
        nil -> {:cont, nil}
        prev -> {:cont, emit(prev), nil}
      end
    )
    |> Enum.flat_map(fn
      {:safe, run} -> run_concurrently(run, min(cap, length(run)), fun)
      {:unsafe, run} -> run_concurrently(run, 1, fun)
    end)
  end

  defp emit({kind, run}), do: {kind, Enum.reverse(run)}

  defp run_concurrently(run, concurrency, fun) do
    run
    |> Task.async_stream(fun,
      ordered: true,
      timeout: :infinity,
      max_concurrency: max(concurrency, 1)
    )
    |> Enum.map(fn {:ok, r} -> r end)
  end
end
