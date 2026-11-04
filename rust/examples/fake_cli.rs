//! Fake agent CLI for the hermetic CLI-client tests (tests/cli.rs, built by
//! `cargo test`). Mirrors the helper roles of golang/cli_test.go:
//!
//! - `fake_cli mcp-bridge <url>`: the stdio⇄HTTP MCP bridge (`run_mcp_bridge`),
//!   token from `TOOLNEXUS_MCP_TOKEN`;
//! - `TN_FAKE_ACP=1 fake_cli acp`: a fake ACP agent. FAKE_HTTP=1 advertises
//!   mcpCapabilities.http; FAKE_FAIL_INIT=1 exits before initialize;
//!   FAKE_NO_CONFIG=1 rejects session/set_config_option; FAKE_PROJECT=1 reads
//!   the MCP server from <cwd>/.mcp.json; TN_FAKE_REC records every method;
//! - `TN_FAKE_CLI=<mode> fake_cli …`: a fake one-shot CLI. Prompt from stdin,
//!   or the arg after `--p`, or the file after `--f`; the answer goes to stdout,
//!   or to the file after `--o`.

use std::io::{BufRead, Read, Write};

use serde_json::{Value, json};

fn rec(line: &str) {
    if let Ok(p) = std::env::var("TN_FAKE_REC") {
        let mut f = std::fs::OpenOptions::new()
            .append(true)
            .create(true)
            .open(p)
            .unwrap();
        let _ = f.write_all(line.as_bytes());
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 3 && args[1] == "mcp-bridge" {
        let rt = tokio::runtime::Runtime::new().unwrap();
        let token = std::env::var("TOOLNEXUS_MCP_TOKEN").unwrap_or_default();
        let r = rt.block_on(toolnexus::cli::run_mcp_bridge(
            &args[2],
            &token,
            tokio::io::BufReader::new(tokio::io::stdin()),
            tokio::io::stdout(),
        ));
        if let Err(e) = r {
            eprintln!("{e}");
            std::process::exit(1);
        }
        return;
    }
    if std::env::var("TN_FAKE_ACP").as_deref() == Ok("1")
        && args.get(1).map(String::as_str) == Some("acp")
    {
        fake_acp();
        return;
    }
    if let Ok(mode) = std::env::var("TN_FAKE_CLI") {
        fake_one_shot(&mode, &args[1..]);
        return;
    }
    eprintln!("fake_cli: no role");
    std::process::exit(64);
}

fn fake_one_shot(mode: &str, args: &[String]) {
    let (mut prompt, mut out_file) = (String::new(), String::new());
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--p" => {
                i += 1;
                prompt = args[i].clone();
            }
            "--f" => {
                i += 1;
                prompt = std::fs::read_to_string(&args[i]).unwrap_or_default();
            }
            "--o" => {
                i += 1;
                out_file = args[i].clone();
            }
            "--argv" => {
                print!("{}", json!({"content": args.join(" ")}));
                return;
            }
            _ => {}
        }
        i += 1;
    }
    if prompt.is_empty() {
        let _ = std::io::stdin().read_to_string(&mut prompt);
    }
    rec(&format!("{prompt}\n=====\n"));
    let reply = match mode {
        "exit" => {
            eprint!("boom: bad login");
            std::process::exit(3);
        }
        "empty" => return,
        "sleep" => {
            std::thread::sleep(std::time::Duration::from_secs(10));
            json!({"content": "late"}).to_string()
        }
        "nooutfile" => {
            print!("progress only");
            return;
        }
        "tool" => {
            if prompt.contains("BADGE-77Q") {
                json!({"content": "Your badge is BADGE-77Q."}).to_string()
            } else {
                // fenced, string arguments: exercises the tolerant parser
                "```json\n{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"get_badge_number\",\"arguments\":\"{}\"}}]}\n```\n\n".to_owned()
            }
        }
        _ => r#"{"content":"hello"}"#.to_owned(),
    };
    if !out_file.is_empty() {
        println!("progress on stdout");
        std::fs::write(&out_file, reply).unwrap();
        return;
    }
    print!("{reply}");
}

fn send(v: Value) {
    let mut o = std::io::stdout().lock();
    writeln!(o, "{v}").unwrap();
    o.flush().unwrap();
}

