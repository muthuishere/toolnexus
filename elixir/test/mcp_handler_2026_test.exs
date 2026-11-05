defmodule Toolnexus.McpHandler2026Test do
  # SPEC §7C.2 port-own tests (mirror golang/mcphandler2026_test.go): the default JWT
  # verifier against the dev AS, the dev AS flows, onCall/prompts on the 2026 wire,
  # loadMcpSkills, plus the branches the shared conformance cases do not reach.
  use ExUnit.Case, async: false

  import Toolnexus.McpHandlerHelpers

  alias Toolnexus.{McpAuthError, McpDevAuthServer, McpHandler, McpPrincipal, McpServerAuth}
  alias Toolnexus.{Request, Tool, ToolResult}

  @v "2026-07-28"
  @skills_dir Path.expand("../../examples/skills", __DIR__)

  defmodule Router do
    @moduledoc false
    # /mcp ⇒ the handler; /.well-known/oauth-protected-resource* ⇒ the PRM plug.
    @behaviour Plug
    def init(o), do: o

    def call(conn, {handler, auth}) do
      if String.starts_with?(conn.request_path, "/.well-known/"),
        do: McpServerAuth.call(conn, auth),
        else: McpHandler.call(conn, handler)
    end
  end

  defmodule Cimd do
    @moduledoc false
    # Serves a Client ID Metadata Document whose client_id is its own URL.
    @behaviour Plug
    def init(o), do: o

    def call(conn, agent) do
      url = Agent.get(agent, & &1)

      doc = %{
        "client_id" => url,
        "client_name" => "CIMD app",
        "redirect_uris" => ["http://127.0.0.1/cb"]
      }

      conn
      |> Plug.Conn.put_resp_header("content-type", "application/json")
      |> Plug.Conn.send_resp(200, Jason.encode!(doc))
    end
  end

  defp post26(url, token, method, params \\ %{}, extra \\ []) do
    meta = Map.put(params["_meta"] || %{}, "io.modelcontextprotocol/protocolVersion", @v)
    params = Map.put(params, "_meta", meta)

    headers =
      [{"mcp-protocol-version", @v}, {"mcp-method", method}] ++
        if(is_binary(params["name"]), do: [{"mcp-name", params["name"]}], else: []) ++
        if(is_binary(params["uri"]), do: [{"mcp-name", params["uri"]}], else: []) ++ extra

    opts = [headers: headers] ++ if(token, do: [token: "Bearer " <> token], else: [])
    {s, h, b} = post(url, rpc(1, method, params), opts)
    {s, h, if(b == "", do: nil, else: Jason.decode!(b))}
  end

  defp who_tool do
    %Tool{
      name: "who",
      description: "who",
      input_schema: %{"type" => "object"},
      source: "native",
      execute: fn _args, ctx ->
        case McpHandler.identity(ctx) do
          %McpPrincipal{subject: s} -> ToolResult.ok(s)
          _ -> ToolResult.ok("")
        end
      end
    }
  end

  defp text(out), do: get_in(out, ["result", "content", Access.at(0), "text"])

  test "default JWT verifier against the dev AS" do
    {:ok, as} = Toolnexus.start_mcp_dev_auth_server()

    auth =
      McpServerAuth.new(issuer: as.issuer, scopes: %{"*" => ["mcp"], "who" => ["mcp:who"]})

    h =
      Toolnexus.mcp_handler(nil,
        list_tools: fn _ -> [who_tool()] end,
        protocol_versions: [@v],
        authenticate: McpServerAuth.authenticator(auth)
      )

    base = start_plug({Router, {h, auth}}) |> String.trim_trailing("/")
    u = base <> "/mcp"
    now = System.system_time(:second)

    mint = fn over ->
      McpDevAuthServer.mint(
        as,
        Map.merge(
          %{
            "iss" => as.issuer,
            "sub" => "dev-user",
            "aud" => u,
            "scope" => "mcp mcp:who",
            "exp" => now + 60
          },
          over
        )
      )
    end

    {200, _, out} = post26(u, mint.(%{}), "tools/call", %{"name" => "who", "arguments" => %{}})
    assert text(out) == "dev-user"

    good = mint.(%{})

    for {name, tok} <- [
          {"wrong audience", mint.(%{"aud" => "https://other.example/mcp"})},
          {"aud array miss", mint.(%{"aud" => ["x"]})},
          {"aud array hit", nil},
          {"expired", mint.(%{"exp" => now - 1})},
          {"wrong issuer", mint.(%{"iss" => "https://evil.example"})},
          {"tampered",
           binary_part(good, 0, 20) <> "x" <> binary_part(good, 21, byte_size(good) - 21)},
          {"not a jwt", "opaque"},
          {"unknown kid", unknown_kid(good)}
        ],
        tok != nil do
      {s, hd, _} = post26(u, tok, "server/discover")
      www = hd["www-authenticate"]
      assert s == 401, name
      assert www =~ ~s(error="invalid_token"), name
      refute www =~ tok, name
    end

    # an audience array containing the resource is accepted
    {200, _, _} = post26(u, mint.(%{"aud" => ["x", u]}), "server/discover")

    {403, hd, _} =
      post26(u, mint.(%{"scope" => "mcp"}), "tools/call", %{"name" => "who", "arguments" => %{}})

    assert hd["www-authenticate"] ==
             ~s(Bearer resource_metadata="#{base}/.well-known/oauth-protected-resource/mcp", scope="mcp mcp:who", error="insufficient_scope")

    # no token at all
    {401, hd, body} = post26(u, nil, "server/discover")

    assert hd["www-authenticate"] ==
             ~s(Bearer resource_metadata="#{base}/.well-known/oauth-protected-resource/mcp", scope="mcp")

    assert body == %{"error" => "unauthorized"}

    {200, _, prm} = raw(:get, base <> "/.well-known/oauth-protected-resource/mcp")
    prm = Jason.decode!(prm)
    assert prm["resource"] == u
    assert prm["authorization_servers"] == [as.issuer]
    assert prm["scopes_supported"] == ["mcp"]
    {404, _, _} = raw(:get, base <> "/.well-known/oauth-protected-resource/other")

    McpServerAuth.stop(auth)
    McpDevAuthServer.close(as)
  end

  defp unknown_kid(tok) do
    [_h, c, s] = String.split(tok, ".")
    h = Base.url_encode64(~s({"alg":"RS256","kid":"other"}), padding: false)
    h <> "." <> c <> "." <> s
  end

  defp pkce_login(as, client_id, redirect, resource, scope) do
    verifier = "v-" <> String.duplicate("a", 50)
    challenge = Base.url_encode64(:crypto.hash(:sha256, verifier), padding: false)

    q =
      URI.encode_query(%{
        "response_type" => "code",
        "client_id" => client_id,
        "redirect_uri" => redirect,
        "state" => "s1",
        "code_challenge" => challenge,
        "code_challenge_method" => "S256",
        "resource" => resource,
        "scope" => scope
      })

    resp = Req.get!(as.issuer <> "/authorize?" <> q, redirect: false, retry: false)
    assert resp.status == 302
    loc = resp |> Req.Response.get_header("location") |> hd() |> URI.parse()
    cb = URI.decode_query(loc.query)
    assert cb["iss"] == as.issuer
    assert cb["state"] == "s1"
    assert cb["code"] not in [nil, ""]

    tr =
      Req.post!(as.issuer <> "/token",
        form: [
          grant_type: "authorization_code",
          code: cb["code"],
          code_verifier: verifier,
          client_id: client_id,
          redirect_uri: redirect,
          resource: resource
        ],
        retry: false
      )

    assert tr.status == 200
    tr.body
  end

  test "dev AS: pre-registered and CIMD clients, refresh, unknown client, counter" do
    {:ok, agent} = Agent.start_link(fn -> nil end)
    doc_url = start_plug({Cimd, agent}) <> "client.json"
    Agent.update(agent, fn _ -> doc_url end)

    {:ok, as} =
      Toolnexus.start_mcp_dev_auth_server(clients: %{"app" => ["http://127.0.0.1/cb"]})

    auth = McpServerAuth.new(issuer: as.issuer, scopes: %{"*" => ["mcp"]})

    h =
      Toolnexus.mcp_handler(nil,
        list_tools: fn _ -> [who_tool()] end,
        protocol_versions: [@v],
        authenticate: McpServerAuth.authenticator(auth)
      )

    u = start_handler(h) <> "mcp"

    for cid <- ["app", doc_url] do
      tok = pkce_login(as, cid, "http://127.0.0.1:5555/cb", u, "mcp")

      {200, _, out} =
        post26(u, tok["access_token"], "tools/call", %{"name" => "who", "arguments" => %{}})

      assert text(out) == "dev-user"

      rr =
        Req.post!(as.issuer <> "/token",
          form: [
            grant_type: "refresh_token",
            refresh_token: tok["refresh_token"],
            client_id: cid,
            resource: u
          ],
          retry: false
        )

      assert rr.status == 200
      # rotation: the old refresh token is spent
      again =
        Req.post!(as.issuer <> "/token",
          form: [grant_type: "refresh_token", refresh_token: tok["refresh_token"], client_id: cid],
          retry: false
        )

      assert again.status == 400
    end

    resp =
      Req.get!(as.issuer <> "/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb",
        redirect: false,
        retry: false
      )

    assert resp.status == 400
    assert McpDevAuthServer.authorizations(as) == 3

    # metadata + jwks
    md = Req.get!(as.issuer <> "/.well-known/oauth-authorization-server", retry: false).body
    assert md["issuer"] == as.issuer
    assert md["code_challenge_methods_supported"] == ["S256"]
    assert md["client_id_metadata_document_supported"] == true
    refute Map.has_key?(md, "registration_endpoint")

    assert [%{"kid" => "dev", "kty" => "RSA"}] =
             Req.get!(as.issuer <> "/jwks", retry: false).body["keys"]

    assert Req.get!(as.issuer <> "/nope", retry: false).status == 404

    McpDevAuthServer.close(as)
  end

  test "dev AS: authorize and token error paths, secrets, reject_refresh_resource, no_cimd" do
    {:ok, as} =
      McpDevAuthServer.start(
        clients: %{"app" => ["http://127.0.0.1/cb?x=1"], "conf" => ["http://127.0.0.1/cb"]},
        secrets: %{"conf" => "s3cret"},
        reject_refresh_resource: true,
        no_cimd: true,
        access_ttl: 5
      )

    authz = fn params ->
      Req.get!(as.issuer <> "/authorize?" <> URI.encode_query(params),
        redirect: false,
        retry: false
      )
    end

    loc = fn resp -> resp |> Req.Response.get_header("location") |> hd() end

    base = %{"client_id" => "app", "redirect_uri" => "http://127.0.0.1/cb?x=1", "state" => "z"}

    r = authz.(Map.put(base, "response_type", "code"))
    assert loc.(r) =~ "?x=1&error=invalid_request"

    r =
      authz.(
        Map.merge(base, %{
          "response_type" => "code",
          "code_challenge" => "c",
          "code_challenge_method" => "S256"
        })
      )

    assert loc.(r) =~ "error=invalid_target"

    # CIMD disabled ⇒ an URL client_id is unknown
    assert authz.(%{
             "client_id" => "https://x.example/c.json",
             "redirect_uri" => "http://127.0.0.1/cb"
           }).status ==
             400

    md = Req.get!(as.issuer <> "/.well-known/oauth-authorization-server", retry: false).body
    assert md["client_id_metadata_document_supported"] == false

    tok = fn form, opts ->
      Req.post!(as.issuer <> "/token", [form: form, retry: false] ++ opts)
    end

    assert tok.([grant_type: "password"], []).body == %{"error" => "unsupported_grant_type"}
    assert tok.([grant_type: "authorization_code", code: "nope"], []).status == 400
    assert tok.([grant_type: "refresh_token", refresh_token: "nope"], []).status == 400

    # confidential client: post secret wrong ⇒ 401; basic wrong ⇒ 401
    assert tok.([grant_type: "authorization_code", client_id: "conf", client_secret: "bad"], []).status ==
             401

    assert tok.([grant_type: "authorization_code"], auth: {:basic, "conf:bad"}).status == 401

    # full confidential flow via basic auth, then a refresh carrying resource ⇒ invalid_target
    verifier = String.duplicate("b", 50)
    challenge = Base.url_encode64(:crypto.hash(:sha256, verifier), padding: false)

    r =
      authz.(%{
        "client_id" => "conf",
        "redirect_uri" => "http://127.0.0.1:9/cb",
        "response_type" => "code",
        "code_challenge" => challenge,
        "code_challenge_method" => "S256",
        "resource" => "http://127.0.0.1/mcp"
      })

    code = URI.decode_query(URI.parse(loc.(r)).query)["code"]

    # wrong verifier ⇒ invalid_grant and the code is spent
    bad =
      tok.(
        [
          grant_type: "authorization_code",
          code: code,
          code_verifier: "wrong",
          redirect_uri: "http://127.0.0.1:9/cb",
          resource: "http://127.0.0.1/mcp"
        ],
        auth: {:basic, "conf:s3cret"}
      )

    assert bad.body == %{"error" => "invalid_grant"}

    r =
      authz.(%{
        "client_id" => "conf",
        "redirect_uri" => "http://127.0.0.1:9/cb",
        "response_type" => "code",
        "code_challenge" => challenge,
        "code_challenge_method" => "S256",
        "resource" => "http://127.0.0.1/mcp"
      })

    code = URI.decode_query(URI.parse(loc.(r)).query)["code"]

    ok =
      tok.(
        [
          grant_type: "authorization_code",
          code: code,
          code_verifier: verifier,
          redirect_uri: "http://127.0.0.1:9/cb",
          resource: "http://127.0.0.1/mcp"
        ],
        auth: {:basic, "conf:s3cret"}
      )

    assert ok.status == 200
    assert ok.body["expires_in"] == 5

    rr =
      tok.(
        [
          grant_type: "refresh_token",
          refresh_token: ok.body["refresh_token"],
          client_id: "conf",
          client_secret: "s3cret",
          resource: "http://127.0.0.1/mcp"
        ],
        []
      )

    assert rr.body == %{"error" => "invalid_target"}

    # refresh with the wrong client
    assert tok.([grant_type: "refresh_token", refresh_token: "x", client_id: "other"], []).status ==
             400

    McpDevAuthServer.close(as)
  end

  test "onCall fires on the 2026 wire; prompts; discover capabilities" do
    {:ok, events} = Agent.start_link(fn -> [] end)

    h =
      Toolnexus.mcp_handler(nil,
        list_tools: fn _ -> [who_tool()] end,
        protocol_versions: [@v],
        on_call: fn ev ->
          Agent.update(events, &(&1 ++ [ev]))
          raise "isolated"
        end,
        list_prompts: fn _ -> [%{name: "p", description: "d"}] end,
        get_prompt: fn _, name, _ -> %{messages: [%{role: "user", text: "hi " <> name}]} end
      )

    u = start_handler(h)
    {200, _, _} = post26(u, nil, "tools/call", %{"name" => "who", "arguments" => %{}})
    assert [%{name: "who", decision: :allow, session_id: nil}] = Agent.get(events, & &1)

    {200, _, d} = post26(u, nil, "server/discover")
    caps = d["result"]["capabilities"]
    assert caps["prompts"] == %{}
    refute Map.has_key?(caps, "extensions")
    refute Map.has_key?(caps, "resources")

    {200, _, g} = post26(u, nil, "prompts/get", %{"name" => "p"})

    assert g["result"]["messages"] == [
             %{"role" => "user", "content" => %{"type" => "text", "text" => "hi p"}}
           ]

    assert g["result"]["resultType"] == "complete"

    {200, _, l} = post26(u, nil, "prompts/list")
    assert [%{"name" => "p"}] = l["result"]["prompts"]

    # no resources hook ⇒ method not found
    {404, _, nf} = post26(u, nil, "resources/list")
    assert nf["error"]["code"] == -32601
    {404, _, _} = post26(u, nil, "skills/list")
    {404, _, _} = post26(u, nil, "resources/read", %{"uri" => "file:///x"})

    # GET/DELETE on a 2026-only handler ⇒ 405 Allow: POST
    {405, hd, _} = raw(:delete, u)
    assert hd["allow"] == "POST"

    # batch on a 2026-only handler
    {400, _, b} = post(u, "[]")
    assert Jason.decode!(b)["error"]["code"] == -32600

    # non-JSON content type ⇒ legacy 415
    {415, _, _} = post(u, "x", content_type: "text/plain")
    McpHandler.stop(h)
  end

  test "resources, server_info hook, url-mode suspension, hook errors" do
    tools = [
      %Tool{
        name: "login",
        description: "auth",
        input_schema: %{"type" => "object"},
        source: "native",
        execute: fn _, ctx ->
          case ctx.answer do
            %{ok: false, reason: r} ->
              ToolResult.ok("no: " <> r)

            _ ->
              %ToolResult{
                metadata: %{
                  pending: %Request{
                    id: "auth-1",
                    kind: "authorization",
                    prompt: "Sign in",
                    url: "https://example.test/login"
                  }
                }
              }
          end
        end
      },
      %Tool{
        name: "plain",
        description: "plain pending",
        input_schema: %{"type" => "object"},
        source: "native",
        execute: fn _, _ ->
          %ToolResult{metadata: %{pending: %Request{id: "q", kind: "input", prompt: "?"}}}
        end
      }
    ]

    h =
      Toolnexus.mcp_handler(nil,
        protocol_versions: [@v, "2025-06-18"],
        list_tools: fn _ -> tools end,
        server_info: fn _ -> %{name: "dyn", version: "9", instructions: ""} end,
        list_resources: fn _ -> [%{uri: "file:///a", name: "a"}] end,
        read_resource: fn
          _, "file:///a" -> [%{uri: "file:///a", text: "A"}]
          _, _ -> {:error, "missing"}
        end,
        list_skills: fn _ -> {:error, "skills down"} end
      )

    u = start_handler(h)

    {200, _, d} = post26(u, nil, "server/discover")

    assert d["result"]["_meta"]["io.modelcontextprotocol/serverInfo"] == %{
             "name" => "dyn",
             "version" => "9"
           }

    refute Map.has_key?(d["result"], "instructions")
    assert d["result"]["capabilities"]["resources"] == %{}

    {200, _, l} = post26(u, nil, "resources/list")
    assert l["result"]["resources"] == [%{"uri" => "file:///a", "name" => "a"}]
    {200, _, r} = post26(u, nil, "resources/read", %{"uri" => "file:///a"})
    assert r["result"]["contents"] == [%{"uri" => "file:///a", "text" => "A"}]
    {200, _, e} = post26(u, nil, "resources/read", %{"uri" => "file:///b"})
    assert e["error"]["code"] == -32002

    {200, _, e} = post26(u, nil, "skills/list")
    assert e["error"] == %{"code" => -32603, "message" => "skills down"}
    {200, _, e} = post26(u, nil, "skills/get", %{"uri" => "skill://x/SKILL.md"})
    assert e["error"]["code"] == -32603
    {200, _, e} = post26(u, nil, "skills/get", %{"uri" => "http://x"})
    assert e["error"]["message"] == "Invalid skill URI: not a skill:// URI"
    {200, _, e} = post26(u, nil, "resources/read", %{"uri" => "skill://x/%zz"})
    assert e["error"]["message"] == "Invalid resource URI: bad percent-encoding"
    {200, _, e} = post26(u, nil, "resources/read", %{"uri" => "skill://x/SKILL.md"})
    assert e["error"]["code"] == -32603

    {200, _, p} = post26(u, nil, "tools/call", %{"name" => "login", "arguments" => %{}})

    assert p["result"]["inputRequests"] == %{
             "auth-1" => %{
               "method" => "elicitation/create",
               "params" => %{
                 "mode" => "url",
                 "message" => "Sign in",
                 "url" => "https://example.test/login",
                 "elicitationId" => "auth-1"
               }
             }
           }

    st = p["result"]["requestState"]

    {200, _, c} =
      post26(u, nil, "tools/call", %{
        "name" => "login",
        "arguments" => %{},
        "requestState" => st,
        "inputResponses" => %{"auth-1" => %{"action" => "cancel"}}
      })

    assert text(c) == "no: cancelled"

    {200, _, p} = post26(u, nil, "tools/call", %{"name" => "plain"})

    assert p["result"]["inputRequests"]["q"]["params"]["requestedSchema"] ==
             %{"type" => "object", "properties" => %{}}

    # a garbage requestState is ignored
    {200, _, p} =
      post26(u, nil, "tools/call", %{
        "name" => "plain",
        "requestState" => "!!",
        "inputResponses" => %{}
      })

    assert p["result"]["resultType"] == "input_required"

    # mixed: a legacy MCP-Protocol-Version header ⇒ the legacy wire even with Mcp-Method
    {400, _, b} =
      post(u, rpc(1, "ping"),
        headers: [{"mcp-protocol-version", "2025-06-18"}, {"mcp-method", "ping"}]
      )

    assert b =~ "Mcp-Session-Id"

    # mixed: _meta protocolVersion alone routes to 2026 (header missing ⇒ -32020)
    {400, _, m} =
      post(u, rpc(2, "ping", %{"_meta" => %{"io.modelcontextprotocol/protocolVersion" => @v}}))

    assert Jason.decode!(m)["error"]["code"] == -32020

    # mixed: a parse error falls to the legacy wire
    {400, _, pe} = post(u, "{nope")
    assert Jason.decode!(pe)["error"]["message"] == "parse error"

    McpHandler.stop(h)
  end

  test "McpAuthError on the legacy wire and the www_authenticate hook override" do
    h =
      Toolnexus.mcp_handler(nil,
        stateless: true,
        authenticate: fn _ ->
          {:error, %McpAuthError{status: 403, challenge: "Bearer x", reason: "secret reason"}}
        end
      )

    u = start_handler(h)
    {403, hd, body} = post(u, rpc(1, "ping"))
    assert hd["www-authenticate"] == "Bearer x"
    assert body == ~s({"error":"unauthorized"})
    refute body =~ "secret"

    h2 =
      Toolnexus.mcp_handler(nil,
        protocol_versions: [@v],
        authenticate: fn _ -> {:error, %McpAuthError{status: nil, challenge: "Bearer y"}} end,
        www_authenticate: fn _, _ -> "Bearer hooked" end
      )

    u2 = start_handler(h2)
    {401, hd, _} = post26(u2, nil, "ping")
    assert hd["www-authenticate"] == "Bearer hooked"

    h3 =
      Toolnexus.mcp_handler(nil,
        protocol_versions: [@v],
        authenticate: fn _ -> {:error, "nope"} end
      )

    # a plain authenticate error keeps §7C.1's text 401 on the 2026 wire too
    {401, _, b} =
      post(
        start_handler(h3),
        rpc(1, "ping", %{"_meta" => %{"io.modelcontextprotocol/protocolVersion" => @v}}),
        headers: [{"mcp-protocol-version", @v}, {"mcp-method", "ping"}]
      )

    assert b =~ "Unauthorized"

    assert Exception.message(%McpAuthError{}) == "unauthorized"
    assert Exception.message(%McpAuthError{reason: "r"}) == "r"
    Enum.each([h, h2, h3], &McpHandler.stop/1)
  end

  test "McpServerAuth: verify hook, audience-derived metadata URL, base64 Mcp-Name scopes" do
    auth =
      McpServerAuth.new(
        issuer: "https://as.example",
        resource_path: "/api/mcp",
        scopes: %{"*" => [], "wipe" => ["admin"]},
        verify: fn _, tok, aud ->
          send(self(), {:aud, aud})
          if tok == "ok", do: {:ok, %McpPrincipal{subject: "s", scopes: []}}, else: raise("boom")
        end
      )

    conn =
      Plug.Test.conn(:post, "http://h.example:8080/api/mcp")
      |> Plug.Conn.put_req_header("authorization", "Bearer ok")
      |> Plug.Conn.put_req_header("mcp-method", "tools/call")
      |> Plug.Conn.put_req_header("mcp-name", "=?base64?" <> Base.encode64("wipe") <> "?=")

    assert {:error, %McpAuthError{status: 403, challenge: ch}} =
             McpServerAuth.authenticate(auth, conn)

    assert ch ==
             ~s(Bearer resource_metadata="http://h.example:8080/.well-known/oauth-protected-resource/api/mcp", scope="admin", error="insufficient_scope")

    assert_received {:aud, "http://h.example:8080/api/mcp"}

    bad = Plug.Conn.put_req_header(conn, "authorization", "Bearer raise")
    assert {:error, %McpAuthError{status: 401}} = McpServerAuth.authenticate(auth, bad)

    blank = Plug.Conn.put_req_header(conn, "authorization", "Bearer  ")
    assert {:error, %McpAuthError{challenge: c}} = McpServerAuth.authenticate(auth, blank)
    refute c =~ "error="

    # issuer metadata unreachable ⇒ invalid_token (default verifier)
    a2 = McpServerAuth.new(issuer: "http://127.0.0.1:1/x", audience: "https://r.example/mcp")
    tok = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImsifQ.e30.c2ln"

    c2 =
      Plug.Conn.put_req_header(Plug.Test.conn(:post, "/mcp"), "authorization", "Bearer " <> tok)

    assert {:error, %McpAuthError{status: 401, challenge: ch2}} =
             McpServerAuth.authenticate(a2, c2)

    assert ch2 =~ "https://r.example/.well-known/oauth-protected-resource/mcp"
    assert McpServerAuth.metadata(a2) == {McpServerAuth, a2}
  end

  test "load_mcp_skills on examples/skills" do
    {:ok, [s]} = Toolnexus.load_mcp_skills(@skills_dir)
    assert s.name == "hello-world"
    assert Enum.map(s.files, & &1.path) == ["SKILL.md", "scripts/greet.sh"]
    assert s.frontmatter["description"] =~ "tiny example skill"
    assert {:error, _} = Toolnexus.load_mcp_skills(Path.join(@skills_dir, "nope"))

    assert {:error, _} =
             Toolnexus.load_mcp_skills(Path.join(@skills_dir, "hello-world/SKILL.md"))
  end

  test "load_mcp_skills skips nameless skills and binary files; map skills are accepted" do
    dir = Path.join(System.tmp_dir!(), "tn-mcp-skills-#{System.unique_integer([:positive])}")
    File.mkdir_p!(Path.join(dir, "a"))
    File.mkdir_p!(Path.join(dir, "b"))
    File.write!(Path.join(dir, "a/SKILL.md"), "---\nname: a\n---\nbody")
    File.write!(Path.join(dir, "a/bin.dat"), <<255, 254, 0>>)
    File.write!(Path.join(dir, "b/SKILL.md"), "---\ndescription: no name\n---\n")

    {:ok, [s]} = Toolnexus.load_mcp_skills(dir)
    assert s.frontmatter == %{"name" => "a", "description" => ""}
    assert Enum.map(s.files, & &1.path) == ["SKILL.md"]
    File.rm_rf!(dir)

    h =
      Toolnexus.mcp_handler(nil,
        protocol_versions: [@v],
        authenticate: fn _ -> {:ok, "me"} end,
        list_skills: fn _ ->
          [%{"name" => "m", "files" => [%{"path" => "SKILL.md", "text" => "hi"}]}]
        end
      )

    u = start_handler(h)
    {200, _, l} = post26(u, nil, "skills/list")

    assert l["result"]["skills"] == [
             %{"uri" => "skill://m/SKILL.md", "frontmatter" => %{"name" => "m"}}
           ]

    assert l["result"]["cacheScope"] == "private"
    {200, _, r} = post26(u, nil, "resources/read", %{"uri" => "skill://m/SKILL.md"})
    assert [%{"mimeType" => "text/markdown", "text" => "hi"}] = r["result"]["contents"]
    {200, _, r} = post26(u, nil, "resources/read", %{"uri" => "skill://m/other.txt"})
    assert r["error"]["message"] == "Resource not found: skill://m/other.txt"
    McpHandler.stop(h)
  end
end
