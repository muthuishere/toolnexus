defmodule Toolnexus.Cli.AcpSession do
  @moduledoc """
  CLI client, ACP transport (SPEC §8C, ADR 0037 "ACP primary").

  On the first model call the toolkit is served as streamable-HTTP MCP on
  `127.0.0.1:<ephemeral>/mcp`, guarded by a per-session bearer token, and
  handed to the agent in `session/new` `mcpServers` (http when the agent
  advertises `mcpCapabilities.http`, else a stdio bridge; a project MCP file
  in a temp cwd for agents that ignore session servers). The agent calls the
  tools natively; toolnexus hooks run at the server boundary. Every
  permission request is decided once per `toolCallId`: tools served by our
  server are allowed, everything else (the agent's own shell, edit, fetch …)
  is rejected.
  """

  alias Toolnexus.{Acp, Cli, McpServe, Tool, ToolResult, Toolkit}

  @server_name "toolnexus"

  @doc "The name the toolkit's `skill` tool is served under to an ACP agent."
  def served_skill_tool_name, do: "load_skill"

  @doc "The env var that carries the bearer token to a stdio bridge."
  def token_env, do: "TOOLNEXUS_MCP_TOKEN"

  defstruct [
    :acp,
    :bandit,
    :url,
    :token,
    :temp_dir,
    :stderr_path,
    :served,
    :hint,
    :timeout,
    :state
  ]

  # ---------------------------------------------------------------------------
  # Open / close.
  # ---------------------------------------------------------------------------

  @doc false
  # {:ok, session} | {:error, {:launch | :fatal, message}}. Only a failure to
  # start the agent or to answer `initialize` is a :launch error — the one
  # Auto falls back on.
  def open(cfg) do
    [bin | args] = cfg.acp_argv

    case Cli.find_executable(bin) do
      nil -> {:error, {:launch, "cli: command not found: #{bin}"}}
      exe -> do_open(cfg, exe, args)
    end
  end

  defp do_open(cfg, exe, args) do
    token = Base.encode16(:crypto.strong_rand_bytes(32), case: :lower)
    {:ok, state} = Agent.start_link(fn -> %{calls: %{}, decisions: %{}, log: [], ran: []} end)

    tools =
      served_tools(cfg.toolkit, cfg.hooks, fn name ->
        Agent.update(state, &%{&1 | ran: &1.ran ++ [name]})
      end)

    s = %__MODULE__{
      token: token,
      state: state,
      served: MapSet.new(tools, & &1.name),
      hint: get_in(cfg.style, ["acp", "systemHint"]),
      timeout: cfg.timeout,
      stderr_path: Cli.temp_path()
    }

    s = serve(s, tools)
    project_file = get_in(cfg.style, ["acp", "projectMcpFile"])

    {s, cwd, servers} =
      if project_file not in [nil, ""] and not cfg.acp_command? do
        dir = Path.join(System.tmp_dir!(), "toolnexus-acp-" <> rand_hex(6))
        File.mkdir_p!(dir)
        f = Path.join(dir, project_file)
        File.mkdir_p!(Path.dirname(f))

        File.write!(
          f,
          Jason.encode!(%{
            "mcpServers" => %{
              @server_name => %{
                "type" => "http",
                "url" => s.url,
                "headers" => %{"Authorization" => "Bearer " <> token}
              }
            }
          })
        )

        File.chmod!(f, 0o600)
        {%{s | temp_dir: dir}, dir, []}
      else
        entry = %{
          name: @server_name,
          url: s.url,
          headers: %{"Authorization" => "Bearer " <> token}
        }

        bridge =
          case cfg.bridge_command do
            [_ | _] = b ->
              b

            _ ->
              case System.find_executable("toolnexus") do
                nil -> nil
                p -> [p, "mcp-bridge"]
              end
          end

        entry =
          case bridge do
            [cmd | bargs] ->
              Map.merge(entry, %{
                command: cmd,
                args: bargs ++ [s.url],
                env: %{token_env() => token}
              })

            nil ->
              entry
          end

        {s, Path.expand(cfg.cwd || File.cwd!()), [entry]}
      end

    on_update = cfg.on_acp_update

    acp_opts = [
      cwd: cwd,
      env: Cli.expand_env(cfg.env),
      timeout: cfg.timeout,
      mcp_servers: servers,
      model: if(cfg.model == "", do: nil, else: cfg.model),
      stderr: s.stderr_path,
      on_permission: fn params -> permission(state, s.served, params) end,
      on_update: fn params ->
        update(state, params)
        if is_function(on_update, 1), do: on_update.(params)
      end
    ]

    case Acp.connect([exe | args], acp_opts) do
      {:ok, pid} ->
        {:ok, %{s | acp: pid}}

      {:error, reason} ->
        # The connect is linked; its failed init sends us an exit we must not die of.
        receive do
          {:EXIT, _, _} -> :ok
        after
          0 -> :ok
        end

        stderr =
          case File.read(s.stderr_path) do
            {:ok, b} -> tail(b)
            _ -> ""
          end

        close(s)
        {:error, launch_error(reason, stderr)}
    end
  end

  defp tail(b) do
    b = String.trim(b)
    if byte_size(b) > 4096, do: binary_part(b, byte_size(b) - 4096, 4096), else: b
  end

  defp launch_error(reason, stderr) do
    {kind, msg} =
      case reason do
        {:start, r} ->
          {:launch, "cli: acp: start: #{describe(r)}"}

        {:initialize, r} ->
          {:launch, "cli: acp: initialize: #{describe(r)}"}

        {:session_new, r} ->
          {:fatal, "cli: acp: session/new: #{describe(r)}"}

        {:set_model, m, e1, e2} ->
          {:fatal, ~s(cli: acp: set model "#{m}": #{describe(e1)}; #{describe(e2)})}

        other ->
          {:fatal, "cli: acp: #{describe(other)}"}
      end

    {kind, if(stderr == "", do: msg, else: msg <> ": " <> stderr)}
  end

  defp describe({:agent_exited, method}), do: "agent exited before replying to #{method}"
  defp describe({:acp_error, %{"code" => c, "message" => m}}), do: "acp error #{c}: #{m}"
  defp describe(:timeout), do: "timed out"
  defp describe(other), do: inspect(other)

  @doc false
  # Ends the agent (its process tree), stops the server, removes the temp dir.
  def close(%__MODULE__{} = s) do
    if s.acp, do: Acp.close(s.acp)

    if s.bandit && Process.alive?(s.bandit) do
      try do
        Supervisor.stop(s.bandit, :normal, 3_000)
      catch
        :exit, _ -> :ok
      end
    end

    if s.temp_dir, do: File.rm_rf(s.temp_dir)
    if s.stderr_path, do: File.rm(s.stderr_path)
    if s.state && Process.alive?(s.state), do: Agent.stop(s.state)
    :ok
  end

  @doc false
  def info(%__MODULE__{} = s) do
    st = Agent.get(s.state, & &1)
    sent = if s.acp, do: Acp.info(s.acp).mcp_servers_sent, else: []

    %{
      url: s.url,
      mcp_servers_sent: redact(sent),
      permissions: st.log,
      served_calls: st.ran,
      session_cwd: s.temp_dir
    }
  end

  defp redact(servers) do
    Enum.map(servers, fn e ->
      Enum.reduce(["headers", "env"], e, fn k, e ->
        case e[k] do
          l when is_list(l) -> Map.put(e, k, Enum.map(l, &Map.put(&1, "value", "[redacted]")))
          _ -> e
        end
      end)
    end)
  end

  # ---------------------------------------------------------------------------
  # One turn.
  # ---------------------------------------------------------------------------

  @doc false
  def generate(%__MODULE__{} = s, req, emit \\ nil) do
    messages = Cli.with_system_hint(req[:messages] || [], s.hint)
    text = Acp.assemble_prompt_text(messages)

    case Acp.prompt(s.acp, text, s.timeout, emit) do
      {:ok, answer} -> {:ok, %{content: answer}}
      {:error, {:acp_exit, :timeout}} -> {:error, "cli: timed out after #{s.timeout}ms"}
      {:error, reason} -> {:error, "cli: acp: #{describe(reason)}"}
    end
  end

  # ---------------------------------------------------------------------------
  # Permissions, per toolCallId.
  # ---------------------------------------------------------------------------

  # Remember the first non-empty title of each toolCallId (devin puts the name
  # only on the tool_call update and sends an empty permission title).
  defp update(state, %{"update" => %{"sessionUpdate" => kind} = u})
       when kind in ["tool_call", "tool_call_update"] do
    id = u["toolCallId"]
    title = u["title"]

    if is_binary(id) and id != "" and is_binary(title) and title != "" do
      Agent.update(state, fn st -> %{st | calls: Map.put_new(st.calls, id, title)} end)
    end
  end

  defp update(_state, _params), do: :ok

  defp permission(state, served, params) do
    tc = params["toolCall"] || %{}
    id = tc["toolCallId"]
    id = if is_binary(id), do: id, else: ""

    allowed =
      Agent.get_and_update(state, fn st ->
        title =
          case tc["title"] do
            t when is_binary(t) and t != "" -> t
            _ -> Map.get(st.calls, id, "")
          end

        {allowed, decisions} =
          case Map.fetch(st.decisions, id) do
            {:ok, a} ->
              {a, st.decisions}

            :error ->
              a = id != "" and Cli.served_call?(title, served)
              {a, if(id != "", do: Map.put(st.decisions, id, a), else: st.decisions)}
          end

        entry = %{tool_call_id: id, title: title, allowed: allowed}
        {allowed, %{st | decisions: decisions, log: st.log ++ [entry]}}
      end)

    if allowed do
      options = params["options"] || []

      Enum.find_value(options, fn o -> if o["kind"] == "allow_once", do: o["optionId"] end) ||
        Enum.find_value(options, fn o ->
          if String.starts_with?(to_string(o["kind"]), "allow"), do: o["optionId"]
        end)
    end
  end

  # ---------------------------------------------------------------------------
  # The served toolkit.
  # ---------------------------------------------------------------------------

  @doc false
  # The toolkit as served: `skill` renamed to load_skill with the skills list
  # in its description; every execute runs through :before_tool/:after_tool.
  def served_tools(nil, _hooks, _ran), do: []

  def served_tools(%Toolkit{} = tk, hooks, ran) do
    hooks = hooks || %{}
    skills_prompt = Toolkit.skills_prompt(tk) || ""

    Enum.map(Toolkit.tools(tk), fn %Tool{} = t ->
      name = t.name

      t =
        if t.source == "skill" and t.name == "skill" do
          desc =
            if skills_prompt == "",
              do: t.description,
              else: t.description <> "\n\n" <> skills_prompt

          %{t | name: served_skill_tool_name(), description: desc}
        else
          t
        end

      served = t.name
      inner = t.execute

      %{
        t
        | execute: fn args, ctx ->
            run_hooked(hooks, name, t.hints, served, inner, args, ctx, ran)
          end
      }
    end)
  end

  defp run_hooked(hooks, name, hints, served, inner, args, ctx, ran) do
    before =
      case hooks[:before_tool] do
        f when is_function(f, 1) ->
          try do
            f.(%{name: name, args: args, id: nil, turn: 0, hints: hints})
          rescue
            e -> {:raised, Exception.message(e)}
          end

        _ ->
          nil
      end

    case before do
      {:raised, msg} ->
        %ToolResult{output: msg, is_error: true}

      %{result: %ToolResult{} = r} ->
        r

      _ ->
        args =
          case before do
            %{args: %{} = a} -> a
            _ -> args
          end

        ran.(served)

        res =
          try do
            case inner.(args, ctx) do
              %ToolResult{} = r -> r
              other -> %ToolResult{output: to_string(other), is_error: false}
            end
          rescue
            e -> %ToolResult{output: Exception.message(e), is_error: true}
          end

        case hooks[:after_tool] do
          f when is_function(f, 1) ->
            try do
              case f.(%{name: name, args: args, result: res, id: nil, turn: 0}) do
                %{result: %ToolResult{} = r} -> r
                _ -> res
              end
            rescue
              e -> %ToolResult{output: Exception.message(e), is_error: true}
            end

          _ ->
            res
        end
    end
  end

  # ---------------------------------------------------------------------------
  # The bearer-guarded MCP endpoint.
  # ---------------------------------------------------------------------------

  defp serve(s, tools) do
    {:ok, pid} =
      Bandit.start_link(
        plug: {__MODULE__.Guard, %{token: s.token, tools: tools}},
        scheme: :http,
        ip: {127, 0, 0, 1},
        port: 0,
        startup_log: false
      )

    {:ok, {_ip, port}} = ThousandIsland.listener_info(pid)
    %{s | bandit: pid, url: "http://127.0.0.1:#{port}/mcp"}
  end

  defp rand_hex(n), do: Base.encode16(:crypto.strong_rand_bytes(n), case: :lower)

  defmodule Guard do
    @moduledoc false
    @behaviour Plug
    import Plug.Conn

    def init(st), do: st

    def call(conn, st) do
      want = "Bearer " <> st.token

      cond do
        conn.request_path != "/mcp" ->
          send_resp(conn, 404, "not found")

        not Plug.Crypto.secure_compare(auth(conn), want) ->
          conn
          |> put_resp_header("www-authenticate", "Bearer")
          |> send_resp(401, "unauthorized")

        true ->
          McpServe.handle_http(conn, st.tools, %{name: "toolnexus"}, nil)
      end
    end

    defp auth(conn) do
      case get_req_header(conn, "authorization") do
        [v | _] -> v
        _ -> ""
      end
    end
  end
end
