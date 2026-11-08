defmodule Toolnexus.Media.CliClient do
  @moduledoc """
  The CLI base (SPEC §7G.10): run a local subscription CLI **once, headless**, read its
  JSON-lines stdout, collect files, and kill it on cancel or deadline. `Toolnexus.Media.Grok`
  and `Toolnexus.Media.Codex` are built on it; a CLI client accepts `prompt` only.

  A CLI is described by a module implementing the callbacks below.
  """

  alias Toolnexus.Media.{Cancelled, Config, Error, Gather, Job, Result}

  @doc "Client name (`\"grok\"`)."
  @callback cli_name() :: String.t()
  @doc "Default executable."
  @callback default_command() :: String.t()
  @doc "Verbs the CLI serves."
  @callback verbs() :: [String.t()]
  @doc "Prompt template for a verb; `{prompt}` is replaced by the folded prompt."
  @callback template(verb :: String.t()) :: String.t()
  @doc "Client argv after the command (`prompt` is the full templated prompt)."
  @callback argv(
              cwd :: String.t(),
              prompt :: String.t(),
              verb :: String.t(),
              model :: String.t() | nil
            ) ::
              [String.t()]
  @doc "Fixed env for the child."
  @callback env() :: [{String.t(), String.t()}]
  @doc "Parse one JSON object line ⇒ `%{session, text, paths, error, status}` (all optional)."
  @callback parse(map(), raw :: String.t()) :: map()
  @doc "Folder whose entries are candidate files for a session, or `nil`."
  @callback folder(session :: String.t()) :: String.t() | nil
  @doc "Unavailable matcher on the last error: `{:unavailable, message}` or `:no`."
  @callback unavailable(error :: String.t(), verb :: String.t()) ::
              {:unavailable, String.t()} | :no

  @line_max 64 * 1024 * 1024
  @path_re ~r{/[^\s"'()\[\]]+\.(?:png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)}i

  @doc false
  def supports?(mod, verb), do: verb in mod.verbs()

  @doc false
  def run(mod, %Config{} = config, verb, args, ctx, mock \\ false) do
    name = mod.cli_name()
    timeout = Config.positive(config.loop.timeout_ms, 600_000)
    job = Job.new(name, verb, config, ctx, timeout_ms: timeout, default_mode: "download")
    prompt = String.replace(mod.template(verb), "{prompt}", fold_prompt(verb, args))

    if mock, do: mock_run(job, config, verb, prompt), else: spawn_run(mod, job, config, prompt)
  end

  # §7G CLI mock presets: no process. Writes "<gather.dir>/<client>-mock-<verb>.<png|mp4>"
  # holding "toolnexus mock <verb>", gathers it like a CLI file; text = the full prompt.
  defp mock_run(job, config, verb, prompt) do
    ext = if verb == "video", do: ".mp4", else: ".png"
    dir = config.gather.dir || Path.join(System.tmp_dir!(), "toolnexus-media")
    path = Path.expand(Path.join(dir, "#{job.name}-mock-#{verb}#{ext}"))

    with :ok <- File.mkdir_p(dir),
         :ok <- File.write(path, "toolnexus mock " <> verb),
         {:ok, f} <- Gather.collect(job, {:path, path}) do
      {:ok, %Result{files: [f], text: prompt, job_id: "mock", status: "completed"}}
    else
      {:error, %Error{}} = err -> err
      {:error, reason} -> {:error, Job.fail(job, "#{job.name}: #{:file.format_error(reason)}")}
    end
  end

  @hints [
    {"width", "width ", " px"},
    {"height", "height ", " px"},
    {"seconds", "about ", " seconds long"},
    {"aspectRatio", "aspect ratio ", ""}
  ]

  @doc """
  The prompt a CLI style receives (§7G CLI hint folding): `prompt`, then the present size
  hints in canonical verb order as `" (h1, h2)"`; nothing appended when none is present.
  """
  @spec fold_prompt(String.t(), map()) :: String.t()
  def fold_prompt(verb, args) do
    hints =
      for a <- Toolnexus.Media.canonical_args(verb),
          {name, pre, post} <- @hints,
          name == a.name,
          v = args[name],
          v != nil,
          do: pre <> Toolnexus.Media.StyleClient.render(v) <> post

    prompt = Toolnexus.Media.StyleClient.render(args["prompt"] || "")
    if hints == [], do: prompt, else: prompt <> " (" <> Enum.join(hints, ", ") <> ")"
  end

  defp spawn_run(mod, job, config, prompt) do
    name = job.name

    command =
      case config.command do
        nil -> [mod.default_command()]
        s when is_binary(s) -> [s]
        l when is_list(l) and l != [] -> Enum.map(l, &to_string/1)
      end

    [exe | rest] = command

    case resolve(exe) do
      nil ->
        {:error, Error.unavailable(~s(#{name}: cannot start "#{exe}"))}

      path ->
        cwd = Path.join(System.tmp_dir!(), "toolnexus-cli-#{System.unique_integer([:positive])}")
        File.mkdir_p!(cwd)
        # D17: the CLI gets the realpath (macOS /var ⇒ /private/var).
        cwd = realpath(cwd)

        try do
          argv = rest ++ mod.argv(cwd, prompt, job.verb, config.model)
          spawn_and_collect(mod, job, path, argv, cwd)
        after
          File.rm_rf(cwd)
        end
    end
  end

  defp realpath(dir) do
    case System.cmd("pwd", ["-P"], cd: dir, stderr_to_stdout: true) do
      {out, 0} -> String.trim_trailing(out, "\n")
      _ -> dir
    end
  end

  defp resolve(exe) do
    if String.contains?(exe, "/") do
      case File.stat(exe) do
        {:ok, %File.Stat{type: :regular, mode: mode}} when Bitwise.band(mode, 0o111) != 0 -> exe
        _ -> nil
      end
    else
      System.find_executable(exe)
    end
  end

  defp spawn_and_collect(mod, job, path, argv, cwd) do
    # /bin/sh execs the command in place (same pid, so a kill reaches it) with stdin
    # from /dev/null and stderr discarded (§7G.10 step 1).
    port =
      try do
        Port.open({:spawn_executable, "/bin/sh"}, [
          :binary,
          :exit_status,
          {:line, @line_max},
          {:cd, cwd},
          {:env, Enum.map(mod.env(), fn {k, v} -> {to_charlist(k), to_charlist(v)} end)},
          {:args, ["-c", "exec \"$@\" </dev/null 2>/dev/null", "sh", path | argv]}
        ])
      rescue
        _ -> nil
      end

    if port == nil do
      {:error, Error.unavailable(~s(#{job.name}: cannot start "#{path}"))}
    else
      state = %{session: nil, text: "", paths: [], error: nil, usage: nil, buf: ""}

      case loop(mod, job, port, state) do
        {:exit, code, st} -> finish(mod, job, code, st)
        {:killed, :timeout} -> {:error, Job.timeout_error(%{job | job_id: nil, verb: job.name})}
        {:killed, :cancelled} -> raise Cancelled
      end
    end
  end

  defp loop(mod, job, port, st) do
    wait = min(50, max(job.deadline - System.monotonic_time(:millisecond), 0))

    receive do
      {^port, {:data, {:noeol, chunk}}} ->
        loop(mod, job, port, %{st | buf: st.buf <> chunk})

      {^port, {:data, {:eol, chunk}}} ->
        st = line(mod, job, st.buf <> chunk, %{st | buf: ""})
        loop(mod, job, port, st)

      {^port, {:exit_status, code}} ->
        st = if st.buf != "", do: line(mod, job, st.buf, %{st | buf: ""}), else: st
        {:exit, code, st}
    after
      wait ->
        cond do
          Job.cancelled?(job) -> kill(port, :cancelled)
          System.monotonic_time(:millisecond) >= job.deadline -> kill(port, :timeout)
          true -> loop(mod, job, port, st)
        end
    end
  end

  defp kill(port, why) do
    case Port.info(port, :os_pid) do
      {:os_pid, pid} ->
        System.cmd("kill", ["-KILL", Integer.to_string(pid)], stderr_to_stdout: true)

      _ ->
        :ok
    end

    try do
      Port.close(port)
    rescue
      _ -> :ok
    end

    flush(port)
    {:killed, why}
  end

  defp flush(port) do
    receive do
      {^port, _} -> flush(port)
    after
      0 -> :ok
    end
  end

  defp line(mod, job, raw, st) do
    case Jason.decode(raw) do
      {:ok, %{} = obj} ->
        ev = mod.parse(obj, raw)
        if s = ev[:status], do: Job.progress(%{job | job_id: ev[:session] || st.session}, s)

        %{
          st
          | session: ev[:session] || st.session,
            text: st.text <> (ev[:text] || ""),
            paths: st.paths ++ (ev[:paths] || []),
            error: ev[:error] || st.error,
            usage: ev[:usage] || st.usage
        }

      _ ->
        st
    end
  end

  defp finish(mod, job, code, st) do
    job = %{job | job_id: st.session}

    folder =
      case st.session && mod.folder(st.session) do
        dir when is_binary(dir) ->
          case File.ls(dir) do
            {:ok, names} -> names |> Enum.sort() |> Enum.map(&Path.join(dir, &1))
            _ -> []
          end

        _ ->
          []
      end

    scanned = @path_re |> Regex.scan(st.text) |> Enum.map(&hd/1)
    want = if job.verb == "video", do: "video/", else: "image/"

    kept =
      (st.paths ++ scanned ++ folder)
      |> Enum.uniq()
      |> Enum.filter(&File.regular?/1)
      |> Enum.filter(&String.starts_with?(Gather.mime_of(&1) || "", want))

    cond do
      kept != [] ->
        Enum.reduce_while(kept, {:ok, []}, fn p, {:ok, acc} ->
          case Gather.collect(job, {:path, p}) do
            {:ok, f} -> {:cont, {:ok, acc ++ [f]}}
            err -> {:halt, err}
          end
        end)
        |> case do
          {:ok, files} ->
            text = if st.text == "", do: nil, else: st.text

            {:ok,
             %Result{
               files: files,
               text: text,
               job_id: st.session,
               status: "completed",
               usage: st.usage
             }}

          err ->
            err
        end

      is_binary(st.error) ->
        case mod.unavailable(st.error, job.verb) do
          {:unavailable, msg} ->
            {:error, %Error{message: msg, status: :unavailable, job_id: st.session}}

          :no ->
            {:error, Job.fail(job, "#{job.name}: no #{job.verb}: #{Job.cap(st.error, 400)}")}
        end

      true ->
        tail = if st.text == "", do: "", else: ": " <> Job.cap(st.text, 300)

        {:error,
         Job.fail(job, "#{job.name}: no #{job.verb} file in the run (exit code #{code})#{tail}")}
    end
  end

  @doc false
  def prompt_args("video"),
    do: [
      %{name: "prompt", required: true},
      %{name: "seconds", required: false},
      %{name: "aspectRatio", required: false}
    ]

  def prompt_args(_verb),
    do: [
      %{name: "prompt", required: true},
      %{name: "width", required: false},
      %{name: "height", required: false},
      %{name: "aspectRatio", required: false}
    ]
end

defmodule Toolnexus.Media.Grok do
  @moduledoc """
  Grok CLI client (SPEC §7G.10): `grok -p … --output-format streaming-json --yolo`, Grok
  subscription login. Image and video; the file path comes from the completed tool
  result. Never edits the user's Grok settings — under Zero Data Retention video returns
  `:unavailable` so `create/1` falls through to the next video client
  (docs/media#grok-video-zdr).
  """
  @behaviour Toolnexus.Media.Client
  @behaviour Toolnexus.Media.CliClient

  alias Toolnexus.Media.{CliClient, Config, Job}

  defstruct config: %Config{}, mock: false

  @doc "Build the client (`model`, `command`, `loop`, `gather` from §7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl Toolnexus.Media.Client
  def name(_), do: "grok"
  @impl Toolnexus.Media.Client
  def supports?(_, verb), do: CliClient.supports?(__MODULE__, verb)
  @impl Toolnexus.Media.Client
  def args(_, verb), do: CliClient.prompt_args(verb)
  @impl Toolnexus.Media.Client
  def image(c, args, ctx), do: CliClient.run(__MODULE__, c.config, "image", args, ctx, c.mock)
  @impl Toolnexus.Media.Client
  def video(c, args, ctx), do: CliClient.run(__MODULE__, c.config, "video", args, ctx, c.mock)

  @templates %{
    "image" =>
      "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path.",
    "video" =>
      "Generate exactly one short video with your video generation tool, shortest duration available: {prompt}. Do not write code. Reply only with the file path."
  }

  @impl CliClient
  def cli_name, do: "grok"
  @impl CliClient
  def default_command, do: "grok"
  @impl CliClient
  def verbs, do: ["image", "video"]

  @impl CliClient
  def template(verb), do: @templates[verb]

  @impl CliClient
  def argv(cwd, prompt, _verb, model) do
    [
      "-p",
      prompt,
      "--cwd",
      cwd,
      "--output-format",
      "streaming-json",
      "--yolo",
      "--no-auto-update"
    ] ++
      if(model, do: ["-m", model], else: [])
  end

  @impl CliClient
  def env, do: [{"GROK_CONFIG", ~s({"features":{"image_gen":true,"video_gen":true}})}]

  @impl CliClient
  def parse(%{"type" => "text", "data" => d}, _raw) when is_binary(d), do: %{text: d}

  def parse(%{"type" => "tool_call", "toolName" => t}, _raw) when is_binary(t),
    do: %{status: "tool " <> t}

  def parse(%{"type" => "tool_call_update", "status" => s} = e, _raw)
      when is_binary(s) and s != "" do
    raw_out = e["rawOutput"]
    base = %{status: "tool " <> s}

    case s do
      "completed" ->
        case raw_out do
          %{"path" => p} when is_binary(p) and p != "" -> Map.put(base, :paths, [p])
          _ -> base
        end

      "failed" ->
        msg =
          case raw_out do
            %{"message" => m} when is_binary(m) -> m
            other -> Jason.encode!(other)
          end

        Map.put(base, :error, msg)

      _ ->
        base
    end
  end

  def parse(%{"type" => "end"} = e, _raw) do
    %{
      session: if(is_binary(e["sessionId"]), do: e["sessionId"]),
      status: "end #{e["stopReason"]}",
      # §7G.10 / D21: usage from the end event; ticks unit undocumented ⇒ raw only.
      usage: grok_usage(e)
    }
  end

  def parse(%{"type" => "error", "message" => m}, _raw) when is_binary(m), do: %{error: m}
  def parse(_, _raw), do: %{}

  defp grok_usage(e) do
    u = if is_map(e["usage"]), do: e["usage"], else: %{}

    m =
      [
        {"inputTokens", u["input_tokens"]},
        {"outputTokens", u["output_tokens"]},
        {"costUsd", e["total_cost_usd"]}
      ]
      |> Enum.filter(fn {_, v} -> is_number(v) end)
      |> Map.new()

    m =
      if is_number(e["total_cost_usd_ticks"]),
        do: Map.put(m, "raw", %{"total_cost_usd_ticks" => e["total_cost_usd_ticks"]}),
        else: m

    if m == %{}, do: nil, else: m
  end

  @impl CliClient
  def folder(_session), do: nil

  @impl CliClient
  def unavailable(error, verb) do
    down = String.downcase(error)

    cond do
      String.contains?(down, "zero data retention") ->
        {:unavailable,
         "grok: #{verb} blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)"}

      String.contains?(down, "usage limit") ->
        {:unavailable, "grok: " <> Job.cap(error, 400)}

      true ->
        :no
    end
  end
end

defmodule Toolnexus.Media.Codex do
  @moduledoc """
  Codex CLI client (SPEC §7G.10): `codex exec --json`, ChatGPT plan. Image only; the
  file comes from the reply text or `$CODEX_HOME/generated_images/<thread>`.
  """
  @behaviour Toolnexus.Media.Client
  @behaviour Toolnexus.Media.CliClient

  alias Toolnexus.Media.{CliClient, Config, Job}

  defstruct config: %Config{}, mock: false

  @doc "Build the client (`model`, `command`, `loop`, `gather` from §7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl Toolnexus.Media.Client
  def name(_), do: "codex"
  @impl Toolnexus.Media.Client
  def supports?(_, verb), do: CliClient.supports?(__MODULE__, verb)
  @impl Toolnexus.Media.Client
  def args(_, verb), do: CliClient.prompt_args(verb)
  @impl Toolnexus.Media.Client
  def image(c, args, ctx), do: CliClient.run(__MODULE__, c.config, "image", args, ctx, c.mock)

  @template "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved."

  @impl CliClient
  def cli_name, do: "codex"
  @impl CliClient
  def default_command, do: "codex"
  @impl CliClient
  def verbs, do: ["image"]

  @impl CliClient
  def template(_verb), do: @template

  @impl CliClient
  def argv(_cwd, prompt, _verb, model) do
    ["exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"] ++
      if(model, do: ["-m", model], else: []) ++ [prompt]
  end

  @impl CliClient
  def env, do: []

  @impl CliClient
  def parse(%{"type" => type} = e, raw) do
    base = %{status: to_string(type)}

    case type do
      "thread.started" ->
        if is_binary(e["thread_id"]), do: Map.put(base, :session, e["thread_id"]), else: base

      "item.completed" ->
        case e["item"] do
          %{"type" => "agent_message", "text" => t} when is_binary(t) -> Map.put(base, :text, t)
          _ -> base
        end

      t when t in ["error", "turn.failed"] ->
        msg =
          cond do
            is_binary(e["message"]) -> e["message"]
            is_binary(get_in(e, ["error", "message"])) -> e["error"]["message"]
            true -> raw
          end

        Map.put(base, :error, msg)

      _ ->
        base
    end
  end

  def parse(_, _raw), do: %{}

  @impl CliClient
  def folder(session) do
    home =
      case System.get_env("CODEX_HOME") do
        h when is_binary(h) and h != "" -> h
        _ -> Path.join(System.user_home!(), ".codex")
      end

    Path.join([home, "generated_images", session])
  end

  @impl CliClient
  def unavailable(error, _verb) do
    down = String.downcase(error)

    if String.contains?(down, "usage limit") or String.contains?(down, "quota"),
      do: {:unavailable, "codex: " <> Job.cap(error, 400)},
      else: :no
  end
end

defmodule Toolnexus.Media.Agy do
  @moduledoc """
  Google Antigravity CLI client (label `"agy"`, image only): `agy -p … --output-format
  stream-json --dangerously-skip-permissions`. The path is in the final result's response
  text, else the newest image in `~/.gemini/antigravity-cli/brain/<conversation>`.
  Load it with `Toolnexus.Media.load_style("google-agy-cli")`.
  """
  @behaviour Toolnexus.Media.Client
  @behaviour Toolnexus.Media.CliClient

  alias Toolnexus.Media.{CliClient, Config, Job}

  defstruct config: %Config{}, mock: false

  @doc "Build the client (`model`, `command`, `loop`, `gather` from §7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl Toolnexus.Media.Client
  def name(_), do: "agy"
  @impl Toolnexus.Media.Client
  def supports?(_, verb), do: CliClient.supports?(__MODULE__, verb)
  @impl Toolnexus.Media.Client
  def args(_, verb), do: CliClient.prompt_args(verb)
  @impl Toolnexus.Media.Client
  def image(c, args, ctx), do: CliClient.run(__MODULE__, c.config, "image", args, ctx, c.mock)

  @impl CliClient
  def cli_name, do: "agy"
  @impl CliClient
  def default_command, do: "agy"
  @impl CliClient
  def verbs, do: ["image"]

  @impl CliClient
  def template(_verb),
    do:
      "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the absolute path where the image was saved."

  @impl CliClient
  def argv(_cwd, prompt, _verb, model) do
    ["-p", prompt, "--output-format", "stream-json", "--dangerously-skip-permissions"] ++
      if(model, do: ["--model", model], else: [])
  end

  @impl CliClient
  def env, do: []

  @impl CliClient
  def parse(%{"event" => "init"} = e, _raw),
    do: %{session: str(e["conversation_id"]), status: "init"}

  def parse(%{"event" => "step_update", "step_update" => %{} = u}, _raw) do
    with t when is_binary(t) and t != "" <- u["tool_name"],
         s when is_binary(s) and s != "" <- u["state"] do
      %{status: "tool #{t} #{s}"}
    else
      _ -> %{}
    end
  end

  def parse(%{"event" => "result"} = e, _raw) do
    r = if is_map(e["result"]), do: e["result"], else: %{}
    text = str(r["response"])
    status = str(r["status"]) || ""

    error =
      cond do
        # Live: agy reports an exhausted image quota in a SUCCESS result's text.
        status == "SUCCESS" and quota?(text) -> text
        status == "SUCCESS" -> nil
        is_binary(r["error"]) and r["error"] != "" -> r["error"]
        is_binary(get_in(r, ["error", "message"])) -> r["error"]["message"]
        text not in [nil, ""] -> text
        true -> Jason.encode!(e["result"])
      end

    u = if is_map(r["usage"]), do: r["usage"], else: %{}

    usage =
      [{"inputTokens", u["input_tokens"]}, {"outputTokens", u["output_tokens"]}]
      |> Enum.filter(fn {_, v} -> is_number(v) end)
      |> Map.new()

    %{
      session: str(r["conversation_id"]),
      text: text,
      status: "result " <> status,
      error: error,
      usage: if(usage == %{}, do: nil, else: usage)
    }
  end

  def parse(%{"event" => "error", "message" => m}, _raw) when is_binary(m), do: %{error: m}
  def parse(_, _raw), do: %{}

  defp quota?(nil), do: false

  defp quota?(text) do
    low = String.downcase(text)
    String.contains?(low, "quota") or String.contains?(low, "resource_exhausted")
  end

  defp str(s) when is_binary(s) and s != "", do: s
  defp str(_), do: nil

  @impl CliClient
  def folder(session),
    do: Path.join([System.user_home!(), ".gemini", "antigravity-cli", "brain", session])

  @impl CliClient
  def unavailable(error, _verb) do
    down = String.downcase(error)

    if String.contains?(down, "quota") or String.contains?(down, "usage limit"),
      do: {:unavailable, "agy: " <> Job.cap(error, 400)},
      else: :no
  end
end
