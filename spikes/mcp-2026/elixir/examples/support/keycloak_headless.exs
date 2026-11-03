defmodule KeycloakHeadless do
  @moduledoc """
  TEST HARNESS, not something an app needs: it plays the person at the browser. It follows the
  authorization URL, submits Keycloak's login form (and a consent page if shown), stops at the
  127.0.0.1 redirect and returns it (`login/3`) or delivers it to the loopback listener (`opener/2`).
  """
  alias Mcp2026.HTTP

  def opener(user, password) do
    fn url ->
      with {:ok, cb} <- login(url, user, password), {:ok, _} <- HTTP.request(:get, cb, [], nil), do: :ok
    end
  end

  # A tiny cookie jar (Keycloak marks its cookies Secure even on http://localhost, which :httpc's
  # own jar then refuses to send). `jar` is an Agent holding name => value.
  def login(auth_url, user, password) do
    {:ok, jar} = Agent.start_link(fn -> %{} end)

    try do
      with {:ok, resp, url} <- follow(auth_url, jar, 10), do: submit(resp, url, user, password, jar, 0)
    after
      Agent.stop(jar)
    end
  end

  defp req(method, url, hs, body, jar) do
    cookie = Agent.get(jar, fn m -> Enum.map_join(m, "; ", fn {k, v} -> k <> "=" <> v end) end)
    hs = if cookie != "", do: [{"cookie", cookie} | hs], else: hs

    with {:ok, r} <- HTTP.request(method, url, hs, body) do
      for {"set-cookie", c} <- r.headers, [kv | _] = String.split(c, ";"), [k, v] <- [String.split(kv, "=", parts: 2)],
          do: Agent.update(jar, &Map.put(&1, String.trim(k), v))

      {:ok, r}
    end
  end

  defp follow(url, jar, n) do
    with {:ok, r} <- req(:get, url, [], nil, jar) do
      loc = HTTP.header(r, "location")

      cond do
        r.status in 301..308 and String.starts_with?(loc, "http://127.0.0.1") -> {:ok, r, url}
        r.status in 301..308 and n > 0 -> follow(URI.merge(url, loc) |> to_string(), jar, n - 1)
        true -> {:ok, r, url}
      end
    end
  end

  defp submit(%{status: s} = r, _url, _u, _p, _jar, _) when s in 301..308, do: {:ok, HTTP.header(r, "location")}

  defp submit(%{status: 200, body: body}, url, user, password, jar, page) when page < 3 do
    {action, form} =
      cond do
        m = Regex.run(~r/<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/, body) ->
          {Enum.at(m, 1), %{"username" => user, "password" => password, "credentialId" => ""}}

        (m = Regex.run(~r/<form[^>]*action="([^"]+)"/, body)) && String.contains?(body, ~s(name="accept")) ->
          {Enum.at(m, 1), %{"accept" => "Yes"}}

        true ->
          {nil, nil}
      end

    if action do
      target = URI.merge(url, String.replace(action, "&amp;", "&")) |> to_string()
      hs = [{"content-type", "application/x-www-form-urlencoded"}]

      with {:ok, r} <- req(:post, target, hs, URI.encode_query(form), jar),
           do: submit(r, target, user, password, jar, page + 1)
    else
      {:error, "login page without a form"}
    end
  end

  defp submit(r, _, _, _, _, _), do: {:error, "login did not redirect back (HTTP #{r.status})"}
end
