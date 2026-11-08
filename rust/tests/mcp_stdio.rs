//! The in-house frozen-path stdio MCP client (SPEC §2.F, §2.2a) against
//! tests/fixtures/mcp-legacy-stdio-server.mjs (skips without node).

use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{Map, Value, json};
use toolnexus::ToolContext;
use toolnexus::client::WaitForFn;
use toolnexus::mcp::*;
use toolnexus::{Answer, Request};

fn node() -> bool {
    let ok = std::process::Command::new("node")
        .arg("--version")
        .output()
        .is_ok();
    if !ok {
        eprintln!("node not on PATH; skipping");
    }
    ok
}

fn config(timeout_ms: u64) -> McpConfig {
    let f =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/mcp-legacy-stdio-server.mjs");
    parse_mcp_config(&json!({"mcpServers": {
        "leg": {"command": ["node", f.to_string_lossy()], "timeout": timeout_ms},
    }}))
    .unwrap()
}

async fn load(timeout_ms: u64, wait_for: Option<WaitForFn>) -> McpSource {
    let src = load_mcp_with(
        &config(timeout_ms),
        McpLoadOptions {
            wait_for,
            ..Default::default()
        },
    )
    .await;
    assert_eq!(src.status["leg"], McpStatus::Connected, "{:?}", src.errors);
    src
}

async fn call(src: &McpSource, tool: &str, args: Value) -> toolnexus::ToolResult {
    call_ctx(src, tool, args, ToolContext::default()).await
}

async fn call_ctx(
    src: &McpSource,
    tool: &str,
    args: Value,
    ctx: ToolContext,
) -> toolnexus::ToolResult {
    let name = format!("leg_{tool}");
    let t = src
        .tools
        .iter()
        .find(|t| t.name == name)
        .unwrap_or_else(|| panic!("{name} loaded"));
    t.call(args, ctx).await
}

async fn server_log(src: &McpSource) -> Value {
    let r = call(src, "log", json!({})).await;
    serde_json::from_str(&r.output).unwrap()
}

#[tokio::test]
async fn handshake_pagination_names_and_hints() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let names: Vec<&str> = src.tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(
        names,
        [
            "leg_echo",
            "leg_image",
            "leg_structured",
            "leg_fail",
            "leg_progress",
            "leg_silent",
            "leg_ask",
            "leg_reverse",
            "leg_die",
            "leg_orphan",
            "leg_log",
            "leg_flood"
        ],
        "both pages, in list order"
    );
    let echo = &src.tools[0];
    assert_eq!(echo.description, "echo tool");
    assert_eq!(
        echo.input_schema,
        json!({"type": "object", "properties": {"x": {"type": "string"}}, "required": ["x"], "additionalProperties": false})
    );
    let h = echo.hints.unwrap();
    assert!(h.read_only && !h.destructive && h.open_world);
    assert!(src.tools[1].hints.is_none());

    let log = server_log(&src).await;
    assert_eq!(log["initialized"], true);
    let init = &log["initialize"];
    assert_eq!(init["clientInfo"]["name"], "toolnexus");
    assert!(init["protocolVersion"].is_string());
    assert_eq!(
        init["capabilities"],
        json!({}),
        "no wait_for ⇒ no elicitation"
    );
    assert_eq!(log["listParams"], json!([null, {"cursor": "page-2"}]));
    let r = call(&src, "echo", json!({"x": "y"})).await;
    assert_eq!(r.output, r#"{"x":"y"}"#);
    assert_eq!(r.metadata.unwrap()["server"], "leg");
    src.close().await;
}

#[tokio::test]
async fn content_mapping_images_structured_and_is_error() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let r = call(&src, "image", json!({})).await;
    assert!(!r.is_error);
    assert_eq!(r.output, "see image");
    assert_eq!(r.parts.len(), 2);
    assert_eq!(
        (
            r.parts[0].kind.as_str(),
            r.parts[0].mime_type.as_str(),
            r.parts[0].data.as_str()
        ),
        ("image", "image/png", "aGVsbG8=")
    );
    assert_eq!(
        (r.parts[1].kind.as_str(), r.parts[1].url.as_str()),
        ("file", "file:///a.txt")
    );

    let r = call(&src, "structured", json!({})).await;
    assert_eq!(r.output, r#"{"a":[2],"b":1}"#);
    assert_eq!(r.parts.len(), 1, "the image survives structuredContent");

    let r = call(&src, "fail", json!({})).await;
    assert!(r.is_error);
    assert_eq!(r.output, "first\n\nsecond");
    src.close().await;
}

