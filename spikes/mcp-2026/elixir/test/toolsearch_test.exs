defmodule Mcp2026.ToolSearchTest do
  use ExUnit.Case, async: true
  alias Mcp2026.{Client, Server}

  @tag :tmp_dir
  test "BM25 ranking over 100 realistic tools, and lazy is >10x smaller", %{tmp_dir: dir} do
    services = [{"github", "issue"}, {"slack", "message"}, {"jira", "ticket"}, {"gmail", "email"}, {"calendar", "event"},
                {"drive", "file"}, {"stripe", "invoice"}, {"postgres", "row"}, {"s3", "object"}, {"notion", "page"}]
    verbs = ~w(create get list update delete search archive export share comment)
    tools = for {s, o} <- services, v <- verbs, do: %{
      "name" => "#{s}_#{v}_#{o}",
      "description" => "#{v} a #{o} in #{s}. Requires the workspace to be connected; returns the #{o} as JSON with its id, timestamps and owner.",
      "inputSchema" => %{"type" => "object", "properties" => %{"id" => %{"type" => "string"}, "query" => %{"type" => "string"}}, "required" => ["query"]}}
    File.write!(Path.join(dir, "f.json"), JSON.encode!(%{"server" => %{"name" => "big"}, "tools" => tools, "skillsDir" => "."}))
    {:ok, s} = Server.load(Path.join(dir, "f.json"))
    url = Server.serve(s).url
    {:ok, all} = Client.tools(Client.new(url))
    lazy = Client.new(url, tool_search: true, description: "SaaS workspace")
    {:ok, meta} = Client.tools(lazy)
    assert length(all) == 100
    assert byte_size(JSON.encode!(meta)) * 10 < byte_size(JSON.encode!(all))

    for {q, want} <- [{"create slack message", "slack_create_message"}, {"search stripe invoices", "stripe_search_invoice"},
                      {"archive a notion page", "notion_archive_page"}, {"s3 export", "s3_export_object"}] do
      assert {:ok, [%{"name" => ^want, "server" => "SaaS workspace"} | _]} = Client.search_tools(lazy, q, 5)
    end
  end

  test "invoke routes call_tool; direct names are hidden" do
    c = Client.new(Server.serve(Server.fixture()).url, tool_search: true)
    assert {:ok, [%{"name" => "echo"} | _]} = Client.search_tools(c, "echo text back", 3)
    assert {:ok, r} = Client.invoke(c, "call_tool", %{"name" => "echo", "arguments" => %{"text" => "found"}})
    assert Mcp2026.text(r) == "found"
    assert {:error, _} = Client.invoke(c, "echo", %{})
  end

  test "tokens split camelCase and separators" do
    assert Mcp2026.ToolSearch.tokens("getUserID_list-x") == ["get", "user", "id", "list", "x"]
  end
end
