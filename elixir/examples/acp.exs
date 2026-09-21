# ACP (Agent Client Protocol) as the model behind the client loop — issue #96,
# ADR 0031 (openspec/changes/add-acp-model-source).
#
# HONEST HEADER: the "warm session" win this example prints is the ACP agent
# CLI's process-startup cost amortised across turns, NOT a protocol-level
# speedup -- session/prompt itself is not faster than any other wire. See
# ADR 0031's measurements.
#
# Spawns a real ACP agent CLI (devin or opencode), registers one trivial
# local tool, and drives it through the ordinary toolnexus tool-calling loop
# for two turns -- proving MCP, skills and native tools work unchanged when
# the model behind the loop is an ACP agent instead of an HTTP LLM. Requires
# the agent CLI installed and authenticated on PATH; it is NOT hermetic and
# is NOT run by CI.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/acp.exs                                    # spawns `devin acp` (default)
#     TOOLNEXUS_ACP_CMD="opencode acp" mix run examples/acp.exs   # or opencode instead

here = Path.dirname(__ENV__.file)
examples = Path.expand(Path.join([here, "..", "..", "examples"]))

{:ok, tk} =
  Toolnexus.create_toolkit(
    mcp_config: Path.join(examples, "mcp.json"),
    skills_dir: Path.join(examples, "skills")
  )

# a trivial native tool -- proves tool-calling works unchanged through ACP
clock =
  Toolnexus.define_tool(
    name: "clock",
    description: "Return the current UTC time.",
    input_schema: %{"type" => "object", "properties" => %{}},
    execute: fn _args, _ctx -> DateTime.utc_now() |> DateTime.to_iso8601() end
  )

tk = Toolnexus.Toolkit.register(tk, [clock])

# Agent command is selectable: TOOLNEXUS_ACP_CMD (default "devin acp"). Both
# devin and opencode speak ACP live -- `devin acp` and `opencode acp` both
# answer `initialize` with protocolVersion 1.
[command | args] =
  (System.get_env("TOOLNEXUS_ACP_CMD") || "devin acp")
  |> String.split(~r/\s+/, trim: true)

IO.puts("Spawning ACP agent: #{Enum.join([command | args], " ")}")

case Toolnexus.Acp.connect([command | args], cwd: File.cwd!()) do
  {:ok, acp} ->
    client =
      Toolnexus.Client.create_in_process(
        model: command,
        generate: Toolnexus.Acp.generate(acp)
      )

    turns = [
      "What time is it right now? Use the clock tool.",
      "What did the clock tool just return, verbatim?"
    ]

    # Two turns on the SAME warm ACP session/process -- this is the whole
    # point: the process-startup cost was paid once by connect/2, not per turn.
    for {prompt, i} <- Enum.with_index(turns, 1) do
      start = System.monotonic_time(:millisecond)
      result = Toolnexus.Client.run(client, prompt, tk)
      elapsed = System.monotonic_time(:millisecond) - start

      IO.puts("\n--- turn #{i} (#{elapsed}ms) ---")
      IO.puts("prompt: #{prompt}")
      IO.puts("answer: #{String.trim(result.text)}")
    end

    Toolnexus.Acp.close(acp)
    IO.puts("\nElixir ACP example OK — warm session across #{length(turns)} turns")

  {:error, reason} ->
    IO.puts(
      "acp connect failed (is the CLI installed + authenticated?): #{inspect(reason)}"
    )
end

Toolnexus.Toolkit.close(tk)
