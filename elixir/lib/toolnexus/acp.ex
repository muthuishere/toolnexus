defmodule Toolnexus.Acp do
  @moduledoc """
  ACP (Agent Client Protocol) model source — issue #96, ADR 0025, proposal
  `openspec/changes/add-acp-model-source`.

  Connects to a running agent (`devin acp`, Gemini CLI, Zed's agents, ...) over
  a child process's stdin/stdout, speaking JSON-RPC 2.0, one object per line —
  the SAME framing MCP local stdio already uses (SPEC §2), so this module
  reuses `Toolnexus.Mcp.Transport.Stdio` to spawn the child rather than
  hand-rolling a second port-spawning path.

  A **warm session** (ADR 0025 — "the warm session is the feature"): the
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
  reference Go client this ports, and `docs/adr/0025-acp-a-warm-session-is-the-feature.md`.
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
      Real `devin acp` requires the key to be present, even empty.
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
  # marker (ADR 0025's proposed default; spikes/acp/SPIKE.md gate 1).
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
      "environment" => Keyword.get(opts, :environment) || Keyword.get(opts, :env) || %{}
    }

    timeout = Keyword.get(opts, :timeout, @default_timeout)

    case Stdio.open(cfg) do
      {:ok, stdio} ->
        case handshake(stdio, opts, timeout) do
          {:ok, session_id} ->
            {:ok,
             %{
               stdio: stdio,
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
        {:stop, reason}
    end
  end

  # The handshake (`initialize` then `session/new`) runs synchronously inside
  # init/1, draining raw Port messages directly — nothing else can be talking
  # to this process yet (the GenServer loop has not started dispatching), so a
  # small blocking read loop here is safe and keeps connect/2 simple: by the
  # time `start_link` returns, the session genuinely exists.
  defp handshake(stdio, opts, timeout) do
    init_id = "c-1"

    send_req(stdio, init_id, "initialize", %{
      "protocolVersion" => 1,
      "clientCapabilities" => %{"fs" => %{"readTextFile" => false, "writeTextFile" => false}}
    })

    with {:ok, _} <- await_handshake_reply(stdio, init_id, "", timeout) do
      new_id = "c-2"
      cwd = Keyword.get(opts, :cwd) || File.cwd!()
      mcp_servers = Keyword.get(opts, :mcp_servers, [])

      send_req(stdio, new_id, "session/new", %{"cwd" => cwd, "mcpServers" => mcp_servers})

      case await_handshake_reply(stdio, new_id, "", timeout) do
        {:ok, result} -> {:ok, result["sessionId"]}
        {:error, _} = err -> err
      end
    end
  end

  defp await_handshake_reply(%Stdio{port: port} = stdio, want_id, buffer, timeout) do
    receive do
      {^port, {:data, chunk}} ->
        {lines, rest} = Stdio.split_lines(buffer <> chunk)

        case find_reply(lines, want_id) do
          {:found, result} -> result
          :not_found -> await_handshake_reply(stdio, want_id, rest, timeout)
        end

      {^port, {:exit_status, code}} ->
        {:error, {:acp_process_exited, code}}
    after
      timeout -> {:error, :timeout}
    end
  end

  defp find_reply([], _id), do: :not_found

  defp find_reply([line | rest], id) do
    case Jason.decode(line) do
      {:ok, %{"id" => ^id, "result" => result}} -> {:found, {:ok, result}}
      {:ok, %{"id" => ^id, "error" => err}} -> {:found, {:error, {:acp_error, err}}}
      _ -> find_reply(rest, id)
    end
  end

  defp send_req(stdio, id, method, params) do
    Stdio.send_msg(stdio, %{"jsonrpc" => "2.0", "id" => id, "method" => method, "params" => params})
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
    state = fail_all(state, :acp_process_exited)
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
    accumulate(state, params)
  end

  defp route(%{"method" => "session/request_permission", "id" => id, "params" => params}, state) do
    answer_permission(state.stdio, id, params)
    state
  end

  defp route(_msg, state), do: state

  defp accumulate(state, %{"update" => %{"sessionUpdate" => "agent_message_chunk"} = update}) do
    text = get_in(update, ["content", "text"]) || ""
    %{state | chunks: [text | state.chunks]}
  end

  defp accumulate(state, _params), do: state

  defp answer_permission(stdio, id, params) do
    options = params["options"] || []

    chosen =
      Enum.find_value(options, fn o ->
        kind = to_string(o["kind"] || "")
        if String.starts_with?(kind, "allow"), do: o["optionId"]
      end)

    result =
      if chosen do
        %{"outcome" => %{"outcome" => "selected", "optionId" => chosen}}
      else
        %{"outcome" => %{"outcome" => "cancelled"}}
      end

    Stdio.send_msg(stdio, %{"jsonrpc" => "2.0", "id" => id, "result" => result})
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
    if state[:stdio], do: Stdio.close(state.stdio)
    :ok
  end
end
