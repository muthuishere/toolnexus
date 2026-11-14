//! SPEC §8C "ACP content parts" (openspec/changes/fix-acp-image-parts):
//! outbound media parts travel as native ACP blocks, inbound ACP image blocks
//! become §1B parts and reach `RunResult.files`. Hermetic: the fake agent is
//! examples/fake_acp_agent.rs.

use std::sync::{Arc, Mutex};

use base64::Engine as _;
use serde_json::{Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::acp::{AcpClient, AcpOptions};
use toolnexus::client::{
    InProcessOptions, InProcessRequest, StreamEvent, acp_generate, acp_generate_stream,
    create_in_process_client,
};

/// A 1x1 transparent PNG.
const PNG: [u8; 67] = [
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
    0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae,
    0x42, 0x60, 0x82,
];

fn b64() -> String {
    base64::engine::general_purpose::STANDARD.encode(PNG)
}

mod fake_agent;
use fake_agent::fake;

fn opts(scenario: &str) -> AcpOptions {
    AcpOptions::new(fake().to_string_lossy())
        .args([scenario])
        .cwd(std::env::temp_dir().canonicalize().unwrap())
}

/// One turn whose user message is a text part + an image part (OpenAI chat
/// wire shape, as the in-process seam receives it); returns the recorded
/// session/prompt blocks.
async fn image_prompt(scenario: &str, tag: &str) -> Vec<Value> {
    image_prompt_via(scenario, tag, false).await
}

/// [`image_prompt`], through `acp_generate_stream` when `stream`.
async fn image_prompt_via(scenario: &str, tag: &str, stream: bool) -> Vec<Value> {
    let rec = std::env::temp_dir().join(format!(
        "tn-acp-image-{tag}-{}-{}.jsonl",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let c = Arc::new(
        AcpClient::load(opts(scenario).env("TN_FAKE_RECORD", rec.to_string_lossy()))
            .await
            .unwrap(),
    );
    let req = InProcessRequest {
        messages: vec![json!({"role": "user", "content": [
            {"type": "text", "text": "what is in this picture?"},
            {"type": "image_url", "image_url": {"url": format!("data:image/png;base64,{}", b64())}},
        ]})],
        ..Default::default()
    };
    if stream {
        acp_generate_stream(c.clone())(CancellationToken::new(), req, Arc::new(|_| {}))
            .await
            .unwrap();
    } else {
        acp_generate(c.clone())(req).await.unwrap();
    }
    c.close().await;
    let prompt = std::fs::read_to_string(&rec)
        .unwrap()
        .lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .find(|v| v["method"] == "session/prompt")
        .expect("fake agent recorded no session/prompt")["params"]["prompt"]
        .clone();
    let _ = std::fs::remove_file(&rec);
    prompt.as_array().cloned().unwrap_or_default()
}

fn split_blocks(blocks: &[Value]) -> (String, Vec<Value>) {
    let mut text = String::new();
    let mut images = Vec::new();
    for b in blocks {
        match b["type"].as_str() {
            Some("text") => text.push_str(b["text"].as_str().unwrap_or("")),
            Some("image") => images.push(b.clone()),
            _ => {}
        }
    }
    (text, images)
}

#[tokio::test]
async fn image_part_sent_as_image_block() {
    let (text, images) = split_blocks(&image_prompt("image", "adv").await);
    assert_eq!(
        images.len(),
        1,
        "expected 1 ACP image block (text={text:?})"
    );
    assert_eq!(images[0]["mimeType"], "image/png");
    assert_eq!(images[0]["data"], b64());
    assert!(!text.contains(&b64()), "base64 leaked into a text block");
    assert!(text.contains("what is in this picture?"), "{text:?}");
}

#[tokio::test]
async fn streaming_sends_the_same_image_block() {
    let (text, images) = split_blocks(&image_prompt_via("image", "stream", true).await);
    assert_eq!(
        images.len(),
        1,
        "stream must send the image block (text={text:?})"
    );
    assert_eq!(images[0]["data"], b64());
    assert!(!text.contains(&b64()), "base64 leaked into a text block");
}

#[tokio::test]
async fn image_part_placeholder_without_capability() {
    let (text, images) = split_blocks(&image_prompt("echo", "noadv").await);
    assert!(images.is_empty(), "agent did not advertise image");
    assert!(!text.contains(&b64()), "base64 leaked into a text block");
    assert!(text.contains("image (image/png, 67 bytes)"), "{text:?}");
}

#[tokio::test]
async fn inbound_image_blocks_become_parts() {
    let c = Arc::new(AcpClient::load(opts("image_out")).await.unwrap());
    let results: Arc<Mutex<Vec<StreamEvent>>> = Arc::default();
    let r2 = results.clone();
    let req = InProcessRequest {
        messages: vec![json!({"role": "user", "content": "shoot"})],
        ..Default::default()
    };
    let resp = acp_generate_stream(c.clone())(
        CancellationToken::new(),
        req,
        Arc::new(move |ev: StreamEvent| {
            if ev.kind == "tool_result" {
                r2.lock().unwrap().push(ev);
            }
        }),
    )
    .await
    .unwrap();
    c.close().await;
    let results = results.lock().unwrap();
    assert_eq!(results.len(), 1);
    assert_eq!(results[0].output, "image (image/png, 67 bytes)");
    assert_eq!(results[0].parts.len(), 1);
    let p = &results[0].parts[0];
    assert_eq!(
        (p.kind.as_str(), p.mime_type.as_str(), p.data.as_str()),
        ("image", "image/png", b64().as_str())
    );
    assert_eq!(resp.content, "here", "base64 must never enter text");
    assert_eq!(resp.files.len(), 2, "{:?}", resp.files);
    let f = &resp.files[0];
    assert_eq!(
        (f.from.as_str(), f.call_id.as_str(), f.kind.as_str()),
        ("screenshot", "t1", "image")
    );
    assert_eq!(f.data, b64());
    let f = &resp.files[1];
    assert_eq!(
        (f.from.as_str(), f.call_id.as_str(), f.mime_type.as_str()),
        ("acp", "", "image/png")
    );
    assert_eq!(f.data, b64());
}

#[tokio::test]
async fn inbound_image_reaches_run_result_files() {
    let c = Arc::new(AcpClient::load(opts("image_out")).await.unwrap());
    let client = create_in_process_client(InProcessOptions {
        model: "acp".into(),
        generate_stream: Some(acp_generate_stream(c.clone())),
        ..Default::default()
    });
    let tk = toolnexus::create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let res = client.run("shoot", &tk).await.unwrap();
    assert_eq!(res.text, "here");
    assert_eq!(res.files.len(), 2, "{:?}", res.files);
    assert_eq!(res.files[0].from, "screenshot");
    assert_eq!(res.files[0].data, b64());
    assert_eq!(res.files[1].from, "acp");
    assert_eq!(res.tool_call_count, 0);

    let mut rx = client.stream("shoot", &tk).unwrap();
    let mut done = None;
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "done" => done = ev.result,
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    let done = done.expect("no done event");
    assert_eq!(done.files.len(), 2, "{:?}", done.files);
    assert_eq!(done.files[0].call_id, "t1");
    c.close().await;
}
