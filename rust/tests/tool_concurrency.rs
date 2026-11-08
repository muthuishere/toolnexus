//! add-tool-concurrency (SPEC §8 "Tool scheduling"), ported from
//! golang/tool_concurrency_test.go. A local server answers turn 1 with one call per
//! name and turn 2 with "done", in all four wire modes (openai/anthropic × run/stream).

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use common::*;
use serde_json::{Value, json};
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{
    Tool, ToolHints, define_tool, is_concurrency_safe, resolve_max_tool_concurrency_with,
};

fn reply(names: &[String], anth: bool, stream: bool, first: bool) -> Resp {
    let ids: Vec<(usize, &String)> = names.iter().enumerate().collect();
    if !stream {
        return Resp::json(match (anth, first) {
            (true, true) => {
                json!({"content": ids.iter().map(|(i, n)| json!({"type": "tool_use", "id": format!("c{}", i + 1), "name": n, "input": {}})).collect::<Vec<_>>(), "stop_reason": "tool_use"})
            }
            (true, false) => {
                json!({"content": [{"type": "text", "text": "done"}], "stop_reason": "end_turn"})
            }
            (false, true) => {
                json!({"choices": [{"message": {"role": "assistant", "content": null, "tool_calls": ids.iter().map(|(i, n)| json!({"id": format!("c{}", i + 1), "type": "function", "function": {"name": n, "arguments": "{}"}})).collect::<Vec<_>>()}}]})
            }
            (false, false) => {
                json!({"choices": [{"message": {"role": "assistant", "content": "done"}}]})
            }
        });
    }
    let mut ev: Vec<Value> = vec![];
    if !anth {
        if first {
            for (i, n) in &ids {
                ev.push(json!({"choices": [{"delta": {"tool_calls": [{"index": i, "id": format!("c{}", i + 1), "type": "function", "function": {"name": n, "arguments": "{}"}}]}}]}));
            }
            ev.push(json!({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]}));
        } else {
            ev.push(json!({"choices": [{"delta": {"content": "done"}}]}));
            ev.push(json!({"choices": [{"delta": {}, "finish_reason": "stop"}]}));
        }
        return Resp::sse(sse_lines(&ev));
    }
    if first {
        for (i, n) in &ids {
            ev.push(json!({"type": "content_block_start", "index": i, "content_block": {"type": "tool_use", "id": format!("c{}", i + 1), "name": n}}));
            ev.push(json!({"type": "content_block_delta", "index": i, "delta": {"type": "input_json_delta", "partial_json": "{}"}}));
            ev.push(json!({"type": "content_block_stop", "index": i}));
        }
        ev.push(json!({"type": "message_delta", "delta": {"stop_reason": "tool_use"}}));
    } else {
        ev.push(
            json!({"type": "content_block_start", "index": 0, "content_block": {"type": "text"}}),
        );
        ev.push(json!({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "done"}}));
        ev.push(json!({"type": "message_delta", "delta": {"stop_reason": "end_turn"}}));
    }
    ev.push(json!({"type": "message_stop"}));
    let mut s = String::new();
    for e in &ev {
        s.push_str(&format!("data: {e}\n\n"));
    }
    Resp::sse(s)
}

#[derive(Default)]
struct Tracker {
    inflight: usize,
    peak: usize,
    log: Vec<String>,
}

fn tool(tr: &Arc<Mutex<Tracker>>, name: &str, read_only: bool) -> Tool {
    let (tr, n) = (tr.clone(), name.to_string());
    let t = define_tool(name, name, None, move |_, _| {
        let (tr, n) = (tr.clone(), n.clone());
        async move {
            {
                let mut s = tr.lock().unwrap();
                s.inflight += 1;
                s.peak = s.peak.max(s.inflight);
                s.log.push(format!("start {n}"));
            }
            tokio::time::sleep(Duration::from_millis(60)).await;
            {
                let mut s = tr.lock().unwrap();
                s.inflight -= 1;
                s.log.push(format!("end {n}"));
            }
            Ok::<_, String>(format!("out {n}"))
        }
    });
    if read_only {
        Tool {
            hints: Some(ToolHints {
                read_only: true,
                ..Default::default()
            }),
            ..t
        }
    } else {
        t
    }
}

const MODES: [(Style, bool); 4] = [
    (Style::OpenAI, false),
    (Style::Anthropic, false),
    (Style::OpenAI, true),
    (Style::Anthropic, true),
];

