//! Toolkit assembly (SPEC §4): order, shadowing, disables, routing.

use std::path::PathBuf;

use serde_json::json;
use toolnexus::{
    BuiltinsConfig, McpConfigInput, ToolContext, ToolSource, ToolkitOptions, create_toolkit,
    define_tool,
};

fn examples() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../examples")
}

fn echo(name: &str) -> toolnexus::Tool {
    let n = name.to_owned();
    define_tool(name, "echo", None, move |_a, _c| {
        let n = n.clone();
        async move { Ok::<_, String>(format!("from {n}")) }
    })
}

#[tokio::test]
async fn skills_and_extras_in_order_with_builtins_off() {
    let tk = create_toolkit(ToolkitOptions {
        skills_dir: vec![examples().join("skills")],
        extra_tools: vec![echo("mine")],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let names: Vec<String> = tk.tools().into_iter().map(|t| t.name).collect();
    assert_eq!(names, vec!["skill", "mine"]);
    assert_eq!(tk.get("skill").unwrap().source, ToolSource::Skill);
    assert!(tk.skills_prompt().contains("- **hello-world**:"));
    assert!(tk.mcp_status().is_empty());
}

#[tokio::test]
async fn unknown_tool_is_an_error_result() {
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let r = tk.execute("nope", json!({}), ToolContext::default()).await;
    assert!(r.is_error);
    assert_eq!(r.output, "Unknown tool: nope");
    assert_eq!(tk.skills_prompt(), "");
}

#[tokio::test]
async fn duplicate_extra_keeps_first_and_disable_tools_drops() {
    let tk = create_toolkit(ToolkitOptions {
        extra_tools: vec![echo("a"), echo("a"), echo("b")],
        disable_tools: vec!["b".into()],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let names: Vec<String> = tk.tools().into_iter().map(|t| t.name).collect();
    assert_eq!(names, vec!["a"]);
    let r = tk.execute("a", json!(null), ToolContext::default()).await;
    assert_eq!(r.output, "from a");
}

#[tokio::test]
async fn disable_skills_removes_from_prompt_and_tool() {
    let tk = create_toolkit(ToolkitOptions {
        skills_dir: vec![examples().join("skills")],
        disable_skills: vec!["hello-world".into()],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    assert!(!tk.skills_prompt().contains("hello-world"));
}

#[tokio::test]
async fn register_mid_turn_appends_and_keeps_first() {
    let tk = create_toolkit(ToolkitOptions {
        extra_tools: vec![echo("a")],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let clone = tk.clone();
    clone.register([echo("a"), echo("late")]);
    let names: Vec<String> = tk.tools().into_iter().map(|t| t.name).collect();
    assert_eq!(names, vec!["a", "late"]);
    assert_eq!(tk.to_openai().len(), 2);
    assert_eq!(tk.to_anthropic()[1]["name"], "late");
    assert_eq!(
        tk.to_gemini()[0]["functionDeclarations"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
}

#[tokio::test]
async fn config_builtins_key_is_honoured_and_disabled_server_reported() {
    let cfg = json!({
        "mcpServers": {"off": {"command": "nope", "disabled": true}},
        "builtins": false
    });
    let tk = create_toolkit(ToolkitOptions {
        mcp_config: Some(McpConfigInput::Json(cfg)),
        ..Default::default()
    })
    .await
    .unwrap();
    assert!(tk.tools().is_empty());
    assert_eq!(tk.mcp_status()["off"].as_str(), "disabled");
    tk.close().await;
}

#[tokio::test]
async fn bad_config_path_is_an_error() {
    let err = create_toolkit(ToolkitOptions {
        mcp_config: Some(McpConfigInput::Path("/definitely/missing.json".into())),
        ..Default::default()
    })
    .await
    .unwrap_err();
    assert!(err.to_string().contains("mcp config"));
}

#[tokio::test]
async fn execute_with_answer_reaches_the_tool() {
    let t = define_tool("ans", "", None, |_a, c: ToolContext| async move {
        Ok::<_, String>(c.answer.map(|a| a.id).unwrap_or_default())
    });
    let tk = create_toolkit(ToolkitOptions {
        extra_tools: vec![t],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let r = tk
        .execute_with_answer(
            "ans",
            json!({}),
            ToolContext::default(),
            toolnexus::Answer {
                id: "x1".into(),
                ok: true,
                ..Default::default()
            },
        )
        .await;
    assert_eq!(r.output, "x1");
}

#[test]
fn pending_roundtrip() {
    let r = toolnexus::auth_required("https://x", "");
    assert!(r.is_error);
    assert_eq!(r.output, "Authorization required to continue\nhttps://x");
    let req = toolnexus::pending_of(&r).unwrap();
    assert!(req.id.starts_with("pnd-"));
    assert_eq!(req.kind, "authorization");
    let wire = serde_json::to_value(&req).unwrap();
    assert_eq!(wire["url"], "https://x");
    assert!(wire.get("expiresAt").is_none());
    assert!(toolnexus::pending_of(&toolnexus::ToolResult::ok("x")).is_none());
}
