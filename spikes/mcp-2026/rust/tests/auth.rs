//! OAuth client + protected server, against the in-process DevAuthServer (port of auth_test.go).
mod common;

use std::sync::Arc;
use std::time::Duration;

use common::*;
use mcp2026::{
    Authorizer, Client, HttpServer, Principal, fixture_server, obj, opener, responder, run_checks,
    run_checks_with, text,
};
use serde_json::json;

#[tokio::test(flavor = "multi_thread")]
async fn existing_steps_unprotected() {
    let srv = HttpServer::start(fixture_server()).await;
    assert!(run_checks(&format!("{}/mcp", srv.url)).await);
}

#[tokio::test(flavor = "multi_thread")]
async fn existing_steps_protected_auto_mode() {
    let e = new_env(|_| {}).await;
    assert!(
        run_checks_with(|| e.auto_client()).await,
        "protected selftest failed"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn pause_resume() {
    let e = new_env(|_| {}).await;
    let c = e.pause_client();
    let p = pending(
        c.call_tool("echo", json!({"text": "after login"}), None)
            .await,
    );
    assert!(
        p.kind == "authorization" && !p.id.is_empty() && !p.prompt.is_empty(),
        "{p:?}"
    );
    assert!(
        p.url.starts_with(&format!("{}/authorize?", e.dev.issuer)),
        "{p:?}"
    );
    let cb = e.visit(&p.url).await;
    let q = e.dev.last_authorize_query();
    assert_eq!(
        (
            q.get("resource"),
            q.get("code_challenge_method"),
            q.get("scope")
        ),
        (e.url.clone(), "S256".into(), "mcp:read".into())
    );
    let r = c.oauth().resume(&p.id, &cb).await.unwrap();
    assert_eq!(text(&r), "after login");
    assert!(
        c.oauth().resume(&p.id, &cb).await.is_err(),
        "second resume of the same ID must fail"
    );
    let r = c
        .call_tool("echo", json!({"text": "again"}), None)
        .await
        .unwrap();
    assert_eq!(text(&r), "again", "follow-up call should reuse token");
    assert_eq!(e.dev.stats().0, 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn auto_mode_whoami_principal() {
    let e = new_env(|_| {}).await;
    let r = e
        .auto_client()
        .call_tool("whoami", json!({}), None)
        .await
        .unwrap();
    assert_eq!(text(&r), "dev-user mcp:read");
}

#[tokio::test(flavor = "multi_thread")]
async fn iss_mismatch_rejected() {
    let e = new_env(|_| {}).await;
    e.dev.set_iss_override("https://evil.example");
    let c = e.auto_client();
    let err = c
        .call_tool("echo", json!({"text": "x"}), None)
        .await
        .unwrap_err();
    assert!(err.to_string().contains("iss mismatch"), "{err}");
    assert!(
        c.oauth().store.load(&e.url).is_none(),
        "no token may be stored"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn state_mismatch_rejected() {
    let e = new_env(|_| {}).await;
    let c = e.pause_client();
    let p = pending(c.call_tool("echo", json!({"text": "x"}), None).await);
    let cb = e.visit(&p.url).await;
    let i = cb.find("state=").unwrap();
    let cb = format!("{}state=forged&{}", &cb[..i], &cb[i + 6..]);
    let err = c.oauth().resume(&p.id, &cb).await.unwrap_err();
    assert!(err.to_string().contains("state mismatch"), "{err}");
}

#[tokio::test(flavor = "multi_thread")]
async fn pkce_verified_by_as() {
    let e = new_env(|_| {}).await;
    let c = e.pause_client();
    let p = pending(c.call_tool("echo", json!({"text": "x"}), None).await);
    let cb = e.visit(&p.url).await;
    c.oauth().tamper_verifier(&p.id); // the AS must reject the exchange
    let err = c.oauth().resume(&p.id, &cb).await.unwrap_err();
    assert!(err.to_string().contains("invalid_grant"), "{err}");
}

#[tokio::test(flavor = "multi_thread")]
async fn refresh_on_expiry() {
    let e = new_env(|_| {}).await;
    let c = e.auto_client();
    c.call_tool("echo", json!({"text": "1"}), None)
        .await
        .unwrap();
    let old = c.oauth().store.load(&e.url).unwrap().access_token;
    expire_token(&c, &e.url);
    let r = c
        .call_tool("echo", json!({"text": "2"}), None)
        .await
        .unwrap();
    assert_eq!(text(&r), "2");
    let now = c.oauth().store.load(&e.url).unwrap().access_token;
    assert_eq!(e.dev.stats(), (1, 1));
    assert_ne!(now, old, "rotated");
}

#[tokio::test(flavor = "multi_thread")]
async fn insufficient_scope_step_up() {
    let e = new_env(|_| {}).await;
    let c = e.auto_client();
    c.call_tool("echo", json!({"text": "1"}), None)
        .await
        .unwrap();
    let r = c
        .call_tool(
            "greet",
            json!({}),
            responder(|_, _| Ok(obj!({"action": "accept", "content": {"name": "scoped"}}))),
        )
        .await
        .unwrap();
    assert_eq!(text(&r), "Hello, scoped!");
    let sc = e.dev.last_authorize_query().get("scope");
    assert_eq!(e.dev.stats().0, 2);
    assert!(
        sc.split(' ').count() == 2 && sc.contains("mcp:read"),
        "step-up scope {sc} (want union)"
    );
    c.call_tool("echo", json!({"text": "still"}), None)
        .await
        .unwrap();
    assert_eq!(e.dev.stats().0, 2, "previous scope lost");
}

#[tokio::test(flavor = "multi_thread")]
async fn prm_well_known_fallback_and_no_client_identity() {
    let e = new_env(|_| {}).await;
    let o = mcp2026::OAuth::default();
    let m = o.fetch_prm(&e.url, "").await.unwrap();
    assert_eq!(m["resource"], e.url.as_str());
    let c = Client::new(&e.url).with_auth(mcp2026::OAuth {
        authorizer: Authorizer::pause("http://127.0.0.1:9/callback"),
        ..Default::default()
    });
    let err = c
        .call("tools/list", serde_json::Value::Null)
        .await
        .unwrap_err();
    assert!(
        err.to_string()
            .contains(&format!("register an app with {}", e.dev.issuer)),
        "{err}"
    );
}

// --- server-side ---

#[tokio::test(flavor = "multi_thread")]
async fn server_jwt_via_jwks_and_principal() {
    let e = new_env(|_| {}).await;
    let (st, _, body) = e.raw_call(&e.claims(|_| {}), "whoami").await;
    assert!(st == 200 && body.contains("alice mcp:read"), "{st} {body}");
}

#[tokio::test(flavor = "multi_thread")]
async fn server_rejects() {
    let e = new_env(|_| {}).await;
    let cases = [
        ("missing", String::new()),
        (
            "wrong audience",
            e.claims(|c| c["aud"] = json!("https://other.example/mcp")),
        ),
        ("expired", e.claims(|c| c["exp"] = json!(now() - 60))),
        (
            "wrong issuer",
            e.claims(|c| c["iss"] = json!("https://evil.example")),
        ),
        ("tampered", e.claims(|_| {}) + "x"),
    ];
    for (name, tok) in cases {
        let (st, www, _) = e.raw_call(&tok, "echo").await;
        let want = format!(
            r#"resource_metadata="{}/.well-known/oauth-protected-resource/mcp"#,
            e.origin
        );
        assert!(st == 401 && www.contains(&want), "{name}: {st} {www}");
        if !tok.is_empty() {
            assert!(www.contains(r#"error="invalid_token""#), "{name}: {www}");
        }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn server_per_tool_scope_403() {
    let e = new_env(|_| {}).await;
    let (st, www, _) = e.raw_call(&e.claims(|_| {}), "greet").await;
    assert!(
        st == 403
            && www.contains(r#"error="insufficient_scope""#)
            && www.contains(r#"scope="mcp:read mcp:greet""#),
        "{st} {www}"
    );
    let (st, _, _) = e
        .raw_call(
            &e.claims(|c| c["scope"] = json!("mcp:read mcp:greet")),
            "greet",
        )
        .await;
    assert_eq!(st, 200);
}

#[tokio::test(flavor = "multi_thread")]
async fn server_custom_verify() {
    let e = new_env(|s| {
        s.auth.as_mut().unwrap().verify = Some(Arc::new(|tok: &str| {
            if tok != "opaque-ok" {
                return Err("nope".into());
            }
            Ok(Principal {
                subject: "svc".into(),
                scopes: vec!["mcp:read".into()],
                ..Default::default()
            })
        }))
    })
    .await;
    let (st, _, body) = e.raw_call("opaque-ok", "whoami").await;
    assert!(st == 200 && body.contains("svc mcp:read"), "{st} {body}");
    let (st, _, _) = e.raw_call(&e.claims(|_| {}), "whoami").await;
    assert_eq!(st, 401, "JWT must not bypass custom verify");
}

/// A browser opener that fails must surface its error, not park the caller for 5 minutes.
#[tokio::test(flavor = "multi_thread")]
async fn loopback_opener_error_surfaces() {
    let e = new_env(|_| {}).await;
    let c = Client::new(&e.url).with_auth(mcp2026::OAuth {
        authorizer: Authorizer::loopback(opener(|_| async { Err("no browser".to_string()) })),
        ..e.auto_oauth()
    });
    let r = tokio::time::timeout(
        Duration::from_secs(5),
        c.call_tool("echo", json!({"text": "x"}), None),
    )
    .await
    .expect("opener error swallowed: client still waiting for a callback");
    let err = r.unwrap_err();
    assert!(err.to_string().contains("no browser"), "{err}");
}