async fn run(
    style: Style,
    stream: bool,
    names: &[&str],
    tr: &Arc<Mutex<Tracker>>,
    cap: usize,
) -> Vec<String> {
    let names: Vec<String> = names.iter().map(|s| s.to_string()).collect();
    let turn = Arc::new(AtomicUsize::new(0));
    let ns = names.clone();
    let srv = serve(move |r: Req| {
        let first = turn.fetch_add(1, Ordering::SeqCst) == 0;
        let anth = r.path.contains("messages");
        let stream = r.body["stream"] == json!(true);
        let ns = ns.clone();
        async move { reply(&ns, anth, stream, first) }
    })
    .await;
    let tools: Vec<Tool> = names
        .iter()
        .map(|n| tool(tr, n, n.starts_with('r')))
        .collect();
    let tk = toolnexus::create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: tools,
        ..Default::default()
    })
    .await
    .unwrap();
    let base = if style == Style::OpenAI {
        format!("{}/v1", srv.url)
    } else {
        srv.url.clone()
    };
    let client = create_client(ClientOptions {
        base_url: base,
        style,
        model: "m".into(),
        api_key: "k".into(),
        max_tool_concurrency: cap,
        ..Default::default()
    });
    let res = if stream {
        let mut rx = client.stream("go", &tk).unwrap();
        let mut out = None;
        while let Some(ev) = rx.recv().await {
            if let Some(r) = ev.result {
                out = Some(*r);
            }
        }
        out.expect("done event")
    } else {
        client.run("go", &tk).await.unwrap()
    };
    tk.close().await;
    // each result must stay paired with its own call, whatever order they finished in
    for c in &res.tool_calls {
        assert_eq!(
            c.output,
            format!("out {}", c.name),
            "result paired with wrong call"
        );
    }
    res.tool_calls.iter().map(|c| c.name.clone()).collect()
}

fn reads(n: usize) -> Vec<String> {
    (1..=n).map(|i| format!("r{i}")).collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn unsafe_calls_serialize() {
    for (style, stream) in MODES {
        let tr = Arc::new(Mutex::new(Tracker::default()));
        let got = run(style, stream, &["w1", "w2"], &tr, 0).await;
        let s = tr.lock().unwrap();
        assert_eq!(s.peak, 1, "{style:?} {stream}");
        assert_eq!(s.log, ["start w1", "end w1", "start w2", "end w2"]);
        assert_eq!(got, ["w1", "w2"]);
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn read_only_calls_overlap_and_cap_is_honoured() {
    let six = reads(6);
    let six: Vec<&str> = six.iter().map(String::as_str).collect();
    for (style, stream) in MODES {
        let tr = Arc::new(Mutex::new(Tracker::default()));
        let got = run(style, stream, &six, &tr, 0).await;
        assert_eq!(tr.lock().unwrap().peak, 6, "{style:?} {stream}");
        assert_eq!(got, six);
        let tr = Arc::new(Mutex::new(Tracker::default()));
        run(style, stream, &six, &tr, 2).await;
        assert_eq!(tr.lock().unwrap().peak, 2, "cap {style:?} {stream}");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn mixed_runs_keep_order() {
    let names = ["r1", "r2", "w", "r3", "r4"];
    for (style, stream) in MODES {
        let tr = Arc::new(Mutex::new(Tracker::default()));
        let got = run(style, stream, &names, &tr, 0).await;
        let s = tr.lock().unwrap();
        let pos = |e: &str| s.log.iter().position(|l| l == e).unwrap();
        for r in ["r1", "r2"] {
            assert!(pos(&format!("end {r}")) < pos("start w"), "{:?}", s.log);
        }
        for r in ["r3", "r4"] {
            assert!(pos(&format!("start {r}")) > pos("end w"), "{:?}", s.log);
        }
        assert_eq!(s.peak, 2);
        assert_eq!(got, names);
    }
}

#[test]
fn env_override_and_fallback() {
    assert_eq!(
        resolve_max_tool_concurrency_with(0, |_| Some("3".into())),
        3
    );
    assert_eq!(
        resolve_max_tool_concurrency_with(4, |_| Some("3".into())),
        4
    );
    for bad in [Some("0"), Some("-2"), Some("abc"), Some(""), None] {
        assert_eq!(
            resolve_max_tool_concurrency_with(0, |_| bad.map(String::from)),
            10,
            "{bad:?}"
        );
    }
}

#[tokio::test]
async fn safety_rule() {
    let tk = toolnexus::create_toolkit(toolnexus::ToolkitOptions::default())
        .await
        .unwrap();
    for (name, safe) in [
        ("read", true),
        ("grep", true),
        ("glob", true),
        ("webfetch", true),
        ("bash", false),
        ("write", false),
        ("edit", false),
        ("apply_patch", false),
    ] {
        let t = tk.get(name).unwrap_or_else(|| panic!("builtin {name}"));
        assert_eq!(is_concurrency_safe(&t, &json!({})), safe, "{name}");
    }
    tk.close().await;
    let plain = define_tool("x", "", None, |_, _| async {
        Ok::<_, String>(String::new())
    });
    assert!(
        !is_concurrency_safe(&plain, &json!({})),
        "no hints ⇒ unsafe"
    );
    let own = Tool {
        hints: Some(ToolHints {
            read_only: true,
            ..Default::default()
        }),
        ..plain
    }
    .with_concurrency_safe(|a| a["dry"] == json!(true));
    assert!(!is_concurrency_safe(&own, &json!({})));
    assert!(is_concurrency_safe(&own, &json!({"dry": true})));
}
