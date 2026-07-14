defmodule Toolnexus.Skill do
  @moduledoc """
  Dynamic agent-skill source (SPEC §3). Mirrors `js/src/skill.ts`:
  discover `**/SKILL.md`, parse frontmatter, and expose ONE `skill` tool that
  loads a skill's instructions + sampled resources on demand (progressive
  disclosure).

  Beyond on-disk discovery the source also accepts skills supplied as data
  (S1) and/or a lazy provider. Directory-sourced skills keep the exact
  `file://` base + on-disk sibling sampling (byte-identical); data-sourced
  skills use a logical `skill://name/` base + a supplied resource list and
  never touch disk (S4).
  """

  alias Toolnexus.{Tool, ToolResult}

  @skill_tool_description "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n\nUse this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.\n\nThe skill name must match one of the skills listed in your system prompt."

  @skills_prompt_preamble "Skills provide specialized instructions and workflows for specific tasks.\nUse the skill tool to load a skill when a task matches its description."

  @doc "The `skill` loader tool description (verbatim from opencode's skill.txt)."
  @spec skill_tool_description() :: String.t()
  def skill_tool_description, do: @skill_tool_description

  @doc "Instruction preamble prepended to the skills prompt when ≥1 described skill exists."
  @spec skills_prompt_preamble() :: String.t()
  def skills_prompt_preamble, do: @skills_prompt_preamble

  defmodule Info do
    @moduledoc "One discovered skill (SPEC §3)."
    @enforce_keys [:name, :location, :content]
    defstruct [:name, :description, :location, :content, origin: :fs, resources: nil, base: nil]

    @type t :: %__MODULE__{
            name: String.t(),
            description: String.t() | nil,
            location: String.t(),
            content: String.t(),
            origin: :fs | :logical,
            resources: [String.t()] | nil,
            base: String.t() | nil
          }
  end

  defmodule Source do
    @moduledoc "The built skill source: skills + the single `skill` tool + system-prompt catalog."
    @enforce_keys [:skills, :tool, :prompt]
    defstruct [:skills, :tool, :prompt]

    @type t :: %__MODULE__{
            skills: [Toolnexus.Skill.Info.t()],
            tool: Toolnexus.Tool.t(),
            prompt: String.t()
          }
  end

  @frontmatter_re ~r/\A---\r?\n(.*?)\r?\n---\r?\n?(.*)\z/s

  # ── Public API ──────────────────────────────────────────────────────────────

  @doc """
  Discover skills (dirs and/or data/provider) and build the `skill` loader tool.

  Options (keyword list or map):

    * `:dirs` — a skill root or list of roots to glob for `**/SKILL.md`
    * `:skills` — skills supplied as data maps: `%{name:, description:, content:, resources:, base:}` (S1)
    * `:provider` — 0-arity fun returning a skill-data list, resolved once; a
      failing provider is isolated with a warning (S1)
    * `:filter` — `name => bool` allowlist/droplist, `nil`/empty ⇒ all (S2)
    * `:sample_limit` — sibling-file cap: `0` ⇒ default 10, `n > 0` ⇒ cap,
      `-1` ⇒ omit `<skill_files>` (S5)

  A bare binary or list of binaries is shorthand for `dirs: ...`.
  """
  @spec load(String.t() | [String.t()] | keyword() | map()) :: Source.t()
  def load(input) do
    opts = normalize_opts(input)
    sample_limit = opt(opts, :sample_limit) || 0
    {skills, _skipped} = merge_candidates(collect_candidates(opts))
    skills = apply_filter(skills, opt(opts, :filter))
    by_name = Map.new(skills, &{&1.name, &1})

    tool = %Tool{
      name: "skill",
      description: @skill_tool_description,
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "name" => %{"type" => "string", "description" => "The name of the skill to load"}
        },
        "required" => ["name"],
        "additionalProperties" => false
      },
      source: "skill",
      execute: fn args, _ctx -> execute_skill(args, by_name, sample_limit) end
    }

    %Source{skills: skills, tool: tool, prompt: skills_prompt(skills)}
  end

  @doc """
  Discover + validate skills from the same sources `load/1` accepts, returning
  parsed skills plus typed skip reasons — no toolkit wired (SPEC §3, S3). The
  inventory is UNFILTERED (it exists to author/validate the allowlist).

  Skip reasons: `"missing-name" | "malformed-frontmatter" | "duplicate-name" | "unreadable"`.
  """
  @spec list(String.t() | [String.t()] | keyword() | map()) :: %{
          skills: [Info.t()],
          skipped: [%{location: String.t(), reason: String.t()}]
        }
  def list(input) do
    opts = normalize_opts(input)
    {skills, skipped} = merge_candidates(collect_candidates(opts))
    %{skills: skills, skipped: skipped}
  end

  # ── Options ─────────────────────────────────────────────────────────────────

  defp normalize_opts(input) when is_binary(input), do: %{dirs: [input]}

  defp normalize_opts(input) when is_list(input) do
    if Keyword.keyword?(input) and input != [], do: Map.new(input), else: %{dirs: input}
  end

  defp normalize_opts(input) when is_map(input), do: input

  defp opt(opts, key), do: Map.get(opts, key)

  # ── Frontmatter ─────────────────────────────────────────────────────────────

  # Parse YAML frontmatter (between the leading `---` fences) with a real YAML
  # parser. Scalar values are coerced to trimmed strings; non-scalars dropped.
  # `malformed` is true only when fences are present but the YAML fails to parse.
  defp parse_frontmatter(text) do
    case Regex.run(@frontmatter_re, text) do
      nil ->
        {%{}, text, false}

      [_, yaml, body] ->
        case safe_yaml(yaml) do
          {:ok, parsed} when is_map(parsed) -> {coerce_scalars(parsed), body, false}
          {:ok, _non_map} -> {%{}, body, false}
          :error -> {%{}, body, true}
        end
    end
  end

  defp safe_yaml(yaml) do
    case YamlElixir.read_from_string(yaml) do
      {:ok, parsed} -> {:ok, parsed}
      {:error, _} -> :error
    end
  rescue
    _ -> :error
  end

  defp coerce_scalars(parsed) do
    for {key, value} <- parsed, scalar?(value), into: %{} do
      # Trim so block-scalar trailing newlines (chomping differs subtly between
      # YAML libs) don't leak — keeps the ports byte-identical.
      {to_string(key), value |> to_string() |> String.trim()}
    end
  end

  defp scalar?(v), do: is_binary(v) or is_number(v) or is_boolean(v)

  # ── Filesystem traversal (mirrors js walkSkillFiles / sampleSiblingFiles) ───

  # DFS with an explicit stack (push/pop at the head = JS push/pop at the end).
  # Follows symlinked directories and files; guards against symlink cycles by
  # tracking already-visited directory identities ({device, inode} — same
  # effect as the JS realpath set).
  defp walk_skill_files(root), do: walk([root], MapSet.new(), [])

  defp walk([], _seen, out), do: out

  defp walk([dir | rest], seen, out) do
    case File.ls(dir) do
      {:error, _} ->
        walk(rest, seen, out)

      {:ok, entries} ->
        # Node's readdirSync (libuv scandir) returns entries sorted; Erlang's
        # list_dir does not — sort to keep traversal order byte-identical.
        {new_dirs, seen, out} =
          Enum.reduce(Enum.sort(entries), {[], seen, out}, fn entry, {dirs, seen, out} ->
            full = Path.join(dir, entry)

            case classify(full) do
              {:dir, _} when entry in ["node_modules", ".git"] ->
                {dirs, seen, out}

              {:dir, id} ->
                if MapSet.member?(seen, id),
                  do: {dirs, seen, out},
                  else: {[full | dirs], MapSet.put(seen, id), out}

              :file when entry == "SKILL.md" ->
                {dirs, seen, out ++ [full]}

              _ ->
                {dirs, seen, out}
            end
          end)

        # new_dirs is head-accumulated (reversed entry order); prepending it as-is
        # makes the LAST directory entry the next popped — identical to JS
        # stack.push(...entries) + stack.pop().
        walk(new_dirs ++ rest, seen, out)
    end
  end

  defp sample_sibling_files(dir, limit), do: sample([dir], MapSet.new(), [], limit)

  defp sample([], _seen, out, _limit), do: out
  defp sample(_stack, _seen, out, limit) when length(out) >= limit, do: out

  defp sample([dir | rest], seen, out, limit) do
    case File.ls(dir) do
      {:error, _} ->
        sample(rest, seen, out, limit)

      {:ok, entries} ->
        # Sorted for parity with Node readdirSync — see walk/3.
        {new_dirs, seen, out} =
          Enum.reduce_while(Enum.sort(entries), {[], seen, out}, fn entry, {dirs, seen, out} ->
            if length(out) >= limit do
              {:halt, {dirs, seen, out}}
            else
              full = Path.join(dir, entry)

              case classify(full) do
                {:dir, _} when entry in ["node_modules", ".git"] ->
                  {:cont, {dirs, seen, out}}

                {:dir, id} ->
                  if MapSet.member?(seen, id),
                    do: {:cont, {dirs, seen, out}},
                    else: {:cont, {[full | dirs], MapSet.put(seen, id), out}}

                :file when entry != "SKILL.md" ->
                  {:cont, {dirs, seen, out ++ [full]}}

                _ ->
                  {:cont, {dirs, seen, out}}
              end
            end
          end)

        sample(new_dirs ++ rest, seen, out, limit)
    end
  end

  # Classify a path (following symlinks) as {:dir, identity} | :file | :skip.
  defp classify(path) do
    case File.stat(path) do
      {:ok, %File.Stat{type: :directory, major_device: dev, inode: inode}} -> {:dir, {dev, inode}}
      {:ok, %File.Stat{type: :regular}} -> :file
      _ -> :skip
    end
  end

  # ── Candidates (parsed skill OR typed skip) ────────────────────────────────

  defp candidates_from_dir(root) do
    is_dir =
      case File.stat(root) do
        {:ok, %File.Stat{type: :directory}} -> true
        _ -> false
      end

    if is_dir do
      Enum.map(walk_skill_files(root), fn file ->
        case File.read(file) do
          {:error, _} ->
            {:skip, %{location: file, reason: "unreadable"}}

          {:ok, text} ->
            case parse_frontmatter(text) do
              {_data, _content, true} ->
                {:skip, %{location: file, reason: "malformed-frontmatter"}}

              {data, content, false} ->
                case data["name"] do
                  name when name in [nil, ""] ->
                    {:skip, %{location: file, reason: "missing-name"}}

                  name ->
                    {:info,
                     %Info{
                       name: name,
                       description: data["description"],
                       location: file,
                       content: content,
                       origin: :fs
                     }}
                end
            end
        end
      end)
    else
      warn("[toolnexus] skills dir not found: #{root}")
      []
    end
  end

  defp candidates_from_defs(defs) do
    Enum.map(defs, fn d ->
      name = def_get(d, :name)

      if name in [nil, ""] do
        {:skip, %{location: def_get(d, :base) || "skill://", reason: "missing-name"}}
      else
        name = to_string(name)

        base =
          case def_get(d, :base) do
            b when b in [nil, ""] -> "skill://#{name}/"
            b -> b
          end

        {:info,
         %Info{
           name: name,
           description: def_get(d, :description),
           location: base,
           content: def_get(d, :content) || "",
           origin: :logical,
           resources: def_get(d, :resources) || [],
           base: base
         }}
      end
    end)
  end

  defp def_get(d, key), do: Map.get(d, key) || Map.get(d, Atom.to_string(key))

  defp collect_candidates(opts) do
    roots =
      case opt(opts, :dirs) do
        nil -> []
        dirs when is_list(dirs) -> dirs
        dir -> [dir]
      end

    provider_defs =
      case opt(opts, :provider) do
        nil -> []
        provider -> resolve_provider(provider)
      end

    Enum.flat_map(roots, &candidates_from_dir/1) ++
      candidates_from_defs((opt(opts, :skills) || []) ++ provider_defs)
  end

  # Resolve the lazy provider once; a failing provider is isolated (other
  # sources still load), mirroring MCP per-server isolation.
  defp resolve_provider(provider) do
    case provider.() do
      defs when is_list(defs) ->
        defs

      other ->
        warn("[toolnexus] skill provider failed: expected a list, got: #{inspect(other)}")
        []
    end
  rescue
    e ->
      warn("[toolnexus] skill provider failed: #{Exception.message(e)}")
      []
  end

  # Dedupe candidates by name (first-wins); later duplicates become skips.
  defp merge_candidates(cands) do
    {skills_rev, skipped_rev, _names} =
      Enum.reduce(cands, {[], [], MapSet.new()}, fn
        {:skip, skip}, {skills, skipped, names} ->
          {skills, [skip | skipped], names}

        {:info, info}, {skills, skipped, names} ->
          if MapSet.member?(names, info.name) do
            warn(
              "[toolnexus] duplicate skill name \"#{info.name}\" (#{info.location}) — keeping first"
            )

            {skills, [%{location: info.location, reason: "duplicate-name"} | skipped], names}
          else
            {[info | skills], skipped, MapSet.put(names, info.name)}
          end
      end)

    {Enum.reverse(skills_rev), Enum.reverse(skipped_rev)}
  end

  # ── Filter (S2 — semantics identical to the MCP tools filter / builtins) ───

  defp apply_filter(skills, filter) when filter in [nil, %{}], do: skills

  defp apply_filter(skills, filter) do
    names = MapSet.new(skills, & &1.name)

    for {k, _} <- filter, not MapSet.member?(names, k) do
      warn("[toolnexus] skill filter name \"#{k}\" matched no skill")
    end

    has_true = Enum.any?(filter, fn {_, v} -> v == true end)

    Enum.filter(skills, fn %Info{name: name} ->
      if has_true,
        do: Map.get(filter, name) == true,
        else: Map.get(filter, name) != false
    end)
  end

  # ── The `skill` tool body ───────────────────────────────────────────────────

  defp execute_skill(args, by_name, sample_limit) do
    name = to_string(Map.get(args || %{}, "name") || Map.get(args || %{}, :name) || "")

    case Map.get(by_name, name) do
      nil ->
        available =
          case by_name |> Map.keys() |> Enum.sort() do
            [] -> "none"
            names -> Enum.join(names, ", ")
          end

        ToolResult.error("Skill \"#{name}\" not found. Available skills: #{available}")

      info ->
        # eff_limit: 0 ⇒ default 10 (byte-identical to today), n>0 ⇒ cap, -1 ⇒ omit.
        eff_limit = if sample_limit == 0, do: 10, else: sample_limit

        {base, files, meta_dir, emit_files} =
          case info.origin do
            :logical ->
              base = info.base || "skill://#{info.name}/"
              res = info.resources || []
              emit = eff_limit != -1 and res != []
              files = if eff_limit > 0, do: Enum.take(res, eff_limit), else: res
              {base, files, base, emit}

            _fs ->
              dir = Path.dirname(info.location)
              files = if eff_limit == -1, do: [], else: sample_sibling_files(dir, eff_limit)
              {file_url(dir), files, dir, eff_limit != -1}
          end

        lines =
          [
            "<skill_content name=\"#{info.name}\">",
            "# Skill: #{info.name}",
            "",
            String.trim(info.content),
            "",
            "Base directory for this skill: #{base}",
            "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory."
          ] ++
            if emit_files do
              [
                "Note: file list is sampled.",
                "",
                "<skill_files>",
                Enum.map_join(files, "\n", &"<file>#{&1}</file>"),
                "</skill_files>"
              ]
            else
              []
            end

        %ToolResult{
          output: Enum.join(lines ++ ["</skill_content>"], "\n"),
          is_error: false,
          metadata: %{name: info.name, dir: meta_dir}
        }
    end
  end

  # file:// URL for an absolute directory (mirrors Node pathToFileURL).
  defp file_url(dir) do
    "file://" <>
      URI.encode(Path.expand(dir), fn ch -> ch == ?/ or URI.char_unreserved?(ch) end)
  end

  # ── System-prompt catalog (mirrors opencode Skill.fmt) ─────────────────────

  defp skills_prompt(skills) do
    described =
      skills
      |> Enum.filter(&(&1.description != nil))
      |> Enum.sort_by(& &1.name)

    if described == [] do
      "No skills are currently available."
    else
      Enum.join(
        [
          @skills_prompt_preamble,
          "",
          "## Available Skills"
          | Enum.map(described, &"- **#{&1.name}**: #{&1.description}")
        ],
        "\n"
      )
    end
  end

  defp warn(msg), do: IO.puts(:stderr, msg)
end
