defmodule Toolnexus.TestStubStore do
  @moduledoc false
  # A stub conversation store over fixture conversations: list/1 in fixture order,
  # get/2 returns the messages, save/3 must never be called. Counts reads.
  @behaviour Toolnexus.Client.ConversationStore
  defstruct [:convs, :reads]

  def new(convs) do
    {:ok, reads} = Agent.start_link(fn -> 0 end)
    %__MODULE__{convs: convs, reads: reads}
  end

  def reads(%__MODULE__{reads: r}), do: Agent.get(r, & &1)

  @impl true
  def get(%__MODULE__{} = s, id) do
    Agent.update(s.reads, &(&1 + 1))
    Enum.find_value(s.convs, fn c -> if c["id"] == id, do: c["messages"] end)
  end

  @impl true
  def save(_s, _id, _messages), do: raise("transcript search must never write")

  def list(%__MODULE__{} = s) do
    Agent.update(s.reads, &(&1 + 1))
    Enum.map(s.convs, fn c -> %{id: c["id"], updated_at: ~U[1970-01-01 00:00:00Z]} end)
  end
end

defmodule Toolnexus.TestNoListStore do
  @moduledoc false
  @behaviour Toolnexus.Client.ConversationStore
  defstruct []
  @impl true
  def get(_s, _id), do: nil
  @impl true
  def save(_s, _id, _m), do: :ok
end

