//! MCP elicitation bridge (SPEC §2 → §10). The pure mappings mirror golang
//! mcp_test.go (TestElicitationToRequest / TestAnswerToElicitResult); the live
//! tests drive tests/fixtures/mcp-elicit-server.mjs (skip without node).

use std::path::Path;
use std::sync::{Arc, Mutex};

use serde_json::{Map, Value, json};
use toolnexus::client::WaitForFn;
use toolnexus::mcp::{answer_to_elicit_result, elicitation_to_request};
use toolnexus::{
    Answer, BuiltinsConfig, McpConfigInput, Request, ToolContext, ToolkitOptions, create_toolkit,
};

#[test]
fn elicitation_to_request_maps_form_and_url() {
    let schema = json!({"type": "object", "properties": {"name": {"type": "string"}}});
    let req = elicitation_to_request(&json!({"message": "Your name?", "requestedSchema": schema}));
    assert_eq!(req.kind, "input");
    assert_eq!(req.prompt, "Your name?");
    assert!(req.id.starts_with("elc-"), "{}", req.id);
    assert_eq!(req.data.unwrap()["schema"], schema);
    assert!(req.url.is_empty());

    let ureq = elicitation_to_request(
        &json!({"mode": "url", "message": "Log in", "url": "https://x/auth"}),
    );
    assert_eq!(ureq.kind, "authorization");
    assert_eq!(ureq.url, "https://x/auth");
    assert_eq!(ureq.prompt, "Log in");
    assert!(ureq.data.is_none());
    assert_ne!(req.id, ureq.id);
}

#[test]
fn answer_to_elicit_result_maps_every_branch() {
    let mut name = Map::new();
    name.insert("name".into(), json!("Ada"));
    let cases = [
        (
            Answer {
                id: "1".into(),
                ok: true,
                data: Some(name),
                ..Default::default()
            },
            json!({"action": "accept", "content": {"name": "Ada"}}),
        ),
        (
            Answer {
                id: "1".into(),
                ok: true,
                ..Default::default()
            },
            json!({"action": "accept", "content": {}}),
        ),
        (
            Answer {
                id: "1".into(),
                ok: false,
                reason: "declined".into(),
                ..Default::default()
            },
            json!({"action": "decline"}),
        ),
        (
            Answer {
                id: "1".into(),
                ok: false,
                ..Default::default()
            },
            json!({"action": "cancel"}),
        ),
        (
            Answer {
                id: "1".into(),
                ok: false,
                reason: "expired".into(),
                ..Default::default()
            },
            json!({"action": "cancel"}),
        ),
    ];
    for (a, want) in cases {
        assert_eq!(answer_to_elicit_result(&a), want, "{a:?}");
    }
}

fn node() -> bool {
    std::process::Command::new("node")
        .arg("--version")
        .output()
        .is_ok()
}

fn cfg() -> McpConfigInput {
    let f = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/mcp-elicit-server.mjs");
    McpConfigInput::Json(json!({"mcpServers": {"elc": {"command": ["node", f.to_string_lossy()]}}}))
}

async fn call(wait_for: Option<WaitForFn>, tool: &str) -> Value {
    let tk = create_toolkit(ToolkitOptions {
        mcp_config: Some(cfg()),
        builtins: Some(BuiltinsConfig::off()),
        wait_for,
        ..Default::default()
    })
    .await
    .unwrap();
    let r = tk.execute(tool, json!({}), ToolContext::default()).await;
    tk.close().await;
    assert!(!r.is_error, "{r:?}");
    serde_json::from_str(&r.output).unwrap()
}

#[tokio::test]
async fn form_elicitation_bridges_onto_wait_for_inline() {
    if !node() {
        return;
    }
    let seen = Arc::new(Mutex::new(Vec::<Request>::new()));
    let s = seen.clone();
    let wf: WaitForFn = Arc::new(move |req: Request| {
        s.lock().unwrap().push(req.clone());
        Box::pin(async move {
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
    let out = call(Some(wf), "elc_ask_name").await;
    assert_eq!(
        out,
        json!({"advertised": true, "action": "accept", "content": {"name": "Ada"}})
    );
    let seen = seen.lock().unwrap();
    assert_eq!(seen.len(), 1);
    assert_eq!(seen[0].kind, "input");
    assert_eq!(seen[0].prompt, "Your name?");
    assert_eq!(
        seen[0].data.as_ref().unwrap()["schema"]["required"],
        json!(["name"])
    );
}

#[tokio::test]
async fn url_elicitation_maps_to_authorization_and_decline() {
    if !node() {
        return;
    }
    let seen = Arc::new(Mutex::new(Vec::<Request>::new()));
    let s = seen.clone();
    let wf: WaitForFn = Arc::new(move |req: Request| {
        s.lock().unwrap().push(req.clone());
        Box::pin(async move {
            Ok(Answer {
                id: req.id,
                ok: false,
                reason: "declined".into(),
                ..Default::default()
            })
        })
    });
    let out = call(Some(wf), "elc_login").await;
    assert_eq!(out["action"], "decline");
    let seen = seen.lock().unwrap();
    assert!(
        seen[0].kind == "authorization" && seen[0].url == "https://x/auth",
        "{:?}",
        seen[0]
    );
}

#[tokio::test]
async fn without_wait_for_the_capability_is_not_advertised() {
    if !node() {
        return;
    }
    let out = call(None, "elc_ask_name").await;
    assert_eq!(out, json!({"advertised": false, "action": "skipped"}));
}
