# Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
#
# Run: mix run examples/02-connect-stdio.exs
#
# stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed
# through and never parsed. There are no HTTP headers and no OAuth over stdio: a stdio server
# takes its credentials from its own environment.
alias Mcp2026.{Client, Server, Stdio}

# Demo harness: this same script is the stdio server when started with SERVE_STDIO=1.
# In real use the command is your server, e.g. Stdio.start("npx", ["-y", "some-mcp-server"]).
if System.get_env("SERVE_STDIO") == "1" do
  Stdio.serve(Server.fixture())
  System.halt(0)
end

# 1. Describe the process to spawn: command, args, env.
args = ["run", "--no-compile", "--no-deps-check", __ENV__.file]

# 2. Start it and get a client. Same API as the HTTP client: call, tools, call_tool.
{:ok, c} = Stdio.start("mix", args, env: [{"SERVE_STDIO", "1"}])

# 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
{:ok, tools} = Client.tools(c)
IO.puts("tool count: #{length(tools)}")

{:ok, res} = Client.call_tool(c, "echo", %{"text" => "hello over stdio"})
IO.puts("echo: " <> Mcp2026.text(res))

Client.close(c)  # stops the server process
