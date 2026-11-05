//! Lazy tool search (port of toolsearch_test.go).
use mcp2026::{Client, HttpServer, Server, fixture_server, text};
use serde_json::json;

async fn hundred_tool_server() -> HttpServer {
    let services = [
        ("github", "issue"),
        ("slack", "message"),
        ("jira", "ticket"),
        ("gmail", "email"),
        ("calendar", "event"),
        ("drive", "file"),
        ("stripe", "invoice"),
        ("postgres", "row"),
        ("s3", "object"),
        ("notion", "page"),
    ];
    let verbs = [
        "create", "get", "list", "update", "delete", "search", "archive", "export", "share",
        "comment",
    ];
    let mut tools = vec![];
    for (s, noun) in services {
        for v in verbs {
            tools.push(json!({"name": format!("{s}_{v}_{noun}"),
                "description": format!("{v} a {noun} in {s}. Requires the workspace to be connected; returns the {noun} as JSON with its id, timestamps and owner."),
                "inputSchema": {"type": "object", "properties": {
                    "id": {"type": "string", "description": format!("The {noun} id (omit when creating).")},
                    "query": {"type": "string", "description": "Free-text filter applied server-side."},
                    "body": {"type": "object", "description": format!("Fields of the {noun} to write.")},
                    "options": {"type": "object", "properties": {"dryRun": {"type": "boolean"}, "limit": {"type": "integer"}}},
                }, "required": ["query"]}}));
        }
    }
    let dir = std::env::temp_dir().join(format!("mcp2026-ts-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let f = dir.join("f.json");
    std::fs::write(
        &f,
        json!({"server": {"name": "big", "version": "1"}, "tools": tools, "skillsDir": "."})
            .to_string(),
    )
    .unwrap();
    let s = Server::load(&f).unwrap();
    let _ = std::fs::remove_dir_all(&dir);
    HttpServer::start(s).await
}

#[tokio::test]
async fn tool_search_ranking_and_size() {
    let ts = hundred_tool_server().await;
    let url = format!("{}/mcp", ts.url);
    let eager = Client::new(&url);
    let mut lazy = Client::new(&url);
    lazy.tool_search = true;
    lazy.description = "SaaS workspace (GitHub, Slack, Jira, ...)".into();
    let all = eager.tools().await.unwrap();
    assert_eq!(all.len(), 100);
    let meta = lazy.tools().await.unwrap();
    for (q, want) in [
        ("create slack message", "slack_create_message"),
        ("search stripe invoices", "stripe_search_invoice"),
        ("archive a notion page", "notion_archive_page"),
        ("s3 export", "s3_export_object"),
    ] {
        let hits = lazy.search_tools(q, 5).await.unwrap();
        assert!(
            !hits.is_empty()
                && hits[0]["name"] == want
                && hits[0]["server"] == lazy.description.as_str(),
            "{q}: {hits:?}"
        );
    }
    let r = lazy
        .invoke(
            "search_tools",
            json!({"query": "create slack message"}),
            None,
        )
        .await
        .unwrap();
    let (eb, lb, rb) = (
        json!(all).to_string().len(),
        json!(meta).to_string().len(),
        r["content"].to_string().len(),
    );
    println!(
        "tool JSON bytes: eager(100 tools)={eb}  lazy(2 meta-tools)={lb}  + one search_tools result(5 hits)={rb}"
    );
    assert!(lb * 10 < eb, "lazy not smaller: {lb} vs {eb}");
}

#[tokio::test]
async fn tool_search_invoke_calls_found_tool() {
    let ts = HttpServer::start(fixture_server()).await;
    let mut c = Client::new(format!("{}/mcp", ts.url));
    c.tool_search = true;
    let hits = c.search_tools("echo text back", 3).await.unwrap();
    assert!(!hits.is_empty() && hits[0]["name"] == "echo", "{hits:?}");
    let r = c
        .invoke(
            "call_tool",
            json!({"name": "echo", "arguments": {"text": "found"}}),
            None,
        )
        .await
        .unwrap();
    assert_eq!(text(&r), "found");
    assert!(
        c.invoke("echo", json!({}), None).await.is_err(),
        "direct tool names are hidden in ToolSearch mode"
    );
}