fn fake_acp() {
    if std::env::var("FAKE_FAIL_INIT").as_deref() == Ok("1") {
        eprint!("unknown flag: acp");
        std::process::exit(2);
    }
    let env1 = |k: &str| std::env::var(k).as_deref() == Ok("1");
    let stdin = std::io::stdin();
    let mut lines = stdin.lock().lines();
    let mut entry: Value = Value::Null;
    let mut cwd = String::new();
    let mut next_id = 1000;
    let update = |u: Value| {
        send(
            json!({"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":u}}),
        )
    };
    while let Some(Ok(line)) = lines.next() {
        let Ok(m) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let method = m["method"].as_str().unwrap_or("").to_owned();
        let id = m["id"].clone();
        rec(&format!("{method} {}\n", m["params"]));
        match method.as_str() {
            "initialize" => send(
                json!({"jsonrpc":"2.0","id":id,"result":{"protocolVersion":1,
                "agentCapabilities":{"mcpCapabilities":{"http": env1("FAKE_HTTP")}}}}),
            ),
            "session/new" => {
                cwd = m["params"]["cwd"].as_str().unwrap_or("").to_owned();
                if let Some(a) = m["params"]["mcpServers"].as_array() {
                    if a.len() == 1 {
                        entry = a[0].clone();
                    }
                }
                send(json!({"jsonrpc":"2.0","id":id,"result":{"sessionId":"s1"}}));
            }
            "session/set_config_option" => {
                if env1("FAKE_NO_CONFIG") {
                    send(json!({"jsonrpc":"2.0","id":id,"error":{"code":-32601,"message":"nope"}}));
                } else {
                    send(json!({"jsonrpc":"2.0","id":id,"result":{}}));
                }
            }
            "session/set_model" => send(json!({"jsonrpc":"2.0","id":id,"result":{}})),
            "session/prompt" => {
                if env1("FAKE_PROJECT") {
                    let b = std::fs::read_to_string(std::path::Path::new(&cwd).join(".mcp.json"))
                        .unwrap_or_default();
                    let pf: Value = serde_json::from_str(&b).unwrap_or(Value::Null);
                    let e = &pf["mcpServers"]["toolnexus"];
                    let hdr: Vec<Value> = e["headers"]
                        .as_object()
                        .map(|h| {
                            h.iter()
                                .map(|(k, v)| json!({"name": k, "value": v}))
                                .collect()
                        })
                        .unwrap_or_default();
                    entry = json!({"type":"http","url":e["url"],"headers":hdr});
                }
                let mut ask = |call_id: &str, title: &str| -> String {
                    next_id += 1;
                    send(
                        json!({"jsonrpc":"2.0","id":next_id,"method":"session/request_permission","params":{
                        "sessionId":"s1","toolCall":{"toolCallId":call_id,"title":title},
                        "options":[{"optionId":"yes","kind":"allow_once"},{"optionId":"no","kind":"reject_once"}]}}),
                    );
                    for l in lines.by_ref() {
                        let Ok(l) = l else { break };
                        let r: Value = serde_json::from_str(&l).unwrap_or(Value::Null);
                        if r["id"] == json!(next_id) {
                            return r["result"]["outcome"]["optionId"]
                                .as_str()
                                .unwrap_or("")
                                .to_owned();
                        }
                    }
                    String::new()
                };
                // devin-like: the name only on the tool_call update; empty permission title
                update(
                    json!({"sessionUpdate":"tool_call","toolCallId":"tc1","title":"Calling get_badge_number from toolnexus"}),
                );
                let ours = ask("tc1", "");
                update(json!({"sessionUpdate":"tool_call","toolCallId":"tc2","title":"Bash"}));
                let own = ask("tc2", "Bash");
                // a lookalike title on a new id must be rejected
                let fake = ask("tc3", "my_get_badge_number_wrapper");
                // the same id again with another title: decided per id
                let again = ask("tc1", "Bash");
                let text = format!("perm ours={ours} own={own} fake={fake} again={again}; ")
                    + &call_served(&entry);
                update(
                    json!({"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":text}}),
                );
                send(json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"end_turn"}}));
            }
            _ => {}
        }
    }
}

/// Call get_badge_number and load_skill on the handed MCP server (http with
/// its headers, or by spawning the stdio entry); also probe http without a token.
fn call_served(entry: &Value) -> String {
    let reqs = [
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake","version":"0"}}}"#,
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"#,
        r#"{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_badge_number","arguments":{}}}"#,
        r#"{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_skill","arguments":{"name":"hello-world"}}}"#,
    ];
    let mut replies: Vec<String> = Vec::new();
    let mut noauth = 0;
    if entry["type"] == "http" {
        let url = entry["url"].as_str().unwrap_or("").to_owned();
        let hdr: Vec<(String, String)> = entry["headers"]
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|h| {
                        (
                            h["name"].as_str().unwrap_or("").to_owned(),
                            h["value"].as_str().unwrap_or("").to_owned(),
                        )
                    })
                    .collect()
            })
            .unwrap_or_default();
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let http = reqwest::Client::new();
            for r in reqs {
                let mut q = http
                    .post(&url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .body(r);
                for (k, v) in &hdr {
                    q = q.header(k, v);
                }
                match q.send().await {
                    Ok(resp) => replies.push(resp.text().await.unwrap_or_default()),
                    Err(_) => {
                        replies.push("http error".into());
                        return;
                    }
                }
            }
            if let Ok(resp) = http
                .post(&url)
                .header("Content-Type", "application/json")
                .body(reqs[0])
                .send()
                .await
            {
                noauth = resp.status().as_u16();
            }
        });
    } else if entry.is_object() {
        let args: Vec<String> = entry["args"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let mut cmd = std::process::Command::new(entry["command"].as_str().unwrap_or(""));
        cmd.args(&args)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped());
        for e in entry["env"].as_array().cloned().unwrap_or_default() {
            cmd.env(
                e["name"].as_str().unwrap_or(""),
                e["value"].as_str().unwrap_or(""),
            );
        }
        let Ok(mut child) = cmd.spawn() else {
            return "bridge error".into();
        };
        let input = reqs.join("\n") + "\n";
        child
            .stdin
            .take()
            .unwrap()
            .write_all(input.as_bytes())
            .unwrap();
        let out = child.wait_with_output().unwrap();
        if !out.status.success() {
            return "bridge error".into();
        }
        replies = String::from_utf8_lossy(&out.stdout)
            .trim()
            .split('\n')
            .map(String::from)
            .collect();
    }
    let kind = entry["type"].as_str().unwrap_or("<nil>");
    format!(
        "entry={kind} noauth={noauth} replies={}",
        replies.join(" | ")
    )
}
