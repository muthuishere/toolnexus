defmodule Toolnexus.Agents.TurnGate do
  @moduledoc """
  The global turn gate (SPEC §7D backpressure gate 3) — a counting semaphore wrapped
  around the LLM HTTP call ONLY, never a whole Run (holding it across a Run deadlocks
  a parent against its children).

  The gate MONITORS each acquirer: a Run process killed mid-call (interrupt, forced
  close, crash) releases its slot automatically — the release never depends on the
  acquirer's own cleanup path running.
  """
  use GenServer

  @doc false
  def start_link(slots), do: GenServer.start_link(__MODULE__, slots)

  @doc "Block until a slot is granted to the calling process."
  @spec acquire(pid()) :: :ok
  def acquire(gate), do: GenServer.call(gate, :acquire, :infinity)

  @doc "Release the calling process's slot."
  @spec release(pid()) :: :ok
  def release(gate), do: GenServer.cast(gate, {:release, self()})

  @doc "High-water mark of concurrently held slots (conformance probe)."
  @spec max_observed(pid()) :: non_neg_integer()
  def max_observed(gate), do: GenServer.call(gate, :max_observed)

  @impl true
  def init(slots),
    do: {:ok, %{free: slots, waiters: :queue.new(), holders: %{}, concurrent: 0, max: 0}}

  @impl true
  def handle_call(:acquire, {pid, _} = from, st) do
    if st.free > 0 do
      {:reply, :ok, grant(%{st | free: st.free - 1}, pid)}
    else
      {:noreply, %{st | waiters: :queue.in({from, pid}, st.waiters)}}
    end
  end

  def handle_call(:max_observed, _from, st), do: {:reply, st.max, st}

  @impl true
  def handle_cast({:release, pid}, st), do: {:noreply, do_release(st, pid, :normal)}

  @impl true
  def handle_info({:DOWN, _mon, :process, pid, _reason}, st) do
    if Map.has_key?(st.holders, pid),
      do: {:noreply, do_release(st, pid, :down)},
      else: {:noreply, st}
  end

  defp grant(st, pid) do
    mon = Process.monitor(pid)
    concurrent = st.concurrent + 1
    %{st | holders: Map.put(st.holders, pid, mon), concurrent: concurrent, max: max(st.max, concurrent)}
  end

  defp do_release(st, pid, how) do
    case Map.pop(st.holders, pid) do
      {nil, _} ->
        st

      {mon, holders} ->
        if how == :normal, do: Process.demonitor(mon, [:flush])
        st = %{st | holders: holders, concurrent: st.concurrent - 1}

        case :queue.out(st.waiters) do
          {{:value, {from, wpid}}, rest} ->
            GenServer.reply(from, :ok)
            grant(%{st | waiters: rest}, wpid)

          {:empty, _} ->
            %{st | free: st.free + 1}
        end
    end
  end
end
