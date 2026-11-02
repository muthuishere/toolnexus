//! Prompt cache + tool tiers, hermetic: a local mock Anthropic server stands in
//! for the provider and simulates its prompt cache, so this runs with no key and
//! no network. Mirrors golang/examples/promptcache.
//!
//!     cargo run --example prompt_cache
//!
//! What it shows:
//! - `prompt_cache` puts cache_control breakpoints on the stable prefix;
//! - `tool_tiers` sends only the core schemas plus tool_search / call_tool, so
//!   the tools array, and therefore the cached prefix, is identical on every request;
//! - the model loads a listed tool with tool_search and calls it via call_tool,
//!   and the before_tool hook sees the REAL tool name;
//! - Usage reports cache_read_tokens / cache_write_tokens, and under anthropic the
//!   real context size is prompt + cache read + cache write.

use std::sync::{Arc, Mutex};

use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use toolnexus::client::{
    BeforeToolHook, ClientOptions, Hooks, PromptCacheOptions, Style, ToolTierOptions, create_client,
};
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![
            define_tool("clock", "Current time", None, |_, _| async {
                Ok::<_, String>("12:00".to_string())
            }),
            define_tool(
                "weather",
                "Current weather for a city",
                Some(json!({"type": "object", "properties": {"city": {"type": "string"}}})),
                |a: Value, _| async move {
                    Ok::<_, String>(format!("sunny in {}", a["city"].as_str().unwrap_or("")))
                },
            ),
            define_tool("stocks", "Latest stock quote", None, |_, _| async {
                Ok::<_, String>("ACME 42".to_string())
            }),
        ],
        ..Default::default()
    })
    .await?;

    let tools_seen = Arc::new(Mutex::new(Vec::<String>::new()));
    let url = mock_anthropic(tools_seen.clone()).await?;

    let gated = Arc::new(Mutex::new(Vec::<String>::new()));
    let g2 = gated.clone();
    let before: BeforeToolHook = Arc::new(move |ev| {
        g2.lock().unwrap().push(ev.name);
        Box::pin(async { Ok(None) })
    });
    let client = create_client(ClientOptions {
        base_url: url,
        style: Style::Anthropic,
        model: "mock-claude".into(),
        api_key: "YOUR_KEY_HERE".into(), // a local host needs api_key or api_key_env (0.22.0)
        system_prompt: "You are a concise assistant.".into(),
        prompt_cache: Some(PromptCacheOptions::default()),
        tool_tiers: Some(ToolTierOptions {
            core: vec!["clock".into()],
            deferred: vec!["stocks".into()],
            ..Default::default()
        }),
        hooks: Hooks {
            before_tool: Some(before),
            ..Default::default()
        },
        ..Default::default()
    });

    let mut plan: Vec<_> = client
        .tool_tier_plan(&tk)
        .unwrap_or_default()
        .into_iter()
        .map(|(k, v)| format!("{k}={v:?}"))
        .collect();
    plan.sort();
    println!("tier plan: {}", plan.join(" "));

    let res = client
        .ask("What is the weather in Paris?", &tk, "session-1")
        .await?;
    println!("answer:    {}", res.text);
    println!(
        "gated:     {:?} (the hook saw weather, not call_tool)",
        gated.lock().unwrap()
    );

    let res2 = client.ask("And now?", &tk, "session-1").await?;
    let u = &res2.usage;
    println!(
        "turn 2:    prompt={} cacheRead={} cacheWrite={} -> context={} tokens",
        u.prompt_tokens,
        u.cache_read_tokens,
        u.cache_write_tokens,
        u.prompt_tokens + u.cache_read_tokens + u.cache_write_tokens
    );

    let seen = tools_seen.lock().unwrap().clone();
    assert!(
        seen.len() > 1
            && seen
                .iter()
                .all(|t| t == &seen[0] && t.contains("tool_search")),
        "the tools array changed between requests — the cached prefix would break"
    );
    assert_eq!(*gated.lock().unwrap(), ["weather"]);
    assert!(u.cache_read_tokens > 0, "{u:?}");
    println!(
        "ok: tools array identical on all {} requests; cache hits from request 2",
        seen.len()
    );
    Ok(())
}

/// Answers like the Messages API and simulates a prefix cache: the first
/// request writes the tools+system prefix, every later one reads it.
async fn mock_anthropic(tools: Arc<Mutex<Vec<String>>>) -> std::io::Result<String> {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let url = format!("http://{}", listener.local_addr()?);
    tokio::spawn(async move {
        let mut n = 0;
        while let Ok((mut sock, _)) = listener.accept().await {
            let Some(body) = read_body(&mut sock).await else {
                continue;
            };
            n += 1;
            let tools_json = body["tools"].to_string();
            tools.lock().unwrap().push(tools_json.clone());
            let prefix = tools_json.len() / 4;
            let mut usage = json!({"input_tokens": 20, "output_tokens": 5});
            usage[if n == 1 {
                "cache_creation_input_tokens"
            } else {
                "cache_read_input_tokens"
            }] = json!(prefix);
            let content = match n {
                1 => json!([{"type": "tool_use", "id": "t1", "name": "tool_search",
                    "input": {"query": "select:weather"}}]),
                2 => json!([{"type": "tool_use", "id": "t2", "name": "call_tool",
                    "input": {"name": "weather", "arguments": {"city": "Paris"}}}]),
                3 => json!([{"type": "text", "text": "It is sunny in Paris."}]),
                _ => json!([{"type": "text", "text": "Still sunny."}]),
            };
            let stop = if n <= 2 { "tool_use" } else { "end_turn" };
            let out = json!({"content": content, "stop_reason": stop, "usage": usage}).to_string();
            let resp = format!(
                "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{out}",
                out.len()
            );
            let _ = sock.write_all(resp.as_bytes()).await;
        }
    });
    Ok(url)
}

/// Reads one HTTP/1.1 request and returns its JSON body.
async fn read_body(sock: &mut tokio::net::TcpStream) -> Option<Value> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 8192];
    loop {
        let n = sock.read(&mut chunk).await.ok()?;
        if n == 0 {
            return None;
        }
        buf.extend_from_slice(&chunk[..n]);
        let text = String::from_utf8_lossy(&buf);
        if let Some(i) = text.find("\r\n\r\n") {
            let len = text[..i]
                .lines()
                .find_map(|l| {
                    let (k, v) = l.split_once(':')?;
                    k.eq_ignore_ascii_case("content-length")
                        .then(|| v.trim().parse::<usize>().ok())?
                })
                .unwrap_or(0);
            if buf.len() >= i + 4 + len {
                return serde_json::from_slice(&buf[i + 4..i + 4 + len]).ok();
            }
        }
    }
}
