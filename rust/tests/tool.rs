//! Phase 4: core types, native tools, adapters (SPEC §0 items 1, 7, 8).

use serde::Deserialize;
use serde_json::json;
use toolnexus::*;

#[tokio::test]
async fn native_ok_and_err_and_null_args() {
    let t = define_tool("echo", "Echo.", None, |args, _ctx| async move {
        match args.get("x").and_then(|v| v.as_str()) {
            Some(x) => Ok(x.to_owned()),
            None => Err("x is required"),
        }
    });
    assert_eq!(t.source, ToolSource::Native);
    assert_eq!(
        t.input_schema,
        json!({"type": "object", "properties": {}, "additionalProperties": false})
    );
    assert_eq!(
        t.call(json!({"x": "hi"}), ToolContext::default()).await,
        ToolResult::ok("hi")
    );
    let e = t
        .call(serde_json::Value::Null, ToolContext::default())
        .await;
    assert!(e.is_error && e.output == "x is required");
}

#[derive(Deserialize)]
struct Add {
    a: f64,
    b: f64,
}

#[tokio::test]
async fn typed_tool_decodes_or_reports() {
    let schema = json!({"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]});
    let t = define_typed_tool("add", "Add.", schema.clone(), |i: Add, _ctx| async move {
        Ok::<_, String>((i.a + i.b).to_string())
    });
    assert_eq!(t.input_schema, schema);
    assert_eq!(
        t.call(json!({"a": 2, "b": 3}), ToolContext::default())
            .await
            .output,
        "5"
    );
    let bad = t.call(json!({"a": "x"}), ToolContext::default()).await;
    assert!(
        bad.is_error && bad.output.starts_with("invalid arguments:"),
        "{}",
        bad.output
    );
}

#[test]
fn tool_result_wire_keys() {
    let v = serde_json::to_value(ToolResult::error("boom")).unwrap();
    assert_eq!(v, json!({"output": "boom", "isError": true}));
    assert_eq!(
        serde_json::to_value(ToolSource::Builtin).unwrap(),
        json!("builtin")
    );
}

#[test]
fn adapters_match_spec_shapes() {
    let t = define_tool(
        "t",
        "D",
        Some(json!({"type": "object", "properties": {}})),
        |_, _| async { Ok::<_, String>(String::new()) },
    );
    let tools = vec![t];
    assert_eq!(
        to_openai(&tools),
        [
            json!({"type": "function", "function": {"name": "t", "description": "D", "parameters": {"type": "object", "properties": {}}}})
        ]
    );
    assert_eq!(
        to_anthropic(&tools),
        [
            json!({"name": "t", "description": "D", "input_schema": {"type": "object", "properties": {}}})
        ]
    );
    assert_eq!(
        to_gemini(&tools),
        [
            json!({"functionDeclarations": [{"name": "t", "description": "D", "parameters": {"type": "object", "properties": {}}}]})
        ]
    );
    assert_eq!(to_gemini(&[]), [json!({"functionDeclarations": []})]);
}
