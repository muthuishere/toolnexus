//! Prompt caching (SPEC §4C, change `add-prompt-cache`): mark the stable prefix.
//! Anthropic gets `cache_control` breakpoints after the tools, after the system
//! prompt and on the latest message; OpenAI gets `prompt_cache_key`.

use serde_json::{Map, Value, json};

use super::Style;

/// `ClientOptions.prompt_cache`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PromptCacheOptions {
    /// OpenAI `prompt_cache_key` (empty ⇒ not sent).
    pub key: String,
    /// Anthropic 1-hour lifetime (`ttl:"1h"`).
    pub long_ttl: bool,
}

fn with_cache_control(v: &Value, mark: &Value) -> Value {
    match v.as_object() {
        Some(m) => {
            let mut c = m.clone();
            c.insert("cache_control".into(), mark.clone());
            Value::Object(c)
        }
        None => v.clone(),
    }
}

pub(crate) fn apply(body: &mut Map<String, Value>, style: Style, o: &PromptCacheOptions) {
    match style {
        Style::Anthropic => {
            let mut mark = json!({"type": "ephemeral"});
            if o.long_ttl {
                mark["ttl"] = json!("1h");
            }
            if let Some(Value::Array(tools)) = body.get_mut("tools")
                && let Some(last) = tools.last_mut()
            {
                *last = with_cache_control(last, &mark);
            }
            match body.get("system").cloned() {
                Some(Value::String(s)) if !s.is_empty() => {
                    body.insert(
                        "system".into(),
                        json!([{"type": "text", "text": s, "cache_control": mark}]),
                    );
                }
                Some(Value::Array(mut sys)) if !sys.is_empty() => {
                    let n = sys.len() - 1;
                    sys[n] = with_cache_control(&sys[n], &mark);
                    body.insert("system".into(), Value::Array(sys));
                }
                _ => {}
            }
            if let Some(Value::Array(msgs)) = body.get_mut("messages")
                && let Some(Value::Object(last)) = msgs.last_mut()
            {
                match last.get("content").cloned() {
                    Some(Value::String(s)) => {
                        last.insert(
                            "content".into(),
                            json!([{"type": "text", "text": s, "cache_control": mark}]),
                        );
                    }
                    Some(Value::Array(mut blocks)) if !blocks.is_empty() => {
                        let n = blocks.len() - 1;
                        blocks[n] = with_cache_control(&blocks[n], &mark);
                        last.insert("content".into(), Value::Array(blocks));
                    }
                    _ => {}
                }
            }
        }
        Style::OpenAI => {
            if !o.key.is_empty() {
                body.insert("prompt_cache_key".into(), json!(o.key));
            }
        }
    }
}
