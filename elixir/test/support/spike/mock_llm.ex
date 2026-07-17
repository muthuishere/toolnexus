defmodule Toolnexus.Spike.MockLlm do
  @moduledoc """
  Scripted in-process LLM (openai style, keyed by body["model"]) — zero network,
  zero cost. Same pattern as js/spike mockFetch, delivered through Req's `:plug`
  option (the shipped client's `http_options` seam).
  """
  import Plug.Conn

  @usage %{"prompt_tokens" => 30, "completion_tokens" => 10, "total_tokens" => 40}

  def openai_response(message, usage \\ @usage),
    do: %{"choices" => [%{"message" => Map.merge(%{"role" => "assistant"}, message)}], "usage" => usage}

  def tool_call(name, args, id) do
    %{
      "content" => nil,
      "tool_calls" => [
        %{"id" => id, "type" => "function", "function" => %{"name" => name, "arguments" => Jason.encode!(args)}}
      ]
    }
  end

  @doc "Plain (ungated) plug for using the mock outside a Runtime (S13's old-API client)."
  def plain_plug(fun) do
    fn conn ->
      {:ok, raw, conn} = read_body(conn)
      resp = fun.(Jason.decode!(raw))
      conn |> put_resp_content_type("application/json") |> send_resp(200, Jason.encode!(resp))
    end
  end

  # ---- demo.ts mock (S1–S9) ----
  def demo(body) do
    # a short beat per LLM call so genuinely-parallel Runs OVERLAP measurably
    # (the JS event loop gave this for free; real processes need real time)
    Process.sleep(20)
    msgs = body["messages"] || []
    tool_msgs = Enum.filter(msgs, &(&1["role"] == "tool"))

    case body["model"] do
      "m-coordinator" ->
        if tool_msgs == [] do
          openai_response(%{
            "content" => nil,
            "tool_calls" => [
              %{
                "id" => "c1",
                "type" => "function",
                "function" => %{"name" => "task", "arguments" => Jason.encode!(%{"agent" => "explore", "prompt" => "find A"})}
              },
              %{
                "id" => "c2",
                "type" => "function",
                "function" => %{"name" => "task", "arguments" => Jason.encode!(%{"agent" => "explore", "prompt" => "find B"})}
              }
            ]
          })
        else
          openai_response(%{"content" => "synthesis: " <> Enum.map_join(tool_msgs, " + ", &to_string(&1["content"]))})
        end

      "m-explore" ->
        if tool_msgs == [],
          do: openai_response(tool_call("lookup", %{"q" => "x"}, "e1")),
          else: openai_response(%{"content" => "found:#{hd(tool_msgs)["content"]}"})

      "m-approver-parent" ->
        if tool_msgs == [],
          do: openai_response(tool_call("task", %{"agent" => "asker", "prompt" => "get the secret"}, "p1")),
          else: openai_response(%{"content" => "parent-final: #{List.last(tool_msgs)["content"]}"})

      "m-asker" ->
        if Enum.any?(tool_msgs, &String.contains?(to_string(&1["content"]), "secret-token")),
          do: openai_response(%{"content" => "asker-done: secret-token"}),
          else: openai_response(tool_call("check_secret", %{}, "a1"))

      "m-peer" ->
        last = to_string(List.last(msgs)["content"] || "")
        n = length(Regex.scan(~r/^\d+\./m, last))
        openai_response(%{"content" => "processed #{n} items"})

      # never finishes: always another tool call → maxTurns/incomplete
      "m-loop" ->
        openai_response(tool_call("lookup", %{"q" => "again"}, "l#{length(tool_msgs)}"))

      "m-slow" ->
        # blocks the Run inside the LLM call; interrupt kills the Run process
        # (BEAM-native cancellation — no AbortSignal seam needed)
        Process.sleep(120_000)
        openai_response(%{"content" => "slow-done"})

      _ ->
        openai_response(%{"content" => "ok"})
    end
  end

  # ---- demo-ux.ts mock (S10–S13) ----
  def ux(body) do
    msgs = body["messages"] || []
    tool_msgs = Enum.filter(msgs, &(&1["role"] == "tool"))

    sys =
      Enum.find_value(msgs, "", fn m ->
        if m["role"] == "system", do: to_string(m["content"])
      end)

    case body["model"] do
      "m-coder" ->
        if tool_msgs == [] do
          openai_response(tool_call("task", %{"agent" => "explore", "prompt" => "find the bug"}, "t1"))
        else
          soul = if String.contains?(sys, "You are the CODER"), do: "loaded", else: "missing"
          openai_response(%{"content" => "fixed using: #{hd(tool_msgs)["content"]} [soul:#{soul}]"})
        end

      "m-explore" ->
        if tool_msgs == [],
          do: openai_response(tool_call("lookup", %{"q" => "bug"}, "e1")),
          else: openai_response(%{"content" => "bug at line 42 (#{hd(tool_msgs)["content"]})"})

      "m-mia" ->
        last = to_string(List.last(msgs)["content"] || "")

        if String.contains?(last, "Heartbeat") do
          has_ticks = String.contains?(last, "channel=timer")

          text =
            if String.contains?(sys, "water the plants") and has_ticks,
              do: "Reminder: water the plants! 🌱",
              else: "HEARTBEAT_OK"

          openai_response(%{"content" => text})
        else
          found =
            ["SOUL.md", "USER.md", "MEMORY.md"]
            |> Enum.filter(&String.contains?(sys, "## " <> &1))
            |> Enum.join(",")

          openai_response(%{"content" => "soul-sections:[#{found}]"})
        end

      "m-old-api" ->
        if tool_msgs == [],
          do: openai_response(tool_call("explore", %{"prompt" => "scan the repo"}, "b1")),
          else: openai_response(%{"content" => "old-api summary: #{hd(tool_msgs)["content"]}"})

      _ ->
        openai_response(%{"content" => "ok"})
    end
  end
end
