# O2 (BASEDIR) — elixir. Does Path.expand/2 give the resolve() shape the spec
# needs, and does an EMPTY base reproduce today's process-cwd behaviour
# byte-identically?
base = Path.join(System.tmp_dir!(), "tn-p2-#{:erlang.unique_integer([:positive])}")
File.mkdir_p!(Path.join(base, "sub"))
cwd = File.cwd!()

resolve = fn p, b -> if b in [nil, ""], do: Path.expand(p), else: Path.expand(p, b) end

IO.puts("CWD=#{cwd}")
IO.puts("BASE=#{base}")

for p <- ["sub/file.txt", "./sub/file.txt", "../escape.txt", "file.txt", ".", "", "~/x"] do
  IO.puts("WITHBASE #{inspect(p)} -> #{inspect(resolve.(p, base))}")
end

# absolute must be UNAFFECTED by the base
IO.puts("ABS /etc/hosts -> #{inspect(resolve.("/etc/hosts", base))}")

# --- the control arm: empty base must equal today's behaviour exactly -------
# today = Path.expand(p) (elixir's own relative-to-cwd rule) for the file tools,
# and File.cwd!() for bash's workdir default.
same =
  for p <- ["sub/file.txt", "./x", "../y", "z", ".", ""] do
    {p, resolve.(p, ""), Path.expand(p), resolve.(p, "") == Path.expand(p)}
  end
IO.puts("EMPTY_BASE_IDENTICAL=#{inspect(Enum.all?(same, fn {_, _, _, ok} -> ok end))}")
for t <- same, do: IO.puts("  #{inspect(t)}")

# --- apply_patch: the paths are CONTENT, not arguments ----------------------
patch = """
*** Begin Patch
*** Add File: sub/added.txt
+hello
*** End Patch
"""
rewritten =
  patch
  |> String.split("\n")
  |> Enum.map(fn line ->
    case Regex.run(~r/^\*\*\* (Add File|Update File|Delete File): (.*)$/, line) do
      [_, kind, path] -> "*** #{kind}: #{resolve.(String.trim(path), base)}"
      nil -> line
    end
  end)
  |> Enum.join("\n")
IO.puts("PATCH_REWRITE_OK=#{String.contains?(rewritten, Path.join(base, "sub/added.txt"))}")

# --- a real relative write lands under the base, not under cwd --------------
File.write!(resolve.("sub/w.txt", base), "x")
IO.puts("LANDED_IN_BASE=#{File.exists?(Path.join(base, "sub/w.txt"))}")
IO.puts("LANDED_IN_CWD=#{File.exists?(Path.join(cwd, "sub/w.txt"))}")

# --- bash workdir default ---------------------------------------------------
{out, 0} = System.cmd("/bin/sh", ["-c", "pwd"], cd: base)
IO.puts("BASH_WORKDIR_DEFAULT=#{String.trim(out)}")
IO.puts("BASH_WORKDIR_MATCHES_BASE=#{Path.expand(String.trim(out)) == Path.expand(base)}")

File.rm_rf!(base)
