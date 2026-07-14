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

  alias Toolnexus.{Tool, ToolResult, Request}

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
      all = tools()

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
  @spec tools() :: [Tool.t()]
  def tools do
    [
      bash_tool(),
      read_tool(),
      write_tool(),
      edit_tool(),
      grep_tool(),
      glob_tool(),
      webfetch_tool(),
      question_tool(),
      apply_patch_tool(),
      todowrite_tool()
    ]
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

  # Recursively list files under root (skips node_modules/.git).
  defp walk_files(root) do
    case File.ls(root) do
      {:error, _} ->
        []

      {:ok, entries} ->
        Enum.flat_map(Enum.sort(entries), fn name ->
          full = Path.join(root, name)

          cond do
            File.dir?(full) -> if name in @ignore_dirs, do: [], else: walk_files(full)
            File.regular?(full) -> [full]
            true -> []
          end
        end)
    end
  end

  # ---------------------------------------------------------------------------
  # individual tools
  # ---------------------------------------------------------------------------

  defp bash_tool do
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

        if command == "" do
          err("bash: command is required")
        else
          workdir = if args["workdir"], do: str(args["workdir"]), else: File.cwd!()
          timeout = args["timeout"] |> num(60_000) |> round()
          # Run the port loop in its own task so port messages never leak into
          # the caller's mailbox; the port dies with the task.
          Task.async(fn -> run_shell(command, workdir, timeout) end) |> Task.await(:infinity)
        end
      end
    )
  end

  defp run_shell(command, workdir, timeout) do
    port =
      Port.open({:spawn_executable, "/bin/sh"}, [
        :binary,
        :exit_status,
        :stderr_to_stdout,
        args: ["-c", command],
        cd: workdir
      ])

    deadline = System.monotonic_time(:millisecond) + timeout
    collect_shell(port, "", deadline, timeout)
  rescue
    e -> err("bash: #{Exception.message(e)}")
  end

  defp collect_shell(port, out, deadline, timeout) do
    remaining = max(deadline - System.monotonic_time(:millisecond), 0)

    receive do
      {^port, {:data, d}} ->
        collect_shell(port, out <> d, deadline, timeout)

      {^port, {:exit_status, 0}} ->
        ok(out, %{exitCode: 0})

      {^port, {:exit_status, code}} ->
        err("#{out}\nbash: command exited with code #{code}", %{exitCode: code})
    after
      remaining ->
        case Port.info(port, :os_pid) do
          {:os_pid, os_pid} -> System.cmd("kill", ["-9", Integer.to_string(os_pid)], stderr_to_stdout: true)
          _ -> :ok
        end

        if Port.info(port), do: Port.close(port)
        err("bash: command timed out after #{timeout}ms\n#{out}")
    end
  end

  defp read_tool do
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
            case File.read(p) do
              {:error, reason} ->
                err("read: #{p}: #{:file.format_error(reason)}")

              {:ok, content} ->
                if args["offset"] == nil and args["limit"] == nil do
                  ok(content)
                else
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

  defp write_tool do
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
          File.mkdir_p!(Path.dirname(Path.expand(p)))
          File.write!(p, content)
          bytes = byte_size(content)
          ok("Wrote #{bytes} bytes to #{p}", %{bytes: bytes})
        end
      end
    )
  end

  defp edit_tool do
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

            case File.read(p) do
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
                    File.write!(p, next)
                    n = if replace_all, do: count, else: 1
                    plural = if n == 1, do: "", else: "s"
                    ok("Edited #{p} (#{n} replacement#{plural})", %{replacements: n})
                end
            end
        end
      end
    )
  end

  defp grep_tool do
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
              root = if args["path"], do: str(args["path"]), else: File.cwd!()
              include = if args["include"], do: str(args["include"])
              limit = args["limit"] |> num(100) |> trunc()

              matches =
                walk_files(root)
                |> Enum.reduce_while([], fn file, acc ->
                  if length(acc) >= limit do
                    {:halt, acc}
                  else
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
                            |> Enum.map(fn {line, i} -> "#{file}:#{i}:#{line}" end)
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

  defp glob_tool do
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
          root = if args["path"], do: str(args["path"]), else: File.cwd!()
          limit = args["limit"] |> num(100) |> trunc()

          found =
            walk_files(root)
            |> Enum.map(&Path.relative_to(&1, root))
            |> Enum.filter(&match_glob?(&1, pattern))
            |> Enum.take(limit)
            |> Enum.sort()

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

  defp apply_patch_tool do
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
            ops = parse_patch(patch_text)

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
