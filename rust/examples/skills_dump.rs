//! Prints the skill source's observable output as JSON, for cross-port byte
//! comparison (same shape as the Go dump used to verify parity).
use std::collections::BTreeMap;

use serde_json::{Value, json};
use toolnexus::ToolContext;
use toolnexus::skills::load_skills;

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let dir = std::env::args().nth(1).expect("usage: skills_dump <dir>");
    let src = load_skills(&[dir]);
    let mut tools = BTreeMap::new();
    let mut names: Vec<String> = src.skills.keys().cloned().collect();
    names.push("nope".into());
    for n in names {
        let r = src
            .tool
            .call(json!({"name": n}), ToolContext::default())
            .await;
        tools.insert(n, json!({"output": r.output, "isError": r.is_error, "metadata": r.metadata.map(Value::Object).unwrap_or(Value::Null)}));
    }
    let mut skips: Vec<String> = src
        .skipped
        .iter()
        .map(|s| format!("{} {}", s.location, s.reason.as_str()))
        .collect();
    skips.sort();
    println!(
        "{}",
        serde_json::to_string_pretty(
            &json!({"prompt": src.prompt(), "tools": tools, "skipped": skips})
        )
        .unwrap()
    );
}
