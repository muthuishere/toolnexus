# TEST SUPPORT: a tiny MCP 2026-07-28 stdio server (echo + greet/MRTR), one JSON-RPC
# message per line. Launch: elixir -pa _build/test/lib/jason/ebin <this file>
defmodule Mcp2026StdioFixture do
  @pv "2026-07-28"
  @tools [
    %{"name" => "echo", "description" => "Echo the given text back.",
      "inputSchema" => %{"type" => "object", "properties" => %{"text" => %{"type" => "string"}}, "required" => ["text"]}},
    %{"name" => "greet", "description" => "Greet the user; asks for their name via input_required.",
      "inputSchema" => %{"type" => "object", "properties" => %{}}}
  ]

  def loop do
    case IO.binread(:stdio, :line) do
      :eof -> :ok
      {:error, _} -> :ok
      line ->
        IO.write(:stderr, "log: got a line\n")
        case Jason.decode(line) do
          {:ok, %{"id" => id} = req} when id != nil ->
            p = req["params"] || %{}
            msg =
              if get_in(p, ["_meta", "io.modelcontextprotocol/protocolVersion"]) != @pv,
                do: %{"error" => %{"code" => -32022, "message" => "Unsupported protocol version"}},
                else: handle(req["method"], p)
            IO.binwrite(:stdio, Jason.encode!(Map.merge(%{"jsonrpc" => "2.0", "id" => id}, msg)) <> "\n")
          _ -> :ok
        end
        loop()
    end
  end

  defp handle("server/discover", _), do: %{"result" => %{"supportedVersions" => [@pv], "capabilities" => %{"tools" => %{}},
    "serverInfo" => %{"name" => "stdio-fixture", "version" => "0"}}}
  defp handle("tools/list", _), do: %{"result" => %{"tools" => @tools}}
  defp handle("tools/call", %{"name" => "echo", "arguments" => a}),
    do: text(a["text"] || "", %{"text" => a["text"] || ""})
  defp handle("tools/call", %{"name" => "greet"} = p) do
    case p["inputResponses"] do
      %{"name" => %{"action" => "accept", "content" => %{"name" => n}}} -> text("Hello, #{n}!", nil)
      _ -> %{"result" => %{"resultType" => "input_required", "requestState" => "rs-1", "inputRequests" => %{"name" =>
        %{"method" => "elicitation/create", "params" => %{"mode" => "form", "message" => "What is your name?",
          "requestedSchema" => %{"type" => "object", "properties" => %{"name" => %{"type" => "string"}}}}}}}}
    end
  end
  defp handle("tools/call", %{"name" => "die"}), do: System.halt(3)
  defp handle(m, _), do: %{"error" => %{"code" => -32601, "message" => "Method not found: #{m}"}}

  defp text(t, nil), do: %{"result" => %{"content" => [%{"type" => "text", "text" => t}], "isError" => false}}
  defp text(t, sc), do: %{"result" => %{"content" => [%{"type" => "text", "text" => t}], "structuredContent" => sc, "isError" => false}}
end

Mcp2026StdioFixture.loop()
