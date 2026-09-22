# Spike for issue #86 (Elixir): the issue says run/ask need "an arity that omits
# the toolkit". Test both the missing arity and a nil toolkit.
base = System.get_env("SPIKE86_BASE")
client = Toolnexus.Client.create(base_url: base, style: "openai", model: "mock", api_key: "not-a-real-key")

# 1. The arity a completion user reaches for: run(client, prompt).
try do
  apply(Toolnexus.Client, :run, [client, "write me a haiku"])
  |> then(&IO.puts("ex: run/2 OK, text=#{inspect(&1.text)}"))
rescue
  e -> IO.puts("ex: run/2 FAILED: #{inspect(e.__struct__)}: #{Exception.message(e)}")
end

# 2. Explicit nil toolkit on the existing arity.
try do
  r = Toolnexus.Client.run(client, "write me a haiku", nil, [])
  IO.puts("ex: nil-toolkit run OK, text=#{inspect(r.text)}")
rescue
  e -> IO.puts("ex: nil-toolkit run FAILED: #{inspect(e.__struct__)}: #{Exception.message(e)}")
end

# 3. The workaround that exists today.
tk = Toolnexus.Toolkit.build(builtins: false)
r = Toolnexus.Client.run(client, "write me a haiku", tk, [])
IO.puts("ex: Toolkit.build(builtins: false) OK, tools=#{length(Toolnexus.Toolkit.tools(tk))} text=#{inspect(r.text)}")
