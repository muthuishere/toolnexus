defmodule Toolnexus.Mcp2026EdgeTest do
  # Edge cases of the MCP 2026 client against a scripted (in-memory) HTTP function.
  use ExUnit.Case, async: true
  import ExUnit.CaptureLog

  alias Toolnexus.{Answer, Context, Mcp}
  alias Toolnexus.Mcp.{Client2026, Direct, OAuth, RemoteSkills, TokenStore, Wire}

  @url "http://mock/mcp"

  defp resp(status, body, ct \\ "application/json"),
    do: {:ok, %{status: status, headers: [{"content-type", ct}], body: body}}

  defp rpc(id, result), do: Jason.encode!(%{"jsonrpc" => "2.0", "id" => id, "result" => result})

  # A server whose `tools/call` / other replies come from `fun.(method, params, id)`.
  defp client(fun, opts \\ []) do
    http = fn :post, _u, _h, body ->
      req = Jason.decode!(body)
      fun.(req["method"], req["params"], req["id"])
    end

    Client2026.new([url: @url, http: http] ++ opts)
  end

  test "SSE replies: the final data: line carrying a result is used" do
    c =
      client(fn _, _, id ->
        resp(
          200,
          "event: x\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"n\"}\n\ndata: #{rpc(id, %{"ok" => 1})}\n\n",
          "text/event-stream"
        )
      end)

    assert {:ok, %{"ok" => 1, "resultType" => "complete"}} = Client2026.request(c, "x")
  end

  test "result-shape errors: unknown resultType, unsupported input request, wrong id, not rpc, 5xx" do
    c = client(fn _, _, id -> resp(200, rpc(id, %{"resultType" => "weird"})) end)

    assert {:error, "tools/call t: unknown resultType \"weird\""} =
             Client2026.call_tool(c, "t", %{})

    c =
      client(fn _, _, id ->
        resp(
          200,
          rpc(id, %{
            "resultType" => "input_required",
            "inputRequests" => %{"k" => %{"method" => "sampling/x"}}
          })
        )
      end)

    assert {:error, "tools/call t: unsupported input request \"k\""} =
             Client2026.call_tool(c, "t", %{})

    c = client(fn _, _, _ -> resp(200, rpc(999, %{})) end)
    assert {:error, :not_rpc} = Client2026.request(c, "x")
    assert Client2026.discover(c, nil) == :fallback

    c = client(fn _, _, _ -> resp(503, "down", "text/plain") end)
    assert {:error, {:http_status, 503, "down"}} = Client2026.request(c, "x")
    assert Direct.text({:http_status, 503, "down"}) == "HTTP 503 down"
    assert Client2026.discover(c, nil) == :fallback

    c = client(fn _, _, id -> resp(200, rpc(id, %{"supportedVersions" => ["2025-11-25"]})) end)
    assert Client2026.discover(c, nil) == :fallback

    c = client(fn _, _, _ -> resp(418, "teapot", "text/plain") end)
    assert {:error, {:http_status, 418, _}} = Client2026.discover(c, nil)

    c =
      client(fn _, _, id ->
        resp(
          200,
          Jason.encode!(%{
            "jsonrpc" => "2.0",
            "id" => id,
            "error" => %{"code" => -32000, "message" => "nope"}
          })
        )
      end)

    assert {:error, %Toolnexus.Mcp.RpcError{code: -32000}} = Client2026.list_tools(c, nil)

    for e <- [%Toolnexus.Mcp.RpcError{message: "m"}, :not_rpc, :timeout, :closed, "s", {:x}],
        do: assert(is_binary(Direct.text(e)))
  end

  test "an endless input_required loop is bounded" do
    c =
      client(fn _, _, id ->
        resp(200, rpc(id, %{"resultType" => "input_required", "inputRequests" => %{}}))
      end)

    assert {:error, "tools/call t: too many input_required rounds"} =
             Client2026.call_tool(c, "t", %{})
  end

  test "MRTR request ids are mrtr-<random>; URL mode maps to authorization" do
    c =
      client(fn _, p, id ->
        if p["inputResponses"],
          do:
            resp(
              200,
              rpc(id, %{
                "content" => [%{"type" => "text", "text" => Jason.encode!(p["inputResponses"])}]
              })
            ),
          else:
            resp(
              200,
              rpc(id, %{
                "resultType" => "input_required",
                "requestState" => "s",
                "inputRequests" => %{
                  "u" => %{
                    "method" => "elicitation/create",
                    "params" => %{"mode" => "url", "message" => "go", "url" => "https://x"}
                  }
                }
              })
            )
      end)

    {:pending, req, k} = Client2026.call_tool(c, "t", %{})
    assert "mrtr-" <> _ = req.id
    assert {req.kind, req.url} == {"authorization", "https://x"}
    {:ok, r} = k.(%Answer{id: req.id, ok: false})
    assert hd(r["content"])["text"] == ~s({"u":{"action":"cancel"}})
  end

  defp token_store_fns do
    {:ok, a} = Agent.start_link(fn -> %{} end)

    %{
      load: fn r -> Agent.get(a, &Map.get(&1, r)) end,
      save: fn r, t -> Agent.update(a, &Map.put(&1, r, t)) end,
      delete: fn r -> Agent.update(a, &Map.delete(&1, r)) end
    }
  end

  # An HTTP function serving a protected resource + AS metadata, scripted per URL.
  defp oauth_http(over) do
    fn m, u, h, b ->
      case over.(m, u, h, b) do
        nil ->
          cond do
            String.contains?(u, "oauth-protected-resource") ->
              resp(
                200,
                Jason.encode!(%{
                  "resource" => @url,
                  "authorization_servers" => ["https://as.x"],
                  "scopes_supported" => ["r"]
                })
              )

            String.contains?(u, "oauth-authorization-server") ->
              resp(
                200,
                Jason.encode!(%{
                  "issuer" => "https://as.x",
                  "authorization_endpoint" => "https://as.x/a",
                  "token_endpoint" => "https://as.x/t",
                  "code_challenge_methods_supported" => ["S256"]
                })
              )

            u == "https://as.x/t" ->
              resp(
                400,
                Jason.encode!(%{
                  "error" => "invalid_grant",
                  "error_description" => "SECRET-ish body"
                })
              )

            true ->
              {:ok, %{status: 401, headers: [{"www-authenticate", "Bearer"}], body: ""}}
          end

        r ->
          r
      end
    end
  end

  test "OAuth: token endpoint errors never carry the body; basic/post client auth; denial" do
    for {method, check} <- [
          {nil,
           fn h, b ->
             assert List.keymember?(h, "authorization", 0) and not (b =~ "client_id=")
           end},
          {"client_secret_post", fn _h, b -> assert b =~ "client_secret=s3" end}
        ] do
      seen = self()

      http =
        oauth_http(fn
          :post, "https://as.x/t", h, b -> send(seen, {:tok, h, b}) && nil
          _, _, _, _ -> nil
        end)

      o =
        OAuth.new(
          %{
            "clientId" => "cid",
            "clientSecret" => "s3",
            "tokenAuthMethod" => method,
            "mode" => "pause",
            "redirectUri" => "http://127.0.0.1/cb"
          },
          http: http,
          token_store: token_store_fns()
        )

      c = Client2026.new(url: @url, http: http, auth: o)
      {:pending, req, k} = Client2026.request(c, "x")
      q = URI.decode_query(URI.parse(req.url).query)
      cb = "http://127.0.0.1/cb?code=c&state=#{q["state"]}"

      assert {:error, "token endpoint: HTTP 400 invalid_grant"} =
               k.(%Answer{id: req.id, ok: true, data: %{"callbackUrl" => cb}})

      assert_received {:tok, h, b}
      check.(h, b)
    end

    http = oauth_http(fn _, _, _, _ -> nil end)

    o =
      OAuth.new(%{"clientId" => "cid", "mode" => "pause", "redirectUri" => "http://127.0.0.1/cb"},
        http: http
      )

    c = Client2026.new(url: @url, http: http, auth: o)
    {:pending, req, k} = Client2026.request(c, "x")
    assert {:error, "authorization denied: cancelled"} = k.(%Answer{id: req.id, ok: false})
    q = URI.decode_query(URI.parse(req.url).query)

    assert {:error, "authorization denied: access_denied"} =
             OAuth.resume(
               o,
               req.id,
               "http://127.0.0.1/cb?error=access_denied&state=#{q["state"]}"
             )
  end

  test "OAuth refusals: PRM mismatch, no AS, http AS, AS issuer mismatch, 403 other, pause without redirect, failing opener" do
    cases = [
      {fn _, u, _, _ ->
         if u =~ "protected", do: resp(200, ~s({"resource":"http://other/mcp"}))
       end, "does not match"},
      {fn _, u, _, _ -> if u =~ "protected", do: resp(200, ~s({"resource":"#{@url}"})) end,
       "PRM lists no authorization_servers"},
      {fn _, u, _, _ ->
         if u =~ "protected",
           do: resp(200, ~s({"resource":"#{@url}","authorization_servers":["http://as.x"]}))
       end, "authorization server must use https"},
      {fn _, u, _, _ ->
         if u =~ "authorization-server", do: resp(200, ~s({"issuer":"https://evil"}))
       end, "!= https://as.x"},
      {fn _, u, _, _ -> if u =~ "protected", do: resp(404, "") end,
       "protected resource metadata"},
      {fn _, u, _, _ -> if u =~ "protected", do: resp(200, "[1]") end, "not an object"},
      {fn _, u, _, _ ->
         if u == @url,
           do:
             {:ok,
              %{status: 403, headers: [{"www-authenticate", ~s(Bearer error="nope")}], body: ""}}
       end, "forbidden: nope"}
    ]

    for {over, want} <- cases do
      http = oauth_http(over)
      c = Client2026.new(url: @url, http: http, auth: OAuth.new(%{"clientId" => "c"}, http: http))
      assert {:error, msg} = Client2026.request(c, "x")
      assert msg =~ want
    end

    http = oauth_http(fn _, _, _, _ -> nil end)

    c =
      Client2026.new(
        url: @url,
        http: http,
        auth: OAuth.new(%{"clientId" => "c", "mode" => "pause"}, http: http)
      )

    assert {:error, "auth mode pause needs redirectUri"} = Client2026.request(c, "x")

    for opener <- [fn _ -> {:error, :no_browser} end, fn _ -> raise "boom" end] do
      c =
        Client2026.new(
          url: @url,
          http: http,
          auth: OAuth.new(%{"clientId" => "c"}, http: http, open_url: opener)
        )

      assert {:error, "open authorization URL: " <> _} = Client2026.request(c, "x")
    end
  end

  test "auth retries are bounded at 3" do
    http = fn _, u, _, _ ->
      if u == @url,
        do: {:ok, %{status: 401, headers: [{"www-authenticate", "Bearer"}], body: ""}},
        else: nil
    end

    store = token_store_fns()

    tok = %Toolnexus.Mcp.Token{
      access_token: "a",
      issuer: "https://as.x",
      client_id: "c",
      resource: @url
    }

    TokenStore.save(store, @url, tok)
    full = oauth_http(http)

    c =
      Client2026.new(
        url: @url,
        http: full,
        auth: OAuth.new(%{"clientId" => "c"}, http: full, token_store: store)
      )

    assert {:error, "authorization failed: HTTP 401 after 4 attempts"} =
             Client2026.request(c, "x")
  end

  test "remote skills: containment, digest and empty reads are refused before writing" do
    dir = Path.join(System.tmp_dir!(), "tn-edge-#{System.unique_integer([:positive])}")

    mk = fn res, read ->
      client(fn
        "skills/get", _, id ->
          resp(200, rpc(id, %{"skill" => %{"resources" => res}}))

        "resources/read", _, id ->
          resp(200, rpc(id, read))

        "skills/list", _, id ->
          resp(
            200,
            rpc(id, %{
              "skills" => [
                %{"uri" => "skill://s/SKILL.md"},
                %{"nouri" => 1},
                %{"uri" => "skill://../x"}
              ]
            })
          )
      end)
    end

    c = mk.([%{"uri" => "skill://other/a"}], %{})

    assert {:error, "refusing skill resource" <> _} =
             RemoteSkills.download(c, "skill://s/SKILL.md", dir)

    c =
      mk.([%{"uri" => "skill://s/a", "digest" => "sha256:00"}], %{
        "contents" => [%{"text" => "x"}]
      })

    assert {:error, "skill://s/a: digest mismatch"} =
             RemoteSkills.download(c, "skill://s/SKILL.md", dir)

    c = mk.([%{"uri" => "skill://s/a"}], %{"contents" => []})

    assert {:error, "skill://s/a: empty resources/read"} =
             RemoteSkills.download(c, "skill://s/SKILL.md", dir)

    refute File.exists?(dir)

    c = mk.([%{"uri" => "skill://other/a"}], %{})
    log = capture_log(fn -> assert {:ok, []} = RemoteSkills.fetch(c, nil) end)
    assert log =~ "skipped"
    assert {:error, _} = RemoteSkills.parse_uri("skill://x/%zz")

    c =
      client(fn _, _, id ->
        resp(
          200,
          Jason.encode!(%{
            "jsonrpc" => "2.0",
            "id" => id,
            "error" => %{"code" => -32602, "message" => "Unknown skill"}
          })
        )
      end)

    assert {:error, "Unknown skill"} = RemoteSkills.download(c, "skill://s/SKILL.md", dir)
    assert {:error, %Toolnexus.Mcp.RpcError{}} = RemoteSkills.fetch(c, nil)
  end

  test "Wire.get_json error shapes" do
    f = fn r -> fn _, _, _, _ -> r end end
    assert {:error, "GET u: bad JSON"} = Wire.get_json(f.(resp(200, "{")), "u")
    assert {:error, "GET u: HTTP 500"} = Wire.get_json(f.(resp(500, "")), "u")
    assert {:error, "e"} = Wire.get_json(f.({:error, "e"}), "u")
  end

  test "disableTools and the tools map apply before tool search; a cached tools list refreshes after ttlMs" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    http = fn :post, _u, _h, body ->
      req = Jason.decode!(body)

      r =
        case req["method"] do
          "server/discover" ->
            %{"supportedVersions" => ["2026-07-28"]}

          "tools/list" ->
            Agent.update(n, &(&1 + 1))

            %{
              "ttlMs" => 1,
              "tools" => [
                %{"name" => "alpha_tool"},
                %{"name" => "beta_tool"},
                %{"name" => "gamma"}
              ]
            }

          "tools/call" ->
            %{"content" => [%{"type" => "text", "text" => "ran " <> req["params"]["name"]}]}
        end

      resp(200, rpc(req["id"], r))
    end

    src =
      Mcp.load(%{"m" => %{"url" => @url, "toolSearch" => true, "tools" => %{"gamma" => false}}},
        http: http,
        disable_tools: ["m_beta_tool"]
      )

    [search, call] = src.tools
    Process.sleep(5)

    assert Jason.decode!(search.execute.(%{"query" => "tool"}, %Context{}).output)
           |> Enum.map(& &1["name"]) == ["alpha_tool"]

    assert Agent.get(n, & &1) >= 2

    assert call.execute.(%{"name" => "beta_tool"}, %Context{}).output ==
             ~s(unknown tool "beta_tool" on m)

    assert call.execute.(%{"name" => "alpha_tool"}, %Context{}).output == "ran alpha_tool"
    Mcp.close(src)
  end

  test "challenge parsing edge shapes" do
    assert OAuth.parse_challenge("Bearer a=1, b=2") == %{"a" => "1", "b" => "2"}
    assert OAuth.parse_challenge(~s(Bearer a="unterminated)) == %{"a" => "unterminated"}
    assert OAuth.parse_challenge("Bearer junk") == %{}
  end

  test "pause login on discover, then an old server: the frozen path gets the bearer; failed refresh re-authorizes" do
    {:ok, phase} = Agent.start_link(fn -> %{tok: 0, refresh_fail: false} end)

    over = fn
      :post, "https://as.x/t", _h, b ->
        form = URI.decode_query(b)

        if form["grant_type"] == "refresh_token" and Agent.get(phase, & &1.refresh_fail),
          do: {:error, "connection reset"},
          else:
            (
              Agent.update(phase, &%{&1 | tok: &1.tok + 1})

              resp(
                200,
                Jason.encode!(%{
                  "access_token" => "AT",
                  "refresh_token" => "RT",
                  "expires_in" => "60"
                })
              )
            )

      :post, @url, h, body ->
        id = Jason.decode!(body)["id"]

        if List.keymember?(h, "authorization", 0),
          do:
            resp(
              200,
              Jason.encode!(%{
                "jsonrpc" => "2.0",
                "id" => id,
                "error" => %{"code" => -32601, "message" => "nf"}
              })
            ),
          else: nil

      _, _, _, _ ->
        nil
    end

    http = oauth_http(over)

    wait_for = fn r ->
      st = URI.decode_query(URI.parse(r.url).query)["state"]

      %Answer{
        id: r.id,
        ok: true,
        data: %{"callbackUrl" => "http://127.0.0.1/cb?code=c&state=#{st}"}
      }
    end

    store = token_store_fns()

    log =
      capture_log(fn ->
        src =
          Mcp.load(
            %{
              "o" => %{
                "url" => @url,
                "auth" => %{
                  "clientId" => "c",
                  "mode" => "pause",
                  "redirectUri" => "http://127.0.0.1/cb"
                }
              }
            },
            http: http,
            wait_for: wait_for,
            token_store: store
          )

        # the frozen path then tries the unreachable mock host: isolated failure
        assert src.status == %{"o" => "failed"}
        Mcp.close(src)
      end)

    assert log =~ "failed"
    assert %Toolnexus.Mcp.Token{access_token: "AT"} = TokenStore.load(store, @url)

    # expired + refresh fails ⇒ token deleted, a fresh login happens
    o =
      OAuth.new(%{"clientId" => "c", "mode" => "pause", "redirectUri" => "http://127.0.0.1/cb"},
        http: http,
        token_store: store
      )

    c = Client2026.new(url: @url, http: http, auth: o)
    assert {:ok, _} = Client2026.discover(c, wait_for) |> then(fn :fallback -> {:ok, :fb} end)
    t = TokenStore.load(store, @url)
    TokenStore.save(store, @url, %{t | expires_at: System.system_time(:second) - 60})
    Agent.update(phase, &%{&1 | refresh_fail: true})
    assert OAuth.bearer(o, @url) == nil
    assert TokenStore.load(store, @url) == nil
    TokenStore.delete(store, @url)
  end
end
