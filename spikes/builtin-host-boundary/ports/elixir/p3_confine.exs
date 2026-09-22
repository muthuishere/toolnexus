# O3 (CONFINEMENT) — elixir. What do Path.expand, Path.safe_relative and
# :file.read_link_all actually do, and can a deepest-existing-ancestor
# canonicaliser be built out of them with NO new dependency?
defmodule Canon do
  # There is no realpath(3) in OTP. :file.read_link_all resolves exactly ONE
  # level, so the chain has to be walked by hand, segment by segment.
  def real_existing(path), do: do_real(tl(Path.split(Path.expand(path))), "/", 0)

  defp do_real(_, _acc, depth) when depth > 64, do: {:error, :eloop}
  defp do_real([], acc, _), do: {:ok, acc}
  defp do_real([seg | rest], acc, depth) do
    cand = Path.join(acc, seg)
    case :file.read_link_all(String.to_charlist(cand)) do
      {:ok, target} ->
        t = List.to_string(target)
        next = if Path.type(t) == :absolute, do: t, else: Path.expand(t, acc)
        do_real(tl(Path.split(Path.expand(next))) ++ rest, "/", depth + 1)
      {:error, _} ->
        do_real(rest, cand, depth)
    end
  end

  # the spec's shape: deepest existing ancestor, re-attach the tail
  def canon(path) do
    p = Path.expand(path)
    {existing, tail} = split_existing(Path.split(p), [])
    {:ok, r} = real_existing(Path.join(existing))
    if tail == [], do: r, else: Path.join([r | tail])
  end

  defp split_existing(segs, tail) do
    p = Path.join(segs)
    cond do
      File.exists?(p) or match?({:ok, _}, :file.read_link(String.to_charlist(p))) -> {segs, tail}
      length(segs) <= 1 -> {["/"], tail}
      true -> split_existing(Enum.drop(segs, -1), [List.last(segs) | tail])
    end
  end

  def contained?(base, p) do
    cb = canon(base)
    cp = canon(p)
    cb == cp or String.starts_with?(cp, cb <> "/")
  end
end

base_raw = Path.join(System.tmp_dir!(), "tn-p3-#{:erlang.unique_integer([:positive])}")
out_raw  = Path.join(System.tmp_dir!(), "tn-p3-out-#{:erlang.unique_integer([:positive])}")
File.mkdir_p!(Path.join(base_raw, "sub"))
File.mkdir_p!(out_raw)
File.write!(Path.join(out_raw, "secret.txt"), "secret")
File.ln_s!(out_raw, Path.join(base_raw, "link"))

IO.puts("BASE_RAW=#{base_raw}")
IO.puts("PATH_EXPAND=#{Path.expand(base_raw)}")
IO.puts("CANON=#{Canon.canon(base_raw)}")
IO.puts("PATH_EXPAND_IS_LEXICAL_ONLY=#{Canon.canon(base_raw) != Path.expand(base_raw)}")
IO.puts("READ_LINK_ALL_one_level=#{inspect(:file.read_link_all(String.to_charlist(Path.join(base_raw, "link"))))}")

cases = [
  {"sub/file.txt", true},
  {"sub/deep/not/created/yet.txt", true},     # (b) does not exist yet — the write case
  {"../escape.txt", false},
  {"sub/../../escape.txt", false},
  {Path.join(out_raw, "secret.txt"), false},  # absolute outside
  {"link/secret.txt", false},                 # (a) symlink out of the base
  {"link/newfile.txt", false},                # (a)+(b): non-existent THROUGH a symlink
  {".", true},
  {"", true}
]
for {p, want} <- cases do
  full = if Path.type(p) == :absolute, do: p, else: Path.expand(p, base_raw)
  got = Canon.contained?(base_raw, full)
  IO.puts("CONFINE #{String.pad_trailing(inspect(p), 34)} contained=#{got} expected=#{want} #{if got == want, do: "OK", else: "MISMATCH"}")
end

# CONTROL ARM — a NAIVE lexical check must FAIL the symlink case. If it passes
# too, the canonicaliser above is measuring nothing.
naive = fn b, p -> String.starts_with?(Path.expand(p), Path.expand(b) <> "/") end
IO.puts("CONTROL_naive_symlink_says_contained=#{naive.(base_raw, Path.join(base_raw, "link/secret.txt"))}")
IO.puts("CONTROL_naive_relescape_says_contained=#{naive.(base_raw, Path.expand("../escape.txt", base_raw))}")

# --- Path.safe_relative/2: what it actually gives ---------------------------
for p <- ["sub/file.txt", "../escape.txt", "/etc/hosts", "link/secret.txt", "sub/../../escape.txt"] do
  IO.puts("SAFE_RELATIVE #{String.pad_trailing(inspect(p), 26)} -> #{inspect(Path.safe_relative(p, base_raw))}")
end

# --- (c) case normalisation on a case-insensitive filesystem ----------------
upper = String.upcase(base_raw)
IO.puts("CASE_upper_path_exists=#{File.exists?(Path.join(upper, "sub"))}")
IO.puts("CASE_upper_canon=#{Canon.canon(Path.join(upper, "sub"))}")
IO.puts("CASE_contained_via_upper=#{Canon.contained?(base_raw, Path.join(upper, "sub"))}")

# --- the ~ trap: Path.expand/2 ignores the base entirely for a tilde path ---
IO.puts("TILDE_expand=#{Path.expand("~/x", base_raw)}")
IO.puts("TILDE_contained=#{Canon.contained?(base_raw, Path.expand("~/x", base_raw))}")

File.rm_rf!(base_raw); File.rm_rf!(out_raw)
