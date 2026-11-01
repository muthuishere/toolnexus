defmodule Toolnexus.HostEngineAsksTest do
  @moduledoc """
  SPEC §4C host additions (change `add-go-host-asks`) and prompt caching / tool tiers
  (change `add-prompt-cache`), ported from the golang reference tests:
  resolve_key_test.go, host_engine_asks_test.go, host_builtin_extras_test.go,
  tool_tiers_test.go and prompt_cache_test.go. MCP cases run against the shared
  examples/mcp-progress-server.mjs fixture (which also offers a resource).
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Answer, Builtin, Client, Context, Mcp, Request, Tool, ToolHints, ToolResult, Toolkit}
  alias Toolnexus.Builtin.BackgroundShells

  @repo_root Path.expand("../..", __DIR__)
  @fixture Path.join(@repo_root, "examples/mcp-progress-server.mjs")

  # ---- helpers ------------------------------------------------------------------

  defp transport(fun), do: fn req -> {:ok, %{status: 200, headers: %{}, body: fun.(req.body)}} end

  defp openai_text(text),
    do: %{
      "choices" => [%{"message" => %{"role" => "assistant", "content" => text}}],
      "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
    }

  defp openai_calls(calls),
    do: %{
      "choices" => [
        %{
          "message" => %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" =>
              Enum.map(calls, fn {id, name, args} ->
                %{"id" => id, "type" => "function", "function" => %{"name" => name, "arguments" => Jason.encode!(args)}}
              end)
          }
        }
      ],
      "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
    }

  defp anthropic(content, extra_usage \\ %{}),
    do: %{
      "content" => content,
      "stop_reason" => "tool_use",
      "usage" => Map.merge(%{"input_tokens" => 1, "output_tokens" => 1}, extra_usage)
    }

  defp tool_results?(body),
    do: Enum.any?(body["messages"], &(&1["role"] == "tool" or (is_list(&1["content"]) and Enum.any?(&1["content"], fn b -> is_map(b) and b["type"] == "tool_result" end))))

  # An auth-gated tool: suspends until a §10 answer arrives.
  defp balance_tool do
    Toolnexus.define_tool(
      name: "get_balance",
      description: "Get the balance.",
      execute: fn _args, ctx ->
        if ctx.answer,
          do: "balance: 42",
          else: %ToolResult{
            output: "sign in",
            is_error: true,
            metadata: %{pending: %Request{id: "r1", kind: "authorization", prompt: "sign in"}}
          }
      end
    )
  end

  defp balance_llm do
    transport(fn body ->
      if tool_results?(body), do: openai_text("done"), else: openai_calls([{"c1", "get_balance", %{}}])
    end)
  end

  defp with_env(pairs, fun) do
    saved = Map.new(pairs, fn {k, _} -> {k, System.get_env(k)} end)

    Enum.each(pairs, fn
      {k, nil} -> System.delete_env(k)
      {k, v} -> System.put_env(k, v)
    end)

    try do
      fun.()
    after
      Enum.each(saved, fn
        {k, nil} -> System.delete_env(k)
        {k, v} -> System.put_env(k, v)
      end)
    end
  end

  defp node?, do: System.find_executable("node") != nil

  # ---- API key by host (security) --------------------------------------------------

  describe "api key by host (resolve_key_test.go)" do
    # The key a client would send, read off a list_models request through the transport.
    defp sent_key(opts) do
      me = self()

      t = fn req ->
        send(me, {:auth, req.headers["authorization"] || req.headers["x-api-key"]})
        {:ok, %{status: 200, headers: %{}, body: %{"data" => []}}}
      end

      case Client.list_models([transport: t, model: "m"] ++ opts) do
        {:ok, []} ->
          receive do
            {:auth, "Bearer " <> k} -> {:ok, k}
            {:auth, k} -> {:ok, k}
          end

        {:error, msg} ->
          {:error, msg}
      end
    end

    test "each provider host gets only its own env var; explicit key and api_key_env win" do
      with_env(
        [
          {"OPENROUTER_API_KEY", "fake-or"},
          {"OPENAI_API_KEY", "fake-oa"},
          {"ANTHROPIC_API_KEY", "fake-an"},
          {"TN_TEST_KEY", "fake-custom"}
        ],
        fn ->
          cases = [
            {[base_url: "https://api.openai.com/v1"], "fake-oa"},
            {[base_url: "https://api.anthropic.com", style: "anthropic"], "fake-an"},
            {[base_url: "https://openrouter.ai/api/v1", style: "anthropic"], "fake-or"},
            {[base_url: "https://eu.openrouter.ai/api/v1"], "fake-or"},
            {[base_url: "https://API.OpenAI.com/v1"], "fake-oa"},
            {[base_url: "https://example.com/v1", api_key: "fake-explicit"], "fake-explicit"},
            {[base_url: "https://api.openai.com/v1", api_key: "fake-explicit"], "fake-explicit"},
            {[base_url: "https://example.com/v1", api_key_env: "TN_TEST_KEY"], "fake-custom"}
          ]

          for {opts, want} <- cases do
            assert sent_key(opts) == {:ok, want}, inspect(opts)
          end

          # never a cross-provider fallback
          for base <- [
                "https://example.com/v1",
                "http://api.openai.com/v1",
                "https://api.openai.com.evil.test/",
                "https://evil-openrouter.ai/v1",
                "http://localhost:11434/v1"
              ] do
            assert {:error, msg} = sent_key(base_url: base)
            assert msg =~ "No API key"
            refute msg =~ "fake-"
          end
        end
      )

      # the right env var unset ⇒ an error naming it, not another provider's key
      with_env([{"OPENAI_API_KEY", nil}, {"OPENROUTER_API_KEY", "fake-or"}], fn ->
        assert {:error, msg} = sent_key(base_url: "https://api.openai.com/v1")
        assert msg =~ "OPENAI_API_KEY"
      end)
    end

    test "the run loop enforces it too" do
      with_env([{"OPENAI_API_KEY", "fake-oa"}], fn ->
        assert_raise ArgumentError, ~r/No API key/, fn ->
          Client.run(Client.create(base_url: "http://127.0.0.1:1", model: "m"), "x", [])
        end
      end)
    end
  end

  # ---- list_models -----------------------------------------------------------------

  describe "list_models" do
    test "openai shape (with OpenRouter context_length), anthropic pagination, errors" do
      me = self()

      t = fn req ->
        send(me, {:url, req.url})

        body =
          cond do
            req.headers["x-api-key"] && not String.contains?(req.url, "after_id") ->
              %{"data" => [%{"id" => "claude-b", "display_name" => "B"}], "has_more" => true, "last_id" => "claude-b"}

            req.headers["x-api-key"] ->
              %{"data" => [%{"id" => "claude-a", "display_name" => "A"}], "has_more" => false}

            true ->
              %{"data" => [%{"id" => "z/model", "name" => "Z", "context_length" => 200_000}, %{"id" => "a/model"}]}
          end

        {:ok, %{status: 200, headers: %{}, body: body}}
      end

      assert {:ok, [a, z]} = Client.list_models(base_url: "https://h/api/v1", api_key: "fake", transport: t)
      assert a == %{id: "a/model", name: nil, context_length: nil}
      assert z.context_length == 200_000
      assert_received {:url, "https://h/api/v1/models"}

      assert {:ok, [ca, cb]} =
               Client.list_models(base_url: "https://h/v1", style: "anthropic", api_key: "fake", transport: t)

      assert ca.id == "claude-a" and cb.name == "B"
      assert_received {:url, "https://h/v1/models?limit=1000"}
      assert_received {:url, "https://h/v1/models?limit=1000&after_id=claude-b"}

      err = fn _ -> {:ok, %{status: 401, headers: %{}, body: ~s({"error":"bad"})}} end
      assert {:error, msg} = Client.list_models(base_url: "https://h", api_key: "fake", transport: err)
      assert msg =~ "list models: HTTP 401"

      assert {:error, _} = Client.list_models(base_url: "https://h", transport: t)
    end

    test "default Req transport (plug)" do
      plug = fn conn -> Req.Test.json(conn, %{"data" => [%{"id" => "m1"}]}) end

      assert {:ok, [%{id: "m1"}]} =
               Client.list_models(base_url: "https://h/v1", api_key: "fake", http_options: [plug: plug])
    end
  end

  # ---- wait_for_ctx ------------------------------------------------------------------

  describe "wait_for_ctx" do
    test "resolves, wins over wait_for, and sees the call id" do
      c =
        Client.create(
          base_url: "https://x",
          model: "m",
          api_key: "k",
          transport: balance_llm(),
          wait_for: fn _ -> flunk("wait_for must not run when wait_for_ctx is set") end,
          wait_for_ctx: fn %Context{call_id: "c1", signal: pid}, req ->
            assert is_pid(pid)
            %Answer{id: req.id, ok: true}
          end
        )

      r = Client.run(c, "what is my balance?", [balance_tool()])
      assert r.status == "done"
      assert hd(r.tool_calls).output == "balance: 42"
    end

    test "a pending ask ends with the turn" do
      me = self()

      c =
        Client.create(
          base_url: "https://x",
          model: "m",
          api_key: "k",
          transport: balance_llm(),
          wait_for_ctx: fn ctx, _req ->
            send(me, {:waiting, self(), ctx.signal})
            # A resolver waiting elsewhere monitors the turn and gives up with it.
            ref = Process.monitor(ctx.signal)

            receive do
              {:DOWN, ^ref, _, _, _} -> exit(:turn_gone)
            end
          end
        )

      runner = spawn(fn -> Client.run(c, "what is my balance?", [balance_tool()]) end)
      assert_receive {:waiting, waiter, signal}, 2_000
      assert signal == runner
      wref = Process.monitor(waiter)
      Process.exit(runner, :kill)
      assert_receive {:DOWN, ^wref, _, _, _}, 2_000
    end
  end

  # ---- hints + permission gates ----------------------------------------------------------

  describe "tool hints" do
    test "every builtin carries hints (builtin_hints)" do
      ro = ~w(read grep glob webfetch question todowrite)

      for t <- Builtin.tools() do
        assert %ToolHints{} = t.hints, t.name
        assert t.hints.read_only == t.name in ro, t.name
        assert t.hints.destructive == t.name not in ro, t.name
      end

      assert Enum.find(Builtin.tools(), &(&1.name == "bash")).hints.open_world
    end

    test "MCP annotations map with MCP's defaults" do
      assert Mcp.mcp_hints(nil) == nil
      assert Mcp.mcp_hints(%{"title" => "x"}) == nil
      assert Mcp.mcp_hints(%{"readOnlyHint" => true}) == %ToolHints{read_only: true, destructive: false, open_world: true}

      assert Mcp.mcp_hints(%{"destructiveHint" => false, "openWorldHint" => false}) ==
               %ToolHints{read_only: false, destructive: false, open_world: false}

      assert Mcp.mcp_hints(%{"readOnlyHint" => false}) == %ToolHints{destructive: true, open_world: true}
    end

    test "a plan-mode gate on before_tool denies a mutating tool; the run finishes" do
      tool = %{balance_tool() | hints: %ToolHints{destructive: true}}
      me = self()

      c =
        Client.create(
          base_url: "https://x",
          model: "m",
          api_key: "k",
          transport: balance_llm(),
          hooks: %{
            before_tool: fn ev ->
              send(me, {:hints, ev.hints})

              unless ev.hints && ev.hints.read_only,
                do: %{result: %ToolResult{output: "denied: plan mode allows read-only tools only", is_error: true}}
            end
          }
        )

      r = Client.run(c, "balance?", [tool])
      assert r.status == "done"
      assert_received {:hints, %ToolHints{destructive: true}}
      assert [%{is_error: true, output: "denied: plan mode allows read-only tools only"}] = r.tool_calls
    end
  end

  # ---- call id on the tool context ---------------------------------------------------

  describe "ToolContext call_id" do
    defp id_tool(name, me) do
      %Tool{
        name: name,
        description: name,
        input_schema: %{"type" => "object"},
        source: "custom",
        execute: fn _a, ctx ->
          send(me, {:call_id, name, ctx.call_id})
          %ToolResult{output: "ok"}
        end
      }
    end

    defp sse(events), do: Enum.map_join(events, "\n\n", &("data: " <> Jason.encode!(&1))) <> "\n\ndata: [DONE]\n\n"

    test "parallel calls each see their own id: run + stream, openai + anthropic" do
      me = self()
      tools = [id_tool("fast", me), id_tool("slow", me)]

      openai = transport(fn _ -> openai_calls([{"c1", "fast", %{}}, {"c2", "slow", %{}}]) end)

      anthropic_t =
        transport(fn _ ->
          anthropic([
            %{"type" => "tool_use", "id" => "c1", "name" => "fast", "input" => %{}},
            %{"type" => "tool_use", "id" => "c2", "name" => "slow", "input" => %{}}
          ])
        end)

      openai_sse =
        transport(fn _ ->
          sse([
            %{
              "choices" => [
                %{
                  "delta" => %{
                    "tool_calls" => [
                      %{"index" => 0, "id" => "c1", "function" => %{"name" => "fast", "arguments" => "{}"}},
                      %{"index" => 1, "id" => "c2", "function" => %{"name" => "slow", "arguments" => "{}"}}
                    ]
                  }
                }
              ]
            },
            %{"choices" => [%{"delta" => %{}, "finish_reason" => "tool_calls"}]}
          ])
        end)

      anthropic_sse =
        transport(fn _ ->
          sse([
            %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}},
            %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "tool_use", "id" => "c1", "name" => "fast"}},
            %{"type" => "content_block_delta", "index" => 0, "delta" => %{"type" => "input_json_delta", "partial_json" => "{}"}},
            %{"type" => "content_block_start", "index" => 1, "content_block" => %{"type" => "tool_use", "id" => "c2", "name" => "slow"}},
            %{"type" => "content_block_delta", "index" => 1, "delta" => %{"type" => "input_json_delta", "partial_json" => "{}"}},
            %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}, "usage" => %{"output_tokens" => 1}}
          ])
        end)

      for {style, t, mode} <- [
            {"openai", openai, :run},
            {"anthropic", anthropic_t, :run},
            {"openai", openai_sse, :stream},
            {"anthropic", anthropic_sse, :stream}
          ] do
        c = Client.create(base_url: "https://x", style: style, model: "m", api_key: "k", max_turns: 1, transport: t)

        if mode == :run, do: Client.run(c, "go", tools), else: Client.stream(c, "go", tools) |> Enum.to_list()

        assert_received {:call_id, "fast", "c1"}, "#{style} #{mode}"
        assert_received {:call_id, "slow", "c2"}, "#{style} #{mode}"
      end
    end
  end

  # ---- MCP: per-server readiness + resource tools ------------------------------------

  describe "MCP (examples/mcp-progress-server.mjs)" do
    @describetag timeout: 60_000

    test "on_server reports the fast server first; tools registered from the callback work" do
      if node?() do
        me = self()
        t0 = System.monotonic_time(:millisecond)

        cfg = %{
          "fast" => %{"command" => ["node", @fixture], "timeout" => 5000},
          "slow" => %{"command" => ["sh", "-c", "sleep 1; exec node #{@fixture}"], "timeout" => 5000},
          "bad" => %{"command" => ["/nonexistent/toolnexus-no-such-binary"], "timeout" => 2000},
          "off" => %{"command" => ["node", @fixture], "enabled" => false}
        }

        src =
          Mcp.load(cfg,
            on_server: fn r -> send(me, {:server, r.name, r, System.monotonic_time(:millisecond) - t0}) end
          )

        results =
          for _ <- 1..4, into: %{} do
            assert_receive {:server, name, r, at}, 10_000
            {name, {r, at}}
          end

        {fast, fast_at} = results["fast"]
        {slow, slow_at} = results["slow"]
        assert fast.status == "connected"
        assert [%Tool{name: "fast_slow"}] = fast.tools
        assert slow.status == "connected"
        assert elem(results["bad"], 0).status == "failed" and is_binary(elem(results["bad"], 0).error)
        assert elem(results["off"], 0) == %{name: "off", status: "disabled", tools: [], error: nil}
        assert fast_at < slow_at and slow_at - fast_at >= 500

        tk = Toolkit.register(Toolnexus.create_toolkit!(builtins: false), fast.tools)
        out = Toolkit.execute(tk, "fast_slow", %{"totalMs" => 0, "intervalMs" => 0})
        refute out.is_error
        assert src.status == %{"fast" => "connected", "slow" => "connected", "bad" => "failed", "off" => "disabled"}
        Mcp.close(src)
      end
    end

    test "a load deadline still aborts (on_server path)" do
      cfg = %{"slow" => %{"command" => ["sh", "-c", "sleep 5"], "timeout" => 10_000}}
      assert_raise RuntimeError, ~r/deadline/, fn -> Mcp.load(cfg, deadline: 200, on_server: fn _ -> :ok end) end
    end

    test "resource tools list and read the fixture's resource" do
      if node?() do
        src = Mcp.load(%{"progress" => %{"command" => ["node", @fixture], "timeout" => 5000}})
        [list, read] = Mcp.resource_tools(src)
        assert Enum.all?([list, read], &(&1.hints == %ToolHints{read_only: true}))

        r = list.execute.(%{}, %Context{})
        refute r.is_error
        assert r.output == "progress fixture://hello — hello (text/plain): A fixed text resource."
        assert r.metadata.count == 1

        assert list.execute.(%{"server" => "progress"}, %Context{}).output == r.output
        assert list.execute.(%{"server" => "nope"}, %Context{}).is_error

        assert %ToolResult{output: "hello resource", is_error: false} =
                 read.execute.(%{"server" => "progress", "uri" => "fixture://hello"}, %Context{})

        assert read.execute.(%{"server" => "progress", "uri" => "fixture://nope"}, %Context{}).is_error
        assert read.execute.(%{"server" => "progress", "uri" => ""}, %Context{}).output =~ "uri is required"
        assert read.execute.(%{"server" => "nope", "uri" => "x"}, %Context{}).is_error
        Mcp.close(src)

        # a closed source's server reports an error line, not a crash
        assert list.execute.(%{}, %Context{}).output =~ "progress: error:"
      end
    end

    test "no source ⇒ no resources" do
      [list, _] = Mcp.resource_tools(nil)
      assert %ToolResult{output: "No resources.", metadata: %{count: 0}} = list.execute.(%{}, %Context{})
    end
  end

  # ---- opt-in builtins ---------------------------------------------------------------

  defp extra(cfg, name), do: Enum.find(Builtin.tools(cfg), &(&1.name == name))

  describe "opt-in builtins (host_builtin_extras_test.go)" do
    test "absent by default; bash has no run_in_background" do
      names = Enum.map(Builtin.tools(%{}), & &1.name)
      refute Enum.any?(~w(bash_output kill_shell websearch notebook_edit), &(&1 in names))
      refute Map.has_key?(extra(%{}, "bash").input_schema["properties"], "run_in_background")
    end

    @tag timeout: 30_000
    test "background shell lifecycle" do
      bg = BackgroundShells.new()
      cfg = %{background: bg, kill_grace_ms: 100}
      bash = extra(cfg, "bash")
      out = extra(cfg, "bash_output")
      kill = extra(cfg, "kill_shell")
      assert Map.has_key?(bash.input_schema["properties"], "run_in_background")
      assert out.hints.read_only and kill.hints.destructive

      r = bash.execute.(%{"command" => "echo one; sleep 0.2; echo two", "run_in_background" => true}, nil)
      refute r.is_error
      id = r.metadata.backgroundId

      all =
        Enum.reduce_while(1..100, "", fn _, acc ->
          o = out.execute.(%{"id" => id}, nil)
          acc = acc <> hd(String.split(o.output, "\n[status:"))

          if o.metadata.status == "exited" do
            assert o.metadata.exitCode == 0
            assert o.output =~ "[status: exited with code 0]"
            {:halt, acc}
          else
            Process.sleep(50)
            {:cont, acc}
          end
        end)

      assert length(String.split(all, "one")) == 2 and length(String.split(all, "two")) == 2

      r = bash.execute.(%{"command" => "trap '' TERM; sleep 30 & wait", "run_in_background" => true}, nil)
      id2 = r.metadata.backgroundId
      Process.sleep(150)
      t0 = System.monotonic_time(:millisecond)
      k = kill.execute.(%{"id" => id2}, nil)
      assert k.metadata.killed == true and not k.is_error
      assert System.monotonic_time(:millisecond) - t0 < 2_000
      assert out.execute.(%{"id" => id2}, nil).metadata.status == "killed"
      k2 = kill.execute.(%{"id" => id2}, nil)
      assert k2.metadata.killed == false and k2.output =~ "had already killed"
      assert kill.execute.(%{"id" => id}, nil).output =~ "had already exited with code 0"
      assert out.execute.(%{"id" => "nope"}, nil).is_error
      assert kill.execute.(%{"id" => "nope"}, nil).is_error

      assert [%{id: ^id, running: false, killed: false, exit_code: 0}, %{id: ^id2, killed: true}] =
               BackgroundShells.list(bg)

      assert {:error, _} = BackgroundShells.kill(bg, "nope")
      r3 = bash.execute.(%{"command" => "sleep 30", "run_in_background" => true}, nil)
      assert :ok = BackgroundShells.kill(bg, r3.metadata.backgroundId)
      bash.execute.(%{"command" => "sleep 30", "run_in_background" => true}, nil)
      assert :ok = BackgroundShells.close_all(bg)
      assert Enum.all?(BackgroundShells.list(bg), &(not &1.running))
    end

    test "websearch over a host provider" do
      me = self()

      cfg = %{
        web_search: fn q, n ->
          send(me, {:q, q, n})
          {:ok, [%{title: "A", url: "https://a.example", snippet: "sa"}, %{"title" => "B", "url" => "https://b.example"}]}
        end
      }

      ws = extra(cfg, "websearch")
      assert ws.hints == %ToolHints{read_only: true, open_world: true}
      r = ws.execute.(%{"query" => "go", "limit" => 1}, nil)
      assert_received {:q, "go", 1}
      assert r.output == "1. A\n   https://a.example\n   sa"
      assert ws.execute.(%{"query" => "go"}, nil).output == "1. A\n   https://a.example\n   sa\n\n2. B\n   https://b.example"
      assert ws.execute.(%{"query" => " "}, nil).output == "websearch: query is required"
      assert extra(%{web_search: fn _, _ -> {:ok, []} end}, "websearch").execute.(%{"query" => "x"}, nil).output == "No results."
      assert extra(%{web_search: fn _, _ -> {:error, "down"} end}, "websearch").execute.(%{"query" => "x"}, nil).output == "websearch: down"
      assert extra(%{web_search: fn _, _ -> {:error, :x} end}, "websearch").execute.(%{"query" => "x"}, nil).output == "websearch: :x"
    end

    @tag :tmp_dir
    test "notebook_edit replace / insert / delete", %{tmp_dir: dir} do
      nb =
        ~s({"cells":[{"cell_type":"code","id":"a","metadata":{},"source":["x = 1\\n"],"outputs":[{"output_type":"stream","text":["1"]}],"execution_count":3},{"cell_type":"markdown","id":"b","metadata":{},"source":["# T"]}],"metadata":{},"nbformat":4,"nbformat_minor":5})

      p = Path.join(dir, "n.ipynb")
      File.write!(p, nb)
      ne = extra(%{notebook_edit: true, base_dir: dir}, "notebook_edit")
      load = fn -> (p |> File.read!() |> Jason.decode!())["cells"] end

      r = ne.execute.(%{"path" => "n.ipynb", "cell_id" => "a", "new_source" => "x = 2\ny = 3"}, nil)
      refute r.is_error
      [c0 | _] = load.()
      assert c0["outputs"] == [] and c0["execution_count"] == nil and c0["source"] == ["x = 2\n", "y = 3"]

      r = ne.execute.(%{"path" => "n.ipynb", "cell_index" => 0, "edit_mode" => "insert", "cell_type" => "markdown", "new_source" => "hi"}, nil)
      refute r.is_error
      cells = load.()
      assert length(cells) == 3 and Enum.at(cells, 1)["cell_type"] == "markdown"

      r = ne.execute.(%{"path" => "n.ipynb", "cell_id" => "b", "edit_mode" => "delete"}, nil)
      refute r.is_error
      assert length(load.()) == 2 and is_list(r.metadata.files)

      assert ne.execute.(%{"path" => "n.ipynb", "cell_id" => "zz"}, nil).is_error
      assert ne.execute.(%{"path" => "n.ipynb", "cell_index" => 9}, nil).output =~ "out of range"
      assert ne.execute.(%{"path" => "n.ipynb"}, nil).output =~ "cell_id or cell_index is required"
      assert ne.execute.(%{"path" => "n.ipynb", "cell_index" => 0, "edit_mode" => "zap"}, nil).output =~ "unknown edit_mode"
      assert ne.execute.(%{"path" => "", "cell_index" => 0}, nil).output == "notebook_edit: path is required"
      assert ne.execute.(%{"path" => "missing.ipynb", "cell_index" => 0}, nil).is_error

      # insert first with no cell given; replace a markdown cell into code
      r = ne.execute.(%{"path" => "n.ipynb", "edit_mode" => "insert", "new_source" => ""}, nil)
      assert r.output =~ "Inserted code cell at 0"
      assert hd(load.())["source"] == []
      ne.execute.(%{"path" => "n.ipynb", "cell_index" => 0, "cell_type" => "markdown", "new_source" => "m"}, nil)
      refute Map.has_key?(hd(load.()), "outputs")

      File.write!(Path.join(dir, "bad.ipynb"), "[1]")
      assert ne.execute.(%{"path" => "bad.ipynb", "cell_index" => 0}, nil).output =~ "is not a notebook"
      File.write!(Path.join(dir, "bad2.ipynb"), "{")
      assert ne.execute.(%{"path" => "bad2.ipynb", "cell_index" => 0}, nil).output =~ "is not a notebook:"
    end

    @tag :tmp_dir
    test "grep context and truncation; read window truncation", %{tmp_dir: dir} do
      File.write!(Path.join(dir, "f.txt"), "a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n")
      grep = extra(%{base_dir: dir}, "grep")

      assert grep.execute.(%{"pattern" => "HIT", "context" => 1}, nil).output ==
               "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"

      assert grep.execute.(%{"pattern" => "HIT", "before" => 0, "after" => 4}, nil).output ==
               "f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"

      r = grep.execute.(%{"pattern" => "HIT", "limit" => 1}, nil)
      assert r.output == "f.txt:3:HIT1" and r.metadata == %{count: 1, truncated: true, total: 2}

      # no context ⇒ the pinned bytes and no truncation keys
      r = grep.execute.(%{"pattern" => "HIT"}, nil)
      assert r.output == "f.txt:3:HIT1\nf.txt:8:HIT2" and r.metadata == %{count: 2}

      # a second file starts a new group
      File.write!(Path.join(dir, "g.txt"), "HIT3\n")
      assert grep.execute.(%{"pattern" => "HIT[13]", "context" => 1}, nil).output ==
               "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\ng.txt:1:HIT3"

      read = extra(%{base_dir: dir}, "read")
      r = read.execute.(%{"path" => "f.txt", "limit" => 2}, nil)
      assert r.output == "a\nb" and r.metadata == %{truncated: true, totalLines: 9, nextOffset: 3}
      assert read.execute.(%{"path" => "f.txt", "offset" => 8}, nil).metadata == nil
    end
  end

  # ---- tool tiers ----------------------------------------------------------------------

  defp tier_tools(me) do
    mk = fn name, desc, hints ->
      %{
        Toolnexus.define_tool(
          name: name,
          description: desc,
          input_schema: %{"type" => "object", "properties" => %{"x" => %{"type" => "string"}}},
          execute: fn a ->
            send(me, {:ran, "#{name}(#{a["x"]})"})
            name <> " ok"
          end
        )
        | hints: hints
      }
    end

    [
      mk.("zeta_read", "Read zeta.\nsecond line hidden", %ToolHints{read_only: true}),
      mk.("alpha_read", "Read alpha.", %ToolHints{read_only: true}),
      mk.("deploy", "Deploy the service to production.", %ToolHints{destructive: true}),
      mk.("lint", "Lint the code.", nil)
    ]
  end

  defp tier_kit(me), do: Toolnexus.create_toolkit!(builtins: false, extra_tools: tier_tools(me))

  describe "tool tiers (tool_tiers_test.go)" do
    test "plan, tool_search description, frozen on late register, found by search" do
      tk = tier_kit(self())

      c =
        Client.create(
          base_url: "https://x",
          model: "m",
          api_key: "k",
          tool_tiers: %{core: ["zeta_read", "alpha_read"], deferred: ["deploy"]}
        )

      tools = Toolnexus.ToolTiers.request_tools(Toolnexus.ToolTiers.build_plan(%{core: ["zeta_read", "alpha_read"], deferred: ["deploy"]}, tk.tools), tk.tools)
      assert Enum.map(tools, & &1.name) == ~w(alpha_read zeta_read tool_search call_tool)
      desc = Enum.at(tools, 2).description
      assert desc =~ "- lint — Lint the code."
      assert desc =~ "(name only): deploy"
      refute desc =~ "Deploy the service"
      refute desc =~ "second line hidden"

      plan = Client.tool_tier_plan(c, tk)
      assert plan == %{"deploy" => :deferred, "lint" => :listed, "alpha_read" => :core, "zeta_read" => :core}

      late = Toolnexus.define_tool(name: "late", description: "A late tool.", execute: fn _ -> "late ok" end)
      tk2 = Toolkit.register(tk, late)
      assert Client.tool_tier_plan(c, tk2) == plan

      r = Toolnexus.ToolTiers.search(tk2.tools, %{"query" => "late"})
      assert r.output =~ ~s("name": "late")
      assert r.metadata == %{count: 1, tools: ["late"]}

      :ok = Client.reset_tool_tiers(c, tk2)
      assert Client.tool_tier_plan(c, tk2)["late"] == :listed
      assert Client.tool_tier_plan(Client.create(base_url: "https://x", api_key: "k"), tk) == nil
      assert :ok = Client.reset_tool_tiers(Client.create(base_url: "https://x", api_key: "k"), tk)
      # a bare tool list shares one plan
      assert Client.tool_tier_plan(c, tk.tools)["lint"] == :listed
    end

    test "tool_search: select, missing, keywords + limit, empty query" do
      tools = tier_tools(self())
      s = &Toolnexus.ToolTiers.search(tools, &1)
      assert s.(%{"query" => "select:deploy, lint"}).metadata.tools == ["deploy", "lint"]
      assert s.(%{"query" => "select:deploy,nope"}).metadata.tools == ["deploy"]
      assert %ToolResult{is_error: true, output: "tool_search: no such tool: nope"} = s.(%{"query" => "select:nope"})
      assert s.(%{"query" => "read"}).metadata.tools == ["alpha_read", "zeta_read"]
      assert s.(%{"query" => "read", "limit" => 1}).metadata.tools == ["alpha_read"]
      assert s.(%{"query" => "zzz"}).output == "No matching tools."
      assert s.(%{"query" => ""}).output == "tool_search: query is required"
      assert s.(nil).is_error
      # the meta tools run only inside the loop
      plan = Toolnexus.ToolTiers.build_plan([core: []], tools)
      assert plan.search.execute.(%{}, nil).is_error
      assert Toolnexus.ToolTiers.unwrap("call_tool", nil) == {"", %{}, true}
    end

    test "budget demotes the least-used core tool, then listed tools" do
      tools = tier_tools(self())
      by = Map.new(tools, &{&1.name, &1})
      uses = %{"alpha_read" => 9, "zeta_read" => 1, "lint" => 5, "deploy" => 0}
      count = fn v -> div(byte_size(Jason.encode!(v)) + 3, 4) end
      base = Toolnexus.ToolTiers.build_plan(%{core: ["alpha_read", "zeta_read"]}, tools)

      fixed =
        count.(Toolnexus.ToolTiers.tool_def(base.search)) + count.(Toolnexus.ToolTiers.tool_def(base.call))

      one = count.(Toolnexus.ToolTiers.tool_def(by["alpha_read"]))

      p =
        Toolnexus.ToolTiers.build_plan(
          %{core: ["alpha_read", "zeta_read"], budget_tokens: fixed + one + 15, usage_count: &uses[&1]},
          tools
        )

      assert p.tier["alpha_read"] == :core and p.tier["zeta_read"] == :listed

      p = Toolnexus.ToolTiers.build_plan(%{core: ["alpha_read"], budget_tokens: 1, usage_count: &uses[&1]}, tools)
      assert Enum.all?(p.tier, fn {_n, t} -> t == :deferred end)

      # default budget: 5% of the context window
      p = Toolnexus.ToolTiers.build_plan(%{core: ["alpha_read"], context_window: 20}, tools)
      assert p.tier["alpha_read"] == :deferred
      p = Toolnexus.ToolTiers.build_plan(%{core: ["alpha_read"], context_window: 10_000_000}, tools)
      assert p.tier["alpha_read"] == :core
    end

    test "loop: tool_search loads, call_tool is unwrapped before hooks, tools array stable" do
      me = self()
      tk = tier_kit(me)
      {:ok, step} = Agent.start_link(fn -> 0 end)

      t =
        transport(fn body ->
          send(me, {:tools, Jason.encode!(body["tools"])})
          n = Agent.get_and_update(step, &{&1 + 1, &1 + 1})

          case n do
            1 -> anthropic([%{"type" => "tool_use", "id" => "s1", "name" => "tool_search", "input" => %{"query" => "select:deploy"}}])
            2 -> anthropic([%{"type" => "tool_use", "id" => "c1", "name" => "call_tool", "input" => %{"name" => "deploy", "arguments" => %{"x" => "prod"}}}])
            3 -> anthropic([%{"type" => "tool_use", "id" => "d1", "name" => "lint", "input" => %{"x" => "direct"}}])
            _ -> anthropic([%{"type" => "text", "text" => "done"}])
          end
        end)

      c =
        Client.create(
          base_url: "https://x",
          style: "anthropic",
          model: "m",
          api_key: "k",
          transport: t,
          tool_tiers: %{core: ["alpha_read"], deferred: ["deploy"]},
          hooks: %{
            before_tool: fn ev ->
              send(me, {:gated, "#{ev.name}:#{if ev.hints, do: ev.hints.destructive, else: "?"}"})
              nil
            end
          }
        )

      r = Client.run(c, "ship it", tk)
      assert r.status == "done"
      assert hd(r.tool_calls).output =~ ~s("name": "deploy")
      assert Enum.at(r.tool_calls, 1).name == "call_tool"
      assert_received {:ran, "deploy(prod)"}
      assert_received {:ran, "lint(direct)"}
      assert_received {:gated, "deploy:true"}
      assert_received {:gated, "lint:?"}
      refute_received {:gated, "tool_search" <> _}

      arrays = for _ <- 1..4, do: (receive do {:tools, a} -> a end)
      assert length(Enum.uniq(arrays)) == 1
      assert hd(arrays) =~ "tool_search" and not (hd(arrays) =~ "Deploy the service")
    end

    test "call_tool of a suspending tool: wait_for re-runs the REAL tool" do
      t =
        transport(fn body ->
          if tool_results?(body),
            do: openai_text("done"),
            else: openai_calls([{"c1", "call_tool", %{"name" => "get_balance", "arguments" => %{}}}])
        end)

      c =
        Client.create(
          base_url: "https://x",
          model: "m",
          api_key: "k",
          transport: t,
          tool_tiers: %{core: []},
          wait_for: fn req -> %Answer{id: req.id, ok: true} end
        )

      r = Client.run(c, "balance?", [balance_tool()])
      assert hd(r.tool_calls).output == "balance: 42"
      assert hd(r.tool_calls).args == %{"name" => "get_balance", "arguments" => %{}}
    end

    test "stream events name the real tool with via: call_tool (openai + anthropic)" do
      me = self()
      {:ok, step} = Agent.start_link(fn -> 0 end)
      args = Jason.encode!(%{"name" => "deploy", "arguments" => %{"x" => "prod"}})

      openai_t =
        transport(fn _ ->
          if Agent.get_and_update(step, &{&1 + 1, &1 + 1}) == 1 do
            sse([
              %{
                "choices" => [
                  %{"delta" => %{"tool_calls" => [%{"index" => 0, "id" => "c1", "type" => "function", "function" => %{"name" => "call_tool", "arguments" => args}}]}}
                ]
              },
              %{"choices" => [%{"delta" => %{}, "finish_reason" => "tool_calls"}]}
            ])
          else
            sse([%{"choices" => [%{"delta" => %{"content" => "done"}, "finish_reason" => "stop"}]}])
          end
        end)

      c = Client.create(base_url: "https://x", model: "m", api_key: "k", transport: openai_t, tool_tiers: %{core: ["alpha_read"]})
      events = Client.stream(c, "go", tier_kit(me)) |> Enum.to_list()

      got =
        for %{type: ty} = e <- events, ty in ["tool_call", "tool_result"],
            do: "#{ty}:#{e.name}:#{e[:via]}:#{e[:args]["x"]}"

      assert got == ["tool_call:deploy:call_tool:prod", "tool_result:deploy:call_tool:"]
      assert_received {:ran, "deploy(prod)"}

      {:ok, step2} = Agent.start_link(fn -> 0 end)

      anthropic_t =
        transport(fn _ ->
          if Agent.get_and_update(step2, &{&1 + 1, &1 + 1}) == 1 do
            sse([
              %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "tool_use", "id" => "c1", "name" => "call_tool"}},
              %{"type" => "content_block_delta", "index" => 0, "delta" => %{"type" => "input_json_delta", "partial_json" => args}},
              %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}}
            ])
          else
            sse([
              %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "text"}},
              %{"type" => "content_block_delta", "index" => 0, "delta" => %{"type" => "text_delta", "text" => "done"}},
              %{"type" => "message_delta", "delta" => %{"stop_reason" => "end_turn"}}
            ])
          end
        end)

      c = Client.create(base_url: "https://x", style: "anthropic", model: "m", api_key: "k", transport: anthropic_t, tool_tiers: %{core: []})
      events = Client.stream(c, "go", tier_kit(me)) |> Enum.to_list()
      assert [%{name: "deploy", via: "call_tool", args: %{"x" => "prod"}}] = Enum.filter(events, &(&1.type == "tool_call"))
      assert [%{name: "deploy", via: "call_tool"}] = Enum.filter(events, &(&1.type == "tool_result"))
    end
  end

  # ---- prompt cache ---------------------------------------------------------------------

  # Strip cache_control so a segment's identity is its content.
  defp strip_cc(%{} = m), do: m |> Map.delete("cache_control") |> Map.new(fn {k, v} -> {k, strip_cc(v)} end)
  defp strip_cc(l) when is_list(l), do: Enum.map(l, &strip_cc/1)
  defp strip_cc(v), do: v

  defp cc?(v), do: Jason.encode!(v) =~ ~s("cache_control")

  # A simulated anthropic prefix cache: a request reads the longest prefix ending at a
  # breakpoint an earlier request wrote (prompt_cache_test.go cacheSim).
  defp cache_sim(sim, body) do
    segs = [body["tools"], body["system"]] ++ body["messages"]
    keys = Enum.map(segs, &Jason.encode!(strip_cc(&1)))
    marks = Enum.map(segs, &cc?/1)
    prefix = fn i -> Enum.take(keys, i) end
    size = fn i -> div(byte_size(Enum.join(prefix.(i))), 4) end

    Agent.get_and_update(sim, fn st ->
      read = Enum.find_value(length(keys)..1//-1, 0, fn i -> if MapSet.member?(st.written, prefix.(i)), do: size.(i) end)

      {written, write} =
        keys
        |> Enum.with_index(1)
        |> Enum.reduce({st.written, 0}, fn {_k, i}, {w, wr} ->
          if Enum.at(marks, i - 1) and not MapSet.member?(w, prefix.(i)),
            do: {MapSet.put(w, prefix.(i)), size.(i) - read},
            else: {w, wr}
        end)

      {{read, write}, %{st | written: written, reads: st.reads ++ [read], bodies: st.bodies ++ [body]}}
    end)
  end

  defp run_cache_session(cache) do
    {:ok, sim} = Agent.start_link(fn -> %{written: MapSet.new(), reads: [], bodies: []} end)
    {:ok, turn} = Agent.start_link(fn -> 0 end)
    catalog = String.duplicate("skill: does a thing in a stable way. ", 700)

    t =
      transport(fn body ->
        {read, write} = cache_sim(sim, body)
        usage = %{"cache_read_input_tokens" => read, "cache_creation_input_tokens" => write}

        if tool_results?(%{"messages" => [List.last(body["messages"])]}) do
          Map.put(anthropic([%{"type" => "text", "text" => "ok"}], usage), "stop_reason", "end_turn")
        else
          n = Agent.get_and_update(turn, &{&1 + 1, &1 + 1})
          name = if n == 2, do: "load_schema", else: "read_notes"
          anthropic([%{"type" => "tool_use", "id" => "t#{n}", "name" => name, "input" => %{}}], usage)
        end
      end)

    notes = Toolnexus.define_tool(name: "read_notes", description: "Read the notes.", execute: fn _ -> "notes" end)

    loader =
      Toolnexus.define_tool(
        name: "load_schema",
        description: "Load a deferred tool's schema.",
        execute: fn _ -> ~s({"name":"deploy","description":"Deploy.","inputSchema":{"type":"object"}}) end
      )

    tk = Toolnexus.create_toolkit!(extra_tools: [notes, loader])

    c =
      Client.create(
        base_url: "https://x",
        style: "anthropic",
        model: "m",
        api_key: "k",
        system_prompt: "You are Karya.\n\n" <> catalog,
        prompt_cache: cache,
        transport: t
      )

    usage =
      Enum.reduce(~w(first second third fourth), %{read: 0, write: 0}, fn p, acc ->
        r = Client.ask(c, p, tk, "session-1")
        %{read: acc.read + Map.get(r.usage, :cache_read_tokens, 0), write: acc.write + Map.get(r.usage, :cache_write_tokens, 0)}
      end)

    {Agent.get(sim, & &1), usage}
  end

  describe "prompt cache (prompt_cache_test.go)" do
    test "replay: hits start on the 2nd request and never drop; prefix bytes stable" do
      {sim, usage} = run_cache_session(%{key: "session-1"})
      assert length(sim.reads) == 8
      assert usage.read > 0 and usage.write > 0
      [first, floor | _] = sim.reads
      assert first == 0
      assert floor >= 6000

      sim.reads
      |> Enum.drop(1)
      |> Enum.chunk_every(2, 1, :discard)
      |> Enum.each(fn [a, b] -> assert b >= a and b >= floor, inspect(sim.reads) end)

      [b0 | _] = sim.bodies
      assert Enum.all?(sim.bodies, &(&1["tools"] == b0["tools"] and &1["system"] == b0["system"]))
    end

    test "off ⇒ byte-identical: no cache_control, system stays a string, usage unchanged" do
      {sim, usage} = run_cache_session(nil)
      assert Enum.all?(sim.bodies, &(not cc?(&1) and is_binary(&1["system"])))
      # the sim reports reads on its own; the usage keys appear only when reported
      assert usage.read >= 0
    end

    test "usage keys are absent when the provider reports no cache" do
      c = Client.create(base_url: "https://x", model: "m", api_key: "k", transport: transport(fn _ -> openai_text("hi") end))
      assert Client.run(c, "x", []).usage == %{prompt_tokens: 1, completion_tokens: 1, total_tokens: 2}
    end

    test "openai: prompt_cache_key and cached_tokens" do
      me = self()

      t =
        transport(fn body ->
          send(me, {:key, body["prompt_cache_key"]})

          %{
            "choices" => [%{"message" => %{"role" => "assistant", "content" => "hi"}}],
            "usage" => %{"prompt_tokens" => 100, "completion_tokens" => 5, "prompt_tokens_details" => %{"cached_tokens" => 80}}
          }
        end)

      c = Client.create(base_url: "https://x", model: "m", api_key: "k", transport: t, prompt_cache: %{key: "s1"})
      r = Client.run(c, "x", [])
      assert_received {:key, "s1"}
      assert r.usage.cache_read_tokens == 80 and r.usage.prompt_tokens == 100
      refute Map.has_key?(r.usage, :cache_write_tokens)

      assert Toolnexus.PromptCache.apply(%{}, "openai", %{}) == %{}
      assert Toolnexus.PromptCache.apply(%{"a" => 1}, "gemini", %{key: "k"}) == %{"a" => 1}
    end

    test "anthropic: three breakpoints with the 1h ttl; request_params still override" do
      body = %{
        "tools" => [%{"name" => "a"}, %{"name" => "x"}],
        "system" => "s",
        "messages" => [%{"role" => "user", "content" => "hi"}]
      }

      out = Toolnexus.PromptCache.apply(body, "anthropic", long_ttl: true)
      assert length(String.split(Jason.encode!(out), ~s("ttl":"1h"))) == 4
      assert hd(out["tools"]) == %{"name" => "a"}
      assert out["system"] == [%{"type" => "text", "text" => "s", "cache_control" => %{"type" => "ephemeral", "ttl" => "1h"}}]

      blocks = %{
        "system" => [%{"type" => "text", "text" => "s"}],
        "messages" => [%{"role" => "user", "content" => [%{"type" => "text", "text" => "a"}, %{"type" => "text", "text" => "b"}]}],
        "tools" => []
      }

      out = Toolnexus.PromptCache.apply(blocks, "anthropic", %{})
      assert List.last(hd(out["messages"])["content"])["cache_control"] == %{"type" => "ephemeral"}
      refute Map.has_key?(hd(hd(out["messages"])["content"]), "cache_control")
      assert hd(out["system"])["cache_control"] == %{"type" => "ephemeral"}
      assert out["tools"] == []
      assert Toolnexus.PromptCache.apply(%{"system" => "", "messages" => [%{"role" => "user"}]}, "anthropic", %{}) ==
               %{"system" => "", "messages" => [%{"role" => "user"}]}

      me = self()
      t = transport(fn b -> send(me, {:sys, b["system"]}) && anthropic([%{"type" => "text", "text" => "ok"}]) end)

      c =
        Client.create(
          base_url: "https://x",
          style: "anthropic",
          model: "m",
          api_key: "k",
          system_prompt: "S",
          transport: t,
          prompt_cache: %{},
          request_params: %{"system" => "override"}
        )

      Client.run(c, "x", [])
      assert_received {:sys, "override"}
    end
  end
end
