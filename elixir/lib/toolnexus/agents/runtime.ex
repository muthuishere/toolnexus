defmodule Toolnexus.Agents.Runtime do
  @moduledoc """
  The agent runtime (SPEC §7D) as OTP.

  Handles are GenServers under a runtime-owned `DynamicSupervisor`; the six verbs
  (`spawn`/`post`/`wake`/`wait`/`interrupt`/`close`) are the GenServer protocol; a
  Run is a monitored process; the global turn gate is a monitoring semaphore wrapped
  around the LLM HTTP call ONLY — injected through the client's first-class
  `:transport` seam (§8 Gap 2), so it composes against a real `base_url`.

  The runtime owns the cross-cutting infrastructure (§7D lifecycle obligations):

    * ONE `ConversationStore` for every handle (conversation id = the handle's
      deterministic id) — transcripts genuinely survive turns and durable resume
      reads real history;
    * ONE `MetricsRegistry` shared by every handle's client — no per-turn client
      processes leak;
    * an injectable clock (`Toolnexus.Agents.Clock`) for every timer/timeout;
    * the handle table (root + parent links), rebuildable by walking snapshots.

  All owned processes are linked to the runtime process and stopped by
  `shutdown/1`. Downward traversal (`close/2`, `list/1`, `resume/2`) runs in the
  CALLER's process — from outside the tree — per the rootward-call discipline
  documented on `Toolnexus.Agents.Handle`.

  ## Options

    * `:registry` — map of agent name → definition (see `Toolnexus.Agents.registry/1`)
    * `:transport` — the LLM transport function (see `Toolnexus.Client` `:transport`);
      the runtime wraps it with the turn gate
    * `:llm` — client settings shared by every handle:
      `%{base_url, style, model, api_key, retries}` (agent `model: "inherit"`
      resolves to `llm.model`)
    * `:max_concurrent_turns` — global turn-gate width (default 8)
    * `:inbox_cap` — bounded-inbox size (default 8)
    * `:shutdown_ms` — graceful-close budget for a running turn (default 200)
    * `:clock` — `Toolnexus.Agents.Clock.t()` (default: the real clock)
    * `:store` — a `ConversationStore` struct (default: runtime-owned in-memory)
  """
  use GenServer

  alias Toolnexus.ToolResult
  alias Toolnexus.Agents.{Clock, Handle, Trace, TurnGate}
  alias Toolnexus.Client.{InMemoryConversationStore, MetricsRegistry}

  # ---- lifecycle -----------------------------------------------------------

  @doc "Start a runtime and return its pid (raises on bad options)."
  @spec new(keyword() | map()) :: pid()
  def new(opts) do
    {:ok, pid} = start_link(opts)
    pid
  end

  @doc false
  def start_link(opts), do: GenServer.start_link(__MODULE__, Map.new(opts))

  @impl true
  def init(opts) do
    {:ok, sup} = DynamicSupervisor.start_link(strategy: :one_for_one)
    {:ok, gate} = TurnGate.start_link(opts[:max_concurrent_turns] || 8)
    metrics = MetricsRegistry.new(link: true)

    store =
      opts[:store] ||
        (
          {:ok, store_agent} = Agent.start_link(fn -> %{} end)
          %InMemoryConversationStore{pid: store_agent}
        )

    transport = opts[:transport]

    ctx = %{
      runtime: self(),
      sup: sup,
      gate: gate,
      trace: Trace.start_link(),
      registry: opts[:registry] || %{},
      inbox_cap: opts[:inbox_cap] || 8,
      shutdown_ms: opts[:shutdown_ms] || 200,
      clock: opts[:clock] || Clock.real(),
      store: store,
      metrics: metrics,
      llm: opts[:llm] || %{},
      gated_transport: transport && gated(gate, transport)
    }

    {:ok, root} =
      Handle.start(ctx, "root", %{name: "root", description: "runtime root", system_prompt: "", model: "none"}, nil)

    {:ok, %{ctx: Map.put(ctx, :root, root), root: root}}
  end

  @impl true
  def handle_call(:ctx, _from, st), do: {:reply, st.ctx, st}

  @impl true
  def terminate(_reason, st) do
    # runtime-owned infrastructure dies with the runtime — nothing leaks
    for pid <- [st.ctx.sup, st.ctx.gate, st.ctx.trace, st.ctx.metrics] do
      if is_pid(pid) and Process.alive?(pid), do: safe_stop(pid)
    end

    case st.ctx.store do
      %InMemoryConversationStore{pid: pid} -> if Process.alive?(pid), do: safe_stop(pid)
      _ -> :ok
    end

    :ok
  end

  defp safe_stop(pid) do
    try do
      GenServer.stop(pid, :normal, 1_000)
    catch
      :exit, _ -> :ok
    end
  end

  @doc "Graceful stop-all (`close(root)`) followed by full runtime teardown."
  @spec shutdown(pid()) :: :ok
  def shutdown(rt) do
    close(rt)
    GenServer.stop(rt)
  end

  # ---- accessors -----------------------------------------------------------

  @doc "The runtime's shared context (root handle, store, gate, trace...)."
  def ctx(rt), do: GenServer.call(rt, :ctx)

  @doc "The root handle."
  def root(rt), do: ctx(rt).root

  @doc "The runtime-wide ConversationStore (conversation id = handle id)."
  def conversation_store(rt), do: ctx(rt).store

  @doc "All transition-trace lines (§0 conformance surface)."
  def trace(rt), do: Trace.all(ctx(rt).trace)

  @doc "High-water mark of concurrent LLM calls through the turn gate."
  def max_observed_concurrent_turns(rt), do: TurnGate.max_observed(ctx(rt).gate)

  @doc "Read-only snapshot of one handle."
  def snapshot(pid), do: Handle.snapshot(pid)

  # ---- the six verbs -------------------------------------------------------

  @doc "Verb: spawn a child of `parent` from registry definition `def_name`."
  def spawn_agent(_rt, parent, def_name, budget \\ nil) do
    case GenServer.call(parent, {:spawn, def_name, budget, nil}) do
      {:ok, pid, _id} -> {:ok, pid}
      err -> err
    end
  end

  @doc "Verb: post an item (`%{from, channel, text}`) to a handle's inbox."
  def post(_rt, pid, item), do: Handle.post(pid, item)

  @doc "Verb: wake an idle handle (drains the inbox into one turn)."
  def wake(_rt, pid, prompt \\ nil), do: Handle.wake(pid, prompt)

  @doc "Verb: wait for the next result or the last result."
  def wait(_rt, pid, timeout_ms \\ nil), do: Handle.wait(pid, timeout_ms)

  @doc "Host-side fused wake+wait on one handle (resume cascades, heartbeats, fixtures)."
  def run_turn(_rt, pid, prompt, one_shot \\ nil), do: Handle.run_now(pid, prompt, one_shot)

  @doc "Verb: abort the in-flight turn → `idle`, drained inbox restored."
  def interrupt(_rt, pid), do: Handle.interrupt(pid)

  @doc "Verb: graceful stop-all — `close(root)`, cascading leaf-first."
  def close(rt, opts \\ []), do: close_handle(ctx(rt), root(rt), opts)

  @doc false
  # Leaf-first close cascade, driven from OUTSIDE the tree (the caller's process).
  def close_handle(ctx, pid, opts \\ []) do
    snap = Handle.snapshot(pid)

    if snap.state != :closed do
      Enum.each(snap.children, fn c -> close_handle(ctx, c.pid, opts) end)
      snap = Handle.snapshot(pid)

      if snap.state == :running do
        if opts[:force] do
          GenServer.call(pid, {:kill_run, :close})
        else
          # a running turn may finish, bounded by shutdown_ms — then escalate
          Handle.wait(pid, ctx.shutdown_ms)
          if Handle.snapshot(pid).state == :running, do: GenServer.call(pid, {:kill_run, :close})
        end
      end

      reason = opts[:reason] || if(opts[:force], do: "interrupted", else: "closed")
      f = snap.def[:on_close]
      if is_function(f, 2), do: f.(snap, reason)
      GenServer.call(pid, {:close, reason})
    end

    :ok
  end

  # ---- views ---------------------------------------------------------------

  @doc "Flat listing of every handle (id, state, rolled-up tokens, inbox depth)."
  def list(rt), do: walk(root(rt), [])

  defp walk(pid, acc) do
    snap = Handle.snapshot(pid)

    acc =
      if snap.id == "root",
        do: acc,
        else: acc ++ [%{id: snap.id, state: snap.state, tokens: snap.usage_total, inbox: length(snap.inbox)}]

    Enum.reduce(snap.children, acc, fn c, acc -> walk(c.pid, acc) end)
  end

  # ---- durable resume ------------------------------------------------------

  @doc """
  Route an Answer to the DEEPEST suspended handle; it resumes from its checkpoint
  (turns/usage grow, never reset), then the upward cascade re-runs each suspended
  parent — whose re-invoked `task` REATTACHES to the existing child by task key.
  """
  def resume(rt, answer) do
    ctx = ctx(rt)
    leaf = find_suspended_leaf(root(rt)) || raise "no suspended handle"
    snap = Handle.snapshot(leaf)
    ok = Map.get(answer, :ok)
    Trace.add(ctx.trace, "#{snap.id}: resume with Answer(ok=#{ok}) at checkpoint (turns so far: #{snap.turns_total})")
    GenServer.call(leaf, :cancel_pending)
    Handle.run_now(leaf, nil, fn _req -> answer end, :resume)

    cascade(ctx, snap.parent)
    :ok
  end

  defp cascade(ctx, pid) do
    if pid != nil and pid != ctx.root do
      snap = Handle.snapshot(pid)

      if snap.state == :suspended do
        Trace.add(ctx.trace, "#{snap.id}: cascade resume (child result cached)")
        GenServer.call(pid, :cancel_pending)
        Handle.run_now(pid, nil, nil, :resume)
        cascade(ctx, snap.parent)
      end
    end
  end

  defp find_suspended_leaf(pid) do
    snap = Handle.snapshot(pid)

    Enum.find_value(snap.children, fn c -> find_suspended_leaf(c.pid) end) ||
      if snap.state == :suspended, do: pid
  end

  # ---- the turn gate wraps the LLM HTTP call ONLY --------------------------

  defp gated(gate, transport) do
    fn request ->
      TurnGate.acquire(gate)

      try do
        transport.(request)
      after
        TurnGate.release(gate)
      end
    end
  end

  # ---- the model surface: `task` only, solicited rail ----------------------

  @doc false
  # Built per handle whose definition declares a team (`task_targets`); the
  # description advertises ONLY the team, sorted by name (§7D task).
  def task_tool(ctx, parent_pid, parent_id, parent_def) do
    targets = parent_def[:task_targets] || []

    entries =
      ctx.registry
      |> Enum.filter(fn {k, _d} -> k in targets end)
      |> Enum.sort_by(fn {k, _d} -> k end)

    names = Enum.map_join(entries, "; ", fn {k, d} -> "#{k}: #{d[:description]}" end)

    Toolnexus.define_tool(%{
      name: "task",
      description: "Delegate a subtask to an isolated subagent. Available agents — " <> names,
      input_schema: %{
        "type" => "object",
        "properties" => %{"agent" => %{"type" => "string"}, "prompt" => %{"type" => "string"}},
        "required" => ["agent", "prompt"]
      },
      execute: fn args ->
        agent_name = to_string(args["agent"] || "")
        prompt = to_string(args["prompt"] || "")

        if agent_name not in targets do
          %ToolResult{
            output: "agent \"#{agent_name}\" not in this agent's team (#{Enum.join(Enum.sort(targets), ", ")})",
            is_error: true
          }
        else
          run_task(ctx, parent_pid, parent_id, agent_name, prompt)
        end
      end
    })
  end

  # task = spawn → wake → wait → close, fused. REATTACHMENT to the existing child
  # by task key — including a closed child via its queryable final state — is the
  # ONLY idempotency mechanism (§7D: "not a completion cache").
  defp run_task(ctx, parent, parent_id, agent_name, prompt) do
    key = "#{agent_name}:#{prompt}"

    existing =
      GenServer.call(parent, :snapshot).children
      |> Enum.filter(&(&1.task_key == key))
      |> Enum.map(fn c -> {c.pid, Handle.snapshot(c.pid)} end)
      # prefer a live handle; a closed one still answers from its final state
      |> Enum.sort_by(fn {_p, s} -> if s.state == :closed, do: 1, else: 0 end)
      |> List.first()

    {child, r} =
      case existing do
        {pid, es} ->
          Trace.add(ctx.trace, "#{parent_id}: task replay → REATTACH to #{es.id} (state #{es.state})")

          r =
            if es.last_result != nil and es.state in [:idle, :suspended, :closed],
              do: es.last_result,
              else: Handle.wait(pid, nil, as: parent_id)

          {pid, r}

        nil ->
          case GenServer.call(parent, {:spawn, agent_name, nil, key}) do
            {:error, msg} ->
              throw({:task_error, msg})

            {:ok, pid, _id} ->
              Handle.wake(pid, prompt)
              {pid, Handle.wait(pid, nil, as: parent_id)}
          end
      end

    if r.status == "pending" do
      # a suspending agent IS a suspending tool — §10 unchanged; the child's
      # Request (path already stamped) rides metadata.pending
      %ToolResult{output: r.pending.prompt, is_error: true, metadata: %{pending: r.pending}}
    else
      close_handle(ctx, child)
      out = if r.status == "done", do: r.text, else: "[#{r.status}] #{r.text}"

      %ToolResult{
        output: out,
        is_error: r.is_error,
        metadata: %{agent: agent_name, turns: r.turns, total_tokens: r.total_tokens}
      }
    end
  catch
    {:task_error, msg} -> %ToolResult{output: msg, is_error: true}
  end
end
