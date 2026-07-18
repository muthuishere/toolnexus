defmodule Toolnexus.Agents.Trace do
  @moduledoc """
  The runtime's transition trace — one append-only list shared by every handle of a
  runtime. §0 conformance for §7D is judged on these lines: identical per-handle
  transition sequences against the shared `examples/subagent-*` fixtures.
  """

  @doc false
  def start_link do
    {:ok, pid} = Agent.start_link(fn -> [] end)
    pid
  end

  @doc "Append one trace line."
  @spec add(pid(), String.t()) :: :ok
  def add(pid, line), do: Agent.update(pid, &(&1 ++ [line]))

  @doc "All trace lines, in order."
  @spec all(pid()) :: [String.t()]
  def all(pid), do: Agent.get(pid, & &1)
end
