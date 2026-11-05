//! Side effects run exactly once across auth pauses, refresh-after-401 and step-up; a lost or
//! failed response is never retried (spec issue #3394). Port of retry_test.go.
mod common;

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use common::*;
use mcp2026::{
    Authorizer, Client, HttpServer, OAuth, Response, Server, fixture_server, handler, obj,
    responder, text,
};
use serde_json::json;

type Counter = Arc<AtomicUsize>;

/// "tick" (base scope) and "charge" (needs mcp:charge), each counting executions.
fn counted(s: &mut Server, tick: &Counter, charge: &Counter) {
    let (t, c) = (tick.clone(), charge.clone());
    s.add_tool("tick", "counts", move |_, _| {
        t.fetch_add(1, Ordering::SeqCst);
        Ok(json!("tick"))
    });
    s.add_tool("charge", "counts, extra scope", move |_, _| {
        c.fetch_add(1, Ordering::SeqCst);
        Ok(json!("charged"))
    });
    if let Some(a) = s.auth.as_mut() {
        a.scopes.insert("charge".into(), vec!["mcp:charge".into()]);
    }
}

fn counters() -> (Counter, Counter) {
    (Arc::default(), Arc::default())
}

#[tokio::test(flavor = "multi_thread")]
async fn pause_resume_executes_once() {
    let (tick, charge) = counters();
    let e = new_env(|s| counted(s, &tick, &charge)).await;
    let c = e.pause_client();
    let p = pending(c.call_tool("tick", json!({}), None).await);
    assert_eq!(
        tick.load(Ordering::SeqCst),
        0,
        "paused call must not have executed"
    );
    let cb = e.visit(&p.url).await;
    assert_eq!(text(&c.oauth().resume(&p.id, &cb).await.unwrap()), "tick");
    assert!(
        c.oauth().resume(&p.id, &cb).await.is_err(),
        "second resume must fail"
    );
    assert_eq!(tick.load(Ordering::SeqCst), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn refresh_after_401_executes_once() {
    let (tick, charge) = counters();
    let e = new_env(|s| counted(s, &tick, &charge)).await;
    let c = e.auto_client();
    c.call_tool("tick", json!({}), None).await.unwrap();
    let mut bad = c.oauth().store.load(&e.url).unwrap();
    bad.access_token = "revoked-by-server".into(); // not expired client-side: the server's 401 drives the refresh
    c.oauth().store.save(&e.url, bad);
    assert_eq!(
        text(&c.call_tool("tick", json!({}), None).await.unwrap()),
        "tick"
    );
    assert_eq!(
        (tick.load(Ordering::SeqCst), e.dev.stats()),
        (2, (1, 1)),
        "executions / (authorizations, refreshes)"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn step_up_executes_once() {
    let (tick, charge) = counters();
    let e = new_env(|s| counted(s, &tick, &charge)).await;
    let c = e.auto_client();
    // 401 (base scope) -> login -> 403 insufficient_scope -> step-up login -> run.
    assert_eq!(
        text(&c.call_tool("charge", json!({}), None).await.unwrap()),
        "charged"
    );
    assert_eq!((charge.load(Ordering::SeqCst), e.dev.stats().0), (1, 2));
}

/// A pause in a later MRTR round resumes THAT round: the user is not re-asked, round 0 is not replayed.
#[tokio::test(flavor = "multi_thread")]
async fn pause_mid_mrtr_resumes_same_round() {
    let e = new_env(|s| {
        s.auth.as_mut().unwrap().scopes.remove("greet");
    })
    .await;
    let c = e.pause_client();
    let p = pending(c.call_tool("echo", json!({"text": "login"}), None).await);
    c.oauth()
        .resume(&p.id, &e.visit(&p.url).await)
        .await
        .unwrap();
    let asked = Arc::new(AtomicUsize::new(0));
    let (a, store, url) = (asked.clone(), c.oauth().store.clone(), e.url.clone());
    let r = c
        .call_tool(
            "greet",
            json!({}),
            responder(move |_, _| {
                a.fetch_add(1, Ordering::SeqCst);
                store.delete(&url); // token lost between rounds -> round 1 gets 401
                Ok(obj!({"action": "accept", "content": {"name": "once"}}))
            }),
        )
        .await;
    let p = pending(r);
    let r = c
        .oauth()
        .resume(&p.id, &e.visit(&p.url).await)
        .await
        .unwrap();
    assert_eq!(
        (text(&r), asked.load(Ordering::SeqCst)),
        ("Hello, once!".to_string(), 1)
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn lost_or_failed_response_not_retried() {
    for mode in ["connection dropped", "502 after execution"] {
        let (tick, charge) = counters();
        let mut s = fixture_server();
        counted(&mut s, &tick, &charge);
        let s = Arc::new(s);
        let ts = HttpServer::start(handler(move |r| {
            let s = s.clone();
            async move {
                let _ = s.handle(r).await; // the tool runs; its response is lost
                if mode == "502 after execution" {
                    Response::text(502, "bad gateway")
                } else {
                    Response::abort()
                }
            }
        }))
        .await;
        let c = Client::new(format!("{}/mcp", ts.url));
        assert!(
            c.call_tool("tick", json!({}), None).await.is_err(),
            "{mode}: want an error"
        );
        tokio::time::sleep(Duration::from_millis(50)).await; // a hidden retry would land here
        assert_eq!(
            tick.load(Ordering::SeqCst),
            1,
            "{mode}: no auto-retry of tools/call"
        );
    }
}

// --- RFC 8707 resource on refresh (python-sdk #2578) ---

#[tokio::test(flavor = "multi_thread")]
async fn refresh_resource_configurable() {
    for omit in [false, true] {
        let e = new_env(|_| {}).await;
        e.dev.set_reject_refresh_resource(true); // Entra-like
        let c = Client::new(&e.url).with_auth(OAuth {
            omit_refresh_resource: omit,
            ..e.auto_oauth()
        });
        c.call_tool("echo", json!({"text": "1"}), None)
            .await
            .unwrap();
        assert_eq!(
            e.dev.last_authorize_query().get("resource"),
            e.url,
            "omit={omit}: authorize resource"
        );
        assert_eq!(
            e.dev.last_token_form().get("resource"),
            e.url,
            "omit={omit}: code exchange must send resource"
        );
        expire_token(&c, &e.url);
        assert_eq!(
            text(
                &c.call_tool("echo", json!({"text": "2"}), None)
                    .await
                    .unwrap()
            ),
            "2"
        );
        let (a, rf) = e.dev.stats();
        if omit {
            assert!(
                rf == 1 && a == 1 && !e.dev.last_token_form().has("resource"),
                "omit: {rf} {a}"
            );
        } else {
            assert!(
                rf == 0 && a == 2,
                "default: refreshes={rf} authorizations={a}"
            ); // Entra rejects -> new login
        }
    }
    let e = new_env(|_| {}).await; // default AS accepts resource on refresh and it is sent
    let c = e.auto_client();
    c.call_tool("echo", json!({"text": "1"}), None)
        .await
        .unwrap();
    expire_token(&c, &e.url);
    c.call_tool("echo", json!({"text": "2"}), None)
        .await
        .unwrap();
    let f = e.dev.last_token_form();
    assert_eq!(
        (f.get("grant_type"), f.get("resource")),
        ("refresh_token".to_string(), e.url.clone())
    );
}

// --- pre-registered client id (+ secret) as the alternative to CIMD; no DCR ---

#[tokio::test(flavor = "multi_thread")]
async fn pre_registered_confidential_client() {
    for method in ["", "client_secret_basic", "client_secret_post"] {
        let e = new_env(|_| {}).await;
        e.dev.set_no_cimd(true);
        e.dev
            .register_client("conf", &["http://127.0.0.1/callback"]);
        e.dev.set_secret("conf", "test-only-secret");
        let mk = |secret: &str, authorizer: Authorizer, store| OAuth {
            client_id: "conf".into(),
            client_secret: secret.into(),
            token_auth_method: method.into(),
            authorizer,
            store,
            ..Default::default()
        };
        let store: Arc<dyn mcp2026::TokenStore> = mcp2026::MemoryTokenStore::new();
        let c = Client::new(&e.url).with_auth(mk(
            "test-only-secret",
            Authorizer::loopback(e.dev.approver()),
            store.clone(),
        ));
        assert_eq!(
            text(
                &c.call_tool("echo", json!({"text": "ok"}), None)
                    .await
                    .unwrap()
            ),
            "ok",
            "{method:?}"
        );
        let f = e.dev.last_token_form();
        if method == "client_secret_post" {
            assert!(
                !f.get("client_secret").is_empty(),
                "{method:?}: secret placement"
            );
        } else {
            assert!(!f.has("client_secret"), "{method:?}: secret placement");
        }
        expire_token(&c, &e.url);
        let c = Client::new(&e.url).with_auth(mk(
            "wrong",
            Authorizer::pause("http://127.0.0.1:9/callback"),
            store,
        ));
        let r = c.call_tool("echo", json!({"text": "x"}), None).await;
        assert!(
            mcp2026::pending_of(&r).is_some(),
            "{method:?}: wrong secret must fail the refresh and fall back to a new authorization, got {r:?}"
        );
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn no_cimd_and_no_client_id_is_a_clear_error() {
    let e = new_env(|_| {}).await;
    e.dev.set_no_cimd(true);
    let err = e
        .auto_client()
        .call_tool("echo", json!({"text": "x"}), None)
        .await
        .unwrap_err()
        .to_string();
    assert!(
        err.contains("does not support Client ID Metadata Documents"),
        "{err}"
    );
    assert!(
        err.contains(&format!(
            "register an app with {} and set OAuth.client_id",
            e.dev.issuer
        )),
        "{err}"
    );
    assert_eq!(e.dev.stats().0, 0, "must not start an authorization");
}
