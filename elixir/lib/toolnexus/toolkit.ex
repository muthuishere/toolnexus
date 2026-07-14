defmodule Toolnexus.Toolkit do
  @moduledoc """
  Toolkit — aggregates dynamic MCP tools + the skill tool + built-ins + remote
  A2A agents + custom tools into a single uniform list, with provider adapters
  and a single `execute/4` router (SPEC §4).

  Assembly is deterministic: (1) drop any builtin whose name a host
  `extra_tools` entry provides (a host can shadow a builtin); (2) concatenate
  the remaining sources in the order **MCP → skill → builtin → agents →
  extra_tools**; (3) dedupe by name, **first wins**. `disable_tools` drops
  tools by their final exposed name across every source.

  The struct exposes `:tools` and `:prompt` directly, so it plugs straight
  into `Toolnexus.Client.run/4`.
  """

  require Logger

  alias Toolnexus.{A2a, Adapters, Builtin, Context, Mcp, Skill, Tool, ToolResult}

  defstruct tools: [],
            prompt: "",
            by_name: %{},
            mcp: nil,
            skill: nil,
            a2a_config: nil,
            mcp_server_config: nil

  @type t :: %__MODULE__{
          tools: [Tool.t()],
          prompt: String.t(),
          by_name: %{String.t() => Tool.t()},
          mcp: Mcp.Source.t() | nil,
          skill: Skill.Source.t() | nil,
          a2a_config: map() | nil,
          mcp_server_config: map() | nil
        }

  @doc """
  Build a Toolkit. Options (keyword list or map):

    * `:mcp_config` — path to an MCP config file, raw JSON, or a parsed map
    * `:skills_dir` — one or more skill roots
    * `:skills` — skills supplied as data, bypassing the filesystem (§3, S1)
    * `:skill_provider` — 0-arity fun lazily supplying data skills (§3, S1)
    * `:skills_filter` — per-agent skill allowlist keyed on name (§3, S2)
    * `:skill_sample_limit` — sibling-file cap: 0 ⇒ default 10, n>0 ⇒ cap,
      -1 ⇒ omit `<skill_files>` (§3, S5)
    * `:disable_tools` — drop tools by their FINAL exposed name across every
      source; composes with the per-server/per-skill filters
    * `:disable_skills` — drop skills by name from the `skill` catalog (sugar
      over a drop-list in `:skills_filter`)
    * `:extra_tools` — your own custom tools, always added to the toolkit
    * `:builtins` — built-in tools toggle (§4A). Default ON. When absent, a
      top-level `builtins` key on a parsed config map is consulted
    * `:agents` — remote A2A agents (§7A); a top-level `agents` config block
      is merged in as well
    * `:wait_for` — §10 host resolver; bridges MCP elicitation
    * `:deadline` (ms) — bounds the MCP load (§2 Gap 3)
  """
  @spec build(keyword() | map()) :: t()
  def build(opts) do
    opts = Map.new(opts)

    # Reserved sibling keys (builtins / agents / a2a / mcpServer) are read off
    # a PARSED config map only — a path/raw-JSON config is the MCP source's
    # business alone (JS parity: `typeof mcpConfig === "object"`).
    raw =
      case opts[:mcp_config] do
        cfg when is_map(cfg) -> cfg |> Jason.encode!() |> Jason.decode!()
        _ -> %{}
      end

    mcp =
      if opts[:mcp_config] != nil,
        do: Mcp.load(opts[:mcp_config], wait_for: opts[:wait_for], deadline: opts[:deadline])

    skill = load_skill_source(opts)

    # The toggle comes from the `builtins` option, or a top-level `builtins`
    # key on a parsed config map — same precedence as MCP's isEnabled.
    builtins_cfg = if Map.has_key?(opts, :builtins), do: opts[:builtins], else: raw["builtins"]
    builtins = Builtin.load(builtins_cfg)

    # Remote A2A agents come from the `agents` option plus a top-level
    # `agents` config block (mirrors mcpServers). Each is resolved to its
    # skill tools; a failing agent is isolated and never breaks the toolkit.
    agent_descriptors =
      Enum.map(opts[:agents] || [], &Map.new/1) ++ A2a.parse_agents_config(raw["agents"])

    agent_tools =
      Enum.flat_map(agent_descriptors, fn ag ->
        try do
          A2a.agent_tools(ag)
        rescue
          e ->
            Logger.warning(~s([toolnexus] A2A agent "#{ag[:card]}" failed: #{Exception.message(e)}))
            []
        end
      end)

    extra_tools = opts[:extra_tools] || []

    # Builtins are the lowest-precedence source: a host extra_tools entry with
    # the same name shadows a builtin (SPEC §4). Drop shadowed builtins up
    # front, then apply the normal first-wins dedupe.
    extra_names = MapSet.new(extra_tools, & &1.name)
    active_builtins = Enum.reject(builtins, &MapSet.member?(extra_names, &1.name))

    all =
      ((mcp && mcp.tools) || []) ++
        if(skill, do: [skill.tool], else: []) ++ active_builtins ++ agent_tools ++ extra_tools

    # disable_tools drops tools by their final exposed name across every source.
    disabled = MapSet.new(opts[:disable_tools] || [])

    {order_rev, by_name} =
      all
      |> Enum.reject(&MapSet.member?(disabled, &1.name))
      |> Enum.reduce({[], %{}}, &dedupe/2)

    %__MODULE__{
      tools: Enum.reverse(order_rev),
      prompt: if(skill, do: skill.prompt, else: ""),
      by_name: by_name,
      mcp: mcp,
      skill: skill,
      a2a_config: raw["a2a"],
      mcp_server_config: raw["mcpServer"]
    }
  end

  defp dedupe(tool, {order, by_name}) do
    if Map.has_key?(by_name, tool.name) do
      Logger.warning(~s([toolnexus] duplicate tool name "#{tool.name}" — keeping first))
      {order, by_name}
    else
      {[tool | order], Map.put(by_name, tool.name, tool)}
    end
  end

  # Skills come from directories, in-memory data, and/or a lazy provider (§3,
  # S1). `disable_skills` is sugar over a drop-list: fold each name into the
  # filter as false (without overriding an explicit skills_filter entry).
  defp load_skill_source(opts) do
    if opts[:skills_dir] != nil or opts[:skills] != nil or opts[:skill_provider] != nil do
      filter = opts[:skills_filter]

      filter =
        case opts[:disable_skills] || [] do
          [] ->
            filter

          names ->
            Enum.reduce(names, filter || %{}, fn n, acc ->
              if Map.has_key?(acc, n), do: acc, else: Map.put(acc, n, false)
            end)
        end

      Skill.load(
        dirs: opts[:skills_dir],
        skills: opts[:skills],
        provider: opts[:skill_provider],
        filter: filter,
        sample_limit: opts[:skill_sample_limit]
      )
    end
  end

  @doc "All tools (mcp tools + skill tool + builtins + agents + extras), in insertion order."
  @spec tools(t()) :: [Tool.t()]
  def tools(%__MODULE__{tools: tools}), do: tools

  @doc "A tool by name, or `nil`."
  @spec get(t(), String.t()) :: Tool.t() | nil
  def get(%__MODULE__{by_name: by_name}, name), do: Map.get(by_name, name)

  @doc """
  Add tools at runtime (native, http, or any custom Tool). First name wins;
  duplicates are skipped with a warning. Returns the updated toolkit.
  """
  @spec register(t(), [Tool.t()] | Tool.t()) :: t()
  def register(%__MODULE__{} = tk, tools) do
    {order_rev, by_name} =
      Enum.reduce(List.wrap(tools), {Enum.reverse(tk.tools), tk.by_name}, &dedupe/2)

    %{tk | tools: Enum.reverse(order_rev), by_name: by_name}
  end

  @doc """
  Fetch a remote A2A agent's card at runtime and register its skills as tools.
  Accepts an Agent descriptor (map/keyword) or a bare card URL (with optional
  `opts` for headers/timeout/poll_every). First-name-wins dedupe.
  """
  @spec add_agent(t(), map() | keyword() | String.t(), keyword() | map()) :: t()
  def add_agent(tk, agent_or_card_url, opts \\ [])

  def add_agent(%__MODULE__{} = tk, card_url, opts) when is_binary(card_url),
    do: add_agent(tk, Map.put(Map.new(opts), :card, card_url), [])

  def add_agent(%__MODULE__{} = tk, ag, _opts),
    do: register(tk, A2a.agent_tools(A2a.agent(ag)))

  @doc "Route to the right tool and run it. Unknown name ⇒ an error ToolResult."
  @spec execute(t(), String.t(), map(), Context.t() | nil) :: ToolResult.t()
  def execute(%__MODULE__{} = tk, name, args, ctx \\ nil) do
    case get(tk, name) do
      nil -> %ToolResult{output: "Unknown tool: #{name}", is_error: true}
      %Tool{execute: f} -> f.(args || %{}, ctx || %Context{})
    end
  end

  @doc "Markdown skill catalog for the system prompt."
  @spec skills_prompt(t()) :: String.t()
  def skills_prompt(%__MODULE__{prompt: prompt}), do: prompt

  @doc "Each MCP server's connection status."
  @spec mcp_status(t()) :: %{String.t() => String.t()}
  def mcp_status(%__MODULE__{mcp: nil}), do: %{}
  def mcp_status(%__MODULE__{mcp: mcp}), do: mcp.status

  @doc "OpenAI tool schema for all tools."
  @spec to_openai(t()) :: [map()]
  def to_openai(%__MODULE__{tools: tools}), do: Adapters.to_openai(tools)

  @doc "Anthropic tool schema for all tools."
  @spec to_anthropic(t()) :: [map()]
  def to_anthropic(%__MODULE__{tools: tools}), do: Adapters.to_anthropic(tools)

  @doc "Gemini tool schema for all tools."
  @spec to_gemini(t()) :: [map()]
  def to_gemini(%__MODULE__{tools: tools}), do: Adapters.to_gemini(tools)

  @doc """
  Serve this toolkit as an agent over HTTP (SPEC §7B/§7C). When the A2A
  profile is present (`opts[:a2a]`, or a top-level `a2a` config block the
  toolkit was built from) it mounts the Agent Card + JSON-RPC endpoint
  fulfilled by `opts[:client]`; a message's A2A `contextId` keys the
  conversation via `Client.ask`. When the MCP profile is present
  (`opts[:mcp]`, or a top-level `mcpServer` config block) it mounts a
  streamable-HTTP MCP server at `/mcp` exposing the toolkit's unified tools.
  Returns a stoppable `Toolnexus.Serve.Handle`.
  """
  @spec serve(t(), String.t(), keyword()) :: Toolnexus.Serve.Handle.t()
  def serve(%__MODULE__{} = tk, addr, opts \\ []) do
    a2a = opts[:a2a] || tk.a2a_config
    mcp = opts[:mcp] || tk.mcp_server_config
    client = opts[:client]

    Toolnexus.Serve.start(addr,
      a2a: a2a,
      skills: if(tk.skill, do: tk.skill.skills, else: []),
      run_task: fn text, context_id ->
        # contextId keys the conversation via Client.ask, so a peer's turns are
        # remembered through the client's ConversationStore; nil ⇒ stateless run.
        if context_id,
          do: Toolnexus.Client.ask(client, text, tk, id: context_id),
          else: Toolnexus.Client.run(client, text, tk)
      end,
      on_task: opts[:on_task],
      mcp: mcp,
      mcp_tools: if(mcp, do: tk.tools),
      on_call: opts[:on_call]
    )
  end

  @doc "Disconnect every MCP client."
  @spec close(t()) :: :ok
  def close(%__MODULE__{mcp: nil}), do: :ok
  def close(%__MODULE__{mcp: mcp}), do: Mcp.close(mcp)
end