#[tokio::test]
async fn rpc_error_keeps_the_sdk_wording_and_resources_go_over_our_client() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let rt = src.resource_tools();
    let list = rt.iter().find(|t| t.name == "list_mcp_resources").unwrap();
    let r = list.call(json!({}), ToolContext::default()).await;
    assert_eq!(r.output, "leg: error: Mcp error: -32601: Method not found");
    let read = rt.iter().find(|t| t.name == "read_mcp_resource").unwrap();
    let r = read
        .call(
            json!({"server": "leg", "uri": "x://y"}),
            ToolContext::default(),
        )
        .await;
    assert!(r.is_error);
    assert_eq!(
        r.output,
        "read_mcp_resource: Mcp error: -32601: Method not found"
    );
    src.close().await;
}

#[tokio::test]
async fn idle_timeout_resets_on_matching_progress_only() {
    if !node() {
        return;
    }
    let src = load(300, None).await;
    // 6 × 150ms = 900ms > 300ms: alive only because each progress resets it
    let r = call(&src, "progress", json!({"n": 6, "gapMs": 150})).await;
    assert!(!r.is_error, "{}", r.output);
    assert!(
        r.output.starts_with("done after 6 with token "),
        "{}",
        r.output
    );

    let start = Instant::now();
    let r = call(
        &src,
        "progress",
        json!({"n": 6, "gapMs": 150, "wrongToken": true}),
    )
    .await;
    assert!(r.is_error);
    assert_eq!(r.output, "mcp idle timeout after 300ms without progress");
    assert!(start.elapsed() < Duration::from_millis(700));

    let r = call(&src, "silent", json!({})).await;
    assert_eq!(r.output, "mcp idle timeout after 300ms without progress");
    let log = server_log(&src).await;
    let reasons: Vec<&str> = log["cancelled"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c["reason"].as_str().unwrap())
        .collect();
    assert_eq!(reasons, ["request timeout", "request timeout"]);
    src.close().await;
}

#[tokio::test]
async fn progress_for_one_call_does_not_keep_another_alive() {
    if !node() {
        return;
    }
    let src = load(300, None).await;
    // A gets its own progress every 150ms for ~1.2s; B gets none and must
    // time out on its own 300ms clock, not ride on A's progress
    let a = call(&src, "progress", json!({"n": 8, "gapMs": 150}));
    let b = async {
        tokio::time::sleep(Duration::from_millis(50)).await;
        let start = Instant::now();
        let r = call(&src, "silent", json!({})).await;
        (r, start.elapsed())
    };
    let (ra, (rb, b_elapsed)) = tokio::join!(a, b);
    assert!(!ra.is_error, "{}", ra.output);
    assert!(ra.output.starts_with("done after 8 with token "));
    assert_eq!(rb.output, "mcp idle timeout after 300ms without progress");
    assert!(
        b_elapsed < Duration::from_millis(700),
        "B lived {b_elapsed:?}: another call's progress reset its timeout"
    );
    src.close().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn server_request_during_a_blocked_large_write_does_not_deadlock() {
    if !node() {
        return;
    }
    let src = load(20_000, None).await;
    // the server stops reading stdin, then (200ms later) pings us and floods
    // stdout; meanwhile a 2 MB request blocks holding the stdin lock. The
    // reader must keep draining stdout instead of waiting on that lock.
    let flood = call(&src, "flood", json!({"delayMs": 200, "mb": 4}));
    let big = async {
        tokio::time::sleep(Duration::from_millis(50)).await;
        call(&src, "echo", json!({"x": "y".repeat(2 << 20)})).await
    };
    let both = tokio::time::timeout(Duration::from_secs(10), async { tokio::join!(flood, big) });
    let (rf, rb) = both
        .await
        .expect("deadlock: client and server both stuck writing");
    assert_eq!(rf.output, "flooded");
    assert!(!rb.is_error, "{}", &rb.output[..rb.output.len().min(200)]);
    assert_eq!(rb.output.len(), (2 << 20) + 8);
    src.close().await;
}

#[tokio::test]
async fn caller_cancellation_sends_cancelled_and_returns_promptly() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let ctx = ToolContext::default();
    let cancel = ctx.cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        cancel.cancel();
    });
    let start = Instant::now();
    let r = call_ctx(&src, "silent", json!({}), ctx).await;
    assert!(start.elapsed() < Duration::from_secs(1));
    assert!(r.is_error);
    assert_eq!(r.output, "cancelled");
    let log = server_log(&src).await;
    assert_eq!(log["cancelled"][0]["reason"], "cancelled");
    assert!(log["cancelled"][0]["requestId"].is_number());
    src.close().await;
}

