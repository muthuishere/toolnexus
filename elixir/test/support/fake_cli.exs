# Hermetic fakes for the CLI client (SPEC §8C), mirroring golang/cli_test.go.
# One script plays three parts, chosen like the Go test binary does:
#
#   argv `mcp-bridge <url>`        - the stdio MCP bridge (Toolnexus.Cli.run_mcp_bridge)
#   TN_FAKE_ACP=1 and argv `acp`   - a fake ACP agent
#   TN_FAKE_CLI=<mode>             - a fake one-shot CLI
#
# It is launched through a generated sh wrapper that puts every _build ebin on
# the code path (see CliTestHelpers.wrapper!/0).

defmodule FakeCli do
  def main(argv) do
    cond do
      match?(["mcp-bridge", _], argv) ->
        {:ok, _} = Application.ensure_all_started(:req)
        [_, url] = argv

        case Toolnexus.Cli.run_mcp_bridge(url, System.get_env("TOOLNEXUS_MCP_TOKEN")) do
          :ok ->
            :ok

          {:error, msg} ->
            IO.puts(:stderr, msg)
            System.halt(1)
        end

      System.get_env("TN_FAKE_ACP") == "1" and List.first(argv) == "acp" ->
        FakeAgent.run()

      (mode = System.get_env("TN_FAKE_CLI")) not in [nil, ""] ->
        one_shot(mode, argv)

      true ->
        IO.puts(:stderr, "fake_cli: nothing to do")
        System.halt(9)
    end
  end

  # Prompt from stdin, or the arg after "--p", or the file after "--f". Writes
  # the answer to stdout, or to the file after "--o".
  defp one_shot(mode, argv) do
    {prompt, out_file, argv_mode} = scan(argv, "", nil, false)

    if argv_mode do
      IO.write(Jason.encode!(%{"content" => Enum.join(argv, " ")}))
    else
      prompt = if prompt == "", do: IO.binread(:stdio, :eof) |> to_bin(), else: prompt

      if rec = System.get_env("TN_FAKE_REC") do
        File.write!(rec, prompt <> "\n=====\n", [:append])
      end

      reply =
        case mode do
          "exit" ->
            IO.write(:stderr, "boom: bad login")
            System.halt(3)

          "empty" ->
            nil

          "sleep" ->
            Process.sleep(10_000)
            ~s({"content":"hello"})

          "nooutfile" ->
            IO.write("progress only")
            nil

          "tool" ->
            if String.contains?(prompt, "BADGE-77Q") do
              Jason.encode!(%{"content" => "Your badge is BADGE-77Q."})
            else
              "```json\n" <>
                ~s({"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_badge_number","arguments":"{}"}}]}) <>
                "\n```\n\n"
            end

          _ ->
            ~s({"content":"hello"})
        end

      cond do
        reply == nil ->
          :ok

        out_file ->
          IO.write("progress on stdout\n")
          File.write!(out_file, reply)

        true ->
          IO.write(reply)
      end
    end
  end

  defp to_bin(:eof), do: ""
  defp to_bin({:error, _}), do: ""
  defp to_bin(b), do: b

  defp scan([], p, o, a), do: {p, o, a}
  defp scan(["--p", v | rest], _p, o, a), do: scan(rest, v, o, a)
  defp scan(["--f", f | rest], _p, o, a), do: scan(rest, File.read!(f), o, a)
  defp scan(["--o", f | rest], p, _o, a), do: scan(rest, p, f, a)
  defp scan(["--argv" | _], p, o, _a), do: {p, o, true}
  # Style-agnostic detection, so every built-in style's real argv works: the
  # prompt as an arg, the {{file}} temp file, the {{out}} temp path (not yet created).
  defp scan([x | rest], p, o, a) do
    cond do
      String.starts_with?(x, String.slice(Toolnexus.Cli.preamble(), 0, 20)) ->
        scan(rest, x, o, a)

      String.contains?(Path.basename(x), "toolnexus-cli-") ->
        case File.read(x) do
          {:ok, b} -> scan(rest, b, o, a)
          _ -> scan(rest, p, x, a)
        end

      true ->
        scan(rest, p, o, a)
    end
  end
end

