# Elixir CLIENT runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md).
# Mirrors golang/internal/interop/mcp2026client/main.go scenario for scenario, public API only.
# Prints PASS <id> / FAIL <id>: <reason>; exit 0 iff all pass. Test tooling, not shipped.
#
#   MIX_ENV=test mix run --no-compile client.exs <base> <skillsDir>
#   MIX_ENV=test mix run --no-compile client.exs --keycloak <mcpURL> <skillsDir>
Logger.configure(level: :error)

defmodule Interop do
  @moduledoc false
  alias Toolnexus.{Answer, Mcp, Toolkit, ToolResult}

  def scenario(id, fun) do
    result =
      try do
        fun.()
      rescue
        e -> {:error, "raise " <> Exception.message(e)}
      catch
        k, v -> {:error, "#{k} #{inspect(v)}"}
      end

    case result do
      :ok ->
        IO.puts("PASS #{id}")

      {:error, why} ->
        Process.put(:failed, true)
        IO.puts("FAIL #{id}: #{String.replace(to_string(why), "\n", " ")}")
    end
  end

  def answers(%{prompt: "What is your name?", id: id}),
    do: %Answer{id: id, ok: true, data: %{"name" => "toolnexus"}}

  def answers(%{prompt: "Really wipe?", id: id}),
    do: %Answer{id: id, ok: true, data: %{"confirm" => true}}

  def answers(%{id: id}), do: %Answer{id: id, ok: false}

  def load(cfg, opts) do
    src = Mcp.load(%{"srv" => cfg}, opts)

    if src.status["srv"] == "connected" do
      {:ok, src}
    else
      Mcp.close(src)
      {:error, "not connected: #{inspect(src.status)}"}
    end
  end

  def run(src, name, args) do
    case Enum.find(src.tools, &(&1.name == name)) do
      nil -> {:error, "no tool #{name}"}
      t -> {:ok, t.execute.(args, %Toolnexus.Context{})}
    end
  end

  def expect(src, name, args, want) do
    with {:ok, r} <- run(src, name, args) do
      if r.is_error or r.output != want,
        do: {:error, "#{name}: got #{inspect(r.output)} (isError=#{r.is_error}), want #{inspect(want)}"},
        else: :ok
    end
  end

  def get(url) do
    case Req.get(url, retry: false, receive_timeout: 15_000) do
      {:ok, _} -> :ok
      {:error, e} -> {:error, Exception.message(e)}
    end
  end

  def with_src(cfg, opts, fun) do
    with {:ok, src} <- load(cfg, opts) do
      try do
        fun.(src)
      after
        Mcp.close(src)
      end
    end
  end

  def scopes_include?(nil, _), do: false
  def scopes_include?(tok, s), do: s in (tok.scopes || [])

  # hello-world's SKILL.md without its frontmatter, trimmed.
  def skill_body(dir) do
    s = File.read!(Path.join([dir, "hello-world", "SKILL.md"])) |> String.replace("\r\n", "\n")

    s =
      with "---\n" <> rest <- s, [_, body] <- :binary.split(rest, "\n---") do
        body
      else
        _ -> s
      end

    String.trim(s)
  end

  def hermetic(base, skills_dir) do
    open = %{"url" => base <> "/open/mcp"}
    wf = [wait_for: &answers/1]

    scenario("discover", fn ->
      with_src(open, wf, fn src ->
        names = Enum.map_join(src.tools, ",", & &1.name)

        cond do
          names != "srv_echo,srv_greet,srv_whoami,srv_wipe" ->
            {:error, "tools #{names}"}

          (r = expect(src, "srv_echo", %{"text" => "hi"}, ~s({"text":"hi"}))) != :ok ->
            r

          true ->
            seen = Jason.decode!(Req.get!(base <> "/log", retry: false, decode_body: false).body)["seen"] || []

            case Enum.find(seen, &(String.starts_with?(&1, "open ") and not String.starts_with?(&1, "open 2026-07-28 "))) do
              nil -> :ok
              s -> {:error, "non-2026 request on the 2026 mount: #{inspect(s)}"}
            end
        end
      end)
    end)

    scenario("mrtr_input", fn ->
      with_src(open, wf, &expect(&1, "srv_greet", %{}, "Hello, toolnexus!"))
    end)

    scenario("mrtr_confirm", fn ->
      with_src(open, wf, &expect(&1, "srv_wipe", %{}, "wiped"))
    end)

    scenario("mrtr_pending", fn ->
      with_src(open, [], fn src ->
        with {:ok, r} <- run(src, "srv_greet", %{}) do
          case r do
            %ToolResult{metadata: %{pending: %{kind: "input", prompt: "What is your name?"}}} -> :ok
            _ -> {:error, "not pending input: #{inspect(r)}"}
          end
        end
      end)
    end)

    scenario("skills", fn ->
      {:ok, tk} = Toolnexus.create_toolkit(mcp_config: %{"mcpServers" => %{"srv" => open}})

      try do
        case Toolkit.get(tk, "skill") do
          nil ->
            {:error, "no skill tool"}

          skill ->
            r = skill.execute.(%{"name" => "hello-world"}, %Toolnexus.Context{})
            body = skill_body(skills_dir)

            if r.is_error or not String.contains?(r.output, body),
              do: {:error, "remote skill output lacks the SKILL.md body: #{String.slice(r.output, 0, 200)}"},
              else: :ok
        end
      after
        Toolkit.close(tk)
      end
    end)

    scenario("tool_search", fn ->
      cfg = %{"url" => base <> "/open/mcp", "toolSearch" => true, "description" => "interop"}

      with_src(cfg, wf, fn src ->
        case src.tools do
          [search, call] ->
            r = search.execute.(%{"query" => "echo text"}, %Toolnexus.Context{})

            cond do
              not String.starts_with?(r.output, ~s([{"name":"echo")) ->
                {:error, "search output #{String.slice(r.output, 0, 200)}"}

              (r2 = call.execute.(%{"name" => "greet"}, %Toolnexus.Context{})).output !=
                  "Hello, toolnexus!" ->
                {:error, "call_tool greet #{inspect(r2.output)}"}

              true ->
                :ok
            end

          ts ->
            {:error, "tool search exposes #{length(ts)} tools, want 2"}
        end
      end)
    end)

    auth_cfg = %{
      "url" => base <> "/mcp",
      "auth" => %{"clientId" => "interop-client", "allowHttp" => true}
    }

    store = Mcp.MemoryTokenStore.new()

    scenario("oauth_preregistered", fn ->
      with {:ok, src} <-
             load(auth_cfg, open_url: &get/1, token_store: store, wait_for: &answers/1) do
        Process.put(:authed, src)
        expect(src, "srv_echo", %{"text" => "authed"}, ~s({"text":"authed"}))
      end
    end)

    scenario("oauth_stepup", fn ->
      case Process.get(:authed) do
        nil ->
          {:error, "needs oauth_preregistered"}

        src ->
          try do
            with :ok <- expect(src, "srv_whoami", %{}, "dev-user") do
              tok = Mcp.MemoryTokenStore.load(store, base <> "/mcp")

              if scopes_include?(tok, "mcp:whoami"),
                do: :ok,
                else: {:error, "stored token scopes lack mcp:whoami: #{inspect(tok && tok.scopes)}"}
            end
          after
            Mcp.close(src)
          end
      end
    end)

    scenario("oauth_cimd", fn ->
      {:ok, agent} = Agent.start_link(fn -> nil end)

      {:ok, hs} =
        Bandit.start_link(
          plug: {Interop.Cimd, agent},
          ip: {127, 0, 0, 1},
          port: 0,
          startup_log: false,
          thousand_island_options: [shutdown_timeout: 100]
        )

      {:ok, {_, p}} = ThousandIsland.listener_info(hs)
      doc_url = "http://127.0.0.1:#{p}/client.json"
      Agent.update(agent, fn _ -> doc_url end)

      {:ok, bad} = Agent.start_link(fn -> nil end)

      follow = fn u ->
        unless String.contains?(u, "client_id=" <> URI.encode_www_form(doc_url)),
          do: Agent.update(bad, fn _ -> "authorize URL lacks the CIMD client_id: #{u}" end)

        get(u)
      end

      try do
        cfg = %{
          "url" => base <> "/mcp",
          "auth" => %{"clientMetadataUrl" => doc_url, "allowHttp" => true}
        }

        with :ok <- with_src(cfg, [open_url: follow], &expect(&1, "srv_echo", %{"text" => "cimd"}, ~s({"text":"cimd"}))) do
          case Agent.get(bad, & &1) do
            nil -> :ok
            bad -> {:error, bad}
          end
        end
      after
        Supervisor.stop(hs)
      end
    end)

    scenario("oauth_no_identity", fn ->
      src =
        Mcp.load(%{"srv" => %{"url" => base <> "/mcp", "auth" => %{"allowHttp" => true}}},
          open_url: fn _ -> :ok end
        )

      try do
        if src.status["srv"] == "failed",
          do: :ok,
          else: {:error, "status #{inspect(src.status)}, want failed"}
      after
        Mcp.close(src)
      end
    end)

    scenario("legacy_fallback", fn ->
      with_src(%{"url" => base <> "/legacy/mcp"}, [], &expect(&1, "srv_echo", %{"text" => "old"}, ~s({"text":"old"})))
    end)
  end

  # ---- live Keycloak (interop/mcp2026/keycloak) ----

  @kc_user "alice"
  # throwaway user of the local-only realm-toolnexus.json
  @kc_password "alice-test-pw"

  # Headless browser: follow the authorize URL (cookie jar), submit Keycloak's login (and
  # consent) form, then deliver the loopback callback.
  def kc_opener do
    {:ok, jar} = Agent.start_link(fn -> %{} end)
    fn auth_url -> kc_flow(jar, auth_url) end
  end

  defp kc_req(jar, method, url, form \\ nil) do
    cookie = Agent.get(jar, fn m -> Enum.map_join(m, "; ", fn {k, v} -> "#{k}=#{v}" end) end)

    opts = [
      method: method,
      url: url,
      redirect: false,
      retry: false,
      receive_timeout: 15_000,
      decode_body: false,
      headers: if(cookie == "", do: [], else: [{"cookie", cookie}])
    ]

    opts = if form, do: Keyword.put(opts, :form, form), else: opts
    resp = Req.request!(opts)

    for sc <- Req.Response.get_header(resp, "set-cookie") do
      [nv | _] = String.split(sc, ";")

      case String.split(nv, "=", parts: 2) do
        [k, v] -> Agent.update(jar, &Map.put(&1, String.trim(k), v))
        _ -> :ok
      end
    end

    resp
  end

  # Follow redirects within Keycloak; stop at a 127.0.0.1 Location (the callback).
  defp kc_follow(jar, resp, url) do
    loc = List.first(Req.Response.get_header(resp, "location"))

    if resp.status in [301, 302, 303, 307, 308] and loc != nil do
      next = URI.merge(url, loc) |> to_string()

      if URI.parse(next).host == "127.0.0.1",
        do: {resp, url},
        else: kc_follow(jar, kc_req(jar, :get, next), next)
    else
      {resp, url}
    end
  end

  defp kc_flow(jar, auth_url) do
    {resp, url} = kc_follow(jar, kc_req(jar, :get, auth_url), auth_url)
    {resp, url} = kc_forms(jar, resp, url, 0)

    if resp.status != 302 do
      {:error, "keycloak login: HTTP #{resp.status}"}
    else
      get(URI.merge(url, List.first(Req.Response.get_header(resp, "location"))) |> to_string())
    end
  end

  defp kc_forms(jar, %{status: 200} = resp, url, page) when page < 3 do
    body = resp.body

    {action, form} = kc_pick_form(body)

    if action do
      target = URI.merge(url, html_unescape(action)) |> to_string()
      {resp, url} = kc_follow(jar, kc_req(jar, :post, target, form), target)
      kc_forms(jar, resp, url, page + 1)
    else
      {resp, url}
    end
  end

  defp kc_forms(_jar, resp, url, _), do: {resp, url}

  defp kc_pick_form(body) do
    login = Regex.run(~r/<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/, body)
    any = Regex.run(~r/<form[^>]*action="([^"]+)"/, body)

    cond do
      login -> {Enum.at(login, 1), [username: @kc_user, password: @kc_password, credentialId: ""]}
      any && String.contains?(body, ~s(name="accept")) -> {Enum.at(any, 1), [accept: "Yes"]}
      true -> {nil, nil}
    end
  end

  defp html_unescape(s),
    do:
      s
      |> String.replace("&amp;", "&")
      |> String.replace("&#x3D;", "=")
      |> String.replace("&#61;", "=")
      |> String.replace("&quot;", "\"")
      |> String.replace("&lt;", "<")
      |> String.replace("&gt;", ">")

  def keycloak(mcp_url) do
    store = Mcp.MemoryTokenStore.new()

    scenario("kc_login", fn ->
      cfg = %{"url" => mcp_url, "auth" => %{"clientId" => "toolnexus-mcp", "allowHttp" => true}}

      with {:ok, src} <- load(cfg, open_url: kc_opener(), token_store: store, wait_for: &answers/1) do
        Process.put(:kc_src, src)
        expect(src, "srv_echo", %{"text" => "keycloak"}, ~s({"text":"keycloak"}))
      end
    end)

    scenario("kc_stepup", fn ->
      case Process.get(:kc_src) do
        nil ->
          {:error, "needs kc_login"}

        src ->
          try do
            with {:ok, r} <- run(src, "srv_whoami", %{}) do
              tok = Mcp.MemoryTokenStore.load(store, mcp_url)

              cond do
                r.is_error or r.output == "" -> {:error, "whoami #{inspect(r)}"}
                not scopes_include?(tok, "mcp:greet") -> {:error, "stored token scopes lack mcp:greet"}
                true -> :ok
              end
            end
          after
            Mcp.close(src)
          end
      end
    end)
  end
end

defmodule Interop.Cimd do
  @moduledoc false
  @behaviour Plug
  def init(o), do: o

  def call(conn, agent) do
    url = Agent.get(agent, & &1)

    doc = %{
      "client_id" => url,
      "client_name" => "interop CIMD",
      "redirect_uris" => ["http://127.0.0.1/callback"]
    }

    conn
    |> Plug.Conn.put_resp_header("content-type", "application/json")
    |> Plug.Conn.send_resp(200, Jason.encode!(doc))
  end
end

case System.argv() do
  ["--keycloak", url, _skills] ->
    Interop.keycloak(url)

  [base, skills] ->
    Interop.hermetic(base, skills)

  _ ->
    IO.puts(:stderr, "usage: client.exs <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>")
    System.halt(2)
end

System.halt(if Process.get(:failed), do: 1, else: 0)
