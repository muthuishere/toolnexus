defmodule Toolnexus.Client.FileConversationStore do
  @moduledoc """
  Durable conversation store (SPEC §8 `FileConversationStore(root)`).

  Each conversation id is one file `<root>/<id>.json` holding
  `{"version":1,"messages":[...]}`. `root` is required — there is no default
  home directory — and is created (mode 0700) on first save.

    * `save/3` writes `<root>/.<id>.<random>.tmp` (mode 0600), fsyncs it and
      renames it over `<id>.json`, so a reader sees the old or the new
      transcript, never a torn one. Power-loss durability of the rename is
      best-effort (the parent directory is not fsynced; BEAM has no portable
      directory fsync).
    * `new(root, sync: mode)` picks the durability mode (SPEC §8 "Durability
      mode"): `:full` (default) and `:fsync` both call `:file.sync/1` — on
      macOS ERTS issues `F_FULLFSYNC`, BEAM exposes no plainer flush, and
      there is no directory fsync to drop, so the two are equal here; `:none`
      skips the flush. Temp-then-rename holds in every mode; an unknown mode
      raises `ArgumentError`.
    * `get/2` of a missing id ⇒ `nil`; unreadable/corrupt JSON or an unknown
      `version` ⇒ raises `Toolnexus.Client.FileConversationStore.Error`,
      never a silent `nil`.
    * Ids must match `^[A-Za-z0-9._-]{1,128}$` and not be `.`/`..`; every
      operation raises `ArgumentError` otherwise (no path traversal).
    * Same-id saves are serialized within this node (a `:global` lock per
      file); across OS processes the last rename wins.
  """
  @behaviour Toolnexus.Client.ConversationStore

  defmodule Error do
    @moduledoc "A conversation file exists but cannot be read back (corrupt, unknown version, I/O)."
    defexception [:message]
  end

  defstruct [:root, sync: :full]

  @type sync :: :full | :fsync | :none
  @type t :: %__MODULE__{root: String.t(), sync: sync()}

  @version 1
  @id_re ~r/\A[A-Za-z0-9._-]{1,128}\z/

  @doc "A store rooted at `root` (required, non-empty). Nothing is touched until the first save."
  @spec new(String.t(), keyword()) :: t()
  def new(root, opts \\ [])

  def new(root, opts) when is_binary(root) and root != "" do
    case Keyword.get(opts, :sync, :full) do
      mode when mode in [:full, :fsync, :none] ->
        %__MODULE__{root: root, sync: mode}

      other ->
        raise ArgumentError, "FileConversationStore: unknown sync mode #{inspect(other)}"
    end
  end

  def new(other, _opts),
    do:
      raise(
        ArgumentError,
        "FileConversationStore: root must be a non-empty path, got #{inspect(other)}"
      )

  @impl true
  def get(%__MODULE__{} = store, id) do
    path = path!(store, id)

    case File.read(path) do
      {:ok, body} ->
        case Jason.decode(body) do
          {:ok, %{"version" => @version, "messages" => messages}} when is_list(messages) ->
            messages

          {:ok, %{"version" => v}} when v != @version ->
            raise Error, "conversation #{inspect(id)}: unknown version #{inspect(v)}"

          {:ok, _} ->
            raise Error, "conversation #{inspect(id)}: malformed file"

          {:error, e} ->
            raise Error, "conversation #{inspect(id)}: corrupt JSON (#{Exception.message(e)})"
        end

      {:error, :enoent} ->
        nil

      {:error, reason} ->
        raise Error, "conversation #{inspect(id)}: unreadable (#{inspect(reason)})"
    end
  end

  @impl true
  def save(%__MODULE__{root: root, sync: sync} = store, id, messages) do
    path = path!(store, id)
    body = Jason.encode!(%{"version" => @version, "messages" => messages})

    :global.trans({{__MODULE__, Path.expand(path)}, self()}, fn ->
      ensure_root!(root)
      tmp = Path.join(root, ".#{id}.#{random()}.tmp")

      try do
        write_synced!(tmp, body, sync)
        File.rename!(tmp, path)
      after
        _ = File.rm(tmp)
      end
    end)

    :ok
  end

  @doc """
  Every stored conversation as `%{id, updated_at}` (file mtime as a UTC
  `DateTime`), newest first, ties by id ascending. Temp files and names that
  are not valid ids are skipped; bodies are not parsed. A missing root ⇒ `[]`.
  """
  @spec list(t()) :: [%{id: String.t(), updated_at: DateTime.t()}]
  def list(%__MODULE__{root: root}) do
    case File.ls(root) do
      {:ok, names} ->
        names
        |> Enum.flat_map(fn name ->
          with true <- String.ends_with?(name, ".json"),
               id = String.replace_suffix(name, ".json", ""),
               true <- valid_id?(id),
               {:ok, %File.Stat{type: :regular, mtime: mtime}} <-
                 File.stat(Path.join(root, name), time: :posix) do
            [{id, mtime}]
          else
            _ -> []
          end
        end)
        |> Enum.sort_by(fn {id, mtime} -> {-mtime, id} end)
        |> Enum.map(fn {id, mtime} -> %{id: id, updated_at: DateTime.from_unix!(mtime)} end)

      {:error, _} ->
        []
    end
  end

  @doc "Remove a conversation; succeeds when it is absent."
  @spec delete(t(), String.t()) :: :ok
  def delete(%__MODULE__{} = store, id) do
    path = path!(store, id)

    case File.rm(path) do
      :ok ->
        :ok

      {:error, :enoent} ->
        :ok

      {:error, reason} ->
        raise Error, "conversation #{inspect(id)}: delete failed (#{inspect(reason)})"
    end
  end

  @doc "Whether `id` is a valid conversation id for this store."
  @spec valid_id?(term()) :: boolean()
  def valid_id?(id) when is_binary(id),
    do: id not in [".", ".."] and Regex.match?(@id_re, id)

  def valid_id?(_), do: false

  # --- internals -------------------------------------------------------------

  defp path!(%__MODULE__{root: root}, id) do
    if valid_id?(id),
      do: Path.join(root, id <> ".json"),
      else: raise(ArgumentError, "FileConversationStore: invalid conversation id #{inspect(id)}")
  end

  defp ensure_root!(root) do
    unless File.dir?(root) do
      File.mkdir_p!(root)
      _ = File.chmod(root, 0o700)
    end
  end

  defp write_synced!(tmp, body, sync) do
    {:ok, fd} = :file.open(String.to_charlist(tmp), [:write, :exclusive, :binary, :raw])

    try do
      _ = File.chmod(tmp, 0o600)
      :ok = :file.write(fd, body)
      if sync != :none, do: :ok = :file.sync(fd)
    after
      :file.close(fd)
    end
  end

  defp random, do: Base.url_encode64(:crypto.strong_rand_bytes(9), padding: false)
end
