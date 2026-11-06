# Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
#
# GitHub supports neither DCR nor CIMD, so you register an OAuth App once
# (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
# enable "expiring user tokens" if you want refresh) and pass its client id + secret.
#
#     sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- mix run examples/10-github.exs
#
# Credentials are read from the environment and never printed; neither is any token.
# BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
# otherwise the system browser opens.
alias Mcp2026.{Client, LoopbackAuthorizer, OAuth}

{id, secret} = {System.get_env("GITHUB_MCP_CLIENT_ID"), System.get_env("GITHUB_MCP_CLIENT_SECRET")}

if id in [nil, ""] or secret in [nil, ""] do
  IO.puts("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...")
  System.halt(0)
end

login =
  if System.get_env("BROWSER") == "chrome-agent",
    do: %LoopbackAuthorizer{open: fn u -> {_, 0} = System.cmd("chrome-agent", ["goto", u]); :ok end},
    else: %LoopbackAuthorizer{}  # system browser + http://127.0.0.1:<random>/callback

c = Client.new("https://api.githubcopilot.com/mcp/", auth: OAuth.new(
  client_id: id,
  client_secret: secret,  # GitHub OAuth Apps are confidential clients (client_secret_basic)
  authorizer: login
))

{:ok, tools} = Client.tools(c)
IO.puts("tools listed: #{tools != []}")

{:ok, res} = Client.call_tool(c, "get_me", %{})  # read-only: who am I
IO.puts("get_me: #{res["resultType"]} | isError: #{res["isError"] == true}")  # content withheld on purpose
