defmodule Toolnexus.CliTest do
  @moduledoc """
  Hermetic tests for the CLI client (SPEC §8C), mirroring golang/cli_test.go:
  no network, no real agent CLI. `test/support/fake_cli.exs` plays the fake
  ACP agent, the fake one-shot CLI and the stdio MCP bridge.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Cli

  @moduletag timeout: 120_000

  @root Path.expand("../..", __DIR__)
  @styles_dir Path.join(@root, "conformance/cli-styles")
  @cases Path.join(@root, "conformance/cli-client/cases.json")
  @skills Path.join(@root, "examples/skills")
  @fake Path.expand("support/fake_cli.exs", __DIR__)

  # ---------------------------------------------------------------------------
  # Helpers.
  # ---------------------------------------------------------------------------

  # A sh wrapper that runs the fake with every _build ebin on the code path —
  # one executable path, like the Go test binary.
  defp wrapper! do
    key = {__MODULE__, :wrapper}

    case :persistent_term.get(key, nil) do
      nil ->
        ebins = Path.wildcard(Path.join(Mix.Project.build_path(), "lib/*/ebin"))
        pa = Enum.map_join(ebins, " ", &("-pa '" <> &1 <> "'"))

        path =
          Path.join(System.tmp_dir!(), "toolnexus-fake-cli-#{System.unique_integer([:positive])}")

        File.write!(path, "#!/bin/sh\nexec elixir #{pa} '#{@fake}' \"$@\"\n")
        File.chmod!(path, 0o755)
        :persistent_term.put(key, path)
        path

      path ->
        path
    end
  end

  defp tmp_file(name) do
    dir = Path.join(System.tmp_dir!(), "tn-cli-test-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    on_exit(fn -> File.rm_rf(dir) end)
    Path.join(dir, name)
  end

  defp badge_toolkit do
    badge =
      Toolnexus.define_tool(
        name: "get_badge_number",
        description: "Returns the badge number.",
        input_schema: %{"type" => "object", "properties" => %{}},
        execute: fn _ -> "BADGE-77Q" end
      )

    Toolnexus.create_toolkit!(extra_tools: [badge], builtins: false, skills_dir: [@skills])
  end

  defp fake_cli_opts(mode, extra \\ []) do
    [
      style: :custom,
      command: wrapper!(),
      args: extra,
      prompt: "stdin",
      answer: "stdout",
      env: %{"TN_FAKE_CLI" => mode},
      transport: :cli
    ]
  end

  defp create!(opts) do
    {:ok, cli} = Cli.create_client(opts)
    on_exit(fn -> Cli.close(cli) end)
    cli
  end

  defp run_cli(opts, tk) do
    cli = create!(opts)
    {:ok, Cli.run(cli, "What is my badge number?", tk)}
  rescue
    e -> {:error, Exception.message(e)}
  end

  # ---------------------------------------------------------------------------
  # Shared data: styles and preamble are byte-identical to conformance/.
  # ---------------------------------------------------------------------------

  test "embedded styles + preamble are byte-identical to conformance/cli-styles (drift)" do
    files =
      @styles_dir
      |> File.ls!()
      |> Enum.reject(&(&1 == "README.md" or File.dir?(Path.join(@styles_dir, &1))))

    assert length(files) == 8, "expected 7 styles + preamble, found #{length(files)}"
    embedded = Cli.embedded_files()

    for f <- files do
      assert Map.get(embedded, f) == File.read!(Path.join(@styles_dir, f)),
             "elixir/lib/toolnexus/cli/styles/#{f} differs from conformance/cli-styles (copy it)"
    end

    assert Cli.preamble() == File.read!(Path.join(@styles_dir, "preamble.txt"))

    for s <- Cli.styles() -- [:custom] do
      {:ok, d} = Cli.style_def(s)
      assert d["name"] == Atom.to_string(s)
      {:ok, elems} = Cli.normalize_args(d["args"])
      assert Cli.validate_style(d, elems, "") == :ok
      assert d["verified"] not in [nil, ""]
    end

    assert {:ok, %{"acp" => nil}} =
             with({:ok, d} <- Cli.style_def(:codex), do: {:ok, Map.put_new(d, "acp", nil)})

    assert {:ok, %{"acp" => %{"projectMcpFile" => ".mcp.json"}}} = Cli.style_def(:devin)
    {:ok, goose} = Cli.style_def(:goose)
    assert String.starts_with?(goose["verified"], "UNVERIFIED")
    assert Cli.style_def(:custom) == :error
    assert Cli.style_def("claude") == :error
    assert Cli.transports() == [:auto, :acp, :cli]
  end

  test "load_style reads JSON text or a file" do
    assert {:ok, %{"name" => "mine"}} = Cli.load_style(~s({"name":"mine"}))
    path = tmp_file("style.json")
    File.write!(path, ~s({"name":"filed","args":[],"prompt":"stdin","answer":"stdout"}))
    assert {:ok, %{"name" => "filed"} = d} = Cli.load_style(path)
    assert {:ok, _} = Cli.create_client(style_def: d, command: "x")
    assert {:error, "cli style: " <> _} = Cli.load_style("/no/such/style.json")
    assert {:error, "cli style: " <> _} = Cli.load_style("{not json")
    File.write!(path, "[1]")
    assert {:error, "cli style: not a JSON object"} = Cli.load_style(path)
  end

  # ---------------------------------------------------------------------------
  # Construction errors.
  # ---------------------------------------------------------------------------

  test "construction errors are the pinned strings" do
    cases = [
      {[style: :claude], "cli: Command is required (path or name of the CLI binary)"},
      {[command: "x"], "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"},
      {[style: "claude", command: "x"],
       "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"},
      {[style: :goose, command: "goose", model: "m", transport: :cli],
       ~s(cli: style "goose" takes no model)},
      {[style: :codex, command: "codex", transport: :acp],
       ~s(cli: style "codex" has no ACP launch; set ACPCommand or use CliTransports.CLI)},
      {[style: :custom, command: "x", args: [1], prompt: "stdin", answer: "stdout"],
       "cli: an arg must be a string or a group of strings"},
      {[style: :custom, command: "x", args: [["a", 1]], prompt: "stdin", answer: "stdout"],
       "cli: a group may only hold strings"},
      {[style: :claude, command: "x", transport: "cli"],
       "cli: transport must be one of :auto, :acp, :cli, not \"cli\""}
    ]

    for {o, want} <- cases do
      assert Cli.create_client(o) == {:error, want}
    end

    assert_raise ArgumentError, "cli: Command is required (path or name of the CLI binary)", fn ->
      Cli.create_client!(style: :claude)
    end

    assert %Cli{} = cli = Cli.create_client!(style: :claude, command: "claude")
    Cli.close(cli)
  end

  # ---------------------------------------------------------------------------
  # The envelope parser.
  # ---------------------------------------------------------------------------

  test "parse_reply: the tolerant six-step envelope parser" do
    assert Cli.parse_reply(~s({"content":"hi"})) == %{content: "hi"}
    assert Cli.parse_reply("```json\n{\"content\":\"fenced\"}\n```\n\n") == %{content: "fenced"}
    assert Cli.parse_reply("```") == %{content: "```"}
    assert Cli.parse_reply(~s(Sure! {"content":"wrapped"} done)) == %{content: "wrapped"}

    assert Cli.parse_reply(~s({"choices":[{"message":{"content":"openai"}}]})) == %{
             content: "openai"
           }

    assert Cli.parse_reply(~s({"choices":[{"x":1}],"content":"c"})) == %{content: "c"}
    assert Cli.parse_reply(~s({"message":{"content":"msg"}})) == %{content: "msg"}
    assert Cli.parse_reply(~s({"content":{"a":1}})) == %{content: ~s({"a":1})}

    assert %{tool_calls: [%{id: "a", name: "t", arguments: ~s({"x":1})}]} =
             Cli.parse_reply(
               ~s({"tool_calls":[{"id":"a","function":{"name":"t","arguments":"{\\"x\\":1}"}}]})
             )

    assert %{tool_calls: [%{name: "flat", arguments: %{"x" => 1}} = c]} =
             Cli.parse_reply(~s({"tool_calls":[{"name":"flat","arguments":{"x":1}}]}))

    refute Map.has_key?(c, :id)

    assert %{tool_calls: [%{name: "n", arguments: %{}}]} =
             Cli.parse_reply(~s({"tool_calls":[7,{"name":""},{"name":"n"}]}))

    assert Cli.parse_reply("plain text answer") == %{content: "plain text answer"}
    assert Cli.parse_reply("} {") == %{content: "} {"}
  end

  # ---------------------------------------------------------------------------
  # One-shot transport.
  # ---------------------------------------------------------------------------

  test "one-shot: the toolnexus loop runs the tool the CLI asked for" do
    rec = tmp_file("rec")
    tk = badge_toolkit()

    opts =
      fake_cli_opts("tool")
      |> Keyword.put(:env, %{"TN_FAKE_CLI" => "tool", "TN_FAKE_REC" => rec})
      |> Keyword.put(:system_prompt, "You are a badge clerk.")

    {:ok, res} = run_cli(opts, tk)
    assert res.text =~ "BADGE-77Q"
    assert [%{name: "get_badge_number"}] = res.tool_calls

    [first | _] = rec |> File.read!() |> String.split("\n=====\n")
    assert String.starts_with?(first, Cli.preamble() <> "\nREQUEST:\n")
    [_, json] = String.split(first, "\nREQUEST:\n", parts: 2)
    req = Jason.decode!(json)
    [m0 | _] = req["messages"]
    assert m0["role"] == "system"
    assert m0["content"] =~ "badge clerk"
    assert req["tools"] != []
  end

  test "one-shot: prompt modes arg/file and the out file" do
    tk = badge_toolkit()
    temps = fn -> MapSet.new(Path.wildcard(Path.join(System.tmp_dir!(), "toolnexus-cli-*"))) end
    before = temps.()

    for {args, prompt, answer} <- [
          {["--p", "{{prompt}}"], "arg", "stdout"},
          {["--f", "{{file}}"], "file", "stdout"},
          {["--o", "{{out}}"], "stdin", "out"}
        ] do
      opts = fake_cli_opts("tool", args) |> Keyword.merge(prompt: prompt, answer: answer)
      assert {:ok, res} = run_cli(opts, tk)
      assert res.text =~ "BADGE-77Q", "#{prompt}/#{answer}"
    end

    # no temp file outlives its call
    assert MapSet.difference(temps.(), before) == MapSet.new()
  end

  test "one-shot: the optional model group is kept only when a model is set" do
    opts = fake_cli_opts("x", ["--argv", "-q", ["--model", "{{model}}"]])
    {:ok, res} = run_cli(opts, nil)
    refute res.text =~ "--model"
    {:ok, res} = run_cli(Keyword.put(opts, :model, "prov/m-1"), nil)
    assert res.text =~ "--model prov/m-1"
  end

  test "one-shot: env values expand ${VAR} from this process" do
    System.put_env("TN_CLI_TEST_MODE", "tool")
    on_exit(fn -> System.delete_env("TN_CLI_TEST_MODE") end)
    opts = fake_cli_opts("x") |> Keyword.put(:env, %{"TN_FAKE_CLI" => "${TN_CLI_TEST_MODE}"})
    {:ok, res} = run_cli(opts, badge_toolkit())
    assert res.text =~ "BADGE-77Q"
  end

  test "one-shot: pinned errors" do
    cases = [
      {fake_cli_opts("exit"), "cli: exit 3: boom: bad login"},
      {fake_cli_opts("empty"), "cli: empty reply"},
      {fake_cli_opts("nooutfile", ["--x", "{{out}}"]) |> Keyword.put(:answer, "out"),
       "cli: no output file"},
      {fake_cli_opts("sleep") |> Keyword.put(:call_timeout_ms, 1500),
       "cli: timed out after 1500ms"},
      {fake_cli_opts("x") |> Keyword.put(:command, "definitely-not-a-cli-xyz"),
       "cli: command not found: definitely-not-a-cli-xyz"},
      {fake_cli_opts("x") |> Keyword.put(:command, "/no/such/dir/cli"),
       "cli: command not found: /no/such/dir/cli"}
    ]

    for {o, want} <- cases do
      assert {:error, msg} = run_cli(o, nil)
      assert msg =~ want
    end
  end

  test "one-shot: a long stderr is cut at 1000 code points" do
    script = tmp_file("loud.sh")
    File.write!(script, "#!/bin/sh\nprintf 'é%.0s' $(seq 1 1500) >&2\nexit 4\n")
    File.chmod!(script, 0o755)

    opts = [
      style: :custom,
      command: script,
      args: [],
      prompt: "stdin",
      answer: "stdout",
      transport: :cli
    ]

    assert {:error, "cli: exit 4: " <> rest} = run_cli(opts, nil)
    assert String.length(rest) == 1001
    assert String.ends_with?(rest, "…")

    File.write!(script, "#!/bin/sh\nexit 5\n")
    assert {:error, "cli: exit 5"} = run_cli(opts, nil)
  end

  test "one-shot: cwd is the child's working directory" do
    dir = Path.dirname(tmp_file("x"))
    script = Path.join(dir, "pwd.sh")
    File.write!(script, "#!/bin/sh\nprintf '{\"content\":\"%s\"}' \"$(pwd -P)\"\n")
    File.chmod!(script, 0o755)

    opts = [
      style: :custom,
      command: script,
      args: [],
      prompt: "stdin",
      answer: "stdout",
      transport: :cli,
      cwd: dir
    ]

    {:ok, res} = run_cli(opts, nil)
    {real, 0} = System.cmd("sh", ["-c", "cd '#{dir}' && pwd -P"])
    assert res.text == String.trim(real)
  end

  defp collect_stream(cli, tk) do
    cli
    |> Cli.stream("What is my badge number?", tk)
    |> Enum.to_list()
  end

  defp stream_text(events),
    do: events |> Enum.filter(&(&1.type == "text")) |> Enum.map_join(& &1.delta)

  test "one-shot streams non-incrementally: tool_call, tool_result, one text, done" do
    tk = badge_toolkit()
    cli = create!(fake_cli_opts("tool"))
    events = collect_stream(cli, tk)
    types = Enum.map(events, & &1.type)

    refute "error" in types
    assert List.last(types) == "done"
    assert Enum.count(types, &(&1 == "text")) == 1
    i_call = Enum.find_index(types, &(&1 == "tool_call"))
    i_res = Enum.find_index(types, &(&1 == "tool_result"))
    i_text = Enum.find_index(types, &(&1 == "text"))
    assert i_call < i_res and i_res < i_text
    assert stream_text(events) =~ "BADGE-77Q"
    assert List.last(events).result.text =~ "BADGE-77Q"
  end

  test "transport is cli from construction when the style has no ACP launch" do
    cli = create!(style: :codex, command: "codex")
    assert Cli.transport(cli) == "cli"
    assert Cli.fallback_reason(cli) == ""
    assert Cli.acp_session(cli) == :error
  end

  # ---------------------------------------------------------------------------
  # ACP transport.
  # ---------------------------------------------------------------------------

  defp fake_acp_opts(env, extra \\ []) do
    rec = tmp_file("rec")

    d = %{
      "name" => "fake",
      "args" => [],
      "prompt" => "stdin",
      "answer" => "stdout",
      "acp" => Map.merge(%{"args" => ["acp"]}, Keyword.get(extra, :acp, %{}))
    }

    opts = [
      style_def: d,
      command: wrapper!(),
      env: Map.merge(%{"TN_FAKE_ACP" => "1", "TN_FAKE_REC" => rec}, env),
      toolkit: badge_toolkit(),
      bridge_command: [wrapper!(), "mcp-bridge"]
    ]

    {opts, rec}
  end

  defp assert_fake_acp_run(cli, res, entry) do
    assert Cli.transport(cli) == "acp", Cli.fallback_reason(cli)

    for want <- [
          "perm ours=yes own=no fake=no again=yes",
          "entry=" <> entry,
          "BADGE-77Q",
          "Hello"
        ] do
      assert res.text =~ want
    end

    assert res.text =~ ~s("name":"load_skill")
    refute res.text =~ ~s("name":"skill")
    {:ok, info} = Cli.acp_session(cli)
    assert length(info.served_calls) == 2
    info
  end

  test "ACP: http entry with a bearer token; 401 without it" do
    {opts, rec} = fake_acp_opts(%{"FAKE_HTTP" => "1"})
    cli = create!(opts)
    assert Cli.transport(cli) == "auto"
    res = Cli.run(cli, "badge?")
    info = assert_fake_acp_run(cli, res, "http")
    assert res.text =~ "noauth=401"
    sent = File.read!(rec)
    assert sent =~ ~s("headers":[{"name":"Authorization","value":"Bearer )
    assert sent =~ ~s("terminal":false)
    refute inspect(info) =~ "Bearer "

    assert [%{"type" => "http", "headers" => [%{"value" => "[redacted]"}]}] =
             info.mcp_servers_sent

    assert Enum.any?(info.permissions, &(&1.allowed and &1.tool_call_id == "tc1"))

    # the endpoint 404s other paths
    url = String.replace_suffix(info.url, "/mcp", "/other")
    assert {:ok, %{status: 404}} = Req.post(url, body: "{}", retry: false)
  end

  test "ACP: stdio bridge entry when the agent lacks http" do
    {opts, rec} = fake_acp_opts(%{"FAKE_HTTP" => "0"})
    cli = create!(opts)
    res = Cli.run(cli, "badge?")
    assert_fake_acp_run(cli, res, "<nil>")
    sent = File.read!(rec)
    assert sent =~ ~s("env":[{"name":"TOOLNEXUS_MCP_TOKEN")
    refute sent =~ ~s("type":"stdio")
  end

  test "ACP: project MCP file in a temp cwd, removed with the server on close" do
    {opts, _} =
      fake_acp_opts(%{"FAKE_HTTP" => "0", "FAKE_PROJECT" => "1"},
        acp: %{"projectMcpFile" => ".mcp.json"}
      )

    {:ok, cli} = Cli.create_client(opts)
    res = Cli.run(cli, "badge?")
    info = assert_fake_acp_run(cli, res, "http")
    assert info.mcp_servers_sent == []
    assert is_binary(info.session_cwd)
    file = Path.join(info.session_cwd, ".mcp.json")
    assert File.exists?(file)
    assert %File.Stat{mode: mode} = File.stat!(file)
    assert Bitwise.band(mode, 0o777) == 0o600
    :ok = Cli.close(cli)
    :ok = Cli.close(cli)
    refute File.exists?(info.session_cwd)
    assert {:error, _} = Req.post(info.url, body: "{}", retry: false)
    assert_raise RuntimeError, "cli: client is closed", fn -> Cli.run(cli, "again?") end
  end

  test "ACP: model goes to set_config_option, then set_model, unchanged" do
    {opts, rec} = fake_acp_opts(%{"FAKE_HTTP" => "1", "FAKE_NO_CONFIG" => "1"})
    test_pid = self()

    opts =
      opts
      |> Keyword.merge(model: "anthropic/claude-x", transport: :acp)
      |> Keyword.put(:on_acp_update, fn p -> send(test_pid, {:update, p}) end)

    cli = create!(opts)
    Cli.run(cli, "badge?")
    s = File.read!(rec)

    assert s =~
             ~s(session/set_config_option {"configId":"model","sessionId":"s1","value":"anthropic/claude-x"})

    assert s =~ ~s(session/set_model {"modelId":"anthropic/claude-x","sessionId":"s1"})
    assert_received {:update, %{"update" => %{"sessionUpdate" => "tool_call"}}}
  end

  test "ACP: Auto falls back to one-shot when the agent fails to start; forced ACP fails" do
    {opts, _} = fake_acp_opts(%{"FAKE_FAIL_INIT" => "1", "TN_FAKE_CLI" => "tool"})
    cli = create!(opts)
    assert Cli.transport(cli) == "auto"
    res = Cli.run(cli, "badge?", Keyword.fetch!(opts, :toolkit))
    assert Cli.transport(cli) == "cli"
    assert Cli.fallback_reason(cli) =~ "cli: acp: initialize"
    assert Cli.fallback_reason(cli) =~ "unknown flag: acp"
    assert res.text =~ "BADGE-77Q"

    {opts2, _} = fake_acp_opts(%{"FAKE_FAIL_INIT" => "1"})
    cli2 = create!(Keyword.put(opts2, :transport, :acp))
    assert_raise RuntimeError, ~r/^cli: acp: /, fn -> Cli.run(cli2, "badge?") end

    # command missing: a launch error too
    cli3 =
      create!(Keyword.merge(opts2, transport: :acp, acp_command: ["definitely-not-an-agent-xyz"]))

    assert_raise RuntimeError, "cli: command not found: definitely-not-an-agent-xyz", fn ->
      Cli.run(cli3, "x")
    end
  end

  test "ACP: acp_command replaces the style's ACP launch" do
    {opts, _} = fake_acp_opts(%{"FAKE_HTTP" => "1"})

    cli =
      create!(Keyword.merge(opts, transport: :acp, model: "m", acp_command: [wrapper!(), "acp"]))

    res = Cli.run(cli, "badge?")
    assert res.text =~ "perm ours=yes"
  end

  test "served_call?: exact title forms only" do
    served = MapSet.new(["get_badge_number", "load_skill"])

    for {title, want} <- [
          {"get_badge_number", true},
          {"mcp__toolnexus__get_badge_number", true},
          {"toolnexus/get_badge_number", true},
          {"get_badge_number (toolnexus MCP Server)", true},
          {"Calling get_badge_number from toolnexus", true},
          {"load_skill(name: secret-recipe)", true},
          {"skill", false},
          {"Bash", false},
          {"", false},
          {nil, false},
          {"mcp__other__get_badge_number", false},
          {"run get_badge_number and then rm -rf", false},
          {"Calling get_badge_number from toolnexus2", false},
          {"get_badge_number (evil MCP Server)", false},
          {"mcp__toolnexus__get_badge_number_and_more", false}
        ] do
      assert Cli.served_call?(title, served) == want, inspect(title)
    end
  end

  test "ACP: a style's systemHint is appended to the turn's system prompt" do
    {:ok, devin} = Cli.style_def(:devin)
    assert devin["acp"]["systemHint"] =~ ~s("toolnexus")

    {opts, rec} =
      fake_acp_opts(%{"FAKE_HTTP" => "1"}, acp: %{"systemHint" => "HINT-USE-TOOLNEXUS"})

    cli = create!(Keyword.put(opts, :system_prompt, "You are terse."))
    Cli.run(cli, "badge?")
    assert File.read!(rec) =~ ~S(You are terse.\n\nHINT-USE-TOOLNEXUS)

    assert [%{"role" => "system", "content" => "H"}, _] =
             Cli.with_system_hint([%{"role" => "user", "content" => "x"}], "H")

    assert [%{"role" => "system", "content" => "H"}] = Cli.with_system_hint([], "H")

    assert [%{role: "system", content: "S\n\nH"}] =
             Cli.with_system_hint([%{role: "system", content: "S"}], "H")

    assert [%{"content" => "H"}] =
             Cli.with_system_hint([%{"role" => "system", "content" => ""}], "H")

    msgs = [%{"role" => "system", "content" => "S"}]
    assert Cli.with_system_hint(msgs, nil) == msgs
    _ = Cli.with_system_hint(msgs, "H")
    assert msgs == [%{"role" => "system", "content" => "S"}]
  end

  test "ACP: hooks run at the server boundary" do
    tk = badge_toolkit()
    test_pid = self()

    hooks = %{
      before_tool: fn %{name: n} = ev ->
        send(test_pid, {:before, n})
        if n == "get_badge_number", do: %{args: ev.args}, else: nil
      end,
      after_tool: fn %{name: n, result: r} ->
        send(test_pid, {:after, n})
        if n == "get_badge_number", do: %{result: %{r | output: r.output <> "-HOOKED"}}
      end
    }

    [badge | _] =
      Toolnexus.Cli.AcpSession.served_tools(tk, hooks, fn n -> send(test_pid, {:ran, n}) end)
      |> Enum.filter(&(&1.name == "get_badge_number"))

    assert %Toolnexus.ToolResult{output: "BADGE-77Q-HOOKED"} =
             badge.execute.(%{}, %Toolnexus.Context{})

    assert_received {:before, "get_badge_number"}
    assert_received {:ran, "get_badge_number"}
    assert_received {:after, "get_badge_number"}

    deny = %{before_tool: fn _ -> %{result: Toolnexus.ToolResult.error("denied")} end}
    [t | _] = Toolnexus.Cli.AcpSession.served_tools(tk, deny, fn _ -> :ok end)

    assert %Toolnexus.ToolResult{output: "denied", is_error: true} =
             t.execute.(%{}, %Toolnexus.Context{})

    boom = %{before_tool: fn _ -> raise "nope" end, after_tool: fn _ -> raise "late" end}
    [t | _] = Toolnexus.Cli.AcpSession.served_tools(tk, boom, fn _ -> :ok end)

    assert %Toolnexus.ToolResult{output: "nope", is_error: true} =
             t.execute.(%{}, %Toolnexus.Context{})

    after_boom = %{after_tool: fn _ -> raise "late" end}
    [t | _] = Toolnexus.Cli.AcpSession.served_tools(tk, after_boom, fn _ -> :ok end)

    assert %Toolnexus.ToolResult{output: "late", is_error: true} =
             t.execute.(%{}, %Toolnexus.Context{})

    skill =
      Enum.find(
        Toolnexus.Cli.AcpSession.served_tools(tk, nil, fn _ -> :ok end),
        &(&1.source == "skill")
      )

    assert skill.name == "load_skill"
    assert skill.description =~ "hello-world"
    assert Toolnexus.Cli.AcpSession.served_tools(nil, nil, nil) == []
  end

  test "run_mcp_bridge relays JSON-RPC lines to the served endpoint" do
    {opts, _} = fake_acp_opts(%{"FAKE_HTTP" => "1"})
    cli = create!(opts)
    Cli.run(cli, "badge?")
    {:ok, info} = Cli.acp_session(cli)

    {:ok, input} =
      StringIO.open(~s({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}\n\n))

    {:ok, output} = StringIO.open("")

    assert {:error, "mcp-bridge: unauthorized" <> _} =
             Cli.run_mcp_bridge(info.url, nil, input, output)

    {:ok, input} = StringIO.open(~s(not-json\n))
    {:ok, output} = StringIO.open("")
    assert {:error, _} = Cli.run_mcp_bridge("http://127.0.0.1:1/mcp", "t", input, output)
  end

  # ---------------------------------------------------------------------------
  # conformance/cli-client/cases.json — the fixture every port replays.
  # ---------------------------------------------------------------------------

  test "replays conformance/cli-client/cases.json" do
    fx = @cases |> File.read!() |> Jason.decode!()
    by_name = Map.new(Cli.styles(), &{Atom.to_string(&1), &1})

    for c <- fx["argv"] do
      {:ok, cfg} =
        Cli.configure(
          style: by_name[c["style"]],
          command: c["command"],
          transport: :cli,
          model: c["model"]
        )

      {argv, out, tmp} = Cli.render_argv(cfg, c["prompt"])
      Enum.each(tmp, &File.rm/1)

      argv =
        Enum.map(argv, fn a ->
          cond do
            out != nil and a == out -> "<out>"
            tmp != [] and a == hd(tmp) -> "<file>"
            true -> a
          end
        end)

      assert argv == c["argv"], "#{c["style"]} model=#{inspect(c["model"])}"
      assert cfg.style["prompt"] == "stdin" == c["stdin"]
    end

    norm = fn
      s when is_binary(s) ->
        case Jason.decode(s) do
          {:ok, v} -> v
          _ -> s
        end

      v ->
        v
    end

    for c <- fx["parse"] do
      r = Cli.parse_reply(c["in"])

      case c["toolCall"] do
        nil ->
          assert r == %{content: c["content"]}, inspect(c["in"])

        tc ->
          assert [call] = r.tool_calls
          assert call.name == tc["name"]
          assert Map.get(call, :id, "") == (tc["id"] || "")
          assert norm.(call.arguments) == tc["arguments"]
      end
    end

    for c <- fx["constructionErrors"] do
      o = c["options"]

      opts =
        [
          style: if(o["style"], do: by_name[o["style"]]),
          command: o["command"],
          model: o["model"],
          prompt: o["prompt"],
          answer: o["answer"],
          args: o["args"],
          transport: %{"cli" => :cli, "acp" => :acp}[o["transport"]] || :auto
        ]

      assert Cli.create_client(opts) == {:error, c["error"]}, inspect(o)
    end

    assert Cli.render_prompt(nil, nil) ==
             String.replace(fx["renderEmpty"], "<PREAMBLE>", Cli.preamble(), global: false)
  end

  test "Acp.wire_server picks the shape; string-keyed entries pass verbatim" do
    e = %{
      name: "t",
      url: "http://u",
      headers: %{"A" => "1"},
      command: "c",
      args: ["x"],
      env: %{"B" => 2}
    }

    assert %{"type" => "http", "headers" => [%{"name" => "A", "value" => "1"}]} =
             Toolnexus.Acp.wire_server(e, true)

    stdio = Toolnexus.Acp.wire_server(e, false)
    refute Map.has_key?(stdio, "type")
    assert stdio["env"] == [%{"name" => "B", "value" => "2"}]

    assert %{"args" => [], "env" => []} =
             Toolnexus.Acp.wire_server(%{name: "n", command: "c"}, true)

    assert Toolnexus.Acp.wire_server(%{"name" => "raw"}, true) == %{"name" => "raw"}
  end

  test "the client state stops and closes its session when the owner exits" do
    {opts, _} = fake_acp_opts(%{"FAKE_HTTP" => "1"})
    parent = self()

    {pid, ref} =
      spawn_monitor(fn ->
        {:ok, cli} = Cli.create_client(opts)
        Cli.run(cli, "badge?")
        {:ok, info} = Cli.acp_session(cli)
        send(parent, {:cli, cli, info})
      end)

    assert_receive {:cli, cli, info}, 60_000
    assert_receive {:DOWN, ^ref, :process, ^pid, _}
    Process.sleep(300)
    refute Process.alive?(cli.server)
    assert {:error, _} = Req.post(info.url, body: "{}", retry: false)
    assert Cli.close(cli) == :ok
  end

  test "Proc helpers tolerate missing pids" do
    assert Toolnexus.Cli.Proc.descendants(nil) == []
    assert Toolnexus.Cli.Proc.kill_tree(nil) == :ok

    port =
      Port.open({:spawn_executable, "/bin/sh"}, [:binary, args: ["-c", "sleep 30 & sleep 30"]])

    {:os_pid, os} = Port.info(port, :os_pid)
    Process.sleep(100)
    assert Toolnexus.Cli.Proc.descendants(os) != []
    assert Toolnexus.Cli.Proc.kill_tree(os) == :ok
  end

  defmodule BridgeEcho do
    @moduledoc false
    import Plug.Conn
    def init(o), do: o

    def call(conn, _) do
      {:ok, body, conn} = read_body(conn)
      conn = put_resp_header(conn, "mcp-session-id", "sess-9")

      if String.contains?(body, "sse") do
        conn
        |> put_resp_content_type("text/event-stream")
        |> send_resp(200, "event: message\ndata: {\"sse\":1}\r\n\n")
      else
        conn |> put_resp_content_type("application/json") |> send_resp(200, body)
      end
    end
  end

  test "run_mcp_bridge writes JSON and SSE replies as lines" do
    {:ok, pid} =
      Bandit.start_link(plug: BridgeEcho, ip: {127, 0, 0, 1}, port: 0, startup_log: false)

    {:ok, {_, port}} = ThousandIsland.listener_info(pid)
    {:ok, input} = StringIO.open(~s({"a":1}\n{"sse":true}\n))
    {:ok, output} = StringIO.open("")
    assert :ok = Cli.run_mcp_bridge("http://127.0.0.1:#{port}/mcp", "tok", input, output)
    {_, out} = StringIO.contents(output)
    assert out == ~s({"a":1}\n{"sse":1}\n)
  end

  test "Acp on_permission returning nil answers with a reject option" do
    script = Path.expand("support/fake_acp_server.exs", __DIR__)
    jason = Path.join(Mix.Project.build_path(), "lib/jason/ebin")

    {:ok, pid} =
      Toolnexus.Acp.connect(["elixir", "-pa", jason, script, "permission"],
        on_permission: fn _ -> nil end
      )

    assert {:ok, "PERMITTED:" <> _} = Toolnexus.Acp.prompt(pid, "x")
    Toolnexus.Acp.close(pid)
  end

  test "ACP streams the answer as text (possibly several deltas), then done" do
    {opts, _rec} = fake_acp_opts(%{"FAKE_HTTP" => "1"})
    cli = create!(opts)
    events = collect_stream(cli, opts[:toolkit])
    types = Enum.map(events, & &1.type)

    refute "error" in types
    assert List.last(types) == "done"
    assert Cli.transport(cli) == "acp"
    assert stream_text(events) =~ "BADGE-77Q"
  end

  test "stream every style over both transports without error" do
    for st <- Cli.styles(), tr <- [:cli, :acp] do
      tk = badge_toolkit()

      opts = [
        style: st,
        command: wrapper!(),
        transport: tr,
        toolkit: tk,
        env: %{"TN_FAKE_CLI" => "tool"}
      ]

      opts =
        if st == :custom,
          do: Keyword.merge(opts, args: [], prompt: "stdin", answer: "stdout"),
          else: opts

      opts =
        if tr == :acp do
          {fo, _} = fake_acp_opts(%{"FAKE_HTTP" => "1"})

          Keyword.merge(opts,
            env: fo[:env],
            bridge_command: fo[:bridge_command],
            acp_command: [wrapper!(), "acp"]
          )
        else
          opts
        end

      cli = create!(opts)
      events = collect_stream(cli, tk)
      types = Enum.map(events, & &1.type)
      label = "#{st}/#{tr}"

      refute "error" in types, label
      assert List.last(types) == "done", label
      assert stream_text(events) =~ "BADGE-77Q", label
      if tr == :cli, do: assert("tool_call" in types, label)
      assert Cli.transport(cli) == Atom.to_string(tr), label
    end
  end

  test "ACP streams incrementally (conformance stream.acpDrip)" do
    fx = @cases |> File.read!() |> Jason.decode!() |> get_in(["stream", "acpDrip"])
    {opts, _rec} = fake_acp_opts(fx["fakeEnv"])
    cli = create!(opts)

    events =
      cli
      |> Cli.stream("drip please", opts[:toolkit])
      |> Enum.map(&Map.put(&1, :at, System.monotonic_time(:millisecond)))

    refute Enum.any?(events, &(&1.type == "error"))
    assert Cli.transport(cli) == "acp"
    texts = Enum.filter(events, &(&1.type == "text"))
    assert Enum.map(texts, & &1.delta) == fx["chunks"]

    thoughts = Enum.filter(events, &(&1.type == "thought"))
    assert [%{delta: d, via: via}] = thoughts
    assert d == fx["expectThought"]["delta"] and via == fx["expectThought"]["via"]

    done = List.last(events)
    assert done.type == "done"
    assert done.result.text == fx["expectDoneText"]
    lead = done.at - hd(texts).at

    assert lead >= fx["minLeadMs"],
           "first text delta only #{lead}ms before done — stream is buffered"

    assert Cli.run(cli, "drip again", opts[:toolkit]).text == fx["expectDoneText"]
  end
end
