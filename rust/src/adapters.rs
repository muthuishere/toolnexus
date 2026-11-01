//! Schema adapters (SPEC §0 item 7): the same tools in each provider's shape.
//! Schema only — the input schema is passed through verbatim.

use serde_json::{Value, json};

use crate::tool::Tool;

/// OpenAI: `[{type:"function", function:{name, description, parameters}}]`.
pub fn to_openai(tools: &[Tool]) -> Vec<Value> {
    tools
        .iter()
        .map(|t| json!({"type": "function", "function": {"name": t.name, "description": t.description, "parameters": t.input_schema}}))
        .collect()
}

/// Anthropic: `[{name, description, input_schema}]`.
pub fn to_anthropic(tools: &[Tool]) -> Vec<Value> {
    tools.iter().map(|t| json!({"name": t.name, "description": t.description, "input_schema": t.input_schema})).collect()
}

/// Gemini: `[{functionDeclarations:[{name, description, parameters}]}]` — one element wrapping all.
pub fn to_gemini(tools: &[Tool]) -> Vec<Value> {
    let decls: Vec<Value> = tools
        .iter()
        .map(
            |t| json!({"name": t.name, "description": t.description, "parameters": t.input_schema}),
        )
        .collect();
    vec![json!({"functionDeclarations": decls})]
}
