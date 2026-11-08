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

  test "classifier: openai backend on the Decisions wire is deterministic", %{base: base} do
    {:ok, c} =
      Classifier.create(
        backend: "openai",
        base_url: base,
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
    media = Media.create!(style)

    check = fn {:ok, %Media.Result{files: [f]} = r}, suffix, mime ->
      assert String.ends_with?(f.url, suffix), f.url
      assert f.mime_type == mime
      assert r.status == "completed"
    end

    check.(Media.image(media, prompt: "a cat"), "/files/image.png", "image/png")
    check.(Media.video(media, prompt: "a cat running"), "/files/video.mp4", "video/mp4")
    check.(Media.speech(media, text: "hello", voice: "v"), "/files/speech.wav", "audio/wav")

    check.(
      Media.talk(media, image: base <> "/files/image.png", audio: base <> "/files/speech.wav"),
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

    assert {:error, %Media.Error{message: msg}} = Media.image(media, prompt: "a cat [fail]")
    assert msg =~ "mock: job asked to fail"
  end

  # ------------------------------------------------------------ round 2: the mock kit
  # Kit scripts (mock_mcp.py, fake_cli.py, fake_acp_agent.py) come from TN_MOCK_KIT_DIR
  # when set (mutation checks point it at a scratch copy), else tools/mock-llm/.

  @kit_default Path.expand("../../tools/mock-llm", __DIR__)
  @skills Path.expand("../../examples/skills", __DIR__)

  defp kit(file) do
    dir =
      case System.get_env("TN_MOCK_KIT_DIR") do
        d when d not in [nil, ""] -> d
        _ -> @kit_default
      end

    Path.join(dir, file)
  end

  defp origin(base), do: URI.parse(base) |> Map.put(:path, nil) |> URI.to_string()

  # Start `python3 script args`, return the URL printed after `prefix` on the first line.
  defp spawn_url(script, args, prefix) do
    port =
      Port.open({:spawn_executable, System.find_executable("python3")}, [
        :binary,
        :exit_status,
        {:line, 4096},
        args: [script | args]
      ])

    url =
      receive do
        {^port, {:data, {:eol, line}}} ->
          [_, url] = String.split(line, prefix, parts: 2)
          String.trim(url)
      after
        10_000 -> raise "#{script} did not start"
      end

    {:os_pid, pid} = Port.info(port, :os_pid)
    on_exit(fn -> System.cmd("kill", [to_string(pid)], stderr_to_stdout: true) end)
    url
  end

  defp native_add(me) do
    Toolnexus.define_tool(
      name: "add",
      description: "Add two numbers.",
      input_schema: %{
        "type" => "object",
        "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
        "required" => ["a", "b"]
      },
      execute: fn %{"a" => a, "b" => b} ->
        send(me, {:native_add, a, b})
        "#{a + b}"
      end
    )
  end

  defp mixed_toolkit(base) do
    http_url = spawn_url(kit("mock_mcp.py"), ["--http", "--port", "0"], "toolnexus mock mcp at ")

    legacy_url =
      spawn_url(
        kit("mock_mcp.py"),
        ["--http", "--legacy", "--port", "0"],
        "toolnexus mock mcp at "
      )

    py = System.find_executable("python3")

    mcp = %{
      "mcpServers" => %{
        "stdio" => %{"type" => "local", "command" => [py, kit("mock_mcp.py")]},
        "web" => %{"type" => "remote", "url" => http_url},
        "legacy" => %{"type" => "remote", "url" => legacy_url}
      }
    }

    http_add =
      Toolnexus.Http.tool(
        name: "http_add",
        description: "Add over HTTP.",
        method: "POST",
        url: base <> "/tools/add",
        input_schema: %{
          "type" => "object",
          "properties" => %{"a" => %{"type" => "integer"}, "b" => %{"type" => "integer"}},
          "required" => ["a", "b"]
        }
      )

    tk =
      Toolnexus.create_toolkit!(
        mcp_config: mcp,
        skills_dir: @skills,
        agents: [
          Toolnexus.A2a.agent(
            card: origin(base) <> "/.well-known/agent-card.json",
            poll_every: 20
          )
        ],
        extra_tools: [http_add, native_add(self())],
        builtins: false
      )

    on_exit(fn -> Toolnexus.Toolkit.close(tk) end)
    tk
  end

  defp out(%ToolResult{output: o}), do: o
  defp out(o) when is_binary(o), do: o

  test "kit h: a toolkit mixing MCP (stdio, http 2026, http legacy), HTTP, A2A, native, skills",
       %{base: base} do
    tk = mixed_toolkit(base)
    names = tk |> Toolnexus.Toolkit.tools() |> Enum.map(& &1.name)

    for n <- ~w(stdio_add stdio_echo web_add web_echo legacy_add legacy_echo
                http_add add mockagent_summarize skill),
        do: assert(n in names, "#{n} missing from #{inspect(names)}")

    assert Toolnexus.Toolkit.mcp_status(tk) == %{
             "stdio" => "connected",
             "web" => "connected",
             "legacy" => "connected"
           }

    ex = &Toolnexus.Toolkit.execute(tk, &1, &2)

    for s <- ~w(stdio web legacy) do
      r = ex.("#{s}_add", %{"a" => 2, "b" => 3})
      refute r.is_error, inspect(r)
      assert out(r) == "5", s
    end

    assert out(ex.("web_echo", %{"text" => "hi"})) =~ "hi"

    a2a = ex.("mockagent_summarize", %{"task" => "x"})
    assert out(a2a) == "A2A mock done: x", inspect(a2a)

    h = ex.("http_add", %{"a" => 2, "b" => 3})
    assert Jason.decode!(out(h)) == %{"sum" => 5}, inspect(h)

    assert out(ex.("add", %{"a" => 2, "b" => 3})) == "5"
    assert_received {:native_add, 2, 3}

    sk = ex.("skill", %{"name" => "hello-world"})
    refute sk.is_error
    assert out(sk) =~ "Report the greeting back."
  end

  test "kit i: one mock-LLM run over the mixed toolkit", %{base: base} do
    tk = mixed_toolkit(base)
    [first | _] = Toolnexus.Toolkit.tools(tk)
    result = Client.run(client(base, "openai"), "use a tool", tk)

    assert result.status == "done"
    assert [%{name: name} | _] = result.tool_calls
    assert name == first.name
    assert "Tool result: " <> rest = result.text
    assert rest != ""
  end

  # The one-shot CLI pattern of site/recipes/in-process-client/elixir/one-shot-cli.
  defp render(%{messages: messages} = req) do
    tools =
      for t <- req.tools || [], f = t["function"], do: "- #{f["name"]}: #{f["description"]}"

    {convo, _} =
      Enum.flat_map_reduce(messages, %{}, fn m, called ->
        cond do
          m["role"] == "tool" ->
            {["[tool #{called[m["tool_call_id"]] || m["tool_call_id"]}] #{m["content"]}"], called}

          m["tool_calls"] ->
            called =
              Enum.reduce(m["tool_calls"], called, &Map.put(&2, &1["id"], &1["function"]["name"]))

            {["[assistant called]"], called}

          true ->
            c = if is_binary(m["content"]), do: m["content"], else: Jason.encode!(m["content"])
            {["[#{m["role"]}] #{c}"], called}
        end
      end)

    Enum.join(["Tools:"] ++ tools ++ ["", "Conversation:"] ++ convo, "\n")
  end

  defp parse(out) do
    case Jason.decode(String.trim(out)) do
      {:ok, %{"tool_calls" => calls}} ->
        %{tool_calls: Enum.map(calls, &%{name: &1["name"], arguments: &1["arguments"] || %{}})}

      {:ok, %{"content" => c}} ->
        %{content: c}
    end
  end

  test "kit j: in-process one-shot CLI client drives fake_cli.py" do
    cli = kit("fake_cli.py")
    py = System.find_executable("python3")

    client =
      Client.create_in_process(
        model: "fake-cli",
        generate: fn req ->
          path =
            Path.join(System.tmp_dir!(), "tn-oneshot-#{System.unique_integer([:positive])}.txt")

          File.write!(path, render(req))

          try do
            {out, 0} = System.cmd("sh", ["-c", ~s(exec "$1" "$2" < "$3"), "sh", py, cli, path])
            parse(out)
          after
            File.rm(path)
          end
        end
      )

    tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: [native_add(self())])
    r = Client.run(client, "Add 19 and 23.", tk)

    assert r.text == "The sum is 42."
    assert_received {:native_add, 19, 23}
  end

  defp acp_run(prompt, decide) do
    tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: [native_add(self())])
    server = Toolnexus.Toolkit.as_mcp_server(tk)

    {:ok, agent} =
      Toolnexus.Acp.connect([System.find_executable("python3"), kit("fake_acp_agent.py")],
        mcp_servers: [
          %{
            "type" => "http",
            "name" => "toolnexus",
            "url" => server.url,
            "headers" => [%{"name" => "Authorization", "value" => "Bearer " <> server.token}]
          }
        ],
        on_permission: decide,
        timeout: 30_000
      )

    try do
      client =
        Client.create_in_process(
          model: "fake-acp",
          generate_stream: Toolnexus.Acp.generate_stream(agent)
        )

      Client.stream(client, prompt, Toolnexus.create_toolkit!(builtins: false)) |> Enum.to_list()
    after
      Toolnexus.Acp.close(agent)
      server.close.()
    end
  end

  defp allow(params),
    do: Enum.find_value(params["options"], fn o -> o["kind"] =~ "allow" && o["optionId"] end)

  test "kit k1: ACP agent with the toolkit served, permission approved" do
    events = acp_run("Use the add tool to add 17 and 25.", &allow/1)
    text = events |> Enum.filter(&(&1.type == "text")) |> Enum.map_join(& &1.delta)

    assert text == "The sum is 42."
    assert %{type: "done", result: %{text: "The sum is 42."}} = List.last(events)
    assert Enum.any?(events, &(&1.type == "tool_call" and &1[:via] == "acp"))
    assert_received {:native_add, 17, 25}
  end

  test "kit k2: ACP permission rejected" do
    events = acp_run("Use the add tool to add 17 and 25.", fn _ -> nil end)
    assert %{type: "done", result: %{text: "Permission denied."}} = List.last(events)
    refute_received {:native_add, _, _}
  end

  @tag skip: "elixir ACP client has no cancel (issue #169)"
  test "kit k3: ACP cancel of a [wait] prompt" do
    :ok
  end
end
