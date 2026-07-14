defmodule Toolnexus.Serve do
  @moduledoc """
  A2A (agent-to-agent) — INBOUND: serve a toolkit as a remote A2A agent
  (SPEC §7B), with the MCP profile co-mounted at `/mcp` (SPEC §7C).

  `Toolnexus.Toolkit.serve(toolkit, addr, opts)` stands up a Bandit HTTP
  server that, when the `a2a` profile is present, exposes:

    * `GET /.well-known/agent-card.json` → an Agent Card built from the
      toolkit's **skills** (SKILL.md name + description), never raw tools.
    * `POST /` → JSON-RPC 2.0: `SendMessage` (submit a Task, fulfil it
      asynchronously via the client loop) + `GetTask` (poll).

  A §10 suspension in the fulfilment run surfaces as the protocol's
  `input-required` Task state carrying the request prompt — never a false
  `completed`. Task persistence is a pluggable TaskStore (in-memory default;
  `"file:<dir>"` / custom via `a2a.store`). When neither profile is present,
  every request answers 404.
  """

  alias Toolnexus.A2a
  alias Toolnexus.McpServe

  @card_path "/.well-known/agent-card.json"
  @protocol_version "0.3.0"
  @default_version "0.1.0"

  # ---------------------------------------------------------------------------
  # TaskStore — pluggable persistence for served Tasks.
  # ---------------------------------------------------------------------------

  defmodule TaskStore do
    @moduledoc "Pluggable Task persistence: `get(store, id)` and `save(store, task)`."
    @callback get(store :: struct(), id :: String.t()) :: map() | nil
    @callback save(store :: struct(), task :: map()) :: any()
  end

  defmodule InMemoryTaskStore do
    @moduledoc "Default store — Tasks live only for the lifetime of the process."
    @behaviour Toolnexus.Serve.TaskStore

    defstruct [:pid]

    def new do
      {:ok, pid} = Agent.start(fn -> %{} end)
      %__MODULE__{pid: pid}
    end

    @impl true
    def get(%__MODULE__{pid: pid}, id), do: Agent.get(pid, &Map.get(&1, id))

    @impl true
    def save(%__MODULE__{pid: pid}, task),
      do: Agent.update(pid, &Map.put(&1, task["id"], task))
  end

  defmodule FileTaskStore do
    @moduledoc "File-backed store — one JSON file per Task id, under `dir`."
    @behaviour Toolnexus.Serve.TaskStore

    defstruct [:dir]

    def new(dir) do
      File.mkdir_p!(dir)
      %__MODULE__{dir: dir}
    end

    defp file(%__MODULE__{dir: dir}, id), do: Path.join(dir, Toolnexus.Tool.sanitize(id) <> ".json")

    @impl true
    def get(store, id) do
      case File.read(file(store, id)) do
        {:ok, raw} ->
          case Jason.decode(raw) do
            {:ok, task} -> task
            _ -> nil
          end

        _ ->
          nil
      end
    end

    @impl true
    def save(store, task) do
      # Atomic write: a concurrent get() must never read a half-written file.
      target = file(store, task["id"])
      tmp = target <> ".tmp"
      File.write!(tmp, Jason.encode!(task))
      File.rename!(tmp, target)
    end
  end

  @doc """
  Map a `store` selector to a concrete TaskStore: `nil` | `"memory"` ⇒
  in-memory (default); `"file:<dir>"` ⇒ file store; a struct ⇒ used as-is.
  """
  def resolve_store(nil), do: InMemoryTaskStore.new()
  def resolve_store("memory"), do: InMemoryTaskStore.new()
  def resolve_store("file:" <> dir), do: FileTaskStore.new(dir)

  def resolve_store(store) when is_binary(store),
    do: raise(ArgumentError, ~s{Unknown A2A store: "#{store}" (expected "memory" or "file:<dir>")})

  def resolve_store(store) when is_struct(store), do: store

  # ---------------------------------------------------------------------------
  # Handle.
  # ---------------------------------------------------------------------------

  defmodule Handle do
    @moduledoc "Handle returned by `serve` — the base URL + port plus stop/close."
    defstruct [:url, :port, :pid]

    @type t :: %__MODULE__{url: String.t(), port: :inet.port_number(), pid: pid()}
  end

  @doc "Stop the server."
  @spec stop(Handle.t()) :: :ok
  def stop(%Handle{pid: pid}) do
    if Process.alive?(pid) do
      try do
        ThousandIsland.stop(pid, 1_000)
      catch
        # Already shutting down (e.g. the owner is exiting concurrently).
        :exit, _ -> :ok
      end
    end

    :ok
  end

  @doc "Alias for `stop/1`."
  @spec close(Handle.t()) :: :ok
  def close(handle), do: stop(handle)

  # ---------------------------------------------------------------------------
  # Card builder.
  # ---------------------------------------------------------------------------

  @doc """
  Build an Agent Card from the toolkit's skills (never raw tools), filtered to
  `a2a.skills` when given. `url` is the JSON-RPC endpoint peers POST to.
  """
  def build_agent_card(cfg, skills, url) do
    selected =
      case cfg_get(cfg, :skills) do
        wanted when is_list(wanted) -> Enum.filter(skills, &(&1.name in wanted))
        _ -> skills
      end

    card = %{
      "name" => cfg_get(cfg, :name) || "toolnexus-agent",
      "description" => cfg_get(cfg, :description) || "",
      "version" => cfg_get(cfg, :version) || @default_version,
      "protocolVersion" => @protocol_version,
      "capabilities" => %{"streaming" => false, "pushNotifications" => false},
      "defaultInputModes" => ["text"],
      "defaultOutputModes" => ["text"],
      "skills" =>
        Enum.map(selected, fn s ->
          %{"id" => s.name, "name" => s.name, "description" => s.description || ""}
        end),
      "url" => url
    }

    case cfg_get(cfg, :provider) do
      nil -> card
      provider -> Map.put(card, "provider", provider_map(provider))
    end
  end

  defp provider_map(provider) do
    %{
      "organization" => cfg_get(provider, :organization),
      "url" => cfg_get(provider, :url)
    }
  end

  # ---------------------------------------------------------------------------
  # Server.
  # ---------------------------------------------------------------------------

  @doc """
  Start the HTTP server. Delegated to by `Toolnexus.Toolkit.serve/3`. Options:

    * `:a2a` — the A2A profile map (mounts the card + JSON-RPC routes)
    * `:skills` — the toolkit's `Skill.Info`s, for the card
    * `:run_task` — `(text, context_id) -> RunResult` fulfilment fun
    * `:on_task` — fires on each Task's terminal state
    * `:mcp` — the MCP serve profile map (mounts `/mcp`)
    * `:mcp_tools` — the toolkit's unified tools, exposed by the MCP profile
    * `:on_call` — fires on each inbound MCP `tools/call`

  When no profile is present the server answers 404 to everything.
  """
  @spec start(String.t(), keyword()) :: Handle.t()
  def start(addr, opts) do
    {host, port} = split_addr(addr)
    a2a = opts[:a2a]
    mcp = opts[:mcp]
    store = if a2a, do: resolve_store(cfg_get(a2a, :store))
    exposed = if mcp, do: McpServe.exposed_tools(opts[:mcp_tools] || [], mcp), else: []
    {:ok, base_agent} = Agent.start(fn -> nil end)

    state = %{
      a2a: a2a,
      store: store,
      skills: opts[:skills] || [],
      run_task: opts[:run_task],
      on_task: opts[:on_task],
      mcp: mcp,
      exposed: exposed,
      on_call: opts[:on_call],
      base_agent: base_agent
    }

    {:ok, pid} =
      Bandit.start_link(
        plug: {__MODULE__.Router, state},
        scheme: :http,
        ip: parse_ip(host),
        port: port,
        startup_log: false
      )

    {:ok, {_ip, actual_port}} = ThousandIsland.listener_info(pid)
    url = "http://#{display_host(host)}:#{actual_port}"
    Agent.update(base_agent, fn _ -> url end)
    %Handle{url: url, port: actual_port, pid: pid}
  end

  # Split `host:port` on the last colon. Missing host ⇒ 127.0.0.1.
  defp split_addr(addr) do
    case :binary.matches(addr, ":") do
      [] ->
        {"127.0.0.1", String.to_integer(addr)}

      matches ->
        {idx, _} = List.last(matches)
        host = binary_part(addr, 0, idx)
        port = binary_part(addr, idx + 1, byte_size(addr) - idx - 1)
        {if(host == "", do: "127.0.0.1", else: host), String.to_integer(port)}
    end
  end

  defp parse_ip(host) do
    case :inet.parse_address(String.to_charlist(host)) do
      {:ok, ip} -> ip
      _ -> {127, 0, 0, 1}
    end
  end

  # 0.0.0.0 / :: are reported as 127.0.0.1 for callers.
  defp display_host(host) when host in ["0.0.0.0", "::"], do: "127.0.0.1"
  defp display_host(host), do: host

  defp cfg_get(cfg, key), do: Map.get(cfg, key, Map.get(cfg, Atom.to_string(key)))

  # ---------------------------------------------------------------------------
  # Router plug.
  # ---------------------------------------------------------------------------

  defmodule Router do
    @moduledoc false
    @behaviour Plug
    import Plug.Conn

    @card_path "/.well-known/agent-card.json"

    def init(state), do: state

    def call(conn, st) do
      cond do
        # No profile at all ⇒ no routes.
        st.a2a == nil and st.mcp == nil ->
          send_resp(conn, 404, "not found")

        # MCP streamable-HTTP profile (independent of A2A). Checked first so
        # /mcp is never shadowed by the A2A POST handler.
        st.mcp != nil and conn.request_path == "/mcp" ->
          McpServe.handle_http(conn, st.exposed, st.mcp, st.on_call)

        # No A2A profile ⇒ no A2A routes (an MCP-only server 404s the rest).
        st.a2a == nil ->
          send_resp(conn, 404, "not found")

        conn.method == "GET" and conn.request_path == @card_path ->
          url = Agent.get(st.base_agent, & &1) <> "/"
          json(conn, Toolnexus.Serve.build_agent_card(st.a2a, st.skills, url))

        conn.method == "POST" ->
          {:ok, body, conn} = read_body(conn)
          handle_rpc(conn, st, body)

        true ->
          send_resp(conn, 404, "not found")
      end
    end

    # --- JSON-RPC 2.0 (§7B) ----------------------------------------------------

    defp handle_rpc(conn, st, body) do
      case Jason.decode(body) do
        {:error, _} ->
          json(conn, rpc_error(nil, -32700, "Parse error"))

        {:ok, rpc} ->
          case rpc["method"] do
            "SendMessage" ->
              text = message_text(rpc["params"])
              context_id = context_id(rpc["params"])
              id = Toolnexus.A2a.uuid4()

              submitted =
                %{"id" => id, "status" => %{"state" => "submitted"}}
                |> maybe_put("contextId", context_id)

              # Persist submitted, kick fulfilment async, return the id immediately.
              store_save(st.store, submitted)
              spawn(fn -> Toolnexus.Serve.fulfil(id, text, context_id, st) end)
              json(conn, rpc_ok(rpc["id"], submitted))

            "GetTask" ->
              id = to_string(get_in(rpc, ["params", "id"]) || "")

              case store_get(st.store, id) do
                nil -> json(conn, rpc_error(rpc["id"], -32001, "Task not found: #{id}"))
                task -> json(conn, rpc_ok(rpc["id"], task))
              end

            method ->
              json(conn, rpc_error(rpc["id"], -32601, "Method not found: #{method}"))
          end
      end
    end

    # Concatenated text of a SendMessage message's parts.
    defp message_text(params) do
      case get_in(params, ["message", "parts"]) do
        parts when is_list(parts) ->
          parts
          |> Enum.filter(&(is_map(&1) and &1["kind"] == "text" and is_binary(&1["text"])))
          |> Enum.map_join("", & &1["text"])

        _ ->
          ""
      end
    end

    defp context_id(params) do
      case get_in(params, ["message", "contextId"]) do
        id when is_binary(id) -> id
        _ -> nil
      end
    end

    defp maybe_put(map, _key, nil), do: map
    defp maybe_put(map, key, value), do: Map.put(map, key, value)

    defp rpc_ok(id, result), do: %{"jsonrpc" => "2.0", "id" => id, "result" => result}

    defp rpc_error(id, code, message),
      do: %{"jsonrpc" => "2.0", "id" => id, "error" => %{"code" => code, "message" => message}}

    defp json(conn, payload) do
      conn
      |> put_resp_content_type("application/json")
      |> send_resp(200, Jason.encode!(payload))
    end

    # Dispatch through the behaviour-carrying struct's module.
    defp store_save(%mod{} = store, task), do: mod.save(store, task)
    defp store_get(%mod{} = store, id), do: mod.get(store, id)
  end

  # ---------------------------------------------------------------------------
  # Background fulfilment: submitted → working → (completed | input-required |
  # failed). Never throws — an error becomes a failed Task.
  # ---------------------------------------------------------------------------

  @doc false
  def fulfil(id, text, context_id, st) do
    {task, result, state} =
      try do
        store_save(st.store, %{"id" => id, "status" => %{"state" => "working"}})
        # contextId groups a peer's turns into one conversation — thread it so
        # the client's ConversationStore remembers across tasks in the context.
        result = st.run_task.(text, context_id)

        if result.status == "pending" && result.pending do
          # §10 suspension over A2A: surface the protocol's `input-required`
          # state carrying the request prompt — never a false `completed`.
          task = %{
            "id" => id,
            "status" => %{
              "state" => "input-required",
              "message" => %{
                "role" => "agent",
                "parts" => [%{"kind" => "text", "text" => result.pending.prompt}]
              }
            }
          }

          {task, result, "input-required"}
        else
          artifact = %{
            "artifactId" => A2a.uuid4(),
            "parts" => [%{"kind" => "text", "text" => result.text}]
          }

          task = %{"id" => id, "status" => %{"state" => "completed"}, "artifacts" => [artifact]}
          {task, result, "completed"}
        end
      rescue
        e ->
          detail = Exception.message(e)

          task = %{
            "id" => id,
            "status" => %{
              "state" => "failed",
              "message" => %{"role" => "agent", "parts" => [%{"kind" => "text", "text" => detail}]}
            }
          }

          {task, nil, "failed"}
      end

    # A store write failure must not crash the server.
    try do
      store_save(st.store, task)
    rescue
      _ -> :ok
    end

    # Host callback errors are isolated.
    if is_function(st.on_task, 1) do
      try do
        st.on_task.(%{id: id, task: task, result: result, state: state})
      rescue
        _ -> :ok
      end
    end

    :ok
  end

  defp store_save(%mod{} = store, task), do: mod.save(store, task)

  # Referenced so the module attribute is used (the Router duplicates the path).
  @doc false
  def card_path, do: @card_path
end
