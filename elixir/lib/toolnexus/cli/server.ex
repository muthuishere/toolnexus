defmodule Toolnexus.Cli.Server do
  @moduledoc false
  # Holds a CLI client's mutable state (SPEC §8C): the chosen transport
  # (:auto until the first model call decides), the live ACP session, and the
  # Auto fallback reason. Opening the ACP session happens here, serialised;
  # the model call itself (a one-shot invocation or an ACP prompt) runs in the
  # caller's process. It stops — closing any session — when the process that
  # created the client exits.

  use GenServer, restart: :temporary

  alias Toolnexus.Cli
  alias Toolnexus.Cli.AcpSession

  @doc false
  def generate(server, cfg, req, emit \\ nil) do
    case GenServer.call(server, :session, :infinity) do
      {:error, msg} ->
        raise RuntimeError, msg

      {:acp, sess} ->
        case AcpSession.generate(sess, req, emit) do
          {:ok, answer} -> answer
          {:error, msg} -> raise RuntimeError, msg
        end

      # The one-shot transport ignores `emit`: the whole answer is one chunk.
      :cli ->
        prompt = Cli.render_prompt(req[:messages], req[:tools])

        case Cli.invoke(cfg, prompt) do
          {:ok, text} -> Cli.parse_reply(text)
          {:error, msg} -> raise RuntimeError, msg
        end
    end
  end

  @impl true
  def init({cfg, owner}) do
    Process.flag(:trap_exit, true)
    ref = Process.monitor(owner)
    {:ok, %{cfg: cfg, chosen: cfg.chosen, acp: nil, closed: false, note: "", owner_ref: ref}}
  end

  @impl true
  def handle_call(:transport, _from, st), do: {:reply, st.chosen, st}
  def handle_call(:fallback_reason, _from, st), do: {:reply, st.note, st}

  def handle_call(:session, _from, %{closed: true} = st),
    do: {:reply, {:error, "cli: client is closed"}, st}

  def handle_call(:session, _from, %{chosen: :cli} = st), do: {:reply, :cli, st}
  def handle_call(:session, _from, %{acp: %{} = s} = st), do: {:reply, {:acp, s}, st}

  def handle_call(:session, _from, st) do
    case AcpSession.open(st.cfg) do
      {:ok, s} ->
        {:reply, {:acp, s}, %{st | acp: s, chosen: :acp}}

      {:error, {:launch, msg}} when st.chosen == :auto and st.cfg.has_cli ->
        {:reply, :cli, %{st | chosen: :cli, note: msg}}

      {:error, {_, msg}} ->
        {:reply, {:error, msg}, st}
    end
  end

  def handle_call(:session_info, _from, st), do: {:reply, st.acp, st}

  def handle_call(:close, _from, st) do
    {:reply, :ok, close_session(%{st | closed: true})}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, _, _}, %{owner_ref: ref} = st) do
    {:stop, :normal, close_session(st)}
  end

  def handle_info(_msg, st), do: {:noreply, st}

  @impl true
  def terminate(_reason, st) do
    close_session(st)
    :ok
  end

  defp close_session(%{acp: nil} = st), do: st

  defp close_session(%{acp: s} = st) do
    AcpSession.close(s)
    %{st | acp: nil}
  end
end
