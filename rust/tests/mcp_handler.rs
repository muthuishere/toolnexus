//! The embeddable MCP handler (SPEC §7C.1, ADR 0039): mirrors
//! golang/mcphandler_test.go. Driven by the rmcp CLIENT over real streamable
//! HTTP (a local hyper server), plus raw HTTP for the 2025-06-18 transport rules
//! and a raw elicitation round trip.
#![cfg(feature = "serve")]

mod common_mcp_handler;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{Map, Value, json};
use toolnexus::mcp_handler::*;
use toolnexus::{Tool, ToolAnnotations, ToolResult, define_tool};

use common_mcp_handler::{serve, tk};

#[derive(Debug, Clone, PartialEq)]
struct User(String);

/// A tool that echoes `<name>:<caller>`, counting executions.
fn test_tool(name: &str, ran: Option<Arc<AtomicUsize>>) -> Tool {
    let n = name.to_owned();
    define_tool(
        name,
        format!("{name} tool"),
        Some(json!({"type": "object", "properties": {"id": {"type": "string"}}})),
        move |_: Value, ctx| {
            let n = n.clone();
            let ran = ran.clone();
            async move {
                if let Some(r) = ran {
                    r.fetch_add(1, Ordering::SeqCst);
                }
                let u = mcp_request_context(&ctx)
                    .and_then(|rc| rc.identity_as::<User>())
                    .map(|u| u.0.clone())
                    .unwrap_or_default();
                Ok::<_, String>(format!("{n}:{u}"))
            }
        },
    )
}

fn bearer_auth() -> AuthenticateFn {
    Arc::new(|rc| {
        let tok = rc
            .header("authorization")
            .trim_start_matches("Bearer ")
            .to_owned();
        Box::pin(async move {
            match tok.as_str() {
                "alice" | "bob" => Ok(Arc::new(User(tok)) as McpIdentity),
                _ => Err("bad token".into()),
            }
        })
    })
}

fn tools_fn(tools: Vec<Tool>) -> ListToolsFn {
    Arc::new(move |_| {
        let t = tools.clone();
        Box::pin(async move { Ok(t) })
    })
}

// ── raw HTTP helpers ──────────────────────────────────────────────────────

async fn raw_post(url: &str, sid: &str, body: &str, hdr: &[(&str, &str)]) -> reqwest::Response {
    let mut b = reqwest::Client::new()
        .post(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .body(body.to_owned());
    if !sid.is_empty() {
        b = b.header("Mcp-Session-Id", sid);
    }
    for (k, v) in hdr {
        b = b.header(*k, *v);
    }
    b.send().await.unwrap()
}

async fn raw_text(url: &str, sid: &str, body: &str, hdr: &[(&str, &str)]) -> String {
    raw_post(url, sid, body, hdr).await.text().await.unwrap()
}

const INIT: &str = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}"#;
const V0618: &[(&str, &str)] = &[("MCP-Protocol-Version", "2025-06-18")];

// ── rmcp client helpers ───────────────────────────────────────────────────

#[cfg(feature = "mcp-http")]
mod rmcp_client {
    use super::*;
    use rmcp::model::{
        CallToolRequestParams, CallToolResult, ClientCapabilities, ClientConfig, ContentBlock,
        ElicitRequestParams, ElicitResult, ElicitationAction, ElicitationCapability,
        FormElicitationCapability,
    };
    use rmcp::service::{RequestContext, RunningService};
    use rmcp::transport::StreamableHttpClientTransport;
    use rmcp::transport::streamable_http_client::StreamableHttpClientTransportConfig;
    use rmcp::{ClientHandler, RoleClient, ServiceExt};

    #[derive(Clone, Default)]
    pub struct H {
        pub elicit: Option<ElicitationAction>,
        pub asked: Arc<AtomicUsize>,
        pub notified: Arc<AtomicUsize>,
    }

    impl ClientHandler for H {
        fn get_info(&self) -> ClientConfig {
            let mut cfg = ClientConfig::default();
            if self.elicit.is_some() {
                let mut el = ElicitationCapability::new();
                el.form = Some(FormElicitationCapability::new());
                let mut caps: ClientCapabilities = cfg.capabilities.clone();
                caps.elicitation = Some(el);
                cfg.capabilities = caps;
            }
            cfg
        }
        async fn create_elicitation(
            &self,
            _request: ElicitRequestParams,
            _context: RequestContext<RoleClient>,
        ) -> Result<ElicitResult, rmcp::ErrorData> {
            self.asked.fetch_add(1, Ordering::SeqCst);
            let a = self.elicit.clone().unwrap_or(ElicitationAction::Decline);
            let accept = a == ElicitationAction::Accept;
            let mut r = ElicitResult::new(a);
            r.content = Some(json!({"confirm": accept}));
            Ok(r)
        }
        async fn on_tool_list_changed(
            &self,
            _context: rmcp::service::NotificationContext<RoleClient>,
        ) {
            self.notified.fetch_add(1, Ordering::SeqCst);
        }
    }

