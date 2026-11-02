//! Single-turn translation (SPEC §11, ADR-0011): mirrors golang/translate_test.go.
//! Hermetic: a loopback capturing upstream, no network, no key.

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use common::*;
use serde_json::{Value, json};
use toolnexus::client::*;
use toolnexus::{Tool, create_toolkit, define_tool};

/// A server that records the last request body and answers `reply`.
async fn capturing(reply: Value) -> (Server, Arc<Mutex<Value>>) {
    let sent = Arc::new(Mutex::new(Value::Null));
    let s2 = sent.clone();
    let srv = serve(move |r: Req| {
        let s = s2.clone();
        let reply = reply.clone();
        async move {
            *s.lock().unwrap() = r.body;
            Resp::json(reply)
        }
    })
    .await;
    (srv, sent)
}

fn client(url: &str, style: Style) -> Client {
    create_client(ClientOptions {
        base_url: url.into(),
        style,
        model: "stub".into(),
        api_key: "k".into(),
        ..Default::default()
    })
}

fn openai_tools() -> Vec<Value> {
    vec![json!({
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get the weather",
            "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}
        }
    })]
}

fn user(s: &str) -> Value {
    json!({"role": "user", "content": s})
}

fn req(messages: Vec<Value>, tools: Vec<Value>) -> TranslateRequest {
    TranslateRequest {
        messages,
        tools,
        ..Default::default()
    }
}

async fn tk_with(t: Tool) -> toolnexus::Toolkit {
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![t],
        ..Default::default()
    })
    .await
    .unwrap()
}

fn counting_tool(name: &str, n: Arc<AtomicUsize>) -> Tool {
    define_tool(name, "must not run", None, move |_a: Value, _| {
        let n = n.clone();
        async move {
            n.fetch_add(1, Ordering::SeqCst);
            Ok::<_, String>("RAN".to_string())
        }
    })
}

#[tokio::test]
async fn tool_call_to_openai_shape() {
    let (srv, sent) = capturing(json!({
        "content": [{"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"city": "Chennai"}}],
        "stop_reason": "tool_use",
        "usage": {"input_tokens": 10, "output_tokens": 5}
    }))
    .await;
    let res = client(&srv.url, Style::Anthropic)
        .translate(req(vec![user("weather in Chennai?")], openai_tools()))
        .await
        .unwrap();
    assert_eq!(res.finish_reason, "tool_calls");
    assert_eq!(res.tool_calls.len(), 1);
    let tc = &res.tool_calls[0];
    assert_eq!(
        (tc.id.as_str(), tc.name.as_str()),
        ("toolu_1", "get_weather")
    );
    let args: Value = serde_json::from_str(&tc.arguments).unwrap();
    assert_eq!(args["city"], "Chennai");
    assert!(res.usage.total_tokens > 0);
    let s = sent.lock().unwrap().to_string();
    for w in ["\"input_schema\"", "get_weather", "Get the weather"] {
        assert!(s.contains(w), "missing {w}: {s}");
    }
    assert!(!s.contains("\"parameters\""), "{s}");
}

#[tokio::test]
async fn multi_turn_tool_result_survives() {
    let (srv, sent) = capturing(json!({
        "content": [{"type": "text", "text": "It is 31C in Chennai."}],
        "stop_reason": "end_turn",
        "usage": {"input_tokens": 20, "output_tokens": 8}
    }))
    .await;
    let res = client(&srv.url, Style::Anthropic)
        .translate(req(
            vec![
                json!({"role": "system", "content": "Be terse."}),
                user("weather in Chennai?"),
                json!({"role": "assistant", "content": null, "tool_calls": [
                    {"id": "call_abc", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"Chennai\"}"}}
                ]}),
                json!({"role": "tool", "tool_call_id": "call_abc", "content": "31C, clear"}),
            ],
            openai_tools(),
        ))
        .await
        .unwrap();
    assert_eq!(res.finish_reason, "stop");
    assert_eq!(res.text, "It is 31C in Chennai.");
    let body = sent.lock().unwrap().clone();
    assert_eq!(body["system"], "Be terse.");
    let s = body.to_string();
    for w in ["\"tool_use\"", "\"tool_result\"", "call_abc", "31C, clear"] {
        assert!(s.contains(w), "lost {w}: {s}");
    }
    let mut found = false;
    for m in body["messages"].as_array().unwrap() {
        for b in m["content"].as_array().into_iter().flatten() {
            if b["type"] == "tool_use" {
                assert_eq!(b["input"]["city"], "Chennai");
                found = true;
            }
        }
    }
    assert!(found);
}

#[tokio::test]
async fn consecutive_tool_results_merge_into_one_user_turn() {
    let (srv, sent) = capturing(
        json!({"content": [{"type": "text", "text": "done"}], "stop_reason": "end_turn"}),
    )
    .await;
    let f = |id: &str| json!({"id": id, "function": {"name": "f", "arguments": "{}"}});
    client(&srv.url, Style::Anthropic)
        .translate(req(
            vec![
                user("do three things"),
                json!({"role": "assistant", "tool_calls": [f("a"), f("b"), f("c")]}),
                json!({"role": "tool", "tool_call_id": "a", "content": "ra"}),
                json!({"role": "tool", "tool_call_id": "b", "content": "rb"}),
                json!({"role": "tool", "tool_call_id": "c", "content": "rc"}),
            ],
            vec![],
        ))
        .await
        .unwrap();
    let body = sent.lock().unwrap().clone();
    let (mut turns, mut blocks) = (0, 0);
    for m in body["messages"].as_array().unwrap() {
        let n = m["content"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|b| b["type"] == "tool_result")
            .count();
        if n > 0 {
            turns += 1;
            blocks = n;
        }
    }
    assert_eq!((turns, blocks), (1, 3));
    assert_eq!(body.to_string().matches("\"tool_use\"").count(), 3);
}

#[tokio::test]
async fn parallel_tool_calls_all_returned() {
    let (srv, _) = capturing(json!({
        "content": [
            {"type": "text", "text": "calling three"},
            {"type": "tool_use", "id": "t1", "name": "alpha", "input": {"n": 1}},
            {"type": "tool_use", "id": "t2", "name": "beta", "input": {"n": 2}},
            {"type": "tool_use", "id": "t3", "name": "gamma", "input": {"n": 3}}
        ],
        "stop_reason": "tool_use"
    }))
    .await;
    let res = client(&srv.url, Style::Anthropic)
        .translate(req(vec![user("go")], openai_tools()))
        .await
        .unwrap();
    let names: Vec<_> = res.tool_calls.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names, ["alpha", "beta", "gamma"]);
    assert_eq!(res.text, "calling three");
    assert_eq!(res.finish_reason, "tool_calls");
    let arr = res.tool_calls_json();
    assert_eq!(arr.len(), 3);
    assert_eq!(arr[0]["type"], "function");
}

