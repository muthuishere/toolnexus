defmodule Toolnexus.Acp do
  @moduledoc """
  ACP (Agent Client Protocol) model source — issue #96, ADR 0031, proposals
  `openspec/changes/add-acp-model-source` and
  `openspec/changes/add-acp-tool-calling` (SPEC §8 "ACP model source").

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
      the instant it arrives — never exposed to the caller and never
      awaited. By default it selects the first `reject`-kind option (the
      client executes tools, the agent must not); `allow_agent_tools: true`
      selects the first `allow`-kind option instead; no matching option ⇒
      `cancelled`. An unanswered permission request hangs the turn forever
      (even in bypass mode); answering immediately is what this module is FOR.
    * Turns are serialised: one ACP session is one conversation, so a second
      concurrent `generate/1` call is queued rather than interleaved into the
      same transcript.
    * The child process's lifetime is independent of any one turn — a failed
      or errored turn does not kill the session — and `close/1` is
      idempotent.
    * The agent is a real tool-calling MODEL, not a text oracle: every turn
      sends a pinned preamble plus the FULL OpenAI-shaped request as JSON —
      every message (including earlier tool calls and their results) and
      every tool schema — PLUS an explicit supersedes marker built by this
      library (not left to the caller), naming the latest user text — the
      mitigation `spikes/acp/SPIKE.md` gate 1 proved against a stateful
      session that otherwise answers a near-duplicate, stale prompt. The
      agent's JSON reply is parsed back into content or tool calls, which
      the ordinary loop executes through the toolkit (`render_prompt/1`,
      `parse_reply/1`).

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
      Real `devin acp` requires the key to be present, even empty.
    * `:environment` / `:env` — extra env vars for the child.
    * `:timeout` — per-RPC-call timeout in ms (default 30000), covering
      `initialize` and `session/new` during connect, and each
      `session/prompt` reply.
    * `:allow_agent_tools` — let the agent run tools of its OWN: a
      `session/request_permission` then selects the first `allow`-kind
      option instead of the default first `reject`-kind option (default
      `false` — the client executes tools, the agent must not).

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

  Each call sends the assembled request (`render_prompt/1`) as one
  `session/prompt` and parses the accumulated reply (`parse_reply/1`) into
  `%{content: ...}` or `%{tool_calls: [...]}`.

  Raises on an ACP-level failure (a closed session, a timed-out call, an RPC
  error) — the same failure shape any other `generate` function raising
  surfaces to the caller.
  """
  @spec generate(pid()) :: (map() -> map())
  def generate(pid) do
    fn req ->
      case prompt(pid, render_prompt(req)) do
        {:ok, answer} ->
          parse_reply(answer)

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
  # prompt assembly — the pinned preamble + the FULL OpenAI-shaped request
  # every turn + the library-built supersedes marker (SPEC §8 "ACP model
  # source"; ADR 0031; spikes/acp/SPIKE.md gate 1).
  # --------------------------------------------------------------------------

  # Byte-pinned by SPEC §8 — identical in all seven ports.
  @preamble ~S"""
  You are the language model behind a tool-calling client. The client executes tools; you never do.
  Do not run commands, read or edit files, or use any tool of your own.
  The REQUEST below is the complete conversation in OpenAI chat-completions format: "messages" holds every message so far, including earlier tool calls and their results; "tools" lists the only tools you may call.
  Reply with exactly one JSON object and nothing else: no prose, no markdown fences.
  To give the final answer: {"content": "<answer>"}
  To call tools: {"tool_calls": [{"id": "<unique id>", "type": "function", "function": {"name": "<tool name>", "arguments": "<JSON-encoded arguments>"}}]}
  Never both. Use tool results already in "messages" instead of calling the same tool again.
  """

  @doc false
  def preamble, do: @preamble

  @doc """
  Render one turn's prompt text: `PREAMBLE <> "\\nREQUEST:\\n" <> JSON <>
  "\\n\\nSUPERSEDES-ALL-PRIOR: " <> latest_user`.

  `JSON` is a compact `{"messages": [...], "tools": [...]}` (messages first,
  `[]` when absent, never HTML-escaped). The FULL request goes every turn —
  an ACP session is stateful, and a delta would make the client a shadow copy
  of conversation state — and the supersedes marker keeps a stateful agent
  off an earlier near-duplicate in its own history (ADR 0031).
  """
  @spec render_prompt(map()) :: String.t()
  def render_prompt(req) do
    messages = field(req, :messages) || []
    tools = field(req, :tools) || []

    # Built by hand, not from a map, so the key order is messages-then-tools.
    # Jason's default `escape: :json` never HTML-escapes `<`, `>`, `&`.
    payload =
      IO.iodata_to_binary([
        ~s({"messages":),
        Jason.encode_to_iodata!(messages),
        ~s(,"tools":),
        Jason.encode_to_iodata!(tools),
        "}"
      ])

    @preamble <>
      "\nREQUEST:\n" <> payload <> "\n\n" <> @supersedes_marker <> " " <> latest_user(messages)
  end

  # The last `user` message's content; with none, the last message's; with no
  # messages, "".
  defp latest_user(messages) do
    case Enum.find(Enum.reverse(messages), &(field(&1, :role) in ["user", :user])) ||
           List.last(messages) do
      nil -> ""
      m -> content_text(field(m, :content))
    end
  end

  # A string as is; an array of parts ⇒ the text of its type:"text" parts
  # joined by one space; anything else ⇒ "".
  defp content_text(content) when is_binary(content), do: content

  defp content_text(content) when is_list(content) do
    content
    |> Enum.filter(fn p -> field(p, :type) in ["text", :text] and is_binary(field(p, :text)) end)
    |> Enum.map_join(" ", &field(&1, :text))
  end

  defp content_text(_), do: ""

  # Messages arrive string-keyed off the wire, atom-keyed from a direct
  # `generate` caller; accept either.
  defp field(m, key) when is_map(m) do
    case Map.fetch(m, Atom.to_string(key)) do
      {:ok, v} -> v
      :error -> Map.get(m, key)
    end
  end

  defp field(_, _), do: nil

  # --------------------------------------------------------------------------
  # reply parsing — the agent's text becomes ONE assistant message.
  # --------------------------------------------------------------------------

  @doc """
  Parse the agent's accumulated reply text into one assistant message, by the
  algorithm SPEC §8 pins: strip fences, parse (or the first-`{`..last-`}`
  slice), unwrap `choices[0].message` / `message`, then `tool_calls` ⇒
  `%{tool_calls: [...]}`, `content` ⇒ `%{content: ...}`, anything else ⇒
  `%{content: text}` with the ORIGINAL text untouched (e.g. structured output
  the host asked for).

  A tool call is `%{name: ..., arguments: ...}` plus `:id` only when the
  agent sent a non-empty string id; `arguments` is a string (pre-encoded) as
  sent, `%{}` when absent/null, else the structured value.
  """
  @spec parse_reply(String.t()) :: map()
  def parse_reply(text) do
    s = text |> String.trim() |> strip_fences()

    case parse_object(s) || parse_braced(s) do
      nil -> %{content: text}
      obj -> obj |> unwrap_envelope() |> from_envelope(text)
    end
  end

  defp strip_fences("```" <> _ = s) do
    rest =
      case :binary.split(s, "\n") do
        [_first_line, rest] -> rest
        [_] -> ""
      end

    rest |> String.trim() |> String.replace_suffix("```", "") |> String.trim()
  end

  defp strip_fences(s), do: s

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

  defp unwrap_envelope(%{"choices" => [%{"message" => %{} = msg} | _]}), do: msg
  defp unwrap_envelope(%{"choices" => [_ | _]} = obj), do: obj
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
        args =
          case Map.get(func, "arguments") do
            nil -> %{}
            a -> a
          end

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
               allow_agent_tools: Keyword.get(opts, :allow_agent_tools, false) == true,
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
    answer_permission(state, id, params)
    state
  end

  defp route(_msg, state), do: state

  defp accumulate(state, %{"update" => %{"sessionUpdate" => "agent_message_chunk"} = update}) do
    text = get_in(update, ["content", "text"]) || ""
    %{state | chunks: [text | state.chunks]}
  end

  defp accumulate(state, _params), do: state

  # The first `reject`-kind option by default (the client executes tools, the
  # agent must not), or the first `allow`-kind option with
  # `allow_agent_tools: true`; no matching option ⇒ cancelled.
  defp answer_permission(state, id, params) do
    options = params["options"] || []
    want = if state.allow_agent_tools, do: "allow", else: "reject"

    chosen =
      Enum.find_value(options, fn o ->
        kind = to_string(o["kind"] || "")
        if String.starts_with?(kind, want), do: o["optionId"]
      end)

    result =
      if chosen do
        %{"outcome" => %{"outcome" => "selected", "optionId" => chosen}}
      else
        %{"outcome" => %{"outcome" => "cancelled"}}
      end

    Stdio.send_msg(state.stdio, %{"jsonrpc" => "2.0", "id" => id, "result" => result})
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
