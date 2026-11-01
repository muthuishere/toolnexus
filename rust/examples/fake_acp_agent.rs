//! Scripted fake ACP agent for the hermetic tests (built by `cargo test`).
//! Usage: fake_acp_agent <scenario> [init_delay_ms]
//! Scenarios mirror real agents' quirks (claude-code-acp, gemini --experimental-acp,
//! codex-acp, goose):
//!   basic       thought, tool_call, tool_call_update, plan, two message chunks, end_turn
//!   permission  request_permission (reject_once / allow_once / allow_always), then
//!               a chunk "chose:<optionId|cancelled>"
//!   exit        one chunk, then the process exits mid-turn with code 3
//!   cancel      waits for session/cancel, then ends the turn with stopReason cancelled
//!   fsrequest   asks the client for fs/read_text_file before answering
//!   echo        replies with the prompt text as one chunk
//! session/new rejects a relative cwd with -32602, like `devin acp`.

use std::io::{BufRead, Write};

fn send(v: serde_json::Value) {
    let mut out = std::io::stdout().lock();
    writeln!(out, "{v}").unwrap();
    out.flush().unwrap();
}

fn update(session: &str, u: serde_json::Value) {
    send(
        serde_json::json!({"jsonrpc":"2.0","method":"session/update","params":{"sessionId":session,"update":u}}),
    );
}

fn chunk(session: &str, kind: &str, text: &str) {
    update(
        session,
        serde_json::json!({"sessionUpdate":kind,"content":{"type":"text","text":text}}),
    );
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let scenario = args.get(1).cloned().unwrap_or_else(|| "basic".into());
    let init_delay: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);
    let session = "sess-1";
    let stdin = std::io::stdin();
    let mut lines = stdin.lock().lines();
    let mut next_out_id = 100;
    while let Some(Ok(line)) = lines.next() {
        let Ok(msg) = serde_json::from_str::<serde_json::Value>(&line) else {
            continue;
        };
        let id = msg.get("id").cloned();
        let method = msg.get("method").and_then(|m| m.as_str()).unwrap_or("");
        match method {
            "initialize" => {
                std::thread::sleep(std::time::Duration::from_millis(init_delay));
                send(
                    serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"protocolVersion":1,"agentInfo":{"name":"fake","version":"0"},"agentCapabilities":{}}}),
                );
            }
            "session/new" => {
                let cwd = msg
                    .pointer("/params/cwd")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                if !cwd.starts_with('/') && !cwd.contains(":\\") {
                    send(
                        serde_json::json!({"jsonrpc":"2.0","id":id,"error":{"code":-32602,"message":"cwd must be absolute"}}),
                    );
                } else {
                    send(
                        serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"sessionId":session}}),
                    );
                }
            }
            "session/set_mode" => send(serde_json::json!({"jsonrpc":"2.0","id":id,"result":{}})),
            "session/prompt" => {
                let text = msg
                    .pointer("/params/prompt/0/text")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_owned();
                match scenario.as_str() {
                    "basic" => {
                        chunk(session, "agent_thought_chunk", "thinking...");
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read file","kind":"read","status":"pending"}),
                        );
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed"}),
                        );
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"plan","entries":[{"content":"step 1","priority":"high","status":"pending"}]}),
                        );
                        chunk(session, "agent_message_chunk", "Hello, ");
                        chunk(session, "agent_message_chunk", "world");
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}),
                        );
                    }
                    "permission" => {
                        let pid = next_out_id;
                        next_out_id += 1;
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":pid,"method":"session/request_permission","params":{
                            "sessionId":session,"toolCall":{"toolCallId":"t9","title":"rm -rf build","kind":"execute"},
                            "options":[{"optionId":"no","name":"Reject","kind":"reject_once"},
                                       {"optionId":"yes","name":"Allow","kind":"allow_once"},
                                       {"optionId":"always","name":"Always allow","kind":"allow_always"}]}}),
                        );
                        // wait for the client's answer
                        let mut chose = String::from("none");
                        while let Some(Ok(l)) = lines.next() {
                            let Ok(m) = serde_json::from_str::<serde_json::Value>(&l) else {
                                continue;
                            };
                            if m.get("id") == Some(&serde_json::json!(pid)) {
                                let o = m.pointer("/result/outcome").cloned().unwrap_or_default();
                                chose = if o.get("outcome").and_then(|v| v.as_str())
                                    == Some("selected")
                                {
                                    o.get("optionId")
                                        .and_then(|v| v.as_str())
                                        .unwrap_or("")
                                        .to_owned()
                                } else {
                                    "cancelled".into()
                                };
                                break;
                            }
                        }
                        chunk(session, "agent_message_chunk", &format!("chose:{chose}"));
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}),
                        );
                    }
                    "exit" => {
                        chunk(session, "agent_message_chunk", "partial");
                        std::process::exit(3);
                    }
                    "cancel" => {
                        chunk(session, "agent_message_chunk", "working");
                        while let Some(Ok(l)) = lines.next() {
                            if l.contains("\"session/cancel\"") {
                                break;
                            }
                        }
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"cancelled"}}),
                        );
                    }
                    "fsrequest" => {
                        let rid = next_out_id;
                        next_out_id += 1;
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":rid,"method":"fs/read_text_file","params":{"sessionId":session,"path":"/etc/hosts"}}),
                        );
                        let mut got = String::from("none");
                        while let Some(Ok(l)) = lines.next() {
                            let Ok(m) = serde_json::from_str::<serde_json::Value>(&l) else {
                                continue;
                            };
                            if m.get("id") == Some(&serde_json::json!(rid)) {
                                got = m
                                    .pointer("/error/code")
                                    .map(|c| c.to_string())
                                    .unwrap_or_else(|| "result".into());
                                break;
                            }
                        }
                        chunk(session, "agent_message_chunk", &format!("fs:{got}"));
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}),
                        );
                    }
                    _ => {
                        chunk(session, "agent_message_chunk", &text);
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}),
                        );
                    }
                }
            }
            _ => {}
        }
    }
}
