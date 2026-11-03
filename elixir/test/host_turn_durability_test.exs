defmodule Toolnexus.HostTurnDurabilityTest do
  @moduledoc """
  add-host-turn-durability (elixir): interrupted stateful turns are saved (SPEC §8),
  builtin `kill_grace_ms` / `killGraceMs` (SPEC §4), and edit diffs on
  edit / write / apply_patch results (SPEC §4 "Edit diffs").
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Builtin, Client}
  alias Toolnexus.Client.{InMemoryConversationStore, TurnGuard}

  # ---- mock wire -------------------------------------------------------------

  defp client(style, transport, store) do
    Client.create(
      base_url: "http://transport.invalid",
      style: style,
      model: "m",
      api_key: "k",
      transport: transport,
      store: store,
      retries: 0
    )
  end

  defp two_calls("openai", false) do
    %{
      "choices" => [
        %{
          "message" => %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" => [
              %{
                "id" => "c1",
                "type" => "function",
                "function" => %{"name" => "fast", "arguments" => "{}"}
              },
              %{
                "id" => "c2",
                "type" => "function",
                "function" => %{"name" => "slow", "arguments" => "{}"}
              }
            ]
          }
        }
      ]
    }
  end

  defp two_calls("anthropic", false) do
    %{
      "content" => [
        %{"type" => "tool_use", "id" => "c1", "name" => "fast", "input" => %{}},
        %{"type" => "tool_use", "id" => "c2", "name" => "slow", "input" => %{}}
      ],
      "stop_reason" => "tool_use"
    }
  end

  defp two_calls("openai", true) do
    sse([
      ~s({"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"fast","arguments":"{}"}}]}}]}),
      ~s({"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"slow","arguments":"{}"}}]}}]}),
      "[DONE]"
    ])
  end

  defp two_calls("anthropic", true) do
    sse([
      ~s({"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"c1","name":"fast"}}),
      ~s({"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}),
      ~s({"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"c2","name":"slow"}}),
      ~s({"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}),
      ~s({"type":"message_delta","delta":{"stop_reason":"tool_use"}})
    ])
  end

  defp sse(payloads), do: Enum.map_join(payloads, "\n\n", &("data: " <> &1))

  defp toolkit(me) do
    fast =
      Toolnexus.Native.define_tool(%{
        name: "fast",
        description: "Returns at once.",
        input_schema: %{"type" => "object"},
        execute: fn _ -> "first-done" end
      })

    slow =
      Toolnexus.Native.define_tool(%{
        name: "slow",
        description: "Never returns.",
        input_schema: %{"type" => "object"},
        execute: fn _ ->
          send(me, :slow_started)
          Process.sleep(:infinity)
        end
      })

    {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [fast, slow])
    tk
  end

  defp await_saved(store, id, tries \\ 100) do
    case InMemoryConversationStore.get(store, id) do
      nil when tries > 0 ->
        Process.sleep(10)
        await_saved(store, id, tries - 1)

      msgs ->
        msgs
    end
  end

  defp assert_interrupted("openai", msgs) do
    assert Enum.any?(msgs, &(&1["role"] == "user" and &1["content"] == "go"))
    assert [%{"tool_calls" => [_, _]}] = Enum.filter(msgs, &(&1["role"] == "assistant"))
    tools = Enum.filter(msgs, &(&1["role"] == "tool"))

    assert Enum.map(tools, &{&1["tool_call_id"], &1["content"]}) ==
             [
               {"c1", "first-done"},
               {"c2",
                "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first."}
             ]
  end

  defp assert_interrupted("anthropic", msgs) do
    assert [%{"role" => "user", "content" => "go"}, %{"role" => "assistant"}, last] = msgs
    assert %{"role" => "user", "content" => [b1, b2]} = last
    assert {b1["tool_use_id"], b1["content"], b1["is_error"]} == {"c1", "first-done", false}

    assert {b2["tool_use_id"], b2["content"], b2["is_error"]} ==
             {"c2",
              "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.",
              true}
  end

  # ---- interrupted turns are saved ----------------------------------------------

  for style <- ["openai", "anthropic"] do
    @style style

    test "#{style}: ask cancelled mid second tool saves prompt, pair, and interrupted result" do
      me = self()
      store = InMemoryConversationStore.new()

      c =
        client(
          @style,
          fn _ -> {:ok, %{status: 200, headers: %{}, body: two_calls(@style, false)}} end,
          store
        )

      tk = toolkit(me)

      {pid, mon} = spawn_monitor(fn -> Client.ask(c, "go", tk, id: "t") end)
      assert_receive :slow_started, 2_000
      Process.sleep(100)
      Process.exit(pid, :shutdown)
      # the cancellation surfaces to whoever ran the turn
      assert_receive {:DOWN, ^mon, :process, ^pid, :shutdown}

      assert_interrupted(@style, await_saved(store, "t"))
    end

    test "#{style}: stream halted mid second tool saves the same transcript" do
      me = self()
      store = InMemoryConversationStore.new()

      c =
        client(
          @style,
          fn _ -> {:ok, %{status: 200, headers: %{}, body: two_calls(@style, true)}} end,
          store
        )

      c
      |> Client.stream("go", toolkit(me), id: "t")
      |> Enum.reduce_while(nil, fn
        %{type: "tool_call", name: "slow"}, acc ->
          assert_receive :slow_started, 2_000
          Process.sleep(100)
          {:halt, acc}

        _, acc ->
          {:cont, acc}
      end)

      assert_interrupted(@style, await_saved(store, "t"))
    end

    test "#{style}: provider error on the first call saves prior + prompt; error still raised" do
      prior = [
        %{"role" => "user", "content" => "earlier"},
        %{"role" => "assistant", "content" => "ok"}
      ]

      bad = fn _ -> {:ok, %{status: 401, headers: %{}, body: %{"error" => "bad key"}}} end

      for mode <- [:ask, :stream] do
        store = InMemoryConversationStore.new()
        InMemoryConversationStore.save(store, "p", prior)
        c = client(@style, bad, store)

        assert_raise Toolnexus.ProviderError, fn ->
          case mode do
            :ask -> Client.ask(c, "next", [], id: "p")
            :stream -> c |> Client.stream("next", [], id: "p") |> Enum.to_list()
          end
        end

        assert InMemoryConversationStore.get(store, "p") ==
                 prior ++ [%{"role" => "user", "content" => "next"}]
      end
    end
  end

  test "a successful stateful turn saves once and the guard stands down" do
    store = InMemoryConversationStore.new()

    ok = fn _ ->
      {:ok,
       %{
         status: 200,
         headers: %{},
         body: %{"choices" => [%{"message" => %{"role" => "assistant", "content" => "hi"}}]}
       }}
    end

    r = Client.ask(client("openai", ok, store), "go", [], id: "s")
    assert r.text == "hi"
    assert List.last(InMemoryConversationStore.get(store, "s"))["content"] == "hi"
    assert TurnGuard.current() == nil
  end

  test "repair leaves complete transcripts alone" do
    msgs = [
      %{"role" => "user", "content" => "x"},
      %{"role" => "assistant", "content" => [%{"type" => "text", "text" => "y"}]}
    ]

    assert TurnGuard.repair(msgs, "anthropic", %{}) == msgs

    assert TurnGuard.repair([%{"role" => "user", "content" => "x"}], "anthropic", %{}) == [
             %{"role" => "user", "content" => "x"}
           ]

    assert TurnGuard.repair(msgs, "openai", %{}) == msgs
  end

  # ---- killGraceMs ----------------------------------------------------------------

  defp alive?(pid), do: match?({_, 0}, System.cmd("kill", ["-0", pid], stderr_to_stdout: true))

  defp read_pid(path, tries \\ 300) do
    case File.read(path) do
      {:ok, s} when s != "" ->
        String.trim(s)

      _ when tries > 0 ->
        Process.sleep(10)
        read_pid(path, tries - 1)
    end
  end

  defp cancel_once(cfg, dir) do
    for f <- ~w(p c g), do: File.rm(Path.join(dir, f))
    bash = Enum.find(Builtin.tools(cfg), &(&1.name == "bash"))

    # the job ignores TERM, and so do its background child and grandchild
    cmd =
      "trap '' TERM; (sleep 30 & echo $! > #{dir}/g; wait) & echo $! > #{dir}/c; echo $$ > #{dir}/p; wait"

    caller = spawn(fn -> bash.execute.(%{"command" => cmd}, nil) end)
    pids = Enum.map(~w(p c g), &read_pid(Path.join(dir, &1)))
    assert Enum.all?(pids, &alive?/1)

    t0 = System.monotonic_time(:millisecond)
    Process.exit(caller, :kill)
    wait_gone(pids, t0)
  end

  defp wait_gone(pids, t0) do
    elapsed = System.monotonic_time(:millisecond) - t0

    cond do
      not Enum.any?(pids, &alive?/1) -> elapsed
      elapsed > 5_000 -> flunk("job tree still alive after 5 s: #{inspect(pids)}")
      true -> wait_gone(pids, t0)
    end
  end

  @tag :tmp_dir
  @tag :posix
  test "killGraceMs: 100 kills a TERM-ignoring job tree within 1 s", %{tmp_dir: dir} do
    if match?({:win32, _}, :os.type()) do
      :ok
    else
      times = for _ <- 1..10, do: cancel_once(%{"killGraceMs" => 100}, dir)
      sorted = Enum.sort(times)

      IO.puts(
        "\nkillGraceMs=100 cancel->gone ms: min=#{hd(sorted)} median=#{Enum.at(sorted, 5)} max=#{List.last(sorted)} all=#{inspect(times)}"
      )

      assert List.last(sorted) < 1_000

      # absent / 0 keeps the 2000 ms default
      default = cancel_once(%{kill_grace_ms: 0}, dir)
      IO.puts("default grace cancel->gone ms: #{default}")
      assert default >= 1_900
    end
  end

  # ---- edit diffs -----------------------------------------------------------------

  defp tool(cfg, name), do: Enum.find(Builtin.tools(cfg), &(&1.name == name))

  @tag :tmp_dir
  test "edit of one line reports modified 1/1 with the -/+ lines", %{tmp_dir: dir} do
    File.write!(Path.join(dir, "f.txt"), "a\nold\nc\n")

    r =
      tool(%{base_dir: dir}, "edit").execute.(
        %{"path" => "f.txt", "oldString" => "old", "newString" => "new"},
        nil
      )

    refute r.is_error
    assert r.output == "Edited f.txt (1 replacement)"
    assert r.metadata.replacements == 1
    assert r.metadata.files == [%{path: "f.txt", status: "modified", additions: 1, deletions: 1}]
    assert r.metadata.diff =~ "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n"
    assert r.metadata.diff =~ "\n-old\n+new\n"
  end

  @tag :tmp_dir
  test "write of a new 3-line file is added 3/0; overwrite is modified", %{tmp_dir: dir} do
    w = tool(%{"baseDir" => dir}, "write")
    r = w.execute.(%{"path" => "sub/n.txt", "content" => "1\n2\n3\n"}, nil)
    assert r.output == "Wrote 6 bytes to sub/n.txt"
    assert r.metadata.files == [%{path: "sub/n.txt", status: "added", additions: 3, deletions: 0}]
    assert r.metadata.diff =~ "--- /dev/null\n+++ b/sub/n.txt\n@@ -0,0 +1,3 @@\n+1\n+2\n+3\n"

    r = w.execute.(%{"path" => "sub/n.txt", "content" => "1\n2\n3\n"}, nil)

    assert r.metadata.files == [
             %{path: "sub/n.txt", status: "modified", additions: 0, deletions: 0}
           ]
  end

  @tag :tmp_dir
  test "apply_patch adding and deleting a file", %{tmp_dir: dir} do
    File.write!(Path.join(dir, "gone.txt"), "x\ny\n")

    patch = """
    *** Begin Patch
    *** Add File: new.txt
    +hello
    +world
    *** Delete File: gone.txt
    *** End Patch
    """

    r = tool(%{base_dir: dir}, "apply_patch").execute.(%{"patchText" => patch}, nil)
    refute r.is_error
    assert r.output == "Applied patch: 2 file operations"

    assert r.metadata.files == [
             %{path: "new.txt", status: "added", additions: 2, deletions: 0},
             %{path: "gone.txt", status: "deleted", additions: 0, deletions: 2}
           ]

    assert r.metadata.diff =~ "--- a/gone.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-x\n-y\n"
    assert r.metadata.diff =~ "+++ b/new.txt"
  end

  test "without base_dir the diff path is the path given" do
    dir = Path.join(System.tmp_dir!(), "tn-diff-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    p = Path.join(dir, "a.txt")
    r = tool(nil, "write").execute.(%{"path" => p, "content" => "z"}, nil)
    assert [%{path: ^p, status: "added", additions: 1}] = r.metadata.files
    File.rm_rf!(dir)
  end

  test "streaming tool_result carries the result metadata" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    t =
      Toolnexus.Native.define_tool(%{
        name: "m",
        description: "meta",
        input_schema: %{"type" => "object"},
        execute: fn _ -> %Toolnexus.ToolResult{output: "o", metadata: %{k: 1}} end
      })

    {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [t])

    transport = fn _ ->
      body =
        if Agent.get_and_update(n, &{&1, &1 + 1}) == 0,
          do:
            sse([
              ~s({"choices":[{"delta":{"tool_calls":[{"index":0,"id":"x","function":{"name":"m","arguments":"{}"}}]}}]}),
              "[DONE]"
            ]),
          else: sse([~s({"choices":[{"delta":{"content":"done"}}]}), "[DONE]"])

      {:ok, %{status: 200, headers: %{}, body: body}}
    end

    events =
      client("openai", transport, InMemoryConversationStore.new())
      |> Client.stream("go", tk)
      |> Enum.to_list()

    assert %{metadata: %{k: 1}} = Enum.find(events, &(&1.type == "tool_result"))
  end
end
