# Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
#
# Run: mix run examples/03-tool-input.exs
#
# In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
# RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
# The client answers them and re-sends the same call with inputResponses (+ the opaque
# requestState). call_tool runs those rounds for you: you only supply a responder function.
alias Mcp2026.{Client, Server}

srv = Server.serve(Server.fixture())  # `greet` asks for the user's name
c = Client.new(srv.url)

# The responder is called once per input request. key identifies the request inside the
# round; req is the request itself (%{"method" => "elicitation/create", "params" => ...}).
# In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
ask_user = fn _key, req ->
  IO.puts("server asks (#{req["method"]}): #{req["params"]["message"]}")
  # Answer like a person who filled the form. "decline" / "cancel" are the other actions.
  %{"action" => "accept", "content" => %{"name" => "Ada"}}
end

{:ok, res} = Client.call_tool(c, "greet", %{}, ask_user)
IO.puts("result: " <> Mcp2026.text(res))
