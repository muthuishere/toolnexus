# O4 follow-up — the `set -m` wrapper is the ps-free option, but it REWRITES the
# command string, and §0 pins `output` and the exit code byte-identically. So:
# does wrapping change what the tool observes? Control arm = the same command
# unwrapped, through the port the shipped code uses.
pidfile = Path.join(System.tmp_dir!(), "tn-p5-#{:erlang.unique_integer([:positive])}.pid")

run = fn c ->
  port = Port.open({:spawn_executable, "/bin/sh"},
    [:binary, :exit_status, :stderr_to_stdout, args: ["-c", c]])
  collect = fn collect, acc ->
    receive do
      {^port, {:data, d}} -> collect.(collect, acc <> d)
      {^port, {:exit_status, s}} -> {acc, s}
    after 5000 -> {acc, :timeout} end
  end
  collect.(collect, "")
end

# NOTE the exact shape. `{ CMD\n} &` — the newline, not a `;`, terminates the
# last command inside the group. An earlier version of this probe used
# `{ CMD\n; } &` and EVERY case came back as a bash syntax error, which is the
# first real finding: this wrapper is sensitive to the command's own text.
wrap = fn c -> "set -m; { " <> c <> "\n} & echo $! > #{pidfile}; wait $!" end

cases = [
  {"stdout", "echo hello"},
  {"stderr", "echo oops 1>&2"},
  {"both+order", "echo a; echo b 1>&2; echo c"},
  {"exit 7", "exit 7"},
  {"exit 0 after output", "echo x; exit 0"},
  {"no trailing newline", "printf abc"},
  {"nonexistent cmd", "nosuchcmd-xyz"},
  {"multiline", "echo one\necho two"},
  {"signal death", "kill -TERM $$"},
  {"trailing comment", "echo z # trailing"},
  {"unterminated &&", "true &&"}
]

IO.puts(String.pad_trailing("case", 22) <> "plain -> wrapped")
for {name, c} <- cases do
  {o1, s1} = run.(c)
  {o2, s2} = run.(wrap.(c))
  same = o1 == o2 and s1 == s2
  IO.puts("#{String.pad_trailing(name, 22)} #{inspect({o1, s1})} -> #{inspect({o2, s2})}  #{if same, do: "IDENTICAL", else: "*** DIVERGES ***"}")
end
File.rm(pidfile)
