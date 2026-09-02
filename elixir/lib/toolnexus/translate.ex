defmodule Toolnexus.Translate do
  @moduledoc """
  Single-turn translation — the translator path (SPEC.md §11, ADR-0011).

  `Toolnexus.Client.translate/2` is toolnexus used as a pure wire-format translator:
  OpenAI shapes in, exactly ONE provider call, OpenAI shapes out. No agent loop, no tool
  execution, no conversation state — so a caller can run it statelessly.

  It is the INBOUND half of the §5 adapters: `to_openai/1`/`to_anthropic/1`/`to_gemini/1`
  send declarations out, this reads the provider's tool calls back in.

  Use it when the CALLER owns the conversation and executes tools itself (the standard
  OpenAI function-calling posture). When toolnexus owns the conversation, use the agent
  loop with relay tools instead (§10).

  This module holds the pure translation functions; the entry point itself lives on
  `Toolnexus.Client`.
  """

  alias Toolnexus.ContentPart

  defmodule ToolCall do
    @moduledoc """
    One tool call the model asked for, in OpenAI shape (§11).

    * `:id` — the tool-call id the caller must echo on its `tool` result message
    * `:name` — the function name
    * `:arguments` — arguments as a JSON **string**, the OpenAI wire form, echoable
      byte-for-byte
    """
    defstruct id: "", name: "", arguments: "{}"

    @type t :: %__MODULE__{id: String.t(), name: String.t(), arguments: String.t()}
  end

  defmodule Result do
    @moduledoc """
    An OpenAI-shaped single-turn result (§11).

    * `:text` — assistant text (`""` when the model only called tools)
    * `:tool_calls` — the calls the model emitted, in provider order; none is ever dropped
    * `:finish_reason` — `"stop" | "tool_calls" | "length" | "content_filter"`
    * `:usage` — this single call's token usage
    * `:model` — the model that answered
    * `:raw` — the provider's decoded response, for fields this struct does not model
    """
    defstruct text: "",
              tool_calls: [],
              finish_reason: "stop",
              usage: %{prompt_tokens: 0, completion_tokens: 0, total_tokens: 0},
              model: "",
              raw: nil

    @type t :: %__MODULE__{}
  end

  @doc """
  Renders a result's tool calls as an OpenAI `tool_calls` array, ready to put on an
  assistant message. Convenience for assembling a response envelope.
  """
  @spec tool_calls_json(Result.t()) :: [map()]
  def tool_calls_json(%Result{tool_calls: calls}) do
    Enum.map(calls, fn tc ->
      %{
        "id" => tc.id,
        "type" => "function",
        "function" => %{"name" => tc.name, "arguments" => tc.arguments}
      }
    end)
  end

  @doc """
  Maps a provider stop reason onto an OpenAI finish reason. Tool calls win: a turn that
  emitted any tool call is always `"tool_calls"` to a conforming client.
  """
  @spec finish_reason_for(boolean(), String.t() | nil) :: String.t()
  def finish_reason_for(true, _provider_stop), do: "tool_calls"
  def finish_reason_for(false, stop) when stop in ["max_tokens", "length"], do: "length"
  def finish_reason_for(false, stop) when stop in ["refusal", "content_filter"], do: "content_filter"
  def finish_reason_for(false, _), do: "stop"

  @doc """
  Flattens an OpenAI `content` value to text: the string form and the parts-array form.
  Non-text parts are ignored.
  """
  @spec content_text(term()) :: String.t()
  def content_text(content) when is_binary(content), do: content

  def content_text(parts) when is_list(parts) do
    parts
    |> Enum.map(fn
      %{"text" => t} when is_binary(t) -> t
      _ -> ""
    end)
    |> Enum.join()
  end

  def content_text(_), do: ""

  @doc """
  Parses a tool-call arguments value into a map, tolerating both wire forms — some clients
  send `arguments` as an object rather than a JSON string.
  """
  @spec args_object(term()) :: map()
  def args_object(args) when is_map(args), do: args

  def args_object(args) when is_binary(args) do
    case String.trim(args) do
      "" ->
        %{}

      trimmed ->
        case Jason.decode(trimmed) do
          {:ok, decoded} when is_map(decoded) -> decoded
          # a malformed arguments string is not fatal
          _ -> %{}
        end
    end
  end

  def args_object(_), do: %{}

  @doc "Renders a tool-call arguments value as the JSON string the OpenAI wire format uses."
  @spec args_string(term()) :: String.t()
  def args_string(args) when is_binary(args), do: args
  def args_string(args) when is_map(args), do: Jason.encode!(args)
  def args_string(_), do: "{}"

  @doc "Reads an assistant message's OpenAI `tool_calls`."
  @spec tool_calls_of(map()) :: [ToolCall.t()]
  def tool_calls_of(%{"tool_calls" => calls}) when is_list(calls) do
    calls
    |> Enum.flat_map(fn
      %{"function" => fn_} = tc when is_map(fn_) ->
        [
          %ToolCall{
            id: to_string(Map.get(tc, "id", "")),
            name: to_string(Map.get(fn_, "name", "")),
            arguments: args_string(Map.get(fn_, "arguments"))
          }
        ]

      _ ->
        []
    end)
  end

  def tool_calls_of(_), do: []

  @doc """
  Converts an OpenAI `messages` list into Anthropic-native messages plus the extracted
  system prompt, preserving the tool structure a text flattening destroys (§11):

    * an assistant turn's `tool_calls` become `tool_use` blocks, with `arguments` parsed
      back from its JSON string into an object;
    * a `tool`-role result becomes a `tool_result` block keyed by `tool_call_id`, **merged
      into a single user turn** when consecutive (providers expect one result-bearing turn
      answering the preceding assistant turn);
    * `system`/`developer` messages are hoisted out, since Anthropic takes system
      separately.

  Returns `{messages, system}`.
  """
  @spec openai_messages_to_anthropic([map()]) :: {[map()], String.t()}
  def openai_messages_to_anthropic(messages) do
    {out, system_parts, pending} =
      (messages || [])
      |> Enum.filter(&is_map/1)
      |> Enum.reduce({[], [], []}, &reduce_message/2)

    # flush any trailing tool results into their own user turn
    out = flush(out, pending)
    {Enum.reverse(out), Enum.join(Enum.reverse(system_parts), "\n\n")}
  end

  # A user turn carrying the pending tool_result blocks, in order.
  defp flush(out, []), do: out
  defp flush(out, pending), do: [%{"role" => "user", "content" => Enum.reverse(pending)} | out]

  defp reduce_message(m, {out, system_parts, pending}) do
    case Map.get(m, "role") do
      role when role in ["system", "developer"] ->
        out = flush(out, pending)

        case content_text(Map.get(m, "content")) do
          "" -> {out, system_parts, []}
          s -> {out, [s | system_parts], []}
        end

      role when role in ["tool", "function"] ->
        block = %{"type" => "tool_result", "content" => content_text(Map.get(m, "content"))}

        block =
          case Map.get(m, "tool_call_id") do
            id when is_binary(id) and id != "" -> Map.put(block, "tool_use_id", id)
            _ -> block
          end

        {out, system_parts, [block | pending]}

      "assistant" ->
        out = flush(out, pending)
        {out, system_parts, []} |> put_assistant(m)

      _ ->
        out = flush(out, pending)
        {out, system_parts, []} |> put_user(m)
    end
  end

  defp put_assistant({out, system_parts, pending}, m) do
    text_blocks =
      case content_text(Map.get(m, "content")) do
        "" -> []
        s -> [%{"type" => "text", "text" => s}]
      end

    use_blocks =
      Enum.map(tool_calls_of(m), fn tc ->
        %{
          "type" => "tool_use",
          "id" => tc.id,
          "name" => tc.name,
          "input" => args_object(tc.arguments)
        }
      end)

    case text_blocks ++ use_blocks do
      # an empty assistant turn would be rejected by the provider
      [] -> {out, system_parts, pending}
      blocks -> {[%{"role" => "assistant", "content" => blocks} | out], system_parts, pending}
    end
  end

  # §11: a `content` array has its text parts concatenated and its non-text parts
  # translated into the provider's native block shape (§8A). Nothing is flattened away or
  # passed through raw — six ports used to do exactly that, undocumented.
  defp put_user({out, system_parts, pending}, m) do
    case Map.get(m, "content") do
      content when is_list(content) ->
        case content_blocks(content) do
          [] -> {out, system_parts, pending}
          blocks -> {[%{"role" => "user", "content" => user_content(blocks)} | out], system_parts, pending}
        end

      content ->
        case content_text(content) do
          "" -> {out, system_parts, pending}
          s -> {[%{"role" => "user", "content" => s} | out], system_parts, pending}
        end
    end
  end

  # All-text stays a plain string, exactly as before; anything else keeps its blocks.
  defp user_content(blocks) do
    if Enum.all?(blocks, &(is_map(&1) and Map.get(&1, "type") == "text")) do
      Enum.map_join(blocks, "", &(Map.get(&1, "text") || ""))
    else
      blocks
    end
  end

  defp content_blocks(content) do
    Enum.flat_map(content, fn entry ->
      if ContentPart.part?(entry) do
        ContentPart.encode([entry], "anthropic")
      else
        [entry]
      end
    end)
  end

  @doc """
  Converts an OpenAI `tools` list into Anthropic tool declarations. Entries that are
  already provider-native pass through; anything unrecognized is skipped.
  """
  @spec openai_tools_to_anthropic([map()] | nil) :: [map()]
  def openai_tools_to_anthropic(tools) do
    (tools || [])
    |> Enum.filter(&is_map/1)
    |> Enum.flat_map(fn t ->
      case Map.get(t, "function") do
        fn_ when is_map(fn_) ->
          case Map.get(fn_, "name") do
            name when is_binary(name) and name != "" ->
              decl = %{"name" => name}

              decl =
                case Map.get(fn_, "description") do
                  d when is_binary(d) and d != "" -> Map.put(decl, "description", d)
                  _ -> decl
                end

              schema =
                case Map.get(fn_, "parameters") do
                  p when is_map(p) -> p
                  _ -> %{"type" => "object", "properties" => %{}}
                end

              [Map.put(decl, "input_schema", schema)]

            _ ->
              []
          end

        # already provider-native
        _ ->
          if Map.has_key?(t, "name"), do: [t], else: []
      end
    end)
  end

  @doc """
  Maps OpenAI `tool_choice` onto Anthropic's shape. Returns `nil` for absent/`"auto"` (the
  provider default) and for anything unrecognized.
  """
  @spec openai_tool_choice_to_anthropic(term()) :: map() | nil
  def openai_tool_choice_to_anthropic(choice) when choice in ["required", "any"],
    do: %{"type" => "any"}

  def openai_tool_choice_to_anthropic("none"), do: %{"type" => "none"}

  def openai_tool_choice_to_anthropic(%{"function" => %{"name" => name}})
      when is_binary(name) and name != "",
      do: %{"type" => "tool", "name" => name}

  def openai_tool_choice_to_anthropic(_), do: nil

  @doc "True when the message list already carries a system-ish message."
  @spec has_system_message?([map()]) :: boolean()
  def has_system_message?(messages) do
    Enum.any?(messages || [], fn
      %{"role" => r} -> r in ["system", "developer"]
      _ -> false
    end)
  end
end
