defmodule Toolnexus.Client.TurnGuard do
  @moduledoc false
  # SPEC §8 "Interrupted turns are saved". A stateful turn (`ask` with an id, or
  # `stream` with an id) runs with one of these beside it. The loop sends it the
  # transcript as it stands (`checkpoint/1`) and each finished tool result
  # (`tool_done/3`); if the turn ends any way but success, the guard saves that
  # transcript, closing every unanswered tool call with an `interrupted` error.
  #
  # It is a separate, MONITORING process on purpose: in Elixir a turn is
  # cancelled by killing the process running it (`Process.exit/2`,
  # `Task.shutdown/2`, a halted stream), and a killed process runs no cleanup.
  # The guard outlives it and saves on the `:DOWN`.

  @key :toolnexus_turn_guard

  @doc false
  def start(store, id, style, owner \\ self()) do
    spawn(fn ->
      mon = Process.monitor(owner)
      loop(%{store: store, id: id, style: style, mon: mon, messages: nil, done: %{}})
    end)
  end

  @doc false
  # Run `fun` as a guarded stateful turn. On a raise/throw/exit inside it the
  # partial transcript is saved BEFORE the original error propagates.
  def run(nil, _store, _style, fun), do: fun.()

  def run(id, store, style, fun) do
    guard = start(store, id, style)
    prev = Process.put(@key, guard)

    try do
      result = fun.()
      finish(guard, :ok)
      result
    catch
      kind, reason ->
        finish(guard, :interrupted)
        :erlang.raise(kind, reason, __STACKTRACE__)
    after
      if prev, do: Process.put(@key, prev), else: Process.delete(@key)
    end
  end

  @doc false
  def current, do: Process.get(@key)

  @doc false
  def checkpoint(messages) do
    if g = current(), do: send(g, {:checkpoint, messages})
    :ok
  end

  @doc false
  # Called from the tool worker (not the loop process), so the guard is passed in.
  def tool_done(nil, _id, _entry), do: :ok
  def tool_done(guard, id, entry), do: send(guard, {:tool, id, entry})

  @doc false
  # The turn succeeded and the client saved it itself: the guard stands down.
  def complete do
    if g = current(), do: finish(g, :ok)
    :ok
  end

  defp finish(guard, mode) do
    ref = Process.monitor(guard)
    send(guard, {:finish, self(), ref, mode})

    receive do
      {^ref, :finished} -> Process.demonitor(ref, [:flush])
      {:DOWN, ^ref, :process, _, _} -> :ok
    end
  end

  defp loop(st) do
    receive do
      {:checkpoint, messages} ->
        loop(%{st | messages: messages})

      {:tool, id, entry} ->
        loop(%{st | done: Map.put(st.done, id, entry)})

      {:finish, from, ref, :ok} ->
        send(from, {ref, :finished})

      {:finish, from, ref, :interrupted} ->
        save(st)
        send(from, {ref, :finished})

      {:DOWN, mon, :process, _, _} when mon == st.mon ->
        # Tool workers die with their owner; take any result they sent first.
        st = drain_tools(st)
        save(st)
    end
  end

  defp drain_tools(st) do
    receive do
      {:tool, id, entry} -> drain_tools(%{st | done: Map.put(st.done, id, entry)})
      {:checkpoint, messages} -> drain_tools(%{st | messages: messages})
    after
      0 -> st
    end
  end

  defp save(%{messages: nil}), do: :ok

  defp save(%{store: %mod{} = store} = st) do
    try do
      mod.save(store, st.id, repair(st.messages, st.style, st.done))
    rescue
      _ -> :ok
    catch
      _, _ -> :ok
    end
  end

  @doc false
  # Close every assistant tool call that has no result: a finished result the
  # loop had not yet folded in, else a synthetic `interrupted` error result.
  def repair(messages, "anthropic", done) do
    case List.last(messages) do
      %{"role" => "assistant", "content" => content} when is_list(content) ->
        uses = for %{"type" => "tool_use", "id" => id} <- content, do: id

        if uses == [] do
          messages
        else
          blocks =
            Enum.map(uses, fn id ->
              Map.get(done, id) ||
                %{
                  "type" => "tool_result",
                  "tool_use_id" => id,
                  "content" => "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.",
                  "is_error" => true
                }
            end)

          messages ++ [%{"role" => "user", "content" => blocks}]
        end

      _ ->
        messages
    end
  end

  def repair(messages, _openai, done) do
    answered =
      for %{"role" => "tool", "tool_call_id" => id} <- messages, into: MapSet.new(), do: id

    missing =
      for %{"role" => "assistant", "tool_calls" => calls} when is_list(calls) <- messages,
          %{"id" => id} <- calls,
          not MapSet.member?(answered, id),
          do: id

    messages ++
      Enum.map(missing, fn id ->
        Map.get(done, id) ||
          %{"role" => "tool", "tool_call_id" => id, "content" => "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first."}
      end)
  end
end
