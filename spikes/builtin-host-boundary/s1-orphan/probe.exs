# S1 probe (elixir). naive = what elixir/lib/toolnexus/builtin.ex:230 does today
# (Port.open + Port.close on timeout).
# tree = walk `ps -o pid=,ppid=` from the port's os_pid and kill each descendant,
# because a BEAM Port child is NOT a process-group leader and there is no
# Setpgid equivalent in Port.open's options.
[mode, marker | _] = System.argv()

port =
  Port.open({:spawn_executable, "/bin/sh"}, [
    :binary,
    :exit_status,
    :stderr_to_stdout,
    args: ["-c", "sleep 0.2; sh -c 'sleep 1; touch #{marker}'"]
  ])

{:os_pid, os_pid} = Port.info(port, :os_pid)

descendants = fn root ->
  {out, 0} = System.cmd("ps", ["-eo", "pid=,ppid="])

  pairs =
    out
    |> String.split("\n", trim: true)
    |> Enum.flat_map(fn line ->
      case String.split(String.trim(line), ~r/\s+/) do
        [p, pp] -> [{String.to_integer(p), String.to_integer(pp)}]
        _ -> []
      end
    end)

  walk = fn walk, pids ->
    kids = for {p, pp} <- pairs, pp in pids, p not in pids, do: p
    if kids == [], do: pids, else: walk.(walk, pids ++ kids)
  end

  walk.(walk, [root]) -- [root]
end

receive do
  {^port, {:exit_status, _}} -> :ok
after
  300 ->
    case mode do
      "naive" ->
        Port.close(port)

      _ ->
        kids = descendants.(os_pid)
        IO.puts("DESCENDANTS=#{length(kids)}")
        for pid <- [os_pid | kids], do: System.cmd("kill", ["-TERM", to_string(pid)], stderr_to_stdout: true)
        Process.sleep(200)
        for pid <- [os_pid | kids], do: System.cmd("kill", ["-KILL", to_string(pid)], stderr_to_stdout: true)
        Port.close(port)
    end
end

IO.puts("killed")
