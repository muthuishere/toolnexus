defmodule Mcp2026.Checks do
  @moduledoc "The 8 cross-port conformance checks, printing PASS/FAIL/SKIP lines."
  alias Mcp2026.{Client, RpcError}

  @doc "Run against `url` (or a client factory `fun -> %Client{}`); `setup` configures every client."
  def run(target, setup \\ & &1) do
    mk = fn -> setup.(if is_function(target), do: target.(), else: Client.new(target)) end
    c = mk.()
    uri = "skill://hello-world/SKILL.md"

    expect_code = fn res, code ->
      case res do
        {:error, %RpcError{code: ^code}} -> :ok
        other -> {:error, "want error #{code}, got #{inspect(other)}"}
      end
    end

    steps = [
      {"server/discover", fn ->
         with {:ok, r} <- Client.call(c, "server/discover") do
           cond do
             not Map.has_key?(get_in(r, ["capabilities", "extensions"]) || %{}, Mcp2026.skills_ext()) ->
               {:error, "skills extension not advertised: #{inspect(r["capabilities"])}"}
             Mcp2026.protocol_version() not in List.wrap(r["supportedVersions"]) ->
               {:error, "supportedVersions lacks #{Mcp2026.protocol_version()}"}
             true -> :ok
           end
         end
       end},
      {"unsupported version -> -32022", fn ->
         bad = %{mk.() | version: "1900-01-01"}
         expect_code.(Client.call(bad, "server/discover"), Mcp2026.code_unsupported_protocol())
       end},
      {"header mismatch -> -32020", fn ->
         bad = mk.()
         if bad.stdio do
           :skip  # stdio has no HTTP headers to mismatch
         else
           bad = %{bad | header_override: %{"Mcp-Name" => "greet"}}
           expect_code.(Client.call(bad, "tools/call", %{"name" => "echo", "arguments" => %{"text" => "x"}}),
             Mcp2026.code_header_mismatch())
         end
       end},
      {"tools/list", fn ->
         with {:ok, r} <- Client.call(c, "tools/list") do
           names = for t <- List.wrap(r["tools"]), do: t["name"]
           cond do
             "echo" not in names or "greet" not in names -> {:error, "tools: #{inspect(names)}"}
             r["ttlMs"] == nil or r["cacheScope"] == nil or r["resultType"] != "complete" ->
               {:error, "missing ttlMs/cacheScope/resultType: #{inspect(r)}"}
             true -> :ok
           end
         end
       end},
      {"tools/call echo", fn ->
         with {:ok, r} <- Client.call_tool(c, "echo", %{"text" => "hi toolnexus"}) do
           if Mcp2026.text(r) == "hi toolnexus", do: :ok, else: {:error, "got #{inspect(r)}"}
         end
       end},
      {"tools/call greet (MRTR input_required round trip)", fn ->
         {:ok, asked} = Agent.start_link(fn -> 0 end)
         res = Client.call_tool(c, "greet", %{}, fn _key, req ->
           Agent.update(asked, &(&1 + 1))
           if req["method"] != "elicitation/create",
             do: {:error, "unexpected input request #{req["method"]}"},
             else: %{"action" => "accept", "content" => %{"name" => "toolnexus"}}
         end)
         n = Agent.get(asked, & &1)
         with {:ok, r} <- res do
           if n == 1 and Mcp2026.text(r) == "Hello, toolnexus!", do: :ok, else: {:error, "asked=#{n} result=#{inspect(r)}"}
         end
       end},
      {"skills/list", fn ->
         with {:ok, r} <- Client.call(c, "skills/list") do
           if Enum.any?(List.wrap(r["skills"]), &(&1["uri"] == uri)), do: :ok, else: {:error, "hello-world not listed: #{inspect(r)}"}
         end
       end},
      {"skills/get hello-world", fn ->
         with {:ok, r} <- Client.call(c, "skills/get", %{"uri" => uri}) do
           sk = r["skill"] || %{}
           if get_in(sk, ["frontmatter", "name"]) == "hello-world" and List.wrap(sk["resources"]) != [],
             do: :ok, else: {:error, "got #{inspect(r)}"}
         end
       end}
    ]

    Enum.reduce(steps, true, fn {name, f}, ok ->
      case f.() do
        :ok -> IO.puts("PASS #{name}"); ok
        :skip -> IO.puts("SKIP #{name} (not applicable over stdio)"); ok
        {:error, e} -> IO.puts("FAIL #{name}: #{if is_binary(e), do: e, else: inspect(e)}"); false
      end
    end)
  end
end

defmodule Mcp2026.CLI do
  @moduledoc "run.sh entry: server | client <url> | selftest"
  def main(["server" | _]) do
    port = String.to_integer(System.get_env("PORT") || "7106")
    srv = Mcp2026.Server.serve(Mcp2026.Server.fixture(), port)
    IO.puts("listening on #{srv.url}")
    Process.sleep(:infinity)
  end

  def main(["client" | rest]) do
    url = List.first(rest) || "http://127.0.0.1:7103/mcp"
    unless Mcp2026.Checks.run(url), do: System.halt(1)
  end

  def main(["selftest" | _]) do
    srv = Mcp2026.Server.serve(Mcp2026.Server.fixture())
    unless Mcp2026.Checks.run(srv.url), do: System.halt(1)
  end

  def main(_) do
    IO.puts("usage: run.sh server | client <url> | selftest")
    System.halt(2)
  end
end
