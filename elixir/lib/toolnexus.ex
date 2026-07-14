defmodule Toolnexus do
  @moduledoc """
  Your LLM, with MCP tools and agent skills built in — the top-level API.

  ## Zero to agent

      {:ok, toolkit} = Toolnexus.create_toolkit(mcp_config: "mcp.json", skills_dir: ["skills"])
      client = Toolnexus.Client.create(base_url: System.get_env("OPENAI_BASE_URL"),
                                       style: "openai", model: "gpt-4.1",
                                       api_key: System.get_env("OPENAI_API_KEY"))
      result = Toolnexus.Client.run(client, "What tools do you have? Use one.", toolkit)
      IO.puts(result.text)

  The toolkit aggregates every tool source — MCP servers, agent skills, the
  built-in shell/file tools, remote A2A agents, and your own tools
  (`define_tool/1` / `Toolnexus.Http.tool/1`) — behind one uniform
  `Toolnexus.Tool` list (SPEC §4).
  """

  alias Toolnexus.Toolkit

  @doc """
  Build a toolkit from keyword or map options — see `Toolnexus.Toolkit.build/1`
  for the full option list. Returns `{:ok, toolkit}`, or `{:error, error}` when
  the build raises (bad config file, MCP load deadline exceeded, …).
  """
  @spec create_toolkit(keyword() | map()) :: {:ok, Toolkit.t()} | {:error, Exception.t()}
  def create_toolkit(opts \\ []) do
    {:ok, Toolkit.build(opts)}
  rescue
    e -> {:error, e}
  end

  @doc "Like `create_toolkit/1` but raises on failure and returns the toolkit."
  @spec create_toolkit!(keyword() | map()) :: Toolkit.t()
  def create_toolkit!(opts \\ []), do: Toolkit.build(opts)

  @doc "Wrap a plain function as a uniform tool (SPEC §6). See `Toolnexus.Native.define_tool/1`."
  defdelegate define_tool(opts), to: Toolnexus.Native

  @doc "Build an A2A agent descriptor (SPEC §7A). See `Toolnexus.A2a.agent/1`."
  defdelegate agent(opts), to: Toolnexus.A2a
end
