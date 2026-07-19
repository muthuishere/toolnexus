defmodule Toolnexus.Agents.HomeTest do
  @moduledoc """
  §7E agent home (H1–H7) — the directory IS the agent, the file-backed memory builtin
  with frozen-snapshot semantics, and the injectable-clock heartbeat.

  Fixture-driven: `examples/persona-agent/fixture.json` supplies the bootstrap dir,
  the canonical order, the 2 MB cap, and the expected outputs. The scripted mock LLM
  is delivered through the client's `:transport` seam (as the subagent tests do), and
  the heartbeat runs on a virtual clock.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents
  alias Toolnexus.Agents.Home
  alias Toolnexus.Agents.Runtime
  alias Toolnexus.TestAgentMock, as: Mock
  alias Toolnexus.TestVirtualClock, as: VClock

  @fixture Path.expand("../../../examples/persona-agent/fixture.json", __DIR__)
           |> File.read!()
           |> Jason.decode!()

  defp opts, do: %{transport: Mock.transport(&Mock.home/1)}

  defp mkdir(files) do
    dir = Path.join(System.tmp_dir!(), "home-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    for {f, body} <- files, do: File.write!(Path.join(dir, f), body)
    dir
  end

  test "fixture pins the canonical bootstrap order and cap" do
    assert Home.bootstrap_order() == @fixture["bootstrapOrder"]
    assert @fixture["maxFileBytes"] == 2 * 1024 * 1024
    assert Home.heartbeat_ok() == "HEARTBEAT_OK"
  end

  # ── H1 — the directory IS the agent: ordered bootstrap files → soul ──────────
  test "H1 ordered bootstrap discovery, ## sections, and the soul reaches the child" do
    dir =
      mkdir(%{
        "SOUL.md" => "You are Ava, a calm support assistant.",
        "USER.md" => "The user is Muthu; timezone IST.",
        "TOOLS.md" => "Prefer the memory tool over guessing.",
        "MEMORY.md" => "- Onboarded 2026-07."
      })

    {soul, found} = Home.compose_soul(dir)

    # H1.a discovers only present files, in canonical order
    assert found == ["SOUL.md", "USER.md", "TOOLS.md", "MEMORY.md"]

    # H1.b injected as ## sections, SOUL before USER before MEMORY
    assert :binary.match(soul, "## SOUL.md") < :binary.match(soul, "## USER.md")
    assert :binary.match(soul, "## USER.md") < :binary.match(soul, "## MEMORY.md")

    # H1.c the composed soul reaches the child system prompt
    ava = Home.from_dir(dir, model: "m-echo-soul")
    r = Agents.run(ava, opts(), "who are you?")
    assert r.text == "sections:[SOUL.md,USER.md,TOOLS.md,MEMORY.md]"
  end

  # ── H2 — per-file 2 MB cap ──────────────────────────────────────────────────
  test "H2 a >2 MB bootstrap file is truncated with a notice; on-disk file untouched" do
    big = String.duplicate("x", 3 * 1024 * 1024)
    dir = mkdir(%{"SOUL.md" => big})
    {soul, _} = Home.compose_soul(dir)

    assert String.contains?(soul, "[truncated: exceeds 2 MB bootstrap cap]")
    assert byte_size(soul) < 2_150_000
    # on-disk file untouched
    assert byte_size(File.read!(Path.join(dir, "SOUL.md"))) == 3 * 1024 * 1024
  end

  # ── H3 — the memory tool: add/replace/remove persist to disk ────────────────
  test "H3 memory add/replace/remove persist; missing substring is loud; target=user" do
    dir = mkdir(%{"MEMORY.md" => "- Likes green tea."})
    tool = Home.memory_tool(dir)
    run = fn args -> tool.execute.(args, nil) end

    # H3.a add appends an entry
    run.(%{"action" => "add", "text" => "Likes hiking"})
    assert File.read!(Path.join(dir, "MEMORY.md")) =~ "- Likes hiking"

    # H3.b replace swaps a substring
    run.(%{"action" => "replace", "text" => "green tea", "with" => "oolong"})
    assert File.read!(Path.join(dir, "MEMORY.md")) =~ "oolong"

    # H3.c remove deletes an entry
    run.(%{"action" => "remove", "text" => "- Likes hiking\n"})
    refute File.read!(Path.join(dir, "MEMORY.md")) =~ "hiking"

    # H3.d replace of a missing substring is a loud isError; file unchanged
    before = File.read!(Path.join(dir, "MEMORY.md"))
    miss = run.(%{"action" => "replace", "text" => "nonexistent", "with" => "x"})
    assert miss.is_error and miss.output =~ "nonexistent"
    assert File.read!(Path.join(dir, "MEMORY.md")) == before

    # H3.e target=user writes USER.md, not MEMORY.md
    uw = run.(%{"action" => "add", "target" => "user", "text" => "Speaks Tamil"})
    refute uw.is_error
    assert File.read!(Path.join(dir, "USER.md")) =~ "Speaks Tamil"

    # remove of a missing substring and an unknown action are both loud isError
    rm = run.(%{"action" => "remove", "text" => "ghost"})
    assert rm.is_error and rm.output =~ "ghost"
    bad = run.(%{"action" => "wat", "text" => "x"})
    assert bad.is_error and bad.output =~ "unknown action"
  end

  # ── H4 — frozen snapshot: a mid-session write hits disk, not the live prompt ─
  test "H4 write lands on disk; the NEXT session's snapshot carries it" do
    dir = mkdir(%{"USER.md" => "The user is new."})

    ava = Home.from_dir(dir, model: "m-remember")
    Agents.run(ava, opts(), "note my coffee preference")

    # H4.a the write landed on disk
    assert File.read!(Path.join(dir, "USER.md")) =~ "Prefers dark roast"

    # H4.b a SECOND, fresh run re-reads the file → the snapshot now carries the note
    ava2 = Home.from_dir(dir, model: "m-recall")
    r2 = Agents.run(ava2, opts(), "what do you know about me?")
    assert r2.text == "I recall: dark roast"
  end

  # ── H5 — heartbeat: HEARTBEAT_OK is silent; a due beat surfaces; ticks coalesce ─
  test "H5.a a HEARTBEAT_OK beat stays silent (no report)" do
    # no '3pm sync' phrase ⇒ m-heartbeat replies HEARTBEAT_OK ⇒ silent
    dir = mkdir(%{"SOUL.md" => "You are Ava.", "HEARTBEAT.md" => "Nothing scheduled."})
    ava = Home.from_dir(dir, model: "m-heartbeat")

    clk = VClock.new()
    started = Home.start_agent(ava, Map.put(opts(), :clock, VClock.clock(clk)), every_ms: 20)

    VClock.advance(clk, 20)
    Home.sync(started)

    assert Home.beats(started) == []
    Home.stop(started)
  end

  test "H5.b a due heartbeat surfaces its reminder to on_beat" do
    dir = mkdir(Map.take(@fixture["bootstrapDir"], ["SOUL.md", "HEARTBEAT.md"]))
    ava = Home.from_dir(dir, model: "m-heartbeat")

    parent = self()
    clk = VClock.new()

    started =
      Home.start_agent(ava, Map.put(opts(), :clock, VClock.clock(clk)),
        every_ms: 20,
        on_beat: fn text -> send(parent, {:beat, text}) end
      )

    VClock.advance(clk, 20)
    Home.sync(started)

    assert_receive {:beat, text}, 1_000
    assert text =~ "3pm sync"
    assert [^text] = Home.beats(started)
    Home.stop(started)
  end

  test "H5.c ticks coalesce: ticks that arrive WHILE a turn is in-flight drain as ONE turn" do
    # A genuine coalesce: the first turn's synchronous prefix drains the inbox at its
    # start, so ticks must be posted while that turn is still running for the test to
    # prove coalescing (not just batch-before-wake). m-peer sleeps 20ms per LLM call,
    # keeping the first turn in-flight, and reports how many inbox items it saw.
    rt =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: Toolnexus.TestAgentHelpers.registry()
      })

    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")

    # wake is non-blocking — the Run executes in its own process
    Runtime.wake(rt, h, "start")
    Process.sleep(5)
    assert Runtime.snapshot(h).state == :running, "first turn must still be in-flight"

    # three ticks arrive DURING the in-flight turn; they land in the inbox
    for _ <- 1..3, do: Runtime.post(rt, h, %{from: "clock", channel: "timer", text: "tick"})

    r1 = Runtime.wait(rt, h)
    assert r1.turns == 1, "first turn drained nothing extra"

    # one wake drains all three coalesced ticks as ONE turn producing a SINGLE item
    # (ticks dedupe to one counted entry) — not one turn per tick
    Runtime.wake(rt, h)
    r2 = Runtime.wait(rt, h)
    assert r2.turns == 1, "three coalesced ticks = one turn, got #{r2.turns}"
    assert r2.text == "processed 1 items", "three ticks coalesced to one item: #{r2.text}"

    Runtime.shutdown(rt)
  end

  # ── H6 — read-only persona (memory: false) ──────────────────────────────────
  test "H6 memory: false ⇒ no memory tool in the toolkit view" do
    dir = mkdir(%{"SOUL.md" => "Read-only Ava."})
    ava = Home.from_dir(dir, memory: false)
    names = get_in(ava.spec, [:uses, :tools]) |> Enum.map(& &1.name)
    refute "memory" in names
  end

  # ── H7 — recipe: 'dream' = a scheduled agent that consolidates into MEMORY.md ─
  test "H7 a dream agent is fromDir + memory tool (composition, not new surface)" do
    dir =
      mkdir(%{
        "SOUL.md" => "Nightly consolidator.",
        "HEARTBEAT.md" => "Consolidate: merge duplicate notes into MEMORY.md via the memory tool."
      })

    dream = Home.from_dir(dir)
    names = get_in(dream.spec, [:uses, :tools]) |> Enum.map(& &1.name)
    assert "memory" in names
  end
end
