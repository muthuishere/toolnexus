//! Streaming `retry` event + `x-should-retry` override (SPEC § Resilience,
//! issue #189). Hermetic: loopback fake provider.

mod common;

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use common::*;
use serde_json::json;
use toolnexus::client::*;

fn opts(url: &str, style: Style) -> ClientOptions {
    ClientOptions {
        base_url: url.into(),
        model: "m".into(),
        api_key: "k".into(),
        retry_base_ms: 1,
        style,
        ..Default::default()
    }
}

fn ok_stream(style: Style) -> Resp {
    match style {
        Style::Anthropic => {
            let ev = [
                json!({"type":"message_start","message":{"usage":{"input_tokens":1}}}),
                json!({"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}),
                json!({"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}),
                json!({"type":"content_block_stop","index":0}),
                json!({"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}),
                json!({"type":"message_stop"}),
            ];
            let mut s = String::new();
            for e in ev {
                s.push_str(&format!(
                    "event: {}\ndata: {e}\n\n",
                    e["type"].as_str().unwrap()
                ));
            }
            Resp::sse(s)
        }
        _ => Resp::sse(sse_lines(&[
            json!({"choices": [{"delta": {"content": "hello"}}]}),
        ])),
    }
}

/// Serves `first` on call 1, then `rest`; returns the server and its call counter.
async fn fail_first(
    first: impl Fn() -> Resp + Send + Sync + 'static,
    rest: impl Fn() -> Resp + Send + Sync + 'static,
) -> (Server, Arc<AtomicUsize>) {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let (first, rest) = (Arc::new(first), Arc::new(rest));
    let srv = serve(move |_| {
        let (n, first, rest) = (n2.clone(), first.clone(), rest.clone());
        async move {
            if n.fetch_add(1, Ordering::SeqCst) == 0 {
                first()
            } else {
                rest()
            }
        }
    })
    .await;
    (srv, n)
}

async fn collect(c: Client) -> Vec<StreamEvent> {
    let tk = empty_toolkit().await;
    let mut rx = c.stream("hi", &tk).unwrap();
    let mut out = Vec::new();
    while let Some(ev) = rx.recv().await {
        out.push(ev);
    }
    out
}

#[tokio::test]
async fn retry_event_on_429_openai_and_anthropic() {
    for style in [Style::OpenAI, Style::Anthropic] {
        let (srv, _) = fail_first(
            || Resp::status(429, "slow").header("Retry-After", "0"),
            move || ok_stream(style),
        )
        .await;
        let evs = collect(create_client(opts(&srv.url, style))).await;
        let r = &evs[0];
        assert_eq!(r.kind, "retry", "{style:?}");
        assert_eq!(
            (r.attempt, r.max, r.delay_ms, r.status, r.reason.as_str()),
            (1, 2, 0, Some(429), "HTTP 429"),
            "{style:?}"
        );
        assert_eq!(evs.iter().filter(|e| e.kind == "retry").count(), 1);
        assert!(evs.iter().any(|e| e.kind == "text" && e.delta == "hello"));
        assert_eq!(evs.last().unwrap().kind, "done");
    }
}

#[tokio::test]
async fn retry_event_on_network_error_has_no_status() {
    // A port nothing listens on: every attempt is a network error.
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", l.local_addr().unwrap());
    drop(l);
    let mut o = opts(&url, Style::OpenAI);
    o.retries = 1;
    let evs = collect(create_client(o)).await;
    let kinds: Vec<_> = evs.iter().map(|e| e.kind.as_str()).collect();
    assert_eq!(kinds, ["retry", "error"]);
    let r = &evs[0];
    assert_eq!(
        (r.attempt, r.max, r.status, r.reason.as_str()),
        (1, 1, None, "network")
    );
    assert!((1..=100).contains(&r.delay_ms), "delay {}", r.delay_ms);
}

#[tokio::test]
async fn terminal_400_streams_no_retry_event() {
    let (srv, n) = fail_first(|| Resp::status(400, "bad"), || ok_stream(Style::OpenAI)).await;
    let evs = collect(create_client(opts(&srv.url, Style::OpenAI))).await;
    let kinds: Vec<_> = evs.iter().map(|e| e.kind.as_str()).collect();
    assert_eq!(kinds, ["error"]);
    assert_eq!(n.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn should_retry_true_retries_a_400() {
    let (srv, n) = fail_first(
        || Resp::status(400, "bad").header("x-should-retry", " TRUE "),
        || Resp::json(oa_text("ok")),
    )
    .await;
    let tk = empty_toolkit().await;
    let res = create_client(opts(&srv.url, Style::OpenAI))
        .run("hi", &tk)
        .await
        .unwrap();
    assert_eq!(res.text, "ok");
    assert_eq!(n.load(Ordering::SeqCst), 2);
}

#[tokio::test]
async fn should_retry_false_makes_503_terminal() {
    let (srv, n) = fail_first(
        || Resp::status(503, "busy").header("X-Should-Retry", "False"),
        || Resp::json(oa_text("ok")),
    )
    .await;
    let tk = empty_toolkit().await;
    let err = create_client(opts(&srv.url, Style::OpenAI))
        .run("hi", &tk)
        .await
        .unwrap_err();
    assert!(
        matches!(err, ClientError::Provider(ref p) if p.status == 503),
        "{err:?}"
    );
    assert_eq!(n.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn on_error_still_decides_over_should_retry() {
    let (srv, n) = fail_first(
        || Resp::status(400, "bad").header("x-should-retry", "true"),
        || Resp::json(oa_text("ok")),
    )
    .await;
    let seen = Arc::new(std::sync::Mutex::new(None));
    let s2 = seen.clone();
    let mut o = opts(&srv.url, Style::OpenAI);
    o.on_error = Some(Arc::new(move |i: &ErrorInfo| {
        *s2.lock().unwrap() = Some(i.retryable);
        ErrorTier::Fail
    }));
    let tk = empty_toolkit().await;
    assert!(create_client(o).run("hi", &tk).await.is_err());
    assert_eq!(n.load(Ordering::SeqCst), 1);
    assert_eq!(*seen.lock().unwrap(), Some(true));
}

#[test]
fn should_retry_header_parsing() {
    assert_eq!(should_retry_header("true"), Some(true));
    assert_eq!(should_retry_header(" FALSE "), Some(false));
    assert_eq!(should_retry_header("yes"), None);
    assert_eq!(should_retry_header(""), None);
}

#[tokio::test]
async fn run_retry_on_429_is_unchanged() {
    let (srv, n) = fail_first(
        || Resp::status(429, "slow").header("Retry-After", "0"),
        || Resp::json(oa_text("ok")),
    )
    .await;
    let tk = empty_toolkit().await;
    let res = create_client(opts(&srv.url, Style::OpenAI))
        .run("hi", &tk)
        .await
        .unwrap();
    assert_eq!(res.text, "ok");
    assert_eq!(n.load(Ordering::SeqCst), 2);
}
