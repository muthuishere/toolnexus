//! MCP elicitation bridge (SPEC §2, mapped onto §10). A server may ask US for
//! input mid-`tools/call` (`elicitation/create`, a reverse request). It is
//! bridged onto the one §10 `wait_for`: form mode → `kind:"input"` with the
//! requested schema in `data.schema`; URL mode → `kind:"authorization"` with
//! `url`. The Answer maps back: `ok` → `accept` (content = `answer.data`, or
//! `{}`); `reason == "declined"` → `decline`; anything else → `cancel`. The
//! reverse request is satisfied inline — the tool is not re-executed.
//!
//! Without a `wait_for` the capability is not advertised, so a compliant
//! server does not elicit. Port of the bridge in `golang/mcp.go`.

use std::sync::atomic::{AtomicU64, Ordering};

use rmcp::model::{
    ClientCapabilities, ClientConfig, ElicitRequestParams, ElicitResult, ElicitationCapability,
    FormElicitationCapability, UrlElicitationCapability,
};
use rmcp::service::RequestContext;
use rmcp::{ClientHandler, RoleClient};
use serde_json::{Map, Value, json};

use crate::client::WaitForFn;
use crate::tool::{Answer, Request};

static ELC_SEQ: AtomicU64 = AtomicU64::new(0);

/// Map `elicitation/create` params (wire JSON: `mode`, `message`,
/// `requestedSchema`, `url`) onto a §10 Request. Ids are `elc-<ms36>-<seq>`.
pub fn elicitation_to_request(params: &Value) -> Request {
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let seq = ELC_SEQ.fetch_add(1, Ordering::SeqCst) + 1;
    let mut req = Request {
        id: format!("elc-{}-{}", crate::tool::to_base36(ms), seq),
        prompt: params
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("")
            .into(),
        ..Default::default()
    };
    if params.get("mode").and_then(Value::as_str) == Some("url") {
        req.kind = "authorization".into();
        req.url = params
            .get("url")
            .and_then(Value::as_str)
            .unwrap_or("")
            .into();
    } else {
        req.kind = "input".into();
        if let Some(schema) = params.get("requestedSchema").filter(|v| !v.is_null()) {
            let mut data = Map::new();
            data.insert("schema".into(), schema.clone());
            req.data = Some(data);
        }
    }
    req
}

/// Map a resolved §10 Answer onto an MCP elicitation result (wire JSON):
/// `ok` → accept (+content), `reason:"declined"` → decline, else cancel.
pub fn answer_to_elicit_result(answer: &Answer) -> Value {
    if answer.ok {
        let content = answer.data.clone().unwrap_or_default();
        return json!({"action": "accept", "content": content});
    }
    if answer.reason == "declined" {
        json!({"action": "decline"})
    } else {
        json!({"action": "cancel"})
    }
}

/// The rmcp client handler: advertises elicitation only when a `wait_for`
/// exists, and answers it through that `wait_for`.
#[derive(Clone, Default)]
pub(crate) struct Bridge {
    pub(crate) wait_for: Option<WaitForFn>,
}

impl ClientHandler for Bridge {
    fn get_info(&self) -> ClientConfig {
        let mut cfg = ClientConfig::default();
        if self.wait_for.is_some() {
            let mut el = ElicitationCapability::new();
            el.form = Some(FormElicitationCapability::new());
            el.url = Some(UrlElicitationCapability::new());
            let mut caps: ClientCapabilities = cfg.capabilities.clone();
            caps.elicitation = Some(el);
            cfg.capabilities = caps;
        }
        cfg
    }

    async fn create_elicitation(
        &self,
        request: ElicitRequestParams,
        _context: RequestContext<RoleClient>,
    ) -> Result<ElicitResult, rmcp::ErrorData> {
        let Some(w) = self.wait_for.clone() else {
            return Ok(ElicitResult::new(rmcp::model::ElicitationAction::Decline));
        };
        let params = serde_json::to_value(&request).unwrap_or(Value::Null);
        let answer = w(elicitation_to_request(&params))
            .await
            .map_err(|e| rmcp::ErrorData::internal_error(e, None))?;
        serde_json::from_value(answer_to_elicit_result(&answer))
            .map_err(|e| rmcp::ErrorData::internal_error(e.to_string(), None))
    }
}
