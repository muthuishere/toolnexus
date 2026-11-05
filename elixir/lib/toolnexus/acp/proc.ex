defmodule Toolnexus.Acp.Proc do
  @moduledoc false
  # OS-process helpers for the ACP client (SPEC §8C). `Port.open` has no
  # Setpgid option, so "end the process group" is done the way the builtin
  # bash tool does it: enumerate the tree with `ps` WHILE the root is alive
  # (after it dies its children are reparented and unreachable), then signal
  # every pid.

  @doc false
  @spec descendants(integer() | nil) :: [integer()]
  def descendants(nil), do: []

  def descendants(root) do
    case System.cmd("ps", ["-eo", "pid=,ppid="], stderr_to_stdout: true) do
      {out, 0} ->
        pairs =
          for line <- String.split(out, "\n", trim: true),
              [p, pp] <- [String.split(String.trim(line), ~r/\s+/)],
              {pid, ""} <- [Integer.parse(p)],
              {ppid, ""} <- [Integer.parse(pp)],
              do: {pid, ppid}

        walk(pairs, [root], [])

      _ ->
        []
    end
  rescue
    _ -> []
  end

  defp walk(_pairs, [], acc), do: acc

  defp walk(pairs, frontier, acc) do
    kids = for {p, pp} <- pairs, pp in frontier, p not in acc, do: p
    walk(pairs, kids, acc ++ kids)
  end

  @doc false
  @spec kill([integer()]) :: :ok
  def kill(pids) do
    Enum.each(pids, fn pid ->
      System.cmd("kill", ["-KILL", Integer.to_string(pid)], stderr_to_stdout: true)
    end)
  end
end
