//! `fallback_models` (SPEC §8 Resilience): advance to the next model only after the
//! retry budget is spent on a final 503/529. Hermetic loopback provider.

mod common;

use std::sync::{Arc, Mutex};

use common::*;
use serde_json::json;
use toolnexus::client::*;

/// The kill-switch test mutates the process env; serialise the whole file.
static ENV: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn opts(url: &str, fallbacks: &[&str]) -> ClientOptions {
    ClientOptions {
        base_url: url.into(),
        model: "m1".into(),
        api_key: "k".into(),
        retry_base_ms: 1,
        retries: -1,
        fallback_models: fallbacks.iter().map(|s| s.to_string()).collect(),
        ..Default::default()
    }
}

/// A provider that fails `fail_model` with `status`, serves every other model, and
/// records each request body's model.
async fn provider(fail_model: &'static str, status: u16) -> (Server, Arc<Mutex<Vec<String>>>) {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let seen = s2.clone();
        async move {
            let m = r.body["model"].as_str().unwrap_or("").to_owned();
            seen.lock().unwrap().push(m.clone());
            if m == fail_model {
                return Resp::status(status, "overloaded");
            }
            if r.body["stream"] == true {
                return Resp::sse(sse_lines(&[
                    json!({"choices": [{"delta": {"content": "ok"}}]}),
                ]));
            }
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    (srv, seen)
}

#[tokio::test]
async fn overload_529_falls_back_and_reports_serving_model() {
    let _g = ENV.lock().await;
    let (srv, seen) = provider("m1", 529).await;
    let tk = empty_toolkit().await;
    let after = Arc::new(Mutex::new(Vec::new()));
    let a2 = after.clone();
    let mut o = opts(&srv.url, &["", "m1", "m2", "m3"]);
    o.hooks.after_llm = Some(Arc::new(move |e: AfterLlmEvent| {
        a2.lock().unwrap().push(e.model.clone());
        Box::pin(async { Ok(()) })
    }));
    let res = create_client(o).run("hi", &tk).await.unwrap();
    assert_eq!(res.text, "ok");
    assert_eq!(res.model, "m2");
    assert_eq!(*seen.lock().unwrap(), ["m1", "m2"]);
    assert_eq!(*after.lock().unwrap(), ["m2"]);
}

#[tokio::test]
async fn fallback_gets_a_fresh_retry_budget_and_exhausted_chain_surfaces_last_error() {
    let _g = ENV.lock().await;
    let srv = serve(|r: Req| async move {
        let _ = r;
        Resp::status(503, "busy")
    })
    .await;
    let tk = empty_toolkit().await;
    let mut o = opts(&srv.url, &["m2"]);
    o.retries = 1;
    let e = create_client(o).run("hi", &tk).await.unwrap_err();
    assert!(
        matches!(e, ClientError::Provider(ref p) if p.status == 503),
        "{e:?}"
    );
}

#[tokio::test]
async fn rate_limit_429_never_switches() {
    let _g = ENV.lock().await;
    let (srv, seen) = provider("m1", 429).await;
    let tk = empty_toolkit().await;
    let e = create_client(opts(&srv.url, &["m2"])).run("hi", &tk).await;
    assert!(e.is_err());
    assert_eq!(*seen.lock().unwrap(), ["m1"]);
}

#[tokio::test]
async fn on_error_fail_on_503_does_not_switch() {
    let _g = ENV.lock().await;
    let (srv, seen) = provider("m1", 503).await;
    let tk = empty_toolkit().await;
    let mut o = opts(&srv.url, &["m2"]);
    o.retries = 2;
    o.on_error = Some(Arc::new(|_| ErrorTier::Fail));
    assert!(create_client(o).run("hi", &tk).await.is_err());
    assert_eq!(*seen.lock().unwrap(), ["m1"]);
}

#[tokio::test]
async fn kill_switch_env_disables_the_chain() {
    let _g = ENV.lock().await;
    let (srv, seen) = provider("m1", 529).await;
    let tk = empty_toolkit().await;
    // SAFETY: serialised by ENV; no other thread in this binary reads the env meanwhile.
    unsafe { std::env::set_var("TOOLNEXUS_DISABLE_MODEL_FALLBACK", "TRUE") };
    let r = create_client(opts(&srv.url, &["m2"])).run("hi", &tk).await;
    unsafe { std::env::remove_var("TOOLNEXUS_DISABLE_MODEL_FALLBACK") };
    assert!(r.is_err());
    assert_eq!(*seen.lock().unwrap(), ["m1"]);
}

#[tokio::test]
async fn stream_emits_fallback_before_text() {
    let _g = ENV.lock().await;
    let (srv, _seen) = provider("m1", 503).await;
    let tk = empty_toolkit().await;
    let mut rx = create_client(opts(&srv.url, &["m2"]))
        .stream("hi", &tk)
        .unwrap();
    let mut kinds = Vec::new();
    let mut result = None;
    while let Some(ev) = rx.recv().await {
        if ev.kind == "fallback" {
            assert_eq!((ev.from.as_str(), ev.to.as_str()), ("m1", "m2"));
        }
        if ev.kind == "done" {
            result = ev.result;
        }
        kinds.push(ev.kind);
    }
    assert_eq!(kinds.first().map(String::as_str), Some("fallback"));
    assert_eq!(kinds.get(1).map(String::as_str), Some("text"));
    assert_eq!(result.unwrap().model, "m2");
}

#[tokio::test]
async fn absent_option_is_unchanged() {
    let _g = ENV.lock().await;
    let (srv, seen) = provider("m1", 529).await;
    let tk = empty_toolkit().await;
    let e = create_client(opts(&srv.url, &[]))
        .run("hi", &tk)
        .await
        .unwrap_err();
    assert!(matches!(e, ClientError::Provider(ref p) if p.status == 529));
    assert_eq!(*seen.lock().unwrap(), ["m1"]);
}
