# Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
#
# Run: mix run examples/01-connect-http.exs
#
# The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
# Every request carries its own protocol version, so "connecting" is just Client.new(url).
alias Mcp2026.{Client, Server}

# A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
# In real use this is someone else's server, e.g. "https://api.example.com/mcp".
srv = Server.serve(Server.fixture())

# 1. Point a client at the server's MCP endpoint. No handshake happens here.
c = Client.new(srv.url)

# 2. Optional: server/discover tells you which versions and capabilities the server has.
{:ok, info} = Client.call(c, "server/discover")
IO.puts("supported versions: [#{Enum.join(info["supportedVersions"], " ")}]")

# 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
{:ok, tools} = Client.tools(c)
IO.puts("tools: " <> Enum.map_join(tools, ", ", & &1["name"]))

# 4. Call a tool. Mcp2026.text/1 returns the first text content of the result.
{:ok, res} = Client.call_tool(c, "echo", %{"text" => "hello over HTTP"})
IO.puts("echo: " <> Mcp2026.text(res))
