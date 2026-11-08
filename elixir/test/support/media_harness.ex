defmodule Toolnexus.MediaHarness do
  @moduledoc false
  # Shared harness for the SPEC §7G.11 media conformance replays: an in-process fake HTTP
  # server (script served in order, last entry repeats, every request recorded exactly as
  # sent), placeholder substitution, and JSON views of results / tool results.
  import ExUnit.Assertions

  alias Toolnexus.{Client, ContentPart, ToolResult}
  alias Toolnexus.Media.{MediaFile, Result}

  @root Path.expand("../../../conformance/media", __DIR__)

  def root, do: @root
  def load(name), do: (@root <> "/" <> name) |> File.read!() |> Jason.decode!()

  defmodule FakeServer do
    @moduledoc false
    @behaviour Plug
    import Plug.Conn

    def init(state), do: state

    def call(conn, state) do
      {:ok, body, conn} = read_all(conn, "")

      path =
        if conn.query_string == "",
          do: conn.request_path,
          else: conn.request_path <> "?" <> conn.query_string

      entry =
        Agent.get_and_update(state, fn %{script: script, requests: reqs} = s ->
          req = %{method: conn.method, path: path, body: body, headers: Map.new(conn.req_headers)}

          {entry, rest} =
            case script do
              [only] -> {only, [only]}
              [h | t] -> {h, t}
            end

          {entry, %{s | script: rest, requests: reqs ++ [req]}}
        end)

      headers = entry["headers"] || %{}
      has_ctype = Enum.any?(headers, fn {k, _} -> String.downcase(k) == "content-type" end)

      {raw, ctype} =
        cond do
          b64 = entry["bodyBase64"] -> {Base.decode64!(b64), "application/octet-stream"}
          raw = entry["rawBody"] -> {raw, "application/json"}
          true -> {Jason.encode!(entry["body"]), "application/json"}
        end

      conn = if has_ctype, do: conn, else: put_resp_header(conn, "content-type", ctype)

      conn =
        Enum.reduce(headers, conn, fn {k, v}, conn ->
          put_resp_header(conn, String.downcase(k), v)
        end)

      send_resp(conn, entry["status"], raw)
    end

    defp read_all(conn, acc) do
      case read_body(conn) do
        {:ok, b, conn} -> {:ok, acc <> b, conn}
        {:more, b, conn} -> read_all(conn, acc <> b)
      end
    end
  end

  @doc "Start a fake server for `script` (a function of the server URL, for substitution)."
  def serve(script_fun) do
    {:ok, state} = Agent.start_link(fn -> %{script: [], requests: []} end)

    {:ok, server} =
      Bandit.start_link(
        plug: {FakeServer, state},
        scheme: :http,
        port: 0,
        ip: {127, 0, 0, 1},
        startup_log: false
      )

    {:ok, {_ip, port}} = ThousandIsland.listener_info(server)
    url = "http://127.0.0.1:#{port}"
    Agent.update(state, &Map.put(&1, :script, script_fun.(url)))
    %{state: state, server: server, url: url}
  end

  def requests(srv), do: Agent.get(srv.state, & &1.requests)

  def stop(srv) do
    ThousandIsland.stop(srv.server, 500)
    Agent.stop(srv.state)
  end

  @doc "Replace every placeholder (`%{\"{server}\" => url, ...}`) in every string of a JSON term."
  def subst(term, subs) when is_binary(term),
    do: Enum.reduce(subs, term, fn {k, v}, acc -> String.replace(acc, k, v) end)

  def subst(term, subs) when is_list(term), do: Enum.map(term, &subst(&1, subs))
  def subst(term, subs) when is_map(term), do: Map.new(term, fn {k, v} -> {k, subst(v, subs)} end)
  def subst(term, _subs), do: term

  def tmp_dir(tag) do
    dir = Path.join(System.tmp_dir!(), "tn-#{tag}-#{System.unique_integer([:positive])}")
    # unique_integer restarts per VM: clear a leftover dir from an earlier run.
    File.rm_rf!(dir)
    File.mkdir_p!(dir)
    dir
  end

  @doc "Set `env`, delete `unset`; returns a restore function."
  def with_env(env, unset) do
    keys = Map.keys(env || %{}) ++ (unset || [])
    saved = Map.new(keys, &{&1, System.get_env(&1)})
    for {k, v} <- env || %{}, do: System.put_env(k, v)
    for k <- unset || [], do: System.delete_env(k)

    fn ->
      for {k, v} <- saved, do: if(v, do: System.put_env(k, v), else: System.delete_env(k))
    end
  end

  def progress_fun do
    me = self()
    fn p -> send(me, {:media_progress, p}) end
  end

  def collect_progress(acc \\ []) do
    receive do
      {:media_progress, p} -> collect_progress(acc ++ [p])
    after
      0 -> acc
    end
  end

  # ---- JSON views ----

  def file_json(%MediaFile{} = f) do
    %{"type" => f.type, "mimeType" => f.mime_type}
    |> put_if("url", f.url)
    |> put_if("path", f.path)
    |> put_if("data", f.data)
  end

  def part_json(%ContentPart{} = p) do
    %{"type" => p.type, "mimeType" => p.mime_type}
    |> put_if("url", p.url)
    |> put_if("data", p.data)
  end

  defp put_if(m, _k, nil), do: m
  defp put_if(m, k, v), do: Map.put(m, k, v)

  @doc "Assert a MediaResult against an expectation (`saved` files checked on disk)."
  def assert_result(%Result{} = r, exp, client, verb, dir) do
    assert r.status == exp["status"]
    assert r.job_id == exp["jobId"]
    assert r.text == exp["text"]
    # §7G.10b: style / model asserted only when the expected result names them.
    if Map.has_key?(exp, "style"), do: assert(r.style == exp["style"])
    if Map.has_key?(exp, "model"), do: assert(r.model == exp["model"])
    # D21: usage exact when present; noUsage ⇒ none; absent field ⇒ not asserted.
    cond do
      Map.has_key?(exp, "usage") -> assert r.usage == exp["usage"]
      exp["noUsage"] -> assert r.usage == nil
      true -> :ok
    end

    assert length(r.files) == length(exp["files"])

    for {f, e} <- Enum.zip(r.files, exp["files"]) do
      case e do
        %{"saved" => saved} ->
          assert f.type == e["type"] and f.mime_type == e["mimeType"]
          assert f.data == nil
          assert Path.dirname(f.path) == dir
          assert Path.type(f.path) == :absolute
          ext = Regex.escape(saved["ext"])

          assert Path.basename(f.path) =~
                   Regex.compile!("^#{client}-#{verb}-[0-9]+-[0-9]+#{ext}$")

          assert f.url == "file://" <> f.path
          assert File.read!(f.path) == Base.decode64!(saved["bytesBase64"])

        _ ->
          assert file_json(f) == e
      end
    end
  end

  @doc "Assert a tool result against an expectation (`dir` for outputPattern)."
  def assert_tool(%ToolResult{} = r, t, dir) do
    assert r.is_error == t["isError"], inspect(r)

    cond do
      Map.has_key?(t, "output") ->
        assert r.output == t["output"]

      p = t["outputPattern"] ->
        regex = String.replace(p, "{dir}", Regex.escape(dir || ""))
        assert r.output =~ Regex.compile!(regex), r.output

      p = t["outputPrefix"] ->
        assert String.starts_with?(r.output, p), r.output

      true ->
        :ok
    end

    if parts = t["parts"], do: assert(Enum.map(r.parts || [], &part_json/1) == parts)
    if t["noParts"], do: assert(r.parts == nil)

    if meta = t["metadata"],
      do: assert(Map.new(r.metadata, fn {k, v} -> {to_string(k), v} end) == meta)

    if mp = t["modelParts"],
      do: assert(Enum.map(Client.model_parts(r), &part_json/1) == mp)
  end

  @doc "Parse a multipart/form-data body into `[{name, value} | {name, filename, ctype, bytes}]`."
  def multipart(body, ctype) do
    [_, boundary] = Regex.run(~r/boundary=([^;]+)/, ctype)

    body
    |> String.split("--" <> boundary)
    |> Enum.drop(1)
    |> Enum.reject(&String.starts_with?(&1, "--"))
    |> Enum.map(fn chunk ->
      chunk = String.trim_leading(chunk, "\r\n")
      [head, content] = String.split(chunk, "\r\n\r\n", parts: 2)
      content = String.replace_suffix(content, "\r\n", "")
      [_, name] = Regex.run(~r/name="([^"]*)"/, head)

      case Regex.run(~r/filename="([^"]*)"/, head) do
        nil ->
          %{"name" => name, "value" => content}

        [_, fname] ->
          [_, ct] = Regex.run(~r/Content-Type: ([^\r\n]+)/i, head)

          %{
            "name" => name,
            "filename" => fname,
            "contentType" => ct,
            "bytesBase64" => Base.encode64(content)
          }
      end
    end)
  end

  @doc "Assert recorded requests against `expectRequests` (clients.json shape)."
  def assert_requests(reqs, exp) do
    assert length(reqs) == length(exp), inspect(Enum.map(reqs, &{&1.method, &1.path}))

    for {e, r} <- Enum.zip(exp, reqs) do
      assert r.method == e["method"]
      assert r.path == e["path"]
      if b = e["body"], do: assert(Jason.decode!(r.body) == b)

      if m = e["multipart"],
        do: assert(multipart(r.body, r.headers["content-type"]) == m)

      for {h, v} <- e["headers"] || %{} do
        assert r.headers[String.downcase(h)] == v, "header #{h} on #{r.path}"
      end

      for h <- e["absentHeaders"] || [] do
        refute Map.has_key?(r.headers, String.downcase(h)),
               "header #{h} must be absent on #{r.path}"
      end
    end
  end
end
