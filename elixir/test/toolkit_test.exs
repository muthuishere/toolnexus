defmodule Toolnexus.ToolkitTest do
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  alias Toolnexus.{Builtin, Context, Native, Tool, Toolkit, ToolResult}

  @moduletag timeout: 60_000

  @repo_root Path.expand("../..", __DIR__)
  @examples_skills Path.join(@repo_root, "examples/skills")
  @fixture_script Path.expand("support/stdio_mcp_server.exs", __DIR__)

  defp fixture_cmd do
    jason_ebin = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    ["elixir", "-pa", jason_ebin, @fixture_script]
  end

  defp mcp_config do
    %{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}}
  end

  defp os_alive?(os_pid) do
    match?({_, 0}, System.cmd("kill", ["-0", Integer.to_string(os_pid)], stderr_to_stdout: true))
  end

  defp wait_until(fun, timeout_ms \\ 5_000) do
    deadline = System.monotonic_time(:millisecond) + timeout_ms

    Stream.repeatedly(fn ->
      cond do
        fun.() -> true
        System.monotonic_time(:millisecond) > deadline -> false
        true -> Process.sleep(50) && nil
      end
    end)
    |> Enum.find(&(&1 != nil))
  end

  defp names(tk), do: Enum.map(Toolkit.tools(tk), & &1.name)

  defp tool(name, output \\ nil) do
    Native.define_tool(
      name: name,
      description: "test tool #{name}",
      execute: fn _args -> output || "out:#{name}" end
    )
  end

  # --- conformance against the shared examples/ fixtures (skills only) ---------

  test "create_toolkit with the shared examples/skills: skill tool + prompt + builtins on by default" do
    {:ok, tk} = Toolnexus.create_toolkit(skills_dir: [@examples_skills])

    # skill tool first, then the ten builtins (no mcp / agents / extras).
    assert names(tk) == [
             "skill"
             | Enum.map(Builtin.tools(), & &1.name)
           ]

    assert length(Toolkit.tools(tk)) == 11

    prompt = Toolkit.skills_prompt(tk)
    assert prompt =~ "## Available Skills"
    assert prompt =~ "- **hello-world**: A tiny example skill."
    # the struct exposes :prompt for the client loop
    assert tk.prompt == prompt

    result = Toolkit.execute(tk, "skill", %{"name" => "hello-world"})
    refute result.is_error
    assert result.output =~ ~s(<skill_content name="hello-world">)
    assert result.output =~ "Base directory for this skill: file://"

    assert Toolkit.mcp_status(tk) == %{}
    assert Toolkit.close(tk) == :ok
  end

  test "create_toolkit! returns the toolkit; create_toolkit wraps a build failure" do
    tk = Toolnexus.create_toolkit!(skills_dir: [@examples_skills])
    assert %Toolkit{} = tk

    # bad config file path raises inside the build → {:error, e}
    assert {:error, %File.Error{}} = Toolnexus.create_toolkit(mcp_config: "/nonexistent/mcp.json")
  end

  # --- assembly order + dedupe (SPEC §4) ----------------------------------------

  test "assembly order is MCP → skill → builtin → agents → extras with first-wins dedupe" do
    log =
      capture_log(fn ->
        tk =
          Toolnexus.create_toolkit!(
            mcp_config: mcp_config(),
            skills_dir: [@examples_skills],
            extra_tools: [tool("mine"), tool("skill", "shadowed-skill")]
          )

        send(self(), {:tk, tk})
      end)

    assert_received {:tk, tk}

    mcp_names = ~w(fixture_echo fixture_structured fixture_fail fixture_getenv fixture_elicit)
    builtin_names = Enum.map(Builtin.tools(), & &1.name)
    assert names(tk) == mcp_names ++ ["skill"] ++ builtin_names ++ ["mine"]

    # duplicate "skill" from extras lost to the skill source (first wins) + warned
    assert log =~ ~s(duplicate tool name "skill" — keeping first)
    assert Toolkit.get(tk, "skill").source == "skill"

    # mcp status + live routing through execute
    assert Toolkit.mcp_status(tk) == %{"fixture" => "connected"}
    result = Toolkit.execute(tk, "fixture_echo", %{"text" => "hi"}, %Context{})
    refute result.is_error
    assert result.output == "echo: hi"

    # close tears the stdio child down
    {:ok, conn} = Map.fetch(tk.mcp.connections, "fixture")
    {:ok, os_pid} = Toolnexus.Mcp.Connection.os_pid(conn)
    assert os_alive?(os_pid)
    assert Toolkit.close(tk) == :ok
    assert wait_until(fn -> not os_alive?(os_pid) end), "stdio child survived close()"
  end

  test "a host extraTools entry shadows a builtin (extras position, extras source)" do
    my_bash = tool("bash", "sandboxed")
    tk = Toolnexus.create_toolkit!(extra_tools: [my_bash, tool("zeta")])

    # builtin "bash" is dropped up front — no dedupe warning, extras keep their slot
    assert names(tk) ==
             (Enum.map(Builtin.tools(), & &1.name) -- ["bash"]) ++ ["bash", "zeta"]

    assert Toolkit.get(tk, "bash").source == "native"
    assert Toolkit.execute(tk, "bash", %{}).output == "sandboxed"
  end

  # --- builtins toggle (§4 / §4A) -----------------------------------------------

  test "builtins toggle: option false, config-map key, and per-tool map" do
    assert names(Toolnexus.create_toolkit!(builtins: false)) == []

    # top-level `builtins` key on a parsed config map (beside mcpServers)
    tk = Toolnexus.create_toolkit!(mcp_config: %{"mcpServers" => %{}, "builtins" => false})
    assert names(tk) == []

    # option wins over the config key; per-tool map on the all-on baseline
    tk =
      Toolnexus.create_toolkit!(
        mcp_config: %{"mcpServers" => %{}, "builtins" => false},
        builtins: %{"tools" => %{"bash" => false, "write" => false}}
      )

    assert "bash" not in names(tk)
    assert "write" not in names(tk)
    assert "read" in names(tk)
  end

  # --- disable_tools / disable_skills (ADR-0001) ---------------------------------

  test "disable_tools drops tools by final exposed name across every source" do
    tk =
      Toolnexus.create_toolkit!(
        skills_dir: [@examples_skills],
        extra_tools: [tool("mine")],
        disable_tools: ["bash", "mine", "skill"]
      )

    assert "bash" not in names(tk)
    assert "mine" not in names(tk)
    assert "skill" not in names(tk)
    assert "read" in names(tk)
  end

  test "disable_skills drops from the skill catalog without overriding an explicit filter" do
    skills = [
      %{name: "alpha", description: "first", content: "A"},
      %{name: "beta", description: "second", content: "B"}
    ]

    tk = Toolnexus.create_toolkit!(skills: skills, disable_skills: ["beta"], builtins: false)
    assert Toolkit.skills_prompt(tk) =~ "alpha"
    refute Toolkit.skills_prompt(tk) =~ "beta"

    # an explicit skills_filter entry wins over the disable list
    capture_log(fn ->
      tk =
        Toolnexus.create_toolkit!(
          skills: skills,
          skills_filter: %{"beta" => true},
          disable_skills: ["beta"],
          builtins: false
        )

      send(self(), {:tk2, tk})
    end)

    assert_received {:tk2, tk2}
    assert Toolkit.skills_prompt(tk2) =~ "beta"
    refute Toolkit.skills_prompt(tk2) =~ "alpha"
  end

  # --- execute router / register / adapters --------------------------------------

  test "execute routes, defaults args/ctx, and reports unknown tools" do
    tk = Toolnexus.create_toolkit!(extra_tools: [tool("mine")], builtins: false)

    assert %ToolResult{output: "out:mine", is_error: false} = Toolkit.execute(tk, "mine", nil)
    assert %ToolResult{output: "Unknown tool: nope", is_error: true} = Toolkit.execute(tk, "nope", %{})
    assert Toolkit.get(tk, "nope") == nil
  end

  test "register adds tools first-wins and keeps order" do
    tk = Toolnexus.create_toolkit!(builtins: false)
    assert names(tk) == []

    log =
      capture_log(fn ->
        tk = tk |> Toolkit.register([tool("a"), tool("b")]) |> Toolkit.register(tool("a"))
        send(self(), {:tk, tk})
      end)

    assert_received {:tk, tk}
    assert names(tk) == ["a", "b"]
    assert log =~ ~s(duplicate tool name "a" — keeping first)
  end

  test "provider adapters mirror Toolnexus.Adapters over the aggregated list" do
    tk = Toolnexus.create_toolkit!(extra_tools: [tool("mine")], builtins: false)

    assert [%{"type" => "function", "function" => %{"name" => "mine"}}] = Toolkit.to_openai(tk)
    assert [%{"name" => "mine", "input_schema" => %{}}] = Toolkit.to_anthropic(tk)
    assert [%{"functionDeclarations" => [%{"name" => "mine"}]}] = Toolkit.to_gemini(tk)
  end

  # --- reserved config blocks (a2a / mcpServer passthrough) ----------------------

  test "top-level a2a and mcpServer config blocks are captured for serve()" do
    tk =
      Toolnexus.create_toolkit!(
        mcp_config: %{
          "mcpServers" => %{},
          "a2a" => %{"name" => "cfg-agent"},
          "mcpServer" => %{"name" => "cfg-mcp", "version" => "3.2.1"}
        },
        builtins: false
      )

    assert tk.a2a_config == %{"name" => "cfg-agent"}
    assert tk.mcp_server_config == %{"name" => "cfg-mcp", "version" => "3.2.1"}
  end

  test "a Tool struct list also drives the client-facing fields" do
    # the toolkit quacks like the client's toolkit protocol: :tools + :prompt
    tk = Toolnexus.create_toolkit!(skills_dir: [@examples_skills], builtins: false)
    assert is_list(tk.tools)
    assert is_binary(tk.prompt)
    assert %Tool{name: "skill"} = List.first(tk.tools)
  end
end
