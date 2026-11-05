//! SPEC §7C.2 (MCP 2026-07-28 on mcp_handler) port-own tests. Mirrors
//! golang/mcphandler2026_test.go: the default RS256/JWKS verifier against the
//! dev authorization server, the dev AS flows (pre-registered + CIMD client,
//! PKCE, refresh), on_call / prompts / discover on the 2026 wire, and
//! load_mcp_skills.
#![cfg(feature = "serve")]

mod common_mcp_handler;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde_json::{Map, Value, json};
use toolnexus::mcp_handler::*;
use toolnexus::{Tool, ToolResult, define_tool};

use common_mcp_handler::{serve, tk};

/// One 2026-07-28 request (headers + `_meta` stamped) ⇒ (status, headers, body).
async fn post26(
    u: &str,
    token: &str,
    method: &str,
    params: Value,
) -> (u16, reqwest::header::HeaderMap, Value) {
    let mut params = params;
    if params.is_null() {
        params = json!({});
    }
    params
        .as_object_mut()
        .unwrap()
        .entry("_meta")
        .or_insert(json!({}))
        .as_object_mut()
        .unwrap()
        .insert(
            "io.modelcontextprotocol/protocolVersion".into(),
            json!(MCP_PROTOCOL_2026),
        );
    let body = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
    let mut b = reqwest::Client::new()
        .post(u)
        .header("content-type", "application/json")
        .header("mcp-protocol-version", MCP_PROTOCOL_2026)
        .header("mcp-method", method)
        .body(body.to_string());
    if let Some(n) = params["name"].as_str() {
        b = b.header("mcp-name", n);
    }
    if !token.is_empty() {
        b = b.header("authorization", format!("Bearer {token}"));
    }
    let r = b.send().await.unwrap();
    let st = r.status().as_u16();
    let h = r.headers().clone();
    let v = r.json::<Value>().await.unwrap_or(Value::Null);
    (st, h, v)
}

fn who_tool() -> Tool {
    let mut t = define_tool(
        "who",
        "who",
        Some(json!({"type": "object"})),
        |_: Value, _| async { Ok::<_, String>(String::new()) },
    );
    t.execute = Arc::new(|_, ctx| {
        Box::pin(async move {
            let sub = mcp_request_context(&ctx)
                .and_then(|rc| rc.identity_as::<McpPrincipal>())
                .map(|p| p.subject.clone())
                .unwrap_or_default();
            ToolResult::ok(sub)
        })
    });
    t
}

fn tools_of(t: Vec<Tool>) -> ListToolsFn {
    Arc::new(move |_| {
        let t = t.clone();
        Box::pin(async move { Ok(t) })
    })
}

/// Serve `h` at `/mcp` and `m` at the well-known paths on ONE origin.
async fn serve_both(h: McpHandler, m: McpAuthMetadata) -> String {
    let ln = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = ln.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = ln.accept().await else {
                return;
            };
            let (h, m) = (h.clone(), m.clone());
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(
                    move |req: hyper::Request<hyper::body::Incoming>| {
                        let (h, m) = (h.clone(), m.clone());
                        async move {
                            let r = if req.uri().path().starts_with("/.well-known/") {
                                m.handle(req).await
                            } else {
                                h.handle(req).await
                            };
                            Ok::<_, std::convert::Infallible>(r)
                        }
                    },
                );
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), svc)
                    .await;
            });
        }
    });
    format!("http://{addr}")
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

