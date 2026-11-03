//! Media generation (SPEC §7G) against the shared a2e.ai preset — both faces:
//! the typed verb (`Media::image`) and the tool face (`Media::tools`, invoked
//! directly). Mirrors golang/examples/media.
//!
//!     A2E_API_KEY=... cargo run --example media
//!
//! Without A2E_API_KEY it prints a hint and exits 0, so hermetic runs never touch
//! the network. The key is read by the preset's `${A2E_API_KEY}` header and is
//! never printed.

use std::path::PathBuf;
use std::sync::Arc;

use serde_json::json;
use toolnexus::{ImageInput, MediaOptions, ToolContext, create_media, load_media_style};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    if std::env::var("A2E_API_KEY").map_or(true, |v| v.is_empty()) {
        println!("set A2E_API_KEY to run the media example (skipping: no network call made)");
        return Ok(());
    }

    let preset =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../examples/media-styles/a2e.json");
    let style = load_media_style(preset)?;
    let media = create_media(
        vec![style],
        MediaOptions {
            on_progress: Some(Arc::new(|p| println!("  ..{} {}", p.verb, p.status))),
            ..Default::default()
        },
    );

    // 1. Typed verb.
    let img = media
        .image(ImageInput {
            prompt: "a red fox in fresh snow, watercolor".into(),
            width: Some(768),
            height: Some(768),
            ..Default::default()
        })
        .await?;
    println!("image status: {}", img.status);
    for f in &img.files {
        println!("  file: {} {} {}", f.kind, f.mime_type, f.url);
    }

    // 2. Tool face — the same engine as plain Tools an LLM would call.
    let tools = media.tools();
    for t in &tools {
        println!("tool: {}", t.name);
    }
    if let Some(speech) = tools.iter().find(|t| t.name == "generate_speech") {
        let res = speech
            .call(
                json!({"text": "Hello from toolnexus."}),
                ToolContext::default(),
            )
            .await;
        println!("speech output: {} isError: {}", res.output, res.is_error);
        for p in &res.parts {
            println!("  part: {}", serde_json::to_string(p)?);
        }
    }
    Ok(())
}
