defmodule Toolnexus.HttpTest do
  use ExUnit.Case, async: true

  import Plug.Conn

  alias Toolnexus.{Http, ToolResult}

  # A plug fun that reports the request to the test process and sends a canned response.
  defp reporting_plug(test_pid, status \\ 200, body \\ "ok") do
    fn conn ->
      {:ok, req_body, conn} = read_body(conn)

      send(test_pid, {:request, %{
        method: conn.method,
        path: conn.request_path,
        query: conn.query_string,
        headers: conn.req_headers,
        body: req_body
      }})

      send_resp(conn, status, body)
    end
  end

  test "GET: {placeholder}s are URL-encoded + consumed; remaining args become the querystring" do
    tool =
      Http.tool(
        name: "get_posts",
        description: "List posts",
        method: "get",
        url: "https://api.example.test/users/{id}/posts",
        req_options: [plug: reporting_plug(self())]
      )

    assert tool.source == "http"
    result = tool.execute.(%{"id" => "a b/c", "limit" => 5, "sort" => "new"}, nil)

    assert %ToolResult{output: "ok", is_error: false, metadata: %{status: 200}} = result
    assert_received {:request, req}
    assert req.method == "GET"
    assert req.path == "/users/a%20b%2Fc/posts"
    assert URI.decode_query(req.query) == %{"limit" => "5", "sort" => "new"}
    assert req.body == ""
  end

  test "POST json: remaining args are the JSON body with Content-Type application/json" do
    tool =
      Http.tool(
        name: "create",
        description: "Create",
        method: "POST",
        url: "https://api.example.test/items",
        req_options: [plug: reporting_plug(self(), 201, "created")]
      )

    result = tool.execute.(%{"name" => "x", "count" => 2}, nil)
    assert %ToolResult{output: "created", is_error: false, metadata: %{status: 201}} = result

    assert_received {:request, req}
    assert req.method == "POST"
    assert Jason.decode!(req.body) == %{"name" => "x", "count" => 2}
    assert {"content-type", "application/json"} in Enum.map(req.headers, fn {k, v} -> {String.downcase(k), v} end)
  end

  test "POST with query list: named args go to the querystring, the rest to the body" do
    tool =
      Http.tool(
        name: "create",
        description: "Create",
        method: "POST",
        url: "https://api.example.test/items",
        query: ["dry_run"],
        req_options: [plug: reporting_plug(self())]
      )

    tool.execute.(%{"dry_run" => true, "name" => "x"}, nil)
    assert_received {:request, req}
    assert URI.decode_query(req.query) == %{"dry_run" => "true"}
    assert Jason.decode!(req.body) == %{"name" => "x"}
  end

  test "form body mode encodes remaining args as x-www-form-urlencoded" do
    tool =
      Http.tool(
        name: "login",
        description: "Login",
        method: "POST",
        url: "https://api.example.test/login",
        body: "form",
        req_options: [plug: reporting_plug(self())]
      )

    tool.execute.(%{"user" => "a b", "pass" => "c"}, nil)
    assert_received {:request, req}
    assert URI.decode_query(req.body) == %{"user" => "a b", "pass" => "c"}
    assert {"content-type", "application/x-www-form-urlencoded"} in Enum.map(req.headers, fn {k, v} -> {String.downcase(k), v} end)
  end

  test "raw body mode sends the body arg verbatim" do
    tool =
      Http.tool(
        name: "raw",
        description: "Raw",
        method: "POST",
        url: "https://api.example.test/raw",
        body: "raw",
        req_options: [plug: reporting_plug(self())]
      )

    tool.execute.(%{"body" => "plain text payload"}, nil)
    assert_received {:request, req}
    assert req.body == "plain text payload"
  end

  test "headers expand ${ENV_VAR} at call time; the value never appears in tool output" do
    System.put_env("TOOLNEXUS_HTTP_TEST_TOKEN", "fake-tok-12345")
    on_exit(fn -> System.delete_env("TOOLNEXUS_HTTP_TEST_TOKEN") end)

    tool =
      Http.tool(
        name: "auth",
        description: "Auth",
        method: "GET",
        url: "https://api.example.test/secure",
        headers: %{"Authorization" => "Bearer ${TOOLNEXUS_HTTP_TEST_TOKEN}", "X-Static" => "v1"},
        req_options: [plug: reporting_plug(self(), 500, "server exploded")]
      )

    result = tool.execute.(%{}, nil)

    assert_received {:request, req}
    headers = Map.new(req.headers, fn {k, v} -> {String.downcase(k), v} end)
    assert headers["authorization"] == "Bearer fake-tok-12345"
    assert headers["x-static"] == "v1"

    # non-2xx error path: "HTTP <status>: <body>" and no secret leakage
    assert result.is_error
    assert result.output == "HTTP 500: server exploded"
    refute result.output =~ "fake-tok-12345"
    assert result.metadata == %{status: 500}
  end

  test "missing env var expands to empty string" do
    System.delete_env("TOOLNEXUS_HTTP_TEST_MISSING")

    tool =
      Http.tool(
        name: "auth",
        description: "Auth",
        method: "GET",
        url: "https://api.example.test/x",
        headers: %{"Authorization" => "Bearer ${TOOLNEXUS_HTTP_TEST_MISSING}"},
        req_options: [plug: reporting_plug(self())]
      )

    tool.execute.(%{}, nil)
    assert_received {:request, req}
    headers = Map.new(req.headers, fn {k, v} -> {String.downcase(k), v} end)
    assert headers["authorization"] == "Bearer "
  end

  test "non-2xx returns is_error with HTTP <status>: <body>" do
    tool =
      Http.tool(
        name: "nf",
        description: "Not found",
        method: "GET",
        url: "https://api.example.test/missing",
        req_options: [plug: fn conn -> send_resp(conn, 404, "not found") end]
      )

    result = tool.execute.(%{}, nil)
    assert result == %ToolResult{output: "HTTP 404: not found", is_error: true, metadata: %{status: 404}}
  end

  test "result_mode status+text prefixes the status line" do
    tool =
      Http.tool(
        name: "st",
        description: "d",
        method: "GET",
        url: "https://api.example.test/x",
        result_mode: "status+text",
        req_options: [plug: fn conn -> send_resp(conn, 200, "body here") end]
      )

    assert tool.execute.(%{}, nil).output == "200\nbody here"
  end

  test "result_mode json re-encodes parseable bodies and passes through the rest" do
    plug = fn conn -> send_resp(conn, 200, ~s( {"a": 1} )) end

    tool =
      Http.tool(
        name: "js",
        description: "d",
        method: "GET",
        url: "https://api.example.test/x",
        result_mode: "json",
        req_options: [plug: plug]
      )

    assert tool.execute.(%{}, nil).output == ~s({"a":1})

    tool2 =
      Http.tool(
        name: "js2",
        description: "d",
        method: "GET",
        url: "https://api.example.test/x",
        result_mode: "json",
        req_options: [plug: fn conn -> send_resp(conn, 200, "not json") end]
      )

    assert tool2.execute.(%{}, nil).output == ~s("not json")
  end

  test "url with an existing querystring appends with &" do
    tool =
      Http.tool(
        name: "q",
        description: "d",
        method: "GET",
        url: "https://api.example.test/x?fixed=1",
        req_options: [plug: reporting_plug(self())]
      )

    tool.execute.(%{"extra" => "2"}, nil)
    assert_received {:request, req}
    assert URI.decode_query(req.query) == %{"fixed" => "1", "extra" => "2"}
  end

  test "default input schema is the empty object schema" do
    tool = Http.tool(name: "t", description: "d", method: "GET", url: "https://x.test/")

    assert tool.input_schema == %{
             "type" => "object",
             "properties" => %{},
             "additionalProperties" => false
           }
  end
end
