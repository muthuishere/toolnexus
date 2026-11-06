defmodule Toolnexus.ClassifierBackendsTest do
  @moduledoc """
  Classifier backends, images, cache and validation (SPEC.md §8B, OpenSpec change
  add-classifier-backends), driven off the SHARED fixtures in
  `examples/judge/backends/`. Every fixture file is exercised. Hermetic: an
  injected `:transport` (and one local Bandit endpoint) stands in for the backend.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{ContentPart, Judge}
  alias Toolnexus.Classifier

  alias Toolnexus.Classifier.{
    BatchResult,
    Choice,
    Decision,
    MemoryCache,
    Noul,
    NoulAnswer,
    Raw,
    RawAnswer,
    Score,
    Thresholds,
    Unsupported
  }

  @dir Path.expand("../../examples/judge", __DIR__)
  @noul_reply ~s({"model":"jev-1.14.0","answers":{"q":{"type":"noul","noul":0.91}},"usage":{"input_tokens":3,"output_tokens":1}})

  defp fixture(name), do: Path.join([@dir, "backends", name]) |> File.read!() |> Jason.decode!()

  defp questions(map), do: Map.new(map, fn {k, q} -> {k, question(q)} end)

  defp question(%{"type" => "noul"} = q),
    do: %Noul{instructions: q["instructions"], criteria: q["criteria"]}

  defp question(%{"type" => "choice"} = q),
    do: %Choice{instructions: q["instructions"], criteria: q["criteria"]}

  defp question(%{"type" => "score"} = q),
    do: %Score{instructions: q["instructions"], criteria: q["criteria"]}

  defp question(q),
    do: %Raw{type: q["type"], instructions: q["instructions"] || "", criteria: q["criteria"]}

  # {"$image":…} / {"$text":…} markers -> ContentParts.
  defp state(%{"$image" => %{"mimeType" => m, "data" => d}}),
    do: %ContentPart{type: "image", mime_type: m, data: d}

  defp state(%{"$text" => t}), do: ContentPart.text(t)
  defp state(%{} = m), do: Map.new(m, fn {k, v} -> {k, state(v)} end)
  defp state(l) when is_list(l), do: Enum.map(l, &state/1)
  defp state(v), do: v

  # A fake backend: records every request, answers with `reply.(n, body)`.
  defp fake(reply) do
    {:ok, log} = Agent.start_link(fn -> [] end)

    transport = fn req ->
      n = Agent.get_and_update(log, fn l -> {length(l) + 1, l ++ [req]} end)
      {status, body} = reply.(n, req.body)
      {:ok, %{status: status, headers: %{}, body: body}}
    end

    {transport, fn -> Agent.get(log, & &1) end}
  end

  defp ok_reply(_n, _b), do: {200, @noul_reply}

  # ------------------------------------------------------------ presets

  test "presets.json: the backend table, unknown backend, custom requirements" do
    fx = fixture("presets.json")

    for {name, row} <- fx["backends"] do
      p = Classifier.backends()[name]
      assert p.base_url == row["baseUrl"], name
      assert p.model == row["model"], name
      assert p.api_key_env == row["apiKeyEnv"], name
      assert p.wire == row["wire"], name
      assert p.images_header == row["imagesHeader"], name

      case row["supportsImages"] do
        nil ->
          assert {:error, msg} = Classifier.create(backend: name)
          assert msg == fx["customError"]

        models ->
          for {model, want} <- models do
            {:ok, c} = Classifier.create(backend: name, model: model)
            assert c.supports_images == want, "#{name}/#{model}"
          end

          {:ok, c} = Classifier.create(backend: String.to_atom(name))
          assert c.base_url == row["baseUrl"]
          assert c.model == row["model"]
          assert c.api_key_env == row["apiKeyEnv"]
      end
    end

    assert Map.keys(Classifier.backends()) |> Enum.sort() ==
             fx["backends"] |> Map.keys() |> Enum.sort()

    assert {:error, msg} = Classifier.create(backend: "nope")
    assert msg == fx["unknownBackendError"]

    # custom needs BOTH; supplied, it works; non-wire styles never need them.
    assert {:error, _} = Classifier.create(backend: "custom", base_url: "http://x")
    assert {:error, _} = Classifier.create(backend: "custom", supports: %{images: true})

    assert {:ok, c} =
             Classifier.create(
               backend: "custom",
               base_url: "http://x",
               supports: %{"images" => false}
             )

    refute c.supports_images

    assert {:ok, _} =
             Classifier.create(
               backend: "custom",
               style: "custom",
               evaluate: fn _, _ -> {:ok, %Decision{}} end
             )

    # Explicit options still win over a preset; supports overrides the column.
    {:ok, c} = Classifier.create(backend: "vercel", model: "m", supports: %{images: true})
    assert c.model == "m" and c.supports_images
  end

  # ------------------------------------------------------------ images

  test "images.json: lifting, references, header, limits, unsupported" do
    fx = fixture("images.json")
    qs = questions(fx["questions"])
    System.put_env("TN_TEST_ACCOUNT", "acct123")
    on_exit(fn -> System.delete_env("TN_TEST_ACCOUNT") end)

    for case_ <- fx["cases"] do
      name = case_["name"]
      cf? = case_["backend"] == "cloudflare"

      {transport, log} =
        fake(fn _, _ ->
          if cf?,
            do: {200, ~s({"success":true,"result":#{@noul_reply}})},
            else: {200, @noul_reply}
        end)

      preset = Classifier.backends()[case_["backend"]]
      if case_["expect"]["url"], do: assert(case_["expect"]["url"] == preset.base_url, name)

      base =
        if cf?, do: "http://fake/accounts/${TN_TEST_ACCOUNT}/ai/run", else: "http://fake/v1"

      opts =
        [backend: case_["backend"], base_url: base, transport: transport, retries: 0]
        |> then(&if(case_["model"], do: &1 ++ [model: case_["model"]], else: &1))
        |> then(&if(case_["supports"], do: &1 ++ [supports: case_["supports"]], else: &1))

      {:ok, c} = Classifier.create(opts)
      result = Classifier.evaluate(c, state(case_["state"]), qs)
      expect = case_["expect"]

      cond do
        expect["unsupported"] ->
          assert {:error, %Unsupported{feature: "images"} = e} = result, name
          assert Exception.message(e) == expect["error"]
          assert to_string(e) == expect["error"]
          assert log.() == []

        expect["errorPrefix"] ->
          assert {:error, msg} = result, name
          assert String.starts_with?(msg, expect["errorPrefix"]), "#{name}: #{msg}"
          assert log.() == []

        true ->
          assert {:ok, _} = result, name
          [req] = log.()
          assert req.body == expect["body"], name

          assert req.headers["TypeSafe-Danger-Zone-Images"] == "true" == expect["imagesHeader"],
                 name

          if cf?,
            do: assert(req.url == "http://fake/accounts/acct123/ai/run"),
            else: assert(req.url == "http://fake/v1/systemone")
      end
    end
  end

  test "image edge cases: url-only, other part types, bad base64, oversize, JPEG walk" do
    {transport, log} = fake(&ok_reply/2)
    {:ok, c} = Classifier.create(base_url: "http://fake/v1", transport: transport)
    q = %{"q" => %Noul{instructions: "x"}}

    assert {:error, msg} =
             Classifier.evaluate(
               c,
               %{"p" => %ContentPart{type: "image", url: "http://i/x.png"}},
               q
             )

    assert msg =~ "needs inline data"

    assert {:error, msg} =
             Classifier.evaluate(
               c,
               [%ContentPart{type: "audio", mime_type: "audio/wav", data: "AAAA"}],
               q
             )

    assert msg == "classifier: state: audio parts are not supported"

    assert {:error, msg} = Classifier.evaluate(c, %ContentPart{type: "image"}, q)
    assert msg =~ "classifier: state: content part"

    img = fn data -> %{"p" => %ContentPart{type: "image", mime_type: "image/png", data: data}} end
    assert {:error, msg} = Classifier.evaluate(c, img.("!!!"), q)
    assert msg == ~s(classifier: image "p": data is not base64)

    big = Base.encode64(:binary.copy(<<0>>, 16 * 1024 * 1024 + 1))
    assert {:error, msg} = Classifier.evaluate(c, img.(big), q)
    assert msg =~ "over 16 MiB"

    # Three 12 MiB PNGs: each within limits, the request total is not.
    png = fn ->
      hdr = <<0x89, "PNG\r\n", 0x1A, "\n", 13::32, "IHDR", 300::32, 300::32>>
      Base.encode64(hdr <> :binary.copy(<<0>>, 12 * 1024 * 1024))
    end

    p = png.()
    part = %ContentPart{type: "image", mime_type: "image/png", data: p}
    assert {:error, msg} = Classifier.evaluate(c, [part, part, part], q)
    assert msg =~ ~s(classifier: image "image3": request images total)
    assert log.() == []

    # JPEG: fill bytes and a skipped APP segment before SOF2; truncated headers fail.
    jpeg = <<0xFF, 0xD8, 0xFF, 0xFF, 0xE0, 0, 4, 1, 2, 0xFF, 0xC2, 0, 11, 8, 400::16, 500::16, 0>>
    assert Classifier.Images.size("jpeg", jpeg) == {:ok, 500, 400}
    assert Classifier.Images.size("jpeg", <<0xFF, 0xD8, 0xFF, 0xC0, 0, 1>>) == :error
    assert Classifier.Images.size("jpeg", <<0xFF, 0xD8, 0x00, 0xC0, 0, 1>>) == :error
    assert Classifier.Images.size("jpeg", <<0xFF, 0xD8, 0xFF, 0xE0, 0, 9, 1>>) == :error
    assert Classifier.Images.size("png", "nope") == :error
    assert Classifier.Images.limits().max_side == 1568
  end

  test "non-wire styles receive an image state verbatim" do
    part = %ContentPart{type: "image", mime_type: "image/gif", data: "x"}

    {:ok, c} =
      Classifier.create(
        style: "custom",
        evaluate: fn s, _ ->
          send(self(), {:state, s})
          {:ok, %Decision{answers: %{"q" => %NoulAnswer{noul: 0.5}}}}
        end
      )

    assert {:ok, _} = Classifier.evaluate(c, %{"p" => part}, %{"q" => %Noul{instructions: "x"}})
    assert_received {:state, %{"p" => ^part}}
  end

  # ------------------------------------------------------------ cloudflare

  test "cloudflare-replies.json: envelope unwrap and errors" do
    fx = fixture("cloudflare-replies.json")
    qs = questions(fx["questions"])

    for case_ <- fx["cases"] do
      reply = Jason.encode!(case_["reply"])
      {transport, _} = fake(fn _, _ -> {200, reply} end)

      {:ok, c} =
        Classifier.create(backend: "cloudflare", base_url: "http://cf/run", transport: transport)

      result = Classifier.evaluate(c, "x", qs)

      case case_["expect"] do
        %{"error" => e} ->
          assert {:error, ^e} = result, case_["name"]
          refute e =~ "0123abcd"

        %{"model" => m, "noul" => p} ->
          assert {:ok, %Decision{model: ^m, answers: %{"q" => %NoulAnswer{noul: ^p}}}} = result
      end
    end

    # A transport handing back an already-decoded map, and an envelope with no result.
    {transport, _} =
      fake(fn _, _ -> {200, %{"success" => true, "result" => Jason.decode!(@noul_reply)}} end)

    {:ok, c} =
      Classifier.create(backend: "cloudflare", base_url: "http://cf/run", transport: transport)

    assert {:ok, %Decision{model: "jev-1.14.0"}} = Classifier.evaluate(c, "x", qs)

    {transport, _} = fake(fn _, _ -> {200, ~s({"success":true})} end)

    {:ok, c} =
      Classifier.create(backend: "cloudflare", base_url: "http://cf/run", transport: transport)

    assert {:error, "classifier: cloudflare: no result in the envelope"} =
             Classifier.evaluate(c, "x", qs)
  end

  test "a templated base url: dialled expanded, errors name it unexpanded" do
    System.put_env("TN_TEST_ACCOUNT", "secretacct")
    on_exit(fn -> System.delete_env("TN_TEST_ACCOUNT") end)

    {transport, log} = fake(fn _, _ -> {500, "boom"} end)

    {:ok, c} =
      Classifier.create(
        backend: "cloudflare",
        base_url: "http://cf/accounts/${TN_TEST_ACCOUNT}/ai/run",
        transport: transport,
        retries: 0
      )

    assert {:error, msg} = Classifier.evaluate(c, "x", %{"q" => %Noul{instructions: "y"}})
    refute msg =~ "secretacct"
    assert msg =~ "${TN_TEST_ACCOUNT}"
    assert [%{url: "http://cf/accounts/secretacct/ai/run"}] = log.()

    # A transport error that echoes the dialled url is redacted to the template.
    err = fn req -> {:error, %RuntimeError{message: "cannot reach " <> req.url}} end

    {:ok, c} =
      Classifier.create(
        backend: "cloudflare",
        base_url: "http://cf/${TN_TEST_ACCOUNT}",
        transport: err,
        retries: 0
      )

    assert {:error, msg} = Classifier.evaluate(c, "x", %{"q" => %Noul{instructions: "y"}})

    assert msg ==
             "classifier: POST http://cf/${TN_TEST_ACCOUNT}: cannot reach http://cf/${TN_TEST_ACCOUNT}"
  end

  # ------------------------------------------------------------ cache

  test "cache-keys.json: model and decision keys are the specified bytes" do
    for case_ <- fixture("cache-keys.json")["cases"] do
      qs = questions(case_["questions"])
      assert Classifier.model_cache_key(case_["endpoint"], case_["model"]) == case_["modelKey"]

      bytes =
        Classifier.Canonical.encode(
          Map.merge(
            %{
              "endpoint" => case_["endpoint"],
              "kind" => "decision",
              "model" => case_["served"],
              "questions" => Classifier.questions_to_wire(qs),
              "state" => case_["state"]
            },
            if(case_["assets"], do: %{"assets" => case_["assets"]}, else: %{})
          )
        )

      assert bytes == case_["decisionKeyBytes"], case_["name"]

      assert Classifier.decision_cache_key(
               case_["endpoint"],
               case_["served"],
               case_["state"],
               case_["assets"],
               qs
             ) == case_["decisionKey"],
             case_["name"]
    end
  end

  test "state builder: V1 + cache hit sends nothing and marks cached" do
    {transport, log} = fake(&ok_reply/2)
    parent = self()

    {:ok, c} =
      Classifier.create(
        base_url: "http://fake/v1",
        transport: transport,
        cache: MemoryCache.new(3_600_000, 100),
        on_metric: fn ev -> send(parent, {:metric, ev}) end
      )

    q = Judge.noul(:q, "Is the object red?")

    assert {:ok, %Decision{cached: false}} =
             c |> Classifier.state("a red mug") |> Classifier.evaluate([q])

    assert {:ok, %Decision{cached: true} = d} =
             c |> Classifier.state("a red mug") |> Classifier.evaluate([q])

    assert d.model == "jev-1.14.0"
    assert length(log.()) == 1

    assert_received {:metric, %{cached: false, questions: 1}}
    assert_received {:metric, %{cached: true, questions: 1, questions_hash: h}}
    assert byte_size(h) == 64

    assert {:ok, _} = c |> Classifier.state("a blue mug") |> Classifier.evaluate([q])
    assert length(log.()) == 2

    assert {:error, msg} = c |> Classifier.state("x") |> Classifier.evaluate([q, q])
    assert msg =~ "duplicate"
  end

  test "invalid replies are never cached; the builder fails closed, plain evaluate does not" do
    bad = ~s({"model":"m","answers":{"q":{"type":"noul","noul":1.5}}})
    {transport, log} = fake(fn _, _ -> {200, bad} end)
    parent = self()

    {:ok, c} =
      Classifier.create(
        base_url: "http://fake/v1",
        transport: transport,
        cache: MemoryCache.new(),
        on_metric: fn ev -> send(parent, {:metric, ev}) end
      )

    qs = %{"q" => %Noul{instructions: "x"}}
    assert {:ok, _} = Classifier.evaluate(c, "s", qs)
    assert {:ok, _} = Classifier.evaluate(c, "s", qs)
    assert length(log.()) == 2

    assert {:error, msg} = c |> Classifier.state("s") |> Classifier.evaluate(qs)
    assert String.starts_with?(msg, ~s(classifier: invalid decision: answer "q"))
    assert_received {:metric, %{status: "error", error: "classifier: invalid decision: " <> _}}
  end

  test "the cache follows the SERVED model; corrupt entries are misses" do
    {:ok, served} = Agent.start_link(fn -> "jev-1.13.0" end)

    {transport, log} =
      fake(fn _, _ ->
        {200,
         ~s({"model":"#{Agent.get(served, & &1)}","answers":{"q":{"type":"noul","noul":0.5}}})}
      end)

    cache = MemoryCache.new()
    {:ok, c} = Classifier.create(base_url: "http://fake/v1", transport: transport, cache: cache)
    ev = fn s -> c |> Classifier.state(s) |> Classifier.evaluate([Judge.noul(:q, "x")]) end

    {:ok, _} = ev.("s")
    mk = Classifier.model_cache_key("http://fake/v1", "jev-latest")
    assert MemoryCache.get(cache, mk) == "jev-1.13.0"

    Agent.update(served, fn _ -> "jev-1.14.0" end)
    {:ok, _} = ev.("other")
    assert {:ok, %Decision{cached: false, model: "jev-1.14.0"}} = ev.("s")
    assert length(log.()) == 3
    assert {:ok, %Decision{cached: true}} = ev.("s")

    # Corrupt the stored decision: a miss, not an error.
    dk =
      Classifier.decision_cache_key("http://fake/v1", "jev-1.14.0", "s", nil, %{
        "q" => %Noul{instructions: "x"}
      })

    MemoryCache.set(cache, dk, "{not json")
    assert {:ok, %Decision{cached: false}} = ev.("s")
  end

  test "a host cache map, and the cloudflare reply stored canonically" do
    {:ok, store} = Agent.start_link(fn -> %{} end)

    cache = %{
      get: &Agent.get(store, fn m -> m[&1] end),
      set: &Agent.update(store, fn m -> Map.put(m, &1, &2) end)
    }

    {transport, log} = fake(fn _, _ -> {200, ~s({"success":true,"result":#{@noul_reply}})} end)

    {:ok, c} =
      Classifier.create(
        backend: "cloudflare",
        base_url: "http://cf",
        transport: transport,
        cache: cache
      )

    q = %{"q" => %Noul{instructions: "x"}}
    assert {:ok, %Decision{cached: false}} = Classifier.evaluate(c, "s", q)
    assert {:ok, %Decision{cached: true}} = Classifier.evaluate(c, "s", q)
    assert length(log.()) == 1

    assert Agent.get(store, &Map.values/1)
           |> Enum.member?(Classifier.Canonical.encode(Jason.decode!(@noul_reply)))
  end

  test "MemoryCache: ttl expiry and oldest-inserted eviction" do
    {:ok, clock} = Agent.start_link(fn -> 0 end)
    c = MemoryCache.new(100, 2, now: fn -> Agent.get(clock, & &1) end)
    MemoryCache.set(c, "a", "1")
    MemoryCache.set(c, "b", "2")
    MemoryCache.set(c, "c", "3")
    assert MemoryCache.get(c, "a") == nil
    assert MemoryCache.get(c, "b") == "2"
    Agent.update(clock, fn _ -> 101 end)
    assert MemoryCache.get(c, "c") == nil
    assert MemoryCache.get(c, "zz") == nil
  end

  # ------------------------------------------------------------ batch

  test "states(...) |> evaluate: per-item results, in order, never one failure for all" do
    {transport, _} =
      fake(fn _, body ->
        if body =~ ~s("state":"bad"), do: {400, ~s({"error":"nope"})}, else: {200, @noul_reply}
      end)

    {:ok, c} = Classifier.create(base_url: "http://fake/v1", transport: transport)
    q = [Judge.noul(:q, "Is the object red?")]

    assert {:ok,
            [
              %BatchResult{decision: %Decision{model: "jev-1.14.0"}, error: nil},
              %BatchResult{decision: nil, error: e},
              %BatchResult{error: nil}
            ]} =
             c |> Classifier.states(["a", "bad", "c"]) |> Classifier.evaluate(q)

    assert e =~ "HTTP 400"
    assert {:ok, []} = c |> Classifier.states([]) |> Classifier.evaluate(q)
    assert {:error, _} = c |> Classifier.states(["a"]) |> Classifier.evaluate(q ++ q)
  end

  test "builder V1 applies to non-wire styles too" do
    {:ok, c} =
      Classifier.create(style: "custom", evaluate: fn _, _ -> {:ok, %Decision{answers: %{}}} end)

    assert {:error, ~s(classifier: invalid decision: no answer "q")} =
             c |> Classifier.state("s") |> Classifier.evaluate(%{"q" => %Noul{instructions: "x"}})
  end

  # ------------------------------------------------------------ V1 / T1 / C1

  test "validate-cases.json: V1 fail-closed, first failure names the key" do
    for case_ <- fixture("validate-cases.json")["cases"] do
      qs = questions(case_["questions"])
      {:ok, d} = Classifier.decode_decision(case_["response"])

      case Classifier.validate_decision(qs, d) do
        :ok ->
          assert case_["valid"], case_["name"]

        {:error, msg} ->
          refute case_["valid"], "#{case_["name"]}: #{msg}"
          assert String.starts_with?(msg, "classifier: invalid decision: ")
          assert msg =~ ~s("#{case_["key"]}"), "#{case_["name"]}: #{msg}"
      end
    end
  end

  test "V1 messages for the remaining branches" do
    ch = %{"t" => %Choice{instructions: "x", criteria: %{"a" => "A", "b" => "B"}}}
    sc = %{"s" => %Score{instructions: "x", criteria: ["lo", "hi"]}}

    v = fn qs, raw ->
      Classifier.validate_decision(qs, elem(Classifier.decode_decision(raw), 1))
    end

    assert {:error, m} =
             v.(ch, %{
               "answers" => %{
                 "t" => %{
                   "type" => "choice",
                   "choice" => "a",
                   "probabilities" => %{"a" => 1.5},
                   "confidence" => 0.5
                 }
               }
             })

    assert m =~ "probability 1.5 outside"

    assert {:error, m} =
             v.(sc, %{
               "answers" => %{
                 "s" => %{
                   "type" => "score",
                   "score" => 1,
                   "probabilities" => %{},
                   "confidence" => 2
                 }
               }
             })

    assert m =~ "confidence 2 outside"

    assert {:error, m} =
             v.(sc, %{
               "answers" => %{
                 "s" => %{
                   "type" => "score",
                   "score" => 1,
                   "probabilities" => %{"0" => 0.5, "1" => 0.1},
                   "confidence" => 0.5
                 }
               }
             })

    assert m =~ "sum to 0.6000"
    assert :ok = v.(%{"n" => %Noul{}}, %{"answers" => %{"n" => %{"type" => "noul"}}})
  end

  test "thresholds-cases.json: inclusive Thresholds next to the unchanged bands" do
    fx = fixture("thresholds-cases.json")
    d = fx["default"]

    assert Thresholds.default() == %Thresholds{
             yes: d["yes"],
             no: d["no"],
             min_confidence: d["minConfidence"]
           }

    for case_ <- fx["cases"] do
      {:ok, dec} = Classifier.decode_decision(%{"answers" => %{"a" => case_["answer"]}})
      a = dec.answers["a"]

      t =
        case case_["thresholds"] do
          nil -> Thresholds.default()
          m -> %Thresholds{yes: m["yes"], no: m["no"], min_confidence: m["minConfidence"]}
        end

      assert Atom.to_string(Thresholds.band(t, a)) == case_["band"], case_["name"]
      if case_["thresholds"] == nil, do: assert(Thresholds.band(a) == Thresholds.band(t, a))

      jb = Judge.answers(dec)["a"]

      bands_band =
        cond do
          jb.type == "noul" -> jb.band
          Judge.Answer.uncertain?(jb) -> :uncertain
          true -> :yes
        end

      assert Atom.to_string(bands_band) == case_["bandsBand"], case_["name"]
    end
  end

  test "chunk_questions: ASCII order, size >= 1" do
    qs = %{"c" => %Noul{}, "a" => %Noul{}, "b" => %Noul{}}
    assert {:ok, [%{"a" => _, "b" => _} = first, %{"c" => _}]} = Classifier.chunk_questions(qs, 2)
    assert map_size(first) == 2
    assert {:error, msg} = Classifier.chunk_questions(qs, 0)
    assert msg =~ "at least 1"
  end

  # ------------------------------------------------------------ raw / L1

  test "raw question on the wire, raw answer decoded, empty type refused" do
    {transport, log} =
      fake(fn _, _ ->
        {200, ~s({"model":"jev-1.14.0","answers":{"spot":{"type":"box","box":[1,2,3,4]}}})}
      end)

    {:ok, c} = Classifier.create(base_url: "http://fake/v1", transport: transport)

    assert {:ok, d} =
             c
             |> Classifier.state("s")
             |> Classifier.evaluate([Judge.raw(:spot, "box", "Where is the mug?")])

    assert [
             %{
               body:
                 ~s({"model":"jev-latest","questions":{"spot":{"instructions":"Where is the mug?","type":"box"}},"state":"s"})
             }
           ] =
             log.()

    assert %RawAnswer{type: "box", value: %{"box" => [1, 2, 3, 4]}} = d.answers["spot"]
    assert {:error, msg} = Decision.noul(d, "spot")
    assert msg =~ "is a box answer"

    {_, raw} = Judge.raw(:x, "box", "i", %{"k" => 1})

    assert Classifier.question_to_wire(raw) == %{
             "type" => "box",
             "instructions" => "i",
             "criteria" => %{"k" => 1}
           }

    assert {:error, msg} = Classifier.evaluate(c, "s", %{"x" => %Raw{}})
    assert msg =~ "a raw question needs a type"

    # A raw answer through ask: uncertain, never a crash.
    assert {:ok, %{"spot" => a}} = Judge.ask(c, "s", [Judge.raw(:spot, "box", "Where?")])
    assert Judge.Answer.uncertain?(a)
  end

  test "L1: the metric hash is the fixture's canonicalSha256" do
    fx = Path.join(@dir, "base.json") |> File.read!() |> Jason.decode!()
    parent = self()

    {:ok, c} =
      Classifier.create(
        style: "custom",
        model: fx["request"]["model"],
        evaluate: fn _, _ -> {:ok, %Decision{model: "m"}} end,
        on_metric: fn ev -> send(parent, {:metric, ev}) end
      )

    {:ok, _} =
      Classifier.evaluate(c, fx["request"]["state"], questions(fx["request"]["questions"]))

    sha = fx["canonicalSha256"]
    assert_received {:metric, %{questions: 3, questions_hash: ^sha, cached: false}}

    # Unencodable questions: count kept, hash empty.
    {:error, _} = Classifier.evaluate(c, "s", %{"bad" => :nope})
    assert_received {:metric, %{status: "error", questions: 1, questions_hash: ""}}
    # A non-map is still a crash, exactly as before this change.
    assert_raise FunctionClauseError, fn -> Classifier.evaluate(c, "s", :not_a_map) end
  end

  # ------------------------------------------------------------ real HTTP

  defmodule Echo do
    @moduledoc false
    import Plug.Conn
    def init(o), do: o

    def call(conn, _) do
      {:ok, _body, conn} = read_body(conn)
      h = get_req_header(conn, "typesafe-danger-zone-images")
      send(:classifier_backends_test, {:hit, conn.request_path, h})
      send_resp(conn, 200, ~s({"model":"m","answers":{"photo":{"type":"noul","noul":0.4}}}))
    end
  end

  test "a local HTTP endpoint receives the image header on the real Req path" do
    Process.register(self(), :classifier_backends_test)
    {:ok, pid} = Bandit.start_link(plug: Echo, port: 0, ip: {127, 0, 0, 1})
    {:ok, {_, port}} = ThousandIsland.listener_info(pid)
    on_exit(fn -> Process.exit(pid, :normal) end)

    data = fixture("images.json")["cases"] |> Enum.find(&(&1["name"] == "lift-under-key"))

    {:ok, c} =
      Classifier.create(
        backend: "custom",
        base_url: "http://127.0.0.1:#{port}/v1",
        supports: %{images: true},
        retries: 0
      )

    assert {:ok, _} =
             c
             |> Classifier.state(state(data["state"]))
             |> Classifier.evaluate([Judge.noul(:photo, "red?")])

    assert_received {:hit, "/v1/systemone", ["true"]}
  end

  defmodule AuthCapture do
    @moduledoc false
    import Plug.Conn
    def init(o), do: o

    def call(conn, %{owner: owner, reply: reply}) do
      {:ok, _body, conn} = read_body(conn)
      send(owner, {:auth, get_req_header(conn, "authorization")})
      send_resp(conn, 200, reply)
    end
  end

  test "key-routing.json: a preset key goes only to its own host (#155)" do
    fx = fixture("key-routing.json")
    qs = questions(fx["questions"])

    for hc <- fx["hostCases"] do
      assert Classifier.key_env(hc["backend"], hc["baseUrl"]) == hc["expectKeyEnv"],
             "#{hc["backend"]} #{hc["baseUrl"]}"
    end

    for cc <- fx["captureCases"] do
      saved = for {k, _} <- cc["env"], do: {k, System.get_env(k)}
      for {k, v} <- cc["env"], do: System.put_env(k, v)

      try do
        reply =
          if cc["backend"] == "cloudflare",
            do: ~s({"success":true,"result":#{@noul_reply}}),
            else: @noul_reply

        {:ok, pid} =
          Bandit.start_link(
            plug: {AuthCapture, %{owner: self(), reply: reply}},
            port: 0,
            ip: {127, 0, 0, 1}
          )

        {:ok, {_, port}} = ThousandIsland.listener_info(pid)

        opts =
          [backend: cc["backend"], base_url: "http://127.0.0.1:#{port}/v1", retries: 0]
          |> then(
            &if(cc["backend"] == "custom", do: [{:supports, %{images: false}} | &1], else: &1)
          )
          |> then(&if(cc["apiKeyEnv"], do: [{:api_key_env, cc["apiKeyEnv"]} | &1], else: &1))
          |> then(&if(cc["headers"], do: [{:headers, cc["headers"]} | &1], else: &1))

        {:ok, c} = Classifier.create(opts)
        assert {:ok, _} = Classifier.evaluate(c, "x", qs), cc["name"]
        assert_received {:auth, got}

        want = if cc["expectAuthorization"], do: [cc["expectAuthorization"]], else: []
        assert got == want, cc["name"]
        refute_received {:auth, _}
        Process.exit(pid, :normal)
      after
        for {k, v} <- saved, do: if(v, do: System.put_env(k, v), else: System.delete_env(k))
      end
    end
  end
end
