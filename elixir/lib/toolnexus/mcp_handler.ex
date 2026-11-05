defmodule Toolnexus.McpHandler do
  @moduledoc """
  MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server as a `Plug`
  with generic per-request hooks (SPEC §7C.1, ADR 0039).

  `Toolnexus.Toolkit.serve/3` owns a listener and exposes one static tool list.
  `Toolnexus.mcp_handler/2` returns a handler a host mounts on its own router,
  behind its own TLS, CORS and OAuth. Every policy is a hook; toolnexus has no
  notion of tenant, user, role or storage.

      handler = Toolnexus.mcp_handler(toolkit, authenticate: &MyAuth.check/1)
      Bandit.start_link(plug: {Toolnexus.McpHandler, handler}, port: 4000)
      # later, when the tool set changes:
      Toolnexus.McpHandler.notify_tools_changed(handler)

  The JSON-RPC transport is implemented here by hand (protocol 2025-06-18,
  optionally 2025-11-25). A confirm elicitation rides the SAME POST's SSE
  response; the client's answer arrives on a later POST and is routed back to
  the waiting request process.

  ## Options (all optional)

    * `:name`, `:title`, `:version`, `:instructions` — static serverInfo
      (defaults `"toolnexus"` / `"0.1.0"`; empty title/instructions omitted)
    * `:server_info` — `fn ctx -> %{name:, title:, version:, instructions:} end`, wins over the static fields
    * `:protocol_versions` — accepted versions, newest first; entries outside
      `implemented_versions/0` are dropped. Default `["2025-06-18"]`
    * `:authenticate` — `fn conn -> {:ok, identity} | {:error, reason} end`; error ⇒ 401
    * `:www_authenticate` — `fn conn, reason -> String.t() | nil end` (the 401 `WWW-Authenticate` header)
    * `:list_tools` — `fn ctx -> {:ok, [Tool.t()]} | [Tool.t()] | {:error, reason} end`, per
      request, for `tools/list` AND the `tools/call` lookup. Default: the toolkit's tools
    * `:before_call` — `fn ctx, tool, args -> decision end`; decision is `:allow` (or `nil`),
      `{:deny, message}`, `{:confirm, message}`, `{:error, reason}` (⇒ deny) or a
      `Toolnexus.McpHandler.Decision`
    * `:confirm_timeout` — ms an elicitation round trip may take (default 300_000)
    * `:on_call` — `fn event -> any end`, once per `tools/call`; raises are isolated. Event:
      `%{name, source, ms, is_error, err, result_bytes, decision, identity, session_id}`
    * `:list_resources` / `:read_resource` / `:list_prompts` / `:get_prompt` — absent ⇒ -32601
    * `:session_ids` — a module implementing `Toolnexus.McpHandler.SessionIds`, or a map
      `%{generate: fn ctx, validate: fn ctx, id, terminate: fn ctx, id}`. Default: in memory
    * `:stateless` — no `Mcp-Session-Id` issued or checked; confirm never elicits
    * `:stateless_session_id` — in stateless mode, returned on initialize, never validated
    * `:max_body_bytes` — POST body cap (default 4 MiB ⇒ 413 above it)

  Hooks and tools reach the request through `identity/1`, `request/1` (the
  `Plug.Conn`), `session_id/1` and `request_meta/1` (the JSON-RPC `params._meta`).
  Each accepts the hook ctx or the tool's `Toolnexus.Context`.
  """

  @behaviour Plug
  import Plug.Conn

  alias Toolnexus.{Context, McpServe, Tool, ToolResult}

  @implemented ["2025-11-25", "2025-06-18"]
  @default_versions ["2025-06-18"]

  defstruct [:toolkit, :cfg, :server, :versions]

  @type t :: %__MODULE__{
          toolkit: Toolnexus.Toolkit.t() | nil,
          cfg: map(),
          server: pid(),
          versions: [String.t()]
        }

  defmodule Ctx do
    @moduledoc "The per-request context every McpHandler hook receives."
    defstruct [:conn, :identity, :session_id, :meta]

    @type t :: %__MODULE__{
            conn: Plug.Conn.t() | nil,
            identity: term(),
            session_id: String.t() | nil,
            meta: map() | nil
          }
  end

  defmodule Decision do
    @moduledoc """
    What `before_call` returns. `kind` is `:allow | :deny | :confirm`.

      * `:deny` — the tool does not run; the caller gets `result` when set, else
        an isError result carrying `message` (default `"denied"`).
      * `:confirm` — the client is asked (`elicitation/create`, `message` as the
        prompt) when it declared elicitation; accept ⇒ run, decline/cancel ⇒
        isError `"declined"`. Otherwise the caller gets `result` when set, else
        isError `"confirmation required: <message>"`.
    """
    defstruct kind: :allow, message: nil, result: nil

    @type t :: %__MODULE__{
            kind: :allow | :deny | :confirm,
            message: String.t() | nil,
            result: Toolnexus.ToolResult.t() | nil
          }
  end

  defmodule SessionIds do
    @moduledoc """
    Issues and checks `Mcp-Session-Id` values. Plug your own (signed ids, a DB
    lookup) so any replica can validate any session.
    """
    @callback generate(Toolnexus.McpHandler.Ctx.t()) :: {:ok, String.t()} | {:error, term()}
    @callback validate(Toolnexus.McpHandler.Ctx.t(), String.t()) ::
                boolean() | {:ok, boolean()} | {:error, term()}
    @callback terminate(Toolnexus.McpHandler.Ctx.t(), String.t()) :: :ok | {:error, term()}
  end

  # ── public API ──────────────────────────────────────────────────────────

  @doc "The MCP protocol versions whose wire this handler implements."
  @spec implemented_versions() :: [String.t()]
  def implemented_versions, do: @implemented

  @doc "Build a handler for `toolkit` (may be `nil` when `:list_tools` is given)."
  @spec new(Toolnexus.Toolkit.t() | nil, keyword() | map()) :: t()
  def new(toolkit, cfg \\ []) do
    cfg = Map.new(cfg)

    versions =
      (cfg[:protocol_versions] || [])
      |> Enum.filter(&(&1 in @implemented))
      |> Enum.uniq()

    {:ok, server} = GenServer.start(__MODULE__.State, nil)

    %__MODULE__{
      toolkit: toolkit,
      cfg: cfg,
      server: server,
      versions: if(versions == [], do: @default_versions, else: versions)
    }
  end

  @doc "Stop the handler's state process (open streams end on their next write)."
  @spec stop(t()) :: :ok
  def stop(%__MODULE__{server: server}), do: GenServer.stop(server)

  @doc """
  Send `notifications/tools/list_changed` on every open GET stream. Clients
  re-run `tools/list` (which calls `:list_tools` again).
  """
  @spec notify_tools_changed(t()) :: :ok
  def notify_tools_changed(%__MODULE__{server: server}) do
    msg = Jason.encode!(%{"jsonrpc" => "2.0", "method" => "notifications/tools/list_changed"})
    for pid <- GenServer.call(server, :streams), do: send(pid, {:toolnexus_mcp_event, msg})
    :ok
  end

  @doc "The value `:authenticate` produced for this request (`nil` without one)."
  def identity(ctx), do: field(ctx, :identity)
  @doc "The inbound `Plug.Conn` being served."
  def request(ctx), do: field(ctx, :conn)
  @doc "The request's `Mcp-Session-Id` (`nil` when none)."
  def session_id(ctx), do: field(ctx, :session_id)
  @doc "The JSON-RPC request's `params._meta` (`nil` when absent)."
  def request_meta(ctx), do: field(ctx, :meta)

  defp field(%Ctx{} = c, k), do: Map.get(c, k)
  defp field(%Context{extra: %{mcp: %Ctx{} = c}}, k), do: Map.get(c, k)
  defp field(_, _), do: nil

  # ── Plug ────────────────────────────────────────────────────────────────

  @impl Plug
  def init(%__MODULE__{} = h), do: h
  def init(fun) when is_function(fun, 0), do: fun
  def init({m, f, a}), do: {m, f, a}

  @impl Plug
  def call(conn, fun) when is_function(fun, 0), do: call(conn, fun.())
  def call(conn, {m, f, a}), do: call(conn, apply(m, f, a))

  def call(conn, %__MODULE__{} = h) do
    ctx = %Ctx{conn: conn}

    case authenticate(conn, h, ctx) do
      {:ok, ctx} ->
        case conn.method do
          "POST" ->
            handle_post(conn, h, ctx)

          "GET" ->
            handle_get(conn, h, ctx)

          "DELETE" ->
            handle_delete(conn, h, ctx)

          _ ->
            conn
            |> put_resp_header("allow", "GET, POST, DELETE")
            |> text(405, "Method Not Allowed")
        end

      {:halt, conn} ->
        conn
    end
  end

  defp authenticate(conn, %{cfg: %{authenticate: auth} = cfg}, ctx) when is_function(auth, 1) do
    case safe(fn -> auth.(conn) end) do
      {:ok, identity} ->
        {:ok, %{ctx | identity: identity}}

      other ->
        reason =
          case other do
            {:error, r} -> r
            r -> r
          end

        conn =
          case cfg[:www_authenticate] do
            f when is_function(f, 2) ->
              case f.(conn, reason) do
                v when is_binary(v) and v != "" -> put_resp_header(conn, "www-authenticate", v)
                _ -> conn
              end

            _ ->
              conn
          end

        {:halt, text(conn, 401, "Unauthorized")}
    end
  end

  defp authenticate(_conn, _h, ctx), do: {:ok, ctx}

  # ── POST ────────────────────────────────────────────────────────────────

  defp handle_post(conn, h, ctx) do
    if json_content_type?(conn) do
      max = h.cfg[:max_body_bytes] || 4 * 1024 * 1024

      case read_body(conn, length: max + 1, read_length: max + 1) do
        {:ok, body, conn} when byte_size(body) <= max -> handle_body(conn, h, ctx, body)
        {:ok, _body, conn} -> text(conn, 413, "Request Entity Too Large")
        {:more, _, conn} -> text(conn, 413, "Request Entity Too Large")
        {:error, _} -> rpc_error_400(conn, nil, -32700, "read error")
      end
    else
      text(conn, 415, "Unsupported Media Type: must be application/json")
    end
  end

  defp json_content_type?(conn) do
    case get_req_header(conn, "content-type") do
      [ct | _] -> match?({:ok, "application", "json", _}, Plug.Conn.Utils.media_type(ct))
      _ -> false
    end
  end

  defp handle_body(conn, h, ctx, body) do
    cond do
      String.starts_with?(String.trim(body), "[") ->
        # JSON-RPC batching was removed in protocol 2025-06-18.
        rpc_error_400(conn, nil, -32600, "batch requests are not supported")

      true ->
        case Jason.decode(body) do
          {:ok, %{"jsonrpc" => "2.0"} = msg} -> handle_msg(conn, h, ctx, msg)
          _ -> rpc_error_400(conn, nil, -32700, "parse error")
        end
    end
  end

  defp handle_msg(conn, h, ctx, %{"method" => "initialize"} = msg),
    do: handle_initialize(conn, h, ctx, msg)

  defp handle_msg(conn, h, ctx, msg) do
    with {:ok, ctx} <- check_session(conn, h, ctx) do
      method = msg["method"]
      id = msg["id"]

      cond do
        # A response from the client (the answer to our elicitation).
        method in [nil, ""] ->
          if id != nil, do: deliver(h, ctx.session_id, msg)
          send_resp(conn, 202, "")

        # A notification: acknowledge.
        id == nil ->
          send_resp(conn, 202, "")

        true ->
          params = msg["params"]

          ctx =
            case params do
              %{"_meta" => meta} when is_map(meta) -> %{ctx | meta: meta}
              _ -> ctx
            end

          ctx = %{ctx | conn: conn}
          {conn, outcome} = dispatch(conn, h, ctx, method, params)
          respond(conn, id, outcome)
      end
    else
      {:halt, conn} -> conn
    end
  end

  defp check_session(conn, h, ctx) do
    version = header(conn, "mcp-protocol-version")

    cond do
      version != nil and version not in h.versions ->
        {:halt, text(conn, 400, "Bad Request: unsupported MCP-Protocol-Version")}

      h.cfg[:stateless] ->
        {:ok, ctx}

      true ->
        case header(conn, "mcp-session-id") do
          nil ->
            {:halt, text(conn, 400, "Bad Request: missing Mcp-Session-Id")}

          sid ->
            if ids_validate(h, ctx, sid),
              do: {:ok, %{ctx | session_id: sid}},
              else: {:halt, text(conn, 404, "Session not found")}
        end
    end
  end

  defp handle_initialize(conn, h, ctx, msg) do
    params = if is_map(msg["params"]), do: msg["params"], else: %{}

    version =
      if params["protocolVersion"] in h.versions,
        do: params["protocolVersion"],
        else: hd(h.versions)

    sid_result =
      if h.cfg[:stateless] do
        {:ok, h.cfg[:stateless_session_id]}
      else
        case ids_generate(h, ctx) do
          {:ok, sid} ->
            caps = params["capabilities"]
            elicit? = is_map(caps) and Map.get(caps, "elicitation") != nil
            GenServer.call(h.server, {:set_cap, sid, elicit?})
            {:ok, sid}

          _ ->
            :error
        end
      end

    case sid_result do
      :error ->
        rpc_error_400(conn, msg["id"], -32603, "cannot create session")

      {:ok, sid} ->
        ctx = %{ctx | session_id: if(h.cfg[:stateless], do: nil, else: sid)}

        info =
          case h.cfg[:server_info] do
            f when is_function(f, 1) -> Map.new(f.(ctx))
            _ -> h.cfg
          end

        name = blank_default(info[:name], "toolnexus")
        ver = blank_default(info[:version], "0.1.0")

        caps =
          %{"tools" => %{"listChanged" => true}}
          |> then(&if h.cfg[:list_resources], do: Map.put(&1, "resources", %{}), else: &1)
          |> then(&if h.cfg[:list_prompts], do: Map.put(&1, "prompts", %{}), else: &1)

        result =
          %{
            "protocolVersion" => version,
            "capabilities" => caps,
            "serverInfo" =>
              omit_empty(%{"name" => name, "title" => info[:title], "version" => ver})
          }
          |> put_nonempty("instructions", info[:instructions])

        conn =
          if sid not in [nil, ""], do: put_resp_header(conn, "mcp-session-id", sid), else: conn

        respond(conn, msg["id"], {:ok, result})
    end
  end

  # ── GET (server→client stream) / DELETE ────────────────────────────────

  defp handle_get(conn, h, ctx) do
    accept = conn |> get_req_header("accept") |> Enum.join(",")

    if String.contains?(accept, "text/event-stream") do
      case check_session(conn, h, ctx) do
        {:halt, conn} ->
          conn

        {:ok, _ctx} ->
          conn =
            conn
            |> put_resp_header("content-type", "text/event-stream")
            |> put_resp_header("cache-control", "no-cache")
            |> send_chunked(200)

          GenServer.call(h.server, {:add_stream, self()})
          stream_loop(conn)
      end
    else
      text(conn, 405, "Method Not Allowed")
    end
  end

  defp stream_loop(conn) do
    receive do
      {:toolnexus_mcp_event, data} ->
        case chunk(conn, "event: message\ndata: #{data}\n\n") do
          {:ok, conn} -> stream_loop(conn)
          {:error, _} -> conn
        end
    after
      15_000 ->
        # An SSE comment: detects a closed client without a message to send.
        case chunk(conn, ": keepalive\n\n") do
          {:ok, conn} -> stream_loop(conn)
          {:error, _} -> conn
        end
    end
  end

  defp handle_delete(conn, h, ctx) do
    if h.cfg[:stateless] do
      text(conn, 405, "Method Not Allowed")
    else
      case check_session(conn, h, ctx) do
        {:halt, conn} ->
          conn

        {:ok, ctx} ->
          if ids_terminate(h, ctx, ctx.session_id) do
            GenServer.call(h.server, {:del_cap, ctx.session_id})
            send_resp(conn, 200, "")
          else
            text(conn, 500, "cannot terminate session")
          end
      end
    end
  end

  # ── JSON-RPC dispatch ──────────────────────────────────────────────────

  defp dispatch(conn, _h, _ctx, "ping", _p), do: {conn, {:ok, %{}}}

  defp dispatch(conn, h, ctx, "tools/list", _p) do
    case tools(h, ctx) do
      {:ok, tools} -> {conn, {:ok, %{"tools" => Enum.map(tools, &wire_tool/1)}}}
      {:error, msg} -> {conn, {:error, -32603, msg}}
    end
  end

  defp dispatch(conn, h, ctx, "tools/call", p), do: call_tool(conn, h, ctx, p)

  defp dispatch(conn, %{cfg: %{list_resources: f}}, ctx, "resources/list", _p)
       when is_function(f, 1) do
    case hook(fn -> f.(ctx) end) do
      {:ok, rs} ->
        out =
          Enum.map(rs, fn r ->
            omit_empty(%{
              "uri" => g(r, :uri),
              "name" => g(r, :name),
              "title" => g(r, :title),
              "description" => g(r, :description),
              "mimeType" => g(r, :mime_type)
            })
          end)

        {conn, {:ok, %{"resources" => out}}}

      {:error, msg} ->
        {conn, {:error, -32603, msg}}
    end
  end

  defp dispatch(conn, %{cfg: %{list_resources: l, read_resource: f}}, ctx, "resources/read", p)
       when is_function(l, 1) and is_function(f, 2) do
    uri = if is_map(p), do: to_string(p["uri"] || ""), else: ""

    case hook(fn -> f.(ctx, uri) end) do
      {:ok, cs} ->
        out =
          Enum.map(cs, fn c ->
            m =
              omit_empty(%{
                "uri" => g(c, :uri),
                "mimeType" => g(c, :mime_type),
                "blob" => g(c, :blob)
              })

            if g(c, :blob) in [nil, ""], do: Map.put(m, "text", g(c, :text) || ""), else: m
          end)

        {conn, {:ok, %{"contents" => out}}}

      {:error, msg} ->
        {conn, {:error, -32002, msg}}
    end
  end

  defp dispatch(conn, %{cfg: %{list_prompts: f}}, ctx, "prompts/list", _p)
       when is_function(f, 1) do
    case hook(fn -> f.(ctx) end) do
      {:ok, ps} ->
        out =
          Enum.map(ps, fn pr ->
            m =
              omit_empty(%{
                "name" => g(pr, :name),
                "title" => g(pr, :title),
                "description" => g(pr, :description)
              })

            case g(pr, :arguments) do
              [_ | _] = args ->
                Map.put(
                  m,
                  "arguments",
                  Enum.map(args, fn a ->
                    am = omit_empty(%{"name" => g(a, :name), "description" => g(a, :description)})
                    if g(a, :required) == true, do: Map.put(am, "required", true), else: am
                  end)
                )

              _ ->
                m
            end
          end)

        {conn, {:ok, %{"prompts" => out}}}

      {:error, msg} ->
        {conn, {:error, -32603, msg}}
    end
  end

  defp dispatch(conn, %{cfg: %{list_prompts: l, get_prompt: f}}, ctx, "prompts/get", p)
       when is_function(l, 1) and is_function(f, 3) do
    with true <- is_map(p),
         name when is_binary(name) <- p["name"] || "",
         args when is_map(args) <- p["arguments"] || %{} do
      case hook(fn -> f.(ctx, name, args) end) do
        {:ok, pr} ->
          msgs =
            Enum.map(g(pr, :messages) || [], fn m ->
              %{"role" => g(m, :role), "content" => %{"type" => "text", "text" => g(m, :text)}}
            end)

          {conn, {:ok, omit_empty(%{"description" => g(pr, :description), "messages" => msgs})}}

        {:error, msg} ->
          {conn, {:error, -32602, msg}}
      end
    else
      _ -> {conn, {:error, -32602, "invalid params"}}
    end
  end

  defp dispatch(conn, _h, _ctx, method, _p),
    do: {conn, {:error, -32601, "method not found: #{method}"}}

  defp tools(h, ctx) do
    case h.cfg[:list_tools] do
      f when is_function(f, 1) -> hook(fn -> f.(ctx) end)
      _ -> {:ok, if(h.toolkit, do: Toolnexus.Toolkit.tools(h.toolkit), else: [])}
    end
  end

  defp call_tool(conn, h, ctx, p) do
    with true <- is_map(p),
         name when is_binary(name) <- p["name"] || "" do
      case tools(h, ctx) do
        {:error, msg} ->
          {conn, {:error, -32603, msg}}

        {:ok, tools} ->
          case Enum.find(tools, &(&1.name == name)) do
            %Tool{execute: exec} = tool when is_function(exec) ->
              call_found(conn, h, ctx, tool, p["arguments"])

            _ ->
              {conn, {:error, -32602, "tool not found: #{name}"}}
          end
      end
    else
      _ -> {conn, {:error, -32602, "invalid params"}}
    end
  end

  defp call_found(conn, h, ctx, tool, raw_args) do
    cond do
      raw_args == nil ->
        gate_and_run(conn, h, ctx, tool, %{})

      is_map(raw_args) ->
        gate_and_run(conn, h, ctx, tool, raw_args)

      header(conn, "mcp-protocol-version") == "2025-11-25" ->
        # 2025-11-25: input validation errors are tool execution errors.
        {conn, {:ok, wire_result(ToolResult.error("invalid arguments: must be an object"))}}

      true ->
        {conn, {:error, -32602, "arguments must be an object"}}
    end
  end

  defp gate_and_run(conn, h, ctx, tool, args) do
    started = System.monotonic_time(:millisecond)

    finish = fn conn, decision, %ToolResult{} = res, err ->
      wire = wire_result(res)

      case h.cfg[:on_call] do
        f when is_function(f, 1) ->
          event = %{
            name: tool.name,
            source: tool.source,
            ms: System.monotonic_time(:millisecond) - started,
            is_error: res.is_error,
            err: err,
            result_bytes: byte_size(Jason.encode!(wire)),
            decision: decision,
            identity: ctx.identity,
            session_id: ctx.session_id
          }

          _ = safe(fn -> f.(event) end)

        _ ->
          :ok
      end

      {conn, {:ok, wire}}
    end

    decision =
      case h.cfg[:before_call] do
        f when is_function(f, 3) -> normalize_decision(safe(fn -> f.(ctx, tool, args) end))
        _ -> %Decision{}
      end

    case decision do
      %Decision{kind: :deny, result: %ToolResult{} = r} ->
        finish.(conn, :deny, r, nil)

      %Decision{kind: :deny, message: m} ->
        finish.(conn, :deny, ToolResult.error(blank_default(m, "denied")), nil)

      %Decision{kind: :confirm} = d ->
        if can_elicit?(h, ctx.session_id) do
          case elicit_confirm(conn, h, ctx.session_id, d.message) do
            {conn, {:ok, true}} ->
              {res, err} = execute(tool, args, ctx)
              finish.(conn, :confirm, res, err)

            {conn, {:ok, false}} ->
              finish.(conn, :confirm, ToolResult.error("declined"), nil)

            {conn, {:error, reason}} ->
              finish.(conn, :confirm, ToolResult.error("confirmation failed: #{reason}"), nil)
          end
        else
          case d.result do
            %ToolResult{} = r ->
              finish.(conn, :confirm, r, nil)

            _ ->
              finish.(
                conn,
                :confirm,
                ToolResult.error("confirmation required: #{d.message}"),
                nil
              )
          end
        end

      %Decision{} ->
        {res, err} = execute(tool, args, ctx)
        finish.(conn, :allow, res, err)
    end
  end

  defp normalize_decision(d) do
    case d do
      %Decision{kind: k} = d when k in [:allow, :deny, :confirm] -> d
      %Decision{} = d -> %{d | kind: :allow}
      nil -> %Decision{}
      :allow -> %Decision{}
      :deny -> %Decision{kind: :deny}
      :confirm -> %Decision{kind: :confirm}
      {:deny, m} -> %Decision{kind: :deny, message: m}
      {:confirm, m} -> %Decision{kind: :confirm, message: m}
      {:error, reason} -> %Decision{kind: :deny, message: reason_text(reason)}
      {:raised, reason} -> %Decision{kind: :deny, message: reason_text(reason)}
      _ -> %Decision{}
    end
  end

  defp execute(tool, args, ctx) do
    tctx = %Context{extra: %{mcp: ctx}}

    try do
      case tool.execute.(args, tctx) do
        %ToolResult{} = r -> {r, nil}
        {:ok, %ToolResult{} = r} -> {r, nil}
        {:error, reason} -> {ToolResult.error(reason_text(reason)), reason}
        out when is_binary(out) -> {ToolResult.ok(out), nil}
        out -> {ToolResult.ok(Jason.encode!(out)), nil}
      end
    rescue
      e -> {ToolResult.error(Exception.message(e)), e}
    end
  end

  # ── elicitation ────────────────────────────────────────────────────────

  defp can_elicit?(_h, nil), do: false
  defp can_elicit?(h, sid), do: GenServer.call(h.server, {:can_elicit, sid})

  # Sends elicitation/create on this POST's SSE stream and waits for the
  # client's answer (a separate POST, routed here by `deliver/3`).
  defp elicit_confirm(conn, h, sid, message) do
    id = "toolnexus-confirm-#{GenServer.call(h.server, :next_seq)}"
    key = {sid, id}
    :ok = GenServer.call(h.server, {:register, key, self()})
    message = blank_default(message, "Confirm this action?")

    req = %{
      "jsonrpc" => "2.0",
      "id" => id,
      "method" => "elicitation/create",
      "params" => %{
        "message" => message,
        "requestedSchema" => %{
          "type" => "object",
          "properties" => %{
            "confirm" => %{"type" => "boolean", "title" => "Confirm", "description" => message}
          }
        }
      }
    }

    try do
      case sse_event(conn, req) do
        {:error, reason} ->
          {conn, {:error, reason_text(reason)}}

        {:ok, conn} ->
          receive do
            {:toolnexus_mcp_answer, ^key, msg} -> {conn, read_answer(msg)}
          after
            h.cfg[:confirm_timeout] || 300_000 -> {conn, {:error, "timeout"}}
          end
      end
    after
      GenServer.call(h.server, {:unregister, key})
    end
  end

  defp read_answer(%{"error" => e}) when e != nil, do: {:error, "client returned an error"}

  defp read_answer(%{"result" => %{} = r}) do
    cond do
      r["action"] != "accept" -> {:ok, false}
      is_map(r["content"]) and r["content"]["confirm"] == false -> {:ok, false}
      true -> {:ok, true}
    end
  end

  defp read_answer(_), do: {:error, "invalid elicitation result"}

  defp deliver(h, sid, %{"id" => id} = msg) when is_binary(id) do
    case GenServer.call(h.server, {:lookup, {sid, id}}) do
      pid when is_pid(pid) -> send(pid, {:toolnexus_mcp_answer, {sid, id}, msg})
      _ -> :ok
    end
  end

  defp deliver(_h, _sid, _msg), do: :ok

  # ── session ids ────────────────────────────────────────────────────────

  defp ids_generate(h, ctx) do
    case h.cfg[:session_ids] do
      nil -> {:ok, GenServer.call(h.server, :generate)}
      %{generate: f} -> f.(ctx)
      mod when is_atom(mod) -> mod.generate(ctx)
    end
    |> case do
      {:ok, id} when is_binary(id) and id != "" -> {:ok, id}
      _ -> :error
    end
  end

  defp ids_validate(h, ctx, sid) do
    case h.cfg[:session_ids] do
      nil -> GenServer.call(h.server, {:validate, sid})
      %{validate: f} -> safe(fn -> f.(ctx, sid) end)
      mod when is_atom(mod) -> safe(fn -> mod.validate(ctx, sid) end)
    end
    |> Kernel.in([true, {:ok, true}])
  end

  defp ids_terminate(h, ctx, sid) do
    case h.cfg[:session_ids] do
      nil -> GenServer.call(h.server, {:terminate, sid})
      %{terminate: f} -> safe(fn -> f.(ctx, sid) end)
      mod when is_atom(mod) -> safe(fn -> mod.terminate(ctx, sid) end)
    end
    |> Kernel.==(:ok)
  end

  # ── wire helpers ───────────────────────────────────────────────────────

  @doc false
  # The tools/list entry: name, description, inputSchema, outputSchema (when set),
  # annotations (verbatim; else derived from hints; else omitted).
  def wire_tool(%Tool{} = t) do
    ann =
      case {Map.get(t, :annotations), t.hints} do
        {%Toolnexus.ToolAnnotations{} = a, _} ->
          %{}
          |> put_nonempty("title", a.title)
          |> McpServe.put_some("readOnlyHint", a.read_only_hint)
          |> McpServe.put_some("destructiveHint", a.destructive_hint)
          |> McpServe.put_some("idempotentHint", a.idempotent_hint)
          |> McpServe.put_some("openWorldHint", a.open_world_hint)

        {nil, %Toolnexus.ToolHints{} = hh} ->
          %{
            "readOnlyHint" => hh.read_only,
            "destructiveHint" => hh.destructive,
            "openWorldHint" => hh.open_world
          }

        _ ->
          %{}
      end

    %{
      "name" => t.name,
      "description" => t.description,
      "inputSchema" => t.input_schema || %{"type" => "object"}
    }
    |> McpServe.put_some("outputSchema", Map.get(t, :output_schema))
    |> then(&if ann == %{}, do: &1, else: Map.put(&1, "annotations", ann))
  end

  @doc false
  # The handler's CallToolResult: isError present only when true (omitempty).
  def wire_result(%ToolResult{} = r) do
    w = McpServe.result_wire(r)
    if r.is_error, do: Map.put(w, "isError", true), else: w
  end

  defp respond(conn, id, outcome) do
    msg =
      case outcome do
        {:ok, result} ->
          %{"jsonrpc" => "2.0", "id" => id, "result" => result}

        {:error, code, m} ->
          %{"jsonrpc" => "2.0", "id" => id, "error" => %{"code" => code, "message" => m}}
      end

    if conn.state == :chunked do
      case sse_event(conn, msg) do
        {:ok, conn} -> conn
        {:error, _} -> conn
      end
    else
      json(conn, 200, msg)
    end
  end

  # Upgrades this POST's response to SSE on first use.
  defp sse_event(conn, msg) do
    conn =
      if conn.state == :chunked do
        conn
      else
        conn
        |> put_resp_header("content-type", "text/event-stream")
        |> put_resp_header("cache-control", "no-cache")
        |> send_chunked(200)
      end

    chunk(conn, "event: message\ndata: #{Jason.encode!(msg)}\n\n")
  end

  defp rpc_error_400(conn, id, code, message) do
    json(conn, 400, %{
      "jsonrpc" => "2.0",
      "id" => id,
      "error" => %{"code" => code, "message" => message}
    })
  end

  defp json(conn, status, payload) do
    conn
    |> put_resp_header("content-type", "application/json")
    |> send_resp(status, Jason.encode!(payload))
  end

  defp text(conn, status, body) do
    conn
    |> put_resp_header("content-type", "text/plain; charset=utf-8")
    |> send_resp(status, body <> "\n")
  end

  defp header(conn, name) do
    case get_req_header(conn, name) do
      [v | _] when v != "" -> v
      _ -> nil
    end
  end

  # A hook's result: {:ok, x} | {:error, reason} | bare x; a raise is an error.
  defp hook(fun) do
    case safe(fun) do
      {:ok, v} -> {:ok, v}
      {:error, reason} -> {:error, reason_text(reason)}
      {:raised, reason} -> {:error, reason_text(reason)}
      v -> {:ok, v}
    end
  end

  defp safe(fun) do
    fun.()
  rescue
    e -> {:raised, e}
  catch
    kind, reason -> {:raised, "#{kind}: #{inspect(reason)}"}
  end

  defp reason_text(r) when is_binary(r), do: r
  defp reason_text(%{__exception__: true} = e), do: Exception.message(e)
  defp reason_text(r) when is_atom(r), do: Atom.to_string(r)
  defp reason_text(r), do: inspect(r)

  defp g(m, k) when is_map(m), do: Map.get(m, k, Map.get(m, Atom.to_string(k)))

  defp omit_empty(m), do: m |> Enum.reject(fn {_, v} -> v in [nil, ""] end) |> Map.new()

  defp put_nonempty(m, _k, v) when v in [nil, ""], do: m
  defp put_nonempty(m, k, v), do: Map.put(m, k, v)

  defp blank_default(v, d) when v in [nil, ""], do: d
  defp blank_default(v, _d), do: v

  defmodule State do
    @moduledoc false
    # Shared handler state: session capabilities, open GET streams, pending
    # elicitation waits, and the default in-memory session set.
    use GenServer

    @impl true
    def init(_),
      do: {:ok, %{caps: %{}, streams: MapSet.new(), pending: %{}, live: MapSet.new(), seq: 0}}

    @impl true
    def handle_call({:set_cap, sid, v}, _f, s), do: {:reply, :ok, put_in(s.caps[sid], v)}

    def handle_call({:del_cap, sid}, _f, s),
      do: {:reply, :ok, %{s | caps: Map.delete(s.caps, sid)}}

    def handle_call({:can_elicit, sid}, _f, s), do: {:reply, Map.get(s.caps, sid, false), s}

    def handle_call({:add_stream, pid}, _f, s) do
      Process.monitor(pid)
      {:reply, :ok, %{s | streams: MapSet.put(s.streams, pid)}}
    end

    def handle_call(:streams, _f, s), do: {:reply, MapSet.to_list(s.streams), s}
    def handle_call(:next_seq, _f, s), do: {:reply, s.seq + 1, %{s | seq: s.seq + 1}}
    def handle_call({:register, k, pid}, _f, s), do: {:reply, :ok, put_in(s.pending[k], pid)}

    def handle_call({:unregister, k}, _f, s),
      do: {:reply, :ok, %{s | pending: Map.delete(s.pending, k)}}

    def handle_call({:lookup, k}, _f, s), do: {:reply, Map.get(s.pending, k), s}

    def handle_call(:generate, _f, s) do
      id = Base.encode16(:crypto.strong_rand_bytes(16), case: :lower)
      {:reply, id, %{s | live: MapSet.put(s.live, id)}}
    end

    def handle_call({:validate, id}, _f, s), do: {:reply, MapSet.member?(s.live, id), s}

    def handle_call({:terminate, id}, _f, s),
      do: {:reply, :ok, %{s | live: MapSet.delete(s.live, id)}}

    @impl true
    def handle_info({:DOWN, _r, :process, pid, _}, s),
      do: {:noreply, %{s | streams: MapSet.delete(s.streams, pid)}}
  end
end
