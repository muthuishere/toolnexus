defmodule Toolnexus.McpServeTest do
  use ExUnit.Case, async: false

  alias Toolnexus.{Context, Mcp, McpServe, Native, Serve, Tool, Toolkit, ToolResult}

  @moduletag timeout: 30_000

  defp add_tool do
    Native.define_tool(
      name: "add",
      description: "Add two numbers",
      input_schema: %{
        "type" => "object",
        "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
        "required" => ["a", "b"]
      },
      execute: fn args -> to_string(trunc(args["a"] + args["b"])) end
    )
  end

  defp fail_tool do
    Native.define_tool(
      name: "explode",
      description: "Always errors",
      execute: fn _args -> raise "kaboom" end
    )
  end

  defp toolkit(extra \\ []) do
    Toolnexus.create_toolkit!(extra_tools: [add_tool(), fail_tool()] ++ extra, builtins: false)
  end

  defp serve!(tk, opts) do
    handle = Toolkit.serve(tk, "127.0.0.1:0", opts)
    on_exit(fn -> Serve.stop(handle) end)
    handle
  end

  defp post_mcp(url, payload) do
    resp =
      Req.post!(
        url: url <> "/mcp",
        json: payload,
        headers: [{"accept", "application/json, text/event-stream"}],
        retry: false,
        decode_body: false
      )

    body = if resp.body in [nil, ""], do: nil, else: Jason.decode!(resp.body)
    {resp.status, body}
  end

  defp rpc(id, method, params),
    do: %{"jsonrpc" => "2.0", "id" => id, "method" => method, "params" => params}

  # --- self-hosting conformance: our own MCP client against our own MCP server -----

  test "round-trip: Toolnexus.Mcp.load connects to a served toolkit, lists and calls tools" do
    tk = toolkit()
    handle = serve!(tk, mcp: %{name: "gateway", version: "9.9.9"})

    source =
      Mcp.load(%{
        "mcpServers" => %{"gw" => %{"url" => handle.url <> "/mcp", "timeout" => 10_000}}
      })

    on_exit(fn -> Mcp.close(source) end)

    assert source.status == %{"gw" => "connected"}
    assert Enum.map(source.tools, & &1.name) == ["gw_add", "gw_explode"]

    add = Enum.find(source.tools, &(&1.name == "gw_add"))
    assert add.source == "mcp"
    # the toolkit tool's schema travels verbatim (plus the client-side merge)
    assert add.input_schema["properties"]["a"] == %{"type" => "number"}

    result = add.execute.(%{"a" => 2, "b" => 3}, %Context{})
    refute result.is_error
    assert result.output == "5"

    # isError propagates end to end (execute raise → MCP isError → client error)
    explode = Enum.find(source.tools, &(&1.name == "gw_explode"))
    result = explode.execute.(%{}, %Context{})
    assert result.is_error
    assert result.output == "kaboom"
  end

  test "mcp.tools filters the exposed subset; unknown names are ignored" do
    tk = toolkit()
    handle = serve!(tk, mcp: %{tools: ["add", "does-not-exist"]})

    source =
      Mcp.load(%{"mcpServers" => %{"gw" => %{"url" => handle.url <> "/mcp", "timeout" => 10_000}}})

    on_exit(fn -> Mcp.close(source) end)
    assert Enum.map(source.tools, & &1.name) == ["gw_add"]
  end

  test "exposed_tools helper: nil config ⇒ all, list ⇒ subset" do
    tools = [add_tool(), fail_tool()]
    assert McpServe.exposed_tools(tools, nil) == tools
    assert McpServe.exposed_tools(tools, %{}) == tools
    assert Enum.map(McpServe.exposed_tools(tools, %{tools: ["explode"]}), & &1.name) == ["explode"]
    assert McpServe.exposed_tools(tools, %{"tools" => ["nope"]}) == []
  end

  # --- wire shapes (§7C) --------------------------------------------------------------

  test "initialize advertises serverInfo from the profile and capabilities.tools" do
    tk = toolkit()
    handle = serve!(tk, mcp: %{name: "gateway", version: "9.9.9"})

    {200, body} =
      post_mcp(handle.url, rpc(1, "initialize", %{"protocolVersion" => "2025-03-26"}))

    assert body["result"] == %{
             "protocolVersion" => "2025-03-26",
             "capabilities" => %{"tools" => %{}},
             "serverInfo" => %{"name" => "gateway", "version" => "9.9.9"}
           }

    # defaults when the profile gives no name/version
    handle2 = serve!(toolkit(), mcp: %{})
    {200, body2} = post_mcp(handle2.url, rpc(1, "initialize", %{}))
    assert body2["result"]["serverInfo"] == %{"name" => "toolnexus", "version" => "0.1.0"}
    assert body2["result"]["protocolVersion"] == "2025-06-18"
  end

  test "tools/list maps name/description/inputSchema verbatim (no re-sanitizing)" do
    tk = toolkit()
    handle = serve!(tk, mcp: %{})

    {200, body} = post_mcp(handle.url, rpc(2, "tools/list", %{}))

    assert body["result"]["tools"] == [
             %{
               "name" => "add",
               "description" => "Add two numbers",
               "inputSchema" => %{
                 "type" => "object",
                 "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
                 "required" => ["a", "b"]
               }
             },
             %{
               "name" => "explode",
               "description" => "Always errors",
               "inputSchema" => %{
                 "type" => "object",
                 "properties" => %{},
                 "additionalProperties" => false
               }
             }
           ]
  end

  test "tools/call maps ToolResult → content text + isError; unknown tool → -32602" do
    {:ok, calls} = Agent.start_link(fn -> [] end)
    tk = toolkit()
    handle = serve!(tk, mcp: %{}, on_call: fn ev -> Agent.update(calls, &(&1 ++ [ev])) end)

    {200, body} =
      post_mcp(handle.url, rpc(3, "tools/call", %{"name" => "add", "arguments" => %{"a" => 1, "b" => 2}}))

    assert body["result"] == %{
             "content" => [%{"type" => "text", "text" => "3"}],
             "isError" => false
           }

    {200, body} = post_mcp(handle.url, rpc(4, "tools/call", %{"name" => "explode"}))
    assert body["result"]["isError"] == true
    assert [%{"type" => "text", "text" => "kaboom"}] = body["result"]["content"]

    {200, body} = post_mcp(handle.url, rpc(5, "tools/call", %{"name" => "ghost", "arguments" => %{}}))
    assert body["error"] == %{"code" => -32602, "message" => "Unknown tool: ghost"}

    # onCall fired per successful dispatch with source + is_error, never for unknown names
    assert [
             %{name: "add", source: "native", is_error: false, ms: _},
             %{name: "explode", source: "native", is_error: true, ms: _}
           ] = Agent.get(calls, & &1)
  end

  test "notifications → 202, unknown method → -32601, parse error → 400/-32700, GET → 405" do
    tk = toolkit()
    handle = serve!(tk, mcp: %{})

    resp =
      Req.post!(
        url: handle.url <> "/mcp",
        json: %{"jsonrpc" => "2.0", "method" => "notifications/initialized"},
        retry: false,
        decode_body: false
      )

    assert resp.status == 202

    {200, body} = post_mcp(handle.url, rpc(6, "resources/list", %{}))
    assert body["error"] == %{"code" => -32601, "message" => "Method not found: resources/list"}

    {200, body} = post_mcp(handle.url, rpc(7, "ping", %{}))
    assert body["result"] == %{}

    resp = Req.post!(url: handle.url <> "/mcp", body: "{oops", retry: false, decode_body: false)
    assert resp.status == 400
    assert Jason.decode!(resp.body)["error"]["code"] == -32700

    resp = Req.get!(url: handle.url <> "/mcp", retry: false, decode_body: false)
    assert resp.status == 405
  end

  test "an MCP-only server 404s every non-/mcp path" do
    tk = toolkit()
    handle = serve!(tk, mcp: %{})

    assert Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false, decode_body: false).status ==
             404

    assert Req.post!(url: handle.url <> "/", json: %{}, retry: false, decode_body: false).status == 404
  end

  test "a top-level mcpServer config block mounts the profile without an inline option" do
    tk =
      Toolnexus.create_toolkit!(
        mcp_config: %{"mcpServers" => %{}, "mcpServer" => %{"name" => "cfg-gw", "version" => "1.1.1"}},
        extra_tools: [add_tool()],
        builtins: false
      )

    handle = serve!(tk, [])
    {200, body} = post_mcp(handle.url, rpc(1, "initialize", %{}))
    assert body["result"]["serverInfo"] == %{"name" => "cfg-gw", "version" => "1.1.1"}
  end

  test "A2A and MCP profiles co-mount on one server (/mcp is never shadowed)" do
    llm_port = free_port()

    tk =
      Toolnexus.create_toolkit!(
        skills: [%{name: "s", description: "d", content: "c"}],
        extra_tools: [add_tool()],
        builtins: false
      )

    client =
      Toolnexus.Client.create(
        base_url: "http://127.0.0.1:#{llm_port}",
        style: "openai",
        model: "m",
        api_key: "k",
        retries: 0
      )

    handle = serve!(tk, client: client, a2a: %{name: "both"}, mcp: %{name: "both-mcp"})

    # A2A card is served…
    card = Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false).body
    assert card["name"] == "both"

    # …and /mcp answers MCP JSON-RPC (skill tool included in the unified list)
    {200, body} = post_mcp(handle.url, rpc(1, "tools/list", %{}))
    assert Enum.map(body["result"]["tools"], & &1["name"]) == ["skill", "add"]
  end

  test "a bare non-ToolResult return and a raw Tool raise are both handled at the boundary" do
    raw = %Tool{
      name: "raw",
      description: "returns a bare string",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _args, _ctx -> %ToolResult{output: "fine", is_error: false} end
    }

    raiser = %Tool{
      name: "raiser",
      description: "raises outside define_tool's guard",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _args, _ctx -> raise "unguarded" end
    }

    tk = Toolnexus.create_toolkit!(extra_tools: [raw, raiser], builtins: false)
    handle = serve!(tk, mcp: %{})

    {200, body} = post_mcp(handle.url, rpc(1, "tools/call", %{"name" => "raw", "arguments" => %{}}))
    assert body["result"] == %{"content" => [%{"type" => "text", "text" => "fine"}], "isError" => false}

    {200, body} = post_mcp(handle.url, rpc(2, "tools/call", %{"name" => "raiser", "arguments" => %{}}))
    assert body["result"]["isError"] == true
    assert [%{"text" => "unguarded"}] = body["result"]["content"]
  end

  defp free_port do
    {:ok, sock} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(sock)
    :gen_tcp.close(sock)
    port
  end
end
