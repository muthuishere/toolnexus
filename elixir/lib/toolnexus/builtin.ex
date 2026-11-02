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

  Opt-in tools (SPEC §4C), absent unless configured, appended in this order:

    * `background: Toolnexus.Builtin.BackgroundShells.new()` — `bash` gains
      `run_in_background`, plus `bash_output` and `kill_shell`. The host owns the
      registry and calls `BackgroundShells.close_all/1` on shutdown.
    * `web_search: fn query, limit -> {:ok, [%{title, url, snippet}]} | {:error, reason} end`
      — adds `websearch` over the host's provider (the library ships none).
    * `notebook_edit: true` — adds `notebook_edit` for `.ipynb` cells.

  Every builtin carries `Toolnexus.ToolHints` for host permission gates.
  """

  alias Toolnexus.{ContentPart, Tool, ToolHints, ToolResult, Request}

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

  # SPEC §4C: bash can do anything, so it is destructive and open-world. todowrite
  # and question only touch the agent's own state / ask the human: read-only for the
  # host's files and systems.
  @hints %{
    "bash" => %ToolHints{destructive: true, open_world: true},
    "read" => %ToolHints{read_only: true},
    "write" => %ToolHints{destructive: true},
    "edit" => %ToolHints{destructive: true},
    "grep" => %ToolHints{read_only: true},
    "glob" => %ToolHints{read_only: true},
    "webfetch" => %ToolHints{read_only: true, open_world: true},
    "question" => %ToolHints{read_only: true},
    "apply_patch" => %ToolHints{destructive: true},
    "todowrite" => %ToolHints{read_only: true},
    "bash_output" => %ToolHints{read_only: true},
    "kill_shell" => %ToolHints{destructive: true},
    "websearch" => %ToolHints{read_only: true, open_world: true},
    "notebook_edit" => %ToolHints{destructive: true}
  }

  @doc """
  Build the ten built-in tools (each `source: "builtin"`). The order is fixed
  for parity: bash, read, write, edit, grep, glob, webfetch, question,
  apply_patch, todowrite.
  """
  @spec tools(nil | boolean() | map()) :: [Tool.t()]
  def tools(cfg \\ nil) do
    e = env(cfg)

    ([
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
     ] ++
       if(e.background, do: [bash_output_tool(e), kill_shell_tool(e)], else: []) ++
       if(e.web_search, do: [websearch_tool(e.web_search)], else: []) ++
       if(e.notebook_edit, do: [notebook_edit_tool(e)], else: []))
    |> Enum.map(&%{&1 | hints: Map.fetch!(@hints, &1.name)})
  end

  # ---------------------------------------------------------------------------
  # the host boundary: interpreter, base directory, confinement (SPEC §4A, ADR 0034)
  # ---------------------------------------------------------------------------

  # How long a job gets between "please stop" and "stop" when the host sets no
  # `kill_grace_ms` / `killGraceMs` (0 or absent). Identical in every port, so a
  # timeout means the same thing everywhere; a host with a tight cancel bar lowers it.
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

      if(comspec, do: [[comspec, "/d", "/s", "/c"]], else: []) ++
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

    confine =
      cfg_get(map, :confine_to_base_dir) == true or cfg_get(map, :confineToBaseDir) == true

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

    grace =
      case cfg_get(map, :kill_grace_ms) || cfg_get(map, :killGraceMs) do
        n when is_number(n) and n > 0 -> round(n)
        _ -> @kill_grace_ms
      end

    web_search =
      case cfg_get(map, :web_search) || cfg_get(map, :webSearch) do
        f when is_function(f, 2) -> f
        _ -> nil
      end

    %{
      shell: shell,
      shell_error: shell_error,
      base_dir: to_string(base_dir),
      confine: confine,
      kill_grace_ms: grace,
      background: cfg_get(map, :background),
      web_search: web_search,
      notebook_edit: cfg_get(map, :notebook_edit) == true or cfg_get(map, :notebookEdit) == true
    }
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
    rest =
      case rest do
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

    if String.contains?(glob, "/"),
      do: Regex.match?(re, rel),
      else: Regex.match?(re, Path.basename(rel))
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
    props = %{
      "command" => %{"type" => "string", "description" => "The shell command to run"},
      "workdir" => %{
        "type" => "string",
        "description" => "Working directory (default: process cwd)"
      },
      "timeout" => %{
        "type" => "number",
        "description" => "Timeout in milliseconds (default 60000)"
      },
      "description" => %{
        "type" => "string",
        "description" => "Human-readable description of the command"
      }
    }

    props =
      if env.background,
        do:
          Map.put(props, "run_in_background", %{
            "type" => "boolean",
            "description" =>
              "Start the command and return at once with a background shell id; read it with bash_output, stop it with kill_shell. No timeout applies."
          }),
        else: props

    builtin(
      "bash",
      "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
      %{
        "type" => "object",
        "properties" => props,
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
              if args["workdir"],
                do: resolve_path(env, str(args["workdir"])),
                else: {:ok, env_dir(env)}

            case workdir_result do
              {:error, message} ->
                err("bash: #{message}")

              {:ok, workdir} ->
                if is_pid(env.background) and args["run_in_background"] == true do
                  id =
                    Toolnexus.Builtin.BackgroundShells.start_job(
                      env.background,
                      env,
                      command,
                      workdir
                    )

                  ok(
                    "Started background shell #{id}. Read its output with bash_output, stop it with kill_shell.",
                    %{shell: Enum.join(env.shell, " "), backgroundId: id}
                  )
                else
                  timeout = args["timeout"] |> num(60_000) |> round()
                  run_job(env, command, workdir, timeout)
                end
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
    grace = Map.get(env, :kill_grace_ms, @kill_grace_ms)
    kill = fn -> kill_job(port, os_pid, grace) end
    result = collect_shell(port, "", deadline, timeout, kill, meta, caller, caller_ref)
    send(caller, {:job_result, self(), result})
  end

  defp collect_shell(port, out, deadline, timeout, kill, meta, caller, caller_ref) do
    remaining = max(deadline - System.monotonic_time(:millisecond), 0)

    receive do
      {^port, {:data, d}} ->
        collect_shell(port, out <> d, deadline, timeout, kill, meta, caller, caller_ref)

      {^port, {:exit_status, 0}} ->
        ok(out, Map.put(meta, :exitCode, 0))

      {^port, {:exit_status, code}} ->
        err("#{out}\nbash: command exited with code #{code}", Map.put(meta, :exitCode, code))

      # The caller went away — cancelled, crashed, or shut down. Stop the job on
      # the way out; nobody is left to receive a result.
      {:DOWN, ^caller_ref, :process, _pid, _reason} ->
        kill.()

        err(
          "bash: command cancelled\n#{out}",
          Map.merge(meta, %{timedOut: false, killedTree: true})
        )

      {:EXIT, _from, _reason} ->
        kill.()

        err(
          "bash: command cancelled\n#{out}",
          Map.merge(meta, %{timedOut: false, killedTree: true})
        )
    after
      remaining ->
        killed = kill.()

        err(
          "bash: command timed out after #{timeout}ms\n#{out}",
          Map.merge(meta, %{timedOut: true, killedTree: killed})
        )
    end
  end

  # An Erlang port reports a shell killed by signal N as 128 + N; Go reports -1
  # (killed by a signal). Map 129..159 to -1 so every port prints the same code.
  # Known limit: a command that itself exits 129..159 also reads as -1 here.
  @doc false
  def signal_code(code) when is_integer(code) and code > 128 and code < 160, do: -1
  def signal_code(code), do: code

  @doc false
  # A background job (SPEC §4C): the port owner. It traps exits, so whichever way
  # the registry leaves, the job's process group is stopped on the way out.
  def background_job(env, command, workdir, owner) do
    Process.flag(:trap_exit, true)
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

    background_loop(%{
      port: port,
      os_pid: os_pid,
      owner: owner,
      grace: env.kill_grace_ms,
      out: "",
      read: 0,
      status: :running,
      code: nil
    })
  end

  defp background_loop(%{port: port} = j) do
    receive do
      {^port, {:data, d}} ->
        background_loop(%{j | out: j.out <> d})

      {^port, {:exit_status, code}} ->
        background_loop(%{
          j
          | status: if(j.status == :killed, do: :killed, else: :exited),
            code: signal_code(code)
        })

      {:read, from, ref} ->
        fresh = binary_part(j.out, j.read, byte_size(j.out) - j.read)
        send(from, {ref, fresh, j.status, j.code})
        background_loop(%{j | read: byte_size(j.out)})

      {:info, from, ref} ->
        send(from, {ref, j.status, j.code})
        background_loop(j)

      {:kill, from, ref} ->
        if j.status == :running do
          kill_job(port, j.os_pid, j.grace)
          send(from, {ref, true})
          background_loop(%{j | status: :killed})
        else
          send(from, {ref, false})
          background_loop(j)
        end

      # Only the registry leaving stops the job; the port's own link exit and the
      # links of the System.cmd ports kill_job opens are not that.
      {:EXIT, owner, _reason} when owner == j.owner ->
        if j.status == :running, do: kill_job(port, j.os_pid, j.grace)
        :ok

      {:EXIT, _from, _reason} ->
        background_loop(j)
    end
  end

  # Stop the command AND everything it started. There is no Setpgid option on
  # `Port.open` (measured: it rejects one), so the tree is enumerated with `ps`
  # WHILE THE PARENT IS STILL ALIVE — after it dies its children are reparented
  # and are no longer reachable from its pid. Ask first, wait out the grace
  # window, then insist.
  defp kill_job(port, nil, _grace_ms) do
    close_port(port)
    false
  end

  defp kill_job(port, os_pid, grace_ms) do
    pids = [os_pid | descendants(os_pid)]
    signal(pids, "-TERM")
    # WAIT for the job to go, up to the grace window — do not SLEEP through it.
    # The window bounds how long the kill may take, not how long the caller
    # waits: a command that dies on SIGTERM in 5 ms must not cost its caller two
    # seconds. Measured parity break — five ports returned a 1 s timeout in
    # ~1.0 s while this one took ~3.05 s.
    unless await_exit(pids, grace_ms) do
      signal(pids, "-KILL")
    end

    close_port(port)
    true
  end

  # The job may exit between the check and the close; that race is not an error.
  defp close_port(port) do
    if Port.info(port), do: Port.close(port)
  rescue
    ArgumentError -> :ok
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
        System.cmd("taskkill", ["/T", "/F", "/PID", Integer.to_string(pid)],
          stderr_to_stdout: true
        )
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
                      parts: [
                        %ContentPart{
                          type: type,
                          mime_type: mime,
                          data: Base.encode64(content),
                          name: Path.basename(p)
                        }
                      ]
                    }

                  not String.valid?(content) ->
                    err("read: #{p}: not valid UTF-8 text and not a recognised media file")

                  args["offset"] == nil and args["limit"] == nil ->
                    ok(content)

                  true ->
                    lines = String.split(content, "\n")

                    offset =
                      if is_number(args["offset"]), do: max(1, trunc(args["offset"])), else: 1

                    start = offset - 1

                    limit =
                      if is_number(args["limit"]),
                        do: max(0, trunc(args["limit"])),
                        else: max(0, length(lines) - start)

                    out = lines |> Enum.slice(start, limit) |> Enum.join("\n")
                    # SPEC §4C: a window that stops before the end says so in metadata,
                    # with the offset to continue from. The text stays the pinned
                    # cross-port bytes. A trailing newline does not count as a line.
                    total = if List.last(lines) == "", do: length(lines) - 1, else: length(lines)
                    stop = min(start + limit, length(lines))

                    if stop < total,
                      do: ok(out, %{truncated: true, totalLines: total, nextOffset: stop + 1}),
                      else: ok(out)
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
              before = if File.regular?(full), do: File.read!(full), else: nil
              File.mkdir_p!(Path.dirname(Path.expand(full)))
              File.write!(full, content)
              bytes = byte_size(content)

              ok(
                "Wrote #{bytes} bytes to #{p}",
                Map.merge(%{bytes: bytes}, edit_metadata(env, [{full, before, content}]))
              )
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

            full =
              case resolve_path(env, p) do
                {:ok, resolved} -> resolved
                {:error, _} -> nil
              end

            case if full, do: File.read(full), else: {:error, elem(resolve_path(env, p), 1)} do
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
                    err(
                      "edit: oldString is not unique in #{p} (#{count} occurrences); use replaceAll"
                    )

                  true ->
                    next = String.replace(content, old_string, new_string, global: replace_all)
                    File.write!(full, next)
                    n = if replace_all, do: count, else: 1
                    plural = if n == 1, do: "", else: "s"

                    ok(
                      "Edited #{p} (#{n} replacement#{plural})",
                      Map.merge(%{replacements: n}, edit_metadata(env, [{full, content, next}]))
                    )
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
          "path" => %{
            "type" => "string",
            "description" => "Directory to search (default: process cwd)"
          },
          "include" => %{"type" => "string", "description" => "Glob filter for file names"},
          "limit" => %{
            "type" => "number",
            "description" => "Maximum number of matches (default 100)"
          },
          "context" => %{
            "type" => "number",
            "description" => "Lines of context before and after each match"
          },
          "before" => %{
            "type" => "number",
            "description" => "Lines of context before each match (overrides context)"
          },
          "after" => %{
            "type" => "number",
            "description" => "Lines of context after each match (overrides context)"
          }
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
                case if args["path"],
                       do: resolve_path(env, str(args["path"])),
                       else: {:ok, env_dir(env)} do
                  {:ok, r} -> r
                  {:error, _} -> nil
                end

              include = if args["include"], do: str(args["include"])
              limit = args["limit"] |> num(100) |> trunc()
              ctx_n = fn key -> if is_number(args[key]), do: trunc(args[key]), else: nil end

              {before, after_} =
                case ctx_n.("context") do
                  c when is_integer(c) and c > 0 -> {c, c}
                  _ -> {0, 0}
                end

              before = if (b = ctx_n.("before")) && b >= 0, do: b, else: before
              after_ = if (a = ctx_n.("after")) && a >= 0, do: a, else: after_

              # A26: `walk_files/1` hands back ONE globally sorted list, so the cap
              # truncates a sorted sequence instead of letting the walk decide. Every
              # hit is counted (not halted at the cap) so a cut can be reported.
              all_hits =
                walk_files(root)
                |> Enum.flat_map(fn file ->
                  # A28/A27c: grep EMITS the same relative `/`-path it sorts on.
                  # Sorting on one string and displaying another orders by one
                  # thing and shows the reader another.
                  rel = Path.relative_to(file, root)

                  with true <- is_nil(include) or match_glob?(rel, include),
                       {:ok, text} <- File.read(file) do
                    lines = String.split(text, "\n")

                    lines
                    |> Enum.with_index(1)
                    |> Enum.filter(fn {line, _i} -> Regex.match?(re, line) end)
                    # A27c: line numbers stay in NUMERIC order. They are never
                    # re-sorted as part of the rendered `path:line:text` string,
                    # which would put line 10 before line 2. `with_index` already
                    # ascends and the filter preserves it, so numeric order holds by
                    # construction — the files above it are ordered by relative
                    # path, giving (path, line) overall.
                    |> Enum.map(fn {line, i} -> {rel, i, line, lines} end)
                  else
                    _ -> []
                  end
                end)

              total = length(all_hits)
              hits = Enum.take(all_hits, max(limit, 0))

              out =
                if before == 0 and after_ == 0,
                  do: Enum.map(hits, fn {rel, i, line, _} -> "#{rel}:#{i}:#{line}" end),
                  else: grep_context(hits, before, after_)

              meta = %{count: length(hits)}

              meta =
                if total > length(hits),
                  do: Map.merge(meta, %{truncated: true, total: total}),
                  else: meta

              ok(Enum.join(out, "\n"), meta)
          end
        end
      end
    )
  end

  # ripgrep-style (SPEC §4C): match lines `path:N:text`, context lines `path-N-text`,
  # `--` between groups that do not touch.
  defp grep_context(hits, before, after_) do
    match_at = MapSet.new(hits, fn {rel, i, _, _} -> {rel, i} end)

    {out, _} =
      Enum.reduce(hits, {[], {nil, 0}}, fn {rel, i, _line, lines}, {out, {last_rel, last_line}} ->
        n = if List.last(lines) == "", do: length(lines) - 1, else: length(lines)
        from = max(1, i - before)
        to = min(n, i + after_)

        {from, out} =
          cond do
            rel == last_rel and from <= last_line + 1 -> {last_line + 1, out}
            last_rel != nil -> {from, ["--" | out]}
            true -> {from, out}
          end

        out =
          if from <= to,
            do:
              Enum.reduce(from..to//1, out, fn k, acc ->
                sep = if MapSet.member?(match_at, {rel, k}), do: ":", else: "-"
                ["#{rel}#{sep}#{k}#{sep}#{Enum.at(lines, k - 1)}" | acc]
              end),
            else: out

        last_line = if to > last_line or rel != last_rel, do: to, else: last_line
        {out, {rel, last_line}}
      end)

    Enum.reverse(out)
  end

  defp glob_tool(env) do
    builtin(
      "glob",
      "List files matching a glob under a directory. Output is newline-joined relative paths.",
      %{
        "type" => "object",
        "properties" => %{
          "pattern" => %{"type" => "string", "description" => "Glob pattern to match"},
          "path" => %{
            "type" => "string",
            "description" => "Directory to search (default: process cwd)"
          },
          "limit" => %{
            "type" => "number",
            "description" => "Maximum number of results (default 100)"
          }
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
            case if args["path"],
                   do: resolve_path(env, str(args["path"])),
                   else: {:ok, env_dir(env)} do
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
  # edit diffs (SPEC §4 "Edit diffs"): metadata.files + metadata.diff
  # ---------------------------------------------------------------------------

  # `changes` is a list of `{full_path, before | nil, after | nil}` — nil before is
  # an added file, nil after a deleted one. The counts come from a MINIMAL line
  # diff (Myers), so they agree with every port; the diff text need only be valid.
  defp edit_metadata(env, changes) do
    {files, diff} =
      Enum.map_reduce(changes, [], fn {full, before, after_}, diff ->
        rel = diff_path(env, full)
        ops = line_ops(diff_lines(before), diff_lines(after_))
        additions = Enum.count(ops, &(elem(&1, 0) == :ins))
        deletions = Enum.count(ops, &(elem(&1, 0) == :del))

        status =
          cond do
            before == nil -> "added"
            after_ == nil -> "deleted"
            true -> "modified"
          end

        header = [
          "--- " <> if(before == nil, do: "/dev/null", else: "a/" <> rel),
          "+++ " <> if(after_ == nil, do: "/dev/null", else: "b/" <> rel)
        ]

        {%{path: rel, status: status, additions: additions, deletions: deletions},
         diff ++ header ++ unified_hunks(ops)}
      end)

    %{files: files, diff: if(diff == [], do: "", else: Enum.join(diff, "\n") <> "\n")}
  end

  defp diff_path(%{base_dir: ""}, full), do: full

  defp diff_path(%{base_dir: base}, full) do
    full |> Path.expand() |> Path.relative_to(Path.expand(base)) |> String.replace("\\", "/")
  end

  defp diff_lines(nil), do: []
  defp diff_lines(""), do: []

  defp diff_lines(text) do
    lines = String.split(text, "\n")
    if List.last(lines) == "", do: Enum.drop(lines, -1), else: lines
  end

  # Flatten List.myers_difference/2 into one op per line.
  defp line_ops(a, b) do
    List.myers_difference(a, b)
    |> Enum.flat_map(fn {kind, lines} -> Enum.map(lines, &{kind, &1}) end)
  end

  # Unified hunks with 3 lines of context.
  defp unified_hunks(ops) do
    ctx = 3
    indexed = Enum.with_index(ops)
    changed = for {{k, _}, i} <- indexed, k != :eq, do: i

    groups =
      Enum.reduce(changed, [], fn
        i, [{s, e} | rest] when i - e <= 2 * ctx -> [{s, i} | rest]
        i, acc -> [{i, i} | acc]
      end)
      |> Enum.reverse()

    {positions, _} =
      Enum.map_reduce(ops, {1, 1}, fn {k, _}, {o, n} ->
        {{o, n}, {if(k == :ins, do: o, else: o + 1), if(k == :del, do: n, else: n + 1)}}
      end)

    positions = List.to_tuple(positions)
    last = length(ops) - 1

    Enum.flat_map(groups, fn {s0, e0} ->
      s = max(0, s0 - ctx)
      e = min(last, e0 + ctx)
      slice = Enum.slice(ops, s..e)
      old_len = Enum.count(slice, &(elem(&1, 0) != :ins))
      new_len = Enum.count(slice, &(elem(&1, 0) != :del))
      {o_at, n_at} = elem(positions, s)
      old_start = if old_len > 0, do: o_at, else: o_at - 1
      new_start = if new_len > 0, do: n_at, else: n_at - 1

      ["@@ -#{old_start},#{old_len} +#{new_start},#{new_len} @@"] ++
        Enum.map(slice, fn
          {:eq, l} -> " " <> l
          {:del, l} -> "-" <> l
          {:ins, l} -> "+" <> l
        end)
    end)
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
                    |> Enum.map(fn l ->
                      if String.starts_with?(l, "+"), do: strip_first(l), else: l
                    end)
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
          "patchText" => %{
            "type" => "string",
            "description" => "The patch text in Begin/End Patch format"
          }
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

            {writes, deletes, changes} =
              Enum.reduce(ops, {[], [], []}, fn op, {writes, deletes, changes} ->
                case op do
                  {:add, p, content} ->
                    if File.exists?(p), do: raise("file already exists: #{p}")
                    {[{p, content} | writes], deletes, [{p, nil, content} | changes]}

                  {:delete, p} ->
                    if not File.exists?(p), do: raise("file not found: #{p}")
                    before = if File.regular?(p), do: File.read!(p), else: ""
                    {writes, [p | deletes], [{p, before, nil} | changes]}

                  {:update, p, body} ->
                    content =
                      case File.read(p) do
                        {:ok, c} -> c
                        {:error, reason} -> raise "#{p}: #{:file.format_error(reason)}"
                      end

                    next = apply_update(content, body)
                    {[{p, next} | writes], deletes, [{p, content, next} | changes]}
                end
              end)

            Enum.each(Enum.reverse(writes), fn {p, content} ->
              File.mkdir_p!(Path.dirname(Path.expand(p)))
              File.write!(p, content)
            end)

            Enum.each(Enum.reverse(deletes), &File.rm/1)

            n = length(ops)
            plural = if n == 1, do: "", else: "s"

            ok(
              "Applied patch: #{n} file operation#{plural}",
              Map.merge(
                %{
                  added: Enum.count(ops, &match?({:add, _, _}, &1)),
                  updated: Enum.count(ops, &match?({:update, _, _}, &1)),
                  deleted: Enum.count(ops, &match?({:delete, _}, &1))
                },
                edit_metadata(env, Enum.reverse(changes))
              )
            )
          rescue
            e in RuntimeError -> err("apply_patch: #{e.message}")
          end
        end
      end
    )
  end

  # ---------------------------------------------------------------------------
  # opt-in builtins (SPEC §4C): background shells, websearch, notebook_edit
  # ---------------------------------------------------------------------------

  alias Toolnexus.Builtin.BackgroundShells

  defp id_schema do
    %{
      "type" => "object",
      "properties" => %{
        "id" => %{"type" => "string", "description" => "The background shell id bash returned"}
      },
      "required" => ["id"],
      "additionalProperties" => false
    }
  end

  defp bash_output_tool(env) do
    builtin(
      "bash_output",
      "Read the output a background bash command (started with run_in_background) produced since the last read, and whether it is still running.",
      id_schema(),
      fn args, _ctx ->
        id = str(args["id"])

        case BackgroundShells.read(env.background, id) do
          :error ->
            err(~s(bash_output: no background shell "#{id}"))

          {:ok, fresh, status, code} ->
            meta = %{id: id, status: Atom.to_string(status)}
            meta = if status == :exited, do: Map.put(meta, :exitCode, code), else: meta
            ok("#{fresh}\n[status: #{BackgroundShells.status_text(status, code)}]", meta)
        end
      end
    )
  end

  defp kill_shell_tool(env) do
    builtin(
      "kill_shell",
      "Stop a background bash command and everything it started.",
      id_schema(),
      fn args, _ctx ->
        id = str(args["id"])

        case BackgroundShells.kill_job(env.background, id) do
          :error ->
            err(~s(kill_shell: no background shell "#{id}"))

          {:ok, true} ->
            ok("Killed background shell #{id}", %{id: id, killed: true})

          {:ok, false} ->
            {status, code} = BackgroundShells.info(env.background, id)

            ok(
              "Background shell #{id} had already #{BackgroundShells.status_text(status, code)}",
              %{id: id, killed: false}
            )
        end
      end
    )
  end

  defp websearch_tool(search) do
    builtin(
      "websearch",
      "Search the web. Returns numbered results with title, URL and snippet.",
      %{
        "type" => "object",
        "properties" => %{
          "query" => %{"type" => "string", "description" => "The search query"},
          "limit" => %{
            "type" => "number",
            "description" => "Maximum number of results (default 5)"
          }
        },
        "required" => ["query"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        q = str(args["query"])

        limit =
          if is_number(args["limit"]) and args["limit"] >= 1, do: trunc(args["limit"]), else: 5

        if String.trim(q) == "" do
          err("websearch: query is required")
        else
          case search.(q, limit) do
            {:ok, results} when is_list(results) ->
              results = results |> Enum.take(limit) |> Enum.map(&search_hit/1)

              if results == [] do
                ok("No results.", %{count: 0})
              else
                text =
                  results
                  |> Enum.with_index(1)
                  |> Enum.map_join("\n\n", fn {r, i} ->
                    "#{i}. #{r.title}\n   #{r.url}" <>
                      if(r.snippet != "", do: "\n   #{r.snippet}", else: "")
                  end)

                ok(text, %{count: length(results), results: results})
              end

            {:error, reason} ->
              err("websearch: #{if is_binary(reason), do: reason, else: inspect(reason)}")
          end
        end
      end
    )
  end

  defp search_hit(r) do
    get = fn k -> str(Map.get(r, k) || Map.get(r, Atom.to_string(k)) || "") end
    %{title: get.(:title), url: get.(:url), snippet: get.(:snippet)}
  end

  defp notebook_edit_tool(env) do
    builtin(
      "notebook_edit",
      "Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell after it, or delete it. Replacing a code cell clears its outputs.",
      %{
        "type" => "object",
        "properties" => %{
          "path" => %{"type" => "string", "description" => "Path to the .ipynb file"},
          "cell_id" => %{"type" => "string", "description" => "The cell's id (or use cell_index)"},
          "cell_index" => %{
            "type" => "number",
            "description" => "0-based cell position (used when cell_id is absent)"
          },
          "new_source" => %{
            "type" => "string",
            "description" => "The new cell source (replace/insert)"
          },
          "cell_type" => %{
            "type" => "string",
            "enum" => ["code", "markdown"],
            "description" => "Cell type for insert (default code) or to change on replace"
          },
          "edit_mode" => %{
            "type" => "string",
            "enum" => ["replace", "insert", "delete"],
            "description" =>
              "Default replace. insert adds the new cell AFTER the given cell, or first when none is given"
          }
        },
        "required" => ["path"],
        "additionalProperties" => false
      },
      fn args, _ctx ->
        p = str(args["path"])

        with {:path, false} <- {:path, p == ""},
             {:ok, full} <- resolve_path(env, p),
             {:ok, raw} <- notebook_io(File.read(full)),
             {:ok, nb} <- decode_notebook(raw, p) do
          notebook_apply(env, args, p, full, raw, nb)
        else
          {:path, true} -> err("notebook_edit: path is required")
          {:error, message} -> err("notebook_edit: #{message}")
        end
      end
    )
  end

  defp notebook_io({:ok, raw}), do: {:ok, raw}
  defp notebook_io({:error, reason}), do: {:error, to_string(:file.format_error(reason))}

  defp decode_notebook(raw, p) do
    case Jason.decode(raw) do
      {:ok, %{} = nb} -> {:ok, nb}
      {:ok, _} -> {:error, "#{p} is not a notebook"}
      {:error, e} -> {:error, "#{p} is not a notebook: #{Exception.message(e)}"}
    end
  end

  defp notebook_apply(env, args, p, full, raw, nb) do
    cells = if is_list(nb["cells"]), do: nb["cells"], else: []
    mode = if args["edit_mode"] in [nil, ""], do: "replace", else: str(args["edit_mode"])
    src = str(args["new_source"])
    cell_type = str(args["cell_type"])

    idx =
      cond do
        is_binary(args["cell_id"]) and args["cell_id"] != "" ->
          case Enum.find_index(cells, &(is_map(&1) and &1["id"] == args["cell_id"])) do
            nil -> {:error, ~s(no cell with id "#{args["cell_id"]}")}
            i -> {:ok, i}
          end

        is_number(args["cell_index"]) ->
          i = trunc(args["cell_index"])

          if i < 0 or i >= length(cells),
            do: {:error, "cell_index #{i} out of range (0-#{length(cells) - 1})"},
            else: {:ok, i}

        mode == "insert" ->
          {:ok, -1}

        true ->
          {:error, "cell_id or cell_index is required"}
      end

    with {:ok, idx} <- idx,
         {:ok, cells, summary} <- notebook_edit_cells(mode, cells, idx, src, cell_type, p) do
      out = Jason.encode!(Map.put(nb, "cells", cells), pretty: [indent: " "]) <> "\n"
      File.write!(full, out)

      ok(summary, Map.merge(%{cells: length(cells)}, edit_metadata(env, [{full, raw, out}])))
    else
      {:error, message} -> err("notebook_edit: #{message}")
    end
  end

  defp notebook_edit_cells("replace", cells, idx, src, cell_type, p) do
    cell = Enum.at(cells, idx)
    cell = Map.put(cell, "source", notebook_source(src))
    cell = if cell_type != "", do: Map.put(cell, "cell_type", cell_type), else: cell

    cell =
      if cell["cell_type"] == "code",
        do: Map.merge(cell, %{"outputs" => [], "execution_count" => nil}),
        else: Map.drop(cell, ["outputs", "execution_count"])

    {:ok, List.replace_at(cells, idx, cell), "Replaced cell #{idx} in #{p}"}
  end

  defp notebook_edit_cells("insert", cells, idx, src, cell_type, p) do
    cell_type = if cell_type == "", do: "code", else: cell_type
    cell = %{"cell_type" => cell_type, "metadata" => %{}, "source" => notebook_source(src)}

    cell =
      if cell_type == "code",
        do: Map.merge(cell, %{"outputs" => [], "execution_count" => nil}),
        else: cell

    at = idx + 1
    {:ok, List.insert_at(cells, at, cell), "Inserted #{cell_type} cell at #{at} in #{p}"}
  end

  defp notebook_edit_cells("delete", cells, idx, _src, _cell_type, p),
    do: {:ok, List.delete_at(cells, idx), "Deleted cell #{idx} from #{p}"}

  defp notebook_edit_cells(mode, _cells, _idx, _src, _cell_type, _p),
    do: {:error, ~s(unknown edit_mode "#{mode}")}

  # nbformat's list of lines, each keeping its "\n".
  defp notebook_source(""), do: []
  defp notebook_source(s), do: s |> String.split(~r/(?<=\n)/) |> Enum.reject(&(&1 == ""))
end

defmodule Toolnexus.Builtin.BackgroundShells do
  @moduledoc """
  The registry of commands `bash` started with `run_in_background` (SPEC §4C).

  Create one with `new/0`, pass it as the builtins `:background` option, and call
  `close_all/1` when the host shuts down — a background command is NOT tied to the
  turn that started it, so nothing else stops it. `kill/2` stops one command's whole
  process tree (SIGTERM, the `kill_grace_ms` window, SIGKILL).

  The registry is a process started unlinked, so it outlives the turn that created a
  job; each job is a port-owning process that stops its tree if the registry exits.
  """
  use GenServer

  @doc "Start an empty registry (unlinked). Returns its pid."
  @spec new() :: pid()
  def new do
    {:ok, pid} = GenServer.start(__MODULE__, nil)
    pid
  end

  @doc "Every background command, oldest first: `%{id, command, started, running, exit_code, killed}`."
  @spec list(pid()) :: [map()]
  def list(reg) do
    reg
    |> GenServer.call(:jobs)
    |> Enum.sort_by(fn {_id, j} -> j.seq end)
    |> Enum.map(fn {id, j} ->
      {status, code} = job_call(j.pid, :info)

      %{
        id: id,
        command: j.command,
        started: j.started,
        running: status == :running,
        exit_code: code,
        killed: status == :killed
      }
    end)
  end

  @doc "Stop one background command's process tree. Unknown id ⇒ `{:error, message}`."
  @spec kill(pid(), String.t()) :: :ok | {:error, String.t()}
  def kill(reg, id) do
    case kill_job(reg, id) do
      :error -> {:error, ~s(no background shell "#{id}")}
      {:ok, _} -> :ok
    end
  end

  @doc "Stop every background command still running, concurrently."
  @spec close_all(pid()) :: :ok
  def close_all(reg) do
    reg
    |> GenServer.call(:jobs)
    |> Task.async_stream(fn {_id, j} -> job_call(j.pid, :kill) end, timeout: :infinity)
    |> Stream.run()
  end

  @doc false
  def start_job(reg, env, command, workdir),
    do: GenServer.call(reg, {:start, env, command, workdir})

  @doc false
  def read(reg, id) do
    case lookup(reg, id) do
      nil -> :error
      pid -> job_call(pid, :read)
    end
  end

  @doc false
  def kill_job(reg, id) do
    case lookup(reg, id) do
      nil -> :error
      pid -> {:ok, job_call(pid, :kill)}
    end
  end

  @doc false
  def info(reg, id), do: job_call(lookup(reg, id), :info)

  @doc false
  def status_text(:running, _code), do: "running"
  def status_text(:killed, _code), do: "killed"
  def status_text(:exited, code), do: "exited with code #{code}"

  defp lookup(reg, id), do: GenServer.call(reg, {:lookup, id})

  defp job_call(pid, op) do
    ref = make_ref()
    send(pid, {op, self(), ref})

    receive do
      {^ref, fresh, status, code} -> {:ok, fresh, status, code}
      {^ref, status, code} -> {status, code}
      {^ref, killed} -> killed
    end
  end

  @impl true
  def init(nil) do
    Process.flag(:trap_exit, true)
    {:ok, %{next: 0, jobs: %{}}}
  end

  @impl true
  def handle_call({:start, env, command, workdir}, _from, st) do
    next = st.next + 1
    id = "bg#{next}"
    me = self()
    pid = spawn_link(fn -> Toolnexus.Builtin.background_job(env, command, workdir, me) end)
    job = %{pid: pid, command: command, started: DateTime.utc_now(), seq: next}
    {:reply, id, %{st | next: next, jobs: Map.put(st.jobs, id, job)}}
  end

  def handle_call({:lookup, id}, _from, st), do: {:reply, get_in(st.jobs, [id, :pid]), st}
  def handle_call(:jobs, _from, st), do: {:reply, Map.to_list(st.jobs), st}

  @impl true
  def handle_info({:EXIT, _pid, _reason}, st), do: {:noreply, st}
end
