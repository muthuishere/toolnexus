# Hermetic stdio MCP server fixture (JSON-RPC 2.0 over newline-delimited stdio).
#
# The outbound-stdio counterpart to python/tests/_stdio_mcp_server.py and
# csharp/tests/StdioMcpServerFixture — and the child process under test for the
# ADR-0007 regression (the child must outlive connect and die on close).
#
# Launch (Jason comes from the project's build):
#   elixir -pa _build/test/lib/jason/ebin test/support/stdio_mcp_server.exs
#
# Tools: echo, structured, fail, getenv, and elicit (sends `elicitation/create`
# to the client and returns the raw elicit result as JSON text).

defmodule StdioMcpServerFixture do
  @tools [
    %{
      "name" => "echo",
      "description" => "Echo the given text back.",
      "inputSchema" => %{
        "type" => "object",
        "properties" => %{"text" => %{"type" => "string"}},
        "required" => ["text"]
      }
    },
    %{
      "name" => "structured",
      "description" => "Return the text inside structuredContent.",
      "inputSchema" => %{"type" => "object", "properties" => %{"text" => %{"type" => "string"}}}
    },
    %{
      "name" => "fail",
      "description" => "Return an isError tool result.",
      "inputSchema" => %{"type" => "object", "properties" => %{"text" => %{"type" => "string"}}}
    },
    %{
      "name" => "getenv",
      "description" => "Return the value of the named environment variable.",
      "inputSchema" => %{"type" => "object", "properties" => %{"name" => %{"type" => "string"}}}
    },
    %{
      "name" => "elicit",
      "description" => "Elicit input from the client; returns the raw elicit result as JSON.",
      "inputSchema" => %{"type" => "object", "properties" => %{"mode" => %{"type" => "string"}}}
    }
  ]

  def run, do: loop(0)

  defp loop(seq) do
    case read_line() do
      :eof -> :ok
      "" -> loop(seq)
      line -> loop(handle(Jason.decode!(line), seq))
    end
  end

  defp read_line do
    case IO.binread(:stdio, :line) do
      :eof -> :eof
      {:error, _} -> :eof
      line -> String.trim(line)
    end
  end

  defp handle(%{"method" => "initialize", "id" => id} = msg, seq) do
    reply(id, %{
      "protocolVersion" => get_in(msg, ["params", "protocolVersion"]) || "2025-06-18",
      "capabilities" => %{"tools" => %{}},
      "serverInfo" => %{"name" => "stdio-fixture", "version" => "0.0.1"}
    })

    seq
  end

  defp handle(%{"method" => "notifications/" <> _}, seq), do: seq
  defp handle(%{"method" => "ping", "id" => id}, seq), do: tap_seq(seq, fn -> reply(id, %{}) end)

  defp handle(%{"method" => "tools/list", "id" => id}, seq),
    do: tap_seq(seq, fn -> reply(id, %{"tools" => @tools}) end)

  defp handle(%{"method" => "tools/call", "id" => id} = msg, seq) do
    name = get_in(msg, ["params", "name"])
    args = get_in(msg, ["params", "arguments"]) || %{}

    case name do
      "echo" ->
        reply(id, text_result("echo: " <> to_string(args["text"] || "")))
        seq

      "structured" ->
        reply(id, %{"content" => [], "structuredContent" => %{"echoed" => args["text"]}})
        seq

      "fail" ->
        reply(id, %{
          "content" => [%{"type" => "text", "text" => "boom: " <> to_string(args["text"] || "")}],
          "isError" => true
        })

        seq

      "getenv" ->
        reply(id, text_result(System.get_env(to_string(args["name"] || "")) || ""))
        seq

      "elicit" ->
        seq = seq + 1
        srv_id = "srv-#{seq}"

        params =
          if args["mode"] == "url" do
            %{
              "mode" => "url",
              "url" => "https://example.com/authorize",
              "message" => "Authorize access"
            }
          else
            %{
              "message" => "Enter value",
              "requestedSchema" => %{
                "type" => "object",
                "properties" => %{"value" => %{"type" => "string"}}
              }
            }
          end

        send_msg(%{
          "jsonrpc" => "2.0",
          "id" => srv_id,
          "method" => "elicitation/create",
          "params" => params
        })

        elicit_result = await_response(srv_id)
        reply(id, text_result(Jason.encode!(elicit_result)))
        seq

      _ ->
        send_msg(%{
          "jsonrpc" => "2.0",
          "id" => id,
          "error" => %{"code" => -32602, "message" => "unknown tool: #{name}"}
        })

        seq
    end
  end

  defp handle(%{"method" => _, "id" => id}, seq) do
    send_msg(%{
      "jsonrpc" => "2.0",
      "id" => id,
      "error" => %{"code" => -32601, "message" => "Method not found"}
    })

    seq
  end

  defp handle(_msg, seq), do: seq

  defp await_response(id) do
    case read_line() do
      :eof ->
        %{"error" => "eof"}

      "" ->
        await_response(id)

      line ->
        case Jason.decode!(line) do
          %{"id" => ^id} = msg -> msg["result"] || %{"error" => msg["error"]}
          _other -> await_response(id)
        end
    end
  end

  defp tap_seq(seq, fun) do
    fun.()
    seq
  end

  defp text_result(text),
    do: %{"content" => [%{"type" => "text", "text" => text}], "isError" => false}

  defp reply(id, result), do: send_msg(%{"jsonrpc" => "2.0", "id" => id, "result" => result})
  defp send_msg(msg), do: IO.binwrite(:stdio, Jason.encode!(msg) <> "\n")
end

StdioMcpServerFixture.run()
