defmodule Mcp2026.ExamplesTest do
  # Runs every program under examples/ end to end and checks its exact output, so the docs (which
  # copy code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only); 09 needs
  # KEYCLOAK=1 + keycloak/docker-compose.yml, 10 needs GITHUB_OAUTH=1 + sec-injected creds.
  use ExUnit.Case, async: true

  @hermetic %{
    "01-connect-http" => "supported versions: [2026-07-28]\ntools: echo, greet\necho: hello over HTTP",
    "02-connect-stdio" => "tool count: 2\necho: hello over stdio",
    "03-tool-input" => "server asks (elicitation/create): What is your name?\nresult: Hello, Ada!",
    "04-skills" => """
    skills extension: true
    skill skill://hello-world/SKILL.md: hello-world
    first heading: # Hello World Skill
    downloaded: hello-world/SKILL.md
    downloaded: hello-world/scripts/greet.sh\
    """,
    "05-tool-search" => """
    eager: 100 tools, 9721 bytes of schema
    lazy:  2 tools, 519 bytes of schema
    top hit: slack_create
    result: slack_create ran with hi team\
    """,
    "06-oauth-zero-config" => """
    echo: hello after login
    greet: Hello, Ada!
    browser logins: 2
    token stored for this issuer: true\
    """,
    "07-oauth-web-pause-resume" => """
    (a) WebHandler: 200 resumed after consent
    (b) paused: authorization - true - has URL: true
    (b) resumed: resumed by hand\
    """,
    "08-serve-protected" => """
    no token: 401 | WWW-Authenticate has resource_metadata: true
    metadata: issuer ok: true | scopes: [mcp:read]
    whoami: hello dev-user (mcp:read)
    delete_all after step-up: deleted
    whoami: hello dev-user (mcp:read mcp:admin)
    verify hook: hello billing-service
    wrong key rejected: true\
    """
  }

  @doc false
  def run(name, env \\ [], timeout \\ 90_000) do
    root = Path.expand("..", __DIR__)
    task = Task.async(fn ->
      System.cmd("mix", ["run", "--no-compile", "--no-deps-check", "examples/#{name}.exs"],
        cd: root, env: [{"MIX_ENV", "test"} | env], stderr_to_stdout: false)
    end)
    {out, status} = Task.await(task, timeout)
    {String.trim(out), status}
  end

  for {name, want} <- @hermetic do
    @tag timeout: 120_000
    test "example #{name}" do
      assert {unquote(want), 0} == run(unquote(name))
    end
  end

  @tag :keycloak
  @tag timeout: 120_000
  test "example 09-keycloak (KEYCLOAK=1)" do
    {out, 0} = run("09-keycloak", [{"KEYCLOAK", "1"}, {"HEADLESS_LOGIN", "1"}])
    assert out =~ "whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true"
    assert out =~ "greet: Hello, Alice!"
  end

  @tag :github
  @tag timeout: 200_000
  test "example 10-github (GITHUB_OAUTH=1, creds via sec run)" do
    assert {"tools listed: true\nget_me: complete | isError: false", 0} == run("10-github", [{"BROWSER", "chrome-agent"}], 180_000)
  end
end
