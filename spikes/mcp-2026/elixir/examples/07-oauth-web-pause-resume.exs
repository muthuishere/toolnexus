# Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
#
# Run: mix run examples/07-oauth-web-pause-resume.exs   (hermetic: everything runs on 127.0.0.1)
#
# A server-side app must never block a request waiting for a login. With PauseAuthorizer the
# call returns {:error, %PendingError{pending: %Pending{id, kind: "authorization", prompt, url,
# expires_at}}}, the shape of toolnexus SPEC §10 Request. The app sends the browser to
# pending.url; the authorization server redirects back; the app resumes and gets the ORIGINAL
# call's result.
#
# OAuth.WebHandler packages that whole dance as one Plug. The manual version is shown too.
alias Mcp2026.{Client, DevAuthServer, HTTP, OAuth, PauseAuthorizer, Server, ServerAuth}

# ---- demo harness: dev authorization server + protected MCP server ----
as = DevAuthServer.start()  # DEV/TEST ONLY
DevAuthServer.put_client(as, "my-web-app", ["http://127.0.0.1/tool", "http://127.0.0.1/callback"])
mcp = Server.serve(%{Server.fixture() | auth: ServerAuth.new(issuer: as.issuer)})
# ---- end harness ----

# ---- the web app ----
{:ok, ln} = :gen_tcp.listen(0, ip: {127, 0, 0, 1})  # reserve a port for the app's own URL
{:ok, port} = :inet.port(ln)
:gen_tcp.close(ln)
app = "http://127.0.0.1:#{port}"

c = Client.new(mcp.url, auth: OAuth.new(client_id: "my-web-app", authorizer: %PauseAuthorizer{redirect: app <> "/tool"}))

# (a) One Plug: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
# with ?code&state, WebHandler resumes, and the tool's text is written to the response.
Bandit.start_link(ip: {127, 0, 0, 1}, port: port, startup_log: false,
  plug: {OAuth.WebHandler, oauth: c.auth, call: fn conn ->
    Client.call_tool(c, "echo", %{"text" => Plug.Conn.fetch_query_params(conn).query_params["text"]})
  end})
# ---- end web app ----

# A person's browser opens /tool?text=... (HTTP.get_follow follows redirects and trusts the dev TLS).
{:ok, resp} = HTTP.get_follow(app <> "/tool?text=resumed+after+consent")
IO.puts("(a) WebHandler: #{resp.status} #{resp.body}")

# (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
c2 = Client.new(mcp.url, auth: OAuth.new(client_id: "my-web-app", authorizer: %PauseAuthorizer{redirect: "http://127.0.0.1/callback"}))
p = Mcp2026.pending_of(Client.call_tool(c2, "echo", %{"text" => "resumed by hand"}))  # nil when the call did not pause
IO.puts("(b) paused: #{p.kind} - #{p.prompt != ""} - has URL: #{p.url != ""}")

# The user logs in; you receive the callback URL (here: the AS's redirect, not followed).
{:ok, %{status: 302} = r} = HTTP.request(:get, p.url, [], nil)
callback_url = HTTP.header(r, "location")
{:ok, res} = OAuth.resume(c2.auth, p.id, callback_url)
IO.puts("(b) resumed: " <> Mcp2026.text(res))
