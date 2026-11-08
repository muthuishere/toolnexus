//! Reactive compaction on context overflow (SPEC.md §7F.1, change
//! `add-reactive-compaction`, issue #194). When the provider rejects a request
//! as too long and `ClientOptions::compact_on_overflow` is set, the loop
//! compacts the working transcript and re-sends the same turn, guarded by a
//! "rapid refill" breaker. Unset ⇒ byte-identical: the `ClientError::Provider`
//! surfaces exactly as before.

use serde_json::Value;

use super::{BeforeLlmEvent, Client, ClientError};

/// The pinned body markers (matched lowercased).
const OVERFLOW_MARKERS: [&str; 6] = [
    "context_length_exceeded",
    "maximum context length",
    "prompt is too long",
    "prompt_too_long",
    "request_too_large",
    "context window",
];

/// Whether a provider failure means "the prompt does not fit": status 400 or
/// 413 AND the (redacted) body, lowercased, contains a pinned marker (§7F.1).
pub fn is_context_overflow(status: u16, body: &str) -> bool {
    if status != 400 && status != 413 {
        return false;
    }
    let b = body.to_lowercase();
    OVERFLOW_MARKERS.iter().any(|m| b.contains(m))
}

/// The run failed because reactive compaction could not make the prompt fit:
/// the breaker tripped (`compactions == 3`) or the hook made no progress.
/// `status` / `body` come from the last provider error (body already redacted).
#[derive(Debug, Clone, PartialEq, thiserror::Error)]
#[error("context overflow: {compactions} reactive compaction(s) did not make the prompt fit")]
pub struct ContextOverflowError {
    pub compactions: usize,
    pub status: u16,
    pub body: String,
}

/// Per-run §7F.1 counters.
#[derive(Debug, Default)]
pub(super) struct OverflowBreaker {
    /// Successful LLM calls since the last reactive compaction.
    ok_since: usize,
    /// Compactions in the current rapid chain.
    streak: usize,
}

impl OverflowBreaker {
    pub(super) fn ok(&mut self) {
        self.ok_since += 1;
    }
}

impl Client {
    /// Decide what a failed LLM call becomes: `Ok(messages)` ⇒ re-send the same
    /// turn with this compacted transcript (no `before_llm`, no turn consumed);
    /// `Err(e)` ⇒ the run fails with `e` (the original error untouched when the
    /// option is unset or the failure is not an overflow).
    pub(super) async fn react_overflow(
        &self,
        b: &mut OverflowBreaker,
        err: ClientError,
        messages: &[Value],
        tools: &[Value],
        model: &str,
        turn: usize,
    ) -> Result<Vec<Value>, ClientError> {
        let Some(hook) = &self.opts().compact_on_overflow else {
            return Err(err);
        };
        let (status, body) = match &err {
            ClientError::Provider(p) if is_context_overflow(p.status, &p.body) => {
                (p.status, p.body.clone())
            }
            _ => return Err(err),
        };
        if !(b.streak > 0 && b.ok_since < 2) {
            b.streak = 0;
        }
        let fail = |n| {
            ClientError::ContextOverflow(ContextOverflowError {
                compactions: n,
                status,
                body: body.clone(),
            })
        };
        if b.streak == 3 {
            return Err(fail(3));
        }
        let ov = hook(BeforeLlmEvent {
            messages: messages.to_vec(),
            tools: tools.to_vec(),
            model: model.into(),
            turn,
        })
        .await
        .map_err(ClientError::Hook)?;
        match ov.and_then(|o| o.messages) {
            Some(m) if m.len() < messages.len() => {
                b.streak += 1;
                b.ok_since = 0;
                Ok(m)
            }
            _ => Err(fail(b.streak)),
        }
    }
}