#[tokio::test]
async fn default_jwt_verifier_with_dev_as() {
    let dev = start_mcp_dev_auth_server(McpDevAuthOptions::default())
        .await
        .unwrap();
    let auth = McpServerAuth {
        issuer: dev.issuer.clone(),
        scopes: HashMap::from([
            ("*".into(), vec!["mcp".into()]),
            ("who".into(), vec!["mcp:who".into()]),
        ]),
        ..Default::default()
    };
    let h = tk().await.mcp_handler(McpHandlerConfig {
        protocol_versions: vec![MCP_PROTOCOL_2026.into()],
        authenticate: Some(auth.authenticate()),
        list_tools: Some(tools_of(vec![who_tool()])),
        ..Default::default()
    });
    let origin = serve_both(h, auth.metadata()).await;
    let u = format!("{origin}/mcp");
    let t0 = now();
    let mint = |over: Value| {
        let mut c = json!({"iss": dev.issuer, "sub": "dev-user", "aud": u, "scope": "mcp mcp:who", "exp": t0 + 60});
        for (k, v) in over.as_object().unwrap() {
            c[k] = v.clone();
        }
        dev.mint(c.as_object().unwrap())
    };
    let call = json!({"name": "who", "arguments": {}});
    let (st, _, out) = post26(&u, &mint(json!({})), "tools/call", call.clone()).await;
    assert_eq!(st, 200, "{out}");
    assert_eq!(out["result"]["content"][0]["text"], "dev-user", "{out}");

    let good = mint(json!({}));
    let tampered = format!("{}x{}", &good[..20], &good[21..]);
    for (name, tok) in [
        (
            "wrong audience",
            mint(json!({"aud": "https://other.example/mcp"})),
        ),
        ("aud array miss", mint(json!({"aud": ["x"]}))),
        ("expired", mint(json!({"exp": t0 - 1}))),
        ("wrong issuer", mint(json!({"iss": "https://evil.example"}))),
        ("tampered", tampered),
        ("not a jwt", "opaque".into()),
    ] {
        let (st, h, _) = post26(&u, &tok, "server/discover", Value::Null).await;
        let w = h
            .get("www-authenticate")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        assert_eq!(st, 401, "{name}");
        assert!(w.contains(r#"error="invalid_token""#), "{name}: {w}");
        assert!(!w.contains(&tok), "{name}: token leaked");
    }
    // The array form of aud that contains the resource is accepted.
    let (st, _, _) = post26(
        &u,
        &mint(json!({"aud": ["x", u]})),
        "server/discover",
        Value::Null,
    )
    .await;
    assert_eq!(st, 200);

    let (st, h, _) = post26(&u, &mint(json!({"scope": "mcp"})), "tools/call", call).await;
    assert_eq!(st, 403);
    assert_eq!(
        h["www-authenticate"].to_str().unwrap(),
        format!(
            r#"Bearer resource_metadata="{origin}/.well-known/oauth-protected-resource/mcp", scope="mcp mcp:who", error="insufficient_scope""#
        )
    );
    let prm: Value = reqwest::get(format!("{origin}/.well-known/oauth-protected-resource/mcp"))
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(prm["resource"], json!(u));
    assert_eq!(prm["authorization_servers"][0], json!(dev.issuer));
    assert_eq!(prm["scopes_supported"], json!(["mcp"]));
    assert_eq!(prm["bearer_methods_supported"], json!(["header"]));
    let r = reqwest::get(format!(
        "{origin}/.well-known/oauth-protected-resource/other"
    ))
    .await
    .unwrap();
    assert_eq!(r.status(), 404);
}

fn qe(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

fn form(pairs: &[(&str, &str)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{}={}", qe(k), qe(v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn query_of(loc: &str) -> HashMap<String, String> {
    let q = loc.split_once('?').map(|x| x.1).unwrap_or("");
    q.split('&')
        .filter_map(|kv| kv.split_once('='))
        .map(|(k, v)| {
            let mut out = Vec::new();
            let b = v.replace('+', " ").into_bytes();
            let mut i = 0;
            while i < b.len() {
                if b[i] == b'%' {
                    out.push(
                        u8::from_str_radix(std::str::from_utf8(&b[i + 1..i + 3]).unwrap(), 16)
                            .unwrap(),
                    );
                    i += 3;
                } else {
                    out.push(b[i]);
                    i += 1;
                }
            }
            (k.to_owned(), String::from_utf8(out).unwrap())
        })
        .collect()
}

async fn post_form(u: &str, body: String) -> reqwest::Response {
    reqwest::Client::new()
        .post(u)
        .header("content-type", "application/x-www-form-urlencoded")
        .body(body)
        .send()
        .await
        .unwrap()
}

/// Authorization code + PKCE against the dev AS, with no browser.
async fn pkce_login(
    dev: &McpDevAuthServer,
    client_id: &str,
    redirect: &str,
    resource: &str,
    scope: &str,
) -> Value {
    let verifier = format!("v-{}", "a".repeat(50));
    let sum = ring::digest::digest(&ring::digest::SHA256, verifier.as_bytes());
    let challenge = URL_SAFE_NO_PAD.encode(sum.as_ref());
    let q = form(&[
        ("response_type", "code"),
        ("client_id", client_id),
        ("redirect_uri", redirect),
        ("state", "s1"),
        ("code_challenge", &challenge),
        ("code_challenge_method", "S256"),
        ("resource", resource),
        ("scope", scope),
    ]);
    let hc = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap();
    let r = hc
        .get(format!("{}/authorize?{q}", dev.issuer))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 302);
    let loc = r.headers()["location"].to_str().unwrap().to_owned();
    assert!(loc.starts_with(redirect), "{loc}");
    let cb = query_of(&loc);
    assert_eq!(cb["iss"], dev.issuer);
    assert_eq!(cb["state"], "s1");
    assert!(!cb["code"].is_empty());
    let tr = post_form(
        &format!("{}/token", dev.issuer),
        form(&[
            ("grant_type", "authorization_code"),
            ("code", &cb["code"]),
            ("code_verifier", &verifier),
            ("client_id", client_id),
            ("redirect_uri", redirect),
            ("resource", resource),
        ]),
    )
    .await;
    let st = tr.status();
    let tok: Value = tr.json().await.unwrap();
    assert_eq!(st, 200, "{tok}");
    tok
}

#[tokio::test]
async fn dev_as_pre_registered_and_cimd() {
    // A Client ID Metadata Document served from a local HTTP server.
    let ln = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let doc_url = format!("http://{}/client.json", ln.local_addr().unwrap());
    let doc = json!({"client_id": doc_url, "client_name": "CIMD app", "redirect_uris": ["http://127.0.0.1/cb"]});
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = ln.accept().await else {
                return;
            };
            let doc = doc.clone();
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(move |_| {
                    let body = doc.to_string();
                    async move {
                        Ok::<_, std::convert::Infallible>(hyper::Response::new(
                            http_body_util::Full::new(hyper::body::Bytes::from(body)),
                        ))
                    }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), svc)
                    .await;
            });
        }
    });
    let dev = start_mcp_dev_auth_server(McpDevAuthOptions {
        clients: HashMap::from([("app".into(), vec!["http://127.0.0.1/cb".into()])]),
        ..Default::default()
    })
    .await
    .unwrap();
    let meta: Value = reqwest::get(format!(
        "{}/.well-known/oauth-authorization-server",
        dev.issuer
    ))
    .await
    .unwrap()
    .json()
    .await
    .unwrap();
    assert_eq!(meta["issuer"], json!(dev.issuer));
    assert_eq!(meta["code_challenge_methods_supported"], json!(["S256"]));
    assert_eq!(meta["client_id_metadata_document_supported"], json!(true));
    assert_eq!(
        meta["authorization_response_iss_parameter_supported"],
        json!(true)
    );
    assert!(meta.get("registration_endpoint").is_none(), "no DCR");

    let auth = McpServerAuth {
        issuer: dev.issuer.clone(),
        scopes: HashMap::from([("*".into(), vec!["mcp".into()])]),
        ..Default::default()
    };
    let h = tk().await.mcp_handler(McpHandlerConfig {
        protocol_versions: vec![MCP_PROTOCOL_2026.into()],
        authenticate: Some(auth.authenticate()),
        list_tools: Some(tools_of(vec![who_tool()])),
        ..Default::default()
    });
    let u = serve(h).await;
    for cid in ["app", doc_url.as_str()] {
        let tok = pkce_login(&dev, cid, "http://127.0.0.1:5555/cb", &u, "mcp").await;
        let at = tok["access_token"].as_str().unwrap();
        let (st, _, out) = post26(
            &u,
            at,
            "tools/call",
            json!({"name": "who", "arguments": {}}),
        )
        .await;
        assert_eq!(st, 200, "{cid}: {out}");
        assert_eq!(out["result"]["content"][0]["text"], "dev-user", "{cid}");
        // refresh rotates and keeps working; the old refresh token is spent
        let rt = tok["refresh_token"].as_str().unwrap();
        let refresh = form(&[
            ("grant_type", "refresh_token"),
            ("refresh_token", rt),
            ("client_id", cid),
            ("resource", &u),
        ]);
        let rr = post_form(&format!("{}/token", dev.issuer), refresh.clone()).await;
        assert_eq!(rr.status(), 200, "{cid} refresh");
        let fresh: Value = rr.json().await.unwrap();
        assert_ne!(fresh["refresh_token"], tok["refresh_token"]);
        let again = post_form(&format!("{}/token", dev.issuer), refresh).await;
        assert_eq!(again.status(), 400, "{cid} reused refresh");
    }
    // unknown client / unregistered redirect never redirects
    let hc = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap();
    let r = hc
        .get(format!(
            "{}/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb",
            dev.issuer
        ))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 400);
    assert_eq!(dev.authorizations(), 3);
    dev.close();
}

