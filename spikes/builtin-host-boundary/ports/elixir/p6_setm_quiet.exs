# O4 follow-up 2 — p5 showed `set -m` emits bash JOB NOTIFICATIONS
# ("[1]+  Done ...") on stderr, which :stderr_to_stdout folds straight into
# `output` — a §0 golden break. Which wrapper variants are byte-identical to the
# unwrapped control, and still hand back a pgid?
pidfile = Path.join(System.tmp_dir!(), "tn-p6-#{:erlang.unique_integer([:positive])}.pid")

run = fn shell, c ->
  port = Port.open({:spawn_executable, shell},
    [:binary, :exit_status, :stderr_to_stdout, args: ["-c", c]])
  collect = fn collect, acc ->
    receive do
      {^port, {:data, d}} -> collect.(collect, acc <> d)
      {^port, {:exit_status, s}} -> {acc, s}
    after 5000 -> {acc, :timeout} end
  end
  collect.(collect, "")
end

variants = %{
  "A plain set -m"        => fn c -> "set -m; { " <> c <> "\n} & echo $! > #{pidfile}; wait $!" end,
  "B wait 2>/dev/null"    => fn c -> "set -m; { " <> c <> "\n} & echo $! > #{pidfile}; wait $! 2>/dev/null" end,
  "C set +m before wait"  => fn c -> "set -m; { " <> c <> "\n} & p=$!; echo $p > #{pidfile}; set +m; wait $p" end,
  "D subshell set -m"     => fn c -> "(set -m; { " <> c <> "\n} & echo $! > #{pidfile}; wait $!) 2>&1" end,
  "E set -m +notify"      => fn c -> "set -m; { " <> c <> "\n} & echo $! > #{pidfile}; wait $! 2>&3" end
}

shells = [{"/bin/sh(bash-as-sh)", "/bin/sh"}] ++
  (case :os.find_executable(~c"dash") do
     false -> []
     cl -> [{"dash", List.to_string(cl)}]
   end) ++
  (case :os.find_executable(~c"zsh") do
     false -> []
     cl -> [{"zsh", List.to_string(cl)}]
   end)

cases = ["echo hello", "echo oops 1>&2", "exit 7", "printf abc", "echo a; echo b 1>&2"]

for {sname, sh} <- shells do
  IO.puts("\n== shell #{sname} (#{sh})")
  for {vname, f} <- Enum.sort(variants) do
    results =
      for c <- cases do
        ctrl = run.(sh, c)
        w = run.(sh, f.(c))
        {c, ctrl, w, ctrl == w}
      end
    ok = Enum.all?(results, fn {_, _, _, s} -> s end)
    pg = File.exists?(pidfile) and String.trim(File.read!(pidfile)) != ""
    IO.puts("  #{String.pad_trailing(vname, 22)} byte_identical=#{ok} pgid_written=#{pg}")
    unless ok do
      for {c, ctrl, w, s} <- results, not s do
        IO.puts("      #{inspect(c)}: #{inspect(ctrl)} vs #{inspect(w)}")
      end
    end
  end
end
File.rm(pidfile)
