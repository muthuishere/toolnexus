defmodule Toolnexus.McpHandlerTest do
  # Mirrors golang/mcphandler_test.go (SPEC §7C.1, ADR 0039): the in-house MCP
  # client over real streamable HTTP where it fits, raw HTTP for the transport
  # rules and the elicitation round trip (the in-house client buffers a POST's
  # SSE response, so it cannot answer an elicitation mid-stream).
  use ExUnit.Case, async: false

  import Toolnexus.McpHandlerHelpers

  alias Toolnexus.{
    Context,
    Mcp,
    McpHandler,
    McpServe,
    Tool,
    ToolAnnotations,
    ToolHints,
    ToolResult
  }

  alias Toolnexus.McpHandler.Decision

  @moduletag timeout: 30_000

  defp counter do
    {:ok, a} = Agent.start_link(fn -> 0 end)
    a
  end

  defp count(a), do: Agent.get(a, & &1)

  defp test_tool(name, ran \\ nil) do
    %Tool{
      name: name,
      description: "#{name} tool",
      source: "native",
      input_schema: %{"type" => "object", "properties" => %{"id" => %{"type" => "string"}}},
      execute: fn _args, ctx ->
        if ran, do: Agent.update(ran, &(&1 + 1))
        user = McpHandler.identity(ctx)
        ToolResult.ok("#{name}:#{user}")
      end
    }
  end

  defp bearer_auth(conn) do
    case Plug.Conn.get_req_header(conn, "authorization") do
      ["Bearer " <> tok] when tok in ["alice", "bob"] -> {:ok, tok}
      _ -> {:error, "bad token"}
    end
  end

  defp handler(cfg, tk \\ nil) do
    h = Toolnexus.mcp_handler(tk, cfg)
    on_exit(fn -> if Process.alive?(h.server), do: McpHandler.stop(h) end)
    h
  end

  defp text_of(%{"result" => %{"content" => [%{"text" => t} | _]}}), do: t

  defp call(url, sid, name, args, opts \\ []) do
    params = if args == :none, do: %{"name" => name}, else: %{"name" => name, "arguments" => args}

    decode(
      post(
        url,
        rpc(System.unique_integer([:positive]), "tools/call", params),
        Keyword.put(opts, :sid, sid)
      )
    )
  end

  test "auth rejects with 401 and WWW-Authenticate" do
    url =
      start_handler(
        handler(
          authenticate: &bearer_auth/1,
          www_authenticate: fn _conn, _err ->
            ~s(Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource")
          end
        )
      )

    {status, h, _} = post(url, rpc(1, "ping"))
    assert status == 401
    assert h["www-authenticate"] =~ "resource_metadata="

    # an empty header value is not sent; a raising authenticate is a 401 too
    url2 =
      start_handler(
        handler(authenticate: fn _ -> raise "x" end, www_authenticate: fn _, _ -> "" end)
      )

    {401, h2, _} = post(url2, rpc(1, "ping"))
    refute Map.has_key?(h2, "www-authenticate")
  end

  test "per-identity tools; a tool outside the list is never executed" do
    ran_admin = counter()

    url =
      start_handler(
        handler(
          authenticate: &bearer_auth/1,
          list_tools: fn ctx ->
            # runtime-synthesized: none of these are registered in a toolkit
            tools = [test_tool("search")]

            if McpHandler.identity(ctx) == "alice",
              do: {:ok, tools ++ [test_tool("admin_reset", ran_admin)]},
              else: tools
          end
        )
      )

    # the in-house MCP client against the handler, per identity
    load = fn tok ->
      src =
        Mcp.load(%{
          "mcpServers" => %{
            "s" => %{
              "url" => url,
              "headers" => %{"Authorization" => "Bearer #{tok}"},
              "timeout" => 10_000
            }
          }
        })

      on_exit(fn -> Mcp.close(src) end)
      src
    end

    alice = load.("alice")
    bob = load.("bob")
    assert Enum.map(alice.tools, & &1.name) == ["s_search", "s_admin_reset"]
    assert Enum.map(bob.tools, & &1.name) == ["s_search"]

    search = Enum.find(bob.tools, &(&1.name == "s_search"))
    assert search.execute.(%{}, %Context{}).output == "search:bob"

    sid = init_session(url, token: "Bearer bob")

    assert %{"error" => %{"code" => -32602, "message" => "tool not found: admin_reset"}} =
             call(url, sid, "admin_reset", %{}, token: "Bearer bob")

    assert count(ran_admin) == 0

    reset = Enum.find(alice.tools, &(&1.name == "s_admin_reset"))
    assert reset.execute.(%{}, %Context{}).output == "admin_reset:alice"
    assert count(ran_admin) == 1
  end

  defp gated(fallback) do
    ran = counter()

    del = %{
      test_tool("delete_record", ran)
      | annotations: %ToolAnnotations{
          title: "Delete",
          destructive_hint: true,
          read_only_hint: false,
          idempotent_hint: true,
          open_world_hint: false
        }
    }

    h =
      handler(
        authenticate: &bearer_auth/1,
        list_tools: fn _ -> {:ok, [del, test_tool("read")]} end,
        before_call: fn _ctx, tool, args ->
          cond do
            tool.annotations && tool.annotations.destructive_hint ->
              if args["nonce"] == "nonce-ok",
                do: :allow,
                else: %Decision{kind: :confirm, message: "Delete the record?", result: fallback}

            args["id"] == "forbidden" ->
              {:deny, "not allowed"}

            true ->
              nil
          end
        end
      )

    {start_handler(h), ran}
  end

  test "confirm via elicitation on the same POST's SSE stream" do
    {url, ran} = gated(nil)

    for {action, expect_text, expect_err, expect_ran} <- [
          {"accept", "delete_record:alice", false, 1},
          {"decline", "declined", true, 1}
        ] do
      sid = init_session(url, token: "Bearer alice", elicit: true)

      task =
        stream(:post, url,
          sid: sid,
          token: "Bearer alice",
          body: rpc(9, "tools/call", %{"name" => "delete_record", "arguments" => %{"id" => "1"}})
        )

      ask = await_event(&(&1["method"] == "elicitation/create"))
      assert ask["params"]["message"] == "Delete the record?"
      assert ask["params"]["requestedSchema"]["properties"]["confirm"]["type"] == "boolean"

      answer = %{
        "jsonrpc" => "2.0",
        "id" => ask["id"],
        "result" => %{"action" => action, "content" => %{"confirm" => action == "accept"}}
      }

      assert {202, _, _} = post(url, answer, sid: sid, token: "Bearer alice")
      final = await_event(&(&1["id"] == 9))
      Task.await(task)

      assert text_of(final) == expect_text
      assert Map.get(final["result"], "isError", false) == expect_err
      assert count(ran) == expect_ran
    end
  end

  test "elicitation: content confirm=false, client error and timeout" do
    h =
      handler(
        confirm_timeout: 200,
        list_tools: fn _ -> [test_tool("x")] end,
        before_call: fn _, _, _ -> {:confirm, nil} end
      )

    url = start_handler(h)

    run = fn reply ->
      sid = init_session(url, elicit: true)
      task = stream(:post, url, sid: sid, body: rpc(9, "tools/call", %{"name" => "x"}))
      ask = await_event(&(&1["method"] == "elicitation/create"))
      assert ask["params"]["message"] == "Confirm this action?"
      if reply, do: post(url, Map.put(reply, "id", ask["id"]), sid: sid)
      final = await_event(&(&1["id"] == 9))
      Task.await(task)
      text_of(final)
    end

    assert run.(%{
             "jsonrpc" => "2.0",
             "result" => %{"action" => "accept", "content" => %{"confirm" => false}}
           }) ==
             "declined"

    assert run.(%{"jsonrpc" => "2.0", "error" => %{"code" => 1, "message" => "no"}}) ==
             "confirmation failed: client returned an error"

    assert run.(%{"jsonrpc" => "2.0", "result" => "bad"}) ==
             "confirmation failed: invalid elicitation result"

    assert run.(nil) == "confirmation failed: timeout"
    # a stray response for an unknown id is accepted and ignored
    sid = init_session(url)

    assert {202, _, _} =
             post(url, %{"jsonrpc" => "2.0", "id" => "nope", "result" => %{}}, sid: sid)

    assert {202, _, _} = post(url, %{"jsonrpc" => "2.0", "id" => 5, "result" => %{}}, sid: sid)
  end

  test "confirm without elicitation: plain isError, then a host-shaped nonce re-call" do
    {url, ran} = gated(nil)
    sid = init_session(url, token: "Bearer alice")
    r = call(url, sid, "delete_record", %{"id" => "1"}, token: "Bearer alice")
    assert text_of(r) == "confirmation required: Delete the record?"
    assert r["result"]["isError"] == true
    assert count(ran) == 0

    {url2, ran2} = gated(%ToolResult{output: ~s(re-call with {"nonce":"nonce-ok"})})
    sid2 = init_session(url2, token: "Bearer alice")
    r = call(url2, sid2, "delete_record", %{"id" => "1"}, token: "Bearer alice")
    refute Map.has_key?(r["result"], "isError")
    assert text_of(r) =~ "nonce-ok"
    assert count(ran2) == 0

    r =
      call(url2, sid2, "delete_record", %{"id" => "1", "nonce" => "nonce-ok"},
        token: "Bearer alice"
      )

    assert text_of(r) == "delete_record:alice"
    assert count(ran2) == 1
  end

  test "deny and annotations" do
    {url, _} = gated(nil)
    sid = init_session(url, token: "Bearer alice")
    r = call(url, sid, "read", %{"id" => "forbidden"}, token: "Bearer alice")
    assert r["result"]["isError"] == true
    assert text_of(r) == "not allowed"

    tools =
      decode(post(url, rpc(2, "tools/list"), sid: sid, token: "Bearer alice"))["result"]["tools"]

    assert hd(tools)["annotations"] == %{
             "title" => "Delete",
             "destructiveHint" => true,
             "readOnlyHint" => false,
             "idempotentHint" => true,
             "openWorldHint" => false
           }

    refute Map.has_key?(Enum.at(tools, 1), "annotations")
  end

  test "before_call decision shapes" do
    decide = fn args ->
      case args["d"] do
        "deny" -> :deny
        "error" -> {:error, "gate down"}
        "raise" -> raise "gate raised"
        "confirm" -> :confirm
        "bogus" -> %Decision{kind: :weird}
        "deny_result" -> %Decision{kind: :deny, result: ToolResult.ok("shaped")}
        "other" -> :something
        _ -> :allow
      end
    end

    url =
      start_handler(
        handler(
          stateless: true,
          list_tools: fn _ -> [test_tool("t")] end,
          before_call: fn _, _, a -> decide.(a) end
        )
      )

    t = fn d -> text_of(call(url, nil, "t", %{"d" => d})) end
    assert t.("deny") == "denied"
    assert t.("error") == "gate down"
    assert t.("raise") == "gate raised"
    assert t.("confirm") == "confirmation required: "
    assert t.("bogus") == "t:"
    assert t.("deny_result") == "shaped"
    assert t.("other") == "t:"
    assert t.("allow") == "t:"
  end

  test "on_call event; raises are isolated" do
    {:ok, evs} = Agent.start_link(fn -> [] end)

    boom = %Tool{
      name: "boom",
      description: "",
      input_schema: %{"type" => "object"},
      source: "native",
      execute: fn _, _ ->
        Process.sleep(5)
        {:error, "kaput"}
      end
    }

    url =
      start_handler(
        handler(
          authenticate: &bearer_auth/1,
          list_tools: fn _ -> [boom] end,
          on_call: fn ev ->
            Agent.update(evs, &(&1 ++ [ev]))
            raise "isolated"
          end
        )
      )

    sid = init_session(url, token: "Bearer bob")
    r = call(url, sid, "boom", :none, token: "Bearer bob")
    assert r["result"]["isError"] == true
    assert text_of(r) == "kaput"

    [ev] = Agent.get(evs, & &1)
    assert ev.name == "boom" and ev.source == "native" and ev.is_error
    assert ev.err == "kaput"
    assert ev.result_bytes > 0 and ev.ms >= 5
    assert ev.identity == "bob" and ev.session_id == sid and ev.decision == :allow
  end

  test "execute return shapes: raise, {:ok, result}, binary, other" do
    mk = fn name, f ->
      %Tool{name: name, description: "", input_schema: nil, source: "native", execute: f}
    end

    tools = [
      mk.("raise", fn _, _ -> raise "exploded" end),
      mk.("ok", fn _, _ -> {:ok, ToolResult.ok("wrapped")} end),
      mk.("bin", fn _, _ -> "plain" end),
      mk.("map", fn _, _ -> %{"a" => 1} end),
      mk.("err_atom", fn _, _ -> {:error, :nope} end),
      mk.("err_other", fn _, _ -> {:error, {:x, 1}} end)
    ]

    url = start_handler(handler(stateless: true, list_tools: fn _ -> tools end))
    assert text_of(call(url, nil, "raise", %{})) == "exploded"
    assert text_of(call(url, nil, "ok", %{})) == "wrapped"
    assert text_of(call(url, nil, "bin", %{})) == "plain"
    assert text_of(call(url, nil, "map", %{})) == ~s({"a":1})
    assert text_of(call(url, nil, "err_atom", %{})) == "nope"
    assert text_of(call(url, nil, "err_other", %{})) == "{:x, 1}"

    list = decode(post(url, rpc(1, "tools/list")))["result"]["tools"]
    assert hd(list)["inputSchema"] == %{"type" => "object"}
  end

  test "notify_tools_changed reaches an open GET stream" do
    h = handler(list_tools: fn _ -> [test_tool("a")] end)
    url = start_handler(h)
    sid = init_session(url)
    task = stream(:get, url, sid: sid, accept: "text/event-stream")

    # retry until the stream is registered
    Enum.reduce_while(1..100, nil, fn _, _ ->
      McpHandler.notify_tools_changed(h)

      receive do
        {:chunk, data} -> {:halt, assert(data =~ "notifications/tools/list_changed")}
      after
        50 -> {:cont, nil}
      end
    end)

    Task.shutdown(task, :brutal_kill)
  end

  test "server_info, resources and prompts hooks" do
    url =
      start_handler(
        handler(
          authenticate: &bearer_auth/1,
          server_info: fn ctx ->
            %{
              name: "srv-#{McpHandler.request(ctx).host}-#{McpHandler.identity(ctx)}",
              version: "9.9.9",
              instructions: "be careful"
            }
          end,
          list_resources: fn _ ->
            {:ok, [%{uri: "docs://api", name: "api", mime_type: "text/markdown"}]}
          end,
          read_resource: fn ctx, uri ->
            if uri == "docs://bin",
              do: [%{uri: uri, blob: "AAEC"}],
              else: [
                %{
                  uri: uri,
                  mime_type: "text/markdown",
                  text: "# API for #{McpHandler.identity(ctx)}"
                }
              ]
          end,
          list_prompts: fn _ ->
            [
              %{name: "greet", arguments: [%{name: "who", required: true}, %{name: "tone"}]},
              %{name: "bare"}
            ]
          end,
          get_prompt: fn _ctx, name, args ->
            if name == "fail",
              do: {:error, "no such prompt"},
              else: %{messages: [%{role: "user", text: "hello #{args["who"]}"}]}
          end
        )
      )

    opts = [token: "Bearer alice"]

    {200, h, body} =
      post(
        url,
        rpc(1, "initialize", %{"protocolVersion" => "2025-06-18", "capabilities" => %{}}),
        opts
      )

    init = Jason.decode!(body)["result"]
    assert init["serverInfo"] == %{"name" => "srv-127.0.0.1-alice", "version" => "9.9.9"}
    assert init["instructions"] == "be careful"
    assert init["protocolVersion"] == "2025-06-18"
    assert init["capabilities"]["resources"] == %{} and init["capabilities"]["prompts"] == %{}
    opts = Keyword.put(opts, :sid, h["mcp-session-id"])

    assert decode(post(url, rpc(2, "resources/list"), opts))["result"]["resources"] ==
             [%{"uri" => "docs://api", "name" => "api", "mimeType" => "text/markdown"}]

    assert decode(post(url, rpc(3, "resources/read", %{"uri" => "docs://api"}), opts))["result"][
             "contents"
           ] ==
             [
               %{
                 "uri" => "docs://api",
                 "mimeType" => "text/markdown",
                 "text" => "# API for alice"
               }
             ]

    assert decode(post(url, rpc(3, "resources/read", %{"uri" => "docs://bin"}), opts))["result"][
             "contents"
           ] ==
             [%{"uri" => "docs://bin", "blob" => "AAEC"}]

    pr =
      decode(
        post(
          url,
          rpc(4, "prompts/get", %{"name" => "greet", "arguments" => %{"who" => "world"}}),
          opts
        )
      )

    assert pr["result"] == %{
             "messages" => [
               %{"role" => "user", "content" => %{"type" => "text", "text" => "hello world"}}
             ]
           }

    assert decode(post(url, rpc(4, "prompts/get", %{"name" => "fail"}), opts))["error"] ==
             %{"code" => -32602, "message" => "no such prompt"}

    assert decode(post(url, rpc(4, "prompts/get", %{"name" => 1}), opts))["error"]["message"] ==
             "invalid params"

    pl = decode(post(url, rpc(5, "prompts/list"), opts))["result"]["prompts"]

    assert pl == [
             %{
               "name" => "greet",
               "arguments" => [%{"name" => "who", "required" => true}, %{"name" => "tone"}]
             },
             %{"name" => "bare"}
           ]
  end

  test "hook errors map to JSON-RPC errors" do
    url =
      start_handler(
        handler(
          stateless: true,
          list_tools: fn _ -> {:error, "db down"} end,
          list_resources: fn _ -> raise "res down" end,
          read_resource: fn _, _ -> {:error, :missing} end,
          list_prompts: fn _ -> throw(:oops) end,
          get_prompt: fn _, _, _ -> %{} end
        )
      )

    assert decode(post(url, rpc(1, "tools/list")))["error"] == %{
             "code" => -32603,
             "message" => "db down"
           }

    assert decode(post(url, rpc(1, "tools/call", %{"name" => "x"})))["error"]["code"] == -32603
    assert decode(post(url, rpc(1, "tools/call", "bad")))["error"]["message"] == "invalid params"

    assert decode(post(url, rpc(1, "resources/list")))["error"] == %{
             "code" => -32603,
             "message" => "res down"
           }

    assert decode(post(url, rpc(1, "resources/read", %{"uri" => "u"})))["error"] == %{
             "code" => -32002,
             "message" => "missing"
           }

    assert decode(post(url, rpc(1, "prompts/list")))["error"]["message"] == "throw: :oops"

    assert decode(post(url, rpc(1, "prompts/get", %{"name" => "p"})))["result"] == %{
             "messages" => []
           }
  end

  test "protocol conformance (raw streamable HTTP, 2025-06-18)" do
    url = start_handler(handler(list_tools: fn _ -> [] end))

    {200, h, body} =
      post(
        url,
        rpc(1, "initialize", %{
          "protocolVersion" => "2025-06-18",
          "capabilities" => %{},
          "clientInfo" => %{"name" => "x", "version" => "1"}
        })
      )

    sid = h["mcp-session-id"]
    assert sid not in [nil, ""]
    assert h["content-type"] == "application/json"
    assert body =~ ~s("protocolVersion":"2025-06-18") and body =~ ~s("listChanged":true)

    assert decode(post(url, rpc(1, "initialize", %{"protocolVersion" => "1999-01-01"})))["result"][
             "protocolVersion"
           ] ==
             "2025-06-18"

    v = [version: "2025-06-18"]

    assert {202, _, _} =
             post(
               url,
               %{"jsonrpc" => "2.0", "method" => "notifications/initialized"},
               [sid: sid] ++ v
             )

    assert {400, _, _} = post(url, rpc(2, "ping"), v)
    assert {404, _, _} = post(url, rpc(2, "ping"), [sid: "nope"] ++ v)
    assert {400, _, _} = post(url, rpc(2, "ping"), sid: sid, version: "2099-01-01")

    assert {400, _, b} =
             post(url, ~s([{"jsonrpc":"2.0","id":3,"method":"ping"}]), [sid: sid] ++ v)

    assert b =~ "-32600"
    assert {400, _, b} = post(url, "{not json", [sid: sid] ++ v)
    assert Jason.decode!(b)["error"] == %{"code" => -32700, "message" => "parse error"}
    assert {400, _, _} = post(url, %{"jsonrpc" => "1.0", "id" => 1, "method" => "ping"}, sid: sid)

    assert decode(post(url, rpc(4, "ping"), [sid: sid] ++ v))["result"] == %{}
    assert decode(post(url, rpc(5, "resources/list"), [sid: sid] ++ v))["error"]["code"] == -32601

    assert {415, _, _} = post(url, "{}", content_type: "text/plain")
    assert {415, _, _} = raw(:post, url, content_type: "", body: "{}")
    assert {405, _, _} = raw(:get, url, accept: "application/json")
    assert {405, h405, _} = raw(:put, url, sid: sid)
    assert h405["allow"] == "GET, POST, DELETE"
    assert {404, _, _} = raw(:get, url, sid: "nope", accept: "text/event-stream")

    assert {200, _, _} = raw(:delete, url, sid: sid)
    assert {404, _, _} = post(url, rpc(6, "ping"), [sid: sid] ++ v)
    assert {404, _, _} = raw(:delete, url, sid: sid)
  end

  test "max body size ⇒ 413" do
    url = start_handler(handler(stateless: true, max_body_bytes: 64))
    assert {413, _, _} = post(url, rpc(1, "ping", %{"pad" => String.duplicate("x", 200)}))
    assert {200, _, _} = post(url, rpc(1, "ping"))
  end

  defmodule HostIds do
    @behaviour Toolnexus.McpHandler.SessionIds
    def generate(_ctx), do: {:ok, "host-session-1"}
    def validate(_ctx, id), do: {:ok, id == "host-session-1"}
    def terminate(_ctx, _id), do: {:error, :read_only}
  end

  test "custom session ids (map and module) and stateless" do
    {:ok, c} = Agent.start_link(fn -> %{gen: 0, val: 0, term: 0} end)
    bump = fn k -> Agent.update(c, &Map.update!(&1, k, fn n -> n + 1 end)) end

    ids = %{
      generate: fn _ ->
        bump.(:gen)
        {:ok, "host-session-1"}
      end,
      validate: fn _, id ->
        bump.(:val)
        id == "host-session-1"
      end,
      terminate: fn _, _ ->
        bump.(:term)
        :ok
      end
    }

    url = start_handler(handler(session_ids: ids, list_tools: fn _ -> [test_tool("a")] end))
    sid = init_session(url)
    assert sid == "host-session-1"
    assert text_of(call(url, sid, "a", :none)) == "a:"
    assert {200, _, _} = raw(:delete, url, sid: sid)
    counts = Agent.get(c, & &1)
    assert counts.gen == 1 and counts.val > 0 and counts.term == 1

    url_m = start_handler(handler(session_ids: HostIds))
    assert init_session(url_m) == "host-session-1"
    assert {200, _, _} = post(url_m, rpc(2, "ping"), sid: "host-session-1")
    assert {404, _, _} = post(url_m, rpc(2, "ping"), sid: "other")
    assert {500, _, _} = raw(:delete, url_m, sid: "host-session-1")

    url_bad = start_handler(handler(session_ids: %{generate: fn _ -> {:error, :x} end}))

    assert decode(post(url_bad, rpc(7, "initialize", %{})))["error"] ==
             %{"code" => -32603, "message" => "cannot create session"}

    st = start_handler(handler(stateless: true, list_tools: fn _ -> [test_tool("a")] end))
    {200, h, _} = post(st, rpc(1, "initialize", %{"protocolVersion" => "2025-06-18"}))
    refute Map.has_key?(h, "mcp-session-id")
    assert text_of(call(st, nil, "a", :none)) == "a:"
    assert {405, _, _} = raw(:delete, st)
  end

  test "defaults to the toolkit's tools" do
    tk = Toolnexus.create_toolkit!(extra_tools: [test_tool("registered")], builtins: false)
    url = start_handler(handler([], tk))
    sid = init_session(url)

    names =
      decode(post(url, rpc(2, "tools/list"), sid: sid))["result"]["tools"]
      |> Enum.map(& &1["name"])

    assert "registered" in names

    url0 = start_handler(handler(stateless: true))
    assert decode(post(url0, rpc(2, "tools/list")))["result"]["tools"] == []
  end

  test "structuredContent and outputSchema; absent ⇒ not on the wire" do
    st = %Tool{
      name: "stats",
      description: "",
      source: "native",
      input_schema: %{"type" => "object"},
      output_schema: %{"type" => "object", "properties" => %{"count" => %{"type" => "integer"}}},
      execute: fn _, _ -> %ToolResult{structured: %{"count" => 3}} end
    }

    plain = test_tool("plain")
    url = start_handler(handler(stateless: true, list_tools: fn _ -> [st, plain] end))
    [t0, t1] = decode(post(url, rpc(1, "tools/list")))["result"]["tools"]
    assert t0["outputSchema"]["type"] == "object"
    refute Map.has_key?(t1, "outputSchema")

    r = call(url, nil, "stats", :none)["result"]
    assert r["structuredContent"] == %{"count" => 3}
    assert text_of(%{"result" => r}) == ~s({"count":3})

    wire = McpHandler.wire_result(ToolResult.ok("x"))
    assert wire == %{"content" => [%{"type" => "text", "text" => "x"}]}
  end

  test "request _meta reaches hooks and tools; result meta ⇒ _meta" do
    {:ok, seen} = Agent.start_link(fn -> %{} end)

    tool = %Tool{
      name: "m",
      description: "",
      input_schema: %{"type" => "object"},
      source: "native",
      execute: fn _, ctx ->
        Agent.update(seen, &Map.put(&1, :tool, McpHandler.request_meta(ctx)))
        %ToolResult{output: "ok", meta: %{"usage" => %{"units" => 2}}}
      end
    }

    url =
      start_handler(
        handler(
          stateless: true,
          list_tools: fn _ -> [tool] end,
          before_call: fn ctx, _, _ ->
            Agent.update(seen, &Map.put(&1, :hook, McpHandler.request_meta(ctx)))
            :allow
          end
        )
      )

    {_, _, b} =
      post(
        url,
        rpc(1, "tools/call", %{
          "name" => "m",
          "_meta" => %{"clientCaps" => %{"elicitation" => %{}}}
        })
      )

    assert b =~ ~s("_meta":{"usage":{"units":2}})
    s = Agent.get(seen, & &1)
    assert Map.has_key?(s.tool, "clientCaps") and Map.has_key?(s.hook, "clientCaps")
    assert McpHandler.request_meta(nil) == nil and McpHandler.session_id(%Context{}) == nil
  end

  test "protocol versions per handler" do
    init = fn v -> rpc(1, "initialize", %{"protocolVersion" => v}) end
    def_url = start_handler(handler(stateless: true))

    newer =
      start_handler(
        handler(
          stateless: true,
          protocol_versions: ["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26"],
          list_tools: fn _ -> [test_tool("a")] end
        )
      )

    assert decode(post(def_url, init.("2025-11-25")))["result"]["protocolVersion"] == "2025-06-18"
    assert {400, _, _} = post(def_url, rpc(2, "ping"), version: "2025-11-25")
    assert decode(post(newer, init.("2025-11-25")))["result"]["protocolVersion"] == "2025-11-25"
    assert decode(post(newer, init.("2026-07-28")))["result"]["protocolVersion"] == "2025-11-25"
    assert {400, _, _} = post(newer, rpc(2, "ping"), version: "2025-03-26")

    bad = rpc(3, "tools/call", %{"name" => "a", "arguments" => [1]})
    r = decode(post(newer, bad, version: "2025-11-25"))
    assert r["result"]["isError"] == true
    assert text_of(r) == "invalid arguments: must be an object"
    assert decode(post(newer, bad, version: "2025-06-18"))["error"]["code"] == -32602
    assert McpHandler.implemented_versions() == ["2026-07-28", "2025-11-25", "2025-06-18"]
  end

  test "stateless session id and title" do
    url =
      start_handler(
        handler(stateless: true, stateless_session_id: "stateless", title: "Records API")
      )

    {200, h, b} = post(url, rpc(1, "initialize", %{"protocolVersion" => "2025-06-18"}))
    assert h["mcp-session-id"] == "stateless"
    assert b =~ ~s("title":"Records API")
    assert decode(post(url, rpc(2, "ping"), sid: "whatever"))["result"] == %{}

    url2 = start_handler(handler(stateless: true))
    {200, _, b2} = post(url2, rpc(1, "initialize", %{}))
    refute b2 =~ ~s("title")
  end

  test "hints-derived annotations" do
    t = %{test_tool("h") | hints: %ToolHints{read_only: true}}

    assert McpHandler.wire_tool(t)["annotations"] ==
             %{"readOnlyHint" => true, "destructiveHint" => false, "openWorldHint" => false}
  end

  test "Plug init accepts a struct, a 0-arity fun or an MFA" do
    h = handler(stateless: true)
    assert McpHandler.init(h) == h
    f = fn -> h end
    assert McpHandler.init(f) == f
    assert McpHandler.init({Function, :identity, [h]}) == {Function, :identity, [h]}

    for opts <- [f, {Function, :identity, [h]}] do
      conn =
        Plug.Test.conn(:post, "/", Jason.encode!(rpc(1, "ping")))
        |> Plug.Conn.put_req_header("content-type", "application/json")
        |> McpHandler.call(opts)

      assert conn.status == 200
    end
  end

  test "§7C serve path: structured/meta/outputSchema mapped; absent ⇒ byte-identical" do
    plain = %ToolResult{output: "hi"}
    assert McpServe.result_wire(plain) == %{"content" => [%{"type" => "text", "text" => "hi"}]}

    assert Jason.encode!(McpServe.result_wire(plain) |> Map.put("isError", false)) ==
             ~s({"content":[{"text":"hi","type":"text"}],"isError":false})

    w =
      McpServe.result_wire(%ToolResult{
        output: "hi",
        structured: %{"a" => 1},
        meta: %{"k" => "v"}
      })

    assert w["structuredContent"] == %{"a" => 1} and w["_meta"] == %{"k" => "v"}
    assert hd(w["content"])["text"] == "hi"

    tool =
      Toolnexus.define_tool(
        name: "s",
        description: "d",
        output_schema: %{"type" => "object"},
        execute: fn _ -> %ToolResult{structured: %{"n" => 1}} end
      )

    tk = Toolnexus.create_toolkit!(extra_tools: [tool, test_tool("p")], builtins: false)
    handle = Toolnexus.Toolkit.serve(tk, "127.0.0.1:0", mcp: %{})
    on_exit(fn -> Toolnexus.Serve.stop(handle) end)
    list = decode(post(handle.url <> "/mcp", rpc(1, "tools/list")))["result"]["tools"]
    assert Enum.find(list, &(&1["name"] == "s"))["outputSchema"] == %{"type" => "object"}
    refute Map.has_key?(Enum.find(list, &(&1["name"] == "p")), "outputSchema")

    r =
      decode(
        post(handle.url <> "/mcp", rpc(2, "tools/call", %{"name" => "s", "arguments" => %{}}))
      )

    assert r["result"] == %{
             "content" => [%{"type" => "text", "text" => ~s({"n":1})}],
             "structuredContent" => %{"n" => 1},
             "isError" => false
           }
  end
end