#[tokio::test]
async fn on_call_prompts_and_discover_on_2026() {
    let events: Arc<Mutex<Vec<McpCallEvent>>> = Arc::default();
    let ev = events.clone();
    let h = tk().await.mcp_handler(McpHandlerConfig {
        protocol_versions: vec![MCP_PROTOCOL_2026.into()],
        list_tools: Some(tools_of(vec![who_tool()])),
        on_call: Some(Arc::new(move |e| {
            ev.lock().unwrap().push(e);
            panic!("isolated");
        })),
        list_prompts: Some(Arc::new(|_| {
            Box::pin(async {
                Ok(vec![McpPrompt {
                    name: "p".into(),
                    description: "d".into(),
                    ..Default::default()
                }])
            })
        })),
        get_prompt: Some(Arc::new(|_, name, _| {
            Box::pin(async move {
                Ok(McpPromptResult {
                    messages: vec![McpPromptMessage {
                        role: "user".into(),
                        text: format!("hi {name}"),
                    }],
                    ..Default::default()
                })
            })
        })),
        ..Default::default()
    });
    let u = serve(h).await;
    let (st, _, _) = post26(
        &u,
        "",
        "tools/call",
        json!({"name": "who", "arguments": {}}),
    )
    .await;
    assert_eq!(st, 200);
    {
        let evs = events.lock().unwrap();
        assert_eq!(evs.len(), 1);
        assert_eq!(evs[0].name, "who");
    }
    let (_, _, d) = post26(&u, "", "server/discover", Value::Null).await;
    let caps = &d["result"]["capabilities"];
    assert!(caps.get("prompts").is_some(), "{d}");
    assert!(
        caps.get("extensions").is_none() && caps.get("resources").is_none(),
        "{d}"
    );
    let (_, _, l) = post26(&u, "", "prompts/list", Value::Null).await;
    assert_eq!(
        l["result"]["prompts"],
        json!([{"name": "p", "description": "d"}])
    );
    let (_, _, g) = post26(&u, "", "prompts/get", json!({"name": "p"})).await;
    assert_eq!(g["result"]["messages"][0]["content"]["text"], "hi p", "{g}");
    assert_eq!(g["result"]["resultType"], "complete");
    // resources are not advertised ⇒ the method is unknown on this wire
    let (st, _, r) = post26(&u, "", "resources/list", Value::Null).await;
    assert_eq!(st, 404, "{r}");
}

