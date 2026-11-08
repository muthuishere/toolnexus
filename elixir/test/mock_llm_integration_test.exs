defmodule Toolnexus.MockLlmIntegrationTest do
  @moduledoc """
  Integration suite against the shared mock model server (`tools/mock-llm/mock_llm.py`):
  real HTTP, real wires, no key, no cost. Starts the mock as a subprocess on a free
  port unless `TN_MOCK_LLM_URL` points at a running one; skips when `python3` is
  missing.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Client, Classifier, ContentPart, Media, Tool, ToolResult}
  alias Toolnexus.Classifier.{Choice, ChoiceAnswer, Noul, NoulAnswer, Score, ScoreAnswer}

  @mock Path.expand("../../tools/mock-llm/mock_llm.py", __DIR__)
  @fixture Path.expand("../../examples/media/fixture.png", __DIR__)
  @fixture_sha (@fixture <> ".sha256") |> File.read!() |> String.trim()
  @reply "This is a reply from the toolnexus mock model."

  @python System.find_executable("python3")
  if @python == nil and System.get_env("TN_MOCK_LLM_URL") in [nil, ""] do
    @moduletag skip: "python3 not found: mock LLM integration suite skipped"
  end

  setup_all do
    System.put_env("TN_MOCK_LLM_TEST_KEY", "mock-key")
    System.put_env("MOCK_LLM_KEY", "mock-key")

    case System.get_env("TN_MOCK_LLM_URL") do
      url when url not in [nil, ""] ->
        {:ok, base: String.trim_trailing(url, "/")}

      _ ->
        port =
          Port.open({:spawn_executable, @python}, [
            :binary,
            :exit_status,
            {:line, 4096},
            args: [@mock, "--port", "0", "--quiet"]
          ])

        base =
          receive do
            {^port, {:data, {:eol, "toolnexus mock model at " <> url}}} -> String.trim(url)
          after
            10_000 -> raise "mock LLM did not start"
          end

        {:os_pid, pid} = Port.info(port, :os_pid)

        on_exit(fn ->
          System.cmd("kill", [to_string(pid)], stderr_to_stdout: true)
        end)

        {:ok, base: base}
    end
  end

  defp client(base, style, extra \\ []) do
    Client.create(
      [base_url: base, style: style, model: "mock-model", api_key: "mock-key", retry_base_ms: 1] ++
        extra
    )
  end

  defp add_tool(me) do
    %Tool{
      name: "add",
      description: "add two integers",
      input_schema: %{
        "type" => "object",
        "properties" => %{"a" => %{"type" => "integer"}, "b" => %{"type" => "integer"}},
        "required" => ["a", "b"]
      },
      source: "native",
      execute: fn args, _ctx ->
        send(me, {:add_called, args})
        ToolResult.ok(to_string(args["a"] + args["b"]))
      end
    }
  end

  defp mock(base, method, path, body \\ nil) do
    opts = [method: method, url: base <> path, retry: false]
    opts = if body, do: Keyword.put(opts, :json, body), else: opts
    Req.request!(opts).body
  end

  for style <- ["openai", "anthropic"] do
    test "#{style}: native tool round trip executes with the mock's args", %{base: base} do
      result = Client.run(client(base, unquote(style)), "add 1 and 1", [add_tool(self())])

      assert_received {:add_called, %{"a" => 1, "b" => 1}}
      assert result.status == "done"
      assert result.text == "Tool result: 2"
    end

    test "#{style}: streaming text deltas concatenate to the mock reply", %{base: base} do
      events = client(base, unquote(style)) |> Client.stream("hi", []) |> Enum.to_list()
      text = events |> Enum.filter(&(&1.type == "text")) |> Enum.map_join(& &1.delta)

      assert text == @reply
      assert %{type: "done", result: %{text: @reply}} = List.last(events)
    end
  end

  test "attachments: the image bytes arrive intact", %{base: base} do
    prompt = [ContentPart.text("what is this?"), ContentPart.image!(@fixture)]
    result = Client.run(client(base, "openai"), prompt, [])

    assert result.text =~ "image/png 82 bytes sha256=" <> String.slice(@fixture_sha, 0, 12)
  end

  test "resilience: one queued 429 is retried", %{base: base} do
    mock(base, :post, "/_mock/reset")

    mock(base, :post, "/_mock/faults", %{
      "path" => "/chat/completions",
      "status" => 429,
      "times" => 1,
      "retryAfter" => "0"
    })

    result = Client.run(client(base, "openai"), "hi", [])
    assert result.status == "done"
    assert result.text == @reply

    counts = mock(base, :get, "/_mock/requests")
    counts = if is_binary(counts), do: Jason.decode!(counts), else: counts

    calls = counts["/chat/completions"]

    assert calls == 2, inspect(counts)
  end

  @questions %{
    "safe" => %Noul{instructions: "Is this message safe?"},
    "team" => %Choice{
      instructions: "Which team owns this?",
      criteria: %{"billing" => "money", "infra" => "servers", "support" => "people"}
    },
    "urgency" => %Score{instructions: "How urgent?", criteria: ["low", "medium", "high"]}
  }

  defp assert_decision(c, state) do
    assert {:ok, d} = Classifier.evaluate(c, state, @questions)
    assert %NoulAnswer{noul: noul} = d.answers["safe"]
    assert noul == 0.95

    assert %ChoiceAnswer{choice: ch, probabilities: p} = d.answers["team"]
    assert ch in ["billing", "infra", "support"]
    assert Map.keys(p) |> Enum.sort() == ["billing", "infra", "support"]
    assert_in_delta Enum.sum(Map.values(p)), 1.0, 1.0e-9

    assert %ScoreAnswer{score: s} = d.answers["urgency"]
    assert s >= 0 and s <= 2
    {ch, s}
  end

  test "classifier: custom backend on the System One wire is deterministic", %{base: base} do
    {:ok, c} =
      Classifier.create(
        backend: "custom",
        base_url: base,
        model: "mock-jev",
        supports: %{"images" => false},
        api_key_env: "TN_MOCK_LLM_TEST_KEY",
        retries: 0
      )

    first = assert_decision(c, "my invoice is wrong")
    assert assert_decision(c, "my invoice is wrong") == first
  end

  test "classifier: cloudflare backend on the Cloudflare wire is deterministic", %{base: base} do
    origin = URI.parse(base) |> Map.merge(%{path: nil}) |> URI.to_string()

    {:ok, c} =
      Classifier.create(
        backend: "cloudflare",
        base_url: origin <> "/v1/accounts/acc/ai/run",
        api_key_env: "TN_MOCK_LLM_TEST_KEY",
        retries: 0
      )

    first = assert_decision(c, "my invoice is wrong")
    assert assert_decision(c, "my invoice is wrong") == first
  end

  test "media: every verb against the mock style, plus an async failure", %{base: base} do
    style_json = mock(base, :get, "/_mock/media-style.json", nil)
    style_json = if is_binary(style_json), do: style_json, else: Jason.encode!(style_json)

    path =
      Path.join(System.tmp_dir!(), "tn-mock-style-#{System.unique_integer([:positive])}.json")

    File.write!(path, style_json)
    on_exit(fn -> File.rm(path) end)

    assert {:ok, style} = Media.load_style(path)
    media = Media.new(style)

    check = fn {:ok, %Media.Result{files: [f]} = r}, suffix, mime ->
      assert String.ends_with?(f.url, suffix), f.url
      assert f.mime_type == mime
      assert r.status == "completed"
    end

    check.(Media.image(media, prompt: "a cat"), "/files/image.png", "image/png")
    check.(Media.video(media, prompt: "a cat running"), "/files/video.mp4", "video/mp4")
    check.(Media.speech(media, text: "hello", voice: "v"), "/files/speech.wav", "audio/wav")

    check.(
      Media.talking(media, image: base <> "/files/image.png", audio: base <> "/files/speech.wav"),
      "talking.mp4",
      "video/mp4"
    )

    audio = base <> "/files/speech.wav"
    assert {:ok, %Media.Result{text: t}} = Media.transcribe(media, audio: audio)
    assert t == "Mock transcript of #{audio}."

    assert {:ok, %Media.Result{text: v}} =
             Media.clone_voice(media, name: "muthu", samples: [audio])

    hash = :crypto.hash(:sha256, "muthu") |> Base.encode16(case: :lower) |> binary_part(0, 8)
    assert v == "voice-" <> hash

    assert {:error, msg} = Media.image(media, prompt: "a cat [fail]")
    assert msg =~ "mock: job asked to fail"
  end
end
