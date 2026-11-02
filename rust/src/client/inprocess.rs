//! In-process models (SPEC §8): drive the same loop with a host function in
//! place of an HTTP provider. The request/response cross the seam in OpenAI
//! wire shape, so the loop itself is unchanged.

use std::collections::HashMap;
use std::sync::Arc;

use serde_json::{Map, Value, json};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use super::{
    BodyTransformFn, BoxFuture, Client, ClientOptions, Hooks, OnErrorFn, OnMetricFn, RawResponse,
    RespBody, StreamEvent, Style, Transport, Tx, WaitForCtxFn, WaitForFn,
};
use crate::store::ConversationStore;

/// A tool call the in-process model asks for.
#[derive(Debug, Clone, Default)]
pub struct InProcessToolCall {
    /// Empty ⇒ `call_<index>`.
    pub id: String,
    pub name: String,
    /// An object (encoded as JSON) or a JSON string (sent verbatim).
    pub arguments: Value,
}

/// What the loop sends the model.
#[derive(Debug, Clone, Default)]
pub struct InProcessRequest {
    pub messages: Vec<Value>,
    pub tools: Vec<Value>,
    pub model: String,
    /// The full request body (after `request_params` / `body_transform`).
    pub body: Value,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct InProcessUsage {
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub total_tokens: i64,
}

/// The model's answer: tool calls, else content.
#[derive(Debug, Clone, Default)]
pub struct InProcessResponse {
    pub content: String,
    pub tool_calls: Vec<InProcessToolCall>,
    pub usage: Option<InProcessUsage>,
}

/// Emit a streaming event from `generate_stream`: `text` deltas become the
/// model's streamed text; any other event is forwarded to the stream as-is
/// (e.g. an ACP agent's own `tool_call`s).
pub type EmitFn = Arc<dyn Fn(StreamEvent) + Send + Sync>;
pub type GenerateFn =
    Arc<dyn Fn(InProcessRequest) -> BoxFuture<Result<InProcessResponse, String>> + Send + Sync>;
pub type GenerateStreamFn = Arc<
    dyn Fn(
            CancellationToken,
            InProcessRequest,
            EmitFn,
        ) -> BoxFuture<Result<InProcessResponse, String>>
        + Send
        + Sync,
>;

/// `create_in_process_client` options (the client options that make sense in-process).
#[derive(Clone, Default)]
pub struct InProcessOptions {
    pub model: String,
    pub generate: Option<GenerateFn>,
    /// Makes `stream` work; also used for `run` when `generate` is absent.
    pub generate_stream: Option<GenerateStreamFn>,
    pub on_error: Option<OnErrorFn>,
    pub system_prompt: String,
    pub max_turns: usize,
    pub hooks: Hooks,
    pub timeout_ms: u64,
    pub store: Option<Arc<dyn ConversationStore>>,
    pub on_metric: Option<OnMetricFn>,
    pub wait_for: Option<WaitForFn>,
    pub wait_for_ctx: Option<WaitForCtxFn>,
    pub request_params: Map<String, Value>,
    pub body_transform: Option<BodyTransformFn>,
    pub headers: HashMap<String, String>,
}

pub(crate) struct InProcess {
    generate: Option<GenerateFn>,
    generate_stream: Option<GenerateStreamFn>,
}

fn encode_args(v: &Value) -> String {
    match v {
        Value::Null => "{}".into(),
        Value::String(s) => s.clone(),
        other => serde_json::to_string(other).unwrap_or_else(|_| "{}".into()),
    }
}

fn call_id(c: &InProcessToolCall, i: usize) -> String {
    if c.id.is_empty() {
        format!("call_{i}")
    } else {
        c.id.clone()
    }
}

fn sse(v: &Value) -> Vec<u8> {
    format!("data: {}\n\n", v).into_bytes()
}

impl InProcess {
    pub(crate) async fn respond(
        &self,
        raw: &[u8],
        cancel: &CancellationToken,
        side: Option<&Tx>,
    ) -> Result<RawResponse, String> {
        let body: Value = serde_json::from_slice(raw).unwrap_or(Value::Null);
        let stream = body.get("stream").and_then(Value::as_bool).unwrap_or(false);
        let req = InProcessRequest {
            messages: body
                .get("messages")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default(),
            tools: body
                .get("tools")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default(),
            model: body
                .get("model")
                .and_then(Value::as_str)
                .unwrap_or("")
                .into(),
            body: body.clone(),
        };
        if stream {
            let Some(gs) = self.generate_stream.clone() else {
                return Err("toolnexus: CreateInProcessClient does not support streaming — Generate returns a complete answer. Use Run, or supply an HTTPClient that streams".into());
            };
            return Ok(Self::respond_stream(gs, req, cancel.clone(), side.cloned()));
        }
        let answer = if let Some(g) = &self.generate {
            g(req).await?
        } else if let Some(gs) = &self.generate_stream {
            gs(cancel.clone(), req, Arc::new(|_| {})).await?
        } else {
            return Err("toolnexus: in-process client has no generate function".into());
        };
        let mut message = Map::new();
        message.insert("role".into(), json!("assistant"));
        if !answer.tool_calls.is_empty() {
            let calls: Vec<Value> = answer
                .tool_calls
                .iter()
                .enumerate()
                .map(|(i, c)| json!({"id": call_id(c, i), "type": "function", "function": {"name": c.name, "arguments": encode_args(&c.arguments)}}))
                .collect();
            message.insert("tool_calls".into(), Value::Array(calls));
        } else {
            message.insert("content".into(), json!(answer.content));
        }
        let finish = if message.contains_key("tool_calls") {
            "tool_calls"
        } else {
            "stop"
        };
        let u = answer.usage.unwrap_or_default();
        let total = if u.total_tokens == 0 {
            u.prompt_tokens + u.completion_tokens
        } else {
            u.total_tokens
        };
        let out = json!({
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            "usage": {"prompt_tokens": u.prompt_tokens, "completion_tokens": u.completion_tokens, "total_tokens": total},
        });
        Ok(RawResponse {
            status: 200,
            retry_after: String::new(),
            body: RespBody::Full(serde_json::to_vec(&out).unwrap_or_default()),
        })
    }

