defmodule Toolnexus.AcpTest do
  @moduledoc """
  ACP (Agent Client Protocol) model source — issue #96, ADR 0031,
  `openspec/changes/add-acp-model-source`. Hermetic: drives a fake ACP
  server (`test/support/fake_acp_server.exs`) over REAL OS pipes, ported
  from `spikes/acp/fakeagent/main.go` / `spikes/acp/SPIKE.md`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Acp

  @moduletag timeout: 60_000

  @fixture_script Path.expand("support/fake_acp_server.exs", __DIR__)

  defp fixture_cmd(scenario) do
    jason_ebin = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    ["elixir", "-pa", jason_ebin, @fixture_script, scenario]
  end

  defp connect!(scenario, opts \\ []) do
    {:ok, pid} = Acp.connect(fixture_cmd(scenario), opts)
    pid
  end

  # -- warm session reuse -----------------------------------------------------

  test "a warm session serves many turns from one process, one session/new" do
    pid = connect!("echo")

    {:ok, a} = Acp.prompt(pid, "first")
    {:ok, b} = Acp.prompt(pid, "second")
    {:ok, c} = Acp.prompt(pid, "third")

    assert a =~ "new_count=1 seq=1"
    assert b =~ "new_count=1 seq=2"
    assert c =~ "new_count=1 seq=3"

    Acp.close(pid)
  end

  test "session/new carries an absolute cwd and an mcpServers array (real devin rejects otherwise)" do
    pid = connect!("echo")
    {:ok, answer} = Acp.prompt(pid, "hi")

    [_, params_json] = String.split(answer, "params=", parts: 2)
    params = Jason.decode!(params_json)

    assert Path.type(params["cwd"]) == :absolute
    assert is_list(params["mcpServers"])

    Acp.close(pid)
  end

  test "generate/1 builds the exact seam create_in_process accepts and drives the in-process client end to end" do
    pid = connect!("echo")
    generate = Acp.generate(pid)

    {:ok, tk} = Toolnexus.create_toolkit(builtins: false)
    client = Toolnexus.Client.create_in_process(model: "acp-fake", generate: generate)
    result = Toolnexus.Client.run(client, "hello", tk)

    assert result.status == "done"
    assert result.text =~ "echo:"

    Acp.close(pid)
  end

  # -- thought/tool narration filtered -----------------------------------------

  test "only agent_message_chunk forms the reply; thought/tool narration is dropped" do
    pid = connect!("noisy")
    {:ok, answer} = Acp.prompt(pid, "hello")

    assert answer == ~s({"answer":"hello"})
    assert {:ok, %{"answer" => "hello"}} = Jason.decode(answer)

    Acp.close(pid)
  end

  # -- permission answered inline, never awaited by the caller -----------------

  test "a session/request_permission is answered with the first allow-kind option, inline, without the caller ever seeing it" do
    pid = connect!("permission")

    {micros, {:ok, answer}} = :timer.tc(fn -> Acp.prompt(pid, "delete the db") end)

    assert answer == "PERMITTED:delete the db"
    # Answered inline from the read loop, not routed through the caller at
    # all — this should complete in well under a second, not hang or wait
    # on any external timeout.
    assert micros < 2_000_000

    Acp.close(pid)
  end

  # -- stale answer prevented by the supersedes marker --------------------------

  test "a stateful session answers a near-duplicate prompt with a STALE answer, unless it carries the supersedes marker" do
    pid = connect!("stale")

    {:ok, a} = Acp.prompt(pid, "What is the capital of France?")
    assert a == "STALE-ANSWER-TO:What is the capital of France?"

    # Full-request-every-turn against a stateful session: the second prompt
    # contains the first, verbatim, with no marker — the fake agent (like a
    # plausible real one) matches the EARLIEST remembered question and
    # answers stale.
    {:ok, b} =
      Acp.prompt(pid, "What is the capital of France?\nWhat is the capital of Japan?")

    assert b == "STALE-ANSWER-TO:What is the capital of France?"

    # The library's own supersedes marker (see Acp.assemble_prompt_text/1)
    # fixes it — the fake agent answers only the text after the marker.
    {:ok, c} =
      Acp.prompt(
        pid,
        "What is the capital of France?\nWhat is the capital of Japan?\n" <>
          "SUPERSEDES-ALL-PRIOR: What is the capital of Japan?"
      )

    assert c == "FRESH-ANSWER-TO:What is the capital of Japan?"

    Acp.close(pid)
  end

  test "generate/1 assembles the supersedes marker itself, so a caller who just appends messages never gets a stale answer" do
    pid = connect!("stale")
    generate = Acp.generate(pid)

    # Even the FIRST turn carries the library-built marker (assembled from
    # every call, not left to the caller), so the fake agent already answers
    # fresh rather than falling back to its naive earliest-match behavior.
    r1 = generate.(%{messages: [%{"role" => "user", "content" => "What is the capital of France?"}]})
    assert r1.content =~ "FRESH-ANSWER-TO:What is the capital of France?"

    r2 =
      generate.(%{
        messages: [
          %{"role" => "user", "content" => "What is the capital of France?"},
          %{"role" => "assistant", "content" => "STALE-ANSWER-TO:What is the capital of France?"},
          %{"role" => "user", "content" => "What is the capital of Japan?"}
        ]
      })

    assert r2.content =~ "FRESH-ANSWER-TO:What is the capital of Japan?"

    Acp.close(pid)
  end

  # -- turn serialisation -------------------------------------------------------

  test "turns on one session are serialised, never sent concurrently" do
    pid = connect!("slow")

    {elapsed_us, results} =
      :timer.tc(fn ->
        [1, 2, 3]
        |> Enum.map(fn n -> Task.async(fn -> Acp.prompt(pid, "t#{n}") end) end)
        |> Enum.map(&Task.await(&1, 10_000))
      end)

    # The fake server sleeps 150ms per prompt; three TRUE-serial turns take
    # >= ~450ms of wall time. If the client let them overlap, this would be
    # closer to 150ms.
    assert elapsed_us >= 400_000

    texts = Enum.map(results, fn {:ok, t} -> t end)
    # Every request got back exactly its own answer — no cross-talk from one
    # session carrying two prompts at once.
    assert Enum.any?(texts, &String.contains?(&1, "t1"))
    assert Enum.any?(texts, &String.contains?(&1, "t2"))
    assert Enum.any?(texts, &String.contains?(&1, "t3"))

    # And the server itself observed them one at a time, in increasing seq
    # order (the client never issued #2 before #1's reply came back).
    seqs =
      texts
      |> Enum.map(fn t ->
        [_, rest] = String.split(t, "slow:", parts: 2)
        [n, _] = String.split(rest, ":", parts: 2)
        String.to_integer(n)
      end)
      |> Enum.sort()

    assert seqs == [1, 2, 3]

    Acp.close(pid)
  end

  # -- process lifetime + idempotent close --------------------------------------

  test "close/1 is idempotent" do
    pid = connect!("echo")
    {:ok, _} = Acp.prompt(pid, "one")

    assert :ok = Acp.close(pid)
    assert :ok = Acp.close(pid)
    assert :ok = Acp.close(pid)
  end

  test "a turn's own failure does not take down the process; the session stays usable" do
    pid = connect!("echo")

    {:ok, _} = Acp.prompt(pid, "first")
    assert Process.alive?(pid)
    {:ok, second} = Acp.prompt(pid, "second")
    assert second =~ "seq=2"

    Acp.close(pid)
  end
end
