defmodule Toolnexus.JudgeBatteriesTest do
  use ExUnit.Case, async: true

  alias Toolnexus.Classifier, as: C
  alias Toolnexus.{Answer, Client, Request, Tool, ToolResult}

  alias Toolnexus.Judge.{
    AgentRouter,
    Batteries,
    ContentGuard,
    IsComplete,
    ModelRouter,
    SkillRelevance,
    ToolGuard,
    ToolRelevance,
    ToolResultFilter
  }

  @dir Path.expand("../../examples/judge/batteries", __DIR__)
  defp load(n), do: @dir |> Path.join(n) |> File.read!() |> Jason.decode!()

  # ------------------------------------------------------------ classifiers

  defp qmap(wire) do
    Map.new(wire, fn
      {k, %{"type" => "noul"} = q} ->
        {k, %C.Noul{instructions: q["instructions"], criteria: q["criteria"]}}

      {k, %{"type" => "choice"} = q} ->
        {k, %C.Choice{instructions: q["instructions"], criteria: q["criteria"]}}

      {k, %{"type" => "score"} = q} ->
        {k, %C.Score{instructions: q["instructions"], criteria: q["criteria"]}}
    end)
  end

  defp failing do
    {:ok, c} = C.create(style: "custom", evaluate: fn _, _ -> {:error, "boom"} end)
    c
  end

  defp battery_classifier(%{"error" => true}), do: failing()

  defp battery_classifier(%{"calls" => [], "name" => name}) do
    me = self()

    {:ok, c} =
      C.create(
        style: "custom",
        evaluate: fn _, _ ->
          send(me, {:unexpected_call, name})
          {:error, "unexpected call"}
        end
      )

    c
  end

  defp battery_classifier(%{"calls" => calls}) do
    decisions =
      Enum.map(calls, fn k ->
        %{state: k["state"], questions: qmap(k["questions"]), response: k["response"]}
      end)

    {:ok, c} = C.create(style: "static", decisions: decisions)
    c
  end

  defp fixed(answers) do
    {:ok, c} =
      C.create(
        style: "custom",
        evaluate: fn _, _ -> C.decode_decision(%{"model" => "m", "answers" => answers}) end
      )

    c
  end

  # ------------------------------------------------------------ fixture plumbing

  defp opts(o) do
    Enum.flat_map(o, fn
      {"onError", v} -> [on_error: String.to_atom(v)]
      {"bands", b} -> [bands: %{low: b["low"], high: b["high"]}]
      {"role", r} -> [role: r]
      {"askAt", v} -> [ask_at: v]
      {"denyAt", v} -> [deny_at: v]
      {"dimensions", ds} -> [dimensions: ds]
    end)
  end

  defp norm(v) when is_struct(v), do: v |> Map.from_struct() |> norm()
  defp norm(m) when is_map(m), do: Map.new(m, fn {k, v} -> {to_string(k), norm(v)} end)
  defp norm(l) when is_list(l), do: Enum.map(l, &norm/1)
  defp norm(a) when is_atom(a) and a not in [nil, true, false], do: Atom.to_string(a)
  defp norm(v), do: v

  defp assert_verdict(name, want, verdict) do
    got = norm(verdict)

    for {k, w} <- want do
      if k == "error" do
        assert got["error"] != nil == w, "#{name}: error present, want #{w}: #{inspect(got)}"
      else
        assert Map.has_key?(got, k), "#{name}: verdict lacks #{k}"
        assert got[k] == w, "#{name}: #{k} = #{inspect(got[k])}, want #{inspect(w)}"
      end
    end

    refute_received {:unexpected_call, _}
  end

  defp run_case("tool-guard.json", k, cl) do
    {:ok, g} = ToolGuard.new(cl, opts(k["options"]))
    ToolGuard.check(g, k["input"])
  end

  defp run_case("tool-relevance.json", k, cl) do
    {:ok, r} = ToolRelevance.new(cl, opts(k["options"]))
    ToolRelevance.select(r, k["input"]["prompt"], k["input"]["tools"])
  end

  defp run_case("skill-relevance.json", k, cl) do
    {:ok, r} = SkillRelevance.new(cl, opts(k["options"]))
    SkillRelevance.select(r, k["input"]["prompt"], k["input"]["skills"])
  end

  defp run_case("tool-result-filter.json", k, cl) do
    {:ok, f} = ToolResultFilter.new(cl, opts(k["options"]))
    ToolResultFilter.filter(f, k["input"]["query"], k["input"]["chunks"])
  end

  defp run_case("is-complete.json", k, cl) do
    {:ok, ic} = IsComplete.new(cl, opts(k["options"]))
    IsComplete.check(ic, k["input"]["task"], k["input"]["answer"])
  end

  defp run_case("agent-router.json", k, cl) do
    r = AgentRouter.new(cl, opts(k["options"]))
    AgentRouter.pick(r, k["input"]["task"], k["input"]["agents"], k["input"]["fallback"])
  end

  defp run_case("content-guard.json", k, cl) do
    {:ok, g} = ContentGuard.new(cl, opts(k["options"]))
    ContentGuard.check(g, k["input"]["text"])
  end

  defp run_case("model-router.json", k, cl) do
    r = ModelRouter.new(cl, k["input"]["models"], opts(k["options"]))
    ModelRouter.pick(r, k["input"]["prompt"], k["input"]["fallback"])
  end

  @files ~w(tool-guard.json tool-relevance.json skill-relevance.json tool-result-filter.json
            is-complete.json agent-router.json content-guard.json model-router.json)

  for file <- @files do
    @file_name file
    test "fixture #{file}: every case" do
      cases = load(@file_name)["cases"]
      assert cases != []

      for k <- cases do
        v = run_case(@file_name, k, battery_classifier(k))
        assert_verdict("#{@file_name}/#{k["name"]}", k["want"], v)
      end
    end
  end

  test "latest_user_text: user-text-cases.json" do
    cases = load("user-text-cases.json")["cases"]
    assert cases != []

    for k <- cases do
      assert Batteries.latest_user_text(k["messages"]) == k["want"], k["name"]
    end
  end

  test "latest_user_text accepts atom-keyed messages" do
    assert Batteries.latest_user_text([%{role: "user", content: [%{type: "text", text: "a"}]}]) ==
             "a"
  end

  test "on_error is required for the six policy batteries" do
    cl = failing()

    for {mod, extra} <- [
          {ToolGuard, []},
          {ToolRelevance, []},
          {SkillRelevance, []},
          {ToolResultFilter, [on_error: :maybe]},
          {IsComplete, []},
          {ContentGuard, [on_error: "sometimes"]}
        ] do
      assert {:error, msg} = mod.new(cl, extra)
      assert msg =~ "on_error", inspect(mod)
    end

    assert {:ok, %ToolGuard{on_error: :open}} = ToolGuard.new(cl, on_error: "open")
  end

  test "classifier exceptions and odd reasons never propagate" do
    {:ok, c} =
      C.create(style: "custom", evaluate: fn _, _ -> {:error, %RuntimeError{message: "x"}} end)

    {:ok, ic} = IsComplete.new(c, on_error: :open)
    v = IsComplete.check(ic, "t", "a")
    assert v.complete and v.error == "x" and v.calibrated == false

    {:ok, c} = C.create(style: "custom", evaluate: fn _, _ -> {:error, :nope} end)
    {:ok, ic} = IsComplete.new(c, on_error: :closed)
    v = IsComplete.check(ic, "t", "a")
    refute v.complete
    assert v.error == ":nope"
  end

  test "default role accessors" do
    for m <- [
          ToolGuard,
          ToolRelevance,
          SkillRelevance,
          ToolResultFilter,
          IsComplete,
          AgentRouter,
          ContentGuard,
          ModelRouter
        ] do
      assert is_binary(m.default_role())
    end

    assert length(ToolGuard.risk_rubric()) == 4
    assert length(ContentGuard.default_dimensions()) == 2
  end

  # ------------------------------------------------------------ client plumbing

  defp recorder(style \\ "openai") do
    {:ok, agent} = Agent.start_link(fn -> [] end)

    transport = fn req ->
      n = Agent.get_and_update(agent, fn bodies -> {length(bodies), bodies ++ [req.body]} end)

      {:ok,
       %{status: 200, headers: %{"content-type" => "application/json"}, body: reply(style, n)}}
    end

    {agent, transport}
  end

  defp reply("openai", 0),
    do: %{
      "choices" => [
        %{
          "message" => %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" => [
              %{
                "id" => "c1",
                "type" => "function",
                "function" => %{"name" => "deploy", "arguments" => "{}"}
              }
            ]
          }
        }
      ]
    }

  defp reply("openai", _),
    do: %{"choices" => [%{"message" => %{"role" => "assistant", "content" => "done"}}]}

  defp reply("anthropic", 0),
    do: %{
      "content" => [%{"type" => "tool_use", "id" => "c1", "name" => "deploy", "input" => %{}}]
    }

  defp reply("anthropic", _), do: %{"content" => [%{"type" => "text", "text" => "done"}]}

  defp bodies(agent), do: Agent.get(agent, & &1)

  defp client(transport, extra, style \\ "openai") do
    Client.create(
      [
        base_url: "http://x/v1",
        style: style,
        model: "configured",
        api_key: "k",
        transport: transport,
        max_turns: 4,
        retry_base_ms: 1
      ] ++ extra
    )
  end

  defp tool(name, out, ran \\ nil) do
    %Tool{
      name: name,
      description: name,
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _, _ ->
        if ran, do: Agent.update(ran, &(&1 + 1))
        ToolResult.ok(out)
      end
    }
  end

  defp risk(score) do
    fixed(%{
      "risk" => %{
        "type" => "score",
        "score" => score,
        "confidence" => 0.9,
        "probabilities" => %{"0" => 0.25, "1" => 0.25, "2" => 0.25, "3" => 0.25},
        "legend" => %{"0" => "a", "1" => "b", "2" => "c", "3" => "d"}
      }
    })
  end

  # ------------------------------------------------------------ before_llm model override

  for style <- ["openai", "anthropic"] do
    @style style
    test "before_llm model override is per turn (#{style})" do
      {agent, transport} = recorder(@style)
      {:ok, seen} = Agent.start_link(fn -> [] end)

      hooks = %{
        before_llm: fn ev -> if ev.turn == 0, do: %{model: "small-fast"}, else: nil end,
        after_llm: fn ev -> Agent.update(seen, &(&1 ++ [ev.model])) end
      }

      r = Client.run(client(transport, [hooks: hooks], @style), "go", [tool("deploy", "D")])
      assert r.status == "done"
      assert Enum.map(bodies(agent), & &1["model"]) == ["small-fast", "configured"]
      assert Agent.get(seen, & &1) == ["small-fast", "configured"]
    end
  end

  for style <- ["openai", "anthropic"] do
    @style style
    test "translate honours the before_llm model override (#{style})" do
      {agent, transport} = recorder(@style)
      me = self()

      hooks = %{
        before_llm: fn _ -> %{model: "small-fast"} end,
        after_llm: fn ev -> send(me, {:after, ev.model}) end
      }

      Client.translate(client(transport, [hooks: hooks], @style), [
        %{"role" => "user", "content" => "hi"}
      ])

      assert [%{"model" => "small-fast"}] = bodies(agent)
      assert_received {:after, "small-fast"}
    end
  end

  test "before_llm empty / nil model keeps the configured model" do
    {agent, transport} = recorder()
    hooks = %{before_llm: fn ev -> if ev.turn == 0, do: %{model: ""}, else: %{model: nil} end}
    Client.run(client(transport, hooks: hooks), "go", [tool("deploy", "D")])
    assert Enum.map(bodies(agent), & &1["model"]) == ["configured", "configured"]
  end

  # ------------------------------------------------------------ ToolGuard hook

  test "ToolGuard hook: ask halts pending, deny short-circuits, allow runs, approval runs once" do
    {:ok, ran} = Agent.start_link(fn -> 0 end)
    {:ok, next_ran} = Agent.start_link(fn -> false end)
    next = fn _ev -> Agent.update(next_ran, fn _ -> true end) && nil end
    deploy = tool("deploy", "DEPLOYED", ran)

    # ask
    {:ok, g} = ToolGuard.new(risk(1.8), on_error: :closed)
    {_a, t} = recorder()

    r =
      Client.run(client(t, hooks: %{before_tool: ToolGuard.as_hook(g, next)}), "ship it", [deploy])

    assert r.status == "pending"
    assert %Request{id: "toolguard:c1", kind: "approval"} = r.pending
    assert r.pending.prompt == "Approve the call to deploy? (medium risk)"

    assert r.pending.data == %{
             "tool" => "deploy",
             "arguments" => %{},
             "reason" => "medium risk",
             "risk" => 1.8
           }

    refute Agent.get(next_ran, & &1)
    assert Agent.get(ran, & &1) == 0

    # deny
    {:ok, g} = ToolGuard.new(risk(2.9), on_error: :closed)
    {_a, t} = recorder()

    r =
      Client.run(client(t, hooks: %{before_tool: ToolGuard.as_hook(g, next)}), "ship it", [deploy])

    assert [%{output: "denied by tool guard: high risk", is_error: true}] = r.tool_calls
    refute Agent.get(next_ran, & &1)
    assert Agent.get(ran, & &1) == 0

    # allow
    {:ok, g} = ToolGuard.new(risk(0.1), on_error: :closed)
    {_a, t} = recorder()

    r =
      Client.run(client(t, hooks: %{before_tool: ToolGuard.as_hook(g, next)}), "ship it", [deploy])

    assert [%{output: "DEPLOYED"}] = r.tool_calls
    assert Agent.get(next_ran, & &1)
    assert Agent.get(ran, & &1) == 1

    # allow without next: no override
    {:ok, g} = ToolGuard.new(risk(0.1), on_error: :closed)
    assert ToolGuard.as_hook(g).(%{name: "deploy", args: %{}, id: "c1", turn: 0}) == nil

    # approved through wait_for: runs once, no re-ask
    {:ok, g} = ToolGuard.new(risk(1.8), on_error: :closed)
    {_a, t} = recorder()

    r =
      Client.run(
        client(t,
          hooks: %{before_tool: ToolGuard.as_hook(g)},
          wait_for: fn %Request{id: id} -> %Answer{id: id, ok: true} end
        ),
        "ship it",
        [deploy]
      )

    assert r.status != "pending"
    assert [%{output: "DEPLOYED"}] = r.tool_calls
    assert Agent.get(ran, & &1) == 2
  end

  # ------------------------------------------------------------ ToolRelevance hook

  test "ToolRelevance hook drops a tool from the request body" do
    {:ok, rel} =
      ToolRelevance.new(
        fixed(%{
          "deploy" => %{"type" => "noul", "noul" => 0.9},
          "send_email" => %{"type" => "noul", "noul" => 0.05}
        }),
        on_error: :open
      )

    {agent, t} = recorder()

    Client.run(client(t, hooks: %{before_llm: ToolRelevance.as_hook(rel)}), "ship it", [
      tool("deploy", "D"),
      tool("send_email", "E")
    ])

    [first | _] = bodies(agent)
    assert [%{"function" => %{"name" => "deploy"}}] = first["tools"]

    # nothing dropped / no tools / no text: plain delegation
    {:ok, keep} =
      ToolRelevance.new(fixed(%{"deploy" => %{"type" => "noul", "noul" => 0.9}}), on_error: :open)

    ev = %{
      messages: [%{"role" => "user", "content" => "x"}],
      tools: [%{"name" => "deploy"}],
      model: "m",
      turn: 0
    }

    assert ToolRelevance.as_hook(keep).(ev) == nil
    assert ToolRelevance.as_hook(keep).(%{ev | tools: []}) == nil

    assert ToolRelevance.as_hook(keep, fn _ -> %{model: "n"} end).(%{ev | messages: []}) == %{
             model: "n"
           }
  end

  test "before_llm merge: next sees the override, next's fields win, absent keep the battery's" do
    {:ok, rel} =
      ToolRelevance.new(
        fixed(%{
          "a" => %{"type" => "noul", "noul" => 0.9},
          "b" => %{"type" => "noul", "noul" => 0.05}
        }),
        on_error: :open
      )

    ev = %{
      messages: [%{"role" => "user", "content" => "x"}],
      tools: [%{"name" => "a"}, %{"name" => "b"}],
      model: "m",
      turn: 0
    }

    me = self()
    out = ToolRelevance.as_hook(rel, fn e -> send(me, {:saw, e.tools}) && %{model: "z"} end).(ev)
    assert_received {:saw, [%{"name" => "a"}]}
    assert out == %{tools: [%{"name" => "a"}], model: "z"}
    assert ToolRelevance.as_hook(rel, fn _ -> nil end).(ev) == %{tools: [%{"name" => "a"}]}
  end

  # ------------------------------------------------------------ ContentGuard hook

  test "ContentGuard hook: block before any request, review delegates, closed error" do
    {:ok, g} =
      ContentGuard.new(
        fixed(%{
          "harmful" => %{"type" => "noul", "noul" => 0.96},
          "prompt_injection" => %{"type" => "noul", "noul" => 0.9}
        }),
        on_error: :closed
      )

    {agent, t} = recorder()

    assert_raise ContentGuard.BlockedError,
                 "content guard blocked: harmful, prompt_injection",
                 fn ->
                   Client.run(client(t, hooks: %{before_llm: ContentGuard.as_hook(g)}), "idiot", [
                     tool("deploy", "D")
                   ])
                 end

    assert bodies(agent) == []

    {:ok, g} =
      ContentGuard.new(
        fixed(%{
          "harmful" => %{"type" => "noul", "noul" => 0.5},
          "prompt_injection" => %{"type" => "noul", "noul" => 0.1}
        }),
        on_error: :closed
      )

    me = self()
    h = ContentGuard.as_hook(g, fn _ -> send(me, :called) && nil end)

    assert h.(%{
             messages: [%{"role" => "user", "content" => "meh"}],
             tools: [],
             model: "m",
             turn: 0
           }) == nil

    assert_received :called

    {:ok, ge} = ContentGuard.new(failing(), on_error: :closed)

    assert_raise ContentGuard.BlockedError, "content guard blocked: classifier error", fn ->
      ContentGuard.as_hook(ge).(%{
        messages: [%{"role" => "user", "content" => "x"}],
        tools: [],
        model: "m",
        turn: 0
      })
    end
  end

  # ------------------------------------------------------------ ToolResultFilter hook

  test "ToolResultFilter hook filters chunks; passes through single-chunk / error / parts" do
    {:ok, f} =
      ToolResultFilter.new(
        fixed(%{
          "0" => %{"type" => "noul", "noul" => 0.9},
          "1" => %{"type" => "noul", "noul" => 0.05},
          "2" => %{"type" => "noul", "noul" => 0.5}
        }),
        on_error: :open
      )

    me = self()
    h = ToolResultFilter.as_hook(f, fn ev -> send(me, {:next, ev.result.output}) && nil end)

    ov =
      h.(%{name: "t", args: %{}, result: %ToolResult{output: "a\n\nb\n\nc"}, id: "c1", turn: 0})

    assert ov.result.output == "a\n\nc"
    assert_received {:next, "a\n\nc"}

    # next's override wins
    win = %{result: ToolResult.ok("N")}

    assert ToolResultFilter.as_hook(f, fn _ -> win end).(%{
             name: "t",
             args: %{},
             result: %ToolResult{output: "a\n\nb"}
           }) == win

    {:ok, fe} = ToolResultFilter.new(failing(), on_error: :closed)

    for r <- [
          %ToolResult{output: "one"},
          %ToolResult{output: "a\n\nb", is_error: true},
          %ToolResult{output: "a\n\nb", parts: [%{type: "image"}]}
        ] do
      assert ToolResultFilter.as_hook(fe).(%{name: "t", args: %{}, result: r}) == nil
    end
  end

  # ------------------------------------------------------------ ModelRouter hook

  @models [
    %{id: "small-fast", description: "cheap"},
    %{id: "large-reasoning", description: "dear"}
  ]
  @sure %{
    "model" => %{
      "type" => "choice",
      "choice" => "small-fast",
      "confidence" => 0.91,
      "probabilities" => %{"small-fast" => 0.91, "large-reasoning" => 0.09}
    }
  }
  @unsure %{
    "model" => %{
      "type" => "choice",
      "choice" => "small-fast",
      "confidence" => 0.6,
      "probabilities" => %{"small-fast" => 0.6, "large-reasoning" => 0.4}
    }
  }

  test "ModelRouter hook: sure routes, unsure keeps configured, no override when unneeded, next wins" do
    for {ans, want} <- [{@sure, "small-fast"}, {@unsure, "configured"}] do
      {agent, t} = recorder()
      r = ModelRouter.new(fixed(ans), @models)

      Client.run(client(t, hooks: %{before_llm: ModelRouter.as_hook(r)}), "capital of France?", [
        tool("deploy", "D")
      ])

      assert Enum.all?(bodies(agent), &(&1["model"] == want)), inspect(bodies(agent))
    end

    ev = %{
      model: "small-fast",
      messages: [%{"role" => "user", "content" => "x"}],
      tools: [],
      turn: 0
    }

    for ans <- [@unsure, @sure] do
      assert ModelRouter.as_hook(ModelRouter.new(fixed(ans), @models)).(ev) == nil
    end

    # no models: no classifier call
    assert ModelRouter.as_hook(ModelRouter.new(failing(), [])).(ev) == nil

    # no router: verbatim
    {agent, t} = recorder()
    Client.run(client(t, []), "x", [tool("deploy", "D")])
    assert hd(bodies(agent))["model"] == "configured"

    # next's model wins; next sees the routed model
    me = self()
    r = ModelRouter.new(fixed(@sure), @models)

    next = fn e ->
      send(me, {:next_saw, e.model})
      %{model: "pinned"}
    end

    {agent, t} = recorder()

    Client.run(client(t, hooks: %{before_llm: ModelRouter.as_hook(r, next)}), "x", [
      tool("deploy", "D")
    ])

    assert_received {:next_saw, "small-fast"}
    assert hd(bodies(agent))["model"] == "pinned"
  end
end