    fn respond_stream(
        gs: GenerateStreamFn,
        req: InProcessRequest,
        cancel: CancellationToken,
        side: Option<Tx>,
    ) -> RawResponse {
        let (ctx, crx) = mpsc::unbounded_channel::<Result<Vec<u8>, String>>();
        tokio::spawn(async move {
            let saw_text = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let emit: EmitFn = {
                let (ctx, saw_text) = (ctx.clone(), saw_text.clone());
                Arc::new(move |ev: StreamEvent| {
                    if ev.kind == "text" {
                        if !ev.delta.is_empty() {
                            saw_text.store(true, std::sync::atomic::Ordering::SeqCst);
                            let _ = ctx.send(Ok(sse(
                                &json!({"choices": [{"index": 0, "delta": {"content": ev.delta}}]}),
                            )));
                        }
                        return;
                    }
                    if let Some(side) = &side {
                        let _ = side.send(ev);
                    }
                })
            };
            let answer = match gs(cancel, req, emit).await {
                Ok(a) => a,
                Err(e) => {
                    let _ = ctx.send(Err(e));
                    return;
                }
            };
            let mut finish = "stop";
            if !answer.tool_calls.is_empty() {
                let calls: Vec<Value> = answer
                    .tool_calls
                    .iter()
                    .enumerate()
                    .map(|(i, c)| json!({"index": i, "id": call_id(c, i), "type": "function", "function": {"name": c.name, "arguments": encode_args(&c.arguments)}}))
                    .collect();
                let _ = ctx.send(Ok(sse(
                    &json!({"choices": [{"index": 0, "delta": {"tool_calls": calls}}]}),
                )));
                finish = "tool_calls";
            } else if !saw_text.load(std::sync::atomic::Ordering::SeqCst)
                && !answer.content.is_empty()
            {
                let _ = ctx.send(Ok(sse(
                    &json!({"choices": [{"index": 0, "delta": {"content": answer.content}}]}),
                )));
            }
            let _ = ctx.send(Ok(sse(
                &json!({"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]}),
            )));
            if let Some(u) = answer.usage {
                let total = if u.total_tokens == 0 {
                    u.prompt_tokens + u.completion_tokens
                } else {
                    u.total_tokens
                };
                let _ = ctx.send(Ok(sse(&json!({"choices": [], "usage": {
                    "prompt_tokens": u.prompt_tokens, "completion_tokens": u.completion_tokens, "total_tokens": total}}))));
            }
            let _ = ctx.send(Ok(b"data: [DONE]\n\n".to_vec()));
        });
        RawResponse {
            status: 200,
            retry_after: String::new(),
            body: RespBody::Chunks(crx),
        }
    }
}

const IN_PROCESS_BASE_URL: &str = "http://in-process.invalid/v1";

/// A client whose "provider" is a host function (`generate` and/or
/// `generate_stream`). Panics when neither is given, as in Go.
pub fn create_in_process_client(opts: InProcessOptions) -> Client {
    assert!(
        opts.generate.is_some() || opts.generate_stream.is_some(),
        "toolnexus: create_in_process_client requires a generate (or generate_stream) function"
    );
    let transport = Transport::InProcess(Arc::new(InProcess {
        generate: opts.generate.clone(),
        generate_stream: opts.generate_stream.clone(),
    }));
    Client::with_transport(
        ClientOptions {
            on_error: opts.on_error,
            retries: -1,
            base_url: IN_PROCESS_BASE_URL.into(),
            style: Style::OpenAI,
            api_key: "in-process".into(),
            model: opts.model,
            system_prompt: opts.system_prompt,
            max_turns: opts.max_turns,
            hooks: opts.hooks,
            timeout_ms: opts.timeout_ms,
            store: opts.store,
            on_metric: opts.on_metric,
            wait_for: opts.wait_for,
            wait_for_ctx: opts.wait_for_ctx,
            request_params: opts.request_params,
            body_transform: opts.body_transform,
            headers: opts.headers,
            ..Default::default()
        },
        transport,
    )
}
