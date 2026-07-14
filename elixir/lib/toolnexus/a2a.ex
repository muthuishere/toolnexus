defmodule Toolnexus.A2a do
  @moduledoc """
  A2A (agent-to-agent) — OUTBOUND: call remote A2A agents (SPEC §7A).

  A remote agent publishes an Agent Card at `/.well-known/agent-card.json`
  describing its skills. Each advertised skill becomes a uniform
  `Toolnexus.Tool` (`source: "a2a"`) whose execute performs one JSON-RPC
  `SendMessage` (non-blocking) to get a Task id, then polls `GetTask` until
  the Task reaches a terminal state — mapping the Task's artifact/message
  text to a ToolResult.

  This is a genuine subset of real A2A (JSON-RPC 2.0 over one HTTP POST
  endpoint), byte-identical with `js/src/a2a.ts`. `${ENV_VAR}` header values
  expand from the environment at call time and are never logged.
  """

  alias Toolnexus.{Tool, ToolResult}
  alias Toolnexus.Mcp.Transport.StreamableHttp

  @default_timeout 300_000
  @default_poll_every 1_000

  # Terminal A2A task states — polling stops once one of these is reached.
  @terminal MapSet.new(["completed", "failed", "canceled"])

  @task_schema %{
    "type" => "object",
    "properties" => %{
      "task" => %{
        "type" => "string",
        "description" => "The task to send to the agent, in natural language."
      }
    },
    "required" => ["task"],
    "additionalProperties" => false
  }

  @doc """
  Build an Agent descriptor pointing at a remote Agent Card URL.

  Options (keyword list or map): `:card` (required — the card URL),
  `:headers` (`${ENV_VAR}` values expand at call time, never logged),
  `:timeout` (overall poll budget in ms, default #{@default_timeout}),
  `:poll_every` (interval between `GetTask` polls in ms, default #{@default_poll_every}).
  """
  @spec agent(keyword() | map()) :: map()
  def agent(opts) do
    opts = Map.new(opts)

    %{
      card: Map.fetch!(opts, :card),
      headers: Map.get(opts, :headers),
      timeout: Map.get(opts, :timeout),
      poll_every: Map.get(opts, :poll_every)
    }
  end

  @doc """
  Parse an `agents` config block (`%{id => %{"card" => ..., ...}}`, mirroring
  `mcpServers`) into Agent descriptors, skipping disabled entries (MCP
  `isEnabled` precedence: `disabled: true` wins, then `enabled: false`). The
  config key is only an identifier — a tool's name prefix comes from the
  fetched card's `name`, not the key. Entries are resolved in sorted-key order.
  """
  @spec parse_agents_config(map() | nil) :: [map()]
  def parse_agents_config(nil), do: []

  def parse_agents_config(block) when is_map(block) do
    block
    |> Map.keys()
    |> Enum.sort()
    |> Enum.map(&Map.get(block, &1))
    |> Enum.filter(fn cfg -> is_map(cfg) and is_binary(cfg["card"]) and enabled?(cfg) end)
    |> Enum.map(fn cfg ->
      agent(
        card: cfg["card"],
        headers: cfg["headers"],
        timeout: cfg["timeout"],
        poll_every: cfg["pollEvery"]
      )
    end)
  end

  defp enabled?(cfg) do
    cond do
      cfg["disabled"] == true -> false
      cfg["enabled"] == false -> false
      true -> true
    end
  end

  @doc """
  Resolve an Agent to its tools: fetch the card, read `skills[]`, and produce
  one Tool per skill (name = `sanitize(card.name) <> "_" <> sanitize(skill id)`).
  Raises on a card fetch/parse failure — the toolkit isolates it.
  """
  @spec agent_tools(map()) :: [Tool.t()]
  def agent_tools(ag) do
    timeout = ag[:timeout] || @default_timeout
    headers = StreamableHttp.expand_env_headers(ag[:headers])
    card = fetch_card(ag[:card], headers, timeout)
    agent_name = card["name"] || "agent"
    # The card's `url` is the JSON-RPC endpoint; fall back to the card origin.
    endpoint = card["url"] || origin(ag[:card])
    Enum.map(card["skills"] || [], &skill_tool(agent_name, endpoint, &1, ag))
  end

  # ---------------------------------------------------------------------------
  # Skill → Tool.
  # ---------------------------------------------------------------------------

  defp skill_tool(agent_name, endpoint, skill, ag) do
    skill_id = skill["id"] || skill["name"] || ""
    name = Tool.sanitize(agent_name) <> "_" <> Tool.sanitize(skill_id)
    timeout = ag[:timeout] || @default_timeout
    poll_every = ag[:poll_every] || @default_poll_every

    %Tool{
      name: name,
      description: skill["description"] || skill["name"] || skill_id,
      input_schema: @task_schema,
      source: "a2a",
      execute: fn args, ctx ->
        execute(agent_name, endpoint, ag, args, ctx, timeout, poll_every)
      end
    }
  end

  # One SendMessage then poll GetTask until terminal / timeout (SPEC §7A).
  defp execute(agent_name, endpoint, ag, args, ctx, timeout, poll_every) do
    start = now_ms()
    budget = (ctx && ctx.timeout) || timeout
    headers = StreamableHttp.expand_env_headers(ag[:headers])
    task_text = to_string(Map.get(args || %{}, "task") || Map.get(args || %{}, :task) || "")

    state = %{agent: agent_name, task_id: "", state: "submitted", polls: 0, start: start}

    try do
      # 1. SendMessage (non-blocking) → a submitted Task.
      task =
        json_rpc(
          endpoint,
          "SendMessage",
          %{
            "message" => %{
              "role" => "user",
              "messageId" => uuid4(),
              "parts" => [%{"kind" => "text", "text" => task_text}]
            },
            "configuration" => %{"blocking" => false}
          },
          headers,
          budget
        )

      state = %{
        state
        | task_id: (task && task["id"]) || "",
          state: get_in(task, ["status", "state"]) || "submitted"
      }

      # 2. Poll GetTask until terminal / timeout.
      poll(endpoint, headers, budget, poll_every, task, state)
    rescue
      e -> %ToolResult{output: Exception.message(e), is_error: true, metadata: meta(state)}
    end
  end

  defp poll(endpoint, headers, budget, poll_every, task, state) do
    if MapSet.member?(@terminal, state.state) do
      # 3. Map the terminal Task → ToolResult.
      if state.state == "completed" do
        %ToolResult{output: extract_output(task), is_error: false, metadata: meta(state)}
      else
        detail = status_message_text(task)
        suffix = if detail == "", do: "", else: ": #{detail}"

        %ToolResult{
          output: "A2A task #{state.task_id} #{state.state}#{suffix}",
          is_error: true,
          metadata: meta(state)
        }
      end
    else
      if now_ms() - state.start >= budget do
        %ToolResult{
          output: "A2A task #{state.task_id} timed out after #{budget}ms (state=#{state.state})",
          is_error: true,
          metadata: meta(state)
        }
      else
        Process.sleep(poll_every)
        task = json_rpc(endpoint, "GetTask", %{"id" => state.task_id}, headers, budget)
        state = %{state | polls: state.polls + 1}
        state = %{state | state: get_in(task, ["status", "state"]) || state.state}
        poll(endpoint, headers, budget, poll_every, task, state)
      end
    end
  end

  defp meta(state) do
    %{
      agent: state.agent,
      task_id: state.task_id,
      state: state.state,
      polls: state.polls,
      ms: now_ms() - state.start
    }
  end

  # ---------------------------------------------------------------------------
  # JSON-RPC transport (single POST) + card fetch.
  # ---------------------------------------------------------------------------

  # POST one JSON-RPC 2.0 request and return `result` (raises on error/non-2xx).
  defp json_rpc(endpoint, method, params, headers, timeout) do
    body = %{"jsonrpc" => "2.0", "id" => uuid4(), "method" => method, "params" => params}

    resp =
      request!(
        method: :post,
        url: endpoint,
        headers: Map.to_list(headers) ++ [{"content-type", "application/json"}],
        body: Jason.encode!(body),
        receive_timeout: timeout
      )

    text = to_text(resp.body)
    if resp.status not in 200..299, do: raise("HTTP #{resp.status}: #{text}")

    case safe_parse(text) do
      %{"error" => %{} = error} ->
        raise to_string(error["message"] || String.trim("JSON-RPC error #{error["code"]}"))

      %{"result" => result} ->
        result

      _ ->
        nil
    end
  end

  # GET and parse the Agent Card at its URL.
  defp fetch_card(card_url, headers, timeout) do
    resp =
      request!(
        method: :get,
        url: card_url,
        headers: Map.to_list(headers),
        receive_timeout: timeout
      )

    text = to_text(resp.body)
    if resp.status not in 200..299, do: raise("HTTP #{resp.status}: #{text}")
    Jason.decode!(text)
  end

  defp request!(opts) do
    case Req.request([retry: false, decode_body: false] ++ opts) do
      {:ok, resp} -> resp
      {:error, e} -> raise e
    end
  end

  # Req with decode_body: false always yields a binary ("" for empty bodies).
  defp to_text(body), do: if(is_binary(body), do: body, else: "")

  defp safe_parse(text) do
    case Jason.decode(text) do
      {:ok, v} -> v
      _ -> nil
    end
  end

  # ---------------------------------------------------------------------------
  # Task text extraction (mirrors js extractOutput / statusMessageText).
  # ---------------------------------------------------------------------------

  # Concatenate a Task's text output: artifact text parts, else last agent message.
  defp extract_output(task) do
    parts =
      for artifact <- (task && task["artifacts"]) || [],
          part <- artifact["parts"] || [],
          part["kind"] == "text",
          is_binary(part["text"]),
          do: part["text"]

    if parts != [] do
      Enum.join(parts, "\n")
    else
      # Fallback: the last agent message in history.
      ((task && task["history"]) || [])
      |> Enum.reverse()
      |> Enum.find_value("", fn msg ->
        if msg["role"] == "agent" do
          text =
            (msg["parts"] || [])
            |> Enum.filter(&(&1["kind"] == "text" and is_binary(&1["text"])))
            |> Enum.map_join("\n", & &1["text"])

          if text == "", do: nil, else: text
        end
      end)
    end
  end

  # Text of a Task's status.message (used for failed/canceled error output).
  defp status_message_text(task) do
    case task && task["status"] do
      %{"message" => %{} = msg} ->
        (msg["parts"] || [])
        |> Enum.filter(&(&1["kind"] == "text" and is_binary(&1["text"])))
        |> Enum.map_join("\n", & &1["text"])

      _ ->
        ""
    end
  end

  # ---------------------------------------------------------------------------
  # Helpers.
  # ---------------------------------------------------------------------------

  defp origin(url) do
    uri = URI.parse(url)
    # JS URL.origin parity: the port is omitted when it is the scheme default.
    port =
      if uri.port && uri.port != URI.default_port(uri.scheme || ""),
        do: ":#{uri.port}",
        else: ""

    "#{uri.scheme}://#{uri.host}#{port}"
  end

  @doc false
  # RFC 4122 v4 UUID (random), used for JSON-RPC ids / messageIds / task ids.
  def uuid4 do
    <<a::binary-size(4), b::binary-size(2), c1, c2, d1, d2, e::binary-size(6)>> =
      :crypto.strong_rand_bytes(16)

    c = <<Bitwise.bor(Bitwise.band(c1, 0x0F), 0x40), c2>>
    d = <<Bitwise.bor(Bitwise.band(d1, 0x3F), 0x80), d2>>
    Enum.map_join([a, b, c, d, e], "-", &Base.encode16(&1, case: :lower))
  end

  defp now_ms, do: System.monotonic_time(:millisecond)
end
