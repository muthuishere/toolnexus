//! stdio transport (port of stdio_test.go). The `spike serve-stdio` binary is the test server.
use std::sync::{Arc, Mutex};

use mcp2026::{Client, run_checks_with, text};
use serde_json::{Value, json};
use tokio::process::Command;

fn server_cmd() -> Command {
    let mut c = Command::new(env!("CARGO_BIN_EXE_spike"));
    c.arg("serve-stdio");
    c
}

#[tokio::test(flavor = "multi_thread")]
async fn stdio_same_eight_steps() {
    let sink = Arc::new(Mutex::new(String::new()));
    assert!(
        run_checks_with(|| Client::stdio_capture(server_cmd(), sink.clone()).unwrap()).await,
        "stdio checks failed"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn stdio_stderr_captured_not_parsed_and_concurrent() {
    let sink = Arc::new(Mutex::new(String::new()));
    let c = Client::stdio_capture(server_cmd(), sink.clone()).unwrap();
    let mut tasks = vec![];
    for i in 0..20 {
        let c = c.clone();
        tasks.push(tokio::spawn(async move {
            let want = format!("msg-{i}");
            let r = c
                .call_tool("echo", json!({"text": want}), None)
                .await
                .unwrap();
            assert_eq!(text(&r), want);
        }));
    }
    for t in tasks {
        t.await.unwrap();
    }
    c.close().await;
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert!(
        sink.lock().unwrap().contains("stdio-test-server ready"),
        "stderr not captured: {:?}",
        sink.lock().unwrap()
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn stdio_server_exit_is_an_error() {
    let mut cmd = Command::new("sh");
    cmd.args(["-c", "exit 3"]);
    let c = Client::stdio(cmd).unwrap();
    assert!(
        c.call("tools/list", Value::Null).await.is_err(),
        "want error from an exited server"
    );
}
