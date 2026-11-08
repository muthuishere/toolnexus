defmodule Toolnexus.MediaTest do
  @moduledoc """
  SPEC §7G unit tests beside the conformance replay (`media_conformance_test.exs`): the
  Elixir idioms (keyword args, the `:http_client` seam, cooperative `:signal`), edge rules
  the fixtures do not reach, and the toolkit / `RunResult.files` / model-visibility wiring.
  """
  use ExUnit.Case, async: false

  import Toolnexus.MediaHarness, only: [tmp_dir: 1]

  alias Toolnexus.{Client, Context, Media, ToolResult}
  alias Toolnexus.Media.{Config, Error, Gather, Job, MediaFile, Result, StyleClient}

  defp transport_media(style, responder, config \\ []) do
    me = self()

    seam = fn req ->
      send(me, {:req, req})
      responder.(req)
    end

    Media.create!(Media.style_client!(style, Keyword.put(config, :http_client, seam)))
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

  defp err(msg), do: {:error, %Error{message: msg, status: :failed}}
  defp unavailable(msg), do: {:error, %Error{message: msg, status: :unavailable}}

  test "keyword args, snake_case atoms, provider_options last, http_client seam, padded arrays" do
    media =
      transport_media(@sync_image, fn _ ->
        {:ok,
         %{
           status: 200,
           headers: %{},
           body: ~s({"u":["https://x/a.gif",["https://x/b.MOV?q=1#f",""]]})
         }}
      end)

    assert {:ok, %Result{files: files, status: "completed", job_id: nil}} =
             Media.image(media,
               prompt: "p",
               aspect_ratio: "1:1",
               seed: 3,
               provider_options: %{"p" => "over", extra: 1}
             )

    assert_receive {:req, req}
    assert req.method == :post and req.url == "http://fake/i"
    assert req.headers["X-K"] == "v" and req.headers["Content-Type"] == "application/json"

    assert Jason.decode!(req.body) == %{
             "p" => "over",
             "ar" => "1:1",
             "opts" => %{"list" => [nil, nil, 3]},
             "extra" => 1
           }

    assert Enum.map(files, &{&1.type, &1.mime_type}) == [
             {"image", "image/gif"},
             {"image", "video/quicktime"}
           ]

    assert Media.supports?(media, :image) and not Media.supports?(media, :clone_voice)
  end

  test "the seam may return a decoded body; transport errors become messages" do
    ok =
      transport_media(@sync_image, fn _ ->
        {:ok, %{status: 200, body: %{"u" => "https://x/a.png"}}}
      end)

    assert {:ok, %{files: [%{url: "https://x/a.png"}]}} =
             Media.generate(ok, :image, %{prompt: "x"})

    bad = transport_media(@sync_image, fn _ -> {:error, RuntimeError.exception("down")} end)
    assert Media.image(bad, %{"prompt" => "x"}) == err("image: down")

    weird = transport_media(@sync_image, fn _ -> {:error, :econnrefused} end)
    assert Media.image(weird, %{"prompt" => "x"}) == err("image: :econnrefused")

    nonjson = transport_media(@sync_image, fn _ -> {:ok, %{status: 200, body: 42}} end)
    assert Media.image(nonjson, %{"prompt" => "x"}) == err("image: response is not JSON")

    e500 = transport_media(@sync_image, fn _ -> {:ok, %{status: 500, body: %{"a" => 1}}} end)
    assert Media.image(e500, %{"prompt" => "x"}) == err(~s(HTTP 500: {"a":1}))
  end

  test "Req path: connection error is reported, never raised; unsupported arg names this style" do
    media = Media.create!(Media.style_client!(%{@sync_image | "baseUrl" => "http://127.0.0.1:1"}))

    assert {:error, %Error{message: "image: " <> _}} =
             Media.image(media, %{prompt: "x", seed: nil})

    assert Media.image(media, %{"z" => 1, "a" => 2}) ==
             err(~s(image: argument "a" not supported))
  end

  test "every verb wrapper resolves its verb name" do
    styles =
      for v <- ~w(video speech music transcribe cloneVoice talk), into: %{} do
        kind =
          cond do
            v in ~w(transcribe cloneVoice) -> "text"
            v in ~w(speech music) -> "audio"
            true -> "video"
          end

        arg =
          %{
            "talk" => "image",
            "transcribe" => "audio",
            "cloneVoice" => "name",
            "speech" => "text"
          }[v] || "prompt"

        {v,
         %{
           "mode" => "sync",
           "kind" => kind,
           "start" => %{"path" => "/" <> v},
           "args" => %{arg => "a"},
           "resultPath" => "r"
         }}
      end

    media =
      transport_media(%{"baseUrl" => "http://f", "verbs" => styles}, fn _ ->
        {:ok, %{status: 200, body: %{"r" => 7.0}}}
      end)

    assert {:ok, %{text: "7"}} = Media.transcribe(media, audio: 1)
    assert {:ok, %{text: "7"}} = Media.clone_voice(media, name: 1)
    assert Media.speech(media, text: 1) == err("speech: no result at r")
    assert Media.music(media, prompt: 1) == err("music: no result at r")
    assert Media.video(media, prompt: 1) == err("video: no result at r")
    assert Media.talk(media, image: 1) == err("talk: no result at r")
  end

  test "error envelope: numeric float code renders integral; missing code passes" do
    style = Map.put(@sync_image, "errorEnvelope", %{"codePath" => "code", "okValues" => [0]})
    m = transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"code" => 5.0}}} end)
    assert Media.image(m, prompt: "x") == err("API error 5: ")

    m =
      transport_media(style, fn _ ->
        {:ok, %{status: 200, body: %{"code" => 0.0, "u" => "https://x/z.flac"}}}
      end)

    assert {:ok, %{files: [%{type: "image", mime_type: "audio/flac"}]}} =
             Media.image(m, prompt: "x")

    m = transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"code" => 1.5}}} end)
    assert Media.image(m, prompt: "x") == err("API error 1.5: ")
    m = transport_media(style, fn _ -> {:ok, %{status: 200, body: %{"code" => [1]}}} end)
    assert Media.image(m, prompt: "x") == err("API error [1]: ")
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

  test "async: numeric status, poll is GET without body, cancellation via signal, loop.on_progress" do
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
    media = transport_media(@async, responder, loop: [on_progress: &send(me, {:p, &1})])
    flag = fn -> Agent.get(n, & &1) >= 3 end
    assert_raise Media.Cancelled, fn -> Media.speech(media, %{text: "hi"}, signal: flag) end

    assert_receive {:p,
                    %{verb: "speech", client: "style", job_id: "j", status: "3", elapsed_ms: e}}
                   when is_integer(e)

    dead = spawn(fn -> :ok end)
    Process.sleep(10)
    Agent.update(n, fn _ -> 0 end)
    assert_raise Media.Cancelled, fn -> Media.speech(media, %{text: "hi"}, signal: dead) end

    # ctx.progress wins over loop.on_progress (never both)
    flush()
    Agent.update(n, fn _ -> 0 end)

    assert_raise Media.Cancelled, fn ->
      Media.speech(media, %{text: "hi"}, signal: flag, progress: &send(me, {:ctx, &1}))
    end

    assert_receive {:ctx, _}
    refute_receive {:p, _}, 20

    Agent.update(n, fn _ -> 0 end)
    [tool] = Media.tools(media)

    assert %ToolResult{is_error: true, output: "media: cancelled"} =
             tool.execute.(%{"text" => "hi"}, %Context{signal: dead})

    assert Job.signalled?(:other) == false
  end

  defp flush do
    receive do
      _ -> flush()
    after
      0 -> :ok
    end
  end

  test "async: id of non-scalar type is 'no job id'; HTTP error on a poll; ctx deadline" do
    m = transport_media(@async, fn _ -> {:ok, %{status: 200, body: %{"id" => %{"x" => 1}}}} end)
    assert Media.speech(m, text: "x") == err("speech: no job id at id")
    m = transport_media(@async, fn _ -> {:ok, %{status: 200, body: %{"id" => 1.5}}} end)
    assert Media.speech(m, text: "x") == err("speech: no job id at id")

    {:ok, n} = Agent.start_link(fn -> 0 end)

    m =
      transport_media(@async, fn _ ->
        if Agent.get_and_update(n, &{&1, &1 + 1}) == 0,
          do: {:ok, %{status: 200, body: %{"id" => 9.0}}},
          else: {:ok, %{status: 500, body: "boom"}}
      end)

    assert {:error, %Error{message: "HTTP 500: boom", job_id: "9"}} = Media.speech(m, text: "x")

    m =
      transport_media(@async, fn _ ->
        {:ok, %{status: 200, body: %{"id" => "d", "st" => "busy"}}}
      end)

    deadline = System.monotonic_time(:millisecond) + 30

    assert {:error, %Error{status: :timeout, message: "job d timed out after 5000ms"}} =
             Media.speech(m, %{text: "x"}, deadline: deadline)
  end

  test "dotPath helpers" do
    assert StyleClient.read_path(%{"a" => [1, %{"b" => 2}]}, "a.1.b") == {:ok, 2}
    assert StyleClient.read_path(%{"a" => [1]}, "a.x") == :absent
    assert StyleClient.read_path(%{"a" => [1]}, "a.4") == :absent
    assert StyleClient.read_path("s", "a") == :absent
    assert StyleClient.read_path(%{}, nil) == :absent
    assert StyleClient.put_path(%{"a" => "scalar"}, "a.0.b", 1) == %{"a" => [%{"b" => 1}]}
    assert StyleClient.put_path(%{"a" => [5, 6]}, "a.1", 7) == %{"a" => [5, 7]}
    assert StyleClient.put_path(%{"a" => []}, "a.x.y", 1) == %{"a" => %{"x" => %{"y" => 1}}}
  end

  test "load_style reads a JSON file and reports bad files; style errors" do
    dir = tmp_dir("media-style")
    on_exit(fn -> File.rm_rf!(dir) end)
    good = Path.join(dir, "s.json")
    File.write!(good, Jason.encode!(@sync_image))
    assert {:ok, %Media.Style{style: @sync_image, label: "style"}} = Media.load_style(good)
    File.write!(Path.join(dir, "b.json"), "{nope")
    assert {:error, %Error{message: "media: " <> _}} = Media.load_style(Path.join(dir, "b.json"))
    File.write!(Path.join(dir, "a.json"), "[1]")
    assert {:error, %Error{message: "media: " <> _}} = Media.load_style(Path.join(dir, "a.json"))

    assert {:error, %Error{message: "media: " <> _}} =
             Media.load_style(Path.join(dir, "missing.json"))

    File.write!(
      Path.join(dir, "e.json"),
      Jason.encode!(put_in(@sync_image, ["verbs", "image", "args", "seed"], ""))
    )

    assert Media.load_style(Path.join(dir, "e.json")) ==
             err(~s(style: empty path for argument "seed"))

    assert_raise Error, ~s(style: unknown verb "talking"), fn ->
      Media.style_client!(%{"baseUrl" => "x", "verbs" => %{"talking" => %{}}})
    end
  end

  # ---- routing edges ----

  defmodule Bare do
    @moduledoc false
    def name(_), do: "bare"
    def supports?(_, verb), do: verb in ["image", "video", "speech"]
    def image(_, _args, _ctx), do: {:error, "plain string"}
    def video(_, _args, _ctx), do: :weird
  end

  test "a module client: no args/2 ⇒ canonical args; string errors, bad results, missing verbs" do
    media = Media.create!(Bare)
    assert Media.image(media, prompt: "x") == err("plain string")

    assert {:error, %Error{message: "video: invalid client result :weird"}} =
             Media.video(media, prompt: "x")

    assert Media.speech(media, text: "x") == err("speech: not implemented by bare")

    assert Media.image(media, width: 1) ==
             unavailable(
               ~s(media: no client available for "image": bare: missing required argument "prompt")
             )

    [img | _] = Media.tools(media)
    assert img.input_schema["required"] == ["prompt"]

    assert Map.keys(img.input_schema["properties"]) |> Enum.sort() ==
             ~w(aspectRatio height prompt seed width)
  end

  test "Config normalises atom and JSON keys" do
    c =
      Config.new(
        models: [clone_voice: "v1", image: "i"],
        gather: [mode: :inline],
        loop: %{"intervalMs" => 7}
      )

    assert c.models == %{"cloneVoice" => "v1", "image" => "i"}
    assert c.gather.mode == "inline" and c.loop.interval_ms == 7 and c.gather.keep == true
    assert Config.new(c) == c and Config.new(nil) == %Config{}

    assert Config.positive("5", 9) == 9 and Config.positive(-1, 9) == 9 and
             Config.positive(3, 9) == 3
  end

  # ---- gather edges (§7G.5) ----

  defp job(config, opts \\ []) do
    Job.new("t", "image", Config.new(config), %{}, Keyword.merge([base: "http://f"], opts))
  end

  test "gather: inline / url / data URL / local path / maxBytes / invalid input" do
    dir = tmp_dir("gather")
    on_exit(fn -> File.rm_rf!(dir) end)

    seam = fn %{url: "http://f/x"} ->
      {:ok, %{status: 200, headers: [{"Content-Type", "image/gif"}], body: "GIF"}}
    end

    j = job(gather: [mode: "inline", dir: dir], http_client: seam)

    assert {:ok, %MediaFile{data: "R0lG", mime_type: "image/gif", type: "image"}} =
             Gather.collect(j, {:url, "http://f/x", "image"})

    assert {:ok, %MediaFile{data: "QUI="}} = Gather.collect(j, {:data_url, "data:image/png,AB"})

    assert {:error, %Error{message: "image: invalid data URL result"}} =
             Gather.collect(j, {:data_url, "data:nocomma"})

    assert {:error, %Error{message: "image: invalid base64 result"}} =
             Gather.collect(j, {:base64, "!!", "image/png"})

    p = Path.join(dir, "in.mp4")
    File.write!(p, "MP4")

    assert {:ok, %MediaFile{type: "file", mime_type: "video/mp4", data: "TVA0"}} =
             Gather.collect(j, {:path, p})

    assert {:error, %Error{message: "image: cannot read " <> _}} =
             Gather.collect(j, {:path, Path.join(dir, "no.png")})

    small = job(gather: [dir: dir, max_bytes: 2], http_client: seam)

    assert {:error,
            %Error{message: "image: download failed: file exceeds gather.maxBytes (2 bytes)"}} =
             Gather.collect(small, {:url, "http://f/x", "image"})

    assert {:error, %Error{message: "image: file exceeds gather.maxBytes (2 bytes)"}} =
             Gather.collect(small, {:path, p})

    assert {:error, %Error{message: "image: file exceeds gather.maxBytes (2 bytes)"}} =
             Gather.save(small, "abc", "image/png")

    dl = job(gather: [dir: dir], http_client: seam)

    assert {:ok, %MediaFile{path: saved, mime_type: "image/gif"}} =
             Gather.collect(dl, {:url, "http://f/x", "image"})

    assert Path.extname(saved) == ".gif"
    assert {:ok, %MediaFile{path: bin}} = Gather.collect(dl, {:bytes, "x", "application/x-thing"})
    assert Path.extname(bin) == ".bin"

    url = job(gather: [mode: "url", dir: dir])
    assert {:ok, %MediaFile{url: "file://" <> ^p, path: ^p}} = Gather.collect(url, {:path, p})
    assert Gather.type_of_mime("text/plain") == "file"
  end

  test "Req download stops at maxBytes + 1 and inputs report their failures" do
    {srv, base} =
      serve_fn(fn conn ->
        case conn.request_path do
          "/big" -> Plug.Conn.send_resp(conn, 200, String.duplicate("a", 5000))
          "/missing" -> Plug.Conn.send_resp(conn, 404, "no")
          _ -> Plug.Conn.send_resp(conn, 200, "ok")
        end
      end)

    j = job([gather: [max_bytes: 10, dir: tmp_dir("dl")]], base: base)

    assert {:error,
            %Error{message: "image: download failed: file exceeds gather.maxBytes (10 bytes)"}} =
             Gather.collect(j, {:url, base <> "/big", "image"})

    assert {:error, %Error{message: ~s(image: cannot read ") <> rest}} =
             Job.read_input(j, base <> "/missing")

    assert rest =~ "HTTP 404"

    assert {:error, %Error{message: ~s(image: cannot read ") <> _}} =
             Job.read_input(j, base <> "/big")

    assert {:ok, %{filename: "input", content_type: "application/octet-stream"}} =
             Job.read_input(j, base <> "/")

    assert {:error, %Error{message: ~s(image: cannot read "http://127.0.0.1:1/x": ) <> _}} =
             Job.read_input(j, "http://127.0.0.1:1/x")

    ThousandIsland.stop(srv, 500)
  end

  test "providerOptions add and replace multipart fields" do
    parts = [{:field, "model", "a"}, {:file, "file", %{}}]

    assert Job.merge_fields(parts, %{provider_options: %{"model" => "b", "z" => 1}}) ==
             [{:field, "model", "b"}, {:file, "file", %{}}, {:field, "z", 1}]

    assert Job.merge_fields(parts, %{}) == parts
  end

  # ---- built-in clients: edges the fixtures leave out ----

  defp seam_client(fun, mod_fun, config \\ []) do
    me = self()

    seam = fn req ->
      send(me, {:req, req})
      fun.(req)
    end

    Media.create!(apply(Media, mod_fun, [Keyword.merge([key: "K", http_client: seam], config)]))
  end

  test "a2e model override, openai integer job id + timeout, fal no request_id, openrouter URL image" do
    a2e =
      seam_client(fn _ -> {:ok, %{status: 200, body: %{"code" => 0, "data" => [%{}]}}} end, :a2e,
        model: "flux"
      )

    assert Media.image(a2e, prompt: "x") == err("image: no job id at data.0._id")
    assert_receive {:req, %{body: body}}
    assert Jason.decode!(body)["model_type"] == "flux"

    openai =
      seam_client(
        fn req ->
          if req.method == :post,
            do: {:ok, %{status: 200, body: %{"id" => 7}}},
            else: {:ok, %{status: 200, body: %{"status" => "queued"}}}
        end,
        :openai,
        loop: [interval_ms: 1, timeout_ms: 30]
      )

    assert {:error, %Error{status: :timeout, message: "job 7 timed out after 30ms", job_id: "7"}} =
             Media.video(openai, prompt: "x", seconds: 2)

    noid = seam_client(fn _ -> {:ok, %{status: 200, body: %{"x" => 1}}} end, :openai)
    assert Media.video(noid, prompt: "x") == err("video: no job id at id")

    flush()
    fal = seam_client(fn _ -> {:ok, %{status: 200, body: %{}}} end, :fal)
    assert Media.image(fal, prompt: "x", height: 9) == err("image: no job id at request_id")
    assert_receive {:req, %{body: b}}
    assert Jason.decode!(b)["image_size"] == %{"width" => 9, "height" => 9}

    or_ =
      seam_client(
        fn _ ->
          {:ok,
           %{
             status: 200,
             body: %{
               "choices" => [
                 %{"message" => %{"images" => [%{"image_url" => %{"url" => "https://x/i.png"}}]}}
               ]
             }
           }}
        end,
        :openrouter,
        gather: [mode: "url"]
      )

    assert {:ok, %Result{files: [%MediaFile{url: "https://x/i.png"}], text: nil, job_id: nil}} =
             Media.image(or_, prompt: "x")

    for f <- [:openrouter, :fal, :elevenlabs] do
      verb = if f == :elevenlabs, do: :speech, else: :image
      m = Media.create!(apply(Media, f, [[key: nil, url: "http://unused"]]))

      env =
        %{openrouter: "OPENROUTER_API_KEY", fal: "FAL_API_KEY", elevenlabs: "ELEVENLABS_API_KEY"}[
          f
        ]

      if System.get_env(env) in [nil, ""] do
        assert {:error, %Error{status: :unavailable}} =
                 Media.generate(
                   m,
                   verb,
                   %{prompt: "x", text: "x"}
                   |> Map.take([if(verb == :speech, do: :text, else: :prompt)])
                 )
      end
    end
  end

  test "elevenlabs: transcribe / clone with no text field fails; unknown-type voice list" do
    el = seam_client(fn _ -> {:ok, %{status: 200, body: %{"other" => 1}}} end, :elevenlabs)
    dir = tmp_dir("el")
    p = Path.join(dir, "a.wav")
    File.write!(p, "W")
    assert Media.transcribe(el, audio: p) == err("transcribe: no result at text")

    assert Media.clone_voice(el, name: "n", samples: [p]) ==
             err("cloneVoice: no result at voice_id")

    assert {:error, %Error{message: ~s(cloneVoice: cannot read ) <> _}} =
             Media.clone_voice(el, name: "n", samples: [p <> "x"])

    assert Media.speech(el, text: "t", voice: "some voice") ==
             err(~s(speech: voice "some voice" not found))
  end

  # ---- CLI client edges ----

  @fake Path.expand("../../conformance/media/fake-cli/fake-cli.sh", __DIR__)

  defp fake_cli(lines, client \\ :grok, config \\ []) do
    out = tmp_dir("cli-out")
    fixture = Path.join(out, "fixture.jsonl")
    File.write!(fixture, Enum.map_join(lines, "", &(&1 <> "\n")))

    config =
      Keyword.merge(
        [
          command: ["/bin/sh", @fake, fixture, Path.join(out, "o")],
          gather: [dir: tmp_dir("cli-dir")]
        ],
        config
      )

    {Media.create!(apply(Media, client, [config])), out}
  end

  test "CLI: cooperative cancellation kills the run; string command; missing executable on PATH" do
    {media, _} = fake_cli([~s({"type":"text","data":"x"}), "#hang"])
    t0 = System.monotonic_time(:millisecond)
    flag = fn -> System.monotonic_time(:millisecond) - t0 > 100 end

    assert_raise Media.Cancelled, fn -> Media.image(media, %{prompt: "x"}, signal: flag) end
    assert System.monotonic_time(:millisecond) - t0 < 3000

    m = Media.create!(Media.codex(command: "toolnexus-no-such-cli-xyz"))
    assert {:error, %Error{status: :unavailable, message: m1}} = Media.image(m, prompt: "x")
    assert m1 =~ ~s(codex: cannot start "toolnexus-no-such-cli-xyz")

    m = Media.create!(Media.grok(command: [Path.expand("../priv/media/verbs.json", __DIR__)]))
    assert {:error, %Error{status: :unavailable}} = Media.image(m, prompt: "x")
  end

  test "CLI: inline gather, codex default folder, codex error without message, grok error kinds" do
    {media, out} =
      fake_cli(
        [
          "#file a.png PNG",
          ~s({"type":"text","data":"{out}/a.png"}),
          ~s({"type":"end","stopReason":"x"})
        ],
        :grok,
        gather: [mode: "inline"]
      )

    assert {:ok, %Result{files: [%MediaFile{data: "UE5H", mime_type: "image/png"}]}} =
             Media.image(media, prompt: "x")

    assert File.exists?(Path.join([out, "o", "a.png"]))

    saved = System.get_env("CODEX_HOME")
    System.delete_env("CODEX_HOME")
    on_exit(fn -> if saved, do: System.put_env("CODEX_HOME", saved) end)

    assert Toolnexus.Media.Codex.folder("t") ==
             Path.join([System.user_home!(), ".codex", "generated_images", "t"])

    {media, _} =
      fake_cli(
        [
          ~s({"type":"thread.started","thread_id":"zz-none"}),
          ~s({"type":"error","x":1}),
          ~s({"type":"item.completed","item":{"type":"other"}})
        ],
        :codex
      )

    assert {:error, %Error{status: :failed, message: ~s(codex: no image: {"type":"error","x":1})}} =
             Media.image(media, prompt: "x")

    {media, _} = fake_cli([~s({"type":"turn.failed","message":"over quota"})], :codex)

    assert {:error,
            %Error{
              status: :unavailable,
              message: ~s(media: no client available for "image": codex: over quota)
            }} = Media.image(media, prompt: "x")

    {media, _} =
      fake_cli([~s({"type":"tool_call_update","status":"in_progress"}), ~s([1]), "#exit 4"])

    assert Media.image(media, prompt: "x") ==
             {:error,
              %Error{status: :failed, message: "grok: no image file in the run (exit code 4)"}}

    assert Media.image(media, prompt: "x", seed: 3) ==
             unavailable(
               ~s(media: no client available for "image": grok: argument "seed" not supported)
             )
  end

  # ---- toolkit + RunResult.files (§8) + model visibility (§7G.6) ----

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
    id = r.files |> hd() |> Map.get(:call_id)
    assert is_binary(id)

    assert r.files == [
             %{
               type: "image",
               mime_type: "image/png",
               url: "https://x/a.png",
               from: "generate_image",
               call_id: id
             },
             %{
               type: "image",
               mime_type: "image/jpeg",
               url: "https://x/b.jpg",
               from: "generate_image",
               call_id: id
             }
           ]

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

  test "§7G.6: only image parts of a media result reach the model; video/audio stay in files" do
    defmodule Mixed do
      @moduledoc false
      def name(_), do: "mixed"
      def supports?(_, v), do: v == "video"

      def video(_, _, _) do
        {:ok,
         %Result{
           files: [
             %MediaFile{type: "file", mime_type: "video/mp4", url: "https://x/v.mp4"},
             %MediaFile{type: "image", mime_type: "image/png", url: "https://x/still.png"},
             %MediaFile{type: "audio", mime_type: "audio/mpeg", data: "QQ=="}
           ]
         }}
      end
    end

    tk = Toolnexus.create_toolkit!(media: Media.create!(Mixed), builtins: false)
    {:ok, turn} = Agent.start_link(fn -> 0 end)
    me = self()

    transport = fn req ->
      send(me, {:wire, req.body})

      body =
        if Agent.get_and_update(turn, &{&1, &1 + 1}) == 0,
          do: %{
            "choices" => [
              %{
                "message" => %{
                  "tool_calls" => [
                    %{
                      "id" => "c1",
                      "type" => "function",
                      "function" => %{
                        "name" => "generate_video",
                        "arguments" => ~s({"prompt":"p"})
                      }
                    }
                  ]
                }
              }
            ]
          },
          else: %{"choices" => [%{"message" => %{"content" => "ok"}}]}

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

    r = Client.run(client, "go", tk)
    assert Enum.map(r.files, & &1.type) == ["file", "image", "audio"]
    assert_receive {:wire, _}
    assert_receive {:wire, second}
    wire = if is_binary(second), do: Jason.decode!(second), else: second
    relocated = List.last(wire["messages"])
    assert relocated["role"] == "user"

    assert relocated["content"] == [
             %{"type" => "text", "text" => "Output of tool generate_video (c1):"},
             %{"type" => "image_url", "image_url" => %{"url" => "https://x/still.png"}}
           ]

    refute Jason.encode!(wire["messages"]) =~ "QQ=="

    assert Client.model_parts(%ToolResult{parts: [%{type: "file"}], metadata: %{source: "other"}}) ==
             [%{type: "file"}]
  end

  # D9 (SPEC §7G.12): a media result with no image part sends the plain output string as
  # the tool-result content in every message style the port has (openai, anthropic) — no
  # empty content array, no placeholder, no relocated message.
  for style <- ["openai", "anthropic"] do
    @style style
    test "D9 #{style}: a media result with modelParts [] sends the plain output string" do
      defmodule Module.concat(__MODULE__, "NoImage#{String.capitalize(@style)}") do
        @moduledoc false
        def name(_), do: "noimg"
        def supports?(_, v), do: v == "speech"

        def speech(_, _, _) do
          {:ok,
           %Result{
             files: [
               %MediaFile{type: "audio", mime_type: "audio/mpeg", url: "https://x/a.mp3"},
               %MediaFile{type: "file", mime_type: "video/mp4", data: "QQ=="}
             ]
           }}
        end
      end

      mod = Module.concat(__MODULE__, "NoImage#{String.capitalize(@style)}")
      tk = Toolnexus.create_toolkit!(media: Media.create!(mod), builtins: false)
      [tool] = Toolnexus.Toolkit.tools(tk)
      direct = tool.execute.(%{"text" => "hi"}, %Context{})
      assert Client.model_parts(direct) == []
      {:ok, turn} = Agent.start_link(fn -> 0 end)
      me = self()

      first =
        case @style do
          "openai" ->
            %{
              "choices" => [
                %{
                  "message" => %{
                    "tool_calls" => [
                      %{
                        "id" => "c1",
                        "type" => "function",
                        "function" => %{"name" => tool.name, "arguments" => ~s({"text":"hi"})}
                      }
                    ]
                  }
                }
              ]
            }

          "anthropic" ->
            %{
              "content" => [
                %{
                  "type" => "tool_use",
                  "id" => "c1",
                  "name" => tool.name,
                  "input" => %{"text" => "hi"}
                }
              ],
              "stop_reason" => "tool_use",
              "usage" => %{}
            }
        end

      done =
        if @style == "openai",
          do: %{"choices" => [%{"message" => %{"content" => "ok"}}]},
          else: %{"content" => [%{"type" => "text", "text" => "ok"}], "usage" => %{}}

      transport = fn req ->
        send(me, {:wire, req.body})
        body = if Agent.get_and_update(turn, &{&1, &1 + 1}) == 0, do: first, else: done
        {:ok, %{status: 200, headers: %{}, body: body}}
      end

      client =
        Client.create(
          base_url: "http://t.invalid",
          style: @style,
          model: "m",
          api_key: "k",
          transport: transport
        )

      r = Client.run(client, "go", tk)
      assert r.text == "ok"
      assert_receive {:wire, _}
      assert_receive {:wire, second}
      wire = if is_binary(second), do: Jason.decode!(second), else: second
      last = List.last(wire["messages"])

      case @style do
        "openai" ->
          assert last == %{"role" => "tool", "tool_call_id" => "c1", "content" => direct.output}

        "anthropic" ->
          assert last["role"] == "user"

          assert [%{"type" => "tool_result", "tool_use_id" => "c1", "content" => content}] =
                   last["content"]

          assert content == direct.output and is_binary(content) and content != ""
      end

      refute Jason.encode!(wire["messages"]) =~ "QQ=="
    end
  end

  test "all seven media tools are concurrency-safe" do
    verbs =
      for v <- ~w(image video speech music transcribe cloneVoice talk), into: %{} do
        {v,
         %{
           "mode" => "sync",
           "kind" => "image",
           "start" => %{"path" => "/x"},
           "args" => %{},
           "resultPath" => "u"
         }}
      end

    tools =
      Media.tools(
        Media.create!(Media.style_client!(%{"baseUrl" => "http://unused", "verbs" => verbs}))
      )

    assert length(tools) == 7
    for t <- tools, do: assert(Toolnexus.ToolScheduling.concurrency_safe?(t, %{}), t.name)
  end

  # ---- §7G.8 transport edges ----

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

  defp style_media(style, config \\ []), do: Media.create!(Media.style_client!(style, config))

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

    assert {:ok, %Result{}} =
             Media.image(style_media(sync_style(a_url), http_options: [redirect: true]),
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
    style = sync_style(url, %{"poll" => %{"timeoutMs" => 1000}})

    assert {:error, %Error{status: :timeout, message: "image: timed out after 1000ms"}} =
             Media.image(style_media(style), prompt: "x")

    assert System.monotonic_time(:millisecond) - t0 < 2_500

    t0 = System.monotonic_time(:millisecond)

    media =
      style_media(style,
        http_options: [receive_timeout: 60_000, connect_options: [protocols: [:http1]]]
      )

    assert {:error, %Error{message: "image: timed out after 1000ms"}} =
             Media.image(media, prompt: "x")

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

    assert {:error, %Error{message: "job h1 timed out after 1000ms"}} =
             Media.image(style_media(async), prompt: "x")

    try do
      ThousandIsland.stop(srv, 100)
    catch
      :exit, _ -> :ok
    end
  end

  test "§7G: the http_client seam sees the remaining deadline and its timeout maps to the message" do
    me = self()

    seam = fn req ->
      send(me, {:rt, req.receive_timeout})
      {:error, %{reason: :timeout}}
    end

    style = sync_style("http://unused", %{"poll" => %{"timeoutMs" => 50}})

    assert {:error, %Error{message: "image: timed out after 50ms"}} =
             Media.image(style_media(style, http_client: seam), prompt: "x")

    assert_received {:rt, rt} when is_integer(rt) and rt <= 50

    assert Media.image(style_media(style, http_client: fn _ -> {:error, "boom"} end), prompt: "x") ==
             err(~s(image: "boom"))
  end

  test "§7G Redirects via the seam: relative Location, header shapes, no-Location and 300 are errors" do
    me = self()

    seam = fn req ->
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
             Media.image(style_media(sync_style("http://a"), http_client: seam), prompt: "y")

    assert_received {:req, %{method: :post, url: "http://a/i"}}
    assert_received {:req, %{method: :post, url: "http://a/two", headers: %{"X-Style" => "s"}}}
    assert_received {:req, %{method: :get, url: "http://b/x", body: nil, headers: h}}
    # Cross-origin hop: no client headers, only the default User-Agent (D23).
    assert h == %{"User-Agent" => Toolnexus.Media.Job.user_agent()}

    for {st, hs} <- [{302, %{}}, {300, %{"location" => "http://a/z"}}, {302, nil}] do
      t = fn _ -> {:ok, %{status: st, headers: hs, body: "nope"}} end

      assert Media.image(style_media(sync_style("http://a"), http_client: t), prompt: "y") ==
               err("HTTP #{st}: nope")
    end
  end

  test "agy: parse branches, argv with model, presets and one-shot errors" do
    alias Toolnexus.Media.Agy

    assert Agy.parse(%{"event" => "step_update", "step_update" => %{"tool_name" => "x"}}, "") ==
             %{}

    assert Agy.parse(%{"event" => "error", "message" => "boom"}, "") == %{error: "boom"}
    assert Agy.parse(%{"event" => "other"}, "") == %{}

    r = fn res -> Agy.parse(%{"event" => "result", "result" => res}, "")[:error] end
    assert r.(%{"status" => "ERROR", "error" => "e1"}) == "e1"
    assert r.(%{"status" => "ERROR", "error" => %{"message" => "e2"}}) == "e2"
    assert r.(%{"status" => "ERROR"}) == ~s({"status":"ERROR"})
    assert r.(%{"status" => "SUCCESS"}) == nil
    assert Agy.unavailable("other", "image") == :no
    assert Agy.argv("/c", "p", "image", "m") |> Enum.take(-2) == ["--model", "m"]
    assert Media.agy().config.model == nil
    assert String.starts_with?(Agy.folder("c9"), System.user_home!())

    assert [Media.xai_grok_cli(), Media.openai_codex_cli(), Media.google_agy_cli()] ==
             ["xai-grok-cli", "openai-codex-cli", "google-agy-cli"]

    assert [Media.xai_grok_cli_mock(), Media.openai_codex_cli_mock(), Media.google_agy_cli_mock()] ==
             ["xai-grok-cli-mock", "openai-codex-cli-mock", "google-agy-cli-mock"]

    assert {:error, %Error{}} = Media.generate_media("/nonexistent/style.json", "image", %{})
    {:ok, s} = Media.load_style(Media.openai_codex_cli_mock())
    assert Media.Style.name(s) == "codex"

    assert {:error, %Error{message: "video: not implemented by codex"}} =
             Media.Style.video(s, %{}, %{})

    assert Toolnexus.Media.CliClient.fold_prompt("image", %{"prompt" => "a", "width" => 2.0}) ==
             "a (width 2 px)"
  end

  test "available: key from config, string command, grok/agy auth files; gather slug" do
    style = %Media.Style{label: "k", style: %{"headers" => %{"A" => "${TN_NEVER_SET_X}"}}}

    assert Media.available(%{style | config: Media.Config.new(key: "YOUR_KEY_HERE")}).reason ==
             "k: key from config"

    {:ok, g} = Media.load_style(Media.xai_grok_cli(), command: "/nonexistent/g")
    assert Media.available(g).reason == ~s(grok: "/nonexistent/g" not found on PATH)
    {:ok, a} = Media.load_style(Media.google_agy_cli(), command: ["/bin/sh"])
    assert Media.available(a).reason =~ "antigravity-oauth-token"
    {:ok, g} = Media.load_style(Media.xai_grok_cli(), command: ["sh"])
    assert Media.available(g).reason =~ ".grok/auth.json"
    assert Media.Gather.slug("..-a b") == "a-b"
    assert Media.Gather.slug(nil) == ""
  end
end
