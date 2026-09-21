defmodule Toolnexus.ClassifierTest do
  @moduledoc """
  `Classifier` (SPEC.md §8B), driven off the SHARED fixtures in `examples/judge/`.

  There is no per-language copy of a fixture: the files are the cross-language
  contract, so a port that passes here passes the same bytes every other port
  emits. The byte claim covers `model` + `questions` only — `state` is transmitted
  verbatim and is outside it, because numbers do not canonicalise across languages.
  """

  use ExUnit.Case, async: false

  alias Toolnexus.Classifier

  alias Toolnexus.Classifier.{
    Choice,
    ChoiceAnswer,
    Decision,
    Noul,
    NoulAnswer,
    Score,
    ScoreAnswer
  }

  @judge Path.expand("../../examples/judge", __DIR__)

  defp fixture(name), do: @judge |> Path.join(name <> ".json") |> File.read!() |> Jason.decode!()

  defp questions(map), do: Map.new(map, fn {k, q} -> {k, question(q)} end)

  defp question(%{"type" => "noul"} = q),
    do: %Noul{instructions: q["instructions"], criteria: q["criteria"]}

  defp question(%{"type" => "choice"} = q),
    do: %Choice{instructions: q["instructions"], criteria: q["criteria"]}

  defp question(%{"type" => "score"} = q),
    do: %Score{instructions: q["instructions"], criteria: q["criteria"]}

  defp sha256(bin), do: :crypto.hash(:sha256, bin) |> Base.encode16(case: :lower)

  defp assert_canonical(%{"request" => req} = fx) do
    bytes = Classifier.canonical_request(req["model"], questions(req["questions"]))
    assert bytes == fx["canonical"]
    assert sha256(bytes) == fx["canonicalSha256"]
    if fx["canonicalBytes"], do: assert(byte_size(bytes) == fx["canonicalBytes"])
    bytes
  end

  defp static_from(fx) do
    req = fx["request"]

    {:ok, c} =
      Classifier.create(
        style: "static",
        model: req["model"],
        decisions: [
          %{state: req["state"], questions: questions(req["questions"]), response: fx["response"]}
        ]
      )

    {c, req}
  end

  # ------------------------------------------------------------ canonical bytes

  for name <- ~w(base hardened numbers wide degenerate near-uniform) do
    test "canonical bytes and sha256 are exact — #{name}.json" do
      assert_canonical(fixture(unquote(name)))
    end
  end

  test "canonical bytes and sha256 are exact — every decisions.json entry" do
    entries = fixture("decisions")["entries"]
    assert length(entries) == 3

    for entry <- entries, do: assert_canonical(entry)

    # The three share ONE questions payload and differ only in state, which is why
    # they share one hash — and why a static corpus must key on the state too.
    assert entries |> Enum.map(& &1["canonicalSha256"]) |> Enum.uniq() |> length() == 1
  end

  test "an absent noul criteria and an empty one are DIFFERENT bytes" do
    absent = Classifier.canonical_request("m", %{"q" => %Noul{instructions: "i"}})

    empty =
      Classifier.canonical_request("m", %{
        "q" => %Noul{instructions: "i", criteria: %{"true" => "", "false" => ""}}
      })

    refute absent == empty
    refute String.contains?(absent, "criteria")
    assert String.contains?(empty, ~s("criteria":{"false":"","true":""}))
  end

  test "a score rubric is NEVER reordered — the order IS the level numbering" do
    bytes =
      Classifier.canonical_request("m", %{
        "s" => %Score{instructions: "i", criteria: ["zulu", "mike", "alpha"]}
      })

    assert String.contains?(bytes, ~s(["zulu","mike","alpha"]))
  end

  # ------------------------------------------------------------ parse

  test "base.json parses, a ZERO probability stays an entry, a wrong-type read errors" do
    fx = fixture("base")
    {c, req} = static_from(fx)

    assert {:ok, %Decision{} = d} =
             Classifier.evaluate(c, req["state"], questions(req["questions"]))

    assert d.calibrated == fx["expect"]["calibrated"]
    assert d.model == "typesafe/jev-1.13-20260917"
    assert d.usage.input_tokens == 398
    assert d.usage.output_tokens == 72

    assert {:ok, %NoulAnswer{noul: 0.98}} = Decision.noul(d, "is_refund_request")

    assert {:ok, %ChoiceAnswer{} = dept} = Decision.choice(d, "department")
    assert dept.choice == "shipping"
    assert dept.confidence == 0.41
    assert dept.near_uniform == false
    # A zero probability stays an ENTRY — never dropped, never made absent.
    assert Map.has_key?(dept.probabilities, "technical")
    assert dept.probabilities["technical"] == 0
    assert map_size(dept.probabilities) == 3

    assert {:ok, %ScoreAnswer{} = urg} = Decision.score(d, "urgency")
    assert urg.score == 1.21
    assert urg.confidence == 0.57
    # Numeric legend keys survive: nothing atomises or renumbers them.
    assert urg.legend == %{"0" => "routine", "1" => "elevated", "2" => "urgent"}
    assert ScoreAnswer.levels(urg) == ["routine", "elevated", "urgent"]

    assert {:error, msg} = Decision.noul(d, "department")
    assert msg =~ "is a choice answer, not noul"
    assert {:error, msg} = Decision.score(d, "nope")
    assert msg =~ "no answer"
  end

  test "numbers.json parses NUMERICALLY, never as strings" do
    fx = fixture("numbers")
    {c, req} = static_from(fx)

    assert {:ok, d} = Classifier.evaluate(c, req["state"], questions(req["questions"]))
    expect = fx["expect"]["parse"]

    assert {:ok, noul} = Decision.noul(d, "is_expensive")
    assert noul.noul == expect["answers.is_expensive.noul"]
    assert {:ok, score} = Decision.score(d, "urgency")
    assert score.score == expect["answers.urgency.score"]
    assert score.probabilities["0"] == expect["answers.urgency.probabilities.0"]
    assert d.usage.cost == expect["usage.cost"]

    # The state round-trips verbatim: integer 0 stays 0, not 0.0.
    assert req["state"] == fx["expect"]["stateRoundTrip"]
  end

  test "wide.json: 40 probability keys survive, near_uniform is false" do
    fx = fixture("wide")
    {c, req} = static_from(fx)

    assert {:ok, d} = Classifier.evaluate(c, req["state"], questions(req["questions"]))
    assert {:ok, skill} = Decision.choice(d, "skill")
    assert skill.choice == fx["expect"]["answers"]["skill"]["choice"]
    assert map_size(skill.probabilities) == fx["expect"]["answers"]["skill"]["probabilityCount"]
    assert skill.near_uniform == fx["expect"]["answers"]["skill"]["nearUniform"]
    # 40 criteria keys, above the 32-key small-map boundary, still sort ASCII.
    assert map_size(req["questions"]["skill"]["criteria"]) == 40
  end

  # ------------------------------------------------------------ near_uniform

  test "near-uniform.json: all four answers match the fixture, at the pinned tolerance" do
    fx = fixture("near-uniform")
    {c, req} = static_from(fx)

    assert fx["expect"]["tolerance"] == Classifier.near_uniform_tolerance()

    assert {:ok, d} = Classifier.evaluate(c, req["state"], questions(req["questions"]))

    for {key, want} <- fx["expect"]["answers"] do
      assert {:ok, a} = Decision.choice(d, key)
      assert a.near_uniform == want["nearUniform"], "near_uniform mismatch on #{key}"
    end
  end

  test "near_uniform? edge cases: empty is false, one entry is true, inclusive at the tolerance" do
    refute Classifier.near_uniform?(%{})
    assert Classifier.near_uniform?(%{"only" => 0.3})
    # n = 2, target 0.5. The fixtures never place a deviation within 1e-9 of the
    # tolerance, so neither does this test: 0.5 +/- 0.05 is not representable in
    # a double and the rule is decidable only off the exact boundary.
    assert Classifier.near_uniform?(%{"a" => 0.4501, "b" => 0.5499})
    refute Classifier.near_uniform?(%{"a" => 0.4499, "b" => 0.5501})
    # Never renormalised: a map that does not sum to 1 is judged as returned.
    refute Classifier.near_uniform?(%{"a" => 0.5, "b" => 0.5, "c" => 0.5})
  end

  # ------------------------------------------------------------ degenerate

  test "degenerate.json: exactly three keys warn, once per key, and the bytes are unchanged" do
    fx = fixture("degenerate")
    req = fx["request"]
    qs = questions(req["questions"])
    parent = self()

    {:ok, c} =
      Classifier.create(
        style: "custom",
        model: req["model"],
        on_metric: fn ev -> send(parent, {:metric, ev}) end,
        evaluate: fn _state, _questions -> {:ok, %Decision{model: req["model"]}} end
      )

    assert {:ok, _} = Classifier.evaluate(c, req["state"], qs)
    assert {:ok, _} = Classifier.evaluate(c, req["state"], qs)

    warned = collect_warnings()
    assert Enum.sort(warned) == fx["expect"]["warnings"]

    for key <- fx["expect"]["noWarning"], do: refute(key in warned)

    # Detection, never repair: the request a caller gets is identical with and
    # without detection.
    bytes = Classifier.canonical_request(req["model"], qs)
    assert bytes == fx["canonical"]
    assert sha256(bytes) == fx["canonicalSha256"]
  end

  defp collect_warnings(acc \\ []) do
    receive do
      {:metric, %{event: "classifier.warning"} = ev} ->
        refute Map.has_key?(ev, :error)
        assert ev.warning =~ ev.question
        collect_warnings([ev.question | acc])

      {:metric, _} ->
        collect_warnings(acc)
    after
      0 -> acc
    end
  end

  test "a single-option choice is never reported degenerate" do
    parent = self()

    {:ok, c} =
      Classifier.create(
        style: "custom",
        on_metric: fn ev -> send(parent, {:metric, ev}) end,
        evaluate: fn _s, _q -> {:ok, %Decision{}} end
      )

    assert {:ok, _} =
             Classifier.evaluate(c, "s", %{
               "solo" => %Choice{instructions: "i", criteria: %{"a" => "a"}}
             })

    assert collect_warnings() == []
  end

  # ------------------------------------------------------------ limits

  test "limits are rejected PRE-FLIGHT, naming the key and the limit, with no HTTP call" do
    parent = self()

    transport = fn _req ->
      send(parent, :called)
      {:ok, %{status: 200, headers: %{}, body: "{}"}}
    end

    {:ok, c} =
      Classifier.create(transport: transport, on_metric: fn ev -> send(parent, {:metric, ev}) end)

    too_many = for i <- 1..256, into: %{}, do: {"opt_#{i}", "means #{i}"}

    assert {:error, msg} =
             Classifier.evaluate(c, "s", %{
               "roster" => %Choice{instructions: "i", criteria: too_many}
             })

    assert msg =~ ~s("roster")
    assert msg =~ "255"
    assert msg =~ "256"

    assert {:error, msg} =
             Classifier.evaluate(c, "s", %{"r" => %Score{instructions: "i", criteria: ["only"]}})

    assert msg =~ ~s("r")
    assert msg =~ "2..10"

    assert {:error, msg} =
             Classifier.evaluate(c, "s", %{
               "r" => %Score{instructions: "i", criteria: Enum.map(1..11, &"l#{&1}")}
             })

    assert msg =~ "2..10"

    assert {:error, msg} =
             Classifier.evaluate(c, "s", %{"c" => %Choice{instructions: "i", criteria: %{}}})

    assert msg =~ "1..255"

    assert {:error, "classifier: no questions to evaluate"} = Classifier.evaluate(c, "s", %{})

    assert {:error, msg} = Classifier.evaluate(c, "s", %{"bad" => %{not: "a question"}})
    assert msg =~ "is not a question"

    refute_received :called

    # Every rejection still reports an `error` evaluate metric.
    assert_received {:metric, %{event: "classifier.evaluate", status: "error"}}
  end

  # ------------------------------------------------------------ secrets

  test "no credential and no expanded header value reaches an error, a metric, or a return value" do
    secret = "sk-not-a-real-key-000"
    header_secret = "hv-not-a-real-value-111"
    System.put_env("TOOLNEXUS_TEST_JUDGE_KEY", secret)
    System.put_env("TOOLNEXUS_TEST_JUDGE_HEADER", header_secret)
    on_exit(fn -> System.delete_env("TOOLNEXUS_TEST_JUDGE_KEY") end)
    on_exit(fn -> System.delete_env("TOOLNEXUS_TEST_JUDGE_HEADER") end)

    parent = self()

    transport = fn req ->
      send(parent, {:headers, req.headers})
      # A gateway happily reflects the credential it rejected back into its own body.
      {:ok,
       %{
         status: 401,
         headers: %{},
         body: ~s({"error":"invalid key #{secret}, header was #{header_secret}"})
       }}
    end

    metrics = :ets.new(:metrics, [:public, :bag])

    {:ok, c} =
      Classifier.create(
        api_key_env: "TOOLNEXUS_TEST_JUDGE_KEY",
        headers: %{"x-tenant" => "acme-${TOOLNEXUS_TEST_JUDGE_HEADER}"},
        transport: transport,
        retries: 0,
        on_metric: fn ev -> :ets.insert(metrics, {:ev, ev}) end
      )

    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})

    # The request really did carry both — otherwise this test proves nothing.
    assert_received {:headers, headers}
    assert headers["authorization"] == "Bearer " <> secret
    assert headers["x-tenant"] == "acme-" <> header_secret

    # ...and neither value is anywhere on the way out.
    assert msg =~ "HTTP 401"
    assert msg =~ "/systemone"
    refute msg =~ secret
    refute msg =~ header_secret

    rendered = metrics |> :ets.tab2list() |> inspect(limit: :infinity, printable_limit: :infinity)
    refute rendered =~ secret
    refute rendered =~ header_secret
    assert rendered =~ "classifier.evaluate"
  end

  test "a non-auth backend error surfaces the backend's own cause intact" do
    transport = fn _ ->
      {:ok,
       %{
         status: 400,
         headers: %{},
         body: ~s({"error":"Too many choices. Must have at most 255 choices."})
       }}
    end

    {:ok, c} = Classifier.create(transport: transport, retries: 0)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "Too many choices"
  end

  test "an over-long backend body is truncated rather than dumped whole" do
    transport = fn _ -> {:ok, %{status: 500, headers: %{}, body: String.duplicate("x", 500)}} end
    {:ok, c} = Classifier.create(transport: transport, retries: 0)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "…"
    assert byte_size(msg) < 400
  end

  # ------------------------------------------------------------ static backend

  test "the static backend distinguishes the three guard bands, and never guesses" do
    fx = fixture("decisions")
    entries = fx["entries"]
    model = hd(entries)["request"]["model"]

    decisions =
      Enum.map(entries, fn e ->
        %{
          state: e["request"]["state"],
          questions: questions(e["request"]["questions"]),
          response: e["response"]
        }
      end)

    {:ok, c} = Classifier.create(style: "static", model: model, decisions: decisions)

    scores =
      Map.new(entries, fn e ->
        qs = questions(e["request"]["questions"])
        assert {:ok, d} = Classifier.evaluate(c, e["request"]["state"], qs)
        assert {:ok, score} = Decision.score(d, "risk")
        {e["band"], score.score}
      end)

    # One questions payload, one canonical hash, three different answers: the
    # corpus is keyed on the STATE as well as the request.
    assert map_size(scores) == 3
    assert scores["allow"] < scores["ask"]
    assert scores["ask"] < scores["deny"]
    assert scores |> Map.values() |> Enum.uniq() |> length() == 3

    # An unrecorded state is an ERROR, never the nearest recorded band.
    unrecorded = %{"command" => "rm -rf /", "cwd" => "/repo", "tool" => "bash"}
    qs = questions(hd(entries)["request"]["questions"])
    assert {:error, msg} = Classifier.evaluate(c, unrecorded, qs)
    assert msg =~ "no recorded decision"
  end

  test "a static corpus entry may be a raw JSON binary, and a malformed one errors" do
    fx = fixture("base")
    req = fx["request"]
    qs = questions(req["questions"])

    {:ok, c} =
      Classifier.create(
        style: "static",
        model: req["model"],
        decisions: [
          %{state: req["state"], questions: qs, response: Jason.encode!(fx["response"])}
        ]
      )

    assert {:ok, %Decision{}} = Classifier.evaluate(c, req["state"], qs)

    {:ok, bad} =
      Classifier.create(
        style: "static",
        model: req["model"],
        decisions: [%{state: req["state"], questions: qs, response: "{not json"}]
      )

    assert {:error, msg} = Classifier.evaluate(bad, req["state"], qs)
    assert msg =~ "invalid JSON"
  end

  # ------------------------------------------------------------ options

  test "create/1 applies the §8B defaults" do
    assert {:ok, c} = Classifier.create()
    assert c.style == "systemone"
    assert c.base_url == Classifier.default_base_url()
    assert c.model == Classifier.default_model()
    assert c.api_key_env == Classifier.default_api_key_env()
    assert c.timeout == Classifier.default_timeout()
    assert c.retries == 2
    assert Classifier.max_choice_options() == 255
    assert Classifier.min_score_levels() == 2
    assert Classifier.max_score_levels() == 10
  end

  test "create/1 rejects a style whose required option is missing, and an unknown style" do
    assert {:error, msg} = Classifier.create(style: "llm")
    assert msg =~ ":client"
    assert {:error, msg} = Classifier.create(style: "custom")
    assert msg =~ ":evaluate"
    assert {:error, msg} = Classifier.create(style: "nonsense")
    assert msg =~ "unknown style"
    assert {:error, msg} = Classifier.create(style: "static", decisions: [%{state: "s"}])
    assert msg =~ "missing :questions"

    assert {:ok, _} =
             Classifier.create(%{style: "custom", evaluate: fn _, _ -> {:ok, %Decision{}} end})
  end

  test "choice_over/2 builds a choice over any (name, description) pairs" do
    q = Classifier.choice_over("Which skill?", %{"a" => "does a", "b" => "does b"})
    assert %Choice{instructions: "Which skill?"} = q
    assert q.criteria == %{"a" => "does a", "b" => "does b"}
  end

  test "request_params merge then body_transform, in §8 order, and state goes out verbatim" do
    parent = self()

    transport = fn req ->
      send(parent, {:body, req.body}) &&
        {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{}})}}
    end

    {:ok, c} =
      Classifier.create(
        transport: transport,
        request_params: %{"tenant" => "acme", "model" => "override-me"},
        body_transform: fn body -> Map.put(body, "trace", "t-1") end
      )

    assert {:ok, %Decision{}} =
             Classifier.evaluate(c, %{"n" => 1}, %{"q" => %Noul{instructions: "i"}})

    assert_received {:body, raw}
    body = Jason.decode!(raw)

    # A request_params key WINS on collision; body_transform runs last.
    assert body["model"] == "override-me"
    assert body["tenant"] == "acme"
    assert body["trace"] == "t-1"
    assert body["state"] == %{"n" => 1}
  end

  test "a body_transform returning nil leaves the body unchanged" do
    parent = self()

    transport = fn req ->
      send(parent, {:body, req.body}) &&
        {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{}})}}
    end

    {:ok, c} = Classifier.create(transport: transport, body_transform: fn _ -> nil end)
    assert {:ok, _} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert_received {:body, raw}
    assert Jason.decode!(raw)["state"] == "s"
  end

  test "a successful evaluate emits one ok metric with the model and the tokens" do
    fx = fixture("base")
    {c0, req} = static_from(fx)
    parent = self()
    c = %{c0 | on_metric: fn ev -> send(parent, {:metric, ev}) end}

    assert {:ok, _} = Classifier.evaluate(c, req["state"], questions(req["questions"]))
    assert_received {:metric, ev}
    assert ev.event == "classifier.evaluate"
    assert ev.status == "ok"
    assert ev.model == "typesafe/jev-1.13-20260917"
    assert ev.prompt_tokens == 398
    assert ev.completion_tokens == 72
    assert is_integer(ev.ms)
  end

  # ------------------------------------------------------------ resilience

  test "a 429 is retried with the §8 Retry-After rule, and on_error can veto the retry" do
    parent = self()
    {:ok, counter} = Agent.start_link(fn -> 0 end)

    transport = fn _ ->
      n = Agent.get_and_update(counter, &{&1, &1 + 1})
      send(parent, {:attempt, n})

      if n == 0 do
        {:ok, %{status: 429, headers: %{"retry-after" => ["0"]}, body: "slow down"}}
      else
        {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{},"model":"m"})}}
      end
    end

    {:ok, c} = Classifier.create(transport: transport, retries: 2)

    assert {:ok, %Decision{model: "m"}} =
             Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})

    assert_received {:attempt, 0}
    assert_received {:attempt, 1}

    # The same failure with a :fail classifier is not retried at all.
    {:ok, counter2} = Agent.start_link(fn -> 0 end)

    transport2 = fn _ ->
      Agent.update(counter2, &(&1 + 1))
      {:ok, %{status: 503, headers: %{}, body: "down"}}
    end

    {:ok, c2} = Classifier.create(transport: transport2, retries: 5, on_error: fn _ -> :fail end)
    assert {:error, _} = Classifier.evaluate(c2, "s", %{"q" => %Noul{instructions: "i"}})
    assert Agent.get(counter2, & &1) == 1
  end

  test "529 retries by default; an unlisted 5xx (520) and a permanent one (501) are terminal" do
    # TypeSafe documents 529 Overloaded as "retry with backoff"; the old six-status set
    # (408/429/500/502/503/504) made it terminal on the first attempt. The set stays an
    # ENUMERATION — "any 5xx" would sweep in 501/505 and change every host's behaviour.
    {:ok, counter} = Agent.start_link(fn -> 0 end)

    transport = fn _ ->
      n = Agent.get_and_update(counter, &{&1, &1 + 1})

      if n == 0,
        do: {:ok, %{status: 529, headers: %{}, body: "overloaded"}},
        else: {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{},"model":"m"})}}
    end

    {:ok, c} = Classifier.create(transport: transport, retries: 2)
    assert {:ok, %Decision{}} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert Agent.get(counter, & &1) == 2

    for status <- [520, 501, 422] do
      {:ok, tries} = Agent.start_link(fn -> 0 end)

      terminal = fn _ ->
        Agent.update(tries, &(&1 + 1))
        {:ok, %{status: status, headers: %{}, body: "no"}}
      end

      {:ok, c} = Classifier.create(transport: terminal, retries: 3)

      assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
      assert msg =~ "#{status}"
      assert Agent.get(tries, & &1) == 1, "status #{status} must not be retried by default"
    end
  end

  test ":retryable_statuses ADDS to the default set, and :on_error still overrides it" do
    cloudflare = [520, 521, 522, 523, 524, 525, 526, 527]

    # 520 retries once opted in; 429 STILL retries (additive, not a replacement).
    for status <- [520, 429] do
      {:ok, counter} = Agent.start_link(fn -> 0 end)

      transport = fn _ ->
        n = Agent.get_and_update(counter, &{&1, &1 + 1})

        if n == 0,
          do: {:ok, %{status: status, headers: %{}, body: "transient"}},
          else: {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{},"model":"m"})}}
      end

      {:ok, c} =
        Classifier.create(transport: transport, retries: 2, retryable_statuses: cloudflare)

      assert {:ok, %Decision{}} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
      assert Agent.get(counter, & &1) == 2, "status #{status} should have been retried"
    end

    # 501 is not on the list, so it stays terminal.
    {:ok, tries} = Agent.start_link(fn -> 0 end)

    terminal = fn _ ->
      Agent.update(tries, &(&1 + 1))
      {:ok, %{status: 501, headers: %{}, body: "nope"}}
    end

    {:ok, c} = Classifier.create(transport: terminal, retries: 3, retryable_statuses: cloudflare)
    assert {:error, _} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert Agent.get(tries, & &1) == 1

    # :on_error has the final say over a status the host itself listed.
    {:ok, tries2} = Agent.start_link(fn -> 0 end)

    listed = fn _ ->
      Agent.update(tries2, &(&1 + 1))
      {:ok, %{status: 520, headers: %{}, body: "origin"}}
    end

    {:ok, c2} =
      Classifier.create(
        transport: listed,
        retries: 5,
        retryable_statuses: cloudflare,
        on_error: fn _ -> :fail end
      )

    assert {:error, msg} = Classifier.evaluate(c2, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "520"
    assert Agent.get(tries2, & &1) == 1
  end

  test "a TypeSafe-shaped usage block reports an ABSENT cost, not a free call" do
    # TypeSafe's own API returns model/answers/usage and no `cost` key at all. Reporting 0
    # there would read as "this call was free" when the truth is "this backend does not say".
    body =
      ~s({"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.98}},) <>
        ~s("usage":{"input_tokens":331,"output_tokens":48}})

    {:ok, c} =
      Classifier.create(
        base_url: "https://api.typesafe.ai/v1",
        model: "jev-latest",
        transport: fn _ -> {:ok, %{status: 200, headers: %{}, body: body}} end
      )

    assert {:ok, d} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert d.usage.input_tokens == 331
    assert is_nil(d.usage.cost)
  end

  test "a transport error is classified, retried and finally reported" do
    {:ok, counter} = Agent.start_link(fn -> 0 end)

    transport = fn _ ->
      Agent.update(counter, &(&1 + 1))
      {:error, %RuntimeError{message: "econnrefused"}}
    end

    {:ok, c} = Classifier.create(transport: transport, retries: 1)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "econnrefused"
    assert Agent.get(counter, & &1) == 2
  end

  test "a non-exception transport error is still reported" do
    {:ok, c} = Classifier.create(transport: fn _ -> {:error, :nxdomain} end, retries: 0)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "nxdomain"
  end

  test "a list-shaped Retry-After header is honoured from a list-shaped header set" do
    parent = self()
    {:ok, counter} = Agent.start_link(fn -> 0 end)

    transport = fn _ ->
      n = Agent.get_and_update(counter, &{&1, &1 + 1})
      send(parent, {:attempt, n})

      if n == 0,
        do: {:ok, %{status: 500, headers: [{"Retry-After", "0"}], body: "x"}},
        else: {:ok, %{status: 200, headers: [], body: ~s({"answers":{}})}}
    end

    {:ok, c} = Classifier.create(transport: transport, retries: 1)
    assert {:ok, _} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert_received {:attempt, 1}
  end

  # ------------------------------------------------------------ llm style

  test "the llm style renders one structured-output call and reports calibrated: false" do
    parent = self()

    llm =
      Toolnexus.Client.create_in_process(
        model: "cheap-chat",
        generate: fn req ->
          send(parent, {:prompt, req.messages})

          %{
            content:
              "Sure! ```json\n" <>
                ~s({"answers":{"q":{"type":"choice","choice":"north","probabilities":{"north":0.9,"south":0.1},"confidence":0.9}}}) <>
                "\n```"
          }
        end
      )

    {:ok, c} = Classifier.create(style: "llm", client: llm, model: "cheap-chat")

    q = %{
      "q" => %Choice{
        instructions: "Which move?",
        criteria: %{"north" => "toward the apple", "south" => "into the tail"}
      }
    }

    assert {:ok, d} = Classifier.evaluate(c, "the snake is 4 long", q)

    # The model's self-report is never asserted as calibrated (ADR 0020).
    assert d.calibrated == false
    assert d.model == "cheap-chat"
    assert {:ok, a} = Decision.choice(d, "q")
    assert a.choice == "north"
    assert a.near_uniform == false

    assert_received {:prompt, messages}
    text = messages |> Enum.map(&inspect/1) |> Enum.join()
    assert text =~ "INDEPENDENT"
    assert text =~ "toward the apple"
  end

  test "an unparseable llm reply is no answer, never a repaired one" do
    llm =
      Toolnexus.Client.create_in_process(
        model: "m",
        generate: fn _ -> %{content: "I cannot help with that."} end
      )

    {:ok, c} = Classifier.create(style: "llm", client: llm)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "no JSON object"

    llm2 =
      Toolnexus.Client.create_in_process(
        model: "m",
        generate: fn _ -> %{content: "{not json}"} end
      )

    {:ok, c2} = Classifier.create(style: "llm", client: llm2)
    assert {:error, msg} = Classifier.evaluate(c2, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "invalid JSON"
  end

  # ------------------------------------------------------------ decoding

  test "an unknown or untagged answer type is an error, naming the key" do
    assert {:error, msg} = Classifier.decode_decision(~s({"answers":{"q":{"type":"vibe"}}}))
    assert msg =~ ~s("q")
    assert msg =~ "unknown type"

    assert {:error, msg} = Classifier.decode_decision(%{"answers" => %{"q" => %{"noul" => 1}}})
    assert msg =~ "no type discriminator"

    assert {:error, msg} = Classifier.decode_decision("not-a-decision")
    assert msg =~ "invalid JSON"

    assert {:error, msg} = Classifier.decode_decision(42)
    assert msg =~ "not a decision"
  end

  test "calibrated is true when absent and false only when the backend says so" do
    assert {:ok, %Decision{calibrated: true}} = Classifier.decode_decision(%{"answers" => %{}})

    assert {:ok, %Decision{calibrated: false}} =
             Classifier.decode_decision(%{"answers" => %{}, "calibrated" => false})
  end

  test "a custom evaluate may fail, and the failure is reported as an error metric" do
    parent = self()

    {:ok, c} =
      Classifier.create(
        style: "custom",
        evaluate: fn _s, _q -> {:error, "the rules engine said no"} end,
        on_metric: fn ev -> send(parent, {:metric, ev}) end
      )

    assert {:error, "the rules engine said no"} =
             Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})

    assert_received {:metric, %{status: "error", error: "the rules engine said no"}}
  end

  # ------------------------------------------------------------ absence

  test "a host that constructs no classifier sees byte-identical client behaviour" do
    client =
      Toolnexus.Client.create_in_process(model: "m", generate: fn _ -> %{content: "hi"} end)

    before = Toolnexus.Client.metrics(client)
    assert Toolnexus.Client.run(client, "hello", nil).text == "hi"
    after_run = Toolnexus.Client.metrics(client)

    # A classifier on the same metric sink never folds into the Prometheus registry.
    {:ok, c} =
      Classifier.create(
        style: "custom",
        evaluate: fn _, _ -> {:ok, %Decision{}} end,
        on_metric: fn _ -> :ok end
      )

    assert {:ok, _} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})

    assert Toolnexus.Client.metrics(client) == after_run
    refute after_run == before
    refute Toolnexus.Client.metrics(client) =~ "classifier"
  end

  # ------------------------------------------------------------ default transport

  defmodule EchoPlug do
    @moduledoc false
    import Plug.Conn

    def init(opts), do: opts

    def call(conn, _opts) do
      {:ok, body, conn} = read_body(conn)

      send(
        :classifier_echo,
        {:req, conn.request_path, body, Plug.Conn.get_req_header(conn, "x-tenant")}
      )

      conn
      |> put_resp_content_type("application/json")
      |> send_resp(
        200,
        ~s({"model":"served","answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":3,"output_tokens":4}})
      )
    end
  end

  test "the DEFAULT Req transport posts the canonical body to {base_url}/systemone" do
    Process.register(self(), :classifier_echo)

    on_exit(fn ->
      if Process.whereis(:classifier_echo), do: Process.unregister(:classifier_echo)
    end)

    port = 1024 + :rand.uniform(60_000)
    start_supervised!({Bandit, plug: EchoPlug, scheme: :http, ip: {127, 0, 0, 1}, port: port})

    {:ok, c} =
      Classifier.create(
        base_url: "http://127.0.0.1:#{port}/v1/",
        headers: %{"x-tenant" => "acme"},
        http_options: [retry: false]
      )

    assert {:ok, d} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert d.model == "served"
    assert d.usage.input_tokens == 3

    assert_receive {:req, path, body, tenant}
    # The trailing slash on base_url never doubles up.
    assert path == "/v1/systemone"
    assert tenant == ["acme"]
    assert Jason.decode!(body)["state"] == "s"
  end

  test "an unreachable endpoint fails through the default transport, naming nothing secret" do
    {:ok, c} = Classifier.create(base_url: "http://127.0.0.1:1/v1", retries: 0, timeout: 500)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "/systemone"
  end

  # ------------------------------------------------------------ odds and ends

  test "atom keys in a state map or a criteria map canonicalise as strings" do
    bytes =
      Classifier.canonical_request("m", %{
        q: %Choice{instructions: "i", criteria: %{north: "a", south: "b"}}
      })

    assert bytes ==
             ~s({"model":"m","questions":{"q":{"criteria":{"north":"a","south":"b"},"instructions":"i","type":"choice"}}})
  end

  test "a retry with no Retry-After falls back to backoff, and a bodyless response is not echoed" do
    parent = self()
    {:ok, counter} = Agent.start_link(fn -> 0 end)

    transport = fn _ ->
      n = Agent.get_and_update(counter, &{&1, &1 + 1})
      send(parent, {:attempt, n})

      if n == 0,
        do: {:ok, %{status: 500, body: nil}},
        else: {:ok, %{status: 200, body: ~s({"answers":{}})}}
    end

    {:ok, c} = Classifier.create(transport: transport, retries: 1)
    assert {:ok, _} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert_received {:attempt, 1}
  end

  test "an absent or empty api key env sends no Authorization header at all" do
    parent = self()

    transport = fn req ->
      send(parent, {:headers, req.headers}) &&
        {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{}})}}
    end

    System.put_env("TOOLNEXUS_TEST_JUDGE_EMPTY", "")
    on_exit(fn -> System.delete_env("TOOLNEXUS_TEST_JUDGE_EMPTY") end)

    for env <- ["TOOLNEXUS_TEST_JUDGE_EMPTY", "TOOLNEXUS_TEST_JUDGE_ABSENT"] do
      {:ok, c} = Classifier.create(api_key_env: env, transport: transport)
      assert {:ok, _} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
      assert_received {:headers, headers}
      refute Map.has_key?(headers, "authorization")
    end
  end

  test "a non-binary backend body is rendered, not crashed on" do
    transport = fn _ -> {:ok, %{status: 400, headers: %{}, body: %{"error" => "nope"}}} end
    {:ok, c} = Classifier.create(transport: transport, retries: 0)
    assert {:error, msg} = Classifier.evaluate(c, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "nope"

    iodata = fn _ -> {:ok, %{status: 400, headers: %{}, body: ["par", "tial"]}} end
    {:ok, c2} = Classifier.create(transport: iodata, retries: 0)
    assert {:error, msg} = Classifier.evaluate(c2, "s", %{"q" => %Noul{instructions: "i"}})
    assert msg =~ "partial"

    blank = fn _ -> {:ok, %{status: 400, headers: %{}, body: "   "}} end
    {:ok, c3} = Classifier.create(transport: blank, retries: 0)
    assert {:error, msg} = Classifier.evaluate(c3, "s", %{"q" => %Noul{instructions: "i"}})
    # A blank body adds no cause suffix at all — the status and the endpoint, nothing else.
    assert String.ends_with?(msg, "HTTP 400")
  end

  test "reading a noul or score answer as the wrong type names the type it actually is" do
    {:ok, d} =
      Classifier.decode_decision(%{
        "answers" => %{
          "n" => %{"type" => "noul", "noul" => 0.5},
          "s" => %{"type" => "score", "score" => 1.0, "legend" => %{"0" => "a", "1" => "b"}}
        }
      })

    assert {:error, msg} = Decision.choice(d, "n")
    assert msg =~ "is a noul answer, not choice"
    assert {:error, msg} = Decision.noul(d, "s")
    assert msg =~ "is a score answer, not noul"
  end
end
