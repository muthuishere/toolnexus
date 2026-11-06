// One CLI process per turn: the whole request goes in on stdin, the reply comes back on stdout.
use std::collections::HashMap;
use std::io::Write;
use std::process::{Command, Stdio};
use std::sync::Arc;

use serde_json::{Value, json};
use toolnexus::client::{
    Client, InProcessOptions, InProcessRequest, InProcessResponse, InProcessToolCall,
    create_in_process_client,
};

// Render the whole request as one prompt: tools, transcript, and the reply contract.
fn render(req: &InProcessRequest) -> String {
    let mut lines = vec!["You are the model inside a tool-calling loop. Tools you may call:".to_string()];
    for t in &req.tools {
        let f = &t["function"];
        lines.push(format!("- {}: {} args={}", f["name"].as_str().unwrap_or_default(),
            f["description"].as_str().unwrap_or_default(), f["parameters"]));
    }
    lines.extend(["".into(), "Conversation:".into()]);
    let mut called: HashMap<String, String> = HashMap::new(); // tool_call_id -> tool name
    for m in &req.messages {
        if m["role"] == "tool" {
            let id = m["tool_call_id"].as_str().unwrap_or_default();
            let name = called.get(id).map_or(id, String::as_str);
            lines.push(format!("[tool {name}] {}", text(&m["content"])));
        } else if let Some(calls) = m["tool_calls"].as_array() {
            for c in calls {
                called.insert(text(&c["id"]), text(&c["function"]["name"]));
            }
            let fns: Vec<&Value> = calls.iter().map(|c| &c["function"]).collect();
            lines.push(format!("[assistant called] {}", json!(fns)));
        } else {
            lines.push(format!("[{}] {}", text(&m["role"]), text(&m["content"])));
        }
    }
    lines.extend(["".into(),
        r#"Reply with ONLY one JSON object: {"tool_calls":[{"name":"...","arguments":{...}}]} to call tools,"#.into(),
        r#"or {"content":"..."} with the final answer. No prose, no code fences."#.into()]);
    lines.join("\n")
}

fn text(v: &Value) -> String {
    v.as_str().map_or_else(|| v.to_string(), String::from)
}

// Pull the outermost {...} out of the reply, tolerating fences or chatter around it.
fn parse(out: &str) -> InProcessResponse {
    let json = match (out.find('{'), out.rfind('}')) {
        (Some(a), Some(b)) if b > a => &out[a..=b],
        _ => "",
    };
    if let Ok(r) = serde_json::from_str::<Value>(json) {
        if let Some(calls) = r["tool_calls"].as_array().filter(|c| !c.is_empty()) {
            let tool_calls = calls.iter().map(|c| InProcessToolCall {
                id: String::new(),
                name: text(&c["name"]),
                arguments: if c["arguments"].is_null() { json!({}) } else { c["arguments"].clone() },
            }).collect();
            return InProcessResponse { tool_calls, ..Default::default() };
        }
        if let Some(content) = r["content"].as_str() {
            return InProcessResponse { content: content.into(), ..Default::default() };
        }
    }
    InProcessResponse { content: out.trim().into(), ..Default::default() } // not our contract: it is the answer
}

pub fn one_shot_client(command: String, args: Vec<String>) -> Client {
    let model = command.clone();
    let generate = Arc::new(move |req: InProcessRequest| {
        let (command, args) = (command.clone(), args.clone());
        Box::pin(async move {
            // spawn_blocking: the child process blocks; keep the async runtime free.
            tokio::task::spawn_blocking(move || {
                let mut child = Command::new(&command).args(&args)
                    .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped())
                    .spawn().map_err(|e| format!("{command}: {e}"))?;
                child.stdin.take().ok_or("no stdin")?.write_all(render(&req).as_bytes()).map_err(|e| e.to_string())?;
                let out = child.wait_with_output().map_err(|e| e.to_string())?;
                if !out.status.success() {
                    return Err(format!("{command} exited {}: {}", out.status, String::from_utf8_lossy(&out.stderr)));
                }
                Ok(parse(&String::from_utf8_lossy(&out.stdout)))
            })
            .await
            .map_err(|e| e.to_string())?
        }) as _
    });
    create_in_process_client(InProcessOptions { model, generate: Some(generate), ..Default::default() })
}
