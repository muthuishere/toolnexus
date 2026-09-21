# Hermetic fake ACP (Agent Client Protocol) server, ported from the Go
# reference at spikes/acp/fakeagent/main.go (ADR 0031 gate). JSON-RPC 2.0,
# one object per line, over its own stdin/stdout — the same shape a real
# `devin acp` speaks.
#
# It is a deterministic script, not a general ACP implementation. Select a
# scenario with the first CLI arg:
#
#   echo        - trivial: replies "echo:<text>" and reports how many times
#                 session/new has been received (always 1 across many
#                 prompts on one connection — proves warm-session reuse) and
#                 a per-prompt sequence number (proves turn serialisation:
#                 the caller only ever has ONE prompt in flight per session).
#   stale       - stateful session; answers a near-duplicate prompt with a
#                 STALE answer unless the new prompt carries
#                 "SUPERSEDES-ALL-PRIOR:", in which case it answers fresh.
#   permission  - sends session/request_permission mid-turn and blocks until
#                 answered before finishing (proves the client answers
#                 inline rather than never answering / hanging).
#   noisy       - emits agent_thought_chunk + tool_call/tool_call_update
#                 narration interleaved with the real agent_message_chunk
#                 payload, to prove naive accumulation corrupts structured
#                 output while filtering to agent_message_chunk stays clean.
#   slow        - sleeps before replying, so a test can prove turns on one
#                 session are serialised (never sent concurrently).
#
# Launch (Jason comes from the project's build):
#   elixir -pa _build/test/lib/jason/ebin test/support/fake_acp_server.exs <scenario>

defmodule FakeAcpServer do
  def run do
    scenario = List.first(System.argv()) || "echo"
    loop(scenario, %{new_count: 0, prompt_n: 0, history: [], new_params: %{}})
  end

  defp loop(scenario, state) do
    case read_line() do
      :eof -> :ok
      "" -> loop(scenario, state)
      line -> loop(scenario, handle(scenario, Jason.decode!(line), state))
    end
  end

  defp read_line do
    case IO.binread(:stdio, :line) do
      :eof -> :eof
      {:error, _} -> :eof
      line -> String.trim(line)
    end
  end

  defp handle(_scenario, %{"method" => "initialize", "id" => id}, state) do
    reply(id, %{"protocolVersion" => 1, "agentCapabilities" => %{"loadSession" => false}})
    %{state | new_count: state.new_count}
  end

  defp handle(_scenario, %{"method" => "session/new", "id" => id} = msg, state) do
    reply(id, %{"sessionId" => "sess-1"})
    %{state | new_count: state.new_count + 1, new_params: msg["params"] || %{}}
  end

  defp handle(_scenario, %{"method" => "session/set_mode", "id" => id}, state) do
    reply(id, %{})
    state
  end

  defp handle(scenario, %{"method" => "session/prompt", "id" => id, "params" => params}, state) do
    text = params["prompt"] |> List.wrap() |> Enum.map(& &1["text"]) |> Enum.join("")
    state = %{state | prompt_n: state.prompt_n + 1, history: state.history ++ [text]}
    dispatch(scenario, id, text, state)
    state
  end

  defp handle(_scenario, %{"method" => "session/cancel", "id" => id}, state) do
    reply(id, %{})
    state
  end

  defp handle(_scenario, %{"method" => _method, "id" => id}, state) do
    reply(id, %{})
    state
  end

  defp handle(_scenario, _msg, state), do: state

  # ---- scenarios --------------------------------------------------------

  defp dispatch("stale", id, text, state) do
    marker = "SUPERSEDES-ALL-PRIOR:"

    answer =
      case :binary.match(text, marker) do
        {idx, len} ->
          fresh = text |> binary_part(idx + len, byte_size(text) - idx - len) |> String.trim()
          "FRESH-ANSWER-TO:" <> fresh

        :nomatch ->
          matched = Enum.find(state.history, hd(state.history), &String.contains?(text, &1))
          "STALE-ANSWER-TO:" <> matched
      end

    emit_chunk(answer)
    reply(id, %{"stopReason" => "end_turn"})
  end

  defp dispatch("permission", id, text, _state) do
    req_id = "srv-1"

    send_msg(%{
      "jsonrpc" => "2.0",
      "id" => req_id,
      "method" => "session/request_permission",
      "params" => %{
        "sessionId" => "sess-1",
        "options" => [
          %{"optionId" => "reject", "kind" => "reject_once", "name" => "Reject"},
          %{"optionId" => "allow-once", "kind" => "allow_once", "name" => "Allow"}
        ]
      }
    })

    await_permission_reply(req_id)
    emit_chunk("PERMITTED:" <> text)
    reply(id, %{"stopReason" => "end_turn"})
  end

  defp dispatch("noisy", id, text, _state) do
    emit_notification(%{"sessionUpdate" => "agent_thought_chunk", "content" => %{"type" => "text", "text" => "Let me think... "}})
    emit_notification(%{"sessionUpdate" => "tool_call", "toolCallId" => "t1", "title" => "reading files", "status" => "in_progress"})
    emit_chunk(~s({"answer":))
    emit_notification(%{"sessionUpdate" => "tool_call_update", "toolCallId" => "t1", "status" => "completed"})
    emit_notification(%{"sessionUpdate" => "agent_thought_chunk", "content" => %{"type" => "text", "text" => "double-checking... "}})
    emit_chunk(~s("#{text}"}))
    reply(id, %{"stopReason" => "end_turn"})
  end

  defp dispatch("slow", id, text, state) do
    Process.sleep(150)
    emit_chunk("slow:#{state.prompt_n}:#{text}")
    reply(id, %{"stopReason" => "end_turn"})
  end

  defp dispatch(_echo, id, text, state) do
    params_json = Jason.encode!(state.new_params)
    emit_chunk("echo:#{text} new_count=#{state.new_count} seq=#{state.prompt_n} params=#{params_json}")
    reply(id, %{"stopReason" => "end_turn"})
  end

  defp await_permission_reply(want_id) do
    case read_line() do
      :eof ->
        :eof

      "" ->
        await_permission_reply(want_id)

      line ->
        case Jason.decode!(line) do
          %{"id" => ^want_id} -> :ok
          _ -> await_permission_reply(want_id)
        end
    end
  end

  defp emit_chunk(text) do
    emit_notification(%{"sessionUpdate" => "agent_message_chunk", "content" => %{"type" => "text", "text" => text}})
  end

  defp emit_notification(update) do
    send_msg(%{"jsonrpc" => "2.0", "method" => "session/update", "params" => %{"sessionId" => "sess-1", "update" => update}})
  end

  defp reply(id, result), do: send_msg(%{"jsonrpc" => "2.0", "id" => id, "result" => result})
  defp send_msg(msg), do: IO.binwrite(:stdio, Jason.encode!(msg) <> "\n")
end

FakeAcpServer.run()
