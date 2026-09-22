# O4 (KILL THE JOB) — elixir. Modes, each printing one verdict line.
#   control   no kill at all — the marker MUST appear, or every arm below is vacuous
#   naive     what elixir/lib/toolnexus/builtin.ex:257 ships (kill -9 the os_pid, Port.close)
#   tree      SPIKE §1.1's fix: walk `ps -eo pid=,ppid=` from Port.info(:os_pid)
#             BEFORE killing, TERM the set, grace, KILL
#   taskkill  the SAME fix, but the surrounding Task is shut down instead of the
#             timeout firing — (b) in the brief
#   portclose Port.close alone, with no kill at all: what does the BEAM do to the
#             OS child on its own?
#   ownerdie  the Port OWNER process is killed with Process.exit(:kill) — the
#             harshest cancellation, no code of ours gets to run
#   setm      the `set -m` job-control wrapper (clojure's fix) applied in elixir,
#             i.e. can elixir do this WITHOUT shelling out to ps?
[mode, marker | rest] = System.argv()
pidfile = List.first(rest) || (marker <> ".pid")
cmd = "sleep 0.2; sh -c 'sleep 1; touch #{marker}'"

open = fn c ->
  Port.open({:spawn_executable, "/bin/sh"},
    [:binary, :exit_status, :stderr_to_stdout, args: ["-c", c]])
end

descendants = fn root ->
  {out, 0} = System.cmd("ps", ["-eo", "pid=,ppid="])
  pairs =
    out |> String.split("\n", trim: true)
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

term_then_kill = fn pids ->
  for p <- pids, do: System.cmd("kill", ["-TERM", to_string(p)], stderr_to_stdout: true)
  Process.sleep(200)          # the spec's window is 2000ms; 200 keeps the probe fast
  for p <- pids, do: System.cmd("kill", ["-KILL", to_string(p)], stderr_to_stdout: true)
end

case mode do
  "control" ->
    port = open.(cmd)
    receive do {^port, {:exit_status, _}} -> :ok after 300 -> IO.puts("NOKILL=1") end

  "naive" ->
    port = open.(cmd)
    {:os_pid, os_pid} = Port.info(port, :os_pid)
    receive do {^port, {:exit_status, _}} -> :ok after
      300 ->
        System.cmd("kill", ["-9", to_string(os_pid)], stderr_to_stdout: true)
        if Port.info(port), do: Port.close(port)
    end

  "tree" ->
    port = open.(cmd)
    {:os_pid, os_pid} = Port.info(port, :os_pid)
    receive do {^port, {:exit_status, _}} -> :ok after
      300 ->
        kids = descendants.(os_pid)
        IO.puts("DESCENDANTS=#{length(kids)}")
        term_then_kill.([os_pid | kids])
        if Port.info(port), do: Port.close(port)
    end

  "taskkill" ->
    # (b): the TASK is shut down, the timeout never fires. Does the ps-walk fix
    # still hold? It can only hold if the walk happens somewhere that still runs.
    parent = self()
    task = Task.async(fn ->
      port = open.(cmd)
      {:os_pid, os_pid} = Port.info(port, :os_pid)
      send(parent, {:pid, os_pid})
      receive do {^port, {:exit_status, _}} -> :ok after 60_000 -> :ok end
    end)
    os_pid = receive do {:pid, p} -> p after 2000 -> nil end
    Process.sleep(300)
    # THE MEASUREMENT: shut the task down. Nothing inside it gets to run.
    Task.shutdown(task, :brutal_kill)
    Process.sleep(50)
    IO.puts("AFTER_TASK_SHUTDOWN_child_alive=#{match?({_, 0}, System.cmd("kill", ["-0", to_string(os_pid)], stderr_to_stdout: true))}")
    # and the fix applied FROM THE OUTSIDE, after the task is gone
    kids = descendants.(os_pid)
    IO.puts("DESCENDANTS_AFTER_SHUTDOWN=#{length(kids)}")
    term_then_kill.([os_pid | kids])

  "portclose" ->
    # (c): Port.close alone. No kill anywhere. What does the BEAM do?
    port = open.(cmd)
    {:os_pid, os_pid} = Port.info(port, :os_pid)
    Process.sleep(300)
    Port.close(port)
    Process.sleep(100)
    {_, rc} = System.cmd("kill", ["-0", to_string(os_pid)], stderr_to_stdout: true)
    IO.puts("AFTER_PORT_CLOSE_direct_child_alive=#{rc == 0}")

  "ownerdie" ->
    parent = self()
    owner = spawn(fn ->
      port = open.(cmd)
      {:os_pid, os_pid} = Port.info(port, :os_pid)
      send(parent, {:pid, os_pid})
      Process.sleep(60_000)
    end)
    os_pid = receive do {:pid, p} -> p after 2000 -> nil end
    Process.sleep(300)
    Process.exit(owner, :kill)
    Process.sleep(100)
    {_, rc} = System.cmd("kill", ["-0", to_string(os_pid)], stderr_to_stdout: true)
    IO.puts("AFTER_OWNER_KILL_direct_child_alive=#{rc == 0}")

  "setm" ->
    # (c): can elixir get a process GROUP without ps? Port.open has no
    # process-group option, but the interpreter it already spawns has `set -m`.
    wrapped = "set -m; { #{cmd}; } & echo $! > #{pidfile}; wait $!"
    port = open.(wrapped)
    receive do {^port, {:exit_status, _}} -> :ok after
      300 ->
        pgid = File.read!(pidfile) |> String.trim()
        IO.puts("PGID=#{pgid}")
        System.cmd("kill", ["-TERM", "-" <> pgid], stderr_to_stdout: true)
        Process.sleep(200)
        System.cmd("kill", ["-KILL", "-" <> pgid], stderr_to_stdout: true)
        if Port.info(port), do: Port.close(port)
    end

  "portinfo" ->
    # (c): what does :erlang.port_info actually expose? Is there anything other
    # than :os_pid to work with?
    port = open.(cmd)
    IO.puts("PORT_INFO=#{inspect(:erlang.port_info(port))}")
    IO.puts("PORT_OPTS_ACCEPTED=#{inspect(try do
      p2 = Port.open({:spawn_executable, "/bin/sh"}, [:binary, :exit_status, args: ["-c", "true"], setpgid: true])
      Port.close(p2); :setpgid_accepted
    rescue e -> {e.__struct__, Exception.message(e)} catch k, r -> {k, r} end)}")
    if Port.info(port), do: Port.close(port)
end
