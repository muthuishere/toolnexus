defmodule Toolnexus.TestAgentMock do
  @moduledoc false
  # Scripted in-process LLM for the §7D agent-runtime tests (openai style, keyed by
  # body["model"]) — zero network, zero cost. Delivered through the client's
  # first-class `:transport` seam (§8 Gap 2), so the runtime's turn gate wraps it
  # exactly as it would wrap a real HTTP call. Scripts mirror the shared
  # `examples/subagent-*` fixtures' mockLLM sections.

  @usage %{"prompt_tokens" => 30, "completion_tokens" => 10, "total_tokens" => 40}

  def usage_per_call, do: @usage

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

  @doc "Wrap a `body -> response` script as a client `:transport` function."
  def transport(fun) do
    fn %{body: body} -> {:ok, %{status: 200, headers: %{}, body: fun.(body)}} end
  end

  # ---- fixture scripts: subagent-fanout / -escalation / -budgets / -lifecycle ----
  def demo(body) do
    # a short beat per LLM call so genuinely-parallel Runs OVERLAP measurably
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
        # blocks the Run inside the (gated) LLM call; interrupt kills the Run process
        Process.sleep(120_000)
        openai_response(%{"content" => "slow-done"})

      _ ->
        openai_response(%{"content" => "ok"})
    end
  end

  # ---- UX scripts (S10–S13) ----
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

  # ---- agent-home scripts (H1–H7) — mirrors examples/persona-agent/fixture.json ----
  @home_order ["AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"]
  @heartbeat_ok_text "HEARTBEAT_OK"

  def home(body) do
    msgs = body["messages"] || []
    tool_msgs = Enum.filter(msgs, &(&1["role"] == "tool"))

    sys =
      Enum.find_value(msgs, "", fn m -> if m["role"] == "system", do: to_string(m["content"]) end)

    last = to_string(List.last(msgs)["content"] || "")

    case body["model"] do
      "m-echo-soul" ->
        found = @home_order |> Enum.filter(&String.contains?(sys, "## " <> &1)) |> Enum.join(",")
        openai_response(%{"content" => "sections:[#{found}]"})

      "m-remember" ->
        if tool_msgs == [],
          do:
            openai_response(
              tool_call("memory", %{"action" => "add", "target" => "user", "text" => "Prefers dark roast"}, "w1")
            ),
          else: openai_response(%{"content" => "saved: #{hd(tool_msgs)["content"]}"})

      "m-recall" ->
        openai_response(%{
          "content" => if(String.contains?(sys, "Prefers dark roast"), do: "I recall: dark roast", else: "no memory")
        })

      "m-heartbeat" ->
        speak = String.contains?(sys, "remind about the 3pm sync") and String.contains?(last, "Heartbeat")
        openai_response(%{"content" => if(speak, do: "Reminder: 3pm sync 🔔", else: @heartbeat_ok_text)})

      _ ->
        openai_response(%{"content" => "ok"})
    end
  end
end
