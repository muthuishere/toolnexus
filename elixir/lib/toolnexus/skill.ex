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
    defstruct [:skills, :tool, :prompt, skipped: []]

    @type t :: %__MODULE__{
            skills: [Toolnexus.Skill.Info.t()],
            tool: Toolnexus.Tool.t(),
            prompt: String.t(),
            skipped: [%{location: String.t(), reason: String.t(), detail: String.t() | nil}]
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
  Every candidate the loader REFUSED rides back on `Source.skipped` as
  `%{location:, reason:, detail:}` (ADR 0028 / D6, addendum A2) — returned data, not a
  hook, so a host never needs `list/1` to learn that 6 of 87 skills vanished.

  A bare binary or list of binaries is shorthand for `dirs: ...`.
  """
  @spec load(String.t() | [String.t()] | keyword() | map()) :: Source.t()
  def load(input) do
    opts = normalize_opts(input)
    sample_limit = opt(opts, :sample_limit) || 0
    {skills, skipped} = merge_candidates(collect_candidates(opts))
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

    %Source{skills: skills, tool: tool, prompt: skills_prompt(skills), skipped: skipped}
  end

  @doc """
  Discover + validate skills from the same sources `load/1` accepts, returning
  parsed skills plus typed skip reasons — no toolkit wired (SPEC §3, S3). The
  inventory is UNFILTERED (it exists to author/validate the allowlist).

  Skip reasons: `"missing-name" | "malformed-frontmatter" | "duplicate-name" | "unreadable"` —
  byte-identical to before. Each skip also carries `detail`: the NATIVE parser error
  where there was one, `nil` otherwise (ADR 0028 / D6).
  """
  @spec list(String.t() | [String.t()] | keyword() | map()) :: %{
          skills: [Info.t()],
          skipped: [%{location: String.t(), reason: String.t(), detail: String.t() | nil}]
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
  # `malformed` is true only when fences are present but BOTH the YAML parser and
  # the lenient rescue below refused the block; `detail` carries the native error.
  #
  # LENIENT READ, INVERTED (ADR 0028 / D6). YAML runs FIRST, always. The line-wise
  # `key: rest-of-line` read runs ONLY on a block a real YAML parser has ALREADY
  # refused — by construction such a block has no YAML semantics to preserve, so a
  # block scalar (`description: |`) can never be misparsed by it. Line-wise FIRST is
  # forbidden: it misreads exactly the files the six YAML ports handle today.
  defp parse_frontmatter(text) do
    case Regex.run(@frontmatter_re, text) do
      nil ->
        {%{}, text, false, nil}

      [_, yaml, body] ->
        case safe_yaml(yaml) do
          {:ok, parsed} when is_map(parsed) ->
            data = coerce_scalars(parsed)

            # A10: YAML libraries DISAGREE about `description: [unterminated, flow`.
            # yaml_elixir throws; JS's `yaml` recovers it into a sequence. So the
            # rescue must ALSO run when the parse SUCCEEDS but leaves `name` or
            # `description` structurally wrong (a mapping/sequence where a scalar
            # belongs) — the non-scalar is DROPPED by `coerce_scalars`, and the
            # rescue may only fill what is now MISSING. Invariant: a file never
            # gains an invented description and never keeps a structurally-wrong one.
            if structurally_wrong?(parsed),
              do: rescued(data, yaml, body, false, "non-scalar name/description dropped"),
              else: {data, body, false, nil}

          {:ok, _non_map} ->
            # valid YAML that is not a mapping: same rescue, and the skip reason
            # stays `missing-name`, byte-identical to before.
            rescued(%{}, yaml, body, false, "frontmatter is not a YAML mapping")

          {:error, detail} ->
            # YAML refused outright. Rescue `name`/`description`, or stay refused.
            rescued(%{}, yaml, body, true, detail)
        end
    end
  end

  # Merge the lenient rescue UNDER the YAML-derived data — it may only fill keys
  # YAML did not supply. `malformed` distinguishes the two skip reasons: a parser
  # that THREW yields `malformed-frontmatter`, anything else `missing-name`.
  defp rescued(data, yaml, body, malformed, detail) do
    data = Map.merge(lenient_frontmatter(yaml), data)
    {data, body, data["name"] in [nil, ""] and malformed, detail}
  end

  defp structurally_wrong?(parsed) do
    Enum.any?(["name", "description"], fn key ->
      case Map.fetch(parsed, key) do
        {:ok, v} -> not is_nil(v) and not scalar?(v)
        :error -> false
      end
    end)
  end

  defp safe_yaml(yaml) do
    case YamlElixir.read_from_string(yaml) do
      {:ok, parsed} -> {:ok, parsed}
      {:error, e} -> {:error, yaml_detail(e)}
    end
  rescue
    e -> {:error, yaml_detail(e)}
  end

  defp yaml_detail(e) when is_exception(e), do: Exception.message(e)
  defp yaml_detail(e) when is_binary(e), do: e
  defp yaml_detail(e), do: inspect(e)

  # Column 0, ordinary key characters, exactly one `:`; the value is the rest of the line.
  @lenient_line_re ~r/\A([A-Za-z0-9_][A-Za-z0-9_.\-]*):[ \t]*(.*)\z/
  @lenient_keys ["name", "description"]
  # A value opening one of these starts a construct the lenient read does not
  # implement (block scalar, anchor, alias, flow collection, tag). Take NOTHING
  # rather than garbage: a half-broken block scalar degrades to "no description".
  @lenient_openers ["|", ">", "&", "*", "[", "{", "!"]

  defp lenient_frontmatter(block) do
    block
    |> String.split(~r/\r?\n/)
    |> Enum.reduce(%{}, fn raw, acc ->
      cond do
        # indented continuation, comment, or blank — not a top-level key
        raw == "" or String.starts_with?(raw, [" ", "\t", "#"]) ->
          acc

        true ->
          case Regex.run(@lenient_line_re, raw) do
            [_, key, value] -> take_lenient(acc, key, String.trim(value))
            _ -> acc
          end
      end
    end)
  end

  defp take_lenient(acc, key, value) do
    cond do
      key not in @lenient_keys -> acc
      # FIRST WINS, exactly like the YAML ports' duplicate-key behaviour
      Map.has_key?(acc, key) -> acc
      value == "" -> acc
      String.first(value) in @lenient_openers -> acc
      true -> Map.put(acc, key, unquote_scalar(value))
    end
  end

  defp unquote_scalar(v) do
    first = String.first(v)

    if String.length(v) > 1 and first in ["\"", "'"] and String.last(v) == first,
      do: String.slice(v, 1..-2//1),
      else: v
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
        # `File.ls/1` is NOT sorted by the runtime. Sorted here by Unicode code point
        # so the walk is deterministic; the candidate ORDER is then decided by the
        # explicit global sort in `candidates_from_dir/1` (A15), not by this walk.
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

  # A25: COLLECT every candidate, SORT by the path RELATIVE to the skill directory in
  # plain Unicode code-point order, THEN truncate to the cap — in that order.
  #
  # Two rules are being closed here. Capping MID-TRAVERSAL let the FILESYSTEM decide
  # WHICH files the model saw, not merely their order (ADR-0004 K1, sort-before-sample).
  # And ordering by per-directory traversal is a function of the STACK DISCIPLINE: it
  # only agrees across ports if every port reproduces the same walk, and for a nested
  # resource tree (`scripts/`, `reference/`) it interleaves differently from a global
  # relative-path sort. The two coincide only for flat siblings — the common case, not
  # every case. So the order comes from ONE global sort over the full candidate set.
  # The `<skill_files>` sample KEEPS its absolute paths — SPEC §3 pins that shape,
  # and unlike `grep` there is no sort-key/display split to fix here: every entry
  # shares the skill directory as a constant prefix, so ordering by the relative
  # path and ordering by the emitted absolute path are THE SAME ORDER. Do not
  # "align" this with grep's relative output; that would create the divergence.
  #
  # A28: the sort key IS the emitted string. Paths are relative to the skill
  # directory with `/` as the separator on every platform (elixir gets this free on
  # POSIX; `Path.relative_to/2` never emits a backslash here), so this can never
  # order by one string and display another.
  defp sample_sibling_files(dir, limit) do
    dir
    |> collect_samples([dir], MapSet.new(), [])
    |> Enum.sort_by(&Path.relative_to(&1, dir))
    |> Enum.take(limit)
  end

  defp collect_samples(_root, [], _seen, out), do: out

  defp collect_samples(root, [dir | rest], seen, out) do
    case File.ls(dir) do
      {:error, _} ->
        collect_samples(root, rest, seen, out)

      {:ok, entries} ->
        # Traversal order no longer decides anything user-visible — the global sort
        # above does — but the walk stays deterministic so the cycle guard and the
        # candidate SET are stable.
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

              :file when entry != "SKILL.md" ->
                {dirs, seen, out ++ [full]}

              _ ->
                {dirs, seen, out}
            end
          end)

        collect_samples(root, new_dirs ++ rest, seen, out)
    end
  end

  # {depth, path} — depth ASCENDING first, then the whole relative path compared by
  # code point (Elixir's binary term order IS code-point order for valid UTF-8).
  defp discovery_key(file, root) do
    rel = Path.relative_to(file, root)
    {length(Path.split(rel)), rel}
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
      # A1/A15: DISCOVERY ORDER is specified, not incidental. Roots keep the CALLER'S
      # order; within a root, candidates are sorted by DEPTH ASCENDING (number of path
      # segments relative to the logical base), then by Unicode CODE POINT as the
      # tie-break WITHIN a depth. First-wins then resolves duplicates, so a skill at
      # the top level always beats a nested copy of itself.
      #
      # Code point ALONE does not deliver that rule: `docx`/`pdf`/`pptx` beat
      # `synced/<uuid>/…` only because `d`/`p` sort before `s`, while `xlsx` LOSES to
      # `synced/<uuid>/xlsx` — the winner would depend on the skill's first letter
      # relative to a sibling DIRECTORY's name. Depth first removes that accident.
      # Symlinks sort at their DISCOVERED path, never their target; no locale
      # collation, no case folding.
      root
      |> walk_skill_files()
      |> Enum.sort_by(&discovery_key(&1, root))
      |> Enum.map(fn file ->
        case File.read(file) do
          {:error, _} ->
            {:skip, %{location: file, reason: "unreadable", detail: nil}}

          {:ok, text} ->
            case parse_frontmatter(text) do
              {_data, _content, true, detail} ->
                {:skip, %{location: file, reason: "malformed-frontmatter", detail: detail}}

              {data, content, false, detail} ->
                case data["name"] do
                  name when name in [nil, ""] ->
                    {:skip, %{location: file, reason: "missing-name", detail: detail}}

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
        {:skip, %{location: def_get(d, :base) || "skill://", reason: "missing-name", detail: nil}}
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

            {skills,
             [%{location: info.location, reason: "duplicate-name", detail: nil} | skipped], names}
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
          # A22: also user-visible. Code point, by the same construction as the
          # §0.10 prompt sort — see `skills_prompt/1`.
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
              # RULED OUT of A25/A26 deliberately: `resources` is a HOST-SUPPLIED
              # list, not a filesystem read. Its order is the caller's, and sorting
              # it would overwrite a choice the host made. The cap is a plain prefix.
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
    # A22: the §0.10 skills prompt has its OWN ordering, separate from discovery
    # order, and SPEC §0.10 pins it byte-identical across ports. This sort is by
    # Unicode CODE POINT **by construction**: Erlang term order on binaries is
    # byte-wise, and byte-wise over UTF-8 IS code-point order. It must never become
    # a locale-aware compare (`localeCompare` is machine-dependent) nor a UTF-16
    # code-unit compare — both diverge from this above U+FFFF.
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