defmodule FakeAgent do
  # FAKE_HTTP=1 advertises mcpCapabilities.http. FAKE_FAIL_INIT=1 exits before
  # answering initialize. FAKE_NO_CONFIG=1 rejects session/set_config_option.
  # FAKE_PROJECT=1 reads the MCP server from <cwd>/.mcp.json instead of
  # session/new. TN_FAKE_REC records every method.
  def run do
    if System.get_env("FAKE_FAIL_INIT") == "1" do
      IO.write(:stderr, "unknown flag: acp")
      System.halt(2)
    end

    loop(%{entry: nil, cwd: nil, id: 1000})
  end

  defp loop(st) do
    case read() do
      :eof -> :ok
      msg -> loop(handle(msg, st))
    end
  end

  defp read do
    case IO.binread(:stdio, :line) do
      :eof -> :eof
      {:error, _} -> :eof
      line -> if String.trim(line) == "", do: read(), else: Jason.decode!(line)
    end
  end

  defp rec(msg) do
    if p = System.get_env("TN_FAKE_REC") do
      File.write!(p, "#{msg["method"]} #{Jason.encode!(msg["params"])}\n", [:append])
    end
  end

  defp out(m), do: IO.binwrite(:stdio, Jason.encode!(Map.put(m, "jsonrpc", "2.0")) <> "\n")
  defp reply(id, result), do: out(%{"id" => id, "result" => result})

  defp handle(%{"method" => "initialize", "id" => id} = m, st) do
    rec(m)
    http = System.get_env("FAKE_HTTP") == "1"

    reply(id, %{
      "protocolVersion" => 1,
      "agentCapabilities" => %{"mcpCapabilities" => %{"http" => http}}
    })

    st
  end

  defp handle(%{"method" => "session/new", "id" => id, "params" => p} = m, st) do
    rec(m)

    entry =
      case p["mcpServers"] do
        [e] -> e
        _ -> nil
      end

    reply(id, %{"sessionId" => "s1"})
    %{st | entry: entry, cwd: p["cwd"]}
  end

  defp handle(%{"method" => "session/set_config_option", "id" => id} = m, st) do
    rec(m)

    if System.get_env("FAKE_NO_CONFIG") == "1",
      do: out(%{"id" => id, "error" => %{"code" => -32601, "message" => "nope"}}),
      else: reply(id, %{})

    st
  end

  defp handle(%{"method" => "session/prompt", "id" => id} = m, st) do
    rec(m)

    entry =
      if System.get_env("FAKE_PROJECT") == "1" do
        pf = st.cwd |> Path.join(".mcp.json") |> File.read!() |> Jason.decode!()
        e = pf["mcpServers"]["toolnexus"]
        hdr = for {k, v} <- e["headers"] || %{}, do: %{"name" => k, "value" => v}
        %{"type" => "http", "url" => e["url"], "headers" => hdr}
      else
        st.entry
      end

    # devin-like: the name is only on the tool_call update; the permission
    # request has an empty title.
    update(%{
      "sessionUpdate" => "tool_call",
      "toolCallId" => "tc1",
      "title" => "Calling get_badge_number from toolnexus"
    })

    {ours, st} = ask(st, "tc1", "")
    update(%{"sessionUpdate" => "tool_call", "toolCallId" => "tc2", "title" => "Bash"})
    {own, st} = ask(st, "tc2", "Bash")
    # a lookalike title on a new id: substring of ours, must be rejected
    {fake, st} = ask(st, "tc3", "my_get_badge_number_wrapper")
    # the same id again with a different title: the decision is per id
    {again, st} = ask(st, "tc1", "Bash")

    text =
      "perm ours=#{ours} own=#{own} fake=#{fake} again=#{again}; " <> call_served(entry)

    update(%{
      "sessionUpdate" => "agent_message_chunk",
      "content" => %{"type" => "text", "text" => text}
    })

    reply(id, %{"stopReason" => "end_turn"})
    st
  end

  defp handle(%{"id" => id} = m, st) when is_map_key(m, "method") do
    rec(m)
    reply(id, %{})
    st
  end

  defp handle(m, st) do
    rec(m)
    st
  end

  defp update(u),
    do: out(%{"method" => "session/update", "params" => %{"sessionId" => "s1", "update" => u}})

  defp ask(st, call_id, title) do
    id = st.id + 1

    out(%{
      "id" => id,
      "method" => "session/request_permission",
      "params" => %{
        "sessionId" => "s1",
        "toolCall" => %{"toolCallId" => call_id, "title" => title},
        "options" => [
          %{"optionId" => "yes", "kind" => "allow_once"},
          %{"optionId" => "no", "kind" => "reject_once"}
        ]
      }
    })

    {await(id), %{st | id: id}}
  end

  defp await(id) do
    case read() do
      :eof -> ""
      %{"id" => ^id, "result" => r} -> get_in(r, ["outcome", "optionId"]) || ""
      _ -> await(id)
    end
  end

  @reqs [
    ~s({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake","version":"0"}}}),
    ~s({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}),
    ~s({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_badge_number","arguments":{}}}),
    ~s({"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_skill","arguments":{"name":"hello-world"}}})
  ]

  # Calls get_badge_number and load_skill on the handed MCP server (http with
  # the given headers, or by spawning the stdio entry), and probes the http
  # endpoint without a token.
  defp call_served(nil), do: "entry=<nil> noauth=0 replies="

  defp call_served(%{"type" => "http"} = e) do
    {:ok, _} = Application.ensure_all_started(:inets)
    url = String.to_charlist(e["url"])

    hdrs =
      for h <- e["headers"],
          do: {String.to_charlist(h["name"]), String.to_charlist(to_string(h["value"]))}

    replies =
      for r <- @reqs do
        {:ok, {_, _, body}} =
          :httpc.request(:post, {url, hdrs, ~c"application/json", r}, [], body_format: :binary)

        body
      end

    {:ok, {{_, noauth, _}, _, _}} =
      :httpc.request(:post, {url, [], ~c"application/json", hd(@reqs)}, [], body_format: :binary)

    "entry=http noauth=#{noauth} replies=" <> Enum.join(replies, " | ")
  end

  defp call_served(e) do
    in_file = Path.join(System.tmp_dir!(), "fake-bridge-in-#{System.unique_integer([:positive])}")
    File.write!(in_file, Enum.join(@reqs, "\n") <> "\n")
    env = for x <- e["env"], do: {x["name"], to_string(x["value"])}

    {out, code} =
      System.cmd(
        "/bin/sh",
        ["-c", ~S(f="$0"; exec "$@" <"$f"), in_file, e["command"] | e["args"]],
        env: env
      )

    File.rm(in_file)

    if code != 0 do
      "bridge error"
    else
      "entry=<nil> noauth=0 replies=" <>
        (out |> String.trim() |> String.split("\n") |> Enum.join(" | "))
    end
  end
end

FakeCli.main(System.argv())
