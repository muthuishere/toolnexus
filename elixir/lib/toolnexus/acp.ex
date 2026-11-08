defmodule Toolnexus.Acp do
  @moduledoc """
  ACP (Agent Client Protocol) model source — issue #96, ADR 0031, proposal
  `openspec/changes/add-acp-model-source`.

  Connects to any agent that speaks ACP over
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
      break JSON parsing outright. `generate_stream/1` REPORTS them as they
      arrive (thought, tool_call, tool_result events tagged `via: "acp"`), but
      they never become part of the answer.
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
  Spawn `command` (a list, e.g. `["my-agent", "acp"]`) and complete the ACP
  handshake: `initialize` then `session/new`.

  Options:

    * `:cwd` — **absolute** working directory advertised to the agent.
      Defaults to `File.cwd!()`. Real agents reject `session/new`
      with `-32602 Invalid params` on a relative or missing `cwd`.
    * `:mcp_servers` — a list of JSON objects (string-keyed maps) sent as
      `session/new` `mcpServers` VERBATIM and in order (SPEC §8C): toolnexus
      does not validate, rename or reshape them. Default `[]`.
    * `:session_meta` — a JSON object sent as `session/new` `_meta`; the key
      is absent when not set.
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

      {:ok, acp} = Toolnexus.Acp.connect(["my-agent", "acp"])
      client = Toolnexus.Client.create_in_process(model: "acp", generate: Toolnexus.Acp.generate(acp))

  Raises on an ACP-level failure (a closed session, a timed-out call, an RPC
  error) — the same failure shape any other `generate` function raising
  surfaces to the caller.
  """
  @spec generate(pid()) :: (map() -> map())
  def generate(pid) do
    stream = generate_stream(pid)
    fn req -> stream.(req, nil) end
  end

  @doc """
  The streaming form of `generate/1`: a `(req, emit) -> map()` function in the
  shape `Toolnexus.Client.create_in_process/1`'s `:generate_stream` accepts.

      client =
        Toolnexus.Client.create_in_process(model: "acp", generate_stream: Toolnexus.Acp.generate_stream(acp))

  `emit` is called from the session's read loop AS EACH `session/update`
  arrives — not after the turn:

    * `agent_message_chunk` ⇒ `%{type: "text", delta: text}`
    * `agent_thought_chunk` ⇒ `%{type: "thought", delta: text, via: "acp"}` —
      reported, never part of the answer
    * `tool_call` ⇒ `%{type: "tool_call", id:, name: title, args: rawInput, via: "acp"}`
    * a `completed`/`failed` `tool_call_update` ⇒
      `%{type: "tool_result", id:, name:, output:, is_error:, via: "acp"}`

  The agent's own tool calls are reports — the loop never executes them. A
  `nil` emit is the non-streaming `generate/1`.
  """
  @spec generate_stream(pid()) :: (map(), (map() -> any()) | nil -> map())
  def generate_stream(pid) do
    fn req, emit ->
      text = assemble_prompt_text(Map.get(req, :messages) || Map.get(req, "messages") || [])

      case prompt(pid, text, :infinity, emit) do
        {:ok, answer} ->
          %{content: answer}

        {:error, reason} ->
          raise "toolnexus: ACP generate failed: " <> describe_error(reason)
      end
    end
  end

  @doc """
  Send one turn's FULL prompt text and wait for the accumulated
  `agent_message_chunk` reply. Turns on the same session are serialised.
  Exposed as the lower-level primitive `generate/1` builds on; most callers
  should use `generate/1`. `emit` (optional) receives the turn's stream events
  as they arrive — see `generate_stream/1`.
  """
  @spec prompt(pid(), String.t(), timeout(), (map() -> any()) | nil) ::
          {:ok, String.t()} | {:error, term()}
  def prompt(pid, text, timeout \\ :infinity, emit \\ nil) do
    GenServer.call(pid, {:prompt, text, emit}, timeout)
  catch
    :exit, reason -> {:error, {:acp_exit, exit_brief(reason)}}
  end

  @doc """
  Change the agent's mode on this RUNNING session (SPEC §8C, #197): sends
  `session/set_mode {sessionId, modeId}` at once — no restart, same session.
  The value passes through unvalidated. An agent rejection fails only this
  call; the session stays usable.
  """
  @spec set_mode(pid(), String.t()) :: :ok | {:error, term()}
  def set_mode(pid, mode), do: control(pid, {:set_mode, mode})

  @doc """
  Change the agent's model on this RUNNING session (SPEC §8C, #197) — the
  `:model` connect option's logic: `session/set_config_option
  {configId:"model"}`, then `session/set_model` if the agent rejects that.
  Both failing returns `{:error, {:set_model, model, e1, e2}}`.
  """
  @spec set_model(pid(), String.t()) :: :ok | {:error, term()}
  def set_model(pid, model), do: control(pid, {:set_model, model})

  defp control(pid, req) do
    GenServer.call(pid, {:control, req}, :infinity)
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

  @doc false
  # Human-readable failure; the agent-exit wording is pinned by SPEC §8C.
  def describe_error({:agent_exited, method}), do: "agent exited before replying to #{method}"
  def describe_error({step, {:agent_exited, _} = r}) when is_atom(step), do: describe_error(r)
  def describe_error(:closed), do: "session is closed"
  def describe_error(other), do: inspect(other)

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
          {:ok, session_id} ->
            {:ok,
             %{
               stdio: stdio,
               on_permission: Keyword.get(opts, :on_permission),
               on_update: Keyword.get(opts, :on_update),
               buffer: "",
               next_id: 2,
               session_id: session_id,
               active_from: nil,
               active_id: nil,
               chunks: [],
               emit: nil,
               tool_names: %{},
               queue: :queue.new(),
               timeout: timeout,
               closed: false,
               ctl: %{}
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
  Read-only view of the live session: `%{os_pid: integer | nil}`.
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

    with {:ok, _init_result} <-
           tag(:initialize, await_handshake_reply(stdio, init_id, "", timeout, "initialize")) do
      new_id = "c-2"
      cwd = Keyword.get(opts, :cwd) || File.cwd!()
      params = %{"cwd" => cwd, "mcpServers" => Keyword.get(opts, :mcp_servers) || []}

      params =
        case Keyword.get(opts, :session_meta) do
          nil -> params
          meta -> Map.put(params, "_meta", meta)
        end

      send_req(stdio, new_id, "session/new", params)

      with {:ok, result} <-
             tag(:session_new, await_handshake_reply(stdio, new_id, "", timeout, "session/new")),
           sid = result["sessionId"],
           :ok <- set_model(stdio, sid, Keyword.get(opts, :model), timeout) do
        {:ok, sid}
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
  def handle_call({:prompt, _text, _emit}, _from, %{closed: true} = state) do
    {:reply, {:error, :closed}, state}
  end

  def handle_call({:prompt, text, emit}, from, %{active_from: nil} = state) do
    {:noreply, start_prompt(state, {text, emit}, from)}
  end

  def handle_call({:prompt, text, emit}, from, state) do
    # A prior turn on this session is still in flight — one ACP session is
    # one conversation, so this turn is queued rather than interleaved.
    {:noreply, %{state | queue: :queue.in({{text, emit}, from}, state.queue)}}
  end

  def handle_call({:control, _req}, _from, %{closed: true} = state) do
    {:reply, {:error, :closed}, state}
  end

  def handle_call({:control, {:set_mode, mode}}, from, state) do
    {:noreply,
     ctl_send(state, from, :mode, mode, nil, "session/set_mode", %{
       "sessionId" => state.session_id,
       "modeId" => mode
     })}
  end

  def handle_call({:control, {:set_model, model}}, from, state) do
    {:noreply, ctl_send_config(state, from, model)}
  end

  def handle_call(:info, _from, state) do
    {:reply, %{os_pid: state.stdio.os_pid}, state}
  end

  def handle_call(:close, _from, state) do
    {:stop, :normal, :ok, state}
  end

  defp start_prompt(state, {text, emit}, from) do
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

    %{
      state
      | next_id: state.next_id + 1,
        active_from: from,
        active_id: id,
        chunks: [],
        emit: emit,
        tool_names: %{}
    }
  end

  # -- live mode/model switch (#197): ids "k-N", routed apart from turns ------

  defp ctl_send_config(state, from, model) do
    ctl_send(state, from, :config, model, nil, "session/set_config_option", %{
      "sessionId" => state.session_id,
      "configId" => "model",
      "value" => model
    })
  end

  defp ctl_send(state, from, kind, value, e1, method, params) do
    id = "k-#{state.next_id}"
    send_req(state.stdio, id, method, params)
    %{state | next_id: state.next_id + 1, ctl: Map.put(state.ctl, id, {from, kind, value, e1})}
  end

  defp ctl_done(state, id, outcome) do
    {{from, kind, value, e1}, ctl} = Map.pop(state.ctl, id)
    state = %{state | ctl: ctl}

    case {kind, outcome} do
      {_, :ok} ->
        GenServer.reply(from, :ok)
        state

      {:config, {:error, e}} ->
        ctl_send(state, from, :model, value, e, "session/set_model", %{
          "sessionId" => state.session_id,
          "modelId" => value
        })

      {:model, {:error, e2}} ->
        GenServer.reply(from, {:error, {:set_model, value, e1, e2}})
        state

      {:mode, {:error, e}} ->
        GenServer.reply(from, {:error, e})
        state
    end
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

  defp route(%{"id" => id} = msg, %{ctl: ctl} = state)
       when is_map_key(ctl, id) and not is_map_key(msg, "method") do
    case msg do
      %{"error" => err} -> ctl_done(state, id, {:error, {:acp_error, err}})
      _ -> ctl_done(state, id, :ok)
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
    text = update_text(update)
    if text != "", do: emit_event(state, %{type: "text", delta: text})
    %{state | chunks: [text | state.chunks]}
  end

  # Reported, never part of the answer.
  defp accumulate(state, %{"update" => %{"sessionUpdate" => "agent_thought_chunk"} = update}) do
    text = update_text(update)
    if text != "", do: emit_event(state, %{type: "thought", delta: text, via: "acp"})
    state
  end

  defp accumulate(state, %{"update" => %{"sessionUpdate" => "tool_call"} = u}) do
    id = u["toolCallId"]
    name = if u["title"] in [nil, ""], do: u["kind"] || "", else: u["title"]

    emit_event(state, %{
      type: "tool_call",
      id: id,
      name: name,
      args: if(is_map(u["rawInput"]), do: u["rawInput"], else: %{}),
      via: "acp"
    })

    %{state | tool_names: Map.put(state.tool_names, id, name)}
  end

  # SPEC §7E (#188): a non-empty title is remembered as the call's name; a
  # non-empty rawInput object emits tool_call_args before any tool_result.
  defp accumulate(state, %{"update" => %{"sessionUpdate" => "tool_call_update"} = u}) do
    id = u["toolCallId"]

    names =
      if is_binary(u["title"]) and u["title"] != "",
        do: Map.put(state.tool_names, id, u["title"]),
        else: state.tool_names

    state = %{state | tool_names: names}
    name = Map.get(names, id, "")

    if is_map(u["rawInput"]) and map_size(u["rawInput"]) > 0 do
      emit_event(state, %{
        type: "tool_call_args",
        id: id,
        name: name,
        args: u["rawInput"],
        via: "acp"
      })
    end

    if u["status"] in ["completed", "failed"] do
      emit_event(state, %{
        type: "tool_result",
        id: id,
        name: name,
        output: tool_content_text(u["content"]),
        is_error: u["status"] == "failed",
        via: "acp"
      })
    end

    state
  end

  defp accumulate(state, _params), do: state

  defp update_text(update) do
    case update["content"] do
      %{"text" => t} when is_binary(t) -> t
      _ -> ""
    end
  end

  defp tool_content_text(parts) when is_list(parts) do
    Enum.map_join(parts, fn
      %{"content" => %{"text" => t}} when is_binary(t) -> t
      _ -> ""
    end)
  end

  defp tool_content_text(_), do: ""

  defp emit_event(%{emit: emit}, ev) when is_function(emit, 1) do
    emit.(ev)
  rescue
    _ -> :ok
  end

  defp emit_event(_state, _ev), do: :ok

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
    state = %{state | active_from: nil, active_id: nil, chunks: [], emit: nil, tool_names: %{}}
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

    Enum.each(state.ctl, fn {_id, {from, _, _, _}} ->
      GenServer.reply(from, {:error, {:agent_exited, "session/set_mode"}})
    end)

    %{state | active_from: nil, active_id: nil, emit: nil, queue: :queue.new(), ctl: %{}}
  end

  @impl true
  def terminate(_reason, state) do
    if state[:stdio] do
      # The agent may be a tree (an `npx …` adapter): collect its descendants
      # while it is alive, close it, then make sure none survive.
      kids = Toolnexus.Acp.Proc.descendants(state.stdio.os_pid)
      Stdio.close(state.stdio)
      Toolnexus.Acp.Proc.kill(kids)
    end

    :ok
  end
end