#[tokio::test]
async fn executes_nothing_and_keeps_no_state() {
    let (srv, sent) = capturing(json!({
        "content": [{"type": "tool_use", "id": "t1", "name": "danger", "input": {}}],
        "stop_reason": "tool_use"
    }))
    .await;
    let n = Arc::new(AtomicUsize::new(0));
    let _tk = tk_with(counting_tool("danger", n.clone())).await; // never given to translate
    let c = client(&srv.url, Style::Anthropic);
    for _ in 0..3 {
        let res = c
            .translate(req(vec![user("go")], openai_tools()))
            .await
            .unwrap();
        assert_eq!(res.tool_calls.len(), 1);
        assert_eq!(res.tool_calls[0].name, "danger");
    }
    assert_eq!(n.load(Ordering::SeqCst), 0);
    assert_eq!(
        sent.lock().unwrap()["messages"].as_array().unwrap().len(),
        1
    );
}

#[tokio::test]
async fn tool_choice_mapping() {
    let cases: Vec<(Option<Value>, &str)> = vec![
        (None, ""),
        (Some(json!("auto")), ""),
        (Some(json!("required")), "\"type\":\"any\""),
        (Some(json!("none")), "\"type\":\"none\""),
        (
            Some(json!({"type": "function", "function": {"name": "get_weather"}})),
            "\"name\":\"get_weather\"",
        ),
    ];
    for (input, want) in cases {
        let (srv, sent) = capturing(
            json!({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"}),
        )
        .await;
        let mut r = req(vec![user("go")], openai_tools());
        r.tool_choice = input.clone();
        client(&srv.url, Style::Anthropic)
            .translate(r)
            .await
            .unwrap();
        let body = sent.lock().unwrap().clone();
        let tc = body.get("tool_choice");
        if want.is_empty() {
            assert!(tc.is_none(), "{input:?} should be omitted: {body}");
        } else {
            assert!(tc.unwrap().to_string().contains(want), "{input:?}: {body}");
        }
    }
}

#[tokio::test]
async fn finish_reason_mapping() {
    for (stop, want) in [
        ("end_turn", "stop"),
        ("max_tokens", "length"),
        ("refusal", "content_filter"),
        ("stop_sequence", "stop"),
    ] {
        let (srv, _) =
            capturing(json!({"content": [{"type": "text", "text": "x"}], "stop_reason": stop}))
                .await;
        let res = client(&srv.url, Style::Anthropic)
            .translate(req(vec![user("go")], vec![]))
            .await
            .unwrap();
        assert_eq!(res.finish_reason, want, "{stop}");
    }
}

#[tokio::test]
async fn openai_upstream_passes_tools_through() {
    let (srv, sent) = capturing(json!({
        "choices": [{"message": {"content": "", "tool_calls": [
            {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"Madurai\"}"}}
        ]}, "finish_reason": "tool_calls"}],
        "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}
    }))
    .await;
    let res = client(&srv.url, Style::OpenAI)
        .translate(req(vec![user("weather?")], openai_tools()))
        .await
        .unwrap();
    assert_eq!(res.finish_reason, "tool_calls");
    assert_eq!(res.tool_calls.len(), 1);
    assert_eq!(res.tool_calls[0].arguments, "{\"city\":\"Madurai\"}");
    assert_eq!(res.usage.total_tokens, 7);
    assert!(sent.lock().unwrap().to_string().contains("\"parameters\""));
}

#[tokio::test]
async fn openai_endpoint_inserts_v1() {
    let path = Arc::new(Mutex::new(String::new()));
    let p2 = path.clone();
    let srv = serve(move |r: Req| {
        let p = p2.clone();
        async move {
            *p.lock().unwrap() = r.path;
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    client(&srv.url, Style::OpenAI)
        .translate(req(vec![user("hi")], vec![]))
        .await
        .unwrap();
    assert_eq!(*path.lock().unwrap(), "/v1/chat/completions");
    client(&format!("{}/v1", srv.url), Style::OpenAI)
        .translate(req(vec![user("hi")], vec![]))
        .await
        .unwrap();
    assert_eq!(*path.lock().unwrap(), "/v1/chat/completions");
}

#[tokio::test]
async fn accepts_struct_messages() {
    #[derive(serde::Serialize)]
    struct Msg {
        role: String,
        content: String,
    }
    let (srv, sent) =
        capturing(json!({"content": [{"type": "text", "text": "hi"}], "stop_reason": "end_turn"}))
            .await;
    let m = serde_json::to_value(Msg {
        role: "user".into(),
        content: "hello".into(),
    })
    .unwrap();
    let res = client(&srv.url, Style::Anthropic)
        .translate(req(vec![m], vec![]))
        .await
        .unwrap();
    assert_eq!(res.text, "hi");
    assert!(sent.lock().unwrap().to_string().contains("hello"));
}

#[tokio::test]
async fn content_parts_array_flattens() {
    let (srv, sent) =
        capturing(json!({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"}))
            .await;
    client(&srv.url, Style::Anthropic)
        .translate(req(
            vec![json!({"role": "user", "content": [
                {"type": "text", "text": "part one "},
                {"type": "text", "text": "part two"}
            ]})],
            vec![],
        ))
        .await
        .unwrap();
    assert!(
        sent.lock()
            .unwrap()
            .to_string()
            .contains("part one part two")
    );
}

#[tokio::test]
async fn image_url_part_becomes_anthropic_image_block() {
    let (srv, sent) =
        capturing(json!({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"}))
            .await;
    client(&srv.url, Style::Anthropic)
        .translate(req(
            vec![json!({"role": "user", "content": [
                {"type": "text", "text": "what is "},
                {"type": "text", "text": "this?"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}}
            ]})],
            vec![],
        ))
        .await
        .unwrap();
    let body = sent.lock().unwrap().clone();
    assert_eq!(
        body["messages"][0]["content"],
        json!([
            {"type": "text", "text": "what is this?"},
            {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBORw0KGgo="}}
        ])
    );
}

#[tokio::test]
async fn declares_a_toolkit_without_executing_it() {
    let (srv, sent) = capturing(json!({
        "content": [{"type": "tool_use", "id": "tu_9", "name": "my_native_tool", "input": {"x": 1}}],
        "stop_reason": "tool_use"
    }))
    .await;
    let n = Arc::new(AtomicUsize::new(0));
    let tk = tk_with(counting_tool("my_native_tool", n.clone())).await;
    let res = client(&srv.url, Style::Anthropic)
        .translate(TranslateRequest {
            messages: vec![user("use the tool")],
            toolkit: Some(tk),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(n.load(Ordering::SeqCst), 0);
    assert_eq!(res.tool_calls.len(), 1);
    assert_eq!(res.tool_calls[0].name, "my_native_tool");
    assert_eq!(res.tool_calls[0].id, "tu_9");
    let s = sent.lock().unwrap().to_string();
    assert!(
        s.contains("\"input_schema\"") && s.contains("my_native_tool"),
        "{s}"
    );
}

#[tokio::test]
async fn composes_toolkit_and_openai_tools() {
    let (srv, sent) =
        capturing(json!({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"}))
            .await;
    let tk = tk_with(counting_tool(
        "server_side_tool",
        Arc::new(AtomicUsize::new(0)),
    ))
    .await;
    client(&srv.url, Style::Anthropic)
        .translate(TranslateRequest {
            messages: vec![user("go")],
            toolkit: Some(tk),
            tools: openai_tools(),
            ..Default::default()
        })
        .await
        .unwrap();
    let body = sent.lock().unwrap().clone();
    let names: Vec<_> = body["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["name"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(names, ["server_side_tool", "get_weather"]); // toolkit first
}

#[tokio::test]
async fn before_llm_error_stops_the_call() {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        n2.fetch_add(1, Ordering::SeqCst);
        async { Resp::json(oa_text("ok")) }
    })
    .await;
    let mut o = ClientOptions {
        base_url: srv.url.clone(),
        model: "stub".into(),
        api_key: "k".into(),
        ..Default::default()
    };
    o.hooks.before_llm = Some(Arc::new(|_| Box::pin(async { Err("nope".to_string()) })));
    let err = create_client(o)
        .translate(req(vec![user("hi")], vec![]))
        .await
        .unwrap_err();
    assert!(matches!(err, ClientError::Hook(ref s) if s == "nope"));
    assert_eq!(n.load(Ordering::SeqCst), 0);
}
