defmodule Toolnexus.Cli do
  @moduledoc """
  CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has
  installed — and pays for — becomes the model behind an ordinary client,
  selected by a closed set of atoms, never by a string.

      {:ok, cli} =
        Toolnexus.Cli.create_client(style: :copilot, command: "copilot", toolkit: tk)

      result = Toolnexus.Cli.run(cli, "What is my badge number?", tk)
      Toolnexus.Cli.close(cli)

  Two transports, one constructor:

    * **ACP** (primary, `Toolnexus.Cli.AcpSession`): when the style declares an
      ACP launch and the binary speaks it, the toolkit is served as a local MCP
      server and handed to the agent in `session/new` `mcpServers`. The agent
      calls the tools natively; `:before_tool`/`:after_tool` hooks run at the
      server boundary.
    * **one-shot CLI** (fallback, this module): the CLI runs once per model
      turn with the conversation rendered under a fixed preamble; its reply is
      parsed as an OpenAI-shaped envelope and tool calls go back to the
      toolnexus loop.

  A style is data (`lib/toolnexus/cli/styles/*.json`, byte-identical to
  `conformance/cli-styles/`) and holds no binary: `:command` is always the
  caller's.

  ## Options

    * `:style` — one of `styles/0` (`:claude`, `:codex`, `:copilot`,
      `:opencode`, `:devin`, `:gemini`, `:goose`, `:custom`). Required unless
      `:style_def` is set. A string is never accepted.
    * `:style_def` — a user style from `load_style/1`; wins over `:style`.
    * `:command` — the CLI binary, a path or a name on PATH. **Required.**
    * `:model` — passed to the CLI unchanged (absent ⇒ the CLI's default).
    * `:args`, `:prompt` (`"stdin" | "arg" | "file"`), `:answer`
      (`"stdout" | "out"`) — the recipe for `style: :custom`.
    * `:env` — map added to the child's environment; values expand `${VAR}`
      from this process's environment at call time and are never logged.
    * `:call_timeout_ms` — bounds one CLI invocation or one ACP prompt
      (default 300000).
    * `:cwd` — the child's working directory.
    * `:transport` — one of `transports/0`: `:auto` (default), `:acp`, `:cli`.
    * `:toolkit` — served to an ACP agent as a local MCP server.
    * `:acp_command` — full argv that starts the agent in ACP mode (replaces
      `[command | style.acp.args]`).
    * `:bridge_command` — argv of a stdio⇄HTTP MCP bridge, for an agent that
      accepts neither http MCP servers nor a project MCP file. Default:
      `["toolnexus", "mcp-bridge"]` when the toolnexus CLI is on PATH.
    * `:on_acp_update` — `(params -> any)` observes every raw ACP `session/update`.
    * every other option is a `Toolnexus.Client.create/1` option
      (`:system_prompt`, `:hooks`, `:max_turns`, …).
  """

  alias Toolnexus.Client

  @styles [:claude, :codex, :copilot, :opencode, :devin, :gemini, :goose, :custom]
  @transports [:auto, :acp, :cli]
  @default_call_timeout_ms 300_000
  @in_process_base_url "http://in-process.invalid/v1"

  @styles_dir Path.join(__DIR__, "cli/styles")
  @style_files ~w(claude codex copilot devin gemini goose opencode)
               |> Enum.map(&(&1 <> ".json"))
               |> Kernel.++(["preamble.txt"])

  for f <- @style_files, do: @external_resource(Path.join(@styles_dir, f))

  @embedded Map.new(@style_files, fn f -> {f, File.read!(Path.join(@styles_dir, f))} end)

  @preamble Map.fetch!(@embedded, "preamble.txt")

  @cli_options [
    :style,
    :style_def,
    :command,
    :args,
    :prompt,
    :answer,
    :env,
    :call_timeout_ms,
    :cwd,
    :transport,
    :toolkit,
    :acp_command,
    :bridge_command,
    :on_acp_update
  ]

  defstruct [:client, :server]

  @type t :: %__MODULE__{client: Client.t(), server: pid()}

  @doc "The closed set of CLI styles."
  @spec styles() :: [atom()]
  def styles, do: @styles

  @doc "The closed set of transports: `:auto`, `:acp`, `:cli`."
  @spec transports() :: [atom()]
  def transports, do: @transports

  @doc "The fixed one-shot preamble (byte-identical to `conformance/cli-styles/preamble.txt`)."
  @spec preamble() :: String.t()
  def preamble, do: @preamble

  @doc false
  # The embedded shared data files, by file name — for the drift test.
  def embedded_files, do: @embedded

  @doc """
  The built-in definition of a style, as the decoded JSON map (`:error` for
  `:custom` or anything outside the set).
  """
  @spec style_def(atom()) :: {:ok, map()} | :error
  def style_def(style) when style in @styles and style != :custom do
    {:ok, Jason.decode!(Map.fetch!(@embedded, "#{style}.json"))}
  end

  def style_def(_), do: :error

  @doc """
  Read a user style from a file path, or from JSON text that starts with `{`.
  Pass the result as `:style_def`.
  """
  @spec load_style(String.t()) :: {:ok, map()} | {:error, String.t()}
  def load_style(path_or_json) do
    text =
      if String.starts_with?(String.trim(path_or_json), "{") do
        {:ok, path_or_json}
      else
        File.read(path_or_json)
      end

    with {:ok, data} <- text,
         {:ok, %{} = d} <- Jason.decode(data) do
      {:ok, d}
    else
      {:ok, _} -> {:error, "cli style: not a JSON object"}
      {:error, %Jason.DecodeError{} = e} -> {:error, "cli style: " <> Exception.message(e)}
      {:error, reason} -> {:error, "cli style: #{:file.format_error(reason)}"}
    end
  end

  # ---------------------------------------------------------------------------
  # Construction.
  # ---------------------------------------------------------------------------

  @doc """
  Build a client whose model is an installed agent CLI. Everything that can be
  validated is validated here; the transport (ACP or one-shot) is decided on
  the first model call. Returns `{:ok, cli}` or `{:error, message}` with the
  SPEC §8C pinned message.
  """
  @spec create_client(keyword() | map()) :: {:ok, t()} | {:error, String.t()}
  def create_client(opts) do
    opts = if is_map(opts), do: Map.to_list(opts), else: opts

    with {:ok, cfg} <- configure(opts) do
      owner = self()
      {:ok, server} = GenServer.start(__MODULE__.Server, {cfg, owner})
      client = build_client(opts, cfg, server)
      {:ok, %__MODULE__{client: client, server: server}}
    end
  end

  @doc "`create_client/1`, raising `ArgumentError` with the pinned message."
  @spec create_client!(keyword() | map()) :: t()
  def create_client!(opts) do
    case create_client(opts) do
      {:ok, cli} -> cli
      {:error, msg} -> raise ArgumentError, msg
    end
  end

  @doc false
  # Resolve and validate; returns the immutable call configuration.
  def configure(opts) do
    command = opts[:command]
    transport = Keyword.get(opts, :transport, :auto) || :auto
    model = opts[:model] || ""

    with :ok <- require_command(command),
         {:ok, d} <- resolve_style(opts),
         :ok <- check_transport(transport) do
      acp_argv =
        cond do
          is_list(opts[:acp_command]) and opts[:acp_command] != [] -> opts[:acp_command]
          is_map(d["acp"]) -> [command | d["acp"]["args"] || []]
          true -> nil
        end

      acp_only? =
        not Map.has_key?(d, "args") and d["prompt"] in [nil, ""] and d["answer"] in [nil, ""] and
          acp_argv != nil and transport == :auto

      cond do
        transport == :acp and acp_argv == nil ->
          {:error,
           ~s(cli: style "#{d["name"]}" has no ACP launch; set ACPCommand or use CliTransports.CLI)}

        transport == :acp or acp_only? ->
          finish_config(opts, d, command, model, transport, acp_argv, nil)

        true ->
          with {:ok, elems} <- normalize_args(d["args"]),
               :ok <- validate_style(d, elems, model) do
            finish_config(opts, d, command, model, transport, acp_argv, elems)
          end
      end
    end
  end

  defp finish_config(opts, d, command, model, transport, acp_argv, elems) do
    env = Map.merge(stringify(d["env"] || %{}), stringify(opts[:env] || %{}))

    timeout =
      Enum.find([opts[:call_timeout_ms], d["timeoutMs"], @default_call_timeout_ms], fn
        t when is_integer(t) and t > 0 -> true
        _ -> false
      end)

    chosen =
      cond do
        transport == :cli or acp_argv == nil -> :cli
        transport == :acp -> :acp
        true -> :auto
      end

    {:ok,
     %{
       style: d,
       command: command,
       model: model,
       elems: elems,
       has_cli: elems != nil,
       env: env,
       timeout: timeout,
       acp_argv: acp_argv,
       chosen: chosen,
       toolkit: opts[:toolkit],
       hooks: opts[:hooks],
       cwd: opts[:cwd],
       acp_command?: is_list(opts[:acp_command]) and opts[:acp_command] != [],
       bridge_command: opts[:bridge_command],
       on_acp_update: opts[:on_acp_update]
     }}
  end

  defp stringify(m), do: Map.new(m, fn {k, v} -> {to_string(k), to_string(v)} end)

  defp require_command(c) when is_binary(c) and c != "", do: :ok

  defp require_command(_),
    do: {:error, "cli: Command is required (path or name of the CLI binary)"}

  defp check_transport(t) when t in @transports, do: :ok

  defp check_transport(t),
    do: {:error, "cli: transport must be one of :auto, :acp, :cli, not #{inspect(t)}"}

  defp resolve_style(opts) do
    cond do
      is_map(opts[:style_def]) ->
        {:ok, opts[:style_def]}

      opts[:style] == :custom ->
        {:ok,
         %{
           "name" => "custom",
           "args" => opts[:args] || [],
           "prompt" => opts[:prompt] || "",
           "answer" => opts[:answer] || ""
         }}

      true ->
        case style_def(opts[:style]) do
          {:ok, d} -> {:ok, d}
          :error -> {:error, "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"}
        end
    end
  end

  defp build_client(opts, cfg, server) do
    generate = fn req -> __MODULE__.Server.generate(server, cfg, req) end
    inner = Client.in_process_transport(generate)

    transport = fn req ->
      if (req.body || %{})["stream"] && __MODULE__.Server.decide(server) == :cli do
        raise RuntimeError,
              "cli: streaming is not supported by the one-shot CLI transport; use Run"
      end

      inner.(req)
    end

    opts
    |> Keyword.drop(@cli_options)
    |> Keyword.drop([:base_url, :api_key, :style, :transport])
    |> Keyword.put_new(:model, cfg.style["name"])
    |> Keyword.update(
      :model,
      cfg.style["name"],
      &if(&1 in [nil, ""], do: cfg.style["name"], else: &1)
    )
    |> Keyword.put_new(:retries, 0)
    |> Keyword.merge(
      base_url: @in_process_base_url,
      style: "openai",
      api_key: "in-process",
      transport: transport
    )
    |> Client.create()
  end

  # ---------------------------------------------------------------------------
  # The client surface.
  # ---------------------------------------------------------------------------

  @doc "`Toolnexus.Client.run/4` on the CLI client."
  def run(%__MODULE__{client: c}, prompt, toolkit \\ nil, opts \\ []),
    do: Client.run(c, prompt, toolkit, opts)

  @doc "`Toolnexus.Client.stream/4` on the CLI client (refused by the one-shot transport)."
  def stream(%__MODULE__{client: c}, prompt, toolkit \\ nil, opts \\ []),
    do: Client.stream(c, prompt, toolkit, opts)

  @doc ~s[The transport in use: "acp", "cli", or "auto" before the first model call decided.]
  @spec transport(t()) :: String.t()
  def transport(%__MODULE__{server: s}), do: GenServer.call(s, :transport) |> Atom.to_string()

  @doc ~s[Why `:auto` fell back from ACP to the one-shot CLI ("" when it did not).]
  @spec fallback_reason(t()) :: String.t()
  def fallback_reason(%__MODULE__{server: s}), do: GenServer.call(s, :fallback_reason)

  @doc """
  The live ACP session (`:error` when the one-shot transport is in use or no
  call has opened one): `%{url, mcp_servers_sent, permissions, served_calls,
  session_cwd}`. Header and env values in `mcp_servers_sent` are redacted; the
  bearer token is never exposed.
  """
  @spec acp_session(t()) :: {:ok, map()} | :error
  def acp_session(%__MODULE__{server: s}) do
    case GenServer.call(s, :session_info) do
      nil -> :error
      sess -> {:ok, Toolnexus.Cli.AcpSession.info(sess)}
    end
  end

  @doc """
  End the ACP agent's process tree, stop the served toolkit and remove any
  temp session dir. Idempotent; a no-op for the one-shot CLI.
  """
  @spec close(t()) :: :ok
  def close(%__MODULE__{server: s}) do
    if Process.alive?(s), do: GenServer.call(s, :close, 30_000)
    :ok
  catch
    :exit, _ -> :ok
  end

  # ---------------------------------------------------------------------------
  # Argv templating and validation.
  # ---------------------------------------------------------------------------

  @placeholder ~r/\{\{([^{}]*)\}\}/

  @doc false
  def normalize_args(nil), do: {:ok, []}

  def normalize_args(args) when is_list(args) do
    Enum.reduce_while(args, {:ok, []}, fn
      s, {:ok, acc} when is_binary(s) ->
        {:cont, {:ok, acc ++ [{:arg, [s]}]}}

      g, {:ok, acc} when is_list(g) ->
        if Enum.all?(g, &is_binary/1),
          do: {:cont, {:ok, acc ++ [{:group, g}]}},
          else: {:halt, {:error, "cli: a group may only hold strings"}}

      _, _ ->
        {:halt, {:error, "cli: an arg must be a string or a group of strings"}}
    end)
  end

  def normalize_args(_), do: {:error, "cli: an arg must be a string or a group of strings"}

  @doc false
  def validate_style(d, elems, model) do
    prompt = d["prompt"] || ""
    answer = d["answer"] || ""

    with :ok <- one_of(prompt, ~w(stdin arg file), "cli: prompt must be stdin, arg or file, not "),
         :ok <- one_of(answer, ~w(stdout out), "cli: answer must be stdout or out, not "),
         {:ok, seen} <- scan_placeholders(elems, model) do
      cond do
        model != "" and not MapSet.member?(seen, "model") ->
          {:error, ~s(cli: style "#{d["name"]}" takes no model)}

        prompt == "arg" and not MapSet.member?(seen, "prompt") ->
          {:error, ~s(cli: prompt "arg" needs {{prompt}} in args)}

        prompt == "file" and not MapSet.member?(seen, "file") ->
          {:error, ~s(cli: prompt "file" needs {{file}} in args)}

        answer == "out" and not MapSet.member?(seen, "out") ->
          {:error, ~s(cli: answer "out" needs {{out}} in args)}

        true ->
          :ok
      end
    end
  end

  defp one_of(v, allowed, prefix) do
    if v in allowed, do: :ok, else: {:error, prefix <> Jason.encode!(v)}
  end

  defp scan_placeholders(elems, model) do
    Enum.reduce_while(elems, {:ok, MapSet.new()}, fn {kind, parts}, {:ok, seen} ->
      names = for p <- parts, [_, n] <- Regex.scan(@placeholder, p), do: n

      Enum.reduce_while(names, {:ok, seen}, fn n, {:ok, seen} ->
        cond do
          n in ~w(prompt file out) ->
            {:cont, {:ok, MapSet.put(seen, n)}}

          n == "model" and kind == :arg and model == "" ->
            {:halt,
             {:error, "cli: the style needs a model: {{model}} is outside an optional group"}}

          n == "model" ->
            {:cont, {:ok, MapSet.put(seen, n)}}

          true ->
            {:halt, {:error, "cli: unknown placeholder {{#{n}}}"}}
        end
      end)
      |> case do
        {:ok, _} = ok -> {:cont, ok}
        err -> {:halt, err}
      end
    end)
  end

  @doc false
  # Render one invocation's argv. Returns {argv, out_path, temp_files}; the
  # caller removes temp_files on every path.
  def render_argv(cfg, prompt) do
    model = cfg.model

    {argv, st} =
      Enum.reduce(cfg.elems, {[cfg.command], %{file: nil, out: nil, tmp: []}}, fn
        {:group, _}, acc when model == "" ->
          acc

        {_, parts}, {argv, st} ->
          {parts, st} = Enum.map_reduce(parts, st, &substitute(&1, &2, prompt, model))
          {argv ++ parts, st}
      end)

    {argv, st.out, st.tmp}
  end

  defp substitute(part, st, prompt, model) do
    Regex.split(@placeholder, part, include_captures: true)
    |> Enum.map_reduce(st, fn piece, st ->
      case Regex.run(@placeholder, piece) do
        [^piece, "prompt"] ->
          {prompt, st}

        [^piece, "model"] ->
          {model, st}

        [^piece, "file"] ->
          st = if st.file, do: st, else: put_temp(st, :file, prompt)
          {st.file, st}

        [^piece, "out"] ->
          st = if st.out, do: st, else: put_temp(st, :out, nil)
          {st.out, st}

        _ ->
          {piece, st}
      end
    end)
    |> then(fn {pieces, st} -> {Enum.join(pieces), st} end)
  end

  defp put_temp(st, key, content) do
    path = temp_path()
    # {{out}}: the CLI creates it; absence ⇒ "cli: no output file".
    if content != nil, do: File.write!(path, content)
    %{st | key => path, tmp: st.tmp ++ [path]}
  end

  @doc false
  def temp_path do
    Path.join(
      System.tmp_dir!(),
      "toolnexus-cli-" <> Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)
    )
  end

  # ---------------------------------------------------------------------------
  # The envelope: render and tolerant parse.
  # ---------------------------------------------------------------------------

  @doc """
  `PREAMBLE <> "\\nREQUEST:\\n" <> {"messages","tools"}` — the OpenAI-shaped
  request, unmodified, as compact JSON.
  """
  @spec render_prompt(list() | nil, list() | nil) :: String.t()
  def render_prompt(messages, tools) do
    @preamble <>
      "\nREQUEST:\n" <>
      Jason.encode!(%{"messages" => messages || [], "tools" => tools || []})
  end

  @doc """
  The tolerant six-step envelope parser: (1) trim; (2) strip one markdown
  fence; (3) parse the whole text as one JSON object, else (4) the span from
  the first `{` to the last `}`; (5) unwrap `choices[0].message` or `message`;
  (6) `tool_calls` (flat or `{function:{}}`, string or object arguments) win,
  else `content`. Anything that is not an envelope is returned verbatim as
  content. Returns `%{content: s}` or `%{tool_calls: [%{name, arguments, id?}]}`.
  """
  @spec parse_reply(String.t()) :: map()
  def parse_reply(text) do
    s = text |> String.trim() |> strip_fence()

    case parse_object(s) || parse_braced(s) do
      nil -> %{content: text}
      obj -> obj |> unwrap_envelope() |> from_envelope(text)
    end
  end

  defp strip_fence("```" <> _ = s) do
    rest =
      case :binary.split(s, "\n") do
        [_first_line, rest] -> rest
        [_] -> ""
      end

    rest |> String.trim() |> String.replace_suffix("```", "") |> String.trim()
  end

  defp strip_fence(s), do: s

  defp parse_object(s) do
    case Jason.decode(s) do
      {:ok, obj} when is_map(obj) -> obj
      _ -> nil
    end
  end

  defp parse_braced(s) do
    case {:binary.match(s, "{"), :binary.matches(s, "}")} do
      {{i, _}, [_ | _] = ends} ->
        {j, _} = List.last(ends)
        if j > i, do: parse_object(binary_part(s, i, j - i + 1))

      _ ->
        nil
    end
  end

  defp unwrap_envelope(%{"choices" => [first | _]} = obj) do
    case first do
      %{"message" => %{} = msg} -> msg
      _ -> obj
    end
  end

  defp unwrap_envelope(%{"message" => %{} = msg}), do: msg
  defp unwrap_envelope(obj), do: obj

  defp from_envelope(obj, text) do
    calls =
      case obj do
        %{"tool_calls" => raw} when is_list(raw) -> Enum.flat_map(raw, &tool_call/1)
        _ -> []
      end

    cond do
      calls != [] -> %{tool_calls: calls}
      Map.has_key?(obj, "content") -> %{content: envelope_content(obj["content"])}
      true -> %{content: text}
    end
  end

  defp tool_call(%{} = el) do
    func =
      case el do
        %{"function" => %{} = f} -> f
        _ -> el
      end

    case func do
      %{"name" => name} when is_binary(name) and name != "" ->
        args = Map.get(func, "arguments") || %{}

        case el do
          %{"id" => id} when is_binary(id) and id != "" ->
            [%{id: id, name: name, arguments: args}]

          _ ->
            [%{name: name, arguments: args}]
        end

      _ ->
        []
    end
  end

  defp tool_call(_), do: []

  defp envelope_content(c) when is_binary(c), do: c
  defp envelope_content(nil), do: ""
  defp envelope_content(c), do: Jason.encode!(c)

  # ---------------------------------------------------------------------------
  # One-shot invocation.
  # ---------------------------------------------------------------------------

  @env_ref ~r/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/

  @doc false
  # The child's extra env with ${VAR} expanded from this process's env. The
  # values are never logged.
  def expand_env(env) do
    Map.new(env, fn {k, v} ->
      {k, Regex.replace(@env_ref, v, fn _, name -> System.get_env(name) || "" end)}
    end)
  end

  @doc false
  def invoke(cfg, prompt) do
    {argv, out_path, tmp} = render_argv(cfg, prompt)
    in_path = if cfg.style["prompt"] == "stdin", do: temp_path()
    err_path = temp_path()
    tmp = tmp ++ Enum.reject([in_path, err_path], &is_nil/1)

    try do
      if in_path, do: File.write!(in_path, prompt)
      do_invoke(cfg, argv, in_path || "/dev/null", err_path, out_path)
    after
      Enum.each(tmp, &File.rm/1)
    end
  end

  defp do_invoke(cfg, [bin | args], in_path, err_path, out_path) do
    case find_executable(bin) do
      nil ->
        {:error, "cli: command not found: #{bin}"}

      exe ->
        env =
          for {k, v} <- expand_env(cfg.env),
              do: {String.to_charlist(k), String.to_charlist(v)}

        port_opts =
          [
            :binary,
            :exit_status,
            env: env,
            # No shell interprets the argv: sh only wires stdin/stderr and execs.
            args: [
              "-c",
              ~S(i="$0"; e="$1"; shift; exec "$@" <"$i" 2>"$e"),
              in_path,
              err_path,
              exe | args
            ]
          ] ++ if(cfg.cwd in [nil, ""], do: [], else: [cd: String.to_charlist(cfg.cwd)])

        port = Port.open({:spawn_executable, "/bin/sh"}, port_opts)

        os_pid =
          case Port.info(port, :os_pid) do
            {:os_pid, p} -> p
            _ -> nil
          end

        deadline = System.monotonic_time(:millisecond) + cfg.timeout

        case collect(port, "", deadline) do
          :timeout ->
            Toolnexus.Cli.Proc.kill_tree(os_pid)
            close_port(port)
            flush_port(port)
            {:error, "cli: timed out after #{cfg.timeout}ms"}

          {:exit, 0, stdout} ->
            answer(cfg, stdout, out_path)

          {:exit, code, _stdout} ->
            msg = err_path |> File.read() |> elem_or("") |> String.trim()

            cond do
              msg == "" ->
                {:error, "cli: exit #{code}"}

              String.length(msg) > 1000 ->
                {:error, "cli: exit #{code}: " <> String.slice(msg, 0, 1000) <> "…"}

              true ->
                {:error, "cli: exit #{code}: " <> msg}
            end
        end
    end
  end

  defp elem_or({:ok, v}, _), do: v
  defp elem_or(_, d), do: d

  defp answer(cfg, stdout, out_path) do
    text =
      if cfg.style["answer"] == "out" do
        case File.read(out_path) do
          {:ok, b} -> {:ok, b}
          _ -> {:error, "cli: no output file"}
        end
      else
        {:ok, stdout}
      end

    case text do
      {:ok, t} -> if String.trim(t) == "", do: {:error, "cli: empty reply"}, else: {:ok, t}
      err -> err
    end
  end

  defp collect(port, acc, deadline) do
    remaining = max(deadline - System.monotonic_time(:millisecond), 0)

    receive do
      {^port, {:data, d}} -> collect(port, acc <> d, deadline)
      {^port, {:exit_status, code}} -> {:exit, code, acc}
    after
      remaining -> :timeout
    end
  end

  defp close_port(port) do
    if Port.info(port), do: Port.close(port)
  rescue
    ArgumentError -> :ok
  end

  defp flush_port(port) do
    receive do
      {^port, _} -> flush_port(port)
    after
      0 -> :ok
    end
  end

  @doc false
  def find_executable(bin) do
    cond do
      String.contains?(bin, "/") ->
        if File.regular?(bin), do: Path.expand(bin)

      true ->
        System.find_executable(bin)
    end
  end

  # ---------------------------------------------------------------------------
  # Permission titles and the ACP system hint.
  # ---------------------------------------------------------------------------

  @server_name "toolnexus"

  @doc """
  Whether a permission title names a tool of OUR server, by the exact
  spellings agents use: `<tool>`, `mcp__toolnexus__<tool>`, `toolnexus/<tool>`,
  `<tool> (toolnexus MCP Server)`, `Calling <tool> from toolnexus`, or
  `<tool>(…)`. No substring matching.
  """
  @spec served_call?(String.t() | nil, Enumerable.t()) :: boolean()
  def served_call?(title, _served) when title in [nil, ""], do: false

  def served_call?(title, served) do
    Enum.any?(served, fn n ->
      title in [
        n,
        "mcp__#{@server_name}__#{n}",
        "#{@server_name}/#{n}",
        "#{n} (#{@server_name} MCP Server)",
        "Calling #{n} from #{@server_name}"
      ] or (String.starts_with?(title, n <> "(") and String.ends_with?(title, ")"))
    end)
  end

  @doc """
  Append a style's ACP system hint to the first system message as
  `"\\n\\n" <> hint` (a copy — the caller's messages are not mutated), or
  prepend a system message holding only the hint when there is none.
  """
  @spec with_system_hint(list(), String.t() | nil) :: list()
  def with_system_hint(msgs, hint) when hint in [nil, ""], do: msgs

  def with_system_hint([first | rest] = msgs, hint) do
    role = Map.get(first, "role") || Map.get(first, :role)
    {key, content} = content_of(first)

    if to_string(role) == "system" and is_binary(content) do
      new = if content == "", do: hint, else: content <> "\n\n" <> hint
      [Map.put(first, key, new) | rest]
    else
      [%{"role" => "system", "content" => hint} | msgs]
    end
  end

  def with_system_hint([], hint), do: [%{"role" => "system", "content" => hint}]

  defp content_of(m) do
    if Map.has_key?(m, :content), do: {:content, m[:content]}, else: {"content", m["content"]}
  end

  # ---------------------------------------------------------------------------
  # stdio ⇄ HTTP MCP bridge.
  # ---------------------------------------------------------------------------

  @doc """
  The stdio side of a stdio `McpServer` entry: each JSON-RPC line read from
  `input` is POSTed to the toolkit's streamable-HTTP endpoint (with the bearer
  token when set), and each reply — plain JSON or SSE `data:` frames — is
  written to `output` as one line. The token comes from env
  `TOOLNEXUS_MCP_TOKEN` in a bridge process.
  """
  @spec run_mcp_bridge(String.t(), String.t() | nil, IO.device(), IO.device()) ::
          :ok | {:error, String.t()}
  def run_mcp_bridge(url, token, input \\ :stdio, output \\ :stdio) do
    input
    |> IO.stream(:line)
    |> Enum.reduce_while(nil, fn line, session ->
      line = String.trim(line)

      if line == "" do
        {:cont, session}
      else
        headers =
          [
            {"content-type", "application/json"},
            {"accept", "application/json, text/event-stream"}
          ] ++
            if(token in [nil, ""], do: [], else: [{"authorization", "Bearer " <> token}]) ++
            if(session, do: [{"mcp-session-id", session}], else: [])

        case Req.post(url, body: line, headers: headers, decode_body: false, retry: false) do
          {:ok, %{status: 401}} ->
            {:halt, {:error, "mcp-bridge: unauthorized (missing or wrong TOOLNEXUS_MCP_TOKEN)"}}

          {:ok, resp} ->
            session = header(resp, "mcp-session-id") || session
            body = to_string(resp.body)

            if String.starts_with?(header(resp, "content-type") || "", "text/event-stream") do
              for l <- String.split(body, "\n"),
                  "data:" <> d <- [String.trim_trailing(l, "\r")],
                  do: IO.binwrite(output, String.trim(d) <> "\n")
            else
              b = String.trim(body)
              if String.starts_with?(b, "{"), do: IO.binwrite(output, b <> "\n")
            end

            {:cont, session}

          {:error, e} ->
            {:halt, {:error, "mcp-bridge: " <> Exception.message(e)}}
        end
      end
    end)
    |> case do
      {:error, _} = err -> err
      _ -> :ok
    end
  end

  defp header(resp, name) do
    case Req.Response.get_header(resp, name) do
      [v | _] -> v
      _ -> nil
    end
  end
end
