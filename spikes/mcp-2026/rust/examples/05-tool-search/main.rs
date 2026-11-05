//! Example 05 — lazy tool search: give the model 2 tools instead of 100.
//!
//! Run: cargo run --example 05-tool-search
//!
//! With c.tool_search = true, c.tools() returns two meta-tools, search_tools(query, limit?) and
//! call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
//! calls what it found. c.invoke runs whatever tool call the model emits.
use mcp2026::{self as mcp, Client, HttpServer, Server};
use serde_json::json;

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    // A server with 100 tools: 10 services x 10 verbs.
    let mut srv = Server::new();
    for svc in [
        "github", "slack", "jira", "gmail", "calendar", "drive", "stripe", "postgres", "s3",
        "notion",
    ] {
        for verb in [
            "create", "get", "list", "update", "delete", "search", "archive", "export", "share",
            "comment",
        ] {
            let name = format!("{svc}_{verb}");
            let n = name.clone();
            srv.add_tool(
                &name,
                &format!("{verb} an item in {svc}."),
                move |_, args| {
                    Ok(json!(format!(
                        "{n} ran with {}",
                        args["text"].as_str().unwrap_or("<nil>")
                    )))
                },
            );
        }
    }
    let ts = HttpServer::start(srv).await;
    let url = format!("{}/mcp", ts.url);

    let eager = Client::new(&url);
    let all = eager.tools().await?;

    // 1. Turn on tool search; description tells the model what this server is for.
    let mut c = Client::new(&url);
    c.tool_search = true;
    c.description = "SaaS workspace (GitHub, Slack, Jira, ...)".into();
    let meta = c.tools().await?;
    println!("eager: {} tools, {} bytes of schema", all.len(), size(&all));
    println!(
        "lazy:  {} tools, {} bytes of schema",
        meta.len(),
        size(&meta)
    );

    // 2. The model calls search_tools ...
    let hits = c.search_tools("send a slack message create", 3).await?;
    println!("top hit: {}", hits[0]["name"].as_str().unwrap_or_default());

    // 3. ... then call_tool with the name it found. invoke routes both meta-tools.
    let res = c
        .invoke(
            "call_tool",
            json!({"name": hits[0]["name"], "arguments": {"text": "hi team"}}),
            None,
        )
        .await?;
    println!("result: {}", mcp::text(&res));
    Ok(())
}

fn size(v: &[mcp::Obj]) -> usize {
    serde_json::to_vec(v).map(|b| b.len()).unwrap_or(0)
}
