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
//! session/new rejects a relative cwd with -32602.
//! Conformance flags (conformance/acp-client/cases.json), read from the env:
//!   TN_FAKE_RECORD=<file>     append every received request as `{method,params}` JSON lines
//!   TN_FAKE_NO_CONFIG_OPTION  reject session/set_config_option (set_model is accepted)
//!   TN_FAKE_EXIT_ON_PROMPT    exit without replying on session/prompt
//!   TN_FAKE_FS_REQUEST        on session/prompt ask fs/read_text_file first; answer "ok"
//!                             if it came back as JSON-RPC error -32601
//!   TN_FAKE_DRIP              on session/prompt: thought "pondering", then three chunks 200ms apart

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
        if let Ok(path) = std::env::var("TN_FAKE_RECORD") {
            let mut f = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .unwrap();
            writeln!(
                f,
                "{}",
                serde_json::json!({"method": method, "params": msg.get("params")})
            )
            .unwrap();
        }
        let flag = |k: &str| std::env::var(k).is_ok_and(|v| !v.is_empty());
        if method == "session/prompt" && flag("TN_FAKE_EXIT_ON_PROMPT") {
            std::process::exit(0);
        }
        if method == "session/prompt" && flag("TN_FAKE_DRIP") {
            chunk(session, "agent_thought_chunk", "pondering");
            for (i, c) in ["drip-1 ", "drip-2 ", "drip-3"].iter().enumerate() {
                if i > 0 {
                    std::thread::sleep(std::time::Duration::from_millis(200));
                }
                chunk(session, "agent_message_chunk", c);
            }
            send(serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}));
            continue;
        }
        if method == "session/prompt" && flag("TN_FAKE_FS_REQUEST") {
            let rid = next_out_id;
            next_out_id += 1;
            send(
                serde_json::json!({"jsonrpc":"2.0","id":rid,"method":"fs/read_text_file","params":{"sessionId":session,"path":"/etc/hosts"}}),
            );
            let mut ok = false;
            while let Some(Ok(l)) = lines.next() {
                let Ok(m) = serde_json::from_str::<serde_json::Value>(&l) else {
                    continue;
                };
                if m.get("id") == Some(&serde_json::json!(rid)) {
                    ok = m.pointer("/error/code") == Some(&serde_json::json!(-32601));
                    break;
                }
            }
            chunk(
                session,
                "agent_message_chunk",
                if ok { "ok" } else { "bad" },
            );
            send(serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}));
            continue;
        }
        match method {
            "session/set_config_option" if flag("TN_FAKE_NO_CONFIG_OPTION") => send(
                serde_json::json!({"jsonrpc":"2.0","id":id,"error":{"code":-32601,"message":"Method not found"}}),
            ),
            "session/set_config_option" | "session/set_model" => {
                send(serde_json::json!({"jsonrpc":"2.0","id":id,"result":{}}))
            }
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
                    "toolstream" => {
                        chunk(session, "agent_thought_chunk", "hmm");
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read file","kind":"read","status":"pending","rawInput":{"path":"a.txt"}}),
                        );
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"in_progress"}),
                        );
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"file body"}}]}),
                        );
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call","toolCallId":"t2","kind":"execute","status":"pending"}),
                        );
                        update(
                            session,
                            serde_json::json!({"sessionUpdate":"tool_call_update","toolCallId":"t2","status":"failed","content":[{"type":"content","content":{"type":"text","text":"boom"}}]}),
                        );
                        chunk(session, "agent_message_chunk", "done");
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}),
                        );
                    }
                    "permission" | "permission_noreject" => {
                        let pid = next_out_id;
                        next_out_id += 1;
                        let options = if scenario == "permission" {
                            serde_json::json!([{"optionId":"no","name":"Reject","kind":"reject_once"},
                                       {"optionId":"yes","name":"Allow","kind":"allow_once"},
                                       {"optionId":"always","name":"Always allow","kind":"allow_always"}])
                        } else {
                            serde_json::json!([{"optionId":"yes","name":"Allow","kind":"allow_once"}])
                        };
                        send(
                            serde_json::json!({"jsonrpc":"2.0","id":pid,"method":"session/request_permission","params":{
                            "sessionId":session,"toolCall":{"toolCallId":"t9","title":"rm -rf build","kind":"execute"},
                            "options":options}}),
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
