defmodule Toolnexus.Issues8693Test do
  @moduledoc """
  Consumer issues #86–#93 — the six decisions in
  `openspec/changes/fix-consumer-issues-86-93/DECISIONS.md` plus addenda A1–A6.

  Hermetic: every LLM call goes through the §8 `:transport` seam with a scripted
  reply, so there is no network, no API key and no cost. The reproductions in
  `spikes/issues/` are the source of each case.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.{Answer, Client, ProviderError, Skill, Status, Tool, ToolResult}
  alias Toolnexus.Agents
  alias Toolnexus.Agents.Loop

  # ---- the scripted "LLM" (mirrors harness_loop_test.exs) ------------------

  defp scripted(messages) do
    {:ok, agent} = Agent.start_link(fn -> %{i: 0, bodies: []} end)

    transport = fn req ->
      state =
        Agent.get_and_update(agent, fn s ->
          {s, %{s | i: s.i + 1, bodies: s.bodies ++ [req.body]}}
        end)

      message = Enum.at(messages, min(state.i, length(messages) - 1))
      finish = if Map.has_key?(message, "tool_calls"), do: "tool_calls", else: "stop"

      {:ok,
       %{
         status: 200,
         headers: %{"content-type" => "application/json"},
         body: %{
           "choices" => [%{"index" => 0, "message" => message, "finish_reason" => finish}],
           "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
         }
       }}
    end

    {transport, fn -> Agent.get(agent, & &1.bodies) end}
  end

  defp say(content), do: %{"role" => "assistant", "content" => content}

  defp call(name, args, id \\ "c1") do
    %{
      "role" => "assistant",
      "tool_calls" => [
        %{
          "id" => id,
          "type" => "function",
          "function" => %{"name" => name, "arguments" => Jason.encode!(args)}
        }
      ]
    }
  end

  defp base_opts(transport, extra \\ %{}) do
    Map.merge(
      %{
        base_url: "http://scripted.invalid",
        style: "openai",
        model: "test-model",
        api_key: "unused",
        transport: transport
      },
      extra
    )
  end

  defp client(transport, extra \\ %{}), do: Client.create(base_opts(transport, extra))

  # =========================================================================
  # D1 — toolkit-less completion (#86, ADR 0023)
  # =========================================================================

  describe "D1 toolkit-less completion" do
    test "run/2 completes with no toolkit at all" do
      {transport, _} = scripted([say("no tools needed")])
      r = Client.run(client(transport), "hello")
      assert r.text == "no tools needed" and r.status == "done"
    end

    test "ask/2 and stream/2 take the same sugar arity" do
      {transport, _} = scripted([say("sugar")])
      assert Client.ask(client(transport), "hello").text == "sugar"

      {transport2, bodies} = scripted([say("streamed")])

      events = client(transport2) |> Client.stream("hello") |> Enum.to_list()

      assert Enum.any?(events, &(&1.type == "done"))
      # the same wire assertion on the streaming path
      [body] = bodies.()
      refute Map.has_key?(body, "tools")
      refute Map.has_key?(body, "tool_choice")
    end

    test "a toolkit-less request body has NO tools and NO tool_choice key — not an empty array" do
      {transport, bodies} = scripted([say("ok")])
      Client.run(client(transport), "hello")

      [body] = bodies.()
      refute Map.has_key?(body, "tools")
      refute Map.has_key?(body, "tool_choice")
    end

    test "an explicit nil toolkit is identical to omitting it" do
      {transport, bodies} = scripted([say("ok")])
      Client.run(client(transport), "hello", nil)
      [body] = bodies.()
      refute Map.has_key?(body, "tools")
    end

    test "there is no public Toolkit.empty/0" do
      refute function_exported?(Toolnexus.Toolkit, :empty, 0)
    end
  end

  # =========================================================================
  # D2 — the Loop honours the Spec (#87, ADR 0024)
  # =========================================================================

  defp bare_toolkit do
    {:ok, tk} = Toolnexus.create_toolkit(builtins: false)
    tk
  end

  defp danger_toolkit(counter) do
    tool = %Tool{
      name: "danger",
      description: "does the dangerous thing",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _args, _ctx ->
        Agent.update(counter, &(&1 + 1))
        ToolResult.ok("EXECUTED")
      end
    }

    {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [tool])
    tk
  end

  describe "D2 the Loop honours the Spec" do
    test "a caller-supplied system prompt WINS over the spec's soul" do
      {transport, bodies} = scripted([say("hi")])
      a = Agents.agent("souled", does: "answers", soul: "SOUL TEXT")

      {out, _} =
        Loop.run(
          Loop.new(a, base_opts(transport, %{system_prompt: "CALLER WINS"}), bare_toolkit()),
          "hi"
        )

      assert out.status == "done"
      [body] = bodies.()
      system = Enum.find(body["messages"], &(&1["role"] == "system"))
      assert system["content"] == "CALLER WINS"
      refute system["content"] =~ "SOUL TEXT"
    end

    test "the soul still applies when the caller supplies no system prompt" do
      {transport, bodies} = scripted([say("hi")])
      a = Agents.agent("souled", does: "answers", soul: "SOUL TEXT")
      {_out, _} = Loop.run(Loop.new(a, base_opts(transport), bare_toolkit()), "hi")

      [body] = bodies.()
      assert Enum.find(body["messages"], &(&1["role"] == "system"))["content"] == "SOUL TEXT"
    end

    test "Spec.model is a Loop default; a per-call :model still wins" do
      {transport, bodies} = scripted([say("a")])
      a = Agents.agent("m", does: "x", model: "spec-model")
      {_out, _} = Loop.run(Loop.new(a, base_opts(transport), bare_toolkit()), "hi")
      assert hd(bodies.())["model"] == "spec-model"

      {transport2, bodies2} = scripted([say("a")])

      {_out, _} =
        Loop.run(Loop.new(a, base_opts(transport2), bare_toolkit()), "hi", model: "call-model")

      assert hd(bodies2.())["model"] == "call-model"
    end

    test "Spec.budget.max_turns is a Loop default" do
      counter = start_supervised!({Agent, fn -> 0 end})
      # the model never stops calling the tool; the spec's budget must stop it
      {transport, _} = scripted([call("ping", %{})])

      tool = %Tool{
        name: "ping",
        description: "p",
        input_schema: %{"type" => "object", "properties" => %{}},
        source: "custom",
        execute: fn _a, _c ->
          Agent.update(counter, &(&1 + 1))
          ToolResult.ok("pong")
        end
      }

      {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [tool])
      a = Agents.agent("b", does: "x", budget: %{max_turns: 2})
      {out, _} = Loop.run(Loop.new(a, base_opts(transport), tk), "go")

      assert out.status == "incomplete"
      assert Agent.get(counter, & &1) == 2
    end

    test "A8: the spec model also applies to the sentinel \"inherit\"" do
      a = Agents.agent("m", does: "x", model: "spec-model")

      for caller <- [nil, "inherit"] do
        {transport, bodies} = scripted([say("a")])

        {_out, _} =
          Loop.run(Loop.new(a, base_opts(transport), bare_toolkit()), "hi", model: caller)

        assert hd(bodies.())["model"] == "spec-model"
      end
    end

    test "loop_unsupported/1 names the fields a driver cannot honour, in the canonical vocabulary" do
      assert Loop.loop_unsupported(%{does: "x"}) == []
      assert Loop.loop_unsupported(does: "x", soul: "y", model: "m") == []

      assert Loop.loop_unsupported(%{
               tools: [%{}],
               team: [%{}],
               wait_for: fn _ -> nil end,
               on_metric: fn _ -> :ok end
             }) == ["tools", "team", "waitFor", "onMetric"]

      # STRINGS, camelCase, identical in all seven ports (addendum A6)
      assert Loop.loop_unsupported(%{wait_for: fn _ -> nil end}) == ["waitFor"]
      assert Loop.loop_unsupported(nil) == []
    end

    test "REGRESSION: a denied tool's execute is NEVER ENTERED" do
      counter = start_supervised!({Agent, fn -> 0 end})
      {transport, _} = scripted([call("danger", %{}), say("gave up")])

      a =
        Agents.agent("guarded",
          does: "x",
          guardrails: [fn ev -> if ev[:name] == "danger", do: "policy: denied", else: "allow" end]
        )

      {out, _} = Loop.run(Loop.new(a, base_opts(transport), danger_toolkit(counter)), "go")

      # the assertion is on the SIDE EFFECT, never on the text
      assert Agent.get(counter, & &1) == 0
      assert out.status in ["done", "incomplete"]
    end
  end

  # =========================================================================
  # D3 — runtime legibility (#88/#90, ADR 0025)
  # =========================================================================

  describe "D3 runtime legibility" do
    test "TaskResult carries own_tokens beside the cumulative total_tokens" do
      {transport, _} = scripted([say("done")])
      a = Agents.agent("solo", does: "x")
      out = Agents.run(a, %{transport: transport}, "go")

      assert out.status == "done"
      assert is_integer(out.total_tokens) and out.total_tokens > 0
      assert Map.has_key?(out, :own_tokens)
      # a leaf spends only its own tokens, so the two agree there
      assert out.own_tokens == out.total_tokens
    end

    # A18: the INVARIANT, not one instance of it. A limit stop MUST name its limit;
    # a non-limit stop MUST leave it empty; and each value must be a member of its
    # OWN closed vocabulary. Elixir has 13 TaskResult construction sites — this is
    # what stops a fourteenth reintroducing the contradiction.
    defp assert_status_invariant(result, where) do
      assert Status.task?(result.status),
             "#{where}: status #{inspect(result.status)} is outside the §7D vocabulary"

      limit = Map.get(result, :limit)

      if result.status in [Status.incomplete(), Status.timeout()] do
        assert is_binary(limit) and limit != "",
               "#{where}: a limit stop (#{result.status}) must NAME its limit, got #{inspect(limit)}"

        assert Status.limit?(limit),
               "#{where}: limit #{inspect(limit)} is outside the closed vocabulary"
      else
        assert limit in [nil, ""],
               "#{where}: a non-limit stop (#{result.status}) must leave limit empty, got #{inspect(limit)}"
      end

      result
    end

    test "A18 INVARIANT: a limit stop names its limit; a non-limit stop leaves it empty" do
      alias Toolnexus.Agents.Runtime

      # ---- done ----
      {transport, _} = scripted([say("finished")])
      a = Agents.agent("plain", does: "x")
      done = Agents.run(a, %{transport: transport}, "go")
      assert done.status == Status.done()
      assert_status_invariant(done, "done")

      # ---- incomplete, from a real budget stop ----
      {transport2, _} = scripted([call("noop", %{})])

      tool = %Tool{
        name: "noop",
        description: "n",
        input_schema: %{"type" => "object", "properties" => %{}},
        source: "custom",
        execute: fn _a, _c -> ToolResult.ok("ok") end
      }

      b = Agents.agent("bounded", does: "x", budget: %{max_turns: 1}, tools: [tool])
      inc = Agents.run(b, %{transport: transport2}, "go")
      assert inc.status == Status.incomplete()
      assert inc.limit == Status.Limit.max_turns()
      assert_status_invariant(inc, "incomplete")

      # ---- closed: drive the branch EXPLICITLY. Waiting on a SETTLED handle
      # returns its last result, not a fresh closed one, so close a handle that
      # has never run and wait on that.
      {transport3, _} = scripted([say("never runs")])
      rt = Runtime.new(%{transport: transport3, registry: Agents.registry(a)})
      {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "plain")
      Runtime.close_handle(Runtime.ctx(rt), h)
      cl = Runtime.wait(rt, h)
      assert cl.status == Status.closed(), "expected a fresh closed result, got #{cl.status}"
      assert_status_invariant(cl, "closed")
      Runtime.shutdown(rt)
    end

    test "A18: the latent second instance — an `incomplete` with an EMPTY limit cannot settle" do
      # a client RunResult carrying no limit must still settle with a NAMED limit
      {transport, _} = scripted([call("spin", %{})])

      tool = %Tool{
        name: "spin",
        description: "s",
        input_schema: %{"type" => "object", "properties" => %{}},
        source: "custom",
        execute: fn _a, _c -> ToolResult.ok("again") end
      }

      a = Agents.agent("spinner", does: "x", budget: %{max_turns: 2}, tools: [tool])
      out = Agents.run(a, %{transport: transport}, "go")

      assert out.status == Status.incomplete()
      refute out.limit in [nil, ""]
      assert Status.limit?(out.limit)
    end

    test "A17: a wait-deadline settle sets BOTH status and limit — they must AGREE" do
      alias Toolnexus.Agents.Runtime
      alias Toolnexus.TestVirtualClock, as: VClock

      # A VIRTUAL clock, not a short real deadline: a real-time deadline racing the
      # scheduler starves exactly this assertion and yields flakiness, not a clean
      # failure. Here no wall time passes at all.
      {transport, _} = scripted([say("slow")])
      a = Agents.agent("slowpoke", does: "x")
      vc = VClock.new()

      rt =
        Runtime.new(%{transport: transport, registry: Agents.registry(a), clock: VClock.clock(vc)})

      {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "slowpoke")

      # never woken, so the wait can only end on its own deadline
      w = Task.async(fn -> Runtime.wait(rt, h, 1_000) end)
      Process.sleep(20)
      refute Task.yield(w, 20), "the wait must not end on wall time"

      VClock.advance(vc, 1_000)
      out = Task.await(w, 1_000)

      assert out.status == Status.timeout()
      assert out.limit == Status.Limit.timeout(),
             "status says timeout but limit is #{inspect(out.limit)}"

      assert Status.task?(out.status) and Status.limit?(out.limit)
      assert_status_invariant(out, "timeout")

      Runtime.shutdown(rt)
    end

    test "A20: both vocabularies are PUBLIC API, value by value, via the public API only" do
      # A suite passing is not evidence of visibility. Everything below is reached
      # the way a HOST reaches it: exported functions on a documented module.
      # `function_exported?/3` answers for LOADED modules only — ensure both are
      # loaded, or this asserts test-run order rather than public visibility.
      Code.ensure_loaded!(Status)
      Code.ensure_loaded!(Status.Limit)
      Code.ensure_loaded!(Toolnexus.Agents.Handle)

      assert function_exported?(Status, :task, 0)
      assert function_exported?(Status, :run, 0)
      assert function_exported?(Status, :limits, 0)
      assert function_exported?(Status, :task?, 1)
      assert function_exported?(Status, :limit?, 1)

      {:docs_v1, _, :elixir, _, moduledoc, _, docs} = Code.fetch_docs(Status)
      assert is_map(moduledoc), "Toolnexus.Status must be documented"

      documented =
        for {{:function, name, arity}, _, _, d, _} <- docs, is_map(d), do: {name, arity}

      assert {:task, 0} in documented and {:limits, 0} in documented

      # EVERY task status is reachable as a named function — no string literal needed
      for value <- Status.task() do
        fun = String.to_atom(value)

        assert function_exported?(Status, fun, 0),
               "task status #{inspect(value)} has no named accessor"

        assert apply(Status, fun, []) == value
      end

      # EVERY limit is reachable as a named function on the public Limit module
      camel_to_snake = fn v -> v |> String.replace(~r/([A-Z])/, "_\\1") |> String.downcase() end

      for value <- Status.limits() do
        fun = String.to_atom(camel_to_snake.(value))

        assert function_exported?(Status.Limit, fun, 0),
               "limit #{inspect(value)} has no named accessor"

        assert apply(Status.Limit, fun, []) == value
      end

      # the internal-pool-name mapper stays PRIVATE: exporting it would leak exactly
      # the internal names A14 exists to keep out of the public field
      refute function_exported?(Toolnexus.Agents.Handle, :canonical_limit, 1)
    end

    test "A14: `limit` is a CLOSED canonical vocabulary, spelled as SPEC spells it" do
      assert Status.limits() == [
               "maxTurns",
               "maxTokens",
               "maxToolCalls",
               "maxWallMs",
               "maxChildren",
               "maxConcurrent",
               "maxDepth",
               "completion",
               "timeout"
             ]

      assert Status.limit?("maxWallMs")
      refute Status.limit?("max_wall_ms")
      refute Status.limit?("tokens")
    end

    test "a budget stop reports the limit by name (elixir already had `limit`)" do
      {transport, _} = scripted([call("noop", %{})])

      tool = %Tool{
        name: "noop",
        description: "n",
        input_schema: %{"type" => "object", "properties" => %{}},
        source: "custom",
        execute: fn _a, _c -> ToolResult.ok("ok") end
      }

      a = Agents.agent("bounded", does: "x", budget: %{max_turns: 1}, tools: [tool])
      out = Agents.run(a, %{transport: transport}, "go")

      assert out.status == "incomplete"
      # A14: the value a host branches on is IN the closed vocabulary
      assert Status.limit?(out.limit), "off-vocabulary limit: #{inspect(out.limit)}"
      assert out.limit == "maxTurns"
      assert Map.has_key?(out, :own_tokens)
    end
  end

  # =========================================================================
  # D4 — the Answer payload contract (#89, ADR 0026)
  # =========================================================================

  describe "D4 the Answer payload contract" do
    test "answer_output/2 builds the string-keyed payload a host had to guess" do
      a = Answer.answer_output("req-1", "42")
      assert a.id == "req-1" and a.ok == true
      assert a.data == %{"output" => "42"}
      # STRING keys: it survives a JSON round-trip unchanged
      assert a |> Jason.encode!() |> Jason.decode!() == %{
               "id" => "req-1",
               "ok" => true,
               "data" => %{"output" => "42"}
             }
    end

    test "a non-string output ERRORS rather than degrading to \"\"" do
      assert_raise ArgumentError, ~r/must be a string/, fn ->
        Answer.answer_output("req-1", %{a: 1})
      end
    end

    test "an Answer that made a JSON round-trip (STRING keys) is accepted" do
      round_tripped =
        Answer.answer_output("r", "yes") |> Jason.encode!() |> Jason.decode!()

      # this raised FunctionClauseError before D4
      assert Answer.ok?(round_tripped)
      assert Answer.get(round_tripped, :id) == "r"

      assert Answer.coerce(round_tripped) == %Answer{
               id: "r",
               ok: true,
               data: %{"output" => "yes"}
             }
    end

    test "atom keys and %Answer{} still work identically" do
      assert Answer.ok?(%{id: "r", ok: true})
      assert Answer.ok?(%Answer{id: "r", ok: true})
      refute Answer.ok?(%{"ok" => false})
      refute Answer.ok?(%{})
      assert Answer.get(%Answer{id: "r", ok: true}, :reason) == nil
      assert Answer.coerce(%Answer{id: "r", ok: true}).id == "r"
    end

    test "A9: answer_declined/2 carries the refusal" do
      d = Answer.answer_declined("req-1", "declined")
      assert d.ok == false and d.reason == "declined"
      assert Answer.answer_declined("r").reason == "declined"
      refute Answer.ok?(d)

      assert_raise ArgumentError, ~r/must be a string/, fn ->
        Answer.answer_declined("r", %{})
      end
    end

    test "a wait_for answering with STRING keys resolves the suspension" do
      {transport, _} = scripted([call("ask_human", %{}), say("thanks")])

      tool = %Tool{
        name: "ask_human",
        description: "asks",
        input_schema: %{"type" => "object", "properties" => %{}},
        source: "custom",
        execute: fn _args, ctx ->
          case ctx.answer do
            nil ->
              %ToolResult{
                output: "",
                metadata: %{
                  pending: %Toolnexus.Request{id: "q1", kind: "input", prompt: "your name?"}
                }
              }

            answer ->
              ToolResult.ok("got: " <> Answer.get(answer, :data)["output"])
          end
        end
      }

      {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [tool])

      c =
        client(transport, %{
          wait_for: fn _req ->
            # the shape a host resuming out of a JSON column actually holds
            %{"id" => "q1", "ok" => true, "data" => %{"output" => "ada"}}
          end
        })

      r = Client.run(c, "go", tk)
      assert r.status == "done"
      assert Enum.any?(r.messages, &(&1["content"] == "got: ada"))
    end
  end

  # =========================================================================
  # D5 — what we hand back when we fail (#91/#92, ADR 0027)
  # =========================================================================

  describe "D5 classifier backend presets" do
    test "the backend preset sets baseUrl + model + apiKeyEnv AS A UNIT" do
      {:ok, c} = Toolnexus.Classifier.create(backend: "openrouter")
      assert c.base_url == "https://openrouter.ai/api/v1"
      assert c.model == "typesafe/jev-1.13"
      assert c.api_key_env == "OPENROUTER_API_KEY"

      {:ok, t} = Toolnexus.Classifier.create(backend: "typesafe")
      assert t.base_url == "https://api.typesafe.ai/v1"
      assert t.model == "jev-latest"
    end

    test "the KNOWN mismatch fails at CONSTRUCTION, not 700ms later on the wire" do
      assert {:error, msg} =
               Toolnexus.Classifier.create(
                 base_url: "https://openrouter.ai/api/v1",
                 model: "jev-latest"
               )

      assert msg =~ ~s(model "jev-latest" is TypeSafe's spelling)
      assert msg =~ ~s(on openrouter.ai use "typesafe/jev-1.13")
    end

    test "an unknown backend name is refused by name" do
      assert {:error, msg} = Toolnexus.Classifier.create(backend: "nope")
      assert msg =~ "unknown backend"
      assert msg =~ "typesafe"
    end

    test "jev-latest on the DEFAULT TypeSafe base url is kept — it is servable there" do
      assert {:ok, c} = Toolnexus.Classifier.create([])
      assert c.model == "jev-latest"
    end
  end

  describe "D5 two status vocabularies" do
    test "the §8 and §7D sets are named constants, and only §7D has timeout" do
      assert Status.run() == ["done", "pending", "incomplete"]

      assert Status.task() ==
               ["done", "pending", "incomplete", "interrupted", "closed", "timeout", "error"]

      assert Status.task?("timeout")
      refute Status.run?("timeout")
      refute Status.run?("error")
      assert Status.run?("done")
    end
  end

  describe "D5 typed errors" do
    test "the run deadline raises a TYPED timeout error that NAMES the budget" do
      {transport, _} = scripted([say("never gets here")])

      err =
        assert_raise Toolnexus.TimeoutError, fn ->
          Client.run(client(transport, %{timeout_ms: 0}), "x", [])
        end

      assert err.timeout_ms == 0
      assert Exception.message(err) =~ "run timeout after 0ms"
    end

    test "a provider failure is a VALUE: status, body and retry_after are fields" do
      transport = fn _req ->
        {:ok,
         %{
           status: 429,
           headers: %{"retry-after" => ["7"]},
           body: %{"error" => "slow down"}
         }}
      end

      err =
        assert_raise ProviderError, fn ->
          Client.run(client(transport, %{retries: 0}), "x", [])
        end

      assert err.status == 429
      # The RAW header verbatim, not a parsed number — identical in all seven ports.
      assert err.retry_after == "7"
      assert err.body == %{"error" => "slow down"}
    end

    test "a non-delay-seconds Retry-After still reaches the host verbatim" do
      # The point of the raw field: the WAITING rule ignores an HTTP-date and falls back to
      # backoff, but the response really supplied it, so the typed error carries it intact.
      raw = "Wed, 21 Oct 2026 07:28:00 GMT"

      transport = fn _req ->
        {:ok, %{status: 503, headers: %{"retry-after" => [raw]}, body: %{"error" => "later"}}}
      end

      err =
        assert_raise ProviderError, fn ->
          Client.run(client(transport, %{retries: 0}), "x", [])
        end

      assert err.retry_after == raw
      # waiting rule unchanged: not honourable, so backoff
      assert Toolnexus.Client.parse_retry_after(raw) == nil
    end

    test "account identifiers are REDACTED in BOTH the typed body and the message (A5)" do
      body = %{"error" => "nope", "user_id" => "user_2FAKEabc", "account_id" => 99}
      transport = fn _req -> {:ok, %{status: 400, headers: %{}, body: body}} end

      err =
        assert_raise ProviderError, fn ->
          Client.run(client(transport, %{retries: 0}), "x", [])
        end

      assert err.body["user_id"] == "«redacted»"
      assert err.body["account_id"] == "«redacted»"
      assert err.body["error"] == "nope"
      refute Exception.message(err) =~ "user_2FAKEabc"
      assert Exception.message(err) =~ "«redacted»"
    end

    test "a STRING body is redacted too — a JSON blob that never reached a decoder" do
      raw = ~s({"error":"bad","organization":"org_SECRET","org_id":"og_1"})
      transport = fn _req -> {:ok, %{status: 400, headers: %{}, body: raw}} end

      err =
        assert_raise ProviderError, fn ->
          Client.run(client(transport, %{retries: 0}), "x", [])
        end

      refute err.body =~ "org_SECRET"
      refute Exception.message(err) =~ "org_SECRET"
      assert err.body =~ "«redacted»"
    end

    test "401/403 bodies are NEVER echoed — the policy lifted from the classifier" do
      transport = fn _req ->
        {:ok, %{status: 401, headers: %{}, body: %{"error" => "Bearer sk-REFLECTED"}}}
      end

      err =
        assert_raise ProviderError, fn ->
          Client.run(client(transport, %{retries: 0}), "x", [])
        end

      refute Exception.message(err) =~ "sk-REFLECTED"
      assert Exception.message(err) == "LLM 401"
      # the value stays reachable on the structured error for a host that wants it
      assert err.body == %{"error" => "Bearer sk-REFLECTED"}
    end

    test "A CAP IS NOT REDACTION: the cap is MESSAGE-ONLY, the typed body is whole (A5)" do
      long = String.duplicate("x", 500)
      body = %{"error" => long, "user_id" => "user_2FAKE"}
      transport = fn _req -> {:ok, %{status: 400, headers: %{}, body: body}} end

      err =
        assert_raise ProviderError, fn ->
          Client.run(client(transport, %{retries: 0}), "x", [])
        end

      assert String.length(Exception.message(err)) < 260
      assert Exception.message(err) =~ "…"
      # full, redacted body on the field
      assert err.body["error"] == long
      assert err.body["user_id"] == "«redacted»"
    end
  end

  describe "D5 MUST NOT REGRESS" do
    test "fail-fast on 4xx: ONE attempt despite retries: 4, via the ENUMERATED retryable set" do
      counter = start_supervised!({Agent, fn -> 0 end})

      transport = fn _req ->
        Agent.update(counter, &(&1 + 1))
        {:ok, %{status: 400, headers: %{}, body: %{"error" => "bad"}}}
      end

      assert_raise ProviderError, fn ->
        Client.run(client(transport, %{retries: 4, retry_base_ms: 1}), "x", [])
      end

      assert Agent.get(counter, & &1) == 1
    end

    test "a status IN the enumerated set is still retried" do
      counter = start_supervised!({Agent, fn -> 0 end})
      {transport, _} = scripted([say("recovered")])

      wrapped = fn req ->
        n = Agent.get_and_update(counter, &{&1, &1 + 1})
        if n == 0, do: {:ok, %{status: 529, headers: %{}, body: %{}}}, else: transport.(req)
      end

      r = Client.run(client(wrapped, %{retries: 2, retry_base_ms: 1}), "x", [])
      assert r.text == "recovered"
      assert Agent.get(counter, & &1) == 2
    end

    test "ClassifierUsage.cost is an OPTIONAL where absent != zero" do
      assert %Toolnexus.Classifier.Usage{}.cost == nil
    end
  end

  # =========================================================================
  # D6 — a skill the writing tool accepts (#93, ADR 0028)
  # =========================================================================

  # The <skill_files> entries, as paths RELATIVE to the skill directory, in the order
  # the tool emitted them.
  defp sample_order(root, name, limit) do
    src = Skill.load(dirs: [root], sample_limit: limit)
    out = src.tool.execute.(%{"name" => name}, %Toolnexus.Context{}).output
    base = Path.join([root, "s"])

    ~r|<file>([^<]+)</file>|
    |> Regex.scan(out)
    |> Enum.map(fn [_, f] -> Path.relative_to(f, base) end)
  end

  defp write_skill!(dir, rel, frontmatter, body) do
    path = Path.join(dir, rel)
    File.mkdir_p!(Path.dirname(path))
    File.write!(path, "---\n" <> frontmatter <> "\n---\n" <> body)
    path
  end

  describe "D6 lenient frontmatter" do
    @describetag :tmp_dir

    test "YAML RUNS FIRST: a block scalar is parsed by YAML, byte-identical", %{tmp_dir: dir} do
      write_skill!(
        dir,
        "b/SKILL.md",
        "name: blocky\ndescription: |\n  line one\n  line two",
        "body"
      )

      %{skills: [s], skipped: []} = Skill.list(dirs: [dir])
      assert s.name == "blocky"
      # the line-wise read would have taken "" or "|" here; YAML first means it does not
      assert s.description == "line one\nline two"
    end

    test "a folded scalar and a quoted scalar are untouched by the lenient path", %{tmp_dir: dir} do
      write_skill!(dir, "f/SKILL.md", ~s(name: folded\ndescription: >\n  a b\n  c d), "body")
      %{skills: [s]} = Skill.list(dirs: [dir])
      assert s.description == "a b c d"
    end

    test "frontmatter YAML REFUSED is rescued line-wise for name/description", %{tmp_dir: dir} do
      # a tab-indented mapping: libyaml refuses tabs for indentation
      write_skill!(
        dir,
        "t/SKILL.md",
        "name: rescued\ndescription: still here\nbad:\n\tx: 1",
        "BODY"
      )

      %{skills: skills, skipped: skipped} = Skill.list(dirs: [dir])
      assert [%Skill.Info{name: "rescued", description: "still here", content: "BODY"}] = skills
      assert skipped == []
    end

    test "the rescue refuses a value opening a construct it does not implement", %{tmp_dir: dir} do
      for {name, fm} <- [
            {"pipe", "name: ok\ndescription: |\nbad:\n\tx: 1"},
            {"flowseq", "name: [unclosed\nbad:\n\tx: 1"},
            {"anchor", "name: &a\nbad:\n\tx: 1"},
            {"tag", "name: !!str\nbad:\n\tx: 1"}
          ] do
        d = Path.join(dir, "openers-#{name}")
        write_skill!(d, "s/SKILL.md", fm, "body")
        inv = Skill.list(dirs: [d])

        case name do
          "pipe" ->
            # `name` is fine and the description is EMPTY, never garbage — either
            # because YAML read the empty block scalar or because the rescue
            # refused the `|` opener
            assert [%Skill.Info{name: "ok", description: d}] = inv.skills
            assert d in [nil, ""]

          _ ->
            # refused, and — the invariant — NO invented name and NO invented
            # description. Which of the two skip reasons applies depends on whether
            # the YAML library threw; the shared fixture table below is the arbiter.
            assert inv.skills == []
            assert [%{reason: r}] = inv.skipped
            assert r in ["malformed-frontmatter", "missing-name"]
        end
      end
    end

    test "the rescue is COLUMN 0 and FIRST WINS", %{tmp_dir: dir} do
      write_skill!(
        dir,
        "g/SKILL.md",
        "name: first\n  name: indented\nname: second\n#name: comment\nbad:\n\tx: 1",
        "body"
      )

      %{skills: [s]} = Skill.list(dirs: [dir])
      assert s.name == "first"
    end

    test "the rescue unquotes, and invents NO description", %{tmp_dir: dir} do
      write_skill!(dir, "q/SKILL.md", ~s(name: "quoted"\nbad:\n\tx: 1), "body")
      %{skills: [s]} = Skill.list(dirs: [dir])
      assert s.name == "quoted"
      assert s.description == nil
    end

    test "A10: a STRUCTURALLY WRONG description is dropped, never kept, never invented", %{
      tmp_dir: dir
    } do
      # yaml_elixir throws here and JS's `yaml` recovers it into a sequence — both
      # must land on: name kept, description ABSENT.
      write_skill!(dir, "bf/SKILL.md", "name: broken-flow\ndescription: [unterminated, flow", "b")
      %{skills: [s], skipped: []} = Skill.list(dirs: [dir])
      assert s.name == "broken-flow"
      assert s.description == nil
    end

    test "A10: valid YAML that is NOT a mapping keeps the `missing-name` reason", %{tmp_dir: dir} do
      write_skill!(dir, "nm/SKILL.md", "just a plain scalar", "b")
      assert %{skills: [], skipped: [%{reason: "missing-name"}]} = Skill.list(dirs: [dir])
    end

    test "A10: a non-scalar NAME is dropped and the file stays refused", %{tmp_dir: dir} do
      write_skill!(dir, "nn/SKILL.md", "name:\n  - not-a-scalar", "b")
      assert %{skills: [], skipped: [%{reason: "missing-name"}]} = Skill.list(dirs: [dir])
    end

    test "the shared spike fixtures produce the cross-port accept/skip table" do
      root = Path.expand("../../spikes/issues/93/fixtures", __DIR__)

      if File.dir?(root) do
        inv = Skill.list(dirs: [root])
        name_of = fn s -> Path.basename(Path.dirname(s.location)) end

        assert inv.skills |> Enum.map(name_of) |> Enum.sort() == [
                 "anchors",
                 "block-folded",
                 "block-literal",
                 "broken-flow",
                 "broken-tab",
                 "colon-space",
                 "colon-space-quoted",
                 "colon-space-single",
                 "hash-inline",
                 "list-block",
                 "list-value",
                 "nested-map",
                 "plain",
                 "url-colon"
               ]

        assert inv.skipped |> Enum.map(&{name_of.(&1), &1.reason}) |> Enum.sort() == [
                 {"broken-unclosed", "missing-name"},
                 {"no-frontmatter", "missing-name"},
                 {"no-name", "missing-name"}
               ]

        by = Map.new(inv.skills, &{name_of.(&1), &1})
        # block scalars byte-identical
        assert by["block-literal"].description ==
                 "First line of the description.\nSecond line, with a colon: still fine inside a block scalar."

        assert by["block-folded"].description ==
                 "A folded description that runs across two source lines."

        # A11: decided by the YAML library's COMMENT handling, not by the rescue —
        # ` #` opens a comment, so the `#stockloop` tail is gone. Assert the STRING,
        # never just the ok verdict: a parser that keeps the tail also reports ok.
        assert by["hash-inline"].description == "Tag things with"

        # A12: the proof A10 is implemented — a library that THROWS and a library
        # that RECOVERS the flow sequence both land here: name kept, no description.
        assert by["broken-flow"].name == "broken-flow"
        assert by["broken-flow"].description == nil
        # the tab-broken file is RESCUED rather than lost
        assert by["broken-tab"].description == "tab-indented continuation"
      end
    end

    test "a genuinely malformed file is STILL REFUSED", %{tmp_dir: dir} do
      write_skill!(dir, "x/SKILL.md", "name: [unterminated", "body")
      %{skills: [], skipped: [%{reason: "malformed-frontmatter"}]} = Skill.list(dirs: [dir])
    end
  end

  describe "A22 sort rules over user-visible data" do
    @describetag :tmp_dir

    test "the §0.10 skills prompt orders by CODE POINT, including non-ASCII", %{tmp_dir: dir} do
      # Chosen to separate the three rules the sweep found across the ports:
      #   "Z" U+005A · "a" U+0061 · "á" U+00E1 · "é" U+00E9 · "\u{1F600}" U+1F600
      # A locale-aware compare puts "á"/"é" next to "a"; a UTF-16 code-unit compare
      # puts the astral emoji BEFORE U+E000-U+FFFF. Code point gives exactly this.
      for n <- ["é-skill", "Z-skill", "a-skill", "á-skill", "\u{1F600}-skill"] do
        write_skill!(dir, "#{Base.url_encode64(n, padding: false)}/SKILL.md", "name: #{n}\ndescription: d", "b")
      end

      src = Skill.load(dirs: [dir])

      order =
        src.prompt
        |> String.split("\n")
        |> Enum.filter(&String.starts_with?(&1, "- **"))
        |> Enum.map(&(&1 |> String.replace_prefix("- **", "") |> String.split("**") |> hd()))

      assert order == ["Z-skill", "a-skill", "á-skill", "é-skill", "\u{1F600}-skill"]

      # the property, not just the fixture: identical to sorting the code points
      assert order == Enum.sort_by(order, &String.to_charlist/1)
    end

    test "A24: the sibling-file sample is CAPPED, so the sort decides WHICH files land", %{
      tmp_dir: dir
    } do
      # The walk stops at the cap. Ordering is therefore a CONTENT decision, not a
      # cosmetic one: an unsorted read would sample whatever the filesystem happened
      # to hand back. Files are CREATED in reverse so creation order != name order.
      d = Path.join(dir, "sampled")
      write_skill!(d, "s/SKILL.md", "name: sampled\ndescription: d", "body")

      names = for i <- 1..20, do: "f#{String.pad_leading("#{i}", 2, "0")}.txt"
      for n <- Enum.reverse(names), do: File.write!(Path.join([d, "s", n]), "x")

      src = Skill.load(dirs: [d], sample_limit: 5)
      out = src.tool.execute.(%{"name" => "sampled"}, %Toolnexus.Context{}).output

      sampled = names |> Enum.filter(&String.contains?(out, &1))

      # exactly the cap, and exactly the FIRST five BY NAME — not by creation order
      assert length(sampled) == 5
      assert sampled == ["f01.txt", "f02.txt", "f03.txt", "f04.txt", "f05.txt"]
      refute String.contains?(out, "f06.txt")
    end

    test "A25: a NESTED resource tree orders by RELATIVE PATH, not by traversal", %{
      tmp_dir: dir
    } do
      # The case where the two rules DIVERGE: a per-directory traversal interleaves
      # `scripts/`, `reference/` and the top-level files by stack discipline; a global
      # relative-path sort does not. Only the second is reproducible across ports.
      d = Path.join(dir, "nested")
      write_skill!(d, "s/SKILL.md", "name: nested\ndescription: d", "body")

      files = [
        "zz-top.txt",
        "aa-top.txt",
        "reference/b.md",
        "reference/a.md",
        "scripts/run.sh",
        "scripts/deep/x.py"
      ]

      for f <- files do
        path = Path.join([d, "s", f])
        File.mkdir_p!(Path.dirname(path))
        File.write!(path, "x")
      end

      full = Skill.load(dirs: [d], sample_limit: 50)
      out = full.tool.execute.(%{"name" => "nested"}, %Toolnexus.Context{}).output

      seen =
        files
        |> Enum.map(&{&1, :binary.match(out, &1)})
        |> Enum.reject(&(elem(&1, 1) == :nomatch))
        |> Enum.sort_by(fn {_f, {at, _}} -> at end)
        |> Enum.map(&elem(&1, 0))

      # code-point order over the path RELATIVE to the skill dir
      assert seen == [
               "aa-top.txt",
               "reference/a.md",
               "reference/b.md",
               "scripts/deep/x.py",
               "scripts/run.sh",
               "zz-top.txt"
             ]

      # ...and the cap takes a PREFIX of exactly that order — sort BEFORE cap, so
      # the filesystem never decides WHICH files the model sees (ADR-0004 K1)
      capped = Skill.load(dirs: [d], sample_limit: 3)
      cout = capped.tool.execute.(%{"name" => "nested"}, %Toolnexus.Context{}).output

      assert Enum.filter(files, &String.contains?(cout, &1)) |> Enum.sort() ==
               ["aa-top.txt", "reference/a.md", "reference/b.md"]

      # the capped <skill_files> block lists EXACTLY the first three, nothing else
      # (the surrounding prose mentions `scripts/` as an example — match the entries)
      entries = Regex.scan(~r|<file>([^<]+)</file>|, cout) |> Enum.map(&Enum.at(&1, 1))
      assert length(entries) == 3
      refute Enum.any?(entries, &String.contains?(&1, "/scripts/"))
      refute Enum.any?(entries, &String.contains?(&1, "zz-top.txt"))
    end

    test "A27: the sample order discriminates ALL THREE wrong rules", %{tmp_dir: dir} do
      d = Path.join(dir, "discriminating")
      write_skill!(d, "s/SKILL.md", "name: disc\ndescription: d", "body")

      # Each file is here to kill one wrong rule:
      #   alpha-b.txt / alpha.txt / alpha/f.txt — FLAT vs PER-LEVEL. Flat code point
      #     over the relative path puts `alpha-b.txt` (0x2D) before `alpha.txt` (0x2E)
      #     before `alpha/f.txt` (0x2F); a per-directory walk descends `alpha/` by its
      #     bare name and yields `alpha/f.txt` at a different place. This is the rule
      #     the OLD elixir code would still have passed, so it matters most here.
      #   zz-dir/aaa.txt — BARE NAME vs RELATIVE PATH. By relative path it sits second
      #     from last; by bare name `aaa.txt` sorts FIRST of everything.
      #   ß.txt — LOCALE vs CODE POINT. U+00DF collates as "ss" (so a locale-aware
      #     compare puts it before `zz.txt`) but is 0xDF by code point (so it sorts
      #     LAST). It has no NFD decomposition, so macOS filename normalisation
      #     cannot hollow the probe out.
      files = ["alpha-b.txt", "alpha.txt", "alpha/f.txt", "zz-dir/aaa.txt", "zz.txt", "ß.txt"]

      for f <- Enum.shuffle(files) do
        path = Path.join([d, "s", f])
        File.mkdir_p!(Path.dirname(path))
        File.write!(path, "x")
      end

      expected = ["alpha-b.txt", "alpha.txt", "alpha/f.txt", "zz-dir/aaa.txt", "zz.txt", "ß.txt"]

      assert sample_order(d, "disc", 50) == expected

      # the three wrong answers, spelled out so a future reader sees what is excluded
      refute sample_order(d, "disc", 50) == Enum.sort_by(expected, &Path.basename/1)
      assert hd(expected) != "zz-dir/aaa.txt"
      assert List.last(expected) == "ß.txt", "locale collation would place ß before zz"

      # CAP AFTER vs CAP DURING: a prefix of the sorted order, and a late-sorting
      # file is ABSENT — under a cap taken mid-walk `zz.txt` would survive.
      assert_cap_discriminates!(Path.join([d, "s"]), 3)
      assert sample_order(d, "disc", 3) == ["alpha-b.txt", "alpha.txt", "alpha/f.txt"]
      refute "zz.txt" in sample_order(d, "disc", 3)
      refute "ß.txt" in sample_order(d, "disc", 3)
    end

    # A27c: PROBED against elixir's real walk. `File.ls!` returns CREATION order, and
    # the old code sorted entries PER DIRECTORY during a depth-first walk. This fixture
    # is built so first-N-by-WALK and first-N-by-SORT are different SETS, not merely
    # different orders — otherwise the broken code passes by filesystem accident.
    #
    #   `alpha/` beside `alpha-b.txt` — per-level sorting puts the DIRECTORY first
    #     (`alpha` < `alpha-b.txt`) and descends, so a walk-capped set takes
    #     `alpha/f.txt`; flat relative-path order puts `alpha-b.txt` first
    #     (`-` 0x2D < `/` 0x2F). Cap 1 ⇒ DIFFERENT SETS.
    #   `zz-dir/aaa.txt` — kills a bare-NAME sort: `aaa.txt` would come first overall.
    #   `ß.txt` — kills a LOCALE compare: collates as "ss" (before `zz-dir`) but is
    #     0xDF by code point, so it must come LAST. No NFD decomposition, so macOS
    #     filename normalisation cannot hollow the probe out.
    @discriminating ["alpha/f.txt", "alpha-b.txt", "zz-dir/aaa.txt", "ß.txt"]
    @discriminating_order ["alpha-b.txt", "alpha/f.txt", "zz-dir/aaa.txt", "ß.txt"]

    defp write_discriminating!(dir, content) do
      for f <- @discriminating do
        path = Path.join(dir, f)
        File.mkdir_p!(Path.dirname(path))
        File.write!(path, content)
      end
    end

    # A27d: the fixture PROVES ITSELF rather than trusting a hand-probe of this
    # machine's `File.ls!` order. It reproduces the PRE-FIX walk (a depth-first
    # traversal sorting entries PER DIRECTORY), computes first-N-by-walk and
    # first-N-by-sort, and fails loudly if they are ever the same SET — because then
    # the broken implementation would pass and the test would be proving nothing.
    # A probe is correct on the machine it ran on; this is correct everywhere, and
    # says so when it stops being.
    defp assert_cap_discriminates!(dir, n) do
      walk = prefix_walk(dir) |> Enum.take(n) |> MapSet.new()

      sorted =
        prefix_walk(dir)
        |> Enum.sort_by(&Path.relative_to(&1, dir))
        |> Enum.take(n)
        |> MapSet.new()

      refute MapSet.equal?(walk, sorted),
             "fixture is vacuous on this filesystem: first-#{n}-by-walk and " <>
               "first-#{n}-by-sort are the same SET (#{inspect(MapSet.to_list(walk))}) — " <>
               "the pre-fix implementation would pass this test"

      MapSet.to_list(sorted)
    end

    # the walk as it was BEFORE A25/A26: per-directory sort, depth-first, files inline
    defp prefix_walk(dir) do
      case File.ls(dir) do
        {:error, _} ->
          []

        {:ok, entries} ->
          entries
          |> Enum.sort()
          |> Enum.flat_map(fn e ->
            full = Path.join(dir, e)
            if File.dir?(full), do: prefix_walk(full), else: [full]
          end)
      end
    end

    test "A26/A27c: glob sorts by relative path BEFORE capping", %{tmp_dir: dir} do
      {:ok, tk} = Toolnexus.create_toolkit(builtins: true)
      glob = Enum.find(tk.tools, &(&1.name == "glob"))
      write_discriminating!(dir, "x")

      all = glob.execute.(%{"pattern" => "**/*.txt", "path" => dir}, %Toolnexus.Context{})
      assert String.split(all.output, "\n") == @discriminating_order

      # CONTENT, not order: a walk-capped glob would return `alpha/f.txt` here.
      # The precondition proves that on THIS filesystem, whatever it orders.
      assert assert_cap_discriminates!(dir, 1) == [Path.join(dir, "alpha-b.txt")]

      one =
        glob.execute.(
          %{"pattern" => "**/*.txt", "path" => dir, "limit" => 1},
          %Toolnexus.Context{}
        )

      assert String.split(one.output, "\n") == ["alpha-b.txt"]
    end

    test "A26/A27c: grep caps a SORTED file sequence", %{tmp_dir: dir} do
      {:ok, tk} = Toolnexus.create_toolkit(builtins: true)
      grep = Enum.find(tk.tools, &(&1.name == "grep"))
      write_discriminating!(dir, "NEEDLE here\n")

      assert assert_cap_discriminates!(dir, 1) == [Path.join(dir, "alpha-b.txt")]

      one =
        grep.execute.(%{"pattern" => "NEEDLE", "path" => dir, "limit" => 1}, %Toolnexus.Context{})

      # which file the model sees is decided by the sort, not the filesystem, and it
      # EMITS the same relative `/`-path it sorted on — no machine path leaks out
      assert one.output == "alpha-b.txt:1:NEEDLE here"
      refute one.output =~ dir
    end

    test "A27c: grep orders by relative path THEN LINE NUMBER NUMERICALLY", %{tmp_dir: dir} do
      {:ok, tk} = Toolnexus.create_toolkit(builtins: true)
      grep = Enum.find(tk.tools, &(&1.name == "grep"))

      # 12 lines so line 10 exists: sorting the rendered `path:line:text` as ONE
      # string would put `:10:` before `:2:`
      File.write!(Path.join(dir, "a.txt"), Enum.map_join(1..12, "\n", fn _ -> "NEEDLE" end))

      out = grep.execute.(%{"pattern" => "NEEDLE", "path" => dir}, %Toolnexus.Context{}).output

      nums =
        out
        |> String.split("\n")
        |> Enum.map(fn l -> l |> String.split(":") |> Enum.at(1) |> String.to_integer() end)

      assert nums == Enum.to_list(1..12)
      refute nums == Enum.sort_by(nums, &to_string/1), "numeric, not lexicographic"
    end

    test "A28: the sort key is the string that is EMITTED, with `/` separators", %{
      tmp_dir: dir
    } do
      {:ok, tk} = Toolnexus.create_toolkit(builtins: true)
      glob = Enum.find(tk.tools, &(&1.name == "glob"))

      for f <- ["b/x.txt", "a/y.txt"] do
        path = Path.join(dir, f)
        File.mkdir_p!(Path.dirname(path))
        File.write!(path, "x")
      end

      out = glob.execute.(%{"pattern" => "**/*.txt", "path" => dir}, %Toolnexus.Context{}).output
      emitted = String.split(out, "\n")

      # forward slashes, relative, and the emitted list IS its own sorted order
      assert Enum.all?(emitted, &(not String.contains?(&1, "\\")))
      refute Enum.any?(emitted, &String.starts_with?(&1, "/"))
      assert emitted == Enum.sort(emitted)
      assert emitted == ["a/y.txt", "b/x.txt"]
    end

    test "the `skill` tool's not-found list is ordered the same way", %{tmp_dir: dir} do
      for n <- ["é-skill", "Z-skill", "a-skill"] do
        write_skill!(dir, "#{Base.url_encode64(n, padding: false)}/SKILL.md", "name: #{n}\ndescription: d", "b")
      end

      src = Skill.load(dirs: [dir])
      r = src.tool.execute.(%{"name" => "nope"}, %Toolnexus.Context{})

      assert r.is_error
      assert r.output =~ "Available skills: Z-skill, a-skill, é-skill"
    end
  end

  describe "D6 skip detail and exposure" do
    @describetag :tmp_dir

    test "a skip record carries `detail` — the NATIVE parser error", %{tmp_dir: dir} do
      write_skill!(dir, "x/SKILL.md", "name: [unterminated", "body")
      %{skipped: [skip]} = Skill.list(dirs: [dir])

      assert skip.reason == "malformed-frontmatter"
      assert is_binary(skip.detail) and skip.detail != ""
    end

    test "`reason` stays byte-identical, and every skip has the `detail` key", %{tmp_dir: dir} do
      write_skill!(dir, "ok/SKILL.md", "name: ok-skill", "b")
      write_skill!(dir, "noname/SKILL.md", "description: nope", "b")
      write_skill!(dir, "zdup/SKILL.md", "name: ok-skill", "b")

      {%{skipped: skipped}, _} =
        ExUnit.CaptureIO.with_io(:stderr, fn -> Skill.list(dirs: [dir]) end)

      assert skipped |> Enum.map(& &1.reason) |> Enum.sort() ==
               ["duplicate-name", "missing-name"]

      assert Enum.all?(skipped, &Map.has_key?(&1, :detail))
    end

    test "A2: load/1 returns the skips as DATA on the Source — no ListSkills needed", %{
      tmp_dir: dir
    } do
      write_skill!(dir, "ok/SKILL.md", "name: fine", "b")
      write_skill!(dir, "bad/SKILL.md", "name: [broken", "b")

      src = Skill.load(dirs: [dir])
      assert Enum.map(src.skills, & &1.name) == ["fine"]
      assert [%{reason: "malformed-frontmatter", detail: d}] = src.skipped
      assert is_binary(d)
    end
  end

  describe "D6 / A1 discovery order" do
    @describetag :tmp_dir

    test "A1a: lexicographic by path relative to the root, first-wins", %{tmp_dir: dir} do
      write_skill!(dir, "b-second/SKILL.md", "name: dup", "B")
      write_skill!(dir, "a-first/SKILL.md", "name: dup", "A")

      {src, _} = ExUnit.CaptureIO.with_io(:stderr, fn -> Skill.load(dirs: [dir]) end)
      assert [%Skill.Info{content: "A"}] = src.skills
    end

    test "A15: a TOP-LEVEL skill beats a nested copy REGARDLESS of its first letter", %{
      tmp_dir: dir
    } do
      # `xlsx` is the case that proves DEPTH-first: under a pure code-point sort
      # `synced/…` (s) beats `xlsx` (x) and the nested copy would win — the winner
      # would depend on the skill's first letter vs a sibling DIRECTORY's name. A
      # docx-only fixture passes under BOTH rules and proves nothing.
      for name <- ["docx", "xlsx"] do
        d = Path.join(dir, "corpus-#{name}")
        write_skill!(d, "#{name}/SKILL.md", "name: #{name}", "TOP LEVEL")
        write_skill!(d, "synced/9f3a2b-uuid/#{name}/SKILL.md", "name: #{name}", "NESTED COPY")

        {src, _} = ExUnit.CaptureIO.with_io(:stderr, fn -> Skill.load(dirs: [d]) end)

        assert [%Skill.Info{name: ^name, content: "TOP LEVEL"}] = src.skills,
               "#{name}: the top-level copy must win"

        assert [%{reason: "duplicate-name", location: loc}] = src.skipped
        assert loc =~ "synced/9f3a2b-uuid/#{name}"
      end
    end

    test "A15: within ONE depth, code point is the tie-break", %{tmp_dir: dir} do
      write_skill!(dir, "z-deep/nested/SKILL.md", "name: dup", "DEEPER")
      write_skill!(dir, "b-second/SKILL.md", "name: dup", "B")
      write_skill!(dir, "a-first/SKILL.md", "name: dup", "A")

      {src, _} = ExUnit.CaptureIO.with_io(:stderr, fn -> Skill.load(dirs: [dir]) end)
      assert [%Skill.Info{content: "A"}] = src.skills
    end

    test "A1: roots keep the CALLER'S order", %{tmp_dir: dir} do
      one = Path.join(dir, "one")
      two = Path.join(dir, "two")
      write_skill!(one, "s/SKILL.md", "name: dup", "FROM ONE")
      write_skill!(two, "s/SKILL.md", "name: dup", "FROM TWO")

      {a, _} = ExUnit.CaptureIO.with_io(:stderr, fn -> Skill.load(dirs: [one, two]) end)
      {b, _} = ExUnit.CaptureIO.with_io(:stderr, fn -> Skill.load(dirs: [two, one]) end)

      assert [%Skill.Info{content: "FROM ONE"}] = a.skills
      assert [%Skill.Info{content: "FROM TWO"}] = b.skills
    end
  end
end
