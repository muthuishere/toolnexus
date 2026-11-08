defmodule OneShot do
  @moduledoc "One CLI process per turn: the prompt goes in on stdin, the reply comes back on stdout."

  # Render the whole request as one prompt: tools, transcript, and the reply contract.
  def render(%{messages: messages} = req) do
    tools = for t <- req.tools || [], f = t["function"],
                do: "- #{f["name"]}: #{f["description"]} args=#{JSON.encode!(f["parameters"])}"

    {convo, _called} =
      Enum.flat_map_reduce(messages, %{}, fn m, called ->
        cond do
          m["role"] == "tool" ->
            {["[tool #{called[m["tool_call_id"]] || m["tool_call_id"]}] #{m["content"]}"], called}

          m["tool_calls"] ->
            called = Enum.reduce(m["tool_calls"], called, &Map.put(&2, &1["id"], &1["function"]["name"]))
            {["[assistant called] " <> JSON.encode!(Enum.map(m["tool_calls"], & &1["function"]))], called}

          true ->
            c = if is_binary(m["content"]), do: m["content"], else: JSON.encode!(m["content"])
            {["[#{m["role"]}] #{c}"], called}
        end
      end)

    Enum.join(
      ["You are the model inside a tool-calling loop. Tools you may call:"] ++ tools ++
        ["", "Conversation:"] ++ convo ++
        ["", ~s(Reply with ONLY one JSON object: {"tool_calls":[{"name":"...","arguments":{...}}]} to call tools,),
         ~s(or {"content":"..."} with the final answer. No prose, no code fences.)],
      "\n")
  end

  # Pull the outermost {...} out of the reply, tolerating fences or chatter around it.
  def parse(out) do
    json =
      case {:binary.match(out, "{"), :binary.matches(out, "}") |> List.last()} do
        {{s, _}, {e, _}} when e > s -> binary_part(out, s, e - s + 1)
        _ -> ""
      end

    case JSON.decode(json) do
      {:ok, %{"tool_calls" => [_ | _] = calls}} ->
        %{tool_calls: Enum.map(calls, &%{name: &1["name"], arguments: &1["arguments"] || %{}})}

      {:ok, %{"content" => c}} when is_binary(c) ->
        %{content: c}

      _ ->
        %{content: String.trim(out)} # not our contract: treat it as the answer
    end
  end

  # System.cmd cannot feed stdin, and a Port cannot half-close it to send EOF, so the prompt
  # goes through a temp file that `sh` redirects into the command's stdin.
  def client(command, args, model \\ nil) do
    Toolnexus.Client.create_in_process(
      model: model || command,
      generate: fn req ->
        path = Path.join(System.tmp_dir!(), "oneshot-#{System.unique_integer([:positive])}.txt")
        File.write!(path, render(req))

        try do
          case System.cmd("sh", ["-c", ~s(f="$1"; shift; exec "$@" < "$f"), "sh", path, command | args]) do
            {out, 0} -> parse(out)
            {out, code} -> raise "#{command} exited #{code}: #{out}"
          end
        after
          File.rm(path)
        end
      end
    )
  end
end
