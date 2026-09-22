defmodule Toolnexus.Builtin do
  @moduledoc """
  Built-in tool source (`source: "builtin"`) — SPEC §4A.

  The default toolset toolnexus ships so an agent can act with zero custom
  wiring: opencode's built-ins, ported with identical tool names + input
  schemas. Every tool obeys the uniform Tool/ToolResult contract: a failure is
  a `%ToolResult{is_error: true}`, never a raise across the boundary. Paths
  resolve relative to the process working directory unless absolute.

  The ten tools, in fixed parity order: bash, read, write, edit, grep, glob,
  webfetch, question, apply_patch, todowrite.
  """

  alias Toolnexus.{ContentPart, Tool, ToolResult, Request}

  @ignore_dirs ["node_modules", ".git"]
  @file_marker ~r/^\*\*\* (Add|Update|Delete) File: (.+)$/

  # ---------------------------------------------------------------------------
  # toggle semantics (SPEC §0.11 / §4 assembly)
  # ---------------------------------------------------------------------------

  @doc """
  Whether the builtin source is on. Default ON. Same precedence as MCP:
  `disabled: true` wins, else `enabled: false` disables, otherwise enabled.
  """
  @spec enabled?(nil | boolean() | map()) :: boolean()
  def enabled?(nil), do: true
  def enabled?(cfg) when is_boolean(cfg), do: cfg

  def enabled?(cfg) when is_map(cfg) do
    cond do
      cfg_get(cfg, :disabled) == true -> false
      cfg_get(cfg, :enabled) == false -> false
      true -> true
    end
  end

  @doc """
  Resolve the active builtin tools for a config. Whole-source-off wins and
  returns `[]`. Otherwise all ten are on; a `tools` name→bool map drops any
  tool mapped to `false` (all-on baseline; `true`/absent stay on; unknown
  names are ignored). SPEC §4A.
  """
  @spec load(nil | boolean() | map()) :: [Tool.t()]
  def load(cfg \\ nil) do
    if enabled?(cfg) do
      map = if is_map(cfg), do: cfg_get(cfg, :tools), else: nil
      all = tools(cfg)

      case map do
        m when is_map(m) -> Enum.filter(all, fn t -> Map.get(m, t.name) != false end)
        _ -> all
      end
    else
      []
    end
  end

  defp cfg_get(cfg, key), do: Map.get(cfg, key, Map.get(cfg, Atom.to_string(key)))

  @doc """
  Build the ten built-in tools (each `source: "builtin"`). The order is fixed
  for parity: bash, read, write, edit, grep, glob, webfetch, question,
  apply_patch, todowrite.
  """
  @spec tools(nil | boolean() | map()) :: [Tool.t()]
  def tools(cfg \\ nil) do
    e = env(cfg)

    [
      bash_tool(e),
      read_tool(e),
      write_tool(e),
      edit_tool(e),
      grep_tool(e),
      glob_tool(e),
      webfetch_tool(),
      question_tool(),
      apply_patch_tool(e),
      todowrite_tool()
    ]
  end

  # ---------------------------------------------------------------------------
  # the host boundary: interpreter, base directory, confinement (SPEC §4A, ADR 0034)
  # ---------------------------------------------------------------------------

  # How long a job gets between "please stop" and "stop". Fixed, and identical in
  # every port, so a timeout means the same thing everywhere.
  @kill_grace_ms 2000

  @win_reserved ~w(CON PRN AUX NUL COM1 COM2 COM3 COM4 COM5 COM6 COM7 COM8 COM9
                   LPT1 LPT2 LPT3 LPT4 LPT5 LPT6 LPT7 LPT8 LPT9)

  @doc false
  # The interpreters tried when the host names none. `%COMSPEC%` leads on Windows
  # because PowerShell is routinely blocked by execution or application-control
  # policy, while `%COMSPEC%` is always present.
  def shell_candidates do
    if windows?() do
      comspec = System.get_env("COMSPEC")

      (if comspec, do: [[comspec, "/d", "/s", "/c"]], else: []) ++
        [
          ["cmd.exe", "/d", "/s", "/c"],
          ["pwsh", "-NoProfile", "-Command"],
          ["powershell", "-NoProfile", "-Command"],
          ["bash", "-lc"]
        ]
    else
      [["/bin/sh", "-c"], ["sh", "-c"]]
    end
  end

  defp windows?, do: match?({:win32, _}, :os.type())

  # `Port.open({:spawn_executable, exe})` needs an ABSOLUTE path and reports the
  # same `:enoent` for a bare name as for a program that does not exist, so the
  # name is resolved here rather than at spawn time.
  defp resolve_exe(name) do
    cond do
      String.contains?(name, "/") or String.contains?(name, "\\") ->
        if File.exists?(name), do: name, else: nil

      true ->
        case :os.find_executable(String.to_charlist(name)) do
          false -> nil
          path -> List.to_string(path)
        end
    end
  end

  @doc false
  # The host boundary, resolved once at load time: a missing interpreter is a
  # configuration fact, and turn fourteen of a paid run is the expensive place to
  # learn it.
  def env(cfg) do
    map = if is_map(cfg), do: cfg, else: %{}
    base_dir = cfg_get(map, :base_dir) || cfg_get(map, :baseDir) || ""
    confine = cfg_get(map, :confine_to_base_dir) == true or cfg_get(map, :confineToBaseDir) == true

    {shell, shell_error} =
      case cfg_get(map, :shell) do
        [_ | _] = given ->
          argv = Enum.map(given, &to_string/1)
          exe = resolve_exe(hd(argv))

          if exe,
            do: {[exe | tl(argv)], nil},
            else: {[], "shell #{hd(argv)} does not resolve"}

        _ ->
          detect_shell()
      end

    %{shell: shell, shell_error: shell_error, base_dir: to_string(base_dir), confine: confine}
  end

  defp detect_shell do
    tried = Enum.map(shell_candidates(), &hd/1)

    found =
      Enum.find_value(shell_candidates(), fn argv ->
        case resolve_exe(hd(argv)) do
          nil -> nil
          exe -> [exe | tl(argv)]
        end
      end)

    if found do
      {found, nil}
    else
      {[],
       "no shell interpreter found (tried: #{Enum.join(tried, ", ")}); set builtins shell, " <>
         "or disable the bash builtin with builtins tools bash: false"}
    end
  end

  @doc false
  # The interpreter the builtins resolved for `bash` — what a host prints, and
  # what `metadata.shell` carries on every bash result.
  def shell(cfg \\ nil) do
    case env(cfg) do
      %{shell_error: nil, shell: argv} -> {:ok, argv}
      %{shell_error: message} -> {:error, message}
    end
  end

  defp env_dir(%{base_dir: ""}), do: File.cwd!()
  defp env_dir(%{base_dir: base}), do: base

  # Map a tool-supplied path onto the filesystem: relative to base_dir (or, with
  # none, exactly as before), and refused when confinement is on and the canonical
  # target is outside the base.
  defp resolve_path(%{base_dir: base, confine: confine}, p) do
    cond do
      confine and base == "" ->
        {:error, "confine_to_base_dir is set but base_dir is empty"}

      true ->
        full = if base != "" and not absolute?(p), do: Path.join(base, p), else: p

        cond do
          not confine ->
            {:ok, full}

          windows?() and reserved_device?(full) ->
            {:error, "#{p} names a reserved device, which is not a file inside #{base}"}

          true ->
            canon_base = canonical(base)
            canon_target = canonical(full)

            if canon_target == canon_base or String.starts_with?(canon_target, canon_base <> "/") do
              {:ok, full}
            else
              {:error, "#{p} resolves outside baseDir #{base}"}
            end
        end
    end
  end

  defp absolute?(p), do: Path.type(p) != :relative

  defp reserved_device?(p) do
    stem = p |> Path.basename() |> String.split(".") |> hd() |> String.trim() |> String.upcase()
    stem in @win_reserved
  end

  # Resolve a path for comparison. Every COMPONENT is resolved, not just the last
  # one: a link in the middle of a path is the escape a leaf-only check misses —
  # `base/link/secret.txt` exists, is not itself a link, and lives outside. A
  # component that does not exist yet is kept as written, because a file `write`
  # is about to create has no real path and a check that only works on existing
  # files is not a check for `write`.
  defp canonical(p) do
    p
    |> Path.expand()
    |> Path.split()
    |> Enum.reduce("", fn segment, acc ->
      joined = if acc == "", do: segment, else: Path.join(acc, segment)
      follow_link(joined, 0)
    end)
  end

  defp follow_link(_path, depth) when depth > 40, do: "/"

  defp follow_link(path, depth) do
    case File.read_link(path) do
      {:ok, target} ->
        resolved = if absolute?(target), do: target, else: Path.expand(target, Path.dirname(path))
        follow_link(resolved, depth + 1)

      _ ->
        path
    end
  end

  # ---------------------------------------------------------------------------
  # shared helpers
  # ---------------------------------------------------------------------------

  defp builtin(name, description, input_schema, run) do
    %Tool{
      name: name,
      description: description,
      input_schema: input_schema,
      source: "builtin",
      execute: fn args, ctx ->
        try do
          run.(args || %{}, ctx)
        rescue
          e -> err("#{name}: #{Exception.message(e)}")
        end
      end
    }
  end

  defp err(output), do: %ToolResult{output: output, is_error: true}
  defp err(output, metadata), do: %ToolResult{output: output, is_error: true, metadata: metadata}
  defp ok(output), do: %ToolResult{output: output, is_error: false}
  defp ok(output, metadata), do: %ToolResult{output: output, is_error: false, metadata: metadata}

  # Producer helper: a §10 suspension — ToolResult with metadata.pending = Request.
  defp pending(kind, prompt, data) do
    ts = System.system_time(:millisecond) |> Integer.to_string(36) |> String.downcase()
    seq = :erlang.unique_integer([:positive, :monotonic])
    req = %Request{id: "pnd-#{ts}-#{seq}", kind: kind, prompt: prompt, data: data}
    %ToolResult{output: prompt, is_error: true, metadata: %{pending: req}}
  end

  defp str(nil), do: ""
  defp str(v) when is_binary(v), do: v
  defp str(v), do: to_string(v)

  defp num(v, _default) when is_number(v), do: v
  defp num(_v, default), do: default

  # ---------------------------------------------------------------------------
  # glob helpers (shared by grep + glob)
  # ---------------------------------------------------------------------------

  # Convert a glob (`*`, `**`, `?`) to an anchored regex (JS globToRegExp parity).
  defp glob_to_regex(glob) do
    glob |> String.graphemes() |> build_glob_regex("") |> then(&Regex.compile!("^" <> &1 <> "$"))
  end

  defp build_glob_regex([], re), do: re

  defp build_glob_regex(["*", "*" | rest], re) do
    rest = case rest do
      ["/" | r] -> r
      r -> r
    end

    build_glob_regex(rest, re <> ".*")
  end

  defp build_glob_regex(["*" | rest], re), do: build_glob_regex(rest, re <> "[^/]*")
  defp build_glob_regex(["?" | rest], re), do: build_glob_regex(rest, re <> "[^/]")

  defp build_glob_regex([c | rest], re) do
    if String.contains?("\\^$.|+()[]{}", c) do
      build_glob_regex(rest, re <> "\\" <> c)
    else
      build_glob_regex(rest, re <> c)
    end
  end

  # Match a relative path against a glob; slash-less globs test the basename.
  defp match_glob?(rel, glob) do
    re = glob_to_regex(glob)
    if String.contains?(glob, "/"), do: Regex.match?(re, rel), else: Regex.match?(re, Path.basename(rel))
  end

  # Recursively list files under root (skips node_modules/.git), returned in ONE
  # global order: the path RELATIVE to the walk root, plain Unicode code point.
  #
  # A26 (same rule as A25 in the skill sample): a per-directory sort during traversal
  # is a function of the WALK — every port must reproduce the same stack discipline to
  # agree, and a nested tree interleaves differently from a global relative-path sort.
  # Both callers below are CAPPED, so this ordering decides WHICH files the model sees,
  # not merely their sequence: collect, sort, THEN truncate — never break at the cap
  # mid-walk (ADR-0004 K1, sort-before-sample).
  # A28: the sort key is the path relative to the walk root with `/` separators — the
  # same string `glob` EMITS. `grep` prints absolute paths, which order identically
  # because they are that same relative path under one constant root prefix.
  defp walk_files(root), do: walk_files(root, root) |> Enum.sort_by(&Path.relative_to(&1, root))

  defp walk_files(root, dir) do
    case File.ls(dir) do
      {:error, _} ->
        []

      {:ok, entries} ->
        Enum.flat_map(entries, fn name ->
          full = Path.join(dir, name)

          cond do
            File.dir?(full) -> if name in @ignore_dirs, do: [], else: walk_files(root, full)
            File.regular?(full) -> [full]
            true -> []
          end
        end)
    end
  end

  # ---------------------------------------------------------------------------
  # individual tools
  # ---------------------------------------------------------------------------

  defp bash_tool(env) do
    builtin(
      "bash",
      "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
      %{
        "type" => "object",
        "properties" => %{
          "command" => %{"type" => "string", "description" => "The shell command to run"},
          "workdir" => %{"type" => "string", "description" => "Working directory (default: process cwd)"},
          "timeout" => %{"type" => "number", "description" => "Timeout in milliseconds (default 60000)"},
          "description" => %{"type" => "string", "description" => "Human-readable description of the command"}
        },
        "required" => ["command"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        command = str(args["command"])

        cond do
          command == "" ->
            err("bash: command is required")

          env.shell_error != nil ->
            err("bash: #{env.shell_error}")

          true ->
            workdir_result =
              if args["workdir"], do: resolve_path(env, str(args["workdir"])), else: {:ok, env_dir(env)}

            case workdir_result do
              {:error, message} ->
                err("bash: #{message}")

              {:ok, workdir} ->
                timeout = args["timeout"] |> num(60_000) |> round()
                run_job(env, command, workdir, timeout)
            end
        end
      end
    )
  end

  # The port is owned by a process that TRAPS EXITS, and the caller only waits on
  # it. That structure is the fix, not decoration: measured
  # (spikes/builtin-host-boundary/ports/elixir), `Port.close/1` does not kill the
  # OS process, `Task.shutdown(:brutal_kill)` kills nothing outside the BEAM, and
  # killing the port owner leaks the whole job — so a kill written INSIDE the task
  # never runs when the task is the thing being cancelled. Trapping exits here
  # means the job is stopped on the way out, whichever way the caller leaves.
  defp run_job(env, command, workdir, timeout) do
    caller = self()

    {owner, ref} =
      spawn_monitor(fn ->
        Process.flag(:trap_exit, true)
        caller_ref = Process.monitor(caller)
        run_shell(env, command, workdir, timeout, caller, caller_ref)
      end)

    receive do
      {:DOWN, ^ref, :process, ^owner, _reason} ->
        receive do
          {:job_result, ^owner, result} -> result
        after
          0 -> err("bash: command owner exited without a result")
        end

      {:job_result, ^owner, result} ->
        Process.demonitor(ref, [:flush])
        result
    end
  end

  defp run_shell(env, command, workdir, timeout, caller, caller_ref) do
    [exe | prefix] = env.shell

    port =
      Port.open({:spawn_executable, exe}, [
        :binary,
        :exit_status,
        :stderr_to_stdout,
        args: prefix ++ [command],
        cd: workdir
      ])

    os_pid =
      case Port.info(port, :os_pid) do
        {:os_pid, pid} -> pid
        _ -> nil
      end

    deadline = System.monotonic_time(:millisecond) + timeout
    meta = %{shell: Enum.join(env.shell, " ")}
    result = collect_shell(port, "", deadline, timeout, os_pid, meta, caller, caller_ref)
    send(caller, {:job_result, self(), result})
  end

  defp collect_shell(port, out, deadline, timeout, os_pid, meta, caller, caller_ref) do
    remaining = max(deadline - System.monotonic_time(:millisecond), 0)

    receive do
      {^port, {:data, d}} ->
        collect_shell(port, out <> d, deadline, timeout, os_pid, meta, caller, caller_ref)

      {^port, {:exit_status, 0}} ->
        ok(out, Map.put(meta, :exitCode, 0))

      {^port, {:exit_status, code}} ->
        err("#{out}\nbash: command exited with code #{code}", Map.put(meta, :exitCode, code))

      # The caller went away — cancelled, crashed, or shut down. Stop the job on
      # the way out; nobody is left to receive a result.
      {:DOWN, ^caller_ref, :process, _pid, _reason} ->
        kill_job(port, os_pid)
        err("bash: command cancelled\n#{out}", Map.merge(meta, %{timedOut: false, killedTree: true}))

      {:EXIT, _from, _reason} ->
        kill_job(port, os_pid)
        err("bash: command cancelled\n#{out}", Map.merge(meta, %{timedOut: false, killedTree: true}))
    after
      remaining ->
        killed = kill_job(port, os_pid)

        err(
          "bash: command timed out after #{timeout}ms\n#{out}",
          Map.merge(meta, %{timedOut: true, killedTree: killed})
        )
    end
  end

  # Stop the command AND everything it started. There is no Setpgid option on
  # `Port.open` (measured: it rejects one), so the tree is enumerated with `ps`
  # WHILE THE PARENT IS STILL ALIVE — after it dies its children are reparented
  # and are no longer reachable from its pid. Ask first, wait out the grace
  # window, then insist.
  defp kill_job(port, nil) do
    if Port.info(port), do: Port.close(port)
    false
  end

  defp kill_job(port, os_pid) do
    pids = [os_pid | descendants(os_pid)]
    signal(pids, "-TERM")
    # WAIT for the job to go, up to the grace window — do not SLEEP through it.
    # The window bounds how long the kill may take, not how long the caller
    # waits: a command that dies on SIGTERM in 5 ms must not cost its caller two
    # seconds. Measured parity break — five ports returned a 1 s timeout in
    # ~1.0 s while this one took ~3.05 s.
    unless await_exit(pids, @kill_grace_ms) do
      signal(pids, "-KILL")
    end

    if Port.info(port), do: Port.close(port)
    true
  end

  # Poll until every pid is gone or the deadline passes. `kill -0` is the
  # portable liveness test; it signals nothing.
  defp await_exit(pids, budget_ms) do
    deadline = System.monotonic_time(:millisecond) + budget_ms

    Enum.reduce_while(Stream.cycle([:tick]), false, fn _, _ ->
      if Enum.all?(pids, &(not alive_pid?(&1))) do
        {:halt, true}
      else
        if System.monotonic_time(:millisecond) >= deadline do
          {:halt, false}
        else
          Process.sleep(20)
          {:cont, false}
        end
      end
    end)
  end

  defp alive_pid?(pid) do
    if windows?() do
      case System.cmd("tasklist", ["/FI", "PID eq #{pid}"], stderr_to_stdout: true) do
        {out, _} -> String.contains?(out, to_string(pid))
      end
    else
      match?({_, 0}, System.cmd("kill", ["-0", to_string(pid)], stderr_to_stdout: true))
    end
  end

  defp signal(pids, sig) do
    if windows?() do
      # Windows has NO graceful termination for a console process: `taskkill /T`
      # without `/F` refuses every console process in the tree while still being
      # able to take the PARENT down, which reparents the grandchild. Both steps
      # therefore force there; the grace window is a POSIX effect.
      Enum.each(pids, fn pid ->
        System.cmd("taskkill", ["/T", "/F", "/PID", Integer.to_string(pid)], stderr_to_stdout: true)
      end)
    else
      Enum.each(pids, fn pid ->
        System.cmd("kill", [sig, Integer.to_string(pid)], stderr_to_stdout: true)
      end)
    end
  end

  defp descendants(root) do
    case System.cmd("ps", ["-eo", "pid=,ppid="], stderr_to_stdout: true) do
      {out, 0} ->
        pairs =
          out
          |> String.split("\n", trim: true)
          |> Enum.flat_map(fn line ->
            case String.split(String.trim(line), ~r/\s+/) do
              [p, pp] ->
                with {pid, ""} <- Integer.parse(p), {ppid, ""} <- Integer.parse(pp) do
                  [{pid, ppid}]
                else
                  _ -> []
                end

              _ ->
                []
            end
          end)

        walk = fn walk, frontier, acc ->
          kids = for {p, pp} <- pairs, pp in frontier, p not in acc, do: p
          if kids == [], do: acc, else: walk.(walk, kids, acc ++ kids)
        end

        walk.(walk, [root], [])

      _ ->
        []
    end
  end

  defp read_tool(env) do
    builtin(
      "read",
      "Read a UTF-8 text file. With offset/limit, return only that line window.",
      %{
        "type" => "object",
        "properties" => %{
          "path" => %{"type" => "string", "description" => "Path to the file to read"},
          "offset" => %{"type" => "number", "description" => "1-based line to start from"},
          "limit" => %{"type" => "number", "description" => "Maximum number of lines to read"}
        },
        "required" => ["path"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        p = str(args["path"])

        cond do
          p == "" ->
            err("read: path is required")

          true ->
            case with({:ok, full} <- resolve_path(env, p), do: File.read(full)) do
              {:error, reason} when is_binary(reason) ->
                err("read: #{reason}")

              {:error, reason} ->
                err("read: #{p}: #{:file.format_error(reason)}")

              {:ok, content} ->
                # SPEC §6: a recognised media extension returns a one-line description plus
                # one content part. Fixed table only — never sniffed, never a platform mime
                # database, whose contents vary per machine and would break port parity.
                cond do
                  match?({:ok, _}, ContentPart.media_for_path(p)) ->
                    {:ok, {mime, type}} = ContentPart.media_for_path(p)

                    %ToolResult{
                      output: "#{p} (#{mime}, #{byte_size(content)} bytes)",
                      is_error: false,
                      parts: [%ContentPart{type: type, mime_type: mime, data: Base.encode64(content), name: Path.basename(p)}]
                    }

                  not String.valid?(content) ->
                    err("read: #{p}: not valid UTF-8 text and not a recognised media file")

                  args["offset"] == nil and args["limit"] == nil ->
                    ok(content)

                  true ->
                    lines = String.split(content, "\n")
                    offset = if is_number(args["offset"]), do: max(1, trunc(args["offset"])), else: 1
                    start = offset - 1

                    limit =
                      if is_number(args["limit"]),
                        do: max(0, trunc(args["limit"])),
                        else: max(0, length(lines) - start)

                    ok(lines |> Enum.slice(start, limit) |> Enum.join("\n"))
                end
            end
        end
      end
    )
  end

  defp write_tool(env) do
    builtin(
      "write",
      "Write content to a file (create/overwrite), creating parent directories.",
      %{
        "type" => "object",
        "properties" => %{
          "path" => %{"type" => "string", "description" => "Path to write to"},
          "content" => %{"type" => "string", "description" => "Content to write"}
        },
        "required" => ["path", "content"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        p = str(args["path"])

        if p == "" do
          err("write: path is required")
        else
          content = str(args["content"])

          case resolve_path(env, p) do
            {:error, message} ->
              err("write: #{message}")

            {:ok, full} ->
              File.mkdir_p!(Path.dirname(Path.expand(full)))
              File.write!(full, content)
              bytes = byte_size(content)
              ok("Wrote #{bytes} bytes to #{p}", %{bytes: bytes})
          end
        end
      end
    )
  end

  defp edit_tool(env) do
    builtin(
      "edit",
      "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
      %{
        "type" => "object",
        "properties" => %{
          "path" => %{"type" => "string", "description" => "Path to the file to edit"},
          "oldString" => %{"type" => "string", "description" => "Exact string to replace"},
          "newString" => %{"type" => "string", "description" => "Replacement string"},
          "replaceAll" => %{"type" => "boolean", "description" => "Replace all occurrences"}
        },
        "required" => ["path", "oldString", "newString"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        p = str(args["path"])
        old_string = args["oldString"]

        cond do
          p == "" ->
            err("edit: path is required")

          not is_binary(old_string) or old_string == "" ->
            err("edit: oldString is required")

          true ->
            new_string = str(args["newString"])
            full = case resolve_path(env, p) do
              {:ok, resolved} -> resolved
              {:error, _} -> nil
            end

            case (if full, do: File.read(full), else: {:error, elem(resolve_path(env, p), 1)}) do
              {:error, reason} when is_binary(reason) ->
                err("edit: #{reason}")

              {:error, reason} ->
                err("edit: #{p}: #{:file.format_error(reason)}")

              {:ok, content} ->
                count = length(String.split(content, old_string)) - 1
                replace_all = args["replaceAll"] == true

                cond do
                  count == 0 ->
                    err("edit: oldString not found in #{p}")

                  count > 1 and not replace_all ->
                    err("edit: oldString is not unique in #{p} (#{count} occurrences); use replaceAll")

                  true ->
                    next = String.replace(content, old_string, new_string, global: replace_all)
                    File.write!(full, next)
                    n = if replace_all, do: count, else: 1
                    plural = if n == 1, do: "", else: "s"
                    ok("Edited #{p} (#{n} replacement#{plural})", %{replacements: n})
                end
            end
        end
      end
    )
  end

  defp grep_tool(env) do
    builtin(
      "grep",
      "Search file contents by regex under a directory. Output is file:line:text matches.",
      %{
        "type" => "object",
        "properties" => %{
          "pattern" => %{"type" => "string", "description" => "Regular expression to search for"},
          "path" => %{"type" => "string", "description" => "Directory to search (default: process cwd)"},
          "include" => %{"type" => "string", "description" => "Glob filter for file names"},
          "limit" => %{"type" => "number", "description" => "Maximum number of matches (default 100)"}
        },
        "required" => ["pattern"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        pattern = str(args["pattern"])

        if pattern == "" do
          err("grep: pattern is required")
        else
          case Regex.compile(pattern) do
            {:error, {reason, at}} ->
              err("grep: invalid regex: #{reason} (at #{at})")

            {:ok, re} ->
              root =
                case (if args["path"], do: resolve_path(env, str(args["path"])), else: {:ok, env_dir(env)}) do
                  {:ok, r} -> r
                  {:error, _} -> nil
                end

              include = if args["include"], do: str(args["include"])
              limit = args["limit"] |> num(100) |> trunc()

              matches =
                # A26: the fold halts at the cap, which is only correct because
                # `walk_files/1` hands back ONE globally sorted list — the halt then
                # truncates a sorted sequence instead of letting the walk decide.
                walk_files(root)
                |> Enum.reduce_while([], fn file, acc ->
                  if length(acc) >= limit do
                    {:halt, acc}
                  else
                    # A28/A27c: grep EMITS the same relative `/`-path it sorts on.
                    # Sorting on one string and displaying another orders by one
                    # thing and shows the reader another.
                    rel = Path.relative_to(file, root)

                    if include && not match_glob?(rel, include) do
                      {:cont, acc}
                    else
                      case File.read(file) do
                        {:error, _} ->
                          {:cont, acc}

                        {:ok, text} ->
                          hits =
                            text
                            |> String.split("\n")
                            |> Enum.with_index(1)
                            |> Enum.filter(fn {line, _i} -> Regex.match?(re, line) end)
                            # A27c: line numbers stay in NUMERIC order. They are
                            # never re-sorted as part of the rendered
                            # `path:line:text` string, which would put line 10
                            # before line 2. `with_index` already ascends and the
                            # filter preserves it, so numeric order holds by
                            # construction — the files above it are ordered by
                            # relative path, giving (path, line) overall.
                            |> Enum.map(fn {line, i} -> "#{rel}:#{i}:#{line}" end)
                            |> Enum.take(max(limit - length(acc), 0))

                          {:cont, acc ++ hits}
                      end
                    end
                  end
                end)

              ok(Enum.join(matches, "\n"), %{count: length(matches)})
          end
        end
      end
    )
  end

  defp glob_tool(env) do
    builtin(
      "glob",
      "List files matching a glob under a directory. Output is newline-joined relative paths.",
      %{
        "type" => "object",
        "properties" => %{
          "pattern" => %{"type" => "string", "description" => "Glob pattern to match"},
          "path" => %{"type" => "string", "description" => "Directory to search (default: process cwd)"},
          "limit" => %{"type" => "number", "description" => "Maximum number of results (default 100)"}
        },
        "required" => ["pattern"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        pattern = str(args["pattern"])

        if pattern == "" do
          err("glob: pattern is required")
        else
          root =
            case (if args["path"], do: resolve_path(env, str(args["path"])), else: {:ok, env_dir(env)}) do
              {:ok, r} -> r
              {:error, _} -> nil
            end
          limit = args["limit"] |> num(100) |> trunc()

          found =
            walk_files(root)
            |> Enum.map(&Path.relative_to(&1, root))
            |> Enum.filter(&match_glob?(&1, pattern))
            # A26: `walk_files/1` is ALREADY globally sorted, so this cap takes a
            # PREFIX of the sorted set. Capping first and sorting the survivors let
            # the filesystem choose which matches the model saw.
            |> Enum.take(limit)

          ok(Enum.join(found, "\n"), %{count: length(found)})
        end
      end
    )
  end

  # Very light HTML → text: drop scripts/styles + tags, collapse whitespace.
  defp strip_html(html) do
    html
    |> String.replace(~r{<script[\s\S]*?</script>}i, "")
    |> String.replace(~r{<style[\s\S]*?</style>}i, "")
    |> String.replace(~r{<[^>]+>}, "")
    |> String.replace(~r/[ \t]+\n/, "\n")
    |> String.replace(~r/\n{3,}/, "\n\n")
    |> String.trim()
  end

  defp webfetch_tool do
    builtin(
      "webfetch",
      "HTTP GET a URL and return its body as text, markdown, or html.",
      %{
        "type" => "object",
        "properties" => %{
          "url" => %{"type" => "string", "description" => "URL to fetch"},
          "format" => %{
            "type" => "string",
            "enum" => ["text", "markdown", "html"],
            "description" => "Response format (default markdown)"
          },
          "timeout" => %{"type" => "number", "description" => "Timeout in seconds (default 30)"}
        },
        "required" => ["url"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        url = str(args["url"])

        if url == "" do
          err("webfetch: url is required")
        else
          format = if args["format"] in ["text", "html"], do: args["format"], else: "markdown"
          timeout_ms = round(num(args["timeout"], 30) * 1000)

          case Req.request(
                 method: :get,
                 url: url,
                 receive_timeout: timeout_ms,
                 retry: false,
                 decode_body: false
               ) do
            {:error, e} ->
              err("webfetch: #{Exception.message(e)}")

            {:ok, %Req.Response{status: status, body: body}} ->
              body = if is_binary(body), do: body, else: IO.iodata_to_binary(body)

              if status in 200..299 do
                output = if format == "html", do: body, else: strip_html(body)
                ok(output, %{status: status, format: format})
              else
                err("HTTP #{status}", %{status: status})
              end
          end
        end
      end
    )
  end

  # Render the questions into a human-readable Request.prompt (§10). Byte-identical
  # across ports: each question's text in order, " (options: a, b, c)" appended when
  # it has non-empty options, joined by "\n" (no trailing newline). `header` is not
  # rendered — it survives in data.questions.
  defp render_question_prompt(questions) do
    questions
    |> Enum.map(fn q ->
      q = if is_map(q), do: q, else: %{}
      line = if is_binary(q["question"]), do: q["question"], else: ""
      opts = if is_list(q["options"]), do: q["options"], else: []
      if opts == [], do: line, else: line <> " (options: #{Enum.join(opts, ", ")})"
    end)
    |> Enum.join("\n")
  end

  defp question_tool do
    builtin(
      "question",
      "Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's waitFor resolves it and the answer is returned to the model.",
      %{
        "type" => "object",
        "properties" => %{
          "questions" => %{
            "type" => "array",
            "description" => "Questions to ask",
            "items" => %{
              "type" => "object",
              "properties" => %{
                "question" => %{"type" => "string"},
                "header" => %{"type" => "string"},
                "options" => %{"type" => "array", "items" => %{"type" => "string"}},
                "multiple" => %{"type" => "boolean"}
              },
              "required" => ["question"]
            }
          }
        },
        "required" => ["questions"],
        "additionalProperties" => false
      },
      fn args, ctx ->
        questions = if is_list(args["questions"]), do: args["questions"], else: []
        answer = if is_struct(ctx, Toolnexus.Context), do: ctx.answer, else: nil

        if answer do
          # Re-executed after the host's waitFor resolved (§10 loop rule): the
          # resolution IS the answer — forward it verbatim to the model.
          ok(Jason.encode!(answer.data || %{}))
        else
          # First call: suspend. A question is just a §10 Request with kind:"question".
          pending("question", render_question_prompt(questions), %{"questions" => questions})
        end
      end
    )
  end

  defp todowrite_tool do
    builtin(
      "todowrite",
      "Replace the session todo list. Returns the rendered list.",
      %{
        "type" => "object",
        "properties" => %{
          "todos" => %{
            "type" => "array",
            "description" => "The full todo list to store",
            "items" => %{
              "type" => "object",
              "properties" => %{
                "id" => %{"type" => "string"},
                "text" => %{"type" => "string"},
                "completed" => %{"type" => "boolean"}
              },
              "required" => ["id", "text", "completed"]
            }
          }
        },
        "required" => ["todos"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        todos = if is_list(args["todos"]), do: args["todos"], else: []

        rendered =
          todos
          |> Enum.map(fn t ->
            mark = if t["completed"], do: "x", else: " "
            "[#{mark}] #{str(t["text"])}"
          end)
          |> Enum.join("\n")

        ok(if(rendered == "", do: "(no todos)", else: rendered), %{todos: todos})
      end
    )
  end

  # ---------------------------------------------------------------------------
  # apply_patch (opencode Begin/End Patch grammar)
  # ---------------------------------------------------------------------------

  defp parse_patch(patch_text) do
    lines = patch_text |> String.split("\n") |> Enum.drop_while(&(String.trim(&1) == ""))

    case lines do
      [first | rest] ->
        if String.trim(first) == "*** Begin Patch" do
          parse_ops(rest, [])
        else
          raise "missing '*** Begin Patch'"
        end

      [] ->
        raise "missing '*** Begin Patch'"
    end
  end

  defp parse_ops([], _ops), do: raise("missing '*** End Patch'")

  defp parse_ops([line | rest], ops) do
    cond do
      String.trim(line) == "*** End Patch" ->
        Enum.reverse(ops)

      String.trim(line) == "" ->
        parse_ops(rest, ops)

      true ->
        case Regex.run(@file_marker, line) do
          nil ->
            raise "unexpected line: #{line}"

          [_, kind, p] ->
            p = String.trim(p)
            {body, rest} = take_patch_body(rest, [])

            op =
              case kind do
                "Add" ->
                  content =
                    body
                    |> Enum.map(fn l -> if String.starts_with?(l, "+"), do: strip_first(l), else: l end)
                    |> Enum.join("\n")

                  {:add, p, content}

                "Delete" ->
                  {:delete, p}

                "Update" ->
                  {:update, p, body}
              end

            parse_ops(rest, [op | ops])
        end
    end
  end

  defp take_patch_body([], acc), do: {Enum.reverse(acc), []}

  defp take_patch_body([l | rest] = all, acc) do
    if String.trim(l) == "*** End Patch" or Regex.match?(@file_marker, l) do
      {Enum.reverse(acc), all}
    else
      take_patch_body(rest, [l | acc])
    end
  end

  defp strip_first(l), do: binary_part(l, 1, byte_size(l) - 1)

  # Apply an Update hunk-body to file content; raises on a non-matching hunk.
  defp apply_update(content, body) do
    body
    |> split_hunks()
    |> Enum.reduce(content, fn hunk, result ->
      {old_lines, new_lines} =
        Enum.reduce(hunk, {[], []}, fn l, {o, n} ->
          cond do
            String.starts_with?(l, "-") -> {[strip_first(l) | o], n}
            String.starts_with?(l, "+") -> {o, [strip_first(l) | n]}
            String.starts_with?(l, " ") -> {[strip_first(l) | o], [strip_first(l) | n]}
            true -> {[l | o], [l | n]}
          end
        end)

      old_block = old_lines |> Enum.reverse() |> Enum.join("\n")
      new_block = new_lines |> Enum.reverse() |> Enum.join("\n")

      cond do
        old_block == "" ->
          # pure insertion with no context — append.
          sep = if String.ends_with?(result, "\n") or result == "", do: "", else: "\n"
          result <> sep <> new_block

        String.contains?(result, old_block) ->
          String.replace(result, old_block, new_block, global: false)

        true ->
          raise "hunk does not match file contents"
      end
    end)
  end

  # Split into hunks by @@ markers; a body with no @@ is a single hunk.
  defp split_hunks(body) do
    {hunks, cur} =
      Enum.reduce(body, {[], []}, fn l, {hunks, cur} ->
        if String.starts_with?(l, "@@") do
          if cur == [], do: {hunks, []}, else: {[Enum.reverse(cur) | hunks], []}
        else
          {hunks, [l | cur]}
        end
      end)

    hunks = if cur == [], do: hunks, else: [Enum.reverse(cur) | hunks]
    Enum.reverse(hunks)
  end

  defp apply_patch_tool(env) do
    builtin(
      "apply_patch",
      "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
      %{
        "type" => "object",
        "properties" => %{
          "patchText" => %{"type" => "string", "description" => "The patch text in Begin/End Patch format"}
        },
        "required" => ["patchText"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        patch_text = str(args["patchText"])

        if patch_text == "" do
          err("apply_patch: patchText is required")
        else
          # Stage every write/delete first; only touch the filesystem once all
          # hunks apply. Any raise while staging aborts with no writes.
          try do
            # The paths live INSIDE the patch text, not in the arguments, so a host
            # cannot rewrite them from a hook — the one case that genuinely needs
            # the base directory to be library-side (ADR 0034 D2).
            ops =
              patch_text
              |> parse_patch()
              |> Enum.map(fn op ->
                p = elem(op, 1)

                case resolve_path(env, p) do
                  {:ok, full} -> put_elem(op, 1, full)
                  {:error, message} -> raise message
                end
              end)

            {writes, deletes} =
              Enum.reduce(ops, {[], []}, fn op, {writes, deletes} ->
                case op do
                  {:add, p, content} ->
                    if File.exists?(p), do: raise("file already exists: #{p}")
                    {[{p, content} | writes], deletes}

                  {:delete, p} ->
                    if not File.exists?(p), do: raise("file not found: #{p}")
                    {writes, [p | deletes]}

                  {:update, p, body} ->
                    content =
                      case File.read(p) do
                        {:ok, c} -> c
                        {:error, reason} -> raise "#{p}: #{:file.format_error(reason)}"
                      end

                    {[{p, apply_update(content, body)} | writes], deletes}
                end
              end)

            Enum.each(Enum.reverse(writes), fn {p, content} ->
              File.mkdir_p!(Path.dirname(Path.expand(p)))
              File.write!(p, content)
            end)

            Enum.each(Enum.reverse(deletes), &File.rm/1)

            n = length(ops)
            plural = if n == 1, do: "", else: "s"

            ok("Applied patch: #{n} file operation#{plural}", %{
              added: Enum.count(ops, &match?({:add, _, _}, &1)),
              updated: Enum.count(ops, &match?({:update, _, _}, &1)),
              deleted: Enum.count(ops, &match?({:delete, _}, &1))
            })
          rescue
            e in RuntimeError -> err("apply_patch: #{e.message}")
          end
        end
      end
    )
  end
end
