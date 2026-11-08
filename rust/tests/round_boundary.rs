//! SPEC §8 Hooks `after_round` (#192) against examples/round-boundary/fixture.json
//! (R1-R6). Mirrors js/test/round-boundary.test.ts. Hermetic: a loopback fake
//! provider and a scripted in-process model.

mod agents_support;
mod common;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use common::*;
use serde_json::{Value, json};
use toolnexus::agents::{Options, Runtime};
use toolnexus::client::*;
use toolnexus::{Tool, create_toolkit, define_tool};

type Reply = Value;

fn echo_tool() -> Tool {
    define_tool("echo", "echo text", None, |a: Value, _| async move {
        Ok::<_, String>(a["text"].as_str().unwrap_or("").to_string())
    })
}

async fn tk() -> toolnexus::Toolkit {
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![echo_tool()],
        ..Default::default()
    })
    .await
    .unwrap()
}

fn fixture() -> Value {
    let p = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../examples/round-boundary/fixture.json"
    );
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

fn scenario(id: &str) -> Value {
    fixture()["scenarios"]
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["id"] == id)
        .unwrap()
        .clone()
}

fn oa_reply(r: &Reply, stream: bool) -> Resp {
    if let Some(tc) = r.get("tool_call") {
        let args = tc["arguments"].to_string();
        if stream {
            return Resp::sse(sse_lines(&[json!({"choices": [{"delta": {"tool_calls": [
                {"index": 0, "id": tc["id"], "function": {"name": tc["name"], "arguments": args}}]}}]})]));
        }
        return Resp::json(oa_calls(&[(
            tc["id"].as_str().unwrap(),
            tc["name"].as_str().unwrap(),
            &args,
        )]));
    }
    let c = r["content"].as_str().unwrap();
    if stream {
        Resp::sse(sse_lines(&[
            json!({"choices": [{"delta": {"content": c}}]}),
        ]))
    } else {
        Resp::json(oa_text(c))
    }
}

fn an_reply(r: &Reply) -> Resp {
    if let Some(tc) = r.get("tool_call") {
        return Resp::json(
            json!({"content": [{"type": "tool_use", "id": tc["id"], "name": tc["name"], "input": tc["arguments"]}],
            "stop_reason": "tool_use"}),
        );
    }
    Resp::json(
        json!({"content": [{"type": "text", "text": r["content"]}], "stop_reason": "end_turn"}),
    )
}

