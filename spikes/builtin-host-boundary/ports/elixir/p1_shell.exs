# O1 (SHELL) — elixir. Can Port.open take a host-set argv prefix, and what does
# it do with a BARE program name? The shipped port hardcodes the absolute
# "/bin/sh" (elixir/lib/toolnexus/builtin.ex:230), which is the reason to ask.
#
# Every arm has a control: the same call with a path that is known to work.
defmodule P1 do
  def try_open(exe, args) do
    port = Port.open({:spawn_executable, exe}, [:binary, :exit_status, :stderr_to_stdout, args: args])
    receive do
      {^port, {:data, d}} ->
        receive do
          {^port, {:exit_status, s}} -> {:ok, s, String.trim(d)}
        after 2000 -> {:ok, :nostatus, String.trim(d)} end
      {^port, {:exit_status, s}} -> {:ok, s, ""}
    after
      2000 -> {:timeout, nil, ""}
    end
  rescue
    e -> {:raise, e.__struct__, Exception.message(e)}
  catch
    k, r -> {:catch, {k, r}, ""}
  end

  def show(label, r), do: IO.puts("#{label}=#{inspect(r)}")
end

# --- control: absolute path, which is what the port ships -------------------
P1.show("CTRL_ABS_BIN_SH", P1.try_open("/bin/sh", ["-c", "echo hello"]))

# --- the measurement: a BARE program name ----------------------------------
P1.show("BARE_sh", P1.try_open("sh", ["-c", "echo hello"]))
P1.show("BARE_bash", P1.try_open("bash", ["-lc", "echo hello"]))
# control for the bare arm: a bare name that does NOT exist, to prove the
# failure above is about resolution and not about this name in particular
P1.show("BARE_nosuchprog", P1.try_open("nosuchprog-xyz", []))

# --- :os.find_executable/1 — the resolver elixir actually has ---------------
for n <- ~w(sh bash zsh pwsh powershell cmd nosuchprog-xyz) do
  P1.show("FIND_" <> n, :os.find_executable(String.to_charlist(n)) |> then(fn
    false -> false
    cl -> List.to_string(cl)
  end))
end

# --- does an absolutised bare name then work? (the proposed fix) ------------
case :os.find_executable(~c"bash") do
  false -> IO.puts("FIXED_bash=no_bash_on_this_box")
  cl -> P1.show("FIXED_bash", P1.try_open(List.to_string(cl), ["-lc", "echo hello"]))
end

# --- a host-set argv prefix used VERBATIM, incl. multi-flag prefixes --------
# ["bash","-lc"]  /  ["cmd","/d","/s","/c"] shape: prefix ++ [command]
prefix_run = fn prefix, command ->
  [exe | flags] = prefix
  case :os.find_executable(String.to_charlist(exe)) do
    false -> {:unresolved, exe}
    cl -> P1.try_open(List.to_string(cl), flags ++ [command])
  end
end
P1.show("PREFIX_sh_-c", prefix_run.(["sh", "-c"], "echo prefix-ok"))
P1.show("PREFIX_bash_-lc", prefix_run.(["bash", "-lc"], "echo prefix-ok"))
P1.show("PREFIX_env_-i_sh_-c", prefix_run.(["env", "-i", "sh", "-c"], "echo prefix-ok"))

# --- COMSPEC / windows candidate ordering, as far as it can be measured here
IO.puts("OS_TYPE=#{inspect(:os.type())}")
IO.puts("COMSPEC=#{inspect(System.get_env("COMSPEC"))}")