defmodule Toolnexus.Agents.MemoryHierarchyTest do
  @moduledoc """
  SPEC §7E "Memory hierarchy" + "Dreaming", driven by the shared fixtures
  `examples/memory-hierarchy/fixture.json` and `examples/dreaming/fixture.json`.
  Hermetic: scripted in-process LLM (the `:transport` seam), virtual clock, temp dirs.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents
  alias Toolnexus.Agents.Home
  alias Toolnexus.Agents.Runtime
  alias Toolnexus.TestAgentMock, as: Mock
  alias Toolnexus.TestStubStore, as: Stub
  alias Toolnexus.TestVirtualClock, as: VClock
  alias Toolnexus.TranscriptSearch

  @mh_path Path.expand("../../../examples/memory-hierarchy/fixture.json", __DIR__)
  @dr_path Path.expand("../../../examples/dreaming/fixture.json", __DIR__)
  @external_resource @mh_path
  @external_resource @dr_path
  @mh @mh_path |> File.read!() |> Jason.decode!()
  @dr @dr_path |> File.read!() |> Jason.decode!()

  # Stored messages keep their key order (a tool_use input encodes in stored order).
  defp ordered_convs(path, keys) do
    fixture = path |> File.read!() |> Jason.decode!(objects: :ordered_objects)

    keys
    |> Enum.reduce(fixture, fn k, acc -> acc[k] end)
    |> Enum.map(fn c -> %{"id" => c["id"], "messages" => c["messages"]} end)
  end

  defp mh_store,
    do: Stub.new(ordered_convs(@mh_path, ["transcriptSearch", "store", "conversations"]))

  defp dr_store, do: Stub.new(ordered_convs(@dr_path, ["store", "conversations"]))

  defp mkhome(files) do
    token = :crypto.strong_rand_bytes(8) |> Base.url_encode64(padding: false)
    dir = Path.join(System.tmp_dir!(), "memh-#{System.unique_integer([:positive])}-#{token}")
    File.mkdir_p!(dir)
    on_exit(fn -> File.rm_rf(dir) end)

    for {f, body} <- files do
      p = Path.join(dir, f)
      File.mkdir_p!(Path.dirname(p))
      File.write!(p, body)
    end

    dir
  end

  # Every file under dir, keyed by '/'-separated relative path.
  defp snapshot(dir) do
    Path.wildcard(Path.join(dir, "**/*"), match_dot: true)
    |> Enum.filter(&File.regular?/1)
    |> Map.new(fn f -> {Path.relative_to(f, dir), File.read!(f)} end)
  end

  defp sha256(s), do: :crypto.hash(:sha256, s) |> Base.encode16(case: :lower)

  defp exec(tool, args) do
    r = tool.execute.(args, nil)
    %{"output" => r.output, "isError" => r.is_error}
  end

  # ---------------- G1 — index cap ----------------

  defp cap_memory(%{"memory" => m}), do: m

  defp cap_memory(%{"generate" => %{"line" => line, "count" => n}}),
    do: Enum.map_join(1..n, fn i -> String.replace(line, "<i>", to_string(i)) <> "\n" end)

  defp soul_opts(o) do
    cap =
      case o["memoryIndexCap"] do
        true -> true
        %{} = c -> %{max_lines: c["maxLines"], max_bytes: c["maxBytes"]}
        nil -> nil
      end

    [memory_index_cap: cap, notes: o["notes"] == true]
  end

  for c <- @mh["indexCap"]["cases"] do
    @c c
    test "G1 index cap: #{c["name"]}" do
      c = @c
      mem = cap_memory(c)
      dir = mkhome(Map.put(c["files"], "MEMORY.md", mem))
      {soul, _} = Home.compose_soul(dir, soul_opts(c["options"]))
      e = c["expect"]
      assert sha256(soul) == e["soulSha256"]
      assert byte_size(soul) == e["soulBytes"]
      assert String.ends_with?(soul, e["memorySectionTail"])
      assert soul == elem(Home.compose_soul(dir), 0) == e["equalsUncappedSoul"]
      assert File.read!(Path.join(dir, "MEMORY.md")) == mem
    end
  end

  test "G1: preset matches fixture; from_dir threads the cap; a missing field takes the preset's" do
    assert Home.memory_index_cap_preset() == %{
             max_lines: @mh["indexCap"]["preset"]["maxLines"],
             max_bytes: @mh["indexCap"]["preset"]["maxBytes"]
           }

    c = Enum.find(@mh["indexCap"]["cases"], &(&1["name"] == "lines-cap-preset-notes-off"))
    dir = mkhome(Map.put(c["files"], "MEMORY.md", cap_memory(c)))
    soul = Home.from_dir(dir, memory_index_cap: true).spec.soul
    assert sha256(soul) == c["expect"]["soulSha256"]
    assert soul =~ "- fact 200\n\n[MEMORY.md index truncated"
    refute soul =~ "- fact 201"
    assert elem(Home.compose_soul(dir, memory_index_cap: %{max_bytes: 25600}), 0) == soul

    assert elem(Home.compose_soul(dir, memory_index_cap: false), 0) ==
             elem(Home.compose_soul(dir), 0)
  end

  test "G1: invalid cap values raise" do
    dir = mkhome(%{"MEMORY.md" => "- a\n"})

    assert_raise ArgumentError, fn ->
      Home.compose_soul(dir, memory_index_cap: %{max_lines: 0})
    end

    assert_raise ArgumentError, fn ->
      Home.compose_soul(dir, memory_index_cap: %{max_bytes: 1.5})
    end

    assert_raise ArgumentError, fn -> Home.from_dir(dir, memory_index_cap: "big") end
  end

  # ---------------- G2 — notes tier ----------------

  test "G2: notes-off tool matches the fixture; notes-on schema/description pinned" do
    off = Home.memory_tool("/x")
    assert off.description == @mh["memoryTool"]["notesOff"]["description"]
    assert off.input_schema == @mh["memoryTool"]["notesOff"]["inputSchema"]
    assert Home.memory_tool("/x", notes: false).input_schema == off.input_schema
    on = Home.memory_tool("/x", notes: true)
    assert on.description == @mh["memoryTool"]["notesOn"]["description"]
    assert on.description == Home.memory_notes_description()
    assert on.input_schema == @mh["memoryTool"]["notesOn"]["inputSchema"]
  end

  for c <- @mh["memoryTool"]["cases"] do
    @c c
    test "G2 memory tool: #{c["name"]}" do
      c = @c
      dir = mkhome(c["seed"])
      tool = Home.memory_tool(dir, notes: c["notes"])

      for op <- c["ops"] do
        assert exec(tool, op["args"]) == op["expect"], inspect(op["args"])
      end

      assert snapshot(dir) == c["expectFiles"]

      if c["soulMustNotContain"],
        do: refute(elem(Home.compose_soul(dir, notes: true), 0) =~ c["soulMustNotContain"])
    end
  end

  test "G2: from_dir notes: true wires the notes-on tool; a non-regular note is not listed" do
    dir = mkhome(%{"SOUL.md" => "S", "memory/x.txt" => "no", "memory/BAD.md" => "no"})
    File.mkdir_p!(Path.join(dir, "memory/dir.md"))
    [mem] = Home.from_dir(dir, notes: true).spec.uses.tools
    assert mem.description == @mh["memoryTool"]["notesOn"]["description"]
    assert exec(mem, %{"action" => "list"}) == %{"output" => "(no notes)", "isError" => false}

    assert exec(mem, %{"action" => "wat", "target" => "note:a"})["output"] ==
             "unknown action: wat"

    assert exec(mem, %{"action" => "replace", "text" => "ghost", "with" => "x"})["output"] ==
             "not found: ghost"

    assert exec(mem, %{"action" => "add", "target" => "user", "text" => "u"})["output"] ==
             "ok (add → USER.md); loads next session"
  end

  # ---------------- G3 — transcript search ----------------

  test "G3: transcript search schema/description/defaults pinned" do
    ts = @mh["transcriptSearch"]
    t = TranscriptSearch.tool(mh_store())
    assert t.name == ts["defaults"]["name"]
    assert t.description == ts["description"]
    assert t.description == TranscriptSearch.description()
    assert t.input_schema == ts["inputSchema"]
  end

  for c <- @mh["transcriptSearch"]["cases"] do
    @c c
    test "G3 transcript search: #{c["name"]}" do
      c = @c
      o = c["options"] || %{}

      opts =
        [max_results: o["maxResults"], snippet_chars: o["snippetChars"]]
        |> Enum.reject(fn {_, v} -> v == nil end)

      assert exec(TranscriptSearch.tool(mh_store(), opts), c["args"]) == c["expect"]
    end
  end

  test "G3: empty query does not read the store; bad construction raises" do
    store = mh_store()
    assert exec(TranscriptSearch.tool(store), %{"query" => ""})["isError"]
    assert Stub.reads(store) == 0

    err =
      assert_raise ArgumentError, fn -> TranscriptSearch.tool(%Toolnexus.TestNoListStore{}) end

    assert err.message == @mh["transcriptSearch"]["storeWithoutList"]["error"]
    assert_raise ArgumentError, fn -> TranscriptSearch.tool(:not_a_store) end
    assert_raise ArgumentError, fn -> TranscriptSearch.tool(store, max_results: 0) end
    assert_raise ArgumentError, fn -> TranscriptSearch.tool(store, snippet_chars: 0) end

    t = TranscriptSearch.tool(store)

    assert exec(t, %{"query" => "moon", "limit" => 1.5})["output"] ==
             "limit must be an integer >= 1"

    assert exec(t, %{"query" => "moon", "limit" => 1.0})["output"] =~ "(+5 more)"
  end

  test "G3: works over the shipped stores; plain maps, odd parts and roles" do
    store = Toolnexus.Client.InMemoryConversationStore.new()

    Toolnexus.Client.InMemoryConversationStore.save(store, "only", [
      %{"role" => "user", "content" => "The vault code is 4417"},
      %{
        "role" => 7,
        "content" => [%{"type" => "tool_use"}, "junk", %{"type" => "text", "text" => 1}]
      },
      "not a message",
      %{
        "content" => [%{"type" => "text", "text" => "VAULT again"}],
        "tool_calls" => [%{"x" => 1}, 3]
      }
    ])

    assert exec(TranscriptSearch.tool(store), %{"query" => "VAULT"})["output"] ==
             "only#0 user: The vault code is 4417\nonly#3 unknown: VAULT again"

    assert exec(TranscriptSearch.tool(store), %{"query" => "{}"})["output"] ==
             "only#1 unknown: {}"
  end

  # ---------------- G4 — write safety ----------------

  test "G4: concurrent adds from two tools over one home keep every entry" do
    w = @mh["writeSafety"]["concurrentAdds"]
    dir = mkhome(%{})
    tools = for _ <- 1..w["writers"], do: Home.memory_tool(dir)

    jobs =
      for {t, wi} <- Enum.with_index(tools), n <- 0..(w["addsEach"] - 1) do
        Task.async(fn -> t.execute.(%{"action" => "add", "text" => "w#{wi}-#{n}"}, nil) end)
      end

    Enum.each(Task.await_many(jobs, 30_000), &refute(&1.is_error))

    lines =
      Path.join(dir, "MEMORY.md") |> File.read!() |> String.trim_trailing() |> String.split("\n")

    assert length(lines) == w["expectEntries"]
    assert length(Enum.uniq(lines)) == w["expectEntries"]
  end

  test "G4: a 3 MB MEMORY.md + add grows on disk, nothing truncated, no temp file" do
    l = @mh["writeSafety"]["largeFileAdd"]
    original = String.duplicate(l["fill"], l["bytes"])
    dir = mkhome(%{"MEMORY.md" => original})

    r =
      Home.memory_tool(dir, notes: true).execute.(
        %{"action" => "add", "text" => l["addText"]},
        nil
      )

    refute r.is_error, r.output
    assert File.read!(Path.join(dir, "MEMORY.md")) == original <> "\n- #{l["addText"]}\n"
    assert File.ls!(dir) == ["MEMORY.md"]
  end

  # ---------------- Dreaming ----------------

  # The dreaming fixture's scripted LLM: the dreamer is recognised by its system
  # prompt ("You are dreaming:"), every other call by model id.
  defp dream_llm(script) do
    steps = @dr["mockLLM"]["scripts"][script]

    fn body ->
      msgs = body["messages"] || []

      sys =
        Enum.find_value(msgs, "", fn m ->
          if m["role"] == "system", do: to_string(m["content"])
        end)

      tool = for m <- msgs, m["role"] == "tool", do: to_string(m["content"])
      call = fn n -> hd(Enum.at(steps, n)["toolCalls"]) end

      reply = fn n ->
        Mock.openai_response(Mock.tool_call(call.(n)["name"], call.(n)["args"], "d#{n}"))
      end

      cond do
        String.contains?(sys, "You are dreaming:") and script == "dreamer-idle" ->
          Mock.openai_response(%{"content" => hd(steps)["text"]})

        String.contains?(sys, "You are dreaming:") ->
          case length(tool) do
            n when n in [0, 1, 3] ->
              reply.(n)

            2 ->
              if String.contains?(Enum.at(tool, 0), "Mt. Moon exit is south") and
                   String.contains?(Enum.at(tool, 1), "exit is north"),
                 do: reply.(2),
                 else: Mock.openai_response(%{"content" => "HEARTBEAT_OK"})

            _ ->
              Mock.openai_response(%{"content" => Enum.at(steps, 4)["text"]})
          end

        body["model"] == "m-navigator" ->
          text =
            if String.contains?(sys, "Mt. Moon exit is north"),
              do: "Head north out of Mt. Moon.",
              else: "Head south out of Mt. Moon."

          Mock.openai_response(%{"content" => text})

        true ->
          Mock.openai_response(%{"content" => "HEARTBEAT_OK"})
      end
    end
  end

  defp dr_opts(script \\ "dreamer-fix"), do: %{transport: Mock.transport(dream_llm(script))}

  test "D0: dream_prompt is the fixture's exact text" do
    assert Home.dream_prompt() == @dr["dreamPrompt"]
  end

  test "D1 BUILD GATE (Mt. Moon): one pass fixes the claim, logs it, next session answers differently" do
    g = @dr["expect"]["D1_mtMoonBuildGate"]
    home = mkhome(@dr["home"])

    before =
      Agents.run(
        Home.from_dir(home, model: g["before"]["model"]),
        dr_opts(),
        g["before"]["prompt"]
      )

    assert before.text == g["before"]["text"]

    pass = Agents.run(Home.dreamer(home, store: dr_store()), dr_opts(), Home.heartbeat_prompt())
    assert pass.text == g["pass"]["replyText"]
    snap = snapshot(home)
    for {f, body} <- g["after"]["files"], do: assert(snap[f] == body, f)

    assert snap["memory/log.md"]
           |> String.split("\n")
           |> Enum.count(&String.starts_with?(&1, "- ")) == 1

    nx = g["after"]["nextSession"]
    after_ = Agents.run(Home.from_dir(home, model: nx["model"]), dr_opts(), nx["prompt"])
    assert after_.text == nx["text"]

    # D2 (second case): a second pass finds nothing to do — silent, nothing written.
    frozen = snapshot(home)
    again = Agents.run(Home.dreamer(home, store: dr_store()), dr_opts(), Home.heartbeat_prompt())
    assert again.text =~ "HEARTBEAT_OK"
    assert snapshot(home) == frozen
  end

  test "D2: an idle dreamer (HEARTBEAT_OK) writes nothing" do
    home = mkhome(@dr["home"])
    frozen = snapshot(home)

    r =
      Agents.run(
        Home.dreamer(home, store: dr_store()),
        dr_opts("dreamer-idle"),
        Home.heartbeat_prompt()
      )

    assert r.text =~ "HEARTBEAT_OK"
    assert snapshot(home) == frozen
  end

  test "D3: the dreamer's tools are exactly memory (notes on) + transcript_search" do
    e = @dr["expect"]["D3_dreamerToolsExactly"]
    home = mkhome(@dr["home"])

    fake = fn name ->
      Toolnexus.define_tool(%{name: name, description: name, execute: fn _ -> "x" end})
    end

    persona = Home.from_dir(home, tools: Enum.map(e["personaTools"], fake))
    assert Enum.map(persona.spec.uses.tools, & &1.name) == e["personaTools"] ++ ["memory"]
    tools = Home.dreamer(home, store: dr_store()).spec.uses.tools
    assert Enum.map(tools, & &1.name) == e["dreamerTools"]
    assert hd(tools).description == Home.memory_notes_description() == e["dreamerMemoryHasNotes"]
  end

  test "D3b: dreamer soul = DREAM_PROMPT + DREAM.md + instructions; name; store checks" do
    e = @dr["expect"]["D3b_dreamerSoul"]
    plain = mkhome(@dr["home"])
    d = Home.dreamer(plain, store: dr_store())
    assert d.spec.soul == e["soulWithoutExtras"]
    assert d.name == "#{Path.basename(plain)}-dreamer"
    assert d.spec.model == "inherit"
    assert d.spec.does == "dreams over the memory in #{plain}"

    with_extras = mkhome(Map.put(@dr["home"], "DREAM.md", e["DREAM.md"]))
    x = Home.dreamer(with_extras, store: dr_store(), instructions: e["instructions"])
    assert x.spec.soul == e["soul"]

    assert_raise ArgumentError, ~r/list\(\)/, fn ->
      Home.dreamer(plain, store: %Toolnexus.TestNoListStore{})
    end

    assert_raise ArgumentError, ~r/store is required/, fn -> Home.dreamer(plain, []) end

    assert_raise ArgumentError, fn ->
      Home.from_dir(plain, dream: %{every_ms: 0, store: dr_store()})
    end
  end

  test "D4: from_dir dream runs the dreamer on the persona's runtime; its replies never reach on_beat" do
    e = @dr["expect"]["D4_fromDirDream"]
    home = mkhome(@dr["home"])
    clk = VClock.new()
    me = self()

    persona = Home.from_dir(home, model: "m-quiet", dream: %{every_ms: 50, store: dr_store()})

    started =
      Home.start_agent(persona, Map.put(dr_opts(), :clock, VClock.clock(clk)),
        every_ms: 1000,
        on_beat: fn t -> send(me, {:beat, t}) end
      )

    VClock.advance(clk, 50)
    Home.sync(started)
    snap = snapshot(home)
    for {f, body} <- e["after"]["files"], do: assert(snap[f] == body, f)
    assert Enum.any?(Runtime.trace(started.runtime), &(inspect(&1) =~ "-dreamer"))
    assert Home.beats(started) == []
    Home.stop(started)
    refute_received {:beat, _}
  end

  test "D5: absent dream option — no dreamer, nothing written" do
    home = mkhome(@dr["home"])
    frozen = snapshot(home)
    clk = VClock.new()
    persona = Home.from_dir(home, model: "m-quiet")
    assert map_size(Agents.registry(persona)) == 1

    started =
      Home.start_agent(persona, Map.put(dr_opts(), :clock, VClock.clock(clk)), every_ms: 50)

    assert started.dream_heartbeat == nil

    for _ <- 1..4 do
      VClock.advance(clk, 50)
      Home.sync(started)
    end

    assert Home.beats(started) == []
    refute Enum.any?(Runtime.trace(started.runtime), &(inspect(&1) =~ "dreamer"))
    Home.stop(started)
    assert snapshot(home) == frozen
  end
end
