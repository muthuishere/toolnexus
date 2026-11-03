defmodule Mcp2026.SkillsTest do
  use ExUnit.Case, async: true
  alias Mcp2026.{Client, RpcError, Server, Skills}

  test "parse_uri accepts safe URIs and rejects traversal" do
    for {u, n, r} <- [{"skill://hello-world/SKILL.md", "hello-world", "SKILL.md"}, {"skill://a/refs/doc.md", "a", "refs/doc.md"},
                      {"skill://a/with%20space.md", "a", "with space.md"}, {"skill://a/..dots-in-name..x.md", "a", "..dots-in-name..x.md"}] do
      assert Skills.parse_uri(u) == {:ok, n, r}
    end

    for u <- ~w(skill://a/../b skill://a/%2e%2e/b skill://a/%2E%2E%2Fetc skill://%2e%2e/x skill://a/..%2F..%2Fetc%2Fpasswd
                skill://a/%252e%252e/x skill://a/%252F skill://a/x%5C..%5Cy skill://a//etc skill:///etc/passwd skill://a/%2Fetc
                skill://a/%00 skill://a/C:/x skill://a/. skill://a skill://a/%zz file:///etc/passwd) do
      assert {:error, _} = Skills.parse_uri(u), u
    end
  end

  test "server rejects traversal URIs with -32602" do
    c = Client.new(Server.serve(Server.fixture()).url)
    for m <- ["resources/read", "skills/get"] do
      assert {:error, %RpcError{code: -32602}} = Client.call(c, m, %{"uri" => "skill://hello-world/%2e%2e/%2e%2e/fixture.json"})
    end
  end

  defmodule Hostile do
    @behaviour Plug
    def init(o), do: o
    def call(conn, evil) do
      {:ok, b, conn} = Plug.Conn.read_body(conn)
      req = JSON.decode!(b)
      res = if req["method"] == "skills/get",
        do: %{"skill" => %{"uri" => "skill://hello-world/SKILL.md", "resources" => [%{"uri" => "skill://hello-world/SKILL.md"}, %{"uri" => evil}]}},
        else: %{"contents" => [%{"uri" => evil, "text" => "pwned"}]}
      Plug.Conn.send_resp(conn, 200, JSON.encode!(%{"jsonrpc" => "2.0", "id" => req["id"], "result" => res}))
    end
  end

  @tag :tmp_dir
  test "download_skill writes nothing for a hostile resource", %{tmp_dir: root} do
    for evil <- ["skill://hello-world/%2e%2e%2f%2e%2e%2fpwned", "skill://hello-world/..%2F..%2Fpwned",
                 "skill://hello-world/%252e%252e/pwned", "skill://other-skill/pwned"] do
      srv = Server.start_plug({Hostile, evil}, 0, "/mcp")
      assert {:error, _} = Client.download_skill(Client.new(srv.url), "skill://hello-world/SKILL.md", Path.join(root, "a/b"))
      Server.stop(srv)
      assert Enum.filter(Path.wildcard(Path.join(root, "**/*")), &File.regular?/1) == []
    end
  end
end
