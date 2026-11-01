defmodule Toolnexus.HostEngineNeedsTest do
  @moduledoc """
  add-host-engine-needs (elixir): MCP `command` + `args` normalization (D1),
  FileConversationStore (D2) + in-memory list/delete, and the MCP idle timeout
  against the shared examples/mcp-progress-server.mjs fixture.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Client.{FileConversationStore, InMemoryConversationStore}
  alias Toolnexus.Mcp
  alias Toolnexus.Context

  @repo_root Path.expand("../..", __DIR__)
  @fixture Path.join(@repo_root, "examples/mcp-progress-server.mjs")

  # --- D1: command + args ------------------------------------------------------

  describe "command + args parsing" do
    test "string command + args, under every wrapper and the flat form" do
      server = %{"command" => "node", "args" => ["server.mjs"]}
      want = %{"s" => %{"command" => ["node", "server.mjs"]}}

      for wrapper <- ["mcpServers", "servers", "mcp"] do
        assert Mcp.parse_config(%{wrapper => %{"s" => server}}) == want
      end

      assert Mcp.parse_config(%{"s" => server, "builtins" => false}) == want

      assert Mcp.parse_config(~s({"mcpServers":{"s":{"command":"node","args":["server.mjs"]}}})) ==
               want
    end

    test "string command without args, list command + args, args with spaces" do
      assert Mcp.parse_config(%{"s" => %{"command" => "node"}})["s"]["command"] == ["node"]

      assert Mcp.parse_config(%{"s" => %{"command" => ["node", "a"], "args" => ["b"]}})["s"][
               "command"
             ] ==
               ["node", "a", "b"]

      assert Mcp.parse_config(%{
               "s" => %{"command" => "node", "args" => ["my file.mjs", "--x=a b"]}
             })["s"][
               "command"
             ] == ["node", "my file.mjs", "--x=a b"]

      assert Mcp.parse_config(%{"s" => %{"command" => "node", "args" => nil}})["s"]["command"] ==
               ["node"]
    end

    test "invalid shapes fail naming the server" do
      bad_command = [
        %{"command" => ""},
        %{"command" => []},
        %{"command" => ["node", 1]},
        %{"command" => [""]},
        %{"command" => 42}
      ]

      for cfg <- bad_command do
        assert_raise ArgumentError, ~s(mcp server "bad": invalid command), fn ->
          Mcp.parse_config(%{"mcpServers" => %{"bad" => cfg}})
        end
      end

      for args <- ["x", ["ok", 1], %{"a" => 1}] do
        assert_raise ArgumentError, ~s(mcp server "bad": invalid args), fn ->
          Mcp.parse_config(%{"mcpServers" => %{"bad" => %{"command" => "node", "args" => args}}})
        end
      end
    end

    test "remote servers ignore args; servers without command pass through" do
      remote = %{"url" => "https://example.com/mcp", "command" => 1, "args" => "ignored"}
      assert Mcp.parse_config(%{"r" => remote}) == %{"r" => remote}
      typed = %{"type" => "remote", "url" => "https://x", "args" => ["a"], "command" => "x"}
      assert Mcp.parse_config(%{"r" => typed}) == %{"r" => typed}
      assert Mcp.parse_config(%{"n" => %{"type" => "local"}}) == %{"n" => %{"type" => "local"}}
    end

    test "a string command + args actually spawns (no shell)" do
      if node = System.find_executable("node") do
        source =
          Mcp.load(%{
            "mcpServers" => %{
              "p" => %{"command" => node, "args" => [@fixture], "timeout" => 5_000}
            }
          })

        on_exit(fn -> Mcp.close(source) end)
        assert source.status == %{"p" => "connected"}
      end
    end
  end

  # --- D2: FileConversationStore ------------------------------------------------

  defp tmp_root do
    root = Path.join(System.tmp_dir!(), "tn-fcs-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(root) end)
    Path.join(root, "nested")
  end

  describe "FileConversationStore" do
    test "sync modes round-trip and leave no temp file; unknown mode raises" do
      assert_raise ArgumentError, ~r/sync mode/, fn ->
        FileConversationStore.new(tmp_root(), sync: :bogus)
      end

      msgs = [%{"role" => "user", "content" => "hi"}]

      for opts <- [[], [sync: :full], [sync: :fsync], [sync: :none]] do
        root = tmp_root()
        :ok = FileConversationStore.save(FileConversationStore.new(root, opts), "c1", msgs)
        assert FileConversationStore.get(FileConversationStore.new(root), "c1") == msgs
        assert File.ls!(root) == ["c1.json"], inspect(opts)
      end
    end

    test "round trip survives a new store instance; missing id is nil" do
      root = tmp_root()

      msgs = [
        %{"role" => "user", "content" => "hi"},
        %{"role" => "assistant", "content" => "yo ✓"}
      ]

      assert FileConversationStore.get(FileConversationStore.new(root), "c1") == nil
      :ok = FileConversationStore.save(FileConversationStore.new(root), "c1", msgs)
      assert FileConversationStore.get(FileConversationStore.new(root), "c1") == msgs

      assert Jason.decode!(File.read!(Path.join(root, "c1.json"))) == %{
               "version" => 1,
               "messages" => msgs
             }

      assert File.stat!(root).mode |> Bitwise.band(0o777) == 0o700
      assert File.stat!(Path.join(root, "c1.json")).mode |> Bitwise.band(0o777) == 0o600
    end

    test "root is required" do
      assert_raise ArgumentError, fn -> FileConversationStore.new("") end
      assert_raise ArgumentError, fn -> FileConversationStore.new(nil) end
    end

    test "traversal and invalid ids are rejected by every operation" do
      root = tmp_root()
      store = FileConversationStore.new(root)
      long = String.duplicate("a", 129)

      for id <- ["../x", "a/b", ".", "..", "", long, "a b", "a\\b", nil] do
        assert_raise ArgumentError, fn -> FileConversationStore.get(store, id) end
        assert_raise ArgumentError, fn -> FileConversationStore.save(store, id, []) end
        assert_raise ArgumentError, fn -> FileConversationStore.delete(store, id) end
      end

      refute File.exists?(root)
      refute File.exists?(Path.join(Path.dirname(root), "x.json"))
      assert FileConversationStore.valid_id?(String.duplicate("a", 128))
      assert FileConversationStore.valid_id?("A-z_0.9")
    end

    test "corrupt file, unknown version, or malformed body is an error, not nil" do
      root = tmp_root()
      store = FileConversationStore.new(root)
      File.mkdir_p!(root)

      File.write!(Path.join(root, "bad.json"), "{not json")

      assert_raise FileConversationStore.Error, ~r/corrupt/, fn ->
        FileConversationStore.get(store, "bad")
      end

      File.write!(Path.join(root, "v2.json"), ~s({"version":2,"messages":[]}))

      assert_raise FileConversationStore.Error, ~r/unknown version/, fn ->
        FileConversationStore.get(store, "v2")
      end

      File.write!(Path.join(root, "odd.json"), ~s({"version":1,"messages":{}}))

      assert_raise FileConversationStore.Error, ~r/malformed/, fn ->
        FileConversationStore.get(store, "odd")
      end

      File.mkdir_p!(Path.join(root, "dir.json"))

      assert_raise FileConversationStore.Error, ~r/unreadable/, fn ->
        FileConversationStore.get(store, "dir")
      end

      assert_raise FileConversationStore.Error, ~r/delete failed/, fn ->
        FileConversationStore.delete(store, "dir")
      end
    end

    test "list is newest first, ties by id ascending, skips temp + invalid names" do
      root = tmp_root()
      store = FileConversationStore.new(root)
      assert FileConversationStore.list(store) == []

      for id <- ["a", "b", "c", "d"], do: :ok = FileConversationStore.save(store, id, [])
      # mtime has 1 s resolution on the BEAM: pin times explicitly.
      File.touch!(Path.join(root, "a.json"), 1_000)
      File.touch!(Path.join(root, "b.json"), 2_000)
      File.touch!(Path.join(root, "c.json"), 2_000)
      File.touch!(Path.join(root, "d.json"), 500)
      File.write!(Path.join(root, ".a.xyz.tmp"), "x")
      File.write!(Path.join(root, "bad name.json"), "x")
      File.write!(Path.join(root, "notes.txt"), "x")

      assert [%{id: "b", updated_at: %DateTime{} = t}, %{id: "c"}, %{id: "a"}, %{id: "d"}] =
               FileConversationStore.list(store)

      assert DateTime.to_unix(t) == 2_000
    end

    test "delete then get / list; deleting again succeeds" do
      store = FileConversationStore.new(tmp_root())
      :ok = FileConversationStore.save(store, "x", [%{"role" => "user", "content" => "1"}])
      assert :ok = FileConversationStore.delete(store, "x")
      assert FileConversationStore.get(store, "x") == nil
      assert FileConversationStore.list(store) == []
      assert :ok = FileConversationStore.delete(store, "x")
    end

    test "concurrent saves of one id leave a valid file and no temp files" do
      root = tmp_root()
      store = FileConversationStore.new(root)

      1..40
      |> Enum.map(fn i ->
        Task.async(fn ->
          FileConversationStore.save(store, "same", [%{"role" => "user", "content" => "#{i}"}])
        end)
      end)
      |> Enum.each(&Task.await/1)

      assert [%{"role" => "user", "content" => _}] = FileConversationStore.get(store, "same")
      assert File.ls!(root) == ["same.json"]
    end

    test "implements the ConversationStore behaviour" do
      assert Toolnexus.Client.ConversationStore in (FileConversationStore.__info__(:attributes)[
                                                      :behaviour
                                                    ] || [])
    end
  end

  describe "InMemoryConversationStore list/delete" do
    test "list newest first; delete idempotent" do
      store = InMemoryConversationStore.new()
      assert InMemoryConversationStore.list(store) == []
      InMemoryConversationStore.save(store, "a", [])
      InMemoryConversationStore.save(store, "b", [%{"role" => "user"}])

      assert [%{id: "b", updated_at: %DateTime{}}, %{id: "a"}] =
               InMemoryConversationStore.list(store)

      InMemoryConversationStore.save(store, "a", [])
      assert [%{id: "a"}, %{id: "b"}] = InMemoryConversationStore.list(store)
      assert :ok = InMemoryConversationStore.delete(store, "a")
      assert InMemoryConversationStore.get(store, "a") == nil
      assert [%{id: "b"}] = InMemoryConversationStore.list(store)
      assert :ok = InMemoryConversationStore.delete(store, "a")
      assert InMemoryConversationStore.get(store, "b") == [%{"role" => "user"}]
    end
  end

  # --- MCP idle timeout (shared fixture) -----------------------------------------

  describe "MCP idle timeout (examples/mcp-progress-server.mjs)" do
    setup do
      case System.find_executable("node") do
        nil ->
          :ok

        node ->
          source =
            Mcp.load(%{
              "mcpServers" => %{"progress" => %{"command" => [node, @fixture], "timeout" => 300}}
            })

          on_exit(fn -> Mcp.close(source) end)
          tool = Enum.find(source.tools, &String.ends_with?(&1.name, "slow"))
          {:ok, tool: tool}
      end
    end

    test "progress every 100ms keeps a 1200ms call alive past a 300ms timeout", ctx do
      if tool = ctx[:tool] do
        r = tool.execute.(%{"totalMs" => 1200, "intervalMs" => 100}, %Context{})
        refute r.is_error, inspect(r)
        body = Jason.decode!(r.output)
        assert body["progressTokenReceived"] == true
        assert body["notificationsSent"] > 3
      end
    end

    test "no progress (interval 0) times out", ctx do
      if tool = ctx[:tool] do
        r = tool.execute.(%{"totalMs" => 1200, "intervalMs" => 0}, %Context{})
        assert r.is_error
        assert r.output =~ "timed out"
      end
    end

    test "progress for a non-matching token does not reset the timer", ctx do
      if tool = ctx[:tool] do
        r =
          tool.execute.(
            %{"totalMs" => 1200, "intervalMs" => 100, "wrongToken" => true},
            %Context{}
          )

        assert r.is_error
        assert r.output =~ "timed out"
      end
    end
  end
end