#[tokio::test]
async fn load_mcp_skills_on_examples() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/skills");
    let skills = load_mcp_skills(dir).unwrap();
    assert_eq!(skills.len(), 1);
    let s = &skills[0];
    assert_eq!(s.name, "hello-world");
    let paths: Vec<&str> = s.files.iter().map(|f| f.path.as_str()).collect();
    assert_eq!(paths, ["SKILL.md", "scripts/greet.sh"]);
    let fm: &Map<String, Value> = s.frontmatter.as_ref().unwrap();
    assert_eq!(fm["name"], "hello-world");
    assert!(
        fm["description"]
            .as_str()
            .unwrap()
            .starts_with("A tiny example skill")
    );
    assert!(load_mcp_skills("/definitely/not/here").is_err());
}

#[tokio::test]
async fn mcp_auth_error_on_the_legacy_wire() {
    let auth = McpServerAuth {
        issuer: "https://as.example.test".into(),
        audience: "https://mcp.example.test/mcp".into(),
        scopes: HashMap::from([("*".into(), vec!["mcp".into()])]),
        ..Default::default()
    };
    let u = serve(tk().await.mcp_handler(McpHandlerConfig {
        authenticate: Some(auth.authenticate()),
        ..Default::default()
    }))
    .await;
    let r = reqwest::Client::new()
        .post(&u)
        .header("content-type", "application/json")
        .body(r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 401);
    assert_eq!(
        r.headers()["www-authenticate"].to_str().unwrap(),
        r#"Bearer resource_metadata="https://mcp.example.test/.well-known/oauth-protected-resource/mcp", scope="mcp""#
    );
    assert_eq!(r.headers()["content-type"], "application/json");
    assert_eq!(r.text().await.unwrap(), r#"{"error":"unauthorized"}"#);
}