/// The scenario's hook rule, recording each call's (turn, final, text).
fn hook_for(id: &str, calls: Arc<Mutex<Vec<Value>>>) -> AfterRoundHook {
    let id = id.to_string();
    Arc::new(move |ev: AfterRoundEvent| {
        calls
            .lock()
            .unwrap()
            .push(json!({"turn": ev.turn, "final": ev.r#final, "text": ev.text}));
        let id = id.clone();
        Box::pin(async move {
            let inject = |v: &[&str]| {
                Ok(Some(RoundInjection {
                    inject: v.iter().map(|s| s.to_string()).collect(),
                }))
            };
            match id.as_str() {
                "R1" if !ev.r#final => inject(&["todo: 2 open"]),
                "R2" | "R6" if ev.r#final && ev.turn == 0 => inject(&["keep going"]),
                "R3" => inject(&[""]),
                "R4" => inject(&["again"]),
                "R5" => Err("round-boom".to_string()),
                _ => Ok(None),
            }
        })
    })
}

struct Outcome {
    requests: Vec<Req>,
    res: Result<RunResult, String>,
    hook_calls: Vec<Value>,
    kinds: Vec<String>,
}

async fn drive(id: &str, style: Style, stream: bool, with_hook: bool) -> Outcome {
    let sc = scenario(id);
    let replies: Vec<Value> = sc["replies"].as_array().unwrap().clone();
    let reqs = Arc::new(Mutex::new(Vec::<Req>::new()));
    let r2 = reqs.clone();
    let srv = serve(move |r: Req| {
        let reqs = r2.clone();
        let replies = replies.clone();
        async move {
            let n = {
                let mut g = reqs.lock().unwrap();
                g.push(r);
                g.len() - 1
            };
            let reply = &replies[n.min(replies.len() - 1)];
            match style {
                Style::OpenAI => oa_reply(reply, stream),
                Style::Anthropic => an_reply(reply),
            }
        }
    })
    .await;
    let calls = Arc::new(Mutex::new(Vec::new()));
    let hooks = if with_hook {
        Hooks {
            after_round: Some(hook_for(id, calls.clone())),
            ..Default::default()
        }
    } else {
        Hooks::default()
    };
    let c = create_client(ClientOptions {
        base_url: srv.url.clone(),
        model: "m".into(),
        api_key: "k".into(),
        retry_base_ms: 1,
        style,
        max_turns: sc["maxTurns"].as_u64().unwrap_or(10) as usize,
        hooks,
        ..Default::default()
    });
    let tk = tk().await;
    let mut kinds = Vec::new();
    let res = if stream {
        let mut rx = c.stream("go", &tk).unwrap();
        let mut out = Err("no done".to_string());
        while let Some(ev) = rx.recv().await {
            kinds.push(ev.kind.clone());
            if ev.kind == "done" {
                out = Ok(*ev.result.unwrap());
            } else if ev.kind == "error" {
                out = Err(ev.error.unwrap().to_string());
            }
        }
        out
    } else {
        c.run("go", &tk).await.map_err(|e| e.to_string())
    };
    let requests = reqs.lock().unwrap().clone();
    let hook_calls = calls.lock().unwrap().clone();
    Outcome {
        requests,
        res,
        hook_calls,
        kinds,
    }
}

fn tail(req: &Req, n: usize) -> Vec<Value> {
    let m = req.messages();
    m[m.len() - n..]
        .iter()
        .map(|x| json!({"role": x["role"], "content": x["content"]}))
        .collect()
}

async fn check(id: &str, stream: bool) {
    let sc = scenario(id);
    let ex = &sc["expect"];
    let o = drive(id, Style::OpenAI, stream, true).await;
    let tag = format!("{id} stream={stream}");
    assert_eq!(
        o.requests.len() as u64,
        ex["requests"].as_u64().unwrap(),
        "{tag}"
    );
    if let Some(e) = ex.get("error") {
        assert_eq!(o.res.unwrap_err(), e.as_str().unwrap(), "{tag}");
        return;
    }
    let res = o.res.unwrap();
    assert_eq!(res.text, ex["text"].as_str().unwrap(), "{tag}");
    if let Some(s) = ex.get("status") {
        assert_eq!(res.status, s.as_str().unwrap(), "{tag}");
    }
    if let Some(t) = ex.get("secondRequestTail") {
        let t = t.as_array().unwrap();
        assert_eq!(tail(&o.requests[1], t.len()), *t, "{tag}");
        // the injection is in the final transcript too
        let last_user = t.last().unwrap()["content"].clone();
        assert!(
            res.messages
                .iter()
                .any(|m| m["role"] == "user" && m["content"] == last_user),
            "{tag}"
        );
    }
    assert_eq!(Value::Array(o.hook_calls.clone()), ex["hookCalls"], "{tag}");
    if stream {
        let n = |k: &str| o.kinds.iter().filter(|x| *x == k).count();
        assert_eq!(n("done"), 1, "{tag}: one done");
        assert!(n("usage") <= 1, "{tag}: no usage for a continued round");
    }
}

#[tokio::test]
async fn r1_to_r5_run_and_stream_openai() {
    for id in ["R1", "R2", "R3", "R4", "R5"] {
        check(id, false).await;
        check(id, true).await;
    }
}

#[tokio::test]
async fn r2_anthropic_run_and_r1_anthropic_tail() {
    let o = drive("R2", Style::Anthropic, false, true).await;
    assert_eq!(o.requests.len(), 2);
    assert_eq!(o.res.unwrap().text, "second");
    let m = o.requests[1].messages();
    assert_eq!(
        m[m.len() - 1],
        json!({"role": "user", "content": "keep going"})
    );
    // R1 on Anthropic: the injection follows the tool_result user turn
    let o = drive("R1", Style::Anthropic, false, true).await;
    assert_eq!(o.requests.len(), 2);
    let m = o.requests[1].messages();
    assert_eq!(m[m.len() - 2]["content"][0]["type"], "tool_result");
    assert_eq!(
        m[m.len() - 1],
        json!({"role": "user", "content": "todo: 2 open"})
    );
}

#[tokio::test]
async fn absent_hook_is_byte_identical() {
    for stream in [false, true] {
        let a = drive("R1", Style::OpenAI, stream, false).await;
        // a hook that injects nothing must not change the wire either
        let b = drive("R3", Style::OpenAI, stream, false).await;
        assert_eq!(a.requests.len(), 2);
        assert_eq!(b.requests.len(), 1);
        let raw: Vec<String> = a.requests.iter().map(|r| r.raw.clone()).collect();
        let again = drive("R1", Style::OpenAI, stream, false).await;
        let raw2: Vec<String> = again.requests.iter().map(|r| r.raw.clone()).collect();
        assert_eq!(raw, raw2);
        assert!(!raw[1].contains("todo"));
    }
}

#[tokio::test]
async fn r6_agent_runtime_forwards_after_round() {
    let seen = agents_support::Scripted::default();
    let generate = seen.generate(|n| agents_support::text(if n == 1 { "first" } else { "second" }));
    let mut registry = HashMap::new();
    let d = agents_support::def("solo", "answers", "", "m-solo");
    registry.insert(d.name.clone(), d);
    let calls = Arc::new(Mutex::new(Vec::new()));
    let rt = Runtime::new(Options {
        in_process: Some(generate),
        registry,
        hooks: Some(Hooks {
            after_round: Some(hook_for("R6", calls.clone())),
            ..Default::default()
        }),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "solo", None).unwrap();
    rt.wake(&h, "go");
    let r = rt.wait(&h, None).await;
    let ex = &scenario("R6")["expect"];
    assert_eq!(seen.n() as u64, ex["requests"].as_u64().unwrap());
    assert_eq!(r.text, ex["text"].as_str().unwrap());
}
