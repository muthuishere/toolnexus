//! SPEC §7C.1 cross-port conformance: replays ../conformance/mcp_handler/cases.json
//! (harness contract in conformance/mcp_handler/README.md). Mirrors
//! golang/mcphandler_conformance_test.go (incl. the §7C.2 `mcp2026_*` cases).
#![cfg(feature = "serve")]

mod common_mcp_handler;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use serde_json::{Map, Value, json};
use toolnexus::mcp_handler::*;
use toolnexus::{
    Request as Pending, Tool, ToolAnnotations, ToolHints, ToolResult, ToolSource, pending,
};

use common_mcp_handler::{serve, serve_metadata, tk};

/// The identity name: a string identity verbatim, a principal's subject, else "".
fn identity_name(id: Option<&McpIdentity>) -> String {
    let Some(id) = id else {
        return String::new();
    };
    if let Some(s) = id.downcast_ref::<String>() {
        return s.clone();
    }
    id.downcast_ref::<McpPrincipal>()
        .map(|p| p.subject.clone())
        .unwrap_or_default()
}

/// README `mcp2026`: stamp `params._meta[protocolVersion]` unless present.
fn stamp_2026(body: &Value) -> Value {
    let mut b = body.clone();
    let p = b
        .as_object_mut()
        .unwrap()
        .entry("params")
        .or_insert(json!({}));
    let meta = p
        .as_object_mut()
        .unwrap()
        .entry("_meta")
        .or_insert(json!({}));
    meta.as_object_mut()
        .unwrap()
        .entry("io.modelcontextprotocol/protocolVersion")
        .or_insert(json!(MCP_PROTOCOL_2026));
    b
}

fn to_result(v: &Value) -> ToolResult {
    ToolResult {
        output: v["output"].as_str().unwrap_or("").into(),
        is_error: v["isError"].as_bool().unwrap_or(false),
        structured: v.get("structured").filter(|s| !s.is_null()).cloned(),
        meta: v.get("meta").and_then(Value::as_object).cloned(),
        ..Default::default()
    }
}

fn path<'a>(mut v: &'a Value, p: &str) -> Option<&'a Value> {
    for seg in p.split('.') {
        v = match v {
            Value::Object(m) => m.get(seg)?,
            Value::Array(a) => a.get(seg.parse::<usize>().ok()?)?,
            _ => return None,
        };
    }
    Some(v)
}

