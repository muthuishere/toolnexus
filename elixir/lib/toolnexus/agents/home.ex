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

  Higher patterns are composition, not API: **dream / consolidation** ships as the one
  preset, `dreamer/2` (a plain agent over the memory tool and transcript search);
  a **channel assistant** is the host's inbound handler calling post/wake.
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
  @index_cap_preset %{max_lines: 200, max_bytes: 25600}
  @note_slug ~r/\A[a-z0-9][a-z0-9._-]{0,63}\z/
  @memory_description "Persist durable memory. action=add appends an entry; replace swaps an " <>
                        "existing substring (with); remove deletes one. target=self (MEMORY.md, " <>
                        "default) or user (USER.md). Writes persist to disk and load at the START of " <>
                        "your NEXT session — they do NOT change your current context."
  @memory_notes_description "Persist durable memory. MEMORY.md is a short index loaded at the start of every " <>
                              "session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never " <>
                              "loaded automatically) and point to them from the index. Record what will change a " <>
                              "future decision. action=add appends an entry; replace swaps the first occurrence of " <>
                              "an existing substring (with); remove deletes it; read returns a file; list lists the " <>
                              "notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: " <>
                              "lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the " <>
                              "START of your NEXT session — they do NOT change your current context."
  @dream_prompt Enum.join(
                  [
                    "You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.",
                    "Work in this order:",
                    "1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix or remove a claim only when it is contradicted by the transcripts; a claim they do not mention stays (transcripts may be pruned).",
                    "2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.",
                    "3. Merge: fold duplicate or conflicting notes into one.",
                    "4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.",
                    "5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why.",
                    "If nothing needs changing, write nothing and reply HEARTBEAT_OK."
                  ],
                  "\n"
                )
  @default_heartbeat_prompt "Heartbeat. Read your HEARTBEAT.md section and follow it. " <>
                              "If nothing needs attention, reply #{@heartbeat_ok}."

  @doc "The canonical bootstrap file order (identity first, memory last)."
  @spec bootstrap_order() :: [String.t()]
  def bootstrap_order, do: @bootstrap_order

  @doc "The reserved silent-beat reply. A reply containing it produces no report."
  @spec heartbeat_ok() :: String.t()
  def heartbeat_ok, do: @heartbeat_ok

  @doc "The heartbeat wake prompt (drives a persona's — or a dreamer's — pass)."
  @spec heartbeat_prompt() :: String.t()
  def heartbeat_prompt, do: @default_heartbeat_prompt

  @doc "The dream prompt (SPEC §7E \"Dreaming\") — byte-identical in every port."
  @spec dream_prompt() :: String.t()
  def dream_prompt, do: @dream_prompt

  @doc "The notes-on `memory` tool description (byte-exact)."
  @spec memory_notes_description() :: String.t()
  def memory_notes_description, do: @memory_notes_description

  # ---- fromDir: the directory IS the agent --------------------------------

  @doc """
  Compose the discovered bootstrap files under `dir` into one soul string (the frozen
  snapshot). Returns `{soul, found}` where `found` is the discovered files in the
  canonical order. Each present file becomes a `## <file>` section; absent files are
  skipped; a file over 2 MB is truncated with a notice (the on-disk file is untouched).

  Options (memory hierarchy; absent ⇒ byte-identical):

    * `:memory_index_cap` — `true` (preset 200 lines / 25600 bytes) or
      `%{max_lines:, max_bytes:}` (a missing field takes the preset's). Caps the
      injected `MEMORY.md` only, after the 2 MB read cap and before the trim.
    * `:notes` — notes tier on; selects the notice wording pointing at `note:<slug>`.
  """
  @spec compose_soul(String.t(), keyword() | map()) :: {String.t(), [String.t()]}
  def compose_soul(dir, opts \\ []) do
    opts = Map.new(opts)
    cap = resolve_index_cap(opts[:memory_index_cap])

    {sections, found} =
      Enum.reduce(@bootstrap_order, {[], []}, fn f, {sections, found} ->
        case read_capped(Path.join(dir, f)) do
          nil ->
            {sections, found}

          body ->
            body =
              if cap && f == "MEMORY.md",
                do: apply_index_cap(body, cap, opts[:notes] == true),
                else: body

            {["## #{f}\n\n#{String.trim(body)}" | sections], [f | found]}
        end
      end)

    {sections |> Enum.reverse() |> Enum.join("\n\n"), Enum.reverse(found)}
  end

  @doc "The preset index cap: `%{max_lines: 200, max_bytes: 25600}`."
  @spec memory_index_cap_preset() :: %{max_lines: pos_integer(), max_bytes: pos_integer()}
  def memory_index_cap_preset, do: @index_cap_preset

  defp resolve_index_cap(nil), do: nil
  defp resolve_index_cap(false), do: nil
  defp resolve_index_cap(true), do: @index_cap_preset

  defp resolve_index_cap(cap) when is_map(cap) or is_list(cap) do
    cap = Map.new(cap)
    lines = Map.get(cap, :max_lines, @index_cap_preset.max_lines)
    bytes = Map.get(cap, :max_bytes, @index_cap_preset.max_bytes)

    unless is_integer(lines) and lines >= 1 and is_integer(bytes) and bytes >= 1,
      do: raise(ArgumentError, "memoryIndexCap: maxLines and maxBytes must be integers >= 1")

    %{max_lines: lines, max_bytes: bytes}
  end

  defp resolve_index_cap(_),
    do: raise(ArgumentError, "memoryIndexCap: maxLines and maxBytes must be integers >= 1")

  @doc "The exact notice appended to a capped `MEMORY.md` (SPEC §7E)."
  @spec memory_index_notice(pos_integer(), pos_integer(), boolean()) :: String.t()
  def memory_index_notice(max_lines, max_bytes, notes) do
    tail =
      if notes,
        do: "; move detail into notes (memory target \"note:<slug>\").",
        else: " (keep entries short)."

    "\n\n[MEMORY.md index truncated at #{max_lines} lines / #{max_bytes} bytes. " <>
      "Keep MEMORY.md a short index#{tail}]"
  end

  @doc """
  Apply the index cap to `MEMORY.md`'s content (SPEC §7E, exact): split on "\\n" (a
  trailing "\\n" starts no line); within both bounds ⇒ unchanged; else keep the first
  `max_lines` lines, drop trailing lines while over `max_bytes` (a lone over-long line
  is cut at a code-point boundary) and append `memory_index_notice/3`.
  """
  @spec apply_index_cap(String.t(), map(), boolean()) :: String.t()
  def apply_index_cap(content, %{max_lines: ml, max_bytes: mb}, notes \\ false) do
    lines = String.split(content, "\n")
    lines = if String.ends_with?(content, "\n"), do: Enum.drop(lines, -1), else: lines

    if length(lines) <= ml and byte_size(Enum.join(lines, "\n")) <= mb do
      content
    else
      text = lines |> Enum.take(ml) |> drop_over(mb) |> Enum.join("\n") |> cut_utf8(mb)
      text <> memory_index_notice(ml, mb, notes)
    end
  end

  defp drop_over([_] = kept, _mb), do: kept

  defp drop_over(kept, mb) do
    if byte_size(Enum.join(kept, "\n")) > mb, do: drop_over(Enum.drop(kept, -1), mb), else: kept
  end

  # Cut at the last whole code point whose UTF-8 end is <= max.
  defp cut_utf8(s, max) when byte_size(s) <= max, do: s

  defp cut_utf8(s, max) do
    s
    |> String.codepoints()
    |> Enum.reduce_while({[], 0}, fn cp, {acc, n} ->
      if n + byte_size(cp) > max,
        do: {:halt, {acc, n}},
        else: {:cont, {[cp | acc], n + byte_size(cp)}}
    end)
    |> elem(0)
    |> Enum.reverse()
    |> Enum.join()
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
    * `:memory_index_cap` — cap the injected `MEMORY.md` (see `compose_soul/2`)
    * `:notes` — turn on the notes tier of the memory tool (`note:<slug>`, `read`, `list`)
    * `:dream` — `%{every_ms:, store:, instructions:}`: when started with
      `start_agent/3`, a `dreamer/2` over the same dir runs on the same runtime
  """
  @spec from_dir(String.t(), keyword() | map()) :: AgentDef.t()
  def from_dir(dir, opts \\ []) do
    opts = Map.new(opts)
    {soul, _found} = compose_soul(dir, Map.take(opts, [:memory_index_cap, :notes]))
    name = opts[:name] || Path.basename(dir)
    base_tools = opts[:tools] || []

    tools =
      if opts[:memory] == false,
        do: base_tools,
        else: base_tools ++ [memory_tool(dir, notes: opts[:notes] == true)]

    spec = %{
      does: opts[:does] || "persona agent from #{dir}",
      soul: soul,
      model: opts[:model] || "inherit",
      uses: %{tools: tools}
    }

    spec =
      case opts[:dream] do
        nil ->
          spec

        dream ->
          dream = Map.new(dream)
          every = dream[:every_ms]

          unless is_number(every) and every > 0,
            do: raise(ArgumentError, "fromDir: dream.everyMs must be > 0")

          d =
            dreamer(dir,
              store: dream[:store],
              instructions: dream[:instructions],
              name: "#{name}-dreamer"
            )

          Map.put(spec, :dream, %{dreamer: d, every_ms: every})
      end

    Agents.agent(name, spec)
  end

  @doc """
  The built-in dreamer preset (SPEC §7E "Dreaming"): a plain agent whose tools are
  EXACTLY `memory` (notes on, over `dir`) and `transcript_search` (over `:store`).
  Soul = one `## HEARTBEAT.md` section: `dream_prompt/0`, then `DREAM.md` (trimmed, if
  non-empty), then `:instructions` (trimmed, if non-empty). Run one pass with
  `Toolnexus.Agents.run(d, rt_opts, heartbeat_prompt())` or schedule it with
  `start_agent/3`.

  Options: `:store` (required, must export `list/1`), `:instructions`, `:name`
  (default `<basename(dir)>-dreamer`), `:model` (default `"inherit"`).
  """
  @spec dreamer(String.t(), keyword() | map()) :: AgentDef.t()
  def dreamer(dir, opts) do
    opts = Map.new(opts)
    if opts[:store] == nil, do: raise(ArgumentError, "dreamer: store is required")
    search = Toolnexus.TranscriptSearch.tool(opts[:store])
    dream_md = (read_capped(Path.join(dir, "DREAM.md")) || "") |> String.trim()
    extra = (opts[:instructions] || "") |> String.trim()
    parts = Enum.reject([@dream_prompt, dream_md, extra], &(&1 == ""))

    Agents.agent(opts[:name] || "#{Path.basename(dir)}-dreamer", %{
      does: "dreams over the memory in #{dir}",
      soul: "## HEARTBEAT.md\n\n" <> Enum.join(parts, "\n\n"),
      model: opts[:model] || "inherit",
      uses: %{tools: [dream_guard(memory_tool(dir, notes: true)), search]}
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

  With `notes: true` it also takes `target: "note:<slug>"` (`<dir>/memory/<slug>.md`,
  never injected into the soul) and the `read`/`list` actions. Every write is atomic
  (fsynced temp + rename) and serialized per resolved absolute path in this node.
  """
  @spec memory_tool(String.t(), keyword() | map()) :: Toolnexus.Tool.t()
  def memory_tool(dir, opts \\ []) do
    if Map.new(opts)[:notes] == true, do: notes_memory_tool(dir), else: plain_memory_tool(dir)
  end

  @text_description "For add: the entry. For replace/remove: the existing text."
  @with_description "For replace: the replacement text."

  defp plain_memory_tool(dir) do
    Toolnexus.define_tool(%{
      name: "memory",
      description: @memory_description,
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "action" => %{"type" => "string", "enum" => ["add", "replace", "remove"]},
          "target" => %{
            "type" => "string",
            "enum" => ["self", "user"],
            "description" => "self=MEMORY.md (default), user=USER.md"
          },
          "text" => %{"type" => "string", "description" => @text_description},
          "with" => %{"type" => "string", "description" => @with_description}
        },
        "required" => ["action", "text"]
      },
      execute: fn args -> apply_memory(dir, args) end
    })
  end

  defp apply_memory(dir, args) do
    file = self_or_user(dir, to_string(args["target"] || "self"))
    action = to_string(args["action"] || "")

    case rmw(file, action, to_string(args["text"] || ""), args["with"]) do
      # plain success string (wrapped as a success ToolResult by define_tool);
      # a full isError result is reserved for the miss path (§7E / JS reference)
      :ok -> "ok (#{action} → #{Path.basename(file)}); loads next session"
      err -> err
    end
  end

  defp self_or_user(dir, "user"), do: Path.join(dir, "USER.md")
  defp self_or_user(dir, _), do: Path.join(dir, "MEMORY.md")

  defp notes_memory_tool(dir) do
    Toolnexus.define_tool(%{
      name: "memory",
      description: @memory_notes_description,
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "action" => %{
            "type" => "string",
            "enum" => ["add", "replace", "remove", "read", "list"]
          },
          "target" => %{
            "type" => "string",
            "description" =>
              "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md"
          },
          "text" => %{"type" => "string", "description" => @text_description},
          "with" => %{"type" => "string", "description" => @with_description}
        },
        "required" => ["action"]
      },
      execute: fn args -> apply_notes_memory(dir, args) end
    })
  end

  defp apply_notes_memory(dir, %{"action" => "list"}), do: list_notes(Path.join(dir, "memory"))

  defp apply_notes_memory(dir, args) do
    action = to_string(args["action"] || "")
    target = if args["target"] == nil, do: "self", else: to_string(args["target"])

    with {:ok, file, label} <- resolve_target(dir, target) do
      cond do
        action == "read" ->
          read_capped(file) || error("not found: #{target}")

        action not in ["add", "replace", "remove"] ->
          error("unknown action: #{action}")

        not is_binary(args["text"]) ->
          error("text is required")

        true ->
          case rmw(file, action, args["text"], args["with"]) do
            :ok when label in ["MEMORY.md", "USER.md"] ->
              "ok (#{action} → #{label}); loads next session"

            :ok ->
              "ok (#{action} → #{label})"

            err ->
              err
          end
      end
    end
  end

  defp resolve_target(dir, target) when target in ["self", "user"] do
    file = self_or_user(dir, target)
    {:ok, file, Path.basename(file)}
  end

  defp resolve_target(dir, "note:" <> slug) do
    if valid_slug?(slug),
      do: {:ok, Path.join([dir, "memory", slug <> ".md"]), "memory/#{slug}.md"},
      else: error("invalid note slug: #{slug}")
  end

  defp resolve_target(_dir, target), do: error("unknown target: #{target}")

  defp valid_slug?(slug), do: Regex.match?(@note_slug, slug) and not String.contains?(slug, "..")

  defp error(msg), do: %ToolResult{output: msg, is_error: true}

  # `list`: `<slug>\t<bytes>` per regular, validly-named note, slug-ascending by an
  # explicit sort (never directory order).
  defp list_notes(notes_dir) do
    rows =
      case File.ls(notes_dir) do
        {:ok, names} -> names
        _ -> []
      end
      |> Enum.flat_map(fn name ->
        slug = String.replace_suffix(name, ".md", "")

        with true <- String.ends_with?(name, ".md") and valid_slug?(slug),
             # lstat: a symlink (dangling or not) is not a regular file (§7E)
             {:ok, %File.Stat{type: :regular, size: size}} <-
               File.lstat(Path.join(notes_dir, name)) do
          [{slug, size}]
        else
          _ -> []
        end
      end)
      |> Enum.sort()

    if rows == [],
      do: "(no notes)",
      else: Enum.map_join(rows, "\n", fn {slug, size} -> "#{slug}\t#{size}" end)
  end

  # Read-modify-write over the FULL file (§7E: the 2 MB cap is a soul-injection cap,
  # never applied on disk), serialized per resolved absolute path: a `:global` lock
  # keyed by the path makes concurrent callers in this node queue, so no add is lost.
  defp rmw(file, action, text, with) do
    key = {{__MODULE__, real_path(file)}, self()}

    :global.trans(
      key,
      fn ->
        cur =
          case File.read(file) do
            {:ok, body} -> body
            _ -> ""
          end

        case compute_memory(action, cur, text, to_string(with || "")) do
          {:ok, next} -> write_atomic!(file, next)
          {:error, msg} -> error(msg)
        end
      end,
      [node()]
    )
  end

  # The lock key is the REAL path (§7E): symlinks resolved, so two tools reaching one
  # file through different spellings share a lock. A missing file resolves its
  # nearest existing ancestor and re-appends the rest.
  @doc false
  def real_path(file) do
    {existing, rest} = split_existing(Path.expand(file), [])
    Path.join([resolve(existing, 0) | rest])
  end

  defp split_existing(p, rest) do
    parent = Path.dirname(p)

    cond do
      match?({:ok, _}, File.lstat(p)) -> {p, rest}
      parent == p -> {p, rest}
      true -> split_existing(parent, [Path.basename(p) | rest])
    end
  end

  # Resolve every symlink component of an existing absolute path.
  defp resolve(path, depth) when depth > 40, do: path

  defp resolve(path, depth) do
    [root | parts] = Path.split(path)

    Enum.reduce(parts, root, fn part, acc ->
      p = Path.join(acc, part)

      case :file.read_link_all(String.to_charlist(p)) do
        {:ok, target} -> resolve(Path.expand(to_string(target), acc), depth + 1)
        _ -> p
      end
    end)
  end

  # The dream guard (SPEC §7E "Dreaming"): the dreamer's memory tool refuses every action on
  # USER.md and replace/remove on note:log before anything is read or written, so a dream
  # never touches the user model and its audit trail is append-only.
  defp dream_guard(%Toolnexus.Tool{execute: run} = mem) do
    %{
      mem
      | execute: fn args, ctx ->
          case {args["target"], args["action"]} do
            {"user", _} ->
              %Toolnexus.ToolResult{output: "dreamer: USER.md is off-limits", is_error: true}

            {"note:log", a} when a in ["replace", "remove"] ->
              %Toolnexus.ToolResult{
                output: "dreamer: memory/log.md is append-only",
                is_error: true
              }

            _ ->
              run.(args, ctx)
          end
        end
    }
  end

  # Write to a temp file in the same directory, flush it to disk (fsync), then rename
  # over the target, so a crash mid-write cannot leave a torn file.
  defp write_atomic!(file, body) do
    File.mkdir_p!(Path.dirname(file))
    tmp = "#{file}.tmp-#{Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)}"

    try do
      # a new file is created with 0666 & ~umask, the default
      {:ok, fd} = :file.open(tmp, [:write, :exclusive, :binary, :raw])

      try do
        :ok = :file.write(fd, body)
        :ok = :file.sync(fd)
      after
        :file.close(fd)
      end

      keep_mode(file, tmp)
      File.rename!(tmp, file)
    after
      File.rm(tmp)
    end
  end

  # Keep an existing file's permission bits (the rename would otherwise give it the temp's).
  defp keep_mode(file, tmp) do
    with {:unix, _} <- :os.type(),
         {:ok, %File.Stat{mode: mode}} <- File.stat(file) do
      File.chmod!(tmp, Bitwise.band(mode, 0o777))
    end

    :ok
  end

  defp compute_memory("add", cur, text, _with),
    do: {:ok, (String.trim_trailing(cur) <> "\n- #{text}\n") |> String.trim_leading()}

  defp compute_memory("replace", cur, text, replacement) do
    if String.contains?(cur, text),
      # a function replacement: inserted literally, never pattern-expanded (§7E)
      do: {:ok, String.replace(cur, text, fn _ -> replacement end, global: false)},
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
    defstruct [:runtime, :handle, :heartbeat, :dream_heartbeat]

    @type t :: %__MODULE__{
            runtime: pid(),
            handle: pid(),
            heartbeat: pid(),
            dream_heartbeat: pid() | nil
          }
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
    dream = agent.spec[:dream]
    own = Agents.registry(agent)

    if dream && Map.has_key?(own, dream.dreamer.name),
      do:
        raise(
          ArgumentError,
          "startAgent: dreamer name collision: #{dream.dreamer.name} is already registered"
        )

    registry = if dream, do: Map.merge(Agents.registry(dream.dreamer), own), else: own

    rt = Runtime.new(rt_opts |> Map.new() |> Map.put(:registry, registry))
    {:ok, handle} = Runtime.spawn_agent(rt, Runtime.root(rt), agent.name)
    clock = Runtime.ctx(rt).clock

    {:ok, hb} =
      Heartbeat.start_link(%{
        rt: rt,
        handle: handle,
        every_ms: opts[:every_ms] || 30 * 60_000,
        prompt: opts[:prompt] || @default_heartbeat_prompt,
        on_beat: opts[:on_beat],
        clock: clock
      })

    # from_dir(…, dream: …): the dreamer runs on the SAME runtime with its own
    # heartbeat. Its replies are housekeeping — they never reach :on_beat / beats/1;
    # what it did is in memory/log.md.
    dream_hb =
      if dream do
        dh =
          case Runtime.spawn_agent(rt, Runtime.root(rt), dream.dreamer.name) do
            {:ok, dh} ->
              dh

            other ->
              # never leak the persona's heartbeat when the dreamer cannot start
              GenServer.stop(hb)
              Runtime.shutdown(rt)
              raise "startAgent: dreamer spawn failed: #{inspect(other)}"
          end

        {:ok, pid} =
          Heartbeat.start_link(%{
            rt: rt,
            handle: dh,
            every_ms: dream.every_ms,
            prompt: @default_heartbeat_prompt,
            on_beat: nil,
            clock: clock,
            silent: true
          })

        pid
      end

    %Started{runtime: rt, handle: handle, heartbeat: hb, dream_heartbeat: dream_hb}
  end

  @doc "The non-silent beats collected so far (HEARTBEAT_OK replies excluded)."
  @spec beats(Started.t()) :: [String.t()]
  def beats(%Started{heartbeat: hb}), do: Heartbeat.beats(hb)

  @doc "Await the heartbeat process (its beat, if any, has been fully applied)."
  @spec sync(Started.t()) :: :ok
  def sync(%Started{heartbeat: hb, dream_heartbeat: dh}) do
    if dh, do: Heartbeat.sync(dh)
    Heartbeat.sync(hb)
  end

  @doc "Stop the heartbeat and tear the runtime down."
  @spec stop(Started.t()) :: :ok
  def stop(%Started{runtime: rt, heartbeat: hb, dream_heartbeat: dh}) do
    for pid <- [hb, dh], pid != nil, Process.alive?(pid), do: GenServer.stop(pid)
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

          if not r.is_error and not String.contains?(r.text, @heartbeat_ok) and
               not Map.get(st, :silent, false) do
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
