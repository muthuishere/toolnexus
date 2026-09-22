# O3 follow-up — Path.safe_relative/2 looked like a ready-made confinement
# check in p3. How far does it actually go? Arms with controls.
base = Path.join(System.tmp_dir!(), "tn-p3b-#{:erlang.unique_integer([:positive])}")
out  = Path.join(System.tmp_dir!(), "tn-p3b-out-#{:erlang.unique_integer([:positive])}")
File.mkdir_p!(Path.join(base, "sub"))
File.mkdir_p!(out)
File.write!(Path.join(out, "secret.txt"), "secret")
File.write!(Path.join(base, "sub/file.txt"), "in")
File.ln_s!(out, Path.join(base, "outlink"))                 # symlink OUT
File.ln_s!(Path.join(base, "sub"), Path.join(base, "inlink")) # symlink INSIDE

show = fn label, p -> IO.puts("#{String.pad_trailing(label, 40)} #{inspect(Path.safe_relative(p, base))}") end

show.("existing inside", "sub/file.txt")
show.("NON-EXISTENT inside (the write case)", "sub/brand/new.txt")
show.("symlink OUT, existing target", "outlink/secret.txt")
show.("symlink OUT, non-existent target", "outlink/new.txt")
show.("symlink INSIDE the base", "inlink/file.txt")
show.("relative escape", "../escape.txt")
show.("absolute inside the base", Path.join(base, "sub/file.txt"))
show.("absolute outside", Path.join(out, "secret.txt"))
show.("the symlink itself", "outlink")
show.("tilde", "~/x")
show.(".", ".")
show.("empty", "")
File.rm_rf!(base); File.rm_rf!(out)
