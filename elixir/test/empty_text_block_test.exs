defmodule Toolnexus.EmptyTextBlockTest do
  @moduledoc """
  Issue #235 / SPEC §8.x — an empty anthropic text block beside tool_use must never be
  stored in the assistant message, or the next request is rejected with a 400.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.{Client, Native}

  defp data(m), do: "data: " <> Jason.encode!(m) <> "\n\n"

  defp turn1(:run) do
    %{
      "content" => [
        %{"type" => "text", "text" => ""},
        %{"type" => "tool_use", "id" => "toolu_0", "name" => "echo", "input" => %{}}
      ],
      "stop_reason" => "tool_use",
      "usage" => %{"input_tokens" => 1, "output_tokens" => 1}
    }
  end

  defp turn1(:stream) do
    [
      %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}},
      %{
        "type" => "content_block_start",
        "index" => 0,
        "content_block" => %{"type" => "text", "text" => ""}
      },
      %{"type" => "content_block_stop", "index" => 0},
      %{
        "type" => "content_block_start",
        "index" => 1,
        "content_block" => %{
          "type" => "tool_use",
          "id" => "toolu_0",
          "name" => "echo",
          "input" => %{}
        }
      },
      %{
        "type" => "content_block_delta",
        "index" => 1,
        "delta" => %{"type" => "input_json_delta", "partial_json" => "{}"}
      },
      %{"type" => "content_block_stop", "index" => 1},
      %{
        "type" => "message_delta",
        "delta" => %{"stop_reason" => "tool_use"},
        "usage" => %{"output_tokens" => 1}
      },
      %{"type" => "message_stop"}
    ]
    |> Enum.map_join(&data/1)
  end

  defp turn2(:run) do
    %{
      "content" => [%{"type" => "text", "text" => "done"}],
      "stop_reason" => "end_turn",
      "usage" => %{"input_tokens" => 1, "output_tokens" => 1}
    }
  end

  defp turn2(:stream) do
    [
      %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}},
      %{
        "type" => "content_block_start",
        "index" => 0,
        "content_block" => %{"type" => "text", "text" => ""}
      },
      %{
        "type" => "content_block_delta",
        "index" => 0,
        "delta" => %{"type" => "text_delta", "text" => "done"}
      },
      %{"type" => "content_block_stop", "index" => 0},
      %{
        "type" => "message_delta",
        "delta" => %{"stop_reason" => "end_turn"},
        "usage" => %{"output_tokens" => 1}
      },
      %{"type" => "message_stop"}
    ]
    |> Enum.map_join(&data/1)
  end

  defp run_mode(client, :run, echo), do: Client.run(client, "go", [echo])

  defp run_mode(client, :stream, echo) do
    client
    |> Client.stream("go", [echo])
    |> Enum.find(&(&1.type == "done"))
    |> Map.fetch!(:result)
  end

  for mode <- [:run, :stream] do
    @mode mode
    test "anthropic empty text block is not stored (#{mode})" do
      mode = @mode
      {:ok, agent} = Agent.start_link(fn -> [] end)

      transport = fn req ->
        n = Agent.get_and_update(agent, fn bodies -> {length(bodies), bodies ++ [req.body]} end)
        body = if n == 0, do: turn1(mode), else: turn2(mode)
        {:ok, %{status: 200, headers: %{}, body: body}}
      end

      echo =
        Native.define_tool(
          name: "echo",
          description: "echo",
          input_schema: %{"type" => "object", "properties" => %{}},
          execute: fn _ -> "ok" end
        )

      client =
        Client.create(
          base_url: "http://fake.invalid",
          style: "anthropic",
          model: "m",
          api_key: "k",
          transport: transport
        )

      result = run_mode(client, mode, echo)

      bodies = Agent.get(agent, & &1)
      assert result.text == "done"
      assert length(bodies) == 2

      msgs = Enum.at(bodies, 1)["messages"]

      for m <- msgs, is_list(m["content"]), b <- m["content"] do
        refute b["type"] == "text" and b["text"] == "", "empty text block sent: #{inspect(msgs)}"
      end
    end
  end
end
