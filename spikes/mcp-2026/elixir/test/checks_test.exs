defmodule Mcp2026.ChecksTest do
  use ExUnit.Case, async: true
  import ExUnit.CaptureIO
  alias Mcp2026.{Client, Server, Stdio}

  test "the 8 cross-port checks pass against our own server" do
    srv = Server.serve(Server.fixture())
    out = capture_io(fn -> assert Mcp2026.Checks.run(srv.url) end)
    assert length(Regex.scan(~r/^PASS /m, out)) == 8
  end

  test "the same checks over stdio: 7 PASS, header mismatch SKIP" do
    script = Path.expand("../examples/02-connect-stdio.exs", __DIR__)
    mk = fn ->
      {:ok, c} = Stdio.start("mix", ["run", "--no-compile", "--no-deps-check", script], env: [{"SERVE_STDIO", "1"}, {"MIX_ENV", "test"}])
      c
    end
    out = capture_io(fn -> assert Mcp2026.Checks.run(mk) end)
    assert length(Regex.scan(~r/^PASS /m, out)) == 7
    assert out =~ "SKIP header mismatch"
  end

  test "stdio: concurrent calls are demultiplexed by id; a dead server is an error, not a hang" do
    {:ok, c} = Stdio.start("sh", ["-c", ~s(read l; echo '{"jsonrpc":"2.0","id":1,"result":{"ok":true}}'; echo noise; exit 0)])
    assert {:ok, %{"ok" => true}} = Client.call(c, "tools/list")
    assert {:error, _} = Client.call(c, "tools/list")
    Client.close(c)

    script = Path.expand("../examples/02-connect-stdio.exs", __DIR__)
    {:ok, c} = Stdio.start("mix", ["run", "--no-compile", "--no-deps-check", script], env: [{"SERVE_STDIO", "1"}, {"MIX_ENV", "test"}])
    results = 1..20 |> Task.async_stream(fn i -> Client.call_tool(c, "echo", %{"text" => "m#{i}"}) end, max_concurrency: 20) |> Enum.to_list()
    assert Enum.map(results, fn {:ok, {:ok, r}} -> Mcp2026.text(r) end) == Enum.map(1..20, &"m#{&1}")
    Client.close(c)
  end

  test "header encoding: base64 sentinel for non-ASCII / padded names" do
    assert Client.encode_header("echo") == "echo"
    assert Client.encode_header("héllo") =~ "=?base64?"
    assert Client.encode_header(" x") =~ "=?base64?"
  end
end
