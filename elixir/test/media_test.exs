defmodule Toolnexus.MediaTest do
  @moduledoc """
  SPEC §7G conformance: replays `conformance/media/cases.json` and `schemas.json`
  (read from the repo, never copied) against an in-process Bandit server.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Client, ContentPart, Context, Media, ToolResult}

  @root Path.expand("../../conformance/media", __DIR__)
  @cases (@root <> "/cases.json") |> File.read!() |> Jason.decode!() |> Map.fetch!("cases")
  @schemas (@root <> "/schemas.json") |> File.read!() |> Jason.decode!()
  @verbs (@root <> "/verbs.json") |> File.read!() |> Jason.decode!() |> Map.fetch!("verbs")

  defmodule FakeServer do
    @moduledoc false
    @behaviour Plug
    import Plug.Conn

    def init(state), do: state

    def call(conn, state) do
      {:ok, body, conn} = read_body(conn)

      entry =
        Agent.get_and_update(state, fn %{script: script, requests: reqs} = s ->
          req = %{
            method: conn.method,
            path: conn.request_path,
            body: body,
            headers: Map.new(conn.req_headers)
          }

          {entry, rest} =
            case script do
              [only] -> {only, [only]}
              [h | t] -> {h, t}
            end

          {entry, %{s | script: rest, requests: reqs ++ [req]}}
        end)

      raw = entry["rawBody"] || Jason.encode!(entry["body"])
      server = Agent.get(state, & &1.url)

      conn =
        Enum.reduce(entry["headers"] || %{}, conn, fn {k, v}, conn ->
          put_resp_header(conn, String.downcase(k), String.replace(v, "{server}", server))
        end)

      conn |> put_resp_content_type("application/json") |> send_resp(entry["status"], raw)
    end
  end

  defp serve(script) do
    {:ok, state} = Agent.start_link(fn -> %{script: script, requests: []} end)

    {:ok, server} =
      Bandit.start_link(
        plug: {FakeServer, state},
        scheme: :http,
        port: 0,
        ip: {127, 0, 0, 1},
        startup_log: false
      )

    {:ok, {_ip, port}} = ThousandIsland.listener_info(server)
    url = "http://127.0.0.1:#{port}"
    Agent.update(state, &Map.put(&1, :url, url))
    {state, server, url}
  end

  defp stop({state, server, _}) do
    ThousandIsland.stop(server, 500)
    Agent.stop(state)
  end

  defp style_for(c, url) do
    Map.update!(c["style"], "baseUrl", &String.replace(&1, "{server}", url))
  end

  defp file_json(%ContentPart{} = p),
    do: %{"type" => p.type, "mimeType" => p.mime_type, "url" => p.url}

  defp result_json(%Media.Result{} = r) do
    %{"files" => Enum.map(r.files, &file_json/1), "status" => r.status}
    |> then(&if(r.text, do: Map.put(&1, "text", r.text), else: &1))
    |> then(&if(r.job_id, do: Map.put(&1, "jobId", r.job_id), else: &1))
  end

  defp tool_json(name, %ToolResult{} = r) do
    %{"name" => name, "isError" => r.is_error, "output" => r.output}
    |> then(
      &if(r.parts,
        do: Map.put(&1, "parts", Enum.map(r.parts, fn p -> file_json(p) end)),
        else: &1
      )
    )
    |> then(
      &if(r.metadata,
        do: Map.put(&1, "metadata", Map.new(r.metadata, fn {k, v} -> {to_string(k), v} end)),
        else: &1
      )
    )
  end

  test "conformance: cases.json has exactly 34 cases" do
    assert length(@cases) == 34
  end

  for c <- @cases do
    @c c
    test "conformance: #{c["name"]}" do
      c = @c
      for {k, v} <- c["env"] || %{}, do: System.put_env(k, v)
      on_exit(fn -> for {k, _} <- c["env"] || %{}, do: System.delete_env(k) end)

      # ---- function face ----
      {state, _, url} = srv = serve(c["script"])
      me = self()
      media = Media.new(style_for(c, url), on_progress: &send(me, {:progress, &1}))
      out = Media.generate(media, c["verb"], c["args"])
      requests = Agent.get(state, & &1.requests)
      stop(srv)

      case c do
        %{"error" => msg} ->
          assert out == {:error, msg}

        %{"result" => expected} ->
          assert {:ok, r} = out
          assert result_json(r) == expected
      end

      if exp = c["expectRequests"] do
        assert length(requests) == length(exp)

        for {e, r} <- Enum.zip(exp, requests) do
          assert r.method == e["method"] and r.path == e["path"]
          if e["body"], do: assert(Jason.decode!(r.body) == e["body"])

          if e["body"],
            do: assert(r.headers["content-type"] == "application/json"),
            else: assert(r.body == "" and r.headers["content-type"] == nil)
        end
      end

      for r <- requests, {h, v} <- c["expectHeaders"] || %{} do
        assert r.headers[String.downcase(h)] == v
      end

      progress = collect_progress([])

      if exp = c["expectProgress"] do
        assert Enum.map(progress, & &1.status) == exp
        assert Enum.all?(progress, &(&1.verb == c["verb"]))
      end

      # ---- tool face ----
      if t = c["tool"] do
        srv = serve(c["script"])
        tools = Media.tools(Media.new(style_for(c, elem(srv, 2))))
        tool = Enum.find(tools, &(&1.name == t["name"]))
        result = tool.execute.(c["args"], %Context{})
        stop(srv)
        assert tool_json(t["name"], result) == t
      end
    end
  end

  defp collect_progress(acc) do
    receive do
      {:progress, p} -> collect_progress(acc ++ [p])
    after
      0 -> acc
    end
  end

  test "conformance: schemas.json — exact tool list, order and inputSchema" do
    tools = Media.tools(Media.new(@schemas["style"]))

    got =
      Enum.map(tools, fn t ->
        %{
          "name" => t.name,
          "description" => t.description,
          "source" => t.source,
          "inputSchema" => t.input_schema
        }
      end)

    assert got == @schemas["tools"]
    # property ORDER is canonical too: compare key order through the verb table
    for {t, exp} <- Enum.zip(tools, @schemas["tools"]) do
      verb = Enum.find(Media.verbs(), &(&1.tool == t.name))
      order = for a <- verb.args, Map.has_key?(t.input_schema["properties"], a.name), do: a.name

      assert order ==
               Map.keys(exp["inputSchema"]["properties"])
               |> Enum.sort_by(&Enum.find_index(order, fn n -> n == &1 end))
    end
  end

  test "the embedded verb table equals verbs.json byte-for-byte (as data)" do
    embedded =
      Enum.map(Media.verbs(), fn v ->
        %{
          "verb" => v.verb,
          "tool" => v.tool,
          "description" => v.description,
          "args" => Enum.map(v.args, fn a -> Map.new(a, fn {k, x} -> {to_string(k), x} end) end)
        }
      end)

    assert embedded == @verbs
  end

  # ---- function-face idioms ----

  defp transport_media(style, responder) do
    me = self()

    Media.new(style,
      transport: fn req ->
        send(me, {:req, req})
        responder.(req)
      end
    )
  end

  @sync_image %{
    "baseUrl" => "http://fake/",
    "headers" => %{"X-K" => "${MEDIA_NEVER_SET_X}v"},
    "verbs" => %{
      "image" => %{
        "mode" => "sync",
        "kind" => "image",
        "start" => %{"path" => "/i"},
        "args" => %{"prompt" => "p", "aspectRatio" => "ar", "seed" => "opts.list.2"},
        "resultPath" => "u"
      }
    }
  }

  test "keyword args, snake_case atoms, provider_options last, transport seam, padded arrays" do
    media =
      transport_media(@sync_image, fn _ ->
        {:ok,
         %{
           status: 200,
           headers: %{},
           body: ~s({"u":["https://x/a.gif",["https://x/b.MOV?q=1#f",""]]})
         }}
      end)

    assert {:ok, %Media.Result{files: files, status: "completed", job_id: nil}} =
             Media.image(media,
               prompt: "p",
               aspect_ratio: "1:1",
               seed: 3,
               provider_options: %{"p" => "over", extra: 1}
             )

    assert_receive {:req, req}
    assert req.method == :post and req.url == "http://fake/i"
    assert req.headers["X-K"] == "v" and req.headers["Content-Type"] == "application/json"

    assert req.body == %{
             "p" => "over",
             "ar" => "1:1",
             "opts" => %{"list" => [nil, nil, 3]},
             "extra" => 1
           }

    assert Enum.map(files, &{&1.type, &1.mime_type}) == [
             {"image", "image/gif"},
             {"image", "video/quicktime"}
           ]
  end

  test "transport may return a decoded body; transport errors become messages" do
    ok =
      transport_media(@sync_image, fn _ ->
        {:ok, %{status: 200, body: %{"u" => "https://x/a.png"}}}
      end)

    assert {:ok, %{files: [%{url: "https://x/a.png"}]}} =
             Media.generate(ok, :image, %{prompt: "x"})

    bad = transport_media(@sync_image, fn _ -> {:error, RuntimeError.exception("down")} end)
    assert Media.image(bad, %{"prompt" => "x"}) == {:error, "image: down"}

    weird = transport_media(@sync_image, fn _ -> {:error, :econnrefused} end)
    assert Media.image(weird, %{"prompt" => "x"}) == {:error, "image: :econnrefused"}

    nonjson = transport_media(@sync_image, fn _ -> {:ok, %{status: 200, body: 42}} end)
    assert Media.image(nonjson, %{"prompt" => "x"}) == {:error, "image: response is not JSON"}
  end

  test "Req path: connection error is reported, never raised" do
    media = Media.new(%{@sync_image | "baseUrl" => "http://127.0.0.1:1"})
    assert {:error, "image: " <> _} = Media.image(media, %{prompt: "x", seed: nil})

    assert {:error, ~s(image: argument "a" not supported by this style)} =
             Media.image(media, %{"z" => 1, "a" => 2})
  end

  test "every verb wrapper resolves its verb name" do
    styles =
      for v <- ~w(video speech transcribe cloneVoice talking), into: %{} do
        kind =
          if v in ~w(transcribe cloneVoice),
            do: "text",
            else: if(v == "speech", do: "audio", else: "video")

        {v,
         %{
           "mode" => "sync",
           "kind" => kind,
           "start" => %{"path" => "/" <> v},
           "args" => %{"a" => "a"},
           "resultPath" => "r"
         }}
      end

    media =
      transport_media(%{"baseUrl" => "http://f", "verbs" => styles}, fn _ ->
        {:ok, %{status: 200, body: %{"r" => 7.0}}}
      end)

    assert {:ok, %{files: [%{type: "file", mime_type: "video/mp4"}]}} =
             Media.video(media, a: "https://x/v") |> fix_url()

    assert {:ok, %{text: "7"}} = Media.transcribe(media, a: 1)
    assert {:ok, %{text: "7"}} = Media.clone_voice(media, a: 1)
    assert {:error, "speech: no result at r"} = Media.speech(media, a: 1)
    assert {:error, "talking: no result at r"} = Media.talking(media, a: 1)
  end

  # number results are not URLs; the video wrapper is exercised with a string result
  defp fix_url(_) do
    media =
      transport_media(
        %{
          "baseUrl" => "http://f",
          "verbs" => %{
            "video" => %{
              "mode" => "sync",
              "kind" => "video",
              "start" => %{"path" => "/v"},
              "args" => %{"a" => "a"},
              "resultPath" => "r"
            }
          }
        },
        fn _ -> {:ok, %{status: 200, body: %{"r" => "https://x/clip"}}} end
      )

    Media.video(media, a: "x")
  end

  test "first style declaring a verb wins; errors on unknown style shapes" do
    a = %{"baseUrl" => "http://a", "verbs" => %{"image" => @sync_image["verbs"]["image"]}}
    b = %{"baseUrl" => "http://b", "verbs" => %{"image" => @sync_image["verbs"]["image"]}}

    media =
      transport_media([a, b], fn _ -> {:ok, %{status: 200, body: %{"u" => "https://x/a"}}} end)

    assert {:ok, _} = Media.image(media, prompt: "x")
    assert_receive {:req, %{url: "http://a/i"}}
  end

  test "error envelope: numeric float code renders integral; missing code passes; non-string message" do
    style =
      Map.put(@sync_image, "errorEnvelope", %{"codePath" => "code", "okValues" => [0]})

    m = transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"code" => 5.0}}} end)
    assert Media.image(m, prompt: "x") == {:error, "API error 5: "}

    m =
      transport_media(style, fn _ ->
        {:ok, %{status: 200, body: %{"code" => 0.0, "u" => "https://x/z.flac"}}}
      end)

    assert {:ok, %{files: [%{type: "image", mime_type: "audio/flac"}]}} =
             Media.image(m, prompt: "x")

    m = transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"code" => 1.5}}} end)
    assert Media.image(m, prompt: "x") == {:error, "API error 1.5: "}
    m = transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"code" => "E"}}} end)
    assert Media.image(m, prompt: "x") == {:error, "API error E: "}

    m =
      transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"u" => "https://x/q.webm"}}} end)

    assert {:ok, %{files: [%{mime_type: "video/webm"}]}} = Media.image(m, prompt: "x")
  end

  @async %{
    "baseUrl" => "http://f",
    "verbs" => %{
      "speech" => %{
        "mode" => "async",
        "kind" => "audio",
        "start" => %{"path" => "/s"},
        "args" => %{"text" => "t"},
        "idPath" => "id",
        "poll" => %{
          "path" => "/p/{id}",
          "statusPath" => "st",
          "done" => ["ok"],
          "failed" => ["no"],
          "intervalMs" => 1,
          "timeoutMs" => 5000
        },
        "resultPath" => "u"
      }
    }
  }

  test "async: numeric status, poll defaults to GET without body, cancellation via signal" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    responder = fn req ->
      case Agent.get_and_update(n, &{&1, &1 + 1}) do
        0 ->
          {:ok, %{status: 200, body: %{"id" => "j"}}}

        _ ->
          assert(req.method == :get and req.body == nil) &&
            {:ok, %{status: 200, body: %{"st" => 3}}}
      end
    end

    me = self()
    media = Media.new(@async, transport: responder, on_progress: &send(me, {:p, &1}))
    flag = fn -> Agent.get(n, & &1) >= 3 end
    assert_raise Media.Cancelled, fn -> Media.speech(media, %{text: "hi"}, signal: flag) end
    assert_receive {:p, %{verb: "speech", job_id: "j", status: "3"}}

    dead = spawn(fn -> :ok end)
    Process.sleep(10)
    Agent.update(n, fn _ -> 0 end)
    assert_raise Media.Cancelled, fn -> Media.speech(media, %{text: "hi"}, signal: dead) end

    # tool face honours ctx.signal and reports jobId; an unknown signal shape never cancels
    Agent.update(n, fn _ -> 0 end)
    [tool] = Media.tools(media)
    r = tool.execute.(%{"text" => "hi"}, %Context{signal: dead})
    assert %ToolResult{is_error: true, output: "media: cancelled"} = r
  end

  test "async: id of non-scalar type is 'no job id'; HTTP error on a poll" do
    m = transport_media(@async, fn _ -> {:ok, %{status: 200, body: %{"id" => %{"x" => 1}}}} end)
    assert Media.speech(m, text: "x") == {:error, "speech: no job id at id"}
    m = transport_media(@async, fn _ -> {:ok, %{status: 200, body: %{"id" => 1.5}}} end)
    assert Media.speech(m, text: "x") == {:error, "speech: no job id at id"}

    {:ok, n} = Agent.start_link(fn -> 0 end)

    m =
      transport_media(@async, fn _ ->
        if Agent.get_and_update(n, &{&1, &1 + 1}) == 0,
          do: {:ok, %{status: 200, body: %{"id" => 9}}},
          else: {:ok, %{status: 500, body: "boom"}}
      end)

    assert Media.speech(m, text: "x") == {:error, "HTTP 500: boom"}
  end

  test "dotPath helpers" do
    assert Media.read_path(%{"a" => [1, %{"b" => 2}]}, "a.1.b") == {:ok, 2}
    assert Media.read_path(%{"a" => [1]}, "a.x") == :absent
    assert Media.read_path(%{"a" => [1]}, "a.4") == :absent
    assert Media.read_path("s", "a") == :absent
    assert Media.read_path(%{}, nil) == :absent
    assert Media.put_path(%{"a" => "scalar"}, "a.0.b", 1) == %{"a" => [%{"b" => 1}]}
    assert Media.put_path(%{"a" => [5, 6]}, "a.1", 7) == %{"a" => [5, 7]}
  end

  test "load_style reads a JSON file and reports bad files" do
    dir = Path.join(System.tmp_dir!(), "tn-media-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    on_exit(fn -> File.rm_rf!(dir) end)
    good = Path.join(dir, "s.json")
    File.write!(good, Jason.encode!(@sync_image))
    assert Media.load_style(good) == {:ok, @sync_image}
    File.write!(Path.join(dir, "b.json"), "{nope")
    assert {:error, "media: " <> _} = Media.load_style(Path.join(dir, "b.json"))
    File.write!(Path.join(dir, "a.json"), "[1]")
    assert {:error, "media: " <> _} = Media.load_style(Path.join(dir, "a.json"))
    assert {:error, "media: " <> _} = Media.load_style(Path.join(dir, "missing.json"))
  end

  # ---- toolkit + RunResult.files (§8) ----

  defp image_media do
    transport_media(@sync_image, fn _ ->
      {:ok, %{status: 200, body: %{"u" => ["https://x/a.png", "https://x/b.jpg"]}}}
    end)
  end

  test "toolkit media: registers the tools; RunResult.files carries from + callId (non-streaming)" do
    tk = Toolnexus.create_toolkit!(media: image_media(), builtins: false)
    assert Enum.map(Toolnexus.Toolkit.tools(tk), & &1.name) == ["generate_image"]

    {:ok, turn} = Agent.start_link(fn -> 0 end)

    client =
      Client.create_in_process(
        model: "fake",
        generate: fn _ ->
          case Agent.get_and_update(turn, &{&1, &1 + 1}) do
            0 ->
              %{
                tool_calls: [
                  %{id: "call_1", name: "generate_image", arguments: %{"prompt" => "cat"}}
                ]
              }

            _ ->
              %{content: "done"}
          end
        end
      )

    r = Client.run(client, "draw", tk)
    assert r.text == "done"

    assert r.files == [
             %{
               type: "image",
               mime_type: "image/png",
               url: "https://x/a.png",
               from: "generate_image",
               call_id: r.files |> hd() |> Map.get(:call_id)
             },
             %{
               type: "image",
               mime_type: "image/jpeg",
               url: "https://x/b.jpg",
               from: "generate_image",
               call_id: r.files |> hd() |> Map.get(:call_id)
             }
           ]

    assert is_binary(hd(r.files).call_id)
    [call] = r.tool_calls
    assert length(call.parts) == 2 and not Map.has_key?(call, :__call_id)
  end

  test "media tools register before :extra_tools — first name wins" do
    extra =
      Toolnexus.define_tool(
        name: "generate_image",
        description: "x",
        execute: fn _ -> "extra" end
      )

    tk = Toolnexus.create_toolkit!(media: image_media(), extra_tools: [extra], builtins: false)
    assert [%{name: "generate_image", source: "media"}] = Toolnexus.Toolkit.tools(tk)
  end

  test "runs without media keep files == [] and tool_calls without parts" do
    tk =
      Toolnexus.create_toolkit!(
        extra_tools: [
          Toolnexus.define_tool(name: "t", description: "d", execute: fn _ -> "ok" end)
        ],
        builtins: false
      )

    {:ok, turn} = Agent.start_link(fn -> 0 end)

    client =
      Client.create_in_process(
        model: "fake",
        generate: fn _ ->
          if Agent.get_and_update(turn, &{&1, &1 + 1}) == 0,
            do: %{tool_calls: [%{name: "t", arguments: %{}}]},
            else: %{content: "fin"}
        end
      )

    r = Client.run(client, "go", tk)
    assert r.files == [] and r.tool_calls |> hd() |> Map.has_key?(:parts) == false
  end

  test "RunResult.files on the openai streaming path uses the model's tool-call id" do
    tk = Toolnexus.create_toolkit!(media: image_media(), builtins: false)
    {:ok, turn} = Agent.start_link(fn -> 0 end)

    sse = fn chunks -> Enum.map_join(chunks ++ ["[DONE]"], "\n\n", &("data: " <> &1)) end

    transport = fn _ ->
      body =
        if Agent.get_and_update(turn, &{&1, &1 + 1}) == 0 do
          sse.([
            ~s({"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_s1","function":{"name":"generate_image","arguments":"{\\"prompt\\":\\"cat\\"}"}}]}}]})
          ])
        else
          sse.([~s({"choices":[{"delta":{"content":"ok"}}]})])
        end

      {:ok, %{status: 200, headers: %{}, body: body}}
    end

    client =
      Client.create(
        base_url: "http://t.invalid",
        style: "openai",
        model: "m",
        api_key: "k",
        transport: transport
      )

    events = Client.stream(client, "draw", tk) |> Enum.to_list()
    %{type: "done", result: r} = List.last(events)

    assert Enum.map(r.files, &{&1.from, &1.call_id, &1.url}) == [
             {"generate_image", "call_s1", "https://x/a.png"},
             {"generate_image", "call_s1", "https://x/b.jpg"}
           ]
  end

  test "RunResult.files on the anthropic non-streaming path" do
    tk = Toolnexus.create_toolkit!(media: image_media(), builtins: false)
    {:ok, turn} = Agent.start_link(fn -> 0 end)

    transport = fn _ ->
      body =
        if Agent.get_and_update(turn, &{&1, &1 + 1}) == 0,
          do: %{
            "content" => [
              %{
                "type" => "tool_use",
                "id" => "tu_1",
                "name" => "generate_image",
                "input" => %{"prompt" => "c"}
              }
            ],
            "stop_reason" => "tool_use",
            "usage" => %{}
          },
          else: %{
            "content" => [%{"type" => "text", "text" => "fin"}],
            "stop_reason" => "end_turn",
            "usage" => %{}
          }

      {:ok, %{status: 200, headers: %{}, body: body}}
    end

    client =
      Client.create(
        base_url: "http://t.invalid",
        style: "anthropic",
        model: "m",
        api_key: "k",
        transport: transport
      )

    r = Client.run(client, "draw", tk)
    assert r.text == "fin"
    assert Enum.map(r.files, & &1.call_id) == ["tu_1", "tu_1"]
  end

  # Media tools are concurrency-safe (SPEC §7G, §8 Tool scheduling): each call is an independent remote job.
  test "all six media tools are concurrency-safe" do
    tools =
      Media.tools(
        Media.new(
          Jason.decode!(
            ~S({"baseUrl":"http://unused","verbs":{"image":{"mode":"sync","kind":"image","start":{"path":"/i"},"args":{"prompt":"p"},"resultPath":"u"},"video":{"mode":"sync","kind":"video","start":{"path":"/v"},"args":{"prompt":"p"},"resultPath":"u"},"speech":{"mode":"sync","kind":"audio","start":{"path":"/s"},"args":{"text":"t"},"resultPath":"u"},"transcribe":{"mode":"sync","kind":"text","start":{"path":"/r"},"args":{"audio":"a"},"resultPath":"u"},"cloneVoice":{"mode":"sync","kind":"text","start":{"path":"/c"},"args":{"name":"n"},"resultPath":"id"},"talking":{"mode":"sync","kind":"video","start":{"path":"/t"},"args":{"image":"i"},"resultPath":"u"}}})
          )
        )
      )

    assert length(tools) == 6
    for t <- tools, do: assert(Toolnexus.ToolScheduling.concurrency_safe?(t, %{}), t.name)
  end

  defmodule PlugFn do
    @moduledoc false
    @behaviour Plug
    def init(f), do: f
    def call(conn, f), do: f.(conn)
  end

  defp serve_fn(f) do
    {:ok, server} =
      Bandit.start_link(
        plug: {PlugFn, f},
        scheme: :http,
        port: 0,
        ip: {127, 0, 0, 1},
        startup_log: false
      )

    {:ok, {_ip, port}} = ThousandIsland.listener_info(server)
    {server, "http://127.0.0.1:#{port}"}
  end

  defp sync_style(url, extra \\ %{}) do
    %{
      "baseUrl" => url,
      "headers" => %{"Authorization" => "Bearer FAKE", "X-Style" => "s"},
      "verbs" => %{
        "image" =>
          Map.merge(
            %{
              "mode" => "sync",
              "kind" => "image",
              "start" => %{"path" => "/i"},
              "args" => %{"prompt" => "p"},
              "resultPath" => "u"
            },
            extra
          )
      }
    }
  end

  test "§7G Redirects: a cross-origin redirect never reaches the other origin with style headers" do
    me = self()

    {b, b_url} =
      serve_fn(fn conn ->
        send(me, {:b_saw, Map.new(conn.req_headers)})
        Plug.Conn.send_resp(conn, 200, ~s({"u":"https://x/r.png"}))
      end)

    {a, a_url} =
      serve_fn(fn conn ->
        conn
        |> Plug.Conn.put_resp_header("location", b_url <> "/landed")
        |> Plug.Conn.send_resp(302, "moved")
      end)

    assert {:ok, %Media.Result{}} =
             Media.image(Media.new(sync_style(a_url), http_options: [redirect: true]),
               prompt: "x"
             )

    assert_received {:b_saw, h}
    refute Map.has_key?(h, "authorization") or Map.has_key?(h, "x-style")
    ThousandIsland.stop(a, 500)
    ThousandIsland.stop(b, 500)
  end

  test "§7G Per-request timeout: a hung server is bounded by the job deadline" do
    {srv, url} =
      serve_fn(fn conn ->
        if conn.request_path == "/a" do
          Plug.Conn.send_resp(conn, 200, ~s({"id":"h1"}))
        else
          Process.sleep(3_000)
          Plug.Conn.send_resp(conn, 200, "{}")
        end
      end)

    t0 = System.monotonic_time(:millisecond)

    assert {:error, "image: timed out after 1000ms"} =
             Media.image(Media.new(sync_style(url, %{"poll" => %{"timeoutMs" => 1000}})),
               prompt: "x"
             )

    assert System.monotonic_time(:millisecond) - t0 < 2_500

    # A host http_options receive_timeout cannot widen the job-deadline bound.
    t0 = System.monotonic_time(:millisecond)

    assert {:error, "image: timed out after 1000ms"} =
             Media.image(
               Media.new(sync_style(url, %{"poll" => %{"timeoutMs" => 1000}}),
                 http_options: [receive_timeout: 60_000, connect_options: [protocols: [:http1]]]
               ),
               prompt: "x"
             )

    assert System.monotonic_time(:millisecond) - t0 < 2_500

    async =
      sync_style(url, %{
        "mode" => "async",
        "start" => %{"path" => "/a"},
        "idPath" => "id",
        "poll" => %{
          "path" => "/p/{id}",
          "statusPath" => "st",
          "done" => ["ok"],
          "intervalMs" => 1,
          "timeoutMs" => 1000
        }
      })

    assert {:error, "job h1 timed out after 1000ms"} = Media.image(Media.new(async), prompt: "x")
  end

  test "§7G: an injected transport sees the remaining deadline and its timeout maps to the message" do
    me = self()

    transport = fn req ->
      send(me, {:rt, req.receive_timeout})
      {:error, %{reason: :timeout}}
    end

    style = sync_style("http://unused", %{"poll" => %{"timeoutMs" => 50}})

    assert {:error, "image: timed out after 50ms"} =
             Media.image(Media.new(style, transport: transport), prompt: "x")

    assert_received {:rt, rt} when is_integer(rt) and rt <= 50

    assert {:error, "image: \"boom\""} =
             Media.image(Media.new(style, transport: fn _ -> {:error, "boom"} end), prompt: "x")
  end

  test "§7G: load_style rejects an args entry mapped to the empty path" do
    path =
      Path.join(System.tmp_dir!(), "tn-media-empty-#{System.unique_integer([:positive])}.json")

    File.write!(
      path,
      Jason.encode!(sync_style("http://x") |> put_in(["verbs", "image", "args", "seed"], ""))
    )

    assert Media.load_style(path) == {:error, ~s(style: empty path for argument "seed")}
    File.rm(path)
  end

  test "§7G: dotPath write through an array with a word segment replaces the array" do
    assert Media.put_path(%{"a" => []}, "a.x.y", 1) == %{"a" => %{"x" => %{"y" => 1}}}
  end

  test "§7G Redirects via transport: relative Location, header shapes, no-Location and 300 are errors" do
    me = self()

    transport = fn req ->
      send(me, {:req, req})

      case req.url do
        "http://a/i" ->
          {:ok, %{status: 308, headers: [{"Location", "/two"}], body: "m"}}

        "http://a/two" ->
          {:ok, %{status: 301, headers: %{"location" => ["http://b/x"]}, body: ""}}

        "http://b/x" ->
          {:ok, %{status: 200, headers: %{}, body: ~s({"u":"https://x/r.png"})}}
      end
    end

    assert {:ok, _} =
             Media.image(Media.new(sync_style("http://a"), transport: transport), prompt: "y")

    assert_received {:req, %{method: :post, url: "http://a/i"}}
    assert_received {:req, %{method: :post, url: "http://a/two", headers: %{"X-Style" => "s"}}}
    assert_received {:req, %{method: :get, url: "http://b/x", body: nil, headers: h}}
    assert h == %{}

    for {st, hs} <- [{302, %{}}, {300, %{"location" => "http://a/z"}}, {302, nil}] do
      t = fn _ -> {:ok, %{status: st, headers: hs, body: "nope"}} end

      assert {:error, "HTTP #{st}: nope"} ==
               Media.image(Media.new(sync_style("http://a"), transport: t), prompt: "y")
    end
  end
end
