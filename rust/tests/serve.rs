//! A2A inbound (SPEC §7B): mirrors golang/serve_test.go. `Toolkit::serve` on an
//! ephemeral port with a loopback mock LLM; the headline test drives the served
//! toolkit with this port's OWN outbound A2A client.
#![cfg(feature = "serve")]

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use common::*;
use serde_json::{Value, json};
use toolnexus::a2a::{A2aArtifact, A2aPart, A2aTask, A2aTaskStatus, Agent, agent_tools};
use toolnexus::client::{ClientOptions, create_client};
use toolnexus::serve::*;
use toolnexus::{BuiltinsConfig, ToolContext, ToolkitOptions, create_toolkit};

fn skills_dir() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/skills")
}

async fn skills_tk(builtins: bool) -> toolnexus::Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: if builtins {
            None
        } else {
            Some(BuiltinsConfig::off())
        },
        skills_dir: vec![skills_dir()],
        ..Default::default()
    })
    .await
    .unwrap()
}

fn client(url: &str) -> toolnexus::client::Client {
    create_client(ClientOptions {
        base_url: url.into(),
        model: "x".into(),
        api_key: "k".into(),
        retry_base_ms: 1,
        ..Default::default()
    })
}

async fn mock_llm(content: &'static str) -> Server {
    serve(move |_| async move { Resp::json(oa_text(content)) }).await
}

async fn rpc(url: &str, method: &str, params: Value) -> Value {
    reqwest::Client::new()
        .post(url)
        .json(&json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap()
}

async fn get_card(base: &str) -> (u16, String) {
    let r = reqwest::get(format!("{base}{CARD_PATH}")).await.unwrap();
    (r.status().as_u16(), r.text().await.unwrap())
}

fn send_params(text: &str) -> Value {
    json!({"message": {"parts": [{"kind": "text", "text": text}]}})
}

/// Poll GetTask until the state leaves submitted/working.
async fn poll_terminal(endpoint: &str, id: &str) -> Value {
    for _ in 0..300 {
        let got = rpc(endpoint, "GetTask", json!({"id": id})).await;
        let res = got["result"].clone();
        assert!(res.is_object(), "GetTask error: {got}");
        let st = res["status"]["state"].as_str().unwrap_or("").to_owned();
        if !st.is_empty() && st != "submitted" && st != "working" {
            return res;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("task never reached a terminal state");
}

#[tokio::test]
async fn a2a_round_trip_through_own_outbound_client() {
    let llm = mock_llm("TRANSCRIBED").await;
    let tk = skills_tk(false).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client(&llm.url)),
                a2a: Some(A2aConfig {
                    name: "video-desk".into(),
                    skills: Some(vec!["hello-world".into()]),
                    ..Default::default()
                }),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let tools = agent_tools(&Agent {
        card: format!("{}{CARD_PATH}", h.url),
        poll_every_ms: 5,
        ..Default::default()
    })
    .await
    .unwrap();
    let names: Vec<_> = tools.iter().map(|t| t.name.clone()).collect();
    let hello = tools
        .iter()
        .find(|t| t.name == "video-desk_hello-world")
        .unwrap_or_else(|| panic!("got {names:?}"));
    let r = hello
        .call(json!({"task": "do it"}), ToolContext::default())
        .await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, "TRANSCRIBED");
    assert_eq!(r.metadata.unwrap()["state"], "completed");
}

#[tokio::test]
async fn card_shape_advertises_skills_not_tools() {
    let tk = skills_tk(true).await;
    assert!(tk.get("bash").is_some());
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client("http://unused")),
                a2a: Some(A2aConfig::default()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let (status, body) = get_card(&h.url).await;
    assert_eq!(status, 200);
    let card: Value = serde_json::from_str(&body).unwrap();
    assert_eq!(card["name"], "toolnexus-agent");
    assert_eq!(card["description"], "");
    assert_eq!(card["version"], "0.1.0");
    assert_eq!(card["protocolVersion"], "0.3.0");
    assert_eq!(card["url"], format!("{}/", h.url));
    assert!(card.get("provider").is_none());
    assert_eq!(
        card["capabilities"],
        json!({"streaming": false, "pushNotifications": false})
    );
    let skills: Vec<&str> = card["skills"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["name"].as_str().unwrap())
        .collect();
    assert!(skills.contains(&"hello-world"));
    for raw in ["bash", "read", "skill"] {
        assert!(!skills.contains(&raw), "raw tool {raw} on the card");
    }
    // Byte-exact key order (JS / Go reference).
    assert!(body.starts_with(
        "{\"name\":\"toolnexus-agent\",\"description\":\"\",\"version\":\"0.1.0\",\"protocolVersion\":\"0.3.0\",\"capabilities\":{\"streaming\":false,\"pushNotifications\":false},\"defaultInputModes\":[\"text\"],\"defaultOutputModes\":[\"text\"],\"skills\":["
    ), "{body}");
}

#[tokio::test]
async fn provider_included_when_set() {
    let tk = skills_tk(false).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client("http://unused")),
                a2a: Some(A2aConfig {
                    provider: Some(A2aProvider {
                        organization: "acme".into(),
                        url: "https://acme.example".into(),
                    }),
                    ..Default::default()
                }),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let card: Value = serde_json::from_str(&get_card(&h.url).await.1).unwrap();
    assert_eq!(
        card["provider"],
        json!({"organization": "acme", "url": "https://acme.example"})
    );
}