#[tokio::test]
async fn elicitation_round_trip_and_reverse_requests() {
    if !node() {
        return;
    }
    let wf: WaitForFn = Arc::new(|req: Request| {
        Box::pin(async move {
            assert_eq!(req.kind, "input");
            assert_eq!(req.prompt, "Your name?");
            assert!(req.data.as_ref().unwrap().contains_key("schema"));
            let mut d = Map::new();
            d.insert("name".into(), json!("Ada"));
            Ok(Answer {
                id: req.id,
                ok: true,
                data: Some(d),
                ..Default::default()
            })
        })
    });
    let src = load(5000, Some(wf)).await;
    let log = server_log(&src).await;
    assert_eq!(
        log["initialize"]["capabilities"],
        json!({"elicitation": {"form": {}, "url": {}}})
    );
    let r = call(&src, "ask", json!({})).await;
    let v: Value = serde_json::from_str(&r.output).unwrap();
    assert_eq!(v, json!({"action": "accept", "content": {"name": "Ada"}}));

    let r = call(&src, "reverse", json!({})).await;
    let v: Value = serde_json::from_str(&r.output).unwrap();
    assert_eq!(
        v,
        json!([
            {},
            {"roots": []},
            {"error": {"code": -32601, "message": "sampling/createMessage"}},
            {"error": {"code": -32601, "message": "foo/bar"}},
        ])
    );
    src.close().await;
}

#[tokio::test]
async fn elicitation_without_wait_for_declines() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let r = call(&src, "ask", json!({})).await;
    let v: Value = serde_json::from_str(&r.output).unwrap();
    assert_eq!(v, json!({"action": "decline"}));
    src.close().await;
}

#[tokio::test]
async fn server_exit_mid_call_fails_this_and_later_calls() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let start = Instant::now();
    let r = call(&src, "die", json!({})).await;
    assert!(start.elapsed() < Duration::from_secs(2));
    assert!(r.is_error);
    assert_eq!(r.output, "Transport closed");
    let r = call(&src, "echo", json!({"x": "1"})).await;
    assert_eq!(r.output, "Transport closed");
    src.close().await;
}

#[tokio::test]
async fn concurrent_calls_are_demultiplexed_by_id() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let futs = (0..50).map(|i| call(&src, "echo", json!({"x": i.to_string()})));
    let out = futures_util::future::join_all(futs).await;
    for (i, r) in out.iter().enumerate() {
        assert_eq!(r.output, format!(r#"{{"x":"{i}"}}"#));
    }
    src.close().await;
}

#[tokio::test]
async fn close_stops_the_child_promptly() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    let start = Instant::now();
    src.close().await;
    src.close().await; // idempotent
    assert!(start.elapsed() < Duration::from_secs(2));
    let r = call(&src, "echo", json!({"x": "1"})).await;
    assert_eq!(r.output, "Transport closed");
}

#[cfg(unix)]
#[tokio::test]
async fn close_kills_the_process_group_when_the_server_lingers() {
    if !node() {
        return;
    }
    let src = load(5000, None).await;
    // the grandchild keeps the server alive after stdin closes
    let pid = call(&src, "orphan", json!({})).await.output;
    let alive = |pid: &str| {
        std::process::Command::new("kill")
            .args(["-0", pid])
            .status()
            .is_ok_and(|s| s.success())
    };
    assert!(alive(&pid));
    let start = Instant::now();
    src.close().await;
    assert!(
        start.elapsed() < Duration::from_secs(5),
        "close took {:?}",
        start.elapsed()
    );
    assert!(!alive(&pid), "grandchild {pid} survived close");
}
