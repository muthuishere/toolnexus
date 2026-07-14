defmodule Toolnexus.BuiltinTest do
  use ExUnit.Case, async: false

  alias Toolnexus.{Builtin, ToolResult, Request, Context, Answer}

  # Golden input schemas, extracted byte-for-byte from js/src/builtin.ts.
  @golden_schemas Jason.decode!("""
  {
    "bash": {
      "type": "object",
      "properties": {
        "command": {"type": "string", "description": "The shell command to run"},
        "workdir": {"type": "string", "description": "Working directory (default: process cwd)"},
        "timeout": {"type": "number", "description": "Timeout in milliseconds (default 60000)"},
        "description": {"type": "string", "description": "Human-readable description of the command"}
      },
      "required": ["command"],
      "additionalProperties": false
    },
    "read": {
      "type": "object",
      "properties": {
        "path": {"type": "string", "description": "Path to the file to read"},
        "offset": {"type": "number", "description": "1-based line to start from"},
        "limit": {"type": "number", "description": "Maximum number of lines to read"}
      },
      "required": ["path"],
      "additionalProperties": false
    },
    "write": {
      "type": "object",
      "properties": {
        "path": {"type": "string", "description": "Path to write to"},
        "content": {"type": "string", "description": "Content to write"}
      },
      "required": ["path", "content"],
      "additionalProperties": false
    },
    "edit": {
      "type": "object",
      "properties": {
        "path": {"type": "string", "description": "Path to the file to edit"},
        "oldString": {"type": "string", "description": "Exact string to replace"},
        "newString": {"type": "string", "description": "Replacement string"},
        "replaceAll": {"type": "boolean", "description": "Replace all occurrences"}
      },
      "required": ["path", "oldString", "newString"],
      "additionalProperties": false
    },
    "grep": {
      "type": "object",
      "properties": {
        "pattern": {"type": "string", "description": "Regular expression to search for"},
        "path": {"type": "string", "description": "Directory to search (default: process cwd)"},
        "include": {"type": "string", "description": "Glob filter for file names"},
        "limit": {"type": "number", "description": "Maximum number of matches (default 100)"}
      },
      "required": ["pattern"],
      "additionalProperties": false
    },
    "glob": {
      "type": "object",
      "properties": {
        "pattern": {"type": "string", "description": "Glob pattern to match"},
        "path": {"type": "string", "description": "Directory to search (default: process cwd)"},
        "limit": {"type": "number", "description": "Maximum number of results (default 100)"}
      },
      "required": ["pattern"],
      "additionalProperties": false
    },
    "webfetch": {
      "type": "object",
      "properties": {
        "url": {"type": "string", "description": "URL to fetch"},
        "format": {"type": "string", "enum": ["text", "markdown", "html"], "description": "Response format (default markdown)"},
        "timeout": {"type": "number", "description": "Timeout in seconds (default 30)"}
      },
      "required": ["url"],
      "additionalProperties": false
    },
    "question": {
      "type": "object",
      "properties": {
        "questions": {
          "type": "array",
          "description": "Questions to ask",
          "items": {
            "type": "object",
            "properties": {
              "question": {"type": "string"},
              "header": {"type": "string"},
              "options": {"type": "array", "items": {"type": "string"}},
              "multiple": {"type": "boolean"}
            },
            "required": ["question"]
          }
        }
      },
      "required": ["questions"],
      "additionalProperties": false
    },
    "apply_patch": {
      "type": "object",
      "properties": {
        "patchText": {"type": "string", "description": "The patch text in Begin/End Patch format"}
      },
      "required": ["patchText"],
      "additionalProperties": false
    },
    "todowrite": {
      "type": "object",
      "properties": {
        "todos": {
          "type": "array",
          "description": "The full todo list to store",
          "items": {
            "type": "object",
            "properties": {
              "id": {"type": "string"},
              "text": {"type": "string"},
              "completed": {"type": "boolean"}
            },
            "required": ["id", "text", "completed"]
          }
        }
      },
      "required": ["todos"],
      "additionalProperties": false
    }
  }
  """)

  @golden_descriptions %{
    "bash" => "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
    "read" => "Read a UTF-8 text file. With offset/limit, return only that line window.",
    "write" => "Write content to a file (create/overwrite), creating parent directories.",
    "edit" =>
      "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
    "grep" => "Search file contents by regex under a directory. Output is file:line:text matches.",
    "glob" => "List files matching a glob under a directory. Output is newline-joined relative paths.",
    "webfetch" => "HTTP GET a URL and return its body as text, markdown, or html.",
    "question" =>
      "Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's waitFor resolves it and the answer is returned to the model.",
    "apply_patch" =>
      "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
    "todowrite" => "Replace the session todo list. Returns the rendered list."
  }

  @order ~w(bash read write edit grep glob webfetch question apply_patch todowrite)

  defp tool(name), do: Enum.find(Builtin.tools(), &(&1.name == name))
  defp run(name, args, ctx \\ nil), do: tool(name).execute.(args, ctx)

  describe "catalog" do
    test "ten tools in the fixed parity order, all source builtin" do
      tools = Builtin.tools()
      assert Enum.map(tools, & &1.name) == @order
      assert Enum.all?(tools, &(&1.source == "builtin"))
    end

    test "every input schema matches the golden extracted from js/src/builtin.ts" do
      for t <- Builtin.tools() do
        assert t.input_schema == @golden_schemas[t.name], "schema mismatch for #{t.name}"
      end
    end

    test "every description matches the JS reference byte-for-byte" do
      for t <- Builtin.tools() do
        assert t.description == @golden_descriptions[t.name], "description mismatch for #{t.name}"
      end
    end
  end

  describe "toggle semantics (§0.11)" do
    test "default and explicit-on configs load all ten" do
      assert length(Builtin.load()) == 10
      assert length(Builtin.load(nil)) == 10
      assert length(Builtin.load(true)) == 10
      assert length(Builtin.load(%{})) == 10
      assert length(Builtin.load(%{"enabled" => true})) == 10
    end

    test "global off: false / disabled:true / enabled:false" do
      assert Builtin.load(false) == []
      assert Builtin.load(%{"disabled" => true}) == []
      assert Builtin.load(%{disabled: true}) == []
      assert Builtin.load(%{"enabled" => false}) == []
      assert Builtin.load(%{enabled: false}) == []
    end

    test "per-tool map drops tools mapped to false from the all-on baseline" do
      names = Builtin.load(%{"tools" => %{"bash" => false, "write" => false}}) |> Enum.map(& &1.name)
      assert names == @order -- ["bash", "write"]

      # true / absent stay on; unknown names are ignored
      names2 = Builtin.load(%{"tools" => %{"bash" => true, "nosuch" => false}}) |> Enum.map(& &1.name)
      assert names2 == @order
    end

    test "whole-source-off wins over the per-tool map" do
      assert Builtin.load(%{"disabled" => true, "tools" => %{"bash" => true}}) == []
    end
  end

  describe "bash" do
    test "returns combined output on success with exitCode metadata" do
      result = run("bash", %{"command" => "printf hi; printf err 1>&2"})
      assert %ToolResult{is_error: false, metadata: %{exitCode: 0}} = result
      assert result.output == "hierr"
    end

    test "non-zero exit is an error including the exit code" do
      result = run("bash", %{"command" => "printf out; exit 3"})
      assert result.is_error
      assert result.output == "out\nbash: command exited with code 3"
      assert result.metadata == %{exitCode: 3}
    end

    test "missing command is an error" do
      assert run("bash", %{}) == %ToolResult{output: "bash: command is required", is_error: true}
    end

    @tag :tmp_dir
    test "workdir is honored", %{tmp_dir: tmp} do
      result = run("bash", %{"command" => "pwd", "workdir" => tmp})
      refute result.is_error
      assert String.trim(result.output) == tmp
    end

    test "timeout kills the command" do
      result = run("bash", %{"command" => "sleep 5", "timeout" => 150})
      assert result.is_error
      assert result.output =~ "bash: command timed out after 150ms"
    end
  end

  describe "read" do
    @tag :tmp_dir
    test "reads a whole file verbatim", %{tmp_dir: tmp} do
      p = Path.join(tmp, "f.txt")
      File.write!(p, "l1\nl2\nl3\nl4\n")
      assert run("read", %{"path" => p}) == %ToolResult{output: "l1\nl2\nl3\nl4\n", is_error: false}
    end

    @tag :tmp_dir
    test "offset/limit return a line window", %{tmp_dir: tmp} do
      p = Path.join(tmp, "f.txt")
      File.write!(p, "l1\nl2\nl3\nl4\n")
      assert run("read", %{"path" => p, "offset" => 2, "limit" => 2}).output == "l2\nl3"
      assert run("read", %{"path" => p, "offset" => 3}).output == "l3\nl4\n"
      assert run("read", %{"path" => p, "limit" => 1}).output == "l1"
    end

    test "missing file is an error with the read: prefix" do
      result = run("read", %{"path" => "/nonexistent/nope.txt"})
      assert result.is_error
      assert result.output =~ "read: "
    end

    test "missing path arg is an error" do
      assert run("read", %{}) == %ToolResult{output: "read: path is required", is_error: true}
    end
  end

  describe "write" do
    @tag :tmp_dir
    test "creates parent dirs and reports byte count", %{tmp_dir: tmp} do
      p = Path.join([tmp, "a", "b", "out.txt"])
      result = run("write", %{"path" => p, "content" => "hello"})
      assert result == %ToolResult{output: "Wrote 5 bytes to #{p}", is_error: false, metadata: %{bytes: 5}}
      assert File.read!(p) == "hello"
    end
  end

  describe "edit" do
    @tag :tmp_dir
    test "replaces a single unique occurrence", %{tmp_dir: tmp} do
      p = Path.join(tmp, "f.txt")
      File.write!(p, "alpha beta gamma")
      result = run("edit", %{"path" => p, "oldString" => "beta", "newString" => "BETA"})
      assert result == %ToolResult{output: "Edited #{p} (1 replacement)", is_error: false, metadata: %{replacements: 1}}
      assert File.read!(p) == "alpha BETA gamma"
    end

    @tag :tmp_dir
    test "not-found and non-unique oldString are errors; replaceAll replaces all", %{tmp_dir: tmp} do
      p = Path.join(tmp, "f.txt")
      File.write!(p, "x y x")

      assert run("edit", %{"path" => p, "oldString" => "z", "newString" => "w"}) ==
               %ToolResult{output: "edit: oldString not found in #{p}", is_error: true}

      assert run("edit", %{"path" => p, "oldString" => "x", "newString" => "w"}) ==
               %ToolResult{
                 output: "edit: oldString is not unique in #{p} (2 occurrences); use replaceAll",
                 is_error: true
               }

      result = run("edit", %{"path" => p, "oldString" => "x", "newString" => "w", "replaceAll" => true})
      assert result.output == "Edited #{p} (2 replacements)"
      assert result.metadata == %{replacements: 2}
      assert File.read!(p) == "w y w"
    end

    test "missing oldString is an error" do
      assert run("edit", %{"path" => "f", "oldString" => "", "newString" => "x"}) ==
               %ToolResult{output: "edit: oldString is required", is_error: true}
    end
  end

  describe "grep" do
    @tag :tmp_dir
    test "emits file:line:text matches, honoring include + skipping node_modules", %{tmp_dir: tmp} do
      File.write!(Path.join(tmp, "a.txt"), "hello world\nno match\nhello again")
      File.write!(Path.join(tmp, "b.md"), "hello md")
      File.mkdir_p!(Path.join(tmp, "node_modules"))
      File.write!(Path.join([tmp, "node_modules", "c.txt"]), "hello hidden")

      result = run("grep", %{"pattern" => "hello", "path" => tmp})
      lines = String.split(result.output, "\n")
      assert result.metadata == %{count: 3}

      assert "#{Path.join(tmp, "a.txt")}:1:hello world" in lines
      assert "#{Path.join(tmp, "a.txt")}:3:hello again" in lines
      assert "#{Path.join(tmp, "b.md")}:1:hello md" in lines

      filtered = run("grep", %{"pattern" => "hello", "path" => tmp, "include" => "*.md"})
      assert filtered.output == "#{Path.join(tmp, "b.md")}:1:hello md"
    end

    @tag :tmp_dir
    test "limit caps matches", %{tmp_dir: tmp} do
      File.write!(Path.join(tmp, "a.txt"), "m\nm\nm\nm")
      result = run("grep", %{"pattern" => "m", "path" => tmp, "limit" => 2})
      assert result.metadata == %{count: 2}
    end

    test "invalid regex is an error" do
      result = run("grep", %{"pattern" => "(unclosed"})
      assert result.is_error
      assert result.output =~ "grep: invalid regex:"
    end
  end

  describe "glob" do
    @tag :tmp_dir
    test "matches ** across directories, sorted relative paths", %{tmp_dir: tmp} do
      File.write!(Path.join(tmp, "z.txt"), "")
      File.mkdir_p!(Path.join(tmp, "sub"))
      File.write!(Path.join([tmp, "sub", "a.txt"]), "")
      File.write!(Path.join(tmp, "other.md"), "")
      File.mkdir_p!(Path.join(tmp, ".git"))
      File.write!(Path.join([tmp, ".git", "skip.txt"]), "")

      result = run("glob", %{"pattern" => "**/*.txt", "path" => tmp})
      assert result.output == "sub/a.txt\nz.txt"
      assert result.metadata == %{count: 2}
    end

    @tag :tmp_dir
    test "slash-less globs match on basename", %{tmp_dir: tmp} do
      File.mkdir_p!(Path.join(tmp, "deep"))
      File.write!(Path.join([tmp, "deep", "x.md"]), "")
      File.write!(Path.join(tmp, "y.md"), "")

      result = run("glob", %{"pattern" => "*.md", "path" => tmp})
      assert result.output == "deep/x.md\ny.md"
    end
  end

  describe "webfetch (local stub server)" do
    defmodule StubPlug do
      import Plug.Conn
      def init(opts), do: opts

      def call(conn, _opts) do
        case conn.request_path do
          "/page" ->
            send_resp(
              conn,
              200,
              "<html><head><script>var x=1;</script><style>.a{}</style></head>" <>
                "<body><h1>Hello</h1>\n\n\n\n<p>World</p></body></html>"
            )

          "/missing" ->
            send_resp(conn, 404, "gone")

          _ ->
            send_resp(conn, 200, "plain")
        end
      end
    end

    setup do
      {:ok, sock} = :gen_tcp.listen(0, ip: {127, 0, 0, 1})
      {:ok, port} = :inet.port(sock)
      :gen_tcp.close(sock)
      start_supervised!({Bandit, plug: StubPlug, scheme: :http, ip: {127, 0, 0, 1}, port: port})
      {:ok, base: "http://127.0.0.1:#{port}"}
    end

    test "default markdown format strips tags/scripts/styles", %{base: base} do
      result = run("webfetch", %{"url" => "#{base}/page"})
      refute result.is_error
      assert result.output == "Hello\n\nWorld"
      assert result.metadata == %{status: 200, format: "markdown"}
    end

    test "html format returns the raw body", %{base: base} do
      result = run("webfetch", %{"url" => "#{base}/page", "format" => "html"})
      assert result.output =~ "<h1>Hello</h1>"
      assert result.metadata == %{status: 200, format: "html"}
    end

    test "non-2xx is an error with HTTP <status>", %{base: base} do
      result = run("webfetch", %{"url" => "#{base}/missing"})
      assert result == %ToolResult{output: "HTTP 404", is_error: true, metadata: %{status: 404}}
    end

    test "missing url is an error" do
      assert run("webfetch", %{}) == %ToolResult{output: "webfetch: url is required", is_error: true}
    end
  end

  describe "question" do
    test "first call suspends with a kind:question Request and the rendered prompt" do
      questions = [
        %{"question" => "Pick one", "header" => "Choice", "options" => ["a", "b", "c"]},
        %{"question" => "Why?"}
      ]

      result = run("question", %{"questions" => questions})

      assert result.is_error
      assert ToolResult.pending?(result)
      assert %Request{kind: "question", prompt: prompt, data: data, id: "pnd-" <> _} = result.metadata.pending
      assert prompt == "Pick one (options: a, b, c)\nWhy?"
      assert result.output == prompt
      assert data == %{"questions" => questions}
    end

    test "re-execution with ctx.answer returns the answer data as JSON" do
      ctx = %Context{answer: %Answer{id: "pnd-1", ok: true, data: %{"answers" => ["a"]}}}
      result = run("question", %{"questions" => [%{"question" => "Pick one"}]}, ctx)
      assert result == %ToolResult{output: ~s({"answers":["a"]}), is_error: false}

      ctx2 = %Context{answer: %Answer{id: "pnd-2", ok: true}}
      assert run("question", %{"questions" => []}, ctx2).output == "{}"
    end
  end

  describe "apply_patch" do
    @tag :tmp_dir
    test "add + update + delete in one patch", %{tmp_dir: tmp} do
      upd = Path.join(tmp, "upd.txt")
      del = Path.join(tmp, "del.txt")
      new = Path.join(tmp, "new.txt")
      File.write!(upd, "keep\nold line\nend\n")
      File.write!(del, "bye")

      patch = """
      *** Begin Patch
      *** Add File: #{new}
      +hello
      +world
      *** Update File: #{upd}
      @@
      -old line
      +new line
      *** Delete File: #{del}
      *** End Patch
      """

      result = run("apply_patch", %{"patchText" => patch})
      assert result.output == "Applied patch: 3 file operations"
      assert result.metadata == %{added: 1, updated: 1, deleted: 1}
      assert File.read!(new) == "hello\nworld"
      assert File.read!(upd) == "keep\nnew line\nend\n"
      refute File.exists?(del)
    end

    @tag :tmp_dir
    test "a non-matching hunk aborts atomically with no writes", %{tmp_dir: tmp} do
      upd = Path.join(tmp, "upd.txt")
      new = Path.join(tmp, "new.txt")
      File.write!(upd, "content\n")

      patch = """
      *** Begin Patch
      *** Add File: #{new}
      +hello
      *** Update File: #{upd}
      @@
      -does not exist
      +replacement
      *** End Patch
      """

      result = run("apply_patch", %{"patchText" => patch})
      assert result == %ToolResult{output: "apply_patch: hunk does not match file contents", is_error: true}
      refute File.exists?(new)
      assert File.read!(upd) == "content\n"
    end

    @tag :tmp_dir
    test "adding an existing file is an error", %{tmp_dir: tmp} do
      p = Path.join(tmp, "exists.txt")
      File.write!(p, "x")

      patch = "*** Begin Patch\n*** Add File: #{p}\n+y\n*** End Patch"
      result = run("apply_patch", %{"patchText" => patch})
      assert result == %ToolResult{output: "apply_patch: file already exists: #{p}", is_error: true}
      assert File.read!(p) == "x"
    end

    test "malformed patches report grammar errors" do
      assert run("apply_patch", %{"patchText" => "nonsense"}) ==
               %ToolResult{output: "apply_patch: missing '*** Begin Patch'", is_error: true}

      assert run("apply_patch", %{"patchText" => "*** Begin Patch\n*** Add File: x\n+1"}) ==
               %ToolResult{output: "apply_patch: missing '*** End Patch'", is_error: true}

      assert run("apply_patch", %{"patchText" => "*** Begin Patch\nwat\n*** End Patch"}) ==
               %ToolResult{output: "apply_patch: unexpected line: wat", is_error: true}
    end
  end

  describe "todowrite" do
    test "renders the list" do
      todos = [
        %{"id" => "1", "text" => "ship it", "completed" => true},
        %{"id" => "2", "text" => "test it", "completed" => false}
      ]

      result = run("todowrite", %{"todos" => todos})
      assert result.output == "[x] ship it\n[ ] test it"
      assert result.metadata == %{todos: todos}
    end

    test "empty list renders (no todos)" do
      assert run("todowrite", %{"todos" => []}).output == "(no todos)"
    end
  end
end
