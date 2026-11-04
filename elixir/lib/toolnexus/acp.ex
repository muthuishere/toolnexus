defmodule Toolnexus.Acp do
  @moduledoc """
  ACP (Agent Client Protocol) model source — issue #96, ADR 0031, proposal
  `openspec/changes/add-acp-model-source`.

  Connects to a running agent (`devin acp`, Gemini CLI, Zed's agents, ...) over
  a child process's stdin/stdout, speaking JSON-RPC 2.0, one object per line —
  the SAME framing MCP local stdio already uses (SPEC §2), so this module
  reuses `Toolnexus.Mcp.Transport.Stdio` to spawn the child rather than
  hand-rolling a second port-spawning path.

  A **warm session** (ADR 0031 — "the warm session is the feature"): the
  process is spawned once via `connect/2`, `initialize` and `session/new`
  happen once, and every subsequent turn is one `session/prompt` against that
  same session. Concretely:

    * Only `agent_message_chunk` `session/update` notifications are
      accumulated into the reply — `agent_thought_chunk` and tool-call
      narration are dropped, or they wrap prose around structured output and
      break JSON parsing outright.
    * Responses to OUR calls and `session/update` notifications interleave on
      the same stream, so replies are demultiplexed by JSON-RPC id.
    * `session/request_permission` is answered INLINE, from the read loop,
      with the first `allow`-kind option, the instant it arrives — never
      exposed to the caller and never awaited. An unanswered permission
      request hangs the turn forever (even in bypass mode); answering
      immediately is what this module is FOR.
    * Turns are serialised: one ACP session is one conversation, so a second
      concurrent `generate/1` call is queued rather than interleaved into the
      same transcript.
    * The child process's lifetime is independent of any one turn — a failed
      or errored turn does not kill the session — and `close/1` is
      idempotent.
    * Every turn sends the FULL assembled request (every message, every
      turn — matching every other toolnexus model source, which is
      stateless by default) PLUS an explicit supersedes marker built by this
      library (not left to the caller), naming the current turn's content —
      the mitigation `spikes/acp/SPIKE.md` gate 1 proved against a stateful
      session that otherwise answers a near-duplicate, stale prompt.

  `generate/1` returns the exact `(map() -> map())` shape
  `Toolnexus.Client.create_in_process/1` and `Toolnexus.Agents.Runtime`'s
  `:in_process` option already accept, so the tool-calling loop, skills, MCP
  and sub-agents are completely unmodified — ACP is a model source, not a new
  tool source and not a new client. See `spikes/acp/SPIKE.md` for the
  reference Go client this ports, and `docs/adr/0031-acp-a-warm-session-is-the-feature.md`.
  """

  use GenServer, restart: :temporary, shutdown: 10_000

  alias Toolnexus.Mcp.Transport.Stdio

  @default_timeout 30_000
  @supersedes_marker "SUPERSEDES-ALL-PRIOR:"

  # --------------------------------------------------------------------------
  # client API
  # --------------------------------------------------------------------------

  @doc """
  Spawn `command` (a list, e.g. `["devin", "acp"]`) and complete the ACP
  handshake: `initialize` then `session/new`.

  Options:

    * `:cwd` — **absolute** working directory advertised to the agent.
      Defaults to `File.cwd!()`. A real `devin acp` rejects `session/new`
      with `-32602 Invalid params` on a relative or missing `cwd`.
    * `:mcp_servers` — the `mcpServers` array on `session/new` (default `[]`).
      Real `devin acp` requires the key to be present, even empty. An entry
      given as an atom-keyed map `%{name:, url:, headers:, command:, args:,
      env:}` is resolved against the agent's `initialize` result (SPEC §8C):
      the http shape `{type:"http",name,url,headers:[{name,value}]}` when the
      agent advertises `agentCapabilities.mcpCapabilities.http` (or the entry
      has no command), else the stdio shape `{name,command,args,env:[…]}` with
      no `type`. String-keyed maps are sent verbatim.
    * `:client_caps_meta` — sent as `initialize` `clientCapabilities._meta`.
    * `:model` — passed through unchanged after `session/new`: first as
      `session/set_config_option {configId:"model"}`, then, if the agent
      rejects that, as `session/set_model`. Both failing fails the connect.
    * `:stderr` — a file path that receives the agent's stderr (appended);
      default: discarded.
    * `:on_permission` — `(params -> option_id | nil)` decides each
      `session/request_permission`; `nil` answers with a `reject*` option.
      Absent ⇒ the first `allow*` option (the model-source default).
    * `:on_update` — `(params -> any)` observes every raw `session/update`.
    * `:environment` / `:env` — extra env vars for the child.
    * `:timeout` — per-RPC-call timeout in ms (default 30000), covering
      `initialize` and `session/new` during connect, and each
      `session/prompt` reply.

  Returns `{:ok, pid}` or `{:error, reason}`.
  """
  @spec connect([String.t()], keyword()) :: {:ok, pid()} | {:error, term()}
  def connect(command, opts \\ []) when is_list(command) do
    GenServer.start_link(__MODULE__, {command, opts})
  end

  @doc """
  Build a semantic `generate` function — `(map() -> map())` — backed by this
  warm ACP session, in the exact shape `Toolnexus.Client.create_in_process/1`
  and `Toolnexus.Agents.Runtime`'s `:in_process` option accept.

      {:ok, acp} = Toolnexus.Acp.connect(["devin", "acp"])
      client = Toolnexus.Client.create_in_process(model: "devin", generate: Toolnexus.Acp.generate(acp))

  Raises on an ACP-level failure (a closed session, a timed-out call, an RPC
  error) — the same failure shape any other `generate` function raising
  surfaces to the caller.
  """
  @spec generate(pid()) :: (map() -> map())
  def generate(pid) do
    fn req ->
      text = assemble_prompt_text(Map.get(req, :messages) || Map.get(req, "messages") || [])

      case prompt(pid, text) do
        {:ok, answer} ->
          %{content: answer}

        {:error, reason} ->
          raise "toolnexus: ACP generate failed: #{inspect(reason)}"
      end
    end
  end

  @doc """
  Send one turn's FULL prompt text and wait for the accumulated
  `agent_message_chunk` reply. Turns on the same session are serialised.
  Exposed as the lower-level primitive `generate/1` builds on; most callers
  should use `generate/1`.
  """
  @spec prompt(pid(), String.t(), timeout()) :: {:ok, String.t()} | {:error, term()}
  def prompt(pid, text, timeout \\ :infinity) do
    GenServer.call(pid, {:prompt, text}, timeout)
  catch
    :exit, reason -> {:error, {:acp_exit, exit_brief(reason)}}
  end

  @doc "Close the session. Idempotent — safe to call more than once."
  @spec close(pid()) :: :ok
  def close(pid) do
    if Process.alive?(pid) do
      GenServer.call(pid, :close, 10_000)
      :ok
    else
      :ok
    end
  catch
    :exit, _ -> :ok
  end

  defp exit_brief({reason, _call}), do: reason
  defp exit_brief(reason), do: reason

  # --------------------------------------------------------------------------
  # prompt assembly — full request every turn + the library-built supersedes
  # marker (ADR 0031's proposed default; spikes/acp/SPIKE.md gate 1).
  # --------------------------------------------------------------------------

  @doc false
  def assemble_prompt_text(messages) do
    transcript =
      messages
      |> Enum.map(fn m -> "#{msg_role(m)}: #{msg_text(m)}" end)
      |> Enum.join("\n")

    latest = messages |> List.last() |> then(fn m -> (m && msg_text(m)) || "" end)
    marker = @supersedes_marker <> " " <> latest

    case transcript do
      "" -> marker
      t -> t <> "\n" <> marker
    end
  end

  defp msg_role(m), do: to_string(Map.get(m, "role") || Map.get(m, :role) || "user")

  defp msg_text(m) do
    content = Map.get(m, "content") || Map.get(m, :content)

    cond do
      is_binary(content) -> content
      is_list(content) -> content |> Enum.map(&extract_part_text/1) |> Enum.join("")
      true -> ""
    end
  end

  defp extract_part_text(%{"text" => t}), do: to_string(t)
  defp extract_part_text(%{text: t}), do: to_string(t)
  defp extract_part_text(_), do: ""

  # --------------------------------------------------------------------------
  # GenServer
  # --------------------------------------------------------------------------

  @impl true
  def init({command, opts}) do
    cfg = %{
      "command" => command,
      "cwd" => Keyword.get(opts, :cwd),
      "environment" => Keyword.get(opts, :environment) || Keyword.get(opts, :env) || %{},
      "stderr_path" => Keyword.get(opts, :stderr)
    }

    timeout = Keyword.get(opts, :timeout, @default_timeout)

    case Stdio.open(cfg) do
      {:ok, stdio} ->
        case handshake(stdio, opts, timeout) do
          {:ok, session_id, sent} ->
            {:ok,
             %{
               stdio: stdio,
               mcp_servers_sent: sent,
               on_permission: Keyword.get(opts, :on_permission),
               on_update: Keyword.get(opts, :on_update),
               buffer: "",
               next_id: 2,
               session_id: session_id,
               active_from: nil,
               active_id: nil,
               chunks: [],
               queue: :queue.new(),
               timeout: timeout,
               closed: false
             }}

          {:error, reason} ->
            Stdio.close(stdio)
            {:stop, reason}
        end

      {:error, reason} ->
        {:stop, {:start, reason}}
    end
  end

  @doc """
  Read-only view of the live session: `%{mcp_servers_sent: [...], os_pid: integer | nil}`.
  """
  @spec info(pid()) :: map()
  def info(pid) do
    GenServer.call(pid, :info, 5_000)
  end

  # The handshake (`initialize` then `session/new`) runs synchronously inside
  # init/1, draining raw Port messages directly — nothing else can be talking
  # to this process yet (the GenServer loop has not started dispatching), so a
  # small blocking read loop here is safe and keeps connect/2 simple: by the
  # time `start_link` returns, the session genuinely exists.
  defp handshake(stdio, opts, timeout) do
    init_id = "c-1"

    caps = %{"fs" => %{"readTextFile" => false, "writeTextFile" => false}, "terminal" => false}

    caps =
      case Keyword.get(opts, :client_caps_meta) do
        nil -> caps
        meta -> Map.put(caps, "_meta", meta)
      end

    send_req(stdio, init_id, "initialize", %{"protocolVersion" => 1, "clientCapabilities" => caps})

    with {:ok, init_result} <-
           tag(:initialize, await_handshake_reply(stdio, init_id, "", timeout, "initialize")) do
      http_ok =
        get_in(init_result || %{}, ["agentCapabilities", "mcpCapabilities", "http"]) == true

      new_id = "c-2"
      cwd = Keyword.get(opts, :cwd) || File.cwd!()
      mcp_servers = Enum.map(Keyword.get(opts, :mcp_servers, []), &wire_server(&1, http_ok))

      send_req(stdio, new_id, "session/new", %{"cwd" => cwd, "mcpServers" => mcp_servers})

      with {:ok, result} <-
             tag(:session_new, await_handshake_reply(stdio, new_id, "", timeout, "session/new")),
           sid = result["sessionId"],
           :ok <- set_model(stdio, sid, Keyword.get(opts, :model), timeout) do
        {:ok, sid, mcp_servers}
      end
    end
  end

  defp tag(_step, {:ok, _} = ok), do: ok
  defp tag(step, {:error, reason}), do: {:error, {step, reason}}

  defp set_model(_stdio, _sid, m, _timeout) when m in [nil, ""], do: :ok

  defp set_model(stdio, sid, model, timeout) do
    send_req(stdio, "c-3", "session/set_config_option", %{
      "sessionId" => sid,
      "configId" => "model",
      "value" => model
    })

    case await_handshake_reply(stdio, "c-3", "", timeout, "session/set_config_option") do
      {:ok, _} ->
        :ok

      {:error, e1} ->
        send_req(stdio, "c-4", "session/set_model", %{"sessionId" => sid, "modelId" => model})

        case await_handshake_reply(stdio, "c-4", "", timeout, "session/set_model") do
          {:ok, _} -> :ok
          {:error, e2} -> {:error, {:set_model, model, e1, e2}}
        end
    end
  end

  @doc false
  # SPEC §8C: an atom-keyed entry picks its wire shape from the agent's
  # advertised http capability; header/env maps go out as sorted [{name,value}].
  def wire_server(%{} = s, http_ok) do
    if Enum.any?(Map.keys(s), &is_atom/1) do
      url = s[:url]
      command = s[:command]

      if url not in [nil, ""] and (http_ok or command in [nil, ""]) do
        %{
          "type" => "http",
          "name" => s[:name],
          "url" => url,
          "headers" => name_values(s[:headers])
        }
      else
        %{
          "name" => s[:name],
          "command" => command,
          "args" => s[:args] || [],
          "env" => name_values(s[:env])
        }
      end
    else
      s
    end
  end

  defp name_values(nil), do: []

  defp name_values(m) do
    m
    |> Enum.sort_by(fn {k, _} -> to_string(k) end)
    |> Enum.map(fn {k, v} -> %{"name" => to_string(k), "value" => to_string(v)} end)
  end

  defp await_handshake_reply(%Stdio{port: port} = stdio, want_id, buffer, timeout, method) do
    receive do
      {^port, {:data, chunk}} ->
        {lines, rest} = Stdio.split_lines(buffer <> chunk)

        case find_reply(lines, want_id, stdio) do
          {:found, result} -> result
          :not_found -> await_handshake_reply(stdio, want_id, rest, timeout, method)
        end

      {^port, {:exit_status, _code}} ->
        {:error, {:agent_exited, method}}
    after
      timeout -> {:error, :timeout}
    end
  end

  defp find_reply([], _id, _stdio), do: :not_found

  defp find_reply([line | rest], id, stdio) do
    case Jason.decode(line) do
      {:ok, %{"id" => ^id, "result" => result}} ->
        {:found, {:ok, result}}

      {:ok, %{"id" => ^id, "error" => err}} ->
        {:found, {:error, {:acp_error, err}}}

      {:ok, %{"method" => m, "id" => rid}} when not is_nil(rid) ->
        method_not_found(stdio, rid, m)
        find_reply(rest, id, stdio)

      _ ->
        find_reply(rest, id, stdio)
    end
  end

  defp method_not_found(stdio, id, method) do
    Stdio.send_msg(stdio, %{
      "jsonrpc" => "2.0",
      "id" => id,
      "error" => %{"code" => -32601, "message" => "method not found: #{method}"}
    })
  end

  defp send_req(stdio, id, method, params) do
    Stdio.send_msg(stdio, %{
      "jsonrpc" => "2.0",
      "id" => id,
      "method" => method,
      "params" => params
    })
  end

  # -- turn serialisation -----------------------------------------------------

  @impl true
  def handle_call({:prompt, _text}, _from, %{closed: true} = state) do
    {:reply, {:error, :closed}, state}
  end

  def handle_call({:prompt, text}, from, %{active_from: nil} = state) do
    {:noreply, start_prompt(state, text, from)}
  end

  def handle_call({:prompt, text}, from, state) do
    # A prior turn on this session is still in flight — one ACP session is
    # one conversation, so this turn is queued rather than interleaved.
    {:noreply, %{state | queue: :queue.in({text, from}, state.queue)}}
  end

  def handle_call(:info, _from, state) do
    {:reply, %{mcp_servers_sent: state.mcp_servers_sent, os_pid: state.stdio.os_pid}, state}
  end

  def handle_call(:close, _from, state) do
    {:stop, :normal, :ok, state}
  end

  defp start_prompt(state, text, from) do
    id = "p-#{state.next_id}"

    Stdio.send_msg(state.stdio, %{
      "jsonrpc" => "2.0",
      "id" => id,
      "method" => "session/prompt",
      "params" => %{
        "sessionId" => state.session_id,
        "prompt" => [%{"type" => "text", "text" => text}]
      }
    })

    %{state | next_id: state.next_id + 1, active_from: from, active_id: id, chunks: []}
  end

  # -- inbound routing ----------------------------------------------------------

  @impl true
  def handle_info({port, {:data, chunk}}, %{stdio: %Stdio{port: port}} = state) do
    {lines, rest} = Stdio.split_lines(state.buffer <> chunk)
    state = %{state | buffer: rest}
    state = Enum.reduce(lines, state, &route_line/2)
    {:noreply, state}
  end

  def handle_info({port, {:exit_status, _code}}, %{stdio: %Stdio{port: port}} = state) do
    state = fail_all(state, {:agent_exited, "session/prompt"})
    {:noreply, %{state | closed: true}}
  end

  def handle_info(_other, state), do: {:noreply, state}

  defp route_line(line, state) do
    case Jason.decode(line) do
      {:ok, msg} when is_map(msg) -> route(msg, state)
      _ -> state
    end
  end

  defp route(%{"id" => id, "result" => _}, %{active_id: id} = state) do
    finish_active(state, {:ok, join_chunks(state)})
  end

  defp route(%{"id" => id, "error" => err}, %{active_id: id} = state) do
    finish_active(state, {:error, {:acp_error, err}})
  end

  defp route(%{"method" => "session/update", "params" => params}, state) do
    if is_function(state.on_update, 1) do
      try do
        state.on_update.(params)
      rescue
        _ -> :ok
      end
    end

    accumulate(state, params)
  end

  defp route(%{"method" => "session/request_permission", "id" => id, "params" => params}, state) do
    answer_permission(state, id, params)
    state
  end

  # An agent->client REQUEST we do not implement (fs/*, terminal/* — declared
  # unsupported in clientCapabilities): answer it, or the agent waits forever.
  defp route(%{"method" => m, "id" => id}, state) when not is_nil(id) do
    method_not_found(state.stdio, id, m)
    state
  end

  defp route(_msg, state), do: state

  defp accumulate(state, %{"update" => %{"sessionUpdate" => "agent_message_chunk"} = update}) do
    text = get_in(update, ["content", "text"]) || ""
    %{state | chunks: [text | state.chunks]}
  end

  defp accumulate(state, _params), do: state

  defp answer_permission(%{stdio: stdio, on_permission: decide}, id, params) do
    options = params["options"] || []

    first_of = fn prefix ->
      Enum.find_value(options, fn o ->
        kind = to_string(o["kind"] || "")
        if String.starts_with?(kind, prefix), do: o["optionId"]
      end)
    end

    chosen =
      if is_function(decide, 1) do
        case safe_decide(decide, params) do
          id when is_binary(id) and id != "" -> id
          _ -> first_of.("reject")
        end
      else
        first_of.("allow")
      end

    result =
      if chosen do
        %{"outcome" => %{"outcome" => "selected", "optionId" => chosen}}
      else
        %{"outcome" => %{"outcome" => "cancelled"}}
      end

    Stdio.send_msg(stdio, %{"jsonrpc" => "2.0", "id" => id, "result" => result})
  end

  defp safe_decide(decide, params) do
    decide.(params)
  rescue
    _ -> nil
  end

  defp join_chunks(state), do: state.chunks |> Enum.reverse() |> Enum.join("")

  defp finish_active(state, outcome) do
    if state.active_from, do: GenServer.reply(state.active_from, outcome)
    state = %{state | active_from: nil, active_id: nil, chunks: []}
    dequeue_next(state)
  end

  defp dequeue_next(state) do
    case :queue.out(state.queue) do
      {{:value, {text, from}}, rest} -> start_prompt(%{state | queue: rest}, text, from)
      {:empty, _} -> state
    end
  end

  defp fail_all(state, reason) do
    if state.active_from, do: GenServer.reply(state.active_from, {:error, reason})

    state.queue
    |> :queue.to_list()
    |> Enum.each(fn {_text, from} -> GenServer.reply(from, {:error, reason}) end)

    %{state | active_from: nil, active_id: nil, queue: :queue.new()}
  end

  @impl true
  def terminate(_reason, state) do
    if state[:stdio] do
      # The agent may be a tree (an `npx …` adapter): collect its descendants
      # while it is alive, close it, then make sure none survive.
      kids = Toolnexus.Cli.Proc.descendants(state.stdio.os_pid)
      Stdio.close(state.stdio)
      Toolnexus.Cli.Proc.kill(kids)
    end

    :ok
  end
end
