defmodule Toolnexus.MediaConformanceTest do
  @moduledoc """
  SPEC §7G.11 conformance: replays EVERY case of `conformance/media/*.json` (read from the
  repo, never copied) hermetically — fake HTTP server, fake CLI via `/bin/sh`, test-only
  custom clients. No network, no real keys.
  """
  use ExUnit.Case, async: false

  import Toolnexus.MediaHarness

  alias Toolnexus.{Context, Media}
  alias Toolnexus.Media.{Error, MediaFile, Result}

  @cases load("cases.json")["cases"]
  @clients load("clients.json")["cases"]
  @cli load("cli.json")["cases"]
  @routing load("routing.json")
  @schemas load("schemas.json")
  @fake Path.join(root(), "fake-cli/fake-cli.sh")

  test "case counts are exact" do
    assert length(@cases) == 49
    assert length(@clients) == 76
    assert length(@cli) == 24
    assert length(@routing["cases"]) == 19
    assert length(@routing["schemaCases"]) == 1
  end

  test "verbs.json and a2e.json are embedded verbatim" do
    assert Media.verbs_json() == File.read!(Path.join(root(), "verbs.json"))
    assert File.read!(Path.expand("../priv/media/verbs.json", __DIR__)) == Media.verbs_json()

    assert Media.a2e_json() ==
             File.read!(Path.expand("../../examples/media-styles/a2e.json", __DIR__))

    embedded =
      Enum.map(Media.verbs(), fn v ->
        %{
          "verb" => v.verb,
          "tool" => v.tool,
          "description" => v.description,
          "args" => Enum.map(v.args, fn a -> Map.new(a, fn {k, x} -> {to_string(k), x} end) end)
        }
      end)

    assert embedded == load("verbs.json")["verbs"]
  end

  # ---- cases.json: the style engine ----

  for c <- @cases do
    @c c
    test "cases.json: #{c["name"]}" do
      c = @c
      restore = with_env(c["env"], c["unsetEnv"])
      on_exit(restore)

      srv = serve(fn url -> subst(c["script"], %{"{server}" => url}) end)
      style = Map.update!(c["style"], "baseUrl", &String.replace(&1, "{server}", srv.url))

      case Media.style_client(style, c["config"] || %{}) do
        {:error, %Error{message: msg}} ->
          assert c["styleError"] == true
          assert msg == c["error"]
          assert requests(srv) == []
          stop(srv)

        {:ok, client} ->
          refute c["styleError"]
          media = Media.create!(client)
          out = Media.generate(media, c["verb"], c["args"], progress: progress_fun())
          reqs = requests(srv)
          stop(srv)

          case c do
            %{"error" => msg} ->
              assert {:error, %Error{message: ^msg} = e} = out
              if s = c["status"], do: assert(e.status == String.to_atom(s))

            %{"result" => expected} ->
              assert {:ok, r} = out
              assert Enum.map(r.files, &file_json/1) == expected["files"]
              assert r.status == expected["status"]
              assert r.job_id == expected["jobId"] and r.text == expected["text"]
              assert r.usage == expected["usage"]
          end

          if exp = c["expectRequests"] do
            assert length(reqs) == length(exp)

            for {e, r} <- Enum.zip(exp, reqs) do
              assert r.method == e["method"] and r.path == e["path"]

              if e["body"] do
                assert Jason.decode!(r.body) == e["body"]
                assert r.headers["content-type"] == "application/json"
              else
                assert r.body == "" and r.headers["content-type"] == nil
              end
            end
          end

          for r <- reqs, {h, v} <- c["expectHeaders"] || %{} do
            assert r.headers[String.downcase(h)] == v
          end

          assert_header_patterns(reqs, c["expectHeaderPatterns"])

          progress = collect_progress()

          if exp = c["expectProgress"] do
            assert Enum.map(progress, & &1.status) == exp
            assert Enum.all?(progress, &(&1.verb == c["verb"] and &1.client == client.name))
          end

          if t = c["tool"] do
            srv = serve(fn url -> subst(c["script"], %{"{server}" => url}) end)
            style = Map.update!(c["style"], "baseUrl", &String.replace(&1, "{server}", srv.url))
            {:ok, client} = Media.style_client(style, c["config"] || %{})
            tool = Enum.find(Media.tools(Media.create!(client)), &(&1.name == t["name"]))
            result = tool.execute.(c["args"], %Context{})
            stop(srv)
            assert_tool(result, t, nil)
          end
      end
    end
  end

  test "schemas.json — exact tool list, order and inputSchema" do
    tools = Media.tools(Media.create!(Media.style_client!(@schemas["style"])))
    assert schema_view(tools) == @schemas["tools"]
    assert_property_order(tools)
  end

  defp schema_view(tools) do
    Enum.map(tools, fn t ->
      %{
        "name" => t.name,
        "description" => t.description,
        "source" => t.source,
        "inputSchema" => t.input_schema
      }
    end)
  end

  defp assert_property_order(tools) do
    for t <- tools do
      verb = Enum.find(Media.verbs(), &(&1.tool == t.name))
      order = for a <- verb.args, Map.has_key?(t.input_schema["properties"], a.name), do: a.name
      assert t.input_schema["required"] == Enum.filter(order, &(&1 in t.input_schema["required"]))
    end
  end

  # ---- clients.json: built-in API clients ----

  defp build(name, config) do
    case name do
      "a2e" -> Media.a2e(config)
      "elevenlabs" -> Media.elevenlabs(config)
      "openai" -> Media.openai(config)
      "openrouter" -> Media.openrouter(config)
      "fal" -> Media.fal(config)
      "grok" -> Media.grok(config)
      "codex" -> Media.codex(config)
    end
  end

  defp client_face(c, face) do
    dir = tmp_dir("dir")
    inputs = tmp_dir("inputs")

    for {n, b64} <- c["inputFiles"] || %{},
        do: File.write!(Path.join(inputs, n), Base.decode64!(b64))

    subs0 = %{"{dir}" => dir, "{inputs}" => inputs}
    srv = serve(fn url -> subst(c["script"], Map.put(subs0, "{server}", url)) end)
    subs = Map.put(subs0, "{server}", srv.url)
    media = Media.create!(build(c["client"], subst(c["config"], subs)))
    args = subst(c["args"], subs)

    out =
      case face do
        :function ->
          Media.generate(media, c["verb"], args, ctx_opts(c))

        :tool ->
          tool = Enum.find(Media.tools(media), &(&1.name == verb_tool(c["verb"])))
          tool.execute.(args, %Context{})
      end

    reqs = requests(srv)
    stop(srv)
    {out, reqs, subs, dir}
  end

  # D12: ctxTimeoutMs sets an absolute caller deadline on the function face.
  defp ctx_opts(c) do
    case c["ctxTimeoutMs"] do
      nil -> [progress: progress_fun()]
      ms -> [progress: progress_fun(), deadline: System.monotonic_time(:millisecond) + ms]
    end
  end

  defp verb_tool(verb), do: Enum.find(Media.verbs(), &(&1.verb == verb)).tool

  for c <- @clients do
    @c c
    test "clients.json: #{c["name"]}" do
      c = @c
      restore = with_env(c["env"], c["unsetEnv"])
      on_exit(restore)

      t0 = System.monotonic_time(:millisecond)
      {out, reqs, subs, dir} = client_face(c, :function)
      elapsed = System.monotonic_time(:millisecond) - t0
      c = subst(Map.drop(c, ["script"]), subs)
      if max = c["maxElapsedMs"], do: assert(elapsed < max)

      cond do
        msg = c["error"] ->
          assert {:error, %Error{message: ^msg} = e} = out
          assert e.status == String.to_atom(c["status"])

        prefix = c["errorPrefix"] ->
          assert {:error, %Error{message: msg} = e} = out
          assert String.starts_with?(msg, prefix), msg
          assert e.status == String.to_atom(c["status"])

        exp = c["result"] ->
          assert {:ok, %Result{} = r} = out
          assert_result(r, exp, c["client"], c["verb"], dir)
      end

      if exp = c["expectRequests"], do: assert_requests(reqs, exp)

      for r <- reqs, {h, v} <- c["expectHeaders"] || %{} do
        assert r.headers[String.downcase(h)] == v
      end

      assert_header_patterns(reqs, c["expectHeaderPatterns"])
      assert_usage_flags(c, out)

      progress = collect_progress()
      if exp = c["expectProgress"], do: assert(Enum.map(progress, & &1.status) == exp)

      if (t = @c["tool"]) && !@c["ctxTimeoutMs"] do
        {result, _reqs, subs, dir} = client_face(@c, :tool)
        assert_tool(result, subst(t, subs), dir)
        assert_tool_flags(c, result)
      end

      if Map.has_key?(c, "runFilePath"), do: assert_run_file_path(c, out)
    end
  end

  # ---- cli.json: CLI clients over the fake CLI ----

  defp cli_face(c, face) do
    dir = tmp_dir("dir")
    out = tmp_dir("out")
    fixture = Path.join(tmp_dir("fx"), "fixture.jsonl")
    File.write!(fixture, Enum.map_join(c["fixture"], "", &(&1 <> "\n")))
    subs = %{"{dir}" => dir, "{out}" => out}
    restore = with_env(subst(c["env"], subs), nil)
    command = c["command"] || ["/bin/sh", @fake, fixture, out]
    config = c["config"] |> subst(subs) |> Map.put("command", command)
    media = Media.create!(build(c["client"], config))
    t0 = System.monotonic_time(:millisecond)

    result =
      try do
        case face do
          :function ->
            Media.generate(media, c["verb"], c["args"], ctx_opts(c))

          :tool ->
            tool = Enum.find(Media.tools(media), &(&1.name == verb_tool(c["verb"])))
            tool.execute.(c["args"], %Context{})
        end
      after
        restore.()
      end

    elapsed = System.monotonic_time(:millisecond) - t0
    {result, subs, dir, out, elapsed}
  end

  defp assert_cli_side_effects(c, out) do
    argv_file = Path.join(out, "argv.txt")

    if c["expectNoSpawn"], do: refute(File.exists?(argv_file))

    if exp = c["expectArgv"] do
      lines = argv_file |> File.read!() |> String.split("\n", trim: false) |> Enum.drop(-1)
      cwd = out |> Path.join("cwd.txt") |> File.read!() |> String.trim_trailing("\n")
      assert length(lines) == length(exp)

      for {e, l} <- Enum.zip(exp, lines) do
        if e == "{cwd}" do
          # D17: byte for byte, no symlink resolution on either side.
          assert l == cwd
        else
          assert l == e
        end
      end
    end

    if env = c["expectEnv"] do
      assert File.read!(Path.join(out, "env-GROK_CONFIG.txt")) == env["GROK_CONFIG"]
    end
  end

  for c <- @cli do
    @c c
    test "cli.json: #{c["name"]}" do
      c = @c
      {res, subs, dir, out, elapsed} = cli_face(c, :function)
      e = subst(Map.drop(c, ["fixture"]), subs)

      cond do
        msg = e["error"] ->
          assert {:error, %Error{message: ^msg} = err} = res
          assert err.status == String.to_atom(e["status"])

        prefix = e["errorPrefix"] ->
          assert {:error, %Error{message: msg} = err} = res
          assert String.starts_with?(msg, prefix), msg
          assert err.status == String.to_atom(e["status"])

        exp = e["result"] ->
          assert {:ok, %Result{} = r} = res
          assert_result(r, exp, c["client"], c["verb"], dir)
      end

      assert_cli_side_effects(e, out)
      assert_usage_flags(e, res)
      if Map.has_key?(e, "runFilePath"), do: assert_run_file_path(e, res)
      for p <- e["expectExists"] || [], do: assert(File.exists?(p), p)
      for p <- e["expectMissing"] || [], do: refute(File.exists?(p), p)
      if max = e["maxElapsedMs"], do: assert(elapsed < max)

      progress = collect_progress()
      if exp = e["expectProgress"], do: assert(Enum.map(progress, & &1.status) == exp)

      if (t = c["tool"]) && !c["ctxTimeoutMs"] do
        {res, subs, dir, out, _} = cli_face(c, :tool)
        assert_tool(res, subst(t, subs), dir)
        assert_tool_flags(c, res)
        assert_cli_side_effects(subst(Map.drop(c, ["fixture"]), subs), out)
      end
    end
  end

  # ---- routing.json: createMedia routing with test-only custom clients ----

  defmodule TestClient do
    @moduledoc false
    @behaviour Toolnexus.Media.Client
    defstruct [:name, :verbs, :pid]

    @impl true
    def name(c), do: c.name
    @impl true
    def supports?(c, verb), do: Map.has_key?(c.verbs, verb)

    @impl true
    def args(c, verb) do
      case c.verbs[verb]["args"] do
        nil -> nil
        list -> Enum.map(list, &%{name: &1["name"], required: &1["required"]})
      end
    end

    for {f, verb} <- [
          image: "image",
          video: "video",
          speech: "speech",
          music: "music",
          transcribe: "transcribe",
          clone_voice: "cloneVoice",
          talk: "talk"
        ] do
      @impl true
      def unquote(f)(c, args, _ctx), do: outcome(c, unquote(verb), args)
    end

    defp outcome(c, verb, args) do
      send(c.pid, {:call, %{"client" => c.name, "verb" => verb, "args" => args}})

      case c.verbs[verb]["outcome"] do
        %{"result" => r} ->
          {:ok,
           %Result{
             files:
               Enum.map(r["files"], fn f ->
                 %MediaFile{
                   type: f["type"],
                   mime_type: f["mimeType"],
                   url: f["url"],
                   data: f["data"]
                 }
               end),
             text: r["text"],
             job_id: r["jobId"],
             status: r["status"],
             usage: r["usage"]
           }}

        %{"error" => m, "status" => s} ->
          {:error, %Error{message: m, status: String.to_atom(s)}}
      end
    end
  end

  defp routing_media(clients) do
    Media.create(
      Enum.map(clients, &%TestClient{name: &1["name"], verbs: &1["verbs"], pid: self()})
    )
  end

  defp calls(acc \\ []) do
    receive do
      {:call, c} -> calls(acc ++ [c])
    after
      0 -> acc
    end
  end

  for c <- @routing["cases"] do
    @c c
    test "routing.json: #{c["name"]}" do
      c = @c
      {:ok, media} = routing_media(c["clients"])
      out = Media.generate(media, c["verb"], c["args"])
      if exp = c["expectCalls"], do: assert(calls() == exp)

      case c do
        %{"error" => msg} ->
          assert {:error, %Error{message: ^msg} = e} = out
          assert e.status == String.to_atom(c["status"])

        %{"result" => exp} ->
          assert {:ok, r} = out
          assert Enum.map(r.files, &file_json/1) == exp["files"]
          assert r.status == exp["status"] and r.job_id == exp["jobId"] and r.text == exp["text"]
          assert r.usage == exp["usage"]
      end

      if t = c["tool"] do
        _ = calls()
        {:ok, media} = routing_media(c["clients"])
        tool = Enum.find(Media.tools(media), &(&1.name == verb_tool(c["verb"])))
        assert_tool(tool.execute.(c["args"], %Context{}), t, nil)
      end
    end
  end

  test "routing.json: schemaCases" do
    for sc <- @routing["schemaCases"] do
      {:ok, media} = routing_media(sc["clients"])
      tools = Media.tools(media)
      assert schema_view(tools) == sc["tools"]
      assert_property_order(tools)
    end
  end

  # ---- D20 / D21 / D23 flags ----

  defp assert_header_patterns(_reqs, nil), do: :ok

  defp assert_header_patterns(reqs, patterns) do
    assert reqs != []

    for r <- reqs, {h, p} <- patterns do
      v = r.headers[String.downcase(h)]
      assert is_binary(v) and v =~ Regex.compile!(p), "#{h}: #{inspect(v)} on #{r.path}"
    end
  end

  defp assert_usage_flags(c, out) do
    if c["noUsage"], do: assert({:ok, %Result{usage: nil}} = out)
  end

  defp assert_tool_flags(c, %Toolnexus.ToolResult{} = r) do
    if c["noUsage"], do: refute(Map.has_key?(r.metadata || %{}, :usage))
  end

  # D20: RunResult.files built by the port's run-files function from a toolCalls record.
  defp assert_run_file_path(c, {:ok, %Result{} = r}) do
    tool = %Toolnexus.ToolResult{
      output: "x",
      parts: Enum.map(r.files, &%Toolnexus.ContentPart{type: &1.type, mime_type: &1.mime_type}),
      paths: Enum.map(r.files, & &1.path)
    }

    rec = %{name: "t", parts: tool.parts, __call_id: "c1", __paths: tool.paths}
    files = Toolnexus.Client.run_files([rec])
    assert length(files) == length(r.files)

    for {f, m} <- Enum.zip(files, r.files) do
      if c["runFilePath"] do
        assert is_binary(m.path) and Path.type(m.path) == :absolute
        assert f.path == m.path
      else
        refute Map.has_key?(f, :path)
      end
    end
  end

  test "D23: the elixir User-Agent is toolnexus/<mix version> (elixir)" do
    assert Toolnexus.Media.Job.user_agent() ==
             "toolnexus/#{Mix.Project.config()[:version]} (elixir)"
  end

  test "D20: the real tool face carries paths internally, never in parts" do
    dir = tmp_dir("d20")
    path = Path.join(dir, "a.png")
    File.write!(path, "PNG")

    {:ok, media} =
      Media.create([
        %TestClient{
          name: "p",
          pid: self(),
          verbs: %{
            "image" => %{
              "outcome" => %{
                "result" => %{
                  "files" => [
                    %{"type" => "image", "mimeType" => "image/png", "url" => "https://x/a.png"}
                  ],
                  "status" => "completed"
                }
              }
            }
          }
        }
      ])

    tool = hd(Media.tools(media))
    r = tool.execute.(%{"prompt" => "x"}, %Context{})
    assert r.paths == [nil]
    refute Enum.any?(r.parts, &Map.has_key?(Map.from_struct(&1), :path))
  end

  test "routing.json: emptyClients" do
    assert Media.create([]) ==
             {:error, %Error{message: @routing["emptyClients"]["error"], status: :failed}}

    assert_raise Error, @routing["emptyClients"]["error"], fn -> Media.create!([]) end
  end
end
