//! Context compaction (SPEC §7F) — a pure messages→messages helper wired
//! through the shipped §8 `before_llm` seam. No core loop change. Port of
//! `golang/agents/compaction.go`.
//!
//! Below `max_tokens` it is a no-op (no override → byte-identical to no
//! compactor). Above it, it summarizes the older head and keeps a recent tail
//! that ALWAYS begins at a user turn that does not carry a tool result
//! (tool-pair safety). A leading system prompt is preserved verbatim.

use std::sync::Arc;

use serde_json::{Value, json};

use crate::client::{BeforeLlmEvent, BeforeLlmHook, BoxFuture, LlmOverride};

/// The default deterministic estimate: per message ceil(len(JSON) / 4), summed.
pub fn estimate_tokens(messages: &[Value]) -> usize {
    messages
        .iter()
        .map(|m| {
            serde_json::to_string(m)
                .map(|s| s.len())
                .unwrap_or(0)
                .div_ceil(4)
        })
        .sum()
}

/// Summarize the older messages. It MAY call an LLM; the library makes none.
pub type SummarizeFn = Arc<dyn Fn(Vec<Value>) -> BoxFuture<Result<String, String>> + Send + Sync>;
pub type CountTokensFn = Arc<dyn Fn(&[Value]) -> usize + Send + Sync>;
pub type OnCompactFn = Arc<dyn Fn(&CompactInfo) + Send + Sync>;

/// `compactor` options.
#[derive(Clone, Default)]
pub struct CompactorOptions {
    /// Compact only when the estimate exceeds this; at or below ⇒ no-op.
    pub max_tokens: usize,
    /// Keep at least this many tokens of recent tail; 0 ⇒ `max_tokens / 2`.
    pub keep_tail: usize,
    /// Required.
    pub summarize: Option<SummarizeFn>,
    /// Default [`estimate_tokens`].
    pub count_tokens: Option<CountTokensFn>,
    /// Inject a pre-compact reminder to persist durable facts via the §7E
    /// memory tool.
    pub flush_to_memory: bool,
    /// Called after each compaction (never on a no-op), e.g. to tell a UI.
    pub on_compact: Option<OnCompactFn>,
}

/// One compaction, for `on_compact`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CompactInfo {
    pub tokens_before: usize,
    pub tokens_after: usize,
    pub messages_before: usize,
    pub messages_after: usize,
    /// How many messages were folded into the summary.
    pub summarized: usize,
    /// The text `summarize` returned (without the header line).
    pub summary: String,
    /// The summarized messages, verbatim, so a host can persist them.
    pub folded: Vec<Value>,
}

fn role_of(m: &Value) -> &str {
    m.get("role").and_then(Value::as_str).unwrap_or("")
}

fn carries_tool_result(m: &Value) -> bool {
    m.get("content").and_then(Value::as_array).is_some_and(|b| {
        b.iter()
            .any(|x| x.get("type").and_then(Value::as_str) == Some("tool_result"))
    })
}

fn is_boundary(m: &Value) -> bool {
    role_of(m) == "user" && !carries_tool_result(m)
}

/// A `before_llm` hook that compacts the transcript past `max_tokens`. The
/// compacted transcript is `[leading system (verbatim), (pinned prompt?),
/// summary system message, (flush reminder?), …tail]`.
pub fn compactor(opts: CompactorOptions) -> BeforeLlmHook {
    let count: CountTokensFn = opts
        .count_tokens
        .clone()
        .unwrap_or_else(|| Arc::new(|m: &[Value]| estimate_tokens(m)));
    let keep_tail = if opts.keep_tail == 0 {
        opts.max_tokens / 2
    } else {
        opts.keep_tail
    };
    let opts = Arc::new(opts);
    Arc::new(move |ev: BeforeLlmEvent| {
        let (count, opts) = (count.clone(), opts.clone());
        Box::pin(async move { compact(&ev.messages, &opts, &count, keep_tail).await })
    })
}

async fn compact(
    msgs: &[Value],
    opts: &CompactorOptions,
    count: &CountTokensFn,
    keep_tail: usize,
) -> Result<Option<LlmOverride>, String> {
    let before = count(msgs);
    if before <= opts.max_tokens {
        return Ok(None);
    }
    let head0 = usize::from(msgs.first().is_some_and(|m| role_of(m) == "system"));
    let system = &msgs[..head0];
    let n = msgs.len();
    let mut split = n;
    let mut i = n;
    while i > head0 + 1 {
        i -= 1;
        let tail = count(&msgs[i..]);
        if is_boundary(&msgs[i]) && tail <= keep_tail {
            split = i;
        }
        if tail > keep_tail {
            break;
        }
    }
    if split == n {
        for i in (head0 + 1..n).rev() {
            if is_boundary(&msgs[i]) {
                split = i;
                break;
            }
        }
    }
    if split <= head0 {
        return Ok(None);
    }
    let mut older = &msgs[head0..split];
    let tail = &msgs[split..];
    let mut pinned: &[Value] = &[];
    if split == n && head0 < n && is_boundary(&msgs[head0]) {
        pinned = &msgs[head0..head0 + 1];
        older = &msgs[head0 + 1..];
    }
    if older.is_empty() {
        return Ok(None);
    }
    let Some(summarize) = &opts.summarize else {
        return Err("toolnexus: CompactorOptions.summarize is required".into());
    };
    let summary = summarize(older.to_vec()).await?;
    let mut out: Vec<Value> = Vec::with_capacity(system.len() + 3 + tail.len());
    out.extend_from_slice(system);
    out.extend_from_slice(pinned);
    out.push(json!({"role": "system", "content": format!("[Summary of earlier conversation]\n{summary}")}));
    if opts.flush_to_memory {
        out.push(json!({"role": "system", "content": "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized."}));
    }
    out.extend_from_slice(tail);
    let after = count(&out);
    if after >= before {
        return Ok(None);
    }
    if let Some(f) = &opts.on_compact {
        f(&CompactInfo {
            tokens_before: before,
            tokens_after: after,
            messages_before: n,
            messages_after: out.len(),
            summarized: older.len(),
            summary,
            folded: older.to_vec(),
        });
    }
    Ok(Some(LlmOverride {
        messages: Some(out),
        ..Default::default()
    }))
}
