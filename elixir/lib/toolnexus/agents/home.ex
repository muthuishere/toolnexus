defmodule Toolnexus.Agents.Home do
  @moduledoc """
  Agent home — personas over the §7D runtime (SPEC §7E).

  The persona archetype: an identity that lives in **files**, durable **memory** the
  agent can edit, and a **heartbeat** so it can act unprompted. All three ride shipped
  seams — the soul injection point, the six verbs, the runtime-wide store — and add no
  new runtime behavior.

  Three things, all recipes over `Toolnexus.Agents`:

    1. `from_dir/2` — **the directory IS the agent.** Ordered bootstrap files
       (`AGENTS.md`, `SOUL.md`, `IDENTITY.md`, `USER.md`, `TOOLS.md`, `HEARTBEAT.md`,
       `MEMORY.md`) compose the soul as `## <file>` sections, 2 MB/file cap.
       Composition happens once at session start (frozen snapshot — cache-stable).
    2. `memory_tool/1` — one `add|replace|remove` tool over `MEMORY.md`/`USER.md`.
       Writes go to **disk**; the live prompt is intentionally NOT touched (the frozen
       snapshot re-reads on the next session). A missing `replace`/`remove` substring
       is a loud `isError`.
    3. `start_agent/3` — a heartbeat: each interval posts a tick to the agent's own
       inbox (the unsolicited rail — ticks coalesce) and, when idle, wakes it. A
       `#{"HEARTBEAT_OK"}` reply stays silent. All timing rides the runtime's
       **injectable clock**, so conformance fixtures run on a virtual clock. Channels
       (Telegram/etc.) stay the host's job — wire them to `Runtime.post/3`.

  Higher patterns are composition, not API: **dream / consolidation** is a
  `start_agent` whose `HEARTBEAT.md` says "fold notes into MEMORY.md via the memory
  tool"; a **channel assistant** is the host's inbound handler calling post/wake.
  """

  alias Toolnexus.Agents
  alias Toolnexus.Agents.AgentDef
  alias Toolnexus.Agents.Home.Heartbeat
  alias Toolnexus.Agents.Runtime
  alias Toolnexus.ToolResult

  @bootstrap_order [
    "AGENTS.md",
    "SOUL.md",
    "IDENTITY.md",
    "USER.md",
    "TOOLS.md",
    "HEARTBEAT.md",
    "MEMORY.md"
  ]
  @max_file_bytes 2 * 1024 * 1024
  @truncation_notice "\n[truncated: exceeds 2 MB bootstrap cap]"
  @heartbeat_ok "HEARTBEAT_OK"
  @default_heartbeat_prompt "Heartbeat. Read your HEARTBEAT.md section and follow it. " <>
                              "If nothing needs attention, reply #{@heartbeat_ok}."

  @doc "The canonical bootstrap file order (identity first, memory last)."
  @spec bootstrap_order() :: [String.t()]
  def bootstrap_order, do: @bootstrap_order

  @doc "The reserved silent-beat reply. A reply containing it produces no report."
  @spec heartbeat_ok() :: String.t()
  def heartbeat_ok, do: @heartbeat_ok

  # ---- fromDir: the directory IS the agent --------------------------------

  @doc """
  Compose the discovered bootstrap files under `dir` into one soul string (the frozen
  snapshot). Returns `{soul, found}` where `found` is the discovered files in the
  canonical order. Each present file becomes a `## <file>` section; absent files are
  skipped; a file over 2 MB is truncated with a notice (the on-disk file is untouched).
  """
  @spec compose_soul(String.t()) :: {String.t(), [String.t()]}
  def compose_soul(dir) do
    {sections, found} =
      Enum.reduce(@bootstrap_order, {[], []}, fn f, {sections, found} ->
        case read_capped(Path.join(dir, f)) do
          nil -> {sections, found}
          body -> {["## #{f}\n\n#{String.trim(body)}" | sections], [f | found]}
        end
      end)

    {sections |> Enum.reverse() |> Enum.join("\n\n"), Enum.reverse(found)}
  end

  # Read a bootstrap file with the byte cap; nil if absent.
  defp read_capped(path) do
    case File.read(path) do
      {:ok, body} ->
        if byte_size(body) > @max_file_bytes,
          do: binary_part(body, 0, @max_file_bytes) <> @truncation_notice,
          else: body

      _ ->
        nil
    end
  end

  @doc """
  The directory IS the agent. Discovers the bootstrap files → soul (frozen snapshot),
  and wires the `memory` tool over the same dir. Returns a `Toolnexus.Agents.AgentDef`
  runnable with `Toolnexus.Agents.run/3` like any agent.

  Options:

    * `:does` — routing description (default `"persona agent from <dir>"`)
    * `:name` — agent name (default: the dir's basename)
    * `:model` — LLM model (default `"inherit"`)
    * `:tools` — extra tools beyond the memory builtin
    * `:memory` — set `false` to omit the memory tool (a read-only persona)
  """
  @spec from_dir(String.t(), keyword() | map()) :: AgentDef.t()
  def from_dir(dir, opts \\ []) do
    opts = Map.new(opts)
    {soul, _found} = compose_soul(dir)
    name = opts[:name] || Path.basename(dir)
    base_tools = opts[:tools] || []
    tools = if opts[:memory] == false, do: base_tools, else: base_tools ++ [memory_tool(dir)]

    Agents.agent(name, %{
      does: opts[:does] || "persona agent from #{dir}",
      soul: soul,
      model: opts[:model] || "inherit",
      uses: %{tools: tools}
    })
  end

  # ---- the memory builtin (file-backed, opt-in) ---------------------------

  @doc """
  The `memory` builtin over `MEMORY.md` (the agent's own notes) and `USER.md` (its
  model of the user), under `dir`. One tool, three actions — `add`, `replace`,
  `remove` — each writing to disk. A `replace`/`remove` whose target substring is
  absent is a loud `isError`. Writes do NOT mutate the current session's prompt (the
  frozen snapshot re-reads at the START of the next session); the description states
  this to the model.
  """
  @spec memory_tool(String.t()) :: Toolnexus.Tool.t()
  def memory_tool(dir) do
    Toolnexus.define_tool(%{
      name: "memory",
      description:
        "Persist durable memory. action=add appends an entry; replace swaps an existing " <>
          "substring; remove deletes one. target=self (MEMORY.md, default) or user (USER.md). " <>
          "Writes persist to disk and load at the START of your next session — they do NOT " <>
          "change your current context.",
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "action" => %{"type" => "string", "enum" => ["add", "replace", "remove"]},
          "target" => %{"type" => "string", "enum" => ["self", "user"]},
          "text" => %{
            "type" => "string",
            "description" => "For add: the entry. For replace/remove: the existing text."
          },
          "with" => %{"type" => "string", "description" => "For replace: the replacement text."}
        },
        "required" => ["action", "text"]
      },
      execute: fn args -> apply_memory(dir, args) end
    })
  end

  defp apply_memory(dir, args) do
    target = to_string(args["target"] || "self")
    file = Path.join(dir, if(target == "user", do: "USER.md", else: "MEMORY.md"))

    cur =
      case File.read(file) do
        {:ok, body} -> body
        _ -> ""
      end

    text = to_string(args["text"] || "")
    action = to_string(args["action"] || "")

    case compute_memory(action, cur, text, to_string(args["with"] || "")) do
      {:ok, next} ->
        File.mkdir_p!(Path.dirname(file))
        File.write!(file, next)
        # plain success string (wrapped as a success ToolResult by define_tool);
        # a full isError result is reserved for the miss path (§7E / JS reference)
        "ok (#{action} → #{Path.basename(file)}); loads next session"

      {:error, msg} ->
        %ToolResult{output: msg, is_error: true}
    end
  end

  defp compute_memory("add", cur, text, _with),
    do: {:ok, (String.trim_trailing(cur) <> "\n- #{text}\n") |> String.trim_leading()}

  defp compute_memory("replace", cur, text, replacement) do
    if String.contains?(cur, text),
      do: {:ok, String.replace(cur, text, replacement, global: false)},
      else: {:error, "not found: #{text}"}
  end

  defp compute_memory("remove", cur, text, _with) do
    if String.contains?(cur, text),
      do: {:ok, String.replace(cur, text, "", global: false)},
      else: {:error, "not found: #{text}"}
  end

  defp compute_memory(action, _cur, _text, _with), do: {:error, "unknown action: #{action}"}

  # ---- heartbeat (start_agent) --------------------------------------------

  defmodule Started do
    @moduledoc "Handle to a running persona (see `Toolnexus.Agents.Home.start_agent/3`)."
    @enforce_keys [:runtime, :handle, :heartbeat]
    defstruct [:runtime, :handle, :heartbeat]

    @type t :: %__MODULE__{runtime: pid(), handle: pid(), heartbeat: pid()}
  end

  @doc """
  Start a long-lived persona with its own clock. Builds a runtime from `rt_opts`
  (`:transport`, `:llm`, `:clock`, ...), spawns the persona, and arms a heartbeat.

  Each interval the heartbeat posts a tick to the agent's own inbox (the unsolicited
  rail — ticks coalesce, so a slow beat can't pile up) and, when the agent is idle,
  wakes it with a prompt to read `HEARTBEAT.md` and act, else reply `#{@heartbeat_ok}`.
  A `#{@heartbeat_ok}` reply is silent (no report); only a substantive reply reaches
  `:on_beat`. All timing rides the runtime's injectable clock (fixtures run virtual).

  Options:

    * `:every_ms` — heartbeat interval (default 30 min)
    * `:prompt` — the heartbeat prompt (default asks to follow HEARTBEAT.md)
    * `:on_beat` — `(text -> any)` fired only for non-silent beats

  Returns a `Started` struct. Use `beats/1` to read collected non-silent beats,
  `sync/1` to await the current beat (deterministic under a virtual clock), and
  `stop/1` to tear everything down.
  """
  @spec start_agent(AgentDef.t(), keyword() | map(), keyword() | map()) :: Started.t()
  def start_agent(%AgentDef{} = agent, rt_opts, opts \\ []) do
    opts = Map.new(opts)
    rt = Runtime.new(rt_opts |> Map.new() |> Map.put(:registry, Agents.registry(agent)))
    {:ok, handle} = Runtime.spawn_agent(rt, Runtime.root(rt), agent.name)

    {:ok, hb} =
      Heartbeat.start_link(%{
        rt: rt,
        handle: handle,
        every_ms: opts[:every_ms] || 30 * 60_000,
        prompt: opts[:prompt] || @default_heartbeat_prompt,
        on_beat: opts[:on_beat],
        clock: Runtime.ctx(rt).clock
      })

    %Started{runtime: rt, handle: handle, heartbeat: hb}
  end

  @doc "The non-silent beats collected so far (HEARTBEAT_OK replies excluded)."
  @spec beats(Started.t()) :: [String.t()]
  def beats(%Started{heartbeat: hb}), do: Heartbeat.beats(hb)

  @doc "Await the heartbeat process (its beat, if any, has been fully applied)."
  @spec sync(Started.t()) :: :ok
  def sync(%Started{heartbeat: hb}), do: Heartbeat.sync(hb)

  @doc "Stop the heartbeat and tear the runtime down."
  @spec stop(Started.t()) :: :ok
  def stop(%Started{runtime: rt, heartbeat: hb}) do
    if Process.alive?(hb), do: GenServer.stop(hb)
    Runtime.shutdown(rt)
    :ok
  end

  defmodule Heartbeat do
    @moduledoc false
    # The heartbeat driver — one GenServer that arms a beat through the runtime's
    # injectable clock and RE-ARMS on each beat (never `:timer` — the spec'd loop
    # must be virtual-clock testable). Each beat posts a tick and, when idle, runs
    # one fused wake+wait turn; a `HEARTBEAT_OK` reply is dropped silently.
    use GenServer

    alias Toolnexus.Agents.Runtime

    @heartbeat_ok "HEARTBEAT_OK"

    def start_link(args), do: GenServer.start_link(__MODULE__, args)

    def beats(pid), do: GenServer.call(pid, :beats)
    def sync(pid), do: GenServer.call(pid, :sync)

    @impl true
    def init(args) do
      arm(args.clock, args.every_ms)
      {:ok, Map.put(args, :beats, [])}
    end

    @impl true
    def handle_info(:beat, st) do
      # unsolicited rail: the tick lands in the inbox (coalesces); wake drains it
      Runtime.post(st.rt, st.handle, %{from: "clock", channel: "timer", text: "tick"})

      st =
        if Runtime.snapshot(st.handle).state == :idle do
          r = Runtime.run_turn(st.rt, st.handle, st.prompt)

          if not r.is_error and not String.contains?(r.text, @heartbeat_ok) do
            if is_function(st.on_beat, 1), do: st.on_beat.(r.text)
            %{st | beats: st.beats ++ [r.text]}
          else
            st
          end
        else
          st
        end

      arm(st.clock, st.every_ms)
      {:noreply, st}
    end

    @impl true
    def handle_call(:beats, _from, st), do: {:reply, st.beats, st}
    def handle_call(:sync, _from, st), do: {:reply, :ok, st}

    defp arm(clock, ms), do: clock.send_after.(self(), :beat, ms)
  end
end
