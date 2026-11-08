defmodule Toolnexus.McpLegacyStdioProbeTest do
  # SPEC §2.2a (amended): a fast discover reply reuses the stdio probe child for
  # the frozen path; only discover silence respawns.
  #
  # Not async, and a 5 s per-request budget: the first discover is answered only
  # after a cold `node` start, and under `mix coveralls` with every async suite
  # running beside it that start alone took > 1.5 s on CI (flaked on main too,
  # run 42479901060). With 1.5 s a late discover *error* looked like silence, so
  # the child was respawned and the spawn count (what this test pins) was wrong.
  # Silence still costs exactly one budget, so the silent case stays bounded.
  use ExUnit.Case, async: false

  alias Toolnexus.{Context, Mcp}

  @server Path.expand("../../examples/mcp-2026/legacy-stdio/server.mjs", __DIR__)

  defp run(mode) do
    file = Path.join(System.tmp_dir!(), "tn-spawn-#{System.unique_integer([:positive])}")
    File.rm(file)
    on_exit(fn -> File.rm(file) end)

    cfg = %{
      "command" => "node",
      "args" => [@server],
      "env" => %{"SPAWN_COUNT_FILE" => file, "DISCOVER_MODE" => mode},
      "timeout" => 5_000
    }

    src = Mcp.load(%{"mcpServers" => %{"legacy" => cfg}})
    on_exit(fn -> Mcp.close(src) end)

    tool = Enum.find(src.tools, &(&1.name == "legacy_echo")) || flunk("no legacy_echo")
    assert tool.execute.(%{"text" => "hi"}, %Context{}).output == "echo:hi"

    file |> File.read!() |> String.split("\n", trim: true) |> length()
  end

  test "discover error reply: the same child continues into initialize (1 spawn)" do
    assert run("error") == 1
  end

  test "discover silence: the probe child is replaced (2 spawns)" do
    assert run("silent") == 2
  end
end
