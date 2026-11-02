//! `list_models` (SPEC §4C): a provider's models, for model switching.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{ClientError, ClientOptions, Style, create_client};

/// One model. `context_length` is set when the provider reports it (OpenRouter).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModelInfo {
    pub id: String,
    #[serde(skip_serializing_if = "String::is_empty", default)]
    pub name: String,
    #[serde(rename = "contextLength", skip_serializing_if = "is_zero", default)]
    pub context_length: u64,
}

fn is_zero(n: &u64) -> bool {
    *n == 0
}

async fn get_json(
    http: &reqwest::Client,
    url: &str,
    headers: &[(&str, String)],
) -> Result<Value, ClientError> {
    let mut req = http.get(url);
    for (k, v) in headers {
        req = req.header(*k, v);
    }
    let resp = req
        .send()
        .await
        .map_err(|e| ClientError::Transport(e.to_string()))?;
    let status = resp.status().as_u16();
    let raw = resp
        .bytes()
        .await
        .map_err(|e| ClientError::Transport(e.to_string()))?;
    if status / 100 != 2 {
        let mut msg = String::from_utf8_lossy(&raw).into_owned();
        if msg.len() > 300 {
            let mut n = 300;
            while !msg.is_char_boundary(n) {
                n -= 1;
            }
            msg.truncate(n);
        }
        return Err(ClientError::Transport(format!(
            "list models: HTTP {status}: {msg}"
        )));
    }
    serde_json::from_slice(&raw).map_err(|e| ClientError::Decode(e.to_string()))
}

/// List a provider's models, sorted by id. The key follows the same by-host rule
/// as the client.
pub async fn list_models(opts: ClientOptions) -> Result<Vec<ModelInfo>, ClientError> {
    let http = opts.http_client.clone().unwrap_or_default();
    let style = opts.style;
    let base = opts.base_url.trim_end_matches('/').to_owned();
    let key = create_client(opts).resolve_key()?;
    let s = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_owned();
    let mut out = Vec::new();
    if style == Style::Anthropic {
        let endpoint = if base.ends_with("/v1") {
            format!("{base}/models")
        } else {
            format!("{base}/v1/models")
        };
        let mut after = String::new();
        for _ in 0..100 {
            let mut u = format!("{endpoint}?limit=1000");
            if !after.is_empty() {
                u.push_str("&after_id=");
                u.push_str(&url_escape(&after));
            }
            let body = get_json(
                &http,
                &u,
                &[
                    ("x-api-key", key.clone()),
                    ("anthropic-version", "2023-06-01".into()),
                ],
            )
            .await?;
            for m in body
                .get("data")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                out.push(ModelInfo {
                    id: s(m, "id"),
                    name: s(m, "display_name"),
                    context_length: 0,
                });
            }
            let last = s(&body, "last_id");
            if !body
                .get("has_more")
                .and_then(Value::as_bool)
                .unwrap_or(false)
                || last.is_empty()
            {
                break;
            }
            after = last;
        }
    } else {
        let body = get_json(
            &http,
            &format!("{base}/models"),
            &[("Authorization", format!("Bearer {key}"))],
        )
        .await?;
        for m in body
            .get("data")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            out.push(ModelInfo {
                id: s(m, "id"),
                name: s(m, "name"),
                context_length: m
                    .get("context_length")
                    .and_then(Value::as_f64)
                    .unwrap_or(0.0) as u64,
            });
        }
    }
    out.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(out)
}

fn url_escape(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}