#[tokio::test]
async fn conformance_cases() {
    let file = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../conformance/mcp_handler/cases.json");
    let doc: Value = serde_json::from_str(&std::fs::read_to_string(file).unwrap()).unwrap();
    let fixture = &doc["fixture"];
    let ftools: Vec<Value> = fixture["tools"].as_array().unwrap().clone();
    let tokens: HashMap<String, String> =
        serde_json::from_value(fixture["auth"]["tokens"].clone()).unwrap();
    let www = fixture["auth"]["wwwAuthenticate"]
        .as_str()
        .unwrap()
        .to_owned();
    let client = reqwest::Client::new();

    for sc in doc["scenarios"].as_array().unwrap() {
        let name = sc["name"].as_str().unwrap();
        let executed: Arc<Mutex<Vec<String>>> = Arc::default();
        let on_calls: Arc<Mutex<Vec<Value>>> = Arc::default();

        let mut tools = Vec::new();
        for ft in &ftools {
            let ft = ft.clone();
            let ex = executed.clone();
            let mut t = toolnexus::define_tool(
                ft["name"].as_str().unwrap(),
                ft["description"].as_str().unwrap(),
                Some(ft["inputSchema"].clone()),
                |_: Value, _| async { Ok::<_, String>(String::new()) },
            );
            t.source = ToolSource::Native;
            t.output_schema = ft.get("outputSchema").cloned();
            if let Some(a) = ft.get("annotations") {
                t.annotations = Some(serde_json::from_value::<ToolAnnotations>(a.clone()).unwrap());
            }
            if let Some(h) = ft.get("hints") {
                t.hints = Some(serde_json::from_value::<ToolHints>(h.clone()).unwrap());
            }
            t.execute = Arc::new(move |args, ctx| {
                let ft = ft.clone();
                let ex = ex.clone();
                Box::pin(async move {
                    ex.lock().unwrap().push(ft["name"].as_str().unwrap().into());
                    match ft["behavior"].as_str().unwrap() {
                        "echo" => ToolResult::ok(args["text"].as_str().unwrap_or("")),
                        "ask" => match &ctx.answer {
                            Some(a) if a.ok => {
                                let n = a
                                    .data
                                    .as_ref()
                                    .and_then(|d| d.get("name"))
                                    .and_then(Value::as_str)
                                    .unwrap_or("");
                                ToolResult::ok(format!("Hello, {n}!"))
                            }
                            _ => pending(Pending {
                                id: "name".into(),
                                kind: "input".into(),
                                prompt: "What is your name?".into(),
                                data: json!({"schema": {"type": "object",
                                    "properties": {"name": {"type": "string"}}, "required": ["name"]}})
                                .as_object()
                                .cloned(),
                                ..Default::default()
                            }),
                        },
                        "whoami" => {
                            let rc = mcp_request_context(&ctx).unwrap();
                            let id = identity_name(rc.identity());
                            let tr = rc
                                .meta()
                                .and_then(|m| m.get("trace"))
                                .and_then(Value::as_str)
                                .unwrap_or("")
                                .to_owned();
                            ToolResult::ok(format!("{id}|{tr}"))
                        }
                        "error" => ToolResult::error(ft["error"].as_str().unwrap()),
                        _ => to_result(&ft["result"]),
                    }
                })
            });
            tools.push(t);
        }
        let by_name: HashMap<String, Value> = ftools
            .iter()
            .map(|t| (t["name"].as_str().unwrap().to_owned(), t.clone()))
            .collect();

        let c = &sc["config"];
        let s = |k: &str| c[k].as_str().unwrap_or("").to_owned();
        let tools = Arc::new(tools);
        let bn = Arc::new(by_name);
        let (t2, bn2, bn3) = (tools.clone(), bn.clone(), bn.clone());
        let oc = on_calls.clone();
        let mut cfg = McpHandlerConfig {
            name: s("name"),
            title: s("title"),
            version: s("version"),
            instructions: s("instructions"),
            stateless: c["stateless"].as_bool().unwrap_or(false),
            stateless_session_id: s("statelessSessionId"),
            protocol_versions: serde_json::from_value(
                c.get("protocolVersions").cloned().unwrap_or(json!([])),
            )
            .unwrap(),
            list_tools: Some(Arc::new(move |rc| {
                let id = identity_name(rc.identity());
                let out: Vec<Tool> = t2
                    .iter()
                    .filter(|t| match bn2[&t.name].get("visibleTo") {
                        Some(Value::Array(v)) => v.iter().any(|x| x == &json!(id)),
                        _ => true,
                    })
                    .cloned()
                    .collect();
                Box::pin(async move { Ok(out) })
            })),
            before_call: Some(Arc::new(move |_, t, _| {
                let d = match bn3[&t.name].get("decision") {
                    None => McpDecision::allow(),
                    Some(d) => McpDecision {
                        kind: match d["kind"].as_str().unwrap() {
                            "deny" => McpDecisionKind::Deny,
                            "confirm" => McpDecisionKind::Confirm,
                            _ => McpDecisionKind::Allow,
                        },
                        message: d["message"].as_str().unwrap_or("").into(),
                        result: d.get("result").map(to_result),
                    },
                };
                Box::pin(async move { Ok(d) })
            })),
            on_call: Some(Arc::new(move |ev| {
                oc.lock()
                    .unwrap()
                    .push(json!({"name": ev.name, "isError": ev.is_error,
                    "decision": ev.decision.as_str()}));
            })),
            ..Default::default()
        };
        if c["auth"].as_bool().unwrap_or(false) {
            let tokens = tokens.clone();
            cfg.authenticate = Some(Arc::new(move |rc| {
                let r = tokens
                    .get(rc.header("authorization"))
                    .map(|id| Arc::new(id.clone()) as McpIdentity)
                    .ok_or_else(|| "unauthorized".into());
                Box::pin(async move { r })
            }));
            let www = www.clone();
            cfg.www_authenticate = Some(Arc::new(move |_, _| www.clone()));
        }
        if c["skills"].as_bool().unwrap_or(false) {
            let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/skills");
            cfg.list_skills = Some(Arc::new(move |_| {
                let r = load_mcp_skills(&dir).map_err(|e| e.to_string());
                Box::pin(async move { r })
            }));
        }
        let mut meta_url = String::new();
        if c["serverAuth"].as_bool().unwrap_or(false) {
            let fa = fixture["serverAuth"].clone();
            let principals = fa["principals"].clone();
            let auth = McpServerAuth {
                issuer: fa["issuer"].as_str().unwrap().into(),
                audience: fa["audience"].as_str().unwrap().into(),
                scopes: serde_json::from_value(fa["scopes"].clone()).unwrap(),
                verify: Some(Arc::new(move |_, token, _| {
                    let r = match principals.get(&token) {
                        Some(p) => Ok(McpPrincipal {
                            subject: p["subject"].as_str().unwrap().into(),
                            scopes: serde_json::from_value(p["scopes"].clone()).unwrap(),
                            ..Default::default()
                        }),
                        None => Err("bad token".to_owned()),
                    };
                    Box::pin(async move { r })
                })),
                ..Default::default()
            };
            cfg.authenticate = Some(auth.authenticate());
            meta_url = serve_metadata(auth.metadata()).await;
        }
        let url = serve(tk().await.mcp_handler(cfg)).await;

        let mut sid = String::new();
        for (i, st) in sc["steps"].as_array().unwrap().iter().enumerate() {
            let rq = &st["request"];
            let method = rq["method"].as_str().unwrap();
            let mcp2026 = rq["mcp2026"].as_bool().unwrap_or(false);
            let target = match rq["target"].as_str() {
                Some(t) => format!("{meta_url}{t}"),
                None => url.clone(),
            };
            let mut b = client.request(method.parse().unwrap(), &target);
            let body = rq
                .get("body")
                .map(|b| if mcp2026 { stamp_2026(b) } else { b.clone() });
            if let Some(raw) = rq["rawBody"].as_str() {
                b = b.body(raw.to_owned());
            } else if let Some(body) = &body {
                b = b.body(serde_json::to_vec(body).unwrap());
            }
            if method == "POST" {
                b = b.header(
                    "Content-Type",
                    rq["contentType"].as_str().unwrap_or("application/json"),
                );
            }
            b = b.header(
                "Accept",
                rq["accept"]
                    .as_str()
                    .unwrap_or("application/json, text/event-stream"),
            );
            let tok = rq["token"].as_str().or(sc["token"].as_str()).unwrap_or("");
            if !tok.is_empty() {
                b = b.header("Authorization", tok);
            }
            if let Some(s) = rq["sessionId"].as_str() {
                b = b.header("Mcp-Session-Id", s);
            } else if rq["session"].as_bool().unwrap_or(false) {
                b = b.header("Mcp-Session-Id", &sid);
            }
            if let Some(v) = rq["version"].as_str() {
                b = b.header("MCP-Protocol-Version", v);
            }
            let mut req = b.build().unwrap();
            let hs = req.headers_mut();
            if mcp2026 {
                let body = body.clone().unwrap_or(json!({}));
                let m = body["method"].as_str().unwrap_or("").to_owned();
                hs.insert("MCP-Protocol-Version", MCP_PROTOCOL_2026.parse().unwrap());
                hs.insert("Mcp-Method", m.parse().unwrap());
                let key = match m.as_str() {
                    "tools/call" | "prompts/get" => Some("name"),
                    "resources/read" => Some("uri"),
                    _ => None,
                };
                if let Some(k) = key {
                    let v = body["params"][k].as_str().unwrap_or("");
                    hs.insert("Mcp-Name", v.parse().unwrap());
                }
            }
            if let Some(Value::Object(extra)) = rq.get("headers") {
                for (k, v) in extra {
                    let name: reqwest::header::HeaderName = k.parse().unwrap();
                    match v.as_str().unwrap_or("") {
                        "" => {
                            hs.remove(&name);
                        }
                        v => {
                            hs.insert(name, v.parse().unwrap());
                        }
                    }
                }
            }
            let resp = client.execute(req).await.unwrap();
            let status = resp.status().as_u16();
            let headers = resp.headers().clone();
            let body = resp.bytes().await.unwrap();
            let ex = &st["expect"];
            let ctxs = format!("{name} step {i}: {}", String::from_utf8_lossy(&body));
            if let Some(want) = ex["status"].as_u64() {
                assert_eq!(status as u64, want, "{ctxs}");
            }
            let hv = |k: &str| {
                headers
                    .get(k)
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_owned()
            };
            if let Some(Value::Object(hs)) = ex.get("headers") {
                for (k, v) in hs {
                    assert_eq!(hv(k), v.as_str().unwrap(), "{ctxs} header {k}");
                }
            }
            for k in ex["headersPresent"].as_array().unwrap_or(&vec![]) {
                assert!(!hv(k.as_str().unwrap()).is_empty(), "{ctxs} {k} missing");
            }
            for k in ex["headersAbsent"].as_array().unwrap_or(&vec![]) {
                assert!(hv(k.as_str().unwrap()).is_empty(), "{ctxs} {k} present");
            }
            if st["capture"].as_str() == Some("sid") {
                sid = hv("mcp-session-id");
            }
            if ex.get("json").is_some() || ex.get("jsonPath").is_some() {
                let got: Value = serde_json::from_slice(&body)
                    .unwrap_or_else(|_| panic!("{ctxs}: body not JSON"));
                if let Some(want) = ex.get("json") {
                    assert_eq!(&got, want, "{ctxs}");
                }
                if let Some(Value::Object(ps)) = ex.get("jsonPath") {
                    for (p, want) in ps {
                        assert_eq!(path(&got, p), Some(want), "{ctxs} path {p}");
                    }
                }
            }
        }
        if let Some(want) = sc.get("expectExecuted") {
            assert_eq!(json!(*executed.lock().unwrap()), *want, "{name} executed");
        }
        if let Some(want) = sc.get("expectOnCall") {
            assert_eq!(json!(*on_calls.lock().unwrap()), *want, "{name} onCall");
        }
        let _: Map<String, Value> = Map::new();
    }
}