#[tokio::test]
async fn no_profile_is_404() {
    let tk = empty_toolkit().await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client("http://unused")),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    assert_eq!(get_card(&h.url).await.0, 404);
}

#[tokio::test]
async fn fulfilment_error_fails_task_and_server_survives() {
    let llm = serve(|_| async { Resp::status(500, r#"{"error":"boom"}"#) }).await;
    let tk = skills_tk(false).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client(&llm.url)),
                a2a: Some(A2aConfig::default()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let endpoint = format!("{}/", h.url);
    let sent = rpc(&endpoint, "SendMessage", send_params("do it")).await;
    let id = sent["result"]["id"].as_str().unwrap().to_owned();
    assert!(!id.is_empty());
    assert_eq!(sent["result"]["status"]["state"], "submitted");
    let task = poll_terminal(&endpoint, &id).await;
    assert_eq!(task["status"]["state"], "failed");
    assert_eq!(get_card(&h.url).await.0, 200);
}

#[tokio::test]
async fn suspended_run_is_input_required() {
    let args = r#"{"questions":[{"question":"Pick a color?","options":["red","green"]}]}"#;
    let llm =
        serve(move |_| async move { Resp::json(oa_calls(&[("c1", "question", args)])) }).await;
    let tk = create_toolkit(ToolkitOptions::default()).await.unwrap(); // builtins ⇒ `question`
    let events: Arc<Mutex<Vec<OnTaskEvent>>> = Arc::default();
    let ev2 = events.clone();
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client(&llm.url)),
                a2a: Some(A2aConfig {
                    name: "ask-desk".into(),
                    ..Default::default()
                }),
                on_task: Some(Arc::new(move |ev| ev2.lock().unwrap().push(ev))),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let endpoint = format!("{}/", h.url);
    let sent = rpc(&endpoint, "SendMessage", send_params("go")).await;
    let id = sent["result"]["id"].as_str().unwrap().to_owned();
    let task = poll_terminal(&endpoint, &id).await;
    assert_eq!(task["status"]["state"], "input-required");
    assert_eq!(
        task["status"]["message"]["parts"][0]["text"],
        "Pick a color? (options: red, green)"
    );
    assert!(task.get("artifacts").is_none());
    tokio::time::sleep(Duration::from_millis(20)).await;
    let evs = events.lock().unwrap();
    assert_eq!(evs.last().map(|e| e.state.as_str()), Some("input-required"));
}

#[tokio::test]
async fn json_rpc_error_codes() {
    let llm = mock_llm("ok").await;
    let tk = skills_tk(false).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client(&llm.url)),
                a2a: Some(A2aConfig::default()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let endpoint = format!("{}/", h.url);
    let got = rpc(&endpoint, "Nope", json!({})).await;
    assert_eq!(got["error"]["code"], -32601);
    assert_eq!(got["error"]["message"], "Method not found: Nope");
    assert_eq!(got["id"], 1);
    let got = rpc(&endpoint, "GetTask", json!({"id": "does-not-exist"})).await;
    assert_eq!(got["error"]["code"], -32001);
    assert_eq!(got["error"]["message"], "Task not found: does-not-exist");
    let raw = reqwest::Client::new()
        .post(&endpoint)
        .body("{not json")
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    assert_eq!(
        raw.trim_end(),
        r#"{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"#
    );
}

#[derive(Default)]
struct CountingStore {
    inner: InMemoryTaskStore,
    saves: AtomicUsize,
    gets: AtomicUsize,
}

impl TaskStore for CountingStore {
    fn get(&self, id: &str) -> Result<Option<A2aTask>, String> {
        self.gets.fetch_add(1, Ordering::SeqCst);
        self.inner.get(id)
    }
    fn save(&self, task: A2aTask) -> Result<(), String> {
        self.saves.fetch_add(1, Ordering::SeqCst);
        self.inner.save(task)
    }
}

#[tokio::test]
async fn custom_store_receives_every_write() {
    let llm = mock_llm("DONE").await;
    let tk = skills_tk(false).await;
    let store = Arc::new(CountingStore::default());
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client(&llm.url)),
                a2a: Some(A2aConfig {
                    store: TaskStoreSpec::Custom(store.clone()),
                    ..Default::default()
                }),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let endpoint = format!("{}/", h.url);
    let sent = rpc(&endpoint, "SendMessage", send_params("go")).await;
    let id = sent["result"]["id"].as_str().unwrap().to_owned();
    let task = poll_terminal(&endpoint, &id).await;
    assert_eq!(task["status"]["state"], "completed");
    assert_eq!(task["artifacts"][0]["parts"][0]["text"], "DONE");
    assert!(store.saves.load(Ordering::SeqCst) >= 3);
    assert!(store.gets.load(Ordering::SeqCst) >= 1);
}

#[tokio::test]
async fn context_id_remembers_the_conversation() {
    let seen: Arc<Mutex<Vec<usize>>> = Arc::default();
    let s2 = seen.clone();
    let llm = serve(move |r: Req| {
        let s2 = s2.clone();
        async move {
            s2.lock().unwrap().push(r.messages().len());
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    let tk = skills_tk(false).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(client(&llm.url)),
                a2a: Some(A2aConfig::default()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let endpoint = format!("{}/", h.url);
    for text in ["one", "two"] {
        let p =
            json!({"message": {"contextId": "ctx-1", "parts": [{"kind": "text", "text": text}]}});
        let sent = rpc(&endpoint, "SendMessage", p).await;
        assert_eq!(sent["result"]["contextId"], "ctx-1");
        let id = sent["result"]["id"].as_str().unwrap().to_owned();
        poll_terminal(&endpoint, &id).await;
    }
    let lens = seen.lock().unwrap().clone();
    assert_eq!(lens.len(), 2);
    assert!(lens[1] > lens[0], "second turn carries history: {lens:?}");
}

#[tokio::test]
async fn top_level_a2a_config_block() {
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        skills_dir: vec![skills_dir()],
        mcp_config: Some(toolnexus::McpConfigInput::Json(
            json!({"a2a": {"name": "from-config"}}),
        )),
        ..Default::default()
    })
    .await
    .unwrap();
    let h = tk
        .serve("127.0.0.1:0", ServeOptions::default())
        .await
        .unwrap();
    let card: Value = serde_json::from_str(&get_card(&h.url).await.1).unwrap();
    assert_eq!(card["name"], "from-config");
}

#[tokio::test]
async fn stop_closes_the_listener() {
    let tk = skills_tk(false).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                a2a: Some(A2aConfig::default()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    assert_eq!(get_card(&h.url).await.0, 200);
    h.stop();
    h.stop();
    tokio::time::sleep(Duration::from_millis(20)).await;
    assert!(reqwest::get(format!("{}{CARD_PATH}", h.url)).await.is_err());
}

#[test]
fn file_task_store_round_trip() {
    let dir = std::env::temp_dir().join(format!("tn-a2a-{}", std::process::id()));
    let store = FileTaskStore::new(&dir);
    let task = A2aTask {
        id: "task-123".into(),
        status: A2aTaskStatus {
            state: "completed".into(),
            message: None,
        },
        artifacts: vec![A2aArtifact {
            artifact_id: "art-1".into(),
            parts: vec![A2aPart::text("hi there")],
        }],
        ..Default::default()
    };
    store.save(task.clone()).unwrap();
    assert_eq!(store.get("task-123").unwrap(), Some(task));
    assert_eq!(store.get("nope").unwrap(), None);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn resolve_store_selectors() {
    assert!(resolve_store(&TaskStoreSpec::default()).is_ok());
    assert!(resolve_store(&TaskStoreSpec::Selector("memory".into())).is_ok());
    let dir = std::env::temp_dir().join(format!("tn-a2a-sel-{}", std::process::id()));
    assert!(resolve_store(&TaskStoreSpec::Selector(format!("file:{}", dir.display()))).is_ok());
    let custom: Arc<dyn TaskStore> = Arc::new(InMemoryTaskStore::new());
    let got = resolve_store(&TaskStoreSpec::Custom(custom.clone())).unwrap();
    assert!(Arc::ptr_eq(&got, &custom));
    let err = resolve_store(&TaskStoreSpec::Selector("bogus".into()))
        .err()
        .unwrap();
    assert!(err.contains("unknown A2A store"));
    let _ = std::fs::remove_dir_all(dir);
}