    pub type C = RunningService<RoleClient, H>;

    pub async fn dial(url: &str, token: &str, h: H) -> C {
        let mut cfg = StreamableHttpClientTransportConfig::with_uri(url.to_owned());
        if !token.is_empty() {
            cfg = cfg.auth_header(token);
        }
        h.serve(StreamableHttpClientTransport::from_config(cfg))
            .await
            .unwrap()
    }

    pub async fn call(c: &C, name: &str, args: Value) -> Result<CallToolResult, String> {
        let mut p = CallToolRequestParams::new(name.to_owned());
        if let Value::Object(m) = args {
            p = p.with_arguments(m);
        }
        c.call_tool(p).await.map_err(|e| e.to_string())
    }

    pub fn text(r: &CallToolResult) -> String {
        match r.content.first() {
            Some(ContentBlock::Text(t)) => t.text.clone(),
            _ => String::new(),
        }
    }

    pub async fn names(c: &C) -> Vec<String> {
        c.list_all_tools()
            .await
            .unwrap()
            .iter()
            .map(|t| t.name.to_string())
            .collect()
    }
}

// ── tests ─────────────────────────────────────────────────────────────────

#[tokio::test]
async fn auth_rejects_with_401_and_www_authenticate() {
    let h = tk().await.mcp_handler(McpHandlerConfig {
        authenticate: Some(bearer_auth()),
        www_authenticate: Some(Arc::new(|_, _| {
            r#"Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource""#
                .into()
        })),
        ..Default::default()
    });
    let url = serve(h).await;
    let r = raw_post(&url, "", r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#, &[]).await;
    assert_eq!(r.status(), 401);
    let w = r.headers()["www-authenticate"].to_str().unwrap();
    assert!(w.contains("resource_metadata="), "{w}");
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn per_identity_tools_and_not_found_never_executes() {
    use rmcp_client::*;
    let ran_admin = Arc::new(AtomicUsize::new(0));
    let ra = ran_admin.clone();
    let h = tk().await.mcp_handler(McpHandlerConfig {
        authenticate: Some(bearer_auth()),
        list_tools: Some(Arc::new(move |rc| {
            // runtime-synthesized: none of these are registered in the toolkit
            let mut tools = vec![test_tool("search", None)];
            if rc.identity_as::<User>().unwrap().0 == "alice" {
                tools.push(test_tool("admin_reset", Some(ra.clone())));
            }
            Box::pin(async move { Ok(tools) })
        })),
        ..Default::default()
    });
    let url = serve(h).await;
    let alice = dial(&url, "alice", H::default()).await;
    let bob = dial(&url, "bob", H::default()).await;
    assert_eq!(names(&alice).await, ["search", "admin_reset"]);
    assert_eq!(names(&bob).await, ["search"]);
    assert_eq!(
        text(&call(&bob, "search", json!({})).await.unwrap()),
        "search:bob"
    );
    let err = call(&bob, "admin_reset", json!({})).await.unwrap_err();
    assert!(err.contains("tool not found"), "{err}");
    assert_eq!(ran_admin.load(Ordering::SeqCst), 0);
    let r = call(&alice, "admin_reset", json!({})).await.unwrap();
    assert_eq!(text(&r), "admin_reset:alice");
}

fn gated_handler_cfg(fallback: Option<ToolResult>) -> (McpHandlerConfig, Arc<AtomicUsize>) {
    let ran = Arc::new(AtomicUsize::new(0));
    let del = test_tool("delete_record", Some(ran.clone())).with_annotations(ToolAnnotations {
        title: "Delete".into(),
        destructive_hint: Some(true),
        read_only_hint: Some(false),
        idempotent_hint: Some(true),
        open_world_hint: Some(false),
    });
    let cfg = McpHandlerConfig {
        authenticate: Some(bearer_auth()),
        list_tools: Some(tools_fn(vec![del, test_tool("read", None)])),
        before_call: Some(Arc::new(move |_, tool, args| {
            let fb = fallback.clone();
            Box::pin(async move {
                let a = args.to_string();
                if tool
                    .annotations
                    .as_ref()
                    .and_then(|a| a.destructive_hint)
                    .unwrap_or(false)
                {
                    if a.contains(r#""nonce-ok""#) {
                        return Ok(McpDecision::allow());
                    }
                    let mut d = McpDecision::confirm("Delete the record?");
                    d.result = fb;
                    return Ok(d);
                }
                if a.contains(r#""forbidden""#) {
                    return Ok(McpDecision::deny("not allowed"));
                }
                Ok(McpDecision::allow())
            })
        })),
        ..Default::default()
    };
    (cfg, ran)
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn confirm_via_elicitation() {
    use rmcp::model::ElicitationAction;
    use rmcp_client::*;
    let (cfg, ran) = gated_handler_cfg(None);
    let url = serve(tk().await.mcp_handler(cfg)).await;

    let yes = H {
        elicit: Some(ElicitationAction::Accept),
        ..Default::default()
    };
    let asked = yes.asked.clone();
    let c = dial(&url, "alice", yes).await;
    let r = call(&c, "delete_record", json!({"id": "1"})).await.unwrap();
    assert_ne!(r.is_error, Some(true));
    assert_eq!(text(&r), "delete_record:alice");
    assert_eq!(asked.load(Ordering::SeqCst), 1);
    assert_eq!(ran.load(Ordering::SeqCst), 1);

    let no = H {
        elicit: Some(ElicitationAction::Decline),
        ..Default::default()
    };
    let c2 = dial(&url, "alice", no).await;
    let r = call(&c2, "delete_record", json!({"id": "1"}))
        .await
        .unwrap();
    assert_eq!(r.is_error, Some(true));
    assert_eq!(text(&r), "declined");
    assert_eq!(ran.load(Ordering::SeqCst), 1);
}

/// The same confirm round trip over raw HTTP: the elicitation/create rides the
/// tools/call POST's SSE response; the answer is a separate POST.
#[tokio::test]
async fn confirm_via_raw_elicitation_round_trip() {
    use futures_util::StreamExt;
    let (cfg, ran) = gated_handler_cfg(None);
    let url = serve(tk().await.mcp_handler(cfg)).await;
    let auth = [("Authorization", "Bearer alice")];
    let init = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}}}}"#;
    let r = raw_post(&url, "", init, &auth).await;
    let sid = r.headers()["mcp-session-id"].to_str().unwrap().to_owned();

    let call = raw_post(
        &url,
        &sid,
        r#"{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"delete_record","arguments":{"id":"1"}}}"#,
        &auth,
    )
    .await;
    assert_eq!(call.headers()["content-type"], "text/event-stream");
    let mut stream = call.bytes_stream();
    let mut buf = String::new();
    let mut events: Vec<Value> = Vec::new();
    while events.len() < 2 {
        let chunk = tokio::time::timeout(Duration::from_secs(5), stream.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        buf.push_str(&String::from_utf8_lossy(&chunk));
        while let Some(i) = buf.find("\n\n") {
            let ev: String = buf.drain(..i + 2).collect();
            let data = ev
                .lines()
                .find_map(|l| l.strip_prefix("data: "))
                .unwrap()
                .to_owned();
            let v: Value = serde_json::from_str(&data).unwrap();
            if v["method"] == "elicitation/create" {
                assert_eq!(v["params"]["message"], "Delete the record?");
                assert_eq!(
                    v["params"]["requestedSchema"]["properties"]["confirm"]["type"],
                    "boolean"
                );
                let answer = json!({"jsonrpc": "2.0", "id": v["id"],
                    "result": {"action": "accept", "content": {"confirm": true}}});
                let a = raw_post(&url, &sid, &answer.to_string(), &auth).await;
                assert_eq!(a.status(), 202);
            }
            events.push(v);
        }
    }
    assert_eq!(events[1]["id"], 7);
    assert_eq!(
        events[1]["result"]["content"][0]["text"],
        "delete_record:alice"
    );
    assert_eq!(ran.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn confirm_timeout_is_a_shaped_failure() {
    let (mut cfg, ran) = gated_handler_cfg(None);
    cfg.confirm_timeout = Some(Duration::from_millis(50));
    let url = serve(tk().await.mcp_handler(cfg)).await;
    let auth = [("Authorization", "Bearer alice")];
    let init = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{"elicitation":{}}}}"#;
    let r = raw_post(&url, "", init, &auth).await;
    let sid = r.headers()["mcp-session-id"].to_str().unwrap().to_owned();
    let body = raw_text(
        &url,
        &sid,
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"delete_record","arguments":{"id":"1"}}}"#,
        &auth,
    )
    .await;
    assert!(body.contains("elicitation/create"), "{body}");
    assert!(body.contains("confirmation failed: "), "{body}");
    assert_eq!(ran.load(Ordering::SeqCst), 0);
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn confirm_without_elicitation() {
    use rmcp_client::*;
    // default: plain isError, tool not run
    let (cfg, ran) = gated_handler_cfg(None);
    let url = serve(tk().await.mcp_handler(cfg)).await;
    let c = dial(&url, "alice", H::default()).await;
    let r = call(&c, "delete_record", json!({"id": "1"})).await.unwrap();
    assert_eq!(r.is_error, Some(true));
    assert_eq!(text(&r), "confirmation required: Delete the record?");
    assert_eq!(ran.load(Ordering::SeqCst), 0);
    // host-shaped result (nonce re-call), then the re-call is allowed by the host
    let (cfg2, ran2) =
        gated_handler_cfg(Some(ToolResult::ok(r#"re-call with {"nonce":"nonce-ok"}"#)));
    let url2 = serve(tk().await.mcp_handler(cfg2)).await;
    let c2 = dial(&url2, "alice", H::default()).await;
    let r = call(&c2, "delete_record", json!({"id": "1"}))
        .await
        .unwrap();
    assert_ne!(r.is_error, Some(true));
    assert!(text(&r).contains("nonce-ok"));
    assert_eq!(ran2.load(Ordering::SeqCst), 0);
    let r = call(
        &c2,
        "delete_record",
        json!({"id": "1", "nonce": "nonce-ok"}),
    )
    .await
    .unwrap();
    assert_eq!(text(&r), "delete_record:alice");
    assert_eq!(ran2.load(Ordering::SeqCst), 1);
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn deny_and_annotations() {
    use rmcp_client::*;
    let (cfg, _) = gated_handler_cfg(None);
    let url = serve(tk().await.mcp_handler(cfg)).await;
    let c = dial(&url, "alice", H::default()).await;
    let r = call(&c, "read", json!({"id": "forbidden"})).await.unwrap();
    assert_eq!(r.is_error, Some(true));
    assert_eq!(text(&r), "not allowed");
    let list = c.list_all_tools().await.unwrap();
    let a = list[0].annotations.as_ref().unwrap();
    assert_eq!(a.title.as_deref(), Some("Delete"));
    assert_eq!(a.destructive_hint, Some(true));
    assert_eq!(a.read_only_hint, Some(false));
    assert_eq!(a.idempotent_hint, Some(true));
    assert_eq!(a.open_world_hint, Some(false));
}

#[tokio::test]
async fn on_call_event() {
    let evs: Arc<Mutex<Vec<McpCallEvent>>> = Arc::default();
    let e2 = evs.clone();
    let boom = define_tool("boom", "", None, |_: Value, _| async {
        tokio::time::sleep(Duration::from_millis(5)).await;
        Err::<String, _>("kaput")
    });
    let h = tk().await.mcp_handler(McpHandlerConfig {
        authenticate: Some(bearer_auth()),
        list_tools: Some(tools_fn(vec![boom])),
        on_call: Some(Arc::new(move |ev| {
            e2.lock().unwrap().push(ev);
            panic!("isolated");
        })),
        ..Default::default()
    });
    let url = serve(h).await;
    let auth = [("Authorization", "Bearer bob")];
    let r = raw_post(&url, "", INIT, &auth).await;
    let sid = r.headers()["mcp-session-id"].to_str().unwrap().to_owned();
    let b = raw_text(
        &url,
        &sid,
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"boom"}}"#,
        &auth,
    )
    .await;
    let v: Value = serde_json::from_str(&b).unwrap();
    assert_eq!(v["result"]["isError"], true);
    assert_eq!(v["result"]["content"][0]["text"], "kaput");
    let evs = evs.lock().unwrap();
    assert_eq!(evs.len(), 1);
    let ev = &evs[0];
    assert_eq!(ev.name, "boom");
    assert!(ev.is_error);
    assert_eq!(ev.err.as_deref(), Some("kaput"));
    assert!(ev.result_bytes > 0);
    assert!(ev.ms >= 5, "{}", ev.ms);
    assert_eq!(ev.identity_as::<User>(), Some(&User("bob".into())));
    assert_eq!(ev.session_id, sid);
    assert_eq!(ev.decision, McpDecisionKind::Allow);
}

#[tokio::test]
async fn list_changed_notification() {
    use futures_util::StreamExt;
    let h = tk().await.mcp_handler(McpHandlerConfig {
        list_tools: Some(tools_fn(vec![test_tool("a", None)])),
        ..Default::default()
    });
    let url = serve(h.clone()).await;
    let r = raw_post(&url, "", INIT, &[]).await;
    let sid = r.headers()["mcp-session-id"].to_str().unwrap().to_owned();
    let get = reqwest::Client::new()
        .get(&url)
        .header("Accept", "text/event-stream")
        .header("Mcp-Session-Id", &sid)
        .send()
        .await
        .unwrap();
    assert_eq!(get.status(), 200);
    assert_eq!(get.headers()["content-type"], "text/event-stream");
    let mut s = get.bytes_stream();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        h.notify_tools_changed();
        if let Ok(Some(Ok(chunk))) = tokio::time::timeout(Duration::from_millis(50), s.next()).await
        {
            let t = String::from_utf8_lossy(&chunk).to_string();
            assert!(t.contains("notifications/tools/list_changed"), "{t}");
            assert!(t.starts_with("event: message\ndata: "), "{t}");
            return;
        }
        assert!(tokio::time::Instant::now() < deadline, "no list_changed");
    }
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn list_changed_reaches_the_rmcp_client() {
    use rmcp_client::*;
    let h = tk().await.mcp_handler(McpHandlerConfig {
        list_tools: Some(tools_fn(vec![test_tool("a", None)])),
        ..Default::default()
    });
    let url = serve(h.clone()).await;
    let ch = H::default();
    let n = ch.notified.clone();
    let _c = dial(&url, "", ch).await;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while n.load(Ordering::SeqCst) == 0 {
        h.notify_tools_changed();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(tokio::time::Instant::now() < deadline, "no list_changed");
    }
}

fn info_cfg() -> McpHandlerConfig {
    McpHandlerConfig {
        authenticate: Some(bearer_auth()),
        server_info: Some(Arc::new(|rc| McpServerInfo {
            name: format!(
                "srv-{}-{}",
                &rc.host()[..9],
                rc.identity_as::<User>().unwrap().0
            ),
            version: "9.9.9".into(),
            instructions: "be careful".into(),
            ..Default::default()
        })),
        list_resources: Some(Arc::new(|_| {
            Box::pin(async {
                Ok(vec![McpResource {
                    uri: "docs://api".into(),
                    name: "api".into(),
                    mime_type: "text/markdown".into(),
                    ..Default::default()
                }])
            })
        })),
        read_resource: Some(Arc::new(|rc, uri| {
            let who = rc.identity_as::<User>().unwrap().0.clone();
            Box::pin(async move {
                Ok(vec![McpResourceContent {
                    uri,
                    mime_type: "text/markdown".into(),
                    text: format!("# API for {who}"),
                    ..Default::default()
                }])
            })
        })),
        list_prompts: Some(Arc::new(|_| {
            Box::pin(async {
                Ok(vec![McpPrompt {
                    name: "greet".into(),
                    arguments: vec![McpPromptArgument {
                        name: "who".into(),
                        required: true,
                        ..Default::default()
                    }],
                    ..Default::default()
                }])
            })
        })),
        get_prompt: Some(Arc::new(|_, _, args| {
            Box::pin(async move {
                Ok(McpPromptResult {
                    messages: vec![McpPromptMessage {
                        role: "user".into(),
                        text: format!("hello {}", args.get("who").cloned().unwrap_or_default()),
                    }],
                    ..Default::default()
                })
            })
        })),
        ..Default::default()
    }
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn server_info_resources_prompts() {
    use rmcp::model::{
        ContentBlock, GetPromptRequestParams, ReadResourceRequestParams, ResourceContents,
    };
    use rmcp_client::*;
    let url = serve(tk().await.mcp_handler(info_cfg())).await;
    let c = dial(&url, "alice", H::default()).await;
    let info = c.peer_info().unwrap();
    assert_eq!(
        info.server_info.as_ref().unwrap().name,
        "srv-127.0.0.1-alice"
    );
    assert_eq!(info.server_info.as_ref().unwrap().version, "9.9.9");
    assert_eq!(info.instructions.as_deref(), Some("be careful"));
    assert_eq!(info.protocol_version.to_string(), "2025-06-18");
    assert!(info.capabilities.resources.is_some());
    assert!(info.capabilities.prompts.is_some());
    let rl = c.list_all_resources().await.unwrap();
    assert_eq!(rl.len(), 1);
    assert_eq!(rl[0].uri, "docs://api");
    let rd = c
        .read_resource(ReadResourceRequestParams::new("docs://api"))
        .await
        .unwrap();
    match &rd.contents[0] {
        ResourceContents::TextResourceContents { text, .. } => assert_eq!(text, "# API for alice"),
        other => panic!("{other:?}"),
    }
    let mut args = Map::new();
    args.insert("who".into(), json!("world"));
    let pr = c
        .get_prompt(GetPromptRequestParams::new("greet").with_arguments(args))
        .await
        .unwrap();
    match &pr.messages[0].content {
        ContentBlock::Text(t) => assert_eq!(t.text, "hello world"),
        other => panic!("{other:?}"),
    }
    let pl = c.list_all_prompts().await.unwrap();
    let a = &pl[0].arguments.as_ref().unwrap()[0];
    assert_eq!(a.name, "who");
    assert_eq!(a.required, Some(true));
}

/// The same hooks over raw HTTP (also runs without the `mcp-http` feature).
#[tokio::test]
async fn server_info_resources_prompts_raw() {
    let url = serve(tk().await.mcp_handler(info_cfg())).await;
    let auth = [("Authorization", "Bearer alice")];
    let r = raw_post(&url, "", INIT, &auth).await;
    let sid = r.headers()["mcp-session-id"].to_str().unwrap().to_owned();
    let init: Value = r.json().await.unwrap();
    assert_eq!(
        init["result"]["serverInfo"],
        json!({"name": "srv-127.0.0.1-alice", "version": "9.9.9"})
    );
    assert_eq!(init["result"]["instructions"], "be careful");
    assert_eq!(
        init["result"]["capabilities"],
        json!({"tools": {"listChanged": true}, "resources": {}, "prompts": {}})
    );
    let hdr = [auth[0], V0618[0]];
    let get = |body: &'static str| {
        let (url, sid) = (url.clone(), sid.clone());
        async move { serde_json::from_str::<Value>(&raw_text(&url, &sid, body, &hdr).await).unwrap() }
    };
    assert_eq!(
        get(r#"{"jsonrpc":"2.0","id":2,"method":"resources/list"}"#).await["result"],
        json!({"resources": [{"uri": "docs://api", "name": "api", "mimeType": "text/markdown"}]})
    );
    assert_eq!(
        get(r#"{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"docs://api"}}"#)
            .await["result"],
        json!({"contents": [{"uri": "docs://api", "mimeType": "text/markdown", "text": "# API for alice"}]})
    );
    assert_eq!(
        get(r#"{"jsonrpc":"2.0","id":4,"method":"prompts/list"}"#).await["result"],
        json!({"prompts": [{"name": "greet", "arguments": [{"name": "who", "required": true}]}]})
    );
    assert_eq!(
        get(r#"{"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"greet","arguments":{"who":"world"}}}"#)
            .await["result"],
        json!({"messages": [{"role": "user", "content": {"type": "text", "text": "hello world"}}]})
    );
}

#[tokio::test]
async fn protocol_conformance() {
    let h = tk().await.mcp_handler(McpHandlerConfig {
        list_tools: Some(tools_fn(vec![])),
        ..Default::default()
    });
    let url = serve(h).await;
    // initialize negotiates 2025-06-18 and issues a session id
    let r = raw_post(&url, "", INIT, &[]).await;
    assert_eq!(r.status(), 200);
    let sid = r.headers()["mcp-session-id"].to_str().unwrap().to_owned();
    assert!(!sid.is_empty());
    let b = r.text().await.unwrap();
    assert!(b.contains(r#""protocolVersion":"2025-06-18""#), "{b}");
    assert!(b.contains(r#""listChanged":true"#), "{b}");
    // unknown version ⇒ our latest
    let b = raw_text(
        &url,
        "",
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"#,
        &[],
    )
    .await;
    assert!(b.contains(r#""protocolVersion":"2025-06-18""#), "{b}");
    // notification ⇒ 202
    let r = raw_post(
        &url,
        &sid,
        r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
        V0618,
    )
    .await;
    assert_eq!(r.status(), 202);
    // missing session ⇒ 400, unknown ⇒ 404
    let ping = r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#;
    assert_eq!(raw_post(&url, "", ping, V0618).await.status(), 400);
    assert_eq!(raw_post(&url, "nope", ping, V0618).await.status(), 404);
    // unsupported protocol header ⇒ 400
    let r = raw_post(&url, &sid, ping, &[("MCP-Protocol-Version", "2099-01-01")]).await;
    assert_eq!(r.status(), 400);
    assert_eq!(
        r.text().await.unwrap(),
        "Bad Request: unsupported MCP-Protocol-Version\n"
    );
    // batch rejected
    let b = raw_text(
        &url,
        &sid,
        r#"[{"jsonrpc":"2.0","id":3,"method":"ping"}]"#,
        V0618,
    )
    .await;
    assert!(b.contains("-32600"), "{b}");
    // ping / unknown method / resources not advertised
    let b = raw_text(
        &url,
        &sid,
        r#"{"jsonrpc":"2.0","id":4,"method":"ping"}"#,
        V0618,
    )
    .await;
    assert!(b.contains(r#""result":{}"#), "{b}");
    let b = raw_text(
        &url,
        &sid,
        r#"{"jsonrpc":"2.0","id":5,"method":"resources/list"}"#,
        V0618,
    )
    .await;
    assert!(b.contains("-32601"), "{b}");
    // parse error
    let b = raw_text(
        &url,
        &sid,
        r#"{"jsonrpc":"1.0","id":5,"method":"ping"}"#,
        V0618,
    )
    .await;
    assert!(b.contains("-32700"), "{b}");
    let c = reqwest::Client::new();
    // wrong content type ⇒ 415; GET without SSE accept ⇒ 405; PUT ⇒ 405
    let r = c
        .post(&url)
        .header("Content-Type", "text/plain")
        .body("{}")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 415);
    assert_eq!(c.get(&url).send().await.unwrap().status(), 405);
    let r = c.put(&url).send().await.unwrap();
    assert_eq!(r.status(), 405);
    assert_eq!(r.headers()["allow"], "GET, POST, DELETE");
    // DELETE ends the session; it is then 404
    let r = c
        .delete(&url)
        .header("Mcp-Session-Id", &sid)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    assert_eq!(raw_post(&url, &sid, ping, V0618).await.status(), 404);
}

#[tokio::test]
async fn max_body_bytes_is_413() {
    let h = tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        max_body_bytes: 64,
        ..Default::default()
    });
    let url = serve(h).await;
    let big = format!(
        r#"{{"jsonrpc":"2.0","id":1,"method":"ping","params":{{"pad":"{}"}}}}"#,
        "x".repeat(200)
    );
    assert_eq!(raw_post(&url, "", &big, &[]).await.status(), 413);
    let b = raw_text(&url, "", r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#, &[]).await;
    assert!(b.contains(r#""result":{}"#), "{b}");
}

#[derive(Default)]
struct TestIds {
    gen_: AtomicUsize,
    val: AtomicUsize,
    term: AtomicUsize,
}

impl McpSessionIds for TestIds {
    fn generate(&self, _: Arc<McpRequestContext>) -> BoxFuture<Result<String, String>> {
        self.gen_.fetch_add(1, Ordering::SeqCst);
        Box::pin(async { Ok("host-session-1".to_owned()) })
    }
    fn validate(&self, _: Arc<McpRequestContext>, id: String) -> BoxFuture<Result<bool, String>> {
        self.val.fetch_add(1, Ordering::SeqCst);
        Box::pin(async move { Ok(id == "host-session-1") })
    }
    fn terminate(&self, _: Arc<McpRequestContext>, _: String) -> BoxFuture<Result<(), String>> {
        self.term.fetch_add(1, Ordering::SeqCst);
        Box::pin(async { Ok(()) })
    }
}

#[tokio::test]
async fn custom_session_ids_and_stateless() {
    let ids = Arc::new(TestIds::default());
    let h = tk().await.mcp_handler(McpHandlerConfig {
        session_ids: Some(ids.clone()),
        list_tools: Some(tools_fn(vec![test_tool("a", None)])),
        ..Default::default()
    });
    let url = serve(h).await;
    let r = raw_post(&url, "", INIT, &[]).await;
    assert_eq!(r.headers()["mcp-session-id"], "host-session-1");
    let b = raw_text(
        &url,
        "host-session-1",
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"a"}}"#,
        V0618,
    )
    .await;
    assert!(b.contains(r#""text":"a:""#), "{b}");
    assert_eq!(ids.gen_.load(Ordering::SeqCst), 1);
    assert!(ids.val.load(Ordering::SeqCst) > 0);
    let r = reqwest::Client::new()
        .delete(&url)
        .header("Mcp-Session-Id", "host-session-1")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    assert_eq!(ids.term.load(Ordering::SeqCst), 1);

    let st = tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        list_tools: Some(tools_fn(vec![test_tool("a", None)])),
        ..Default::default()
    });
    let url2 = serve(st).await;
    let r = raw_post(
        &url2,
        "",
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"#,
        &[],
    )
    .await;
    assert!(r.headers().get("mcp-session-id").is_none());
    let b = raw_text(
        &url2,
        "",
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"a"}}"#,
        &[],
    )
    .await;
    assert!(b.contains("a:"), "{b}");
    // DELETE is meaningless when stateless
    let r = reqwest::Client::new().delete(&url2).send().await.unwrap();
    assert_eq!(r.status(), 405);
}

#[tokio::test]
async fn defaults_to_toolkit_tools() {
    let tk = toolnexus::create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![test_tool("registered", None)],
        ..Default::default()
    })
    .await
    .unwrap();
    let url = serve(tk.mcp_handler(McpHandlerConfig {
        stateless: true,
        ..Default::default()
    }))
    .await;
    let b = raw_text(
        &url,
        "",
        r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#,
        &[],
    )
    .await;
    assert!(b.contains(r#""name":"registered""#), "{b}");
}

#[tokio::test]
async fn structured_content_and_output_schema() {
    let st = define_tool(
        "stats",
        "",
        Some(json!({"type": "object"})),
        |_: Value, _| async { Ok::<_, String>(String::new()) },
    )
    .with_output_schema(json!({"type": "object", "properties": {"count": {"type": "integer"}}}));
    let mut st = st;
    st.execute = Arc::new(|_, _| {
        Box::pin(async { ToolResult::default().with_structured(json!({"count": 3})) })
    });
    let plain = test_tool("plain", None);
    let url = serve(tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        list_tools: Some(tools_fn(vec![st, plain])),
        ..Default::default()
    }))
    .await;
    let lt: Value = serde_json::from_str(
        &raw_text(
            &url,
            "",
            r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#,
            &[],
        )
        .await,
    )
    .unwrap();
    assert_eq!(lt["result"]["tools"][0]["outputSchema"]["type"], "object");
    // absent ⇒ no outputSchema
    assert!(lt["result"]["tools"][1].get("outputSchema").is_none());
    let r: Value = serde_json::from_str(
        &raw_text(
            &url,
            "",
            r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stats"}}"#,
            &[],
        )
        .await,
    )
    .unwrap();
    assert_eq!(r["result"]["structuredContent"], json!({"count": 3}));
    assert_eq!(r["result"]["content"][0]["text"], r#"{"count":3}"#);
    // absent ⇒ no structuredContent / _meta
    let p: Value = serde_json::from_str(
        &raw_text(
            &url,
            "",
            r#"{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"plain"}}"#,
            &[],
        )
        .await,
    )
    .unwrap();
    assert_eq!(
        p["result"],
        json!({"content": [{"type": "text", "text": "plain:"}]})
    );
}

#[tokio::test]
async fn meta_passthrough() {
    let seen: Arc<Mutex<Option<Map<String, Value>>>> = Arc::default();
    let seen_hook: Arc<Mutex<Option<Map<String, Value>>>> = Arc::default();
    let (s1, s2) = (seen.clone(), seen_hook.clone());
    let mut tool = test_tool("m", None);
    tool.execute = Arc::new(move |_, ctx| {
        *s1.lock().unwrap() = mcp_request_context(&ctx).and_then(|rc| rc.meta().cloned());
        Box::pin(async {
            let mut m = Map::new();
            m.insert("usage".into(), json!({"units": 2}));
            ToolResult::ok("ok").with_meta(m)
        })
    });
    let url = serve(tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        list_tools: Some(tools_fn(vec![tool])),
        before_call: Some(Arc::new(move |rc, _, _| {
            *s2.lock().unwrap() = rc.meta().cloned();
            Box::pin(async { Ok(McpDecision::allow()) })
        })),
        ..Default::default()
    }))
    .await;
    let b = raw_text(
        &url,
        "",
        r#"{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"m","_meta":{"clientCaps":{"elicitation":{}}}}}"#,
        &[],
    )
    .await;
    assert!(b.contains(r#""_meta":{"usage":{"units":2}}"#), "{b}");
    assert!(
        seen.lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .contains_key("clientCaps")
    );
    assert!(
        seen_hook
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .contains_key("clientCaps")
    );
}

#[tokio::test]
async fn protocol_versions_per_handler() {
    let init = |v: &str| {
        format!(
            r#"{{"jsonrpc":"2.0","id":1,"method":"initialize","params":{{"protocolVersion":"{v}"}}}}"#
        )
    };
    let def = serve(tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        ..Default::default()
    }))
    .await;
    let newer = serve(tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        protocol_versions: vec![
            "2026-07-28".into(),
            "2025-11-25".into(),
            "2025-06-18".into(),
            "2025-03-26".into(),
        ],
        list_tools: Some(tools_fn(vec![test_tool("a", None)])),
        ..Default::default()
    }))
    .await;
    let ping = r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#;
    let b = raw_text(&def, "", &init("2025-11-25"), &[]).await;
    assert!(b.contains(r#""protocolVersion":"2025-06-18""#), "{b}");
    let r = raw_post(&def, "", ping, &[("MCP-Protocol-Version", "2025-11-25")]).await;
    assert_eq!(r.status(), 400);
    let b = raw_text(&newer, "", &init("2025-11-25"), &[]).await;
    assert!(b.contains(r#""protocolVersion":"2025-11-25""#), "{b}");
    let b = raw_text(&newer, "", &init("2026-07-28"), &[]).await;
    assert!(b.contains(r#""protocolVersion":"2025-11-25""#), "{b}");
    let r = raw_post(&newer, "", ping, &[("MCP-Protocol-Version", "2025-03-26")]).await;
    assert_eq!(r.status(), 400);
    let bad =
        r#"{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"a","arguments":[1]}}"#;
    let b = raw_text(&newer, "", bad, &[("MCP-Protocol-Version", "2025-11-25")]).await;
    assert!(
        b.contains(r#""isError":true"#) && !b.contains("-32602"),
        "{b}"
    );
    let b = raw_text(&newer, "", bad, &[("MCP-Protocol-Version", "2025-06-18")]).await;
    assert!(b.contains("-32602"), "{b}");
}

#[tokio::test]
async fn stateless_session_id_and_title() {
    let url = serve(tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        stateless_session_id: "stateless".into(),
        title: "Records API".into(),
        ..Default::default()
    }))
    .await;
    let r = raw_post(
        &url,
        "",
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"#,
        &[],
    )
    .await;
    assert_eq!(r.headers()["mcp-session-id"], "stateless");
    let b = r.text().await.unwrap();
    assert!(b.contains(r#""title":"Records API""#), "{b}");
    // never validated: any or no id works
    let b = raw_text(
        &url,
        "whatever",
        r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#,
        &[],
    )
    .await;
    assert!(b.contains(r#""result":{}"#), "{b}");
    // title omitted when unset
    let u2 = serve(tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        ..Default::default()
    }))
    .await;
    let b = raw_text(
        &u2,
        "",
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#,
        &[],
    )
    .await;
    assert!(!b.contains(r#""title""#), "{b}");
}

#[tokio::test]
async fn mounts_as_a_hyper_service() {
    use http_body_util::{BodyExt, Full};
    use hyper::service::Service;
    let h = tk().await.mcp_handler(McpHandlerConfig {
        stateless: true,
        ..Default::default()
    });
    let req = hyper::Request::post("/mcp")
        .header("content-type", "application/json")
        .body(Full::new(hyper::body::Bytes::from_static(
            br#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#,
        )))
        .unwrap();
    let resp = h.call(req).await.unwrap();
    assert_eq!(resp.status(), 200);
    let body = resp.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(&body[..], b"{\"id\":1,\"jsonrpc\":\"2.0\",\"result\":{}}\n");
}
