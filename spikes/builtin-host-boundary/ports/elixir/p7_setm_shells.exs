# O4 follow-up 3 — p6 showed `set -m` behaves differently per interpreter.
# Does the wrapper still KILL THE TREE under dash and zsh, or only under bash?
# Each shell gets a CONTROL arm (no kill) so a "killed" verdict means something.
[shell_name, shell_path, mode, marker, pidfile] = System.argv()
cmd = "sleep 0.2; sh -c 'sleep 1; touch #{marker}'"
wrapped = "set -m; { " <> cmd <> "\n} & echo $! > #{pidfile}; wait $! 2>/dev/null"

port = Port.open({:spawn_executable, shell_path},
  [:binary, :exit_status, :stderr_to_stdout, args: ["-c", if(mode == "control", do: cmd, else: wrapped)]])

receive do {^port, {:exit_status, _}} -> :ok after
  400 ->
    if mode != "control" do
      pg = (File.exists?(pidfile) && String.trim(File.read!(pidfile))) || ""
      IO.puts("SHELL=#{shell_name} PGID=#{inspect(pg)}")
      if pg != "" do
        {o, rc} = System.cmd("kill", ["-TERM", "-" <> pg], stderr_to_stdout: true)
        IO.puts("KILLGROUP_rc=#{rc} #{inspect(String.trim(o))}")
        Process.sleep(200)
        System.cmd("kill", ["-KILL", "-" <> pg], stderr_to_stdout: true)
      end
    end
    if Port.info(port), do: Port.close(port)
end
