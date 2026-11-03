//! SPEC §7G.9 / §7G.5 conformance: replays `../conformance/media/clients.json`
//! (built-in API clients a2e, elevenlabs, openai, openrouter, fal) against an
//! in-process server, function face and tool face. No network, no real keys.

mod common;
mod media_support;

use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use media_support::*;
use serde_json::Value;
use toolnexus::{
    A2EMediaClient, ElevenLabsMediaClient, FalMediaClient, MediaClient, MediaClientConfig,
    OpenAIMediaClient, OpenRouterMediaClient, ToolContext, create_media,
};

fn client(kind: &str, cfg: MediaClientConfig) -> Arc<dyn MediaClient> {
    match kind {
        "a2e" => Arc::new(A2EMediaClient::new(cfg)),
        "elevenlabs" => Arc::new(ElevenLabsMediaClient::new(cfg)),
        "openai" => Arc::new(OpenAIMediaClient::new(cfg)),
        "openrouter" => Arc::new(OpenRouterMediaClient::new(cfg)),
        "fal" => Arc::new(FalMediaClient::new(cfg)),
        other => panic!("unknown client {other}"),
    }
}

#[tokio::test]
async fn conformance_clients() {
    let f = load("clients.json");
    let cases = f["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 76, "clients.json case count");
    let mut tool_faces = 0;
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let kind = case["client"].as_str().unwrap();
        let verb = case["verb"].as_str().unwrap();
        let script: Vec<Value> = case["script"].as_array().unwrap().clone();

        let inputs = temp_dir("inputs");
        if let Some(files) = case.get("inputFiles").and_then(Value::as_object) {
            for (n, b) in files {
                std::fs::write(
                    inputs.join(n),
                    STANDARD.decode(b.as_str().unwrap()).unwrap(),
                )
                .unwrap();
            }
        }
        let inputs_s = inputs.to_string_lossy().into_owned();

        // function face
        let dir = temp_dir("dir");
        let (url, log) = script_server(script.clone()).await;
        let dir_s = dir.to_string_lossy().into_owned();
        let pairs = [
            ("{server}", url.as_str()),
            ("{dir}", dir_s.as_str()),
            ("{inputs}", inputs_s.as_str()),
        ];
        let c = subst(case, &pairs);
        set_env(&c, &pairs);
        let (cfg, prog) = config(c.get("config"));
        let media = create_media([client(kind, cfg)]).unwrap();
        let args = c["args"].as_object().unwrap().clone();
        let got = generate_case(&media, &c, verb, args.clone()).await;
        check_outcome(name, &c, &got, kind, verb, &dir);
        check_requests(name, &c, &log.lock().unwrap());
        check_header_patterns(name, &c, &log.lock().unwrap());
        check_progress(name, &c, &prog);

        // tool face: fresh script replay, fresh {dir}
        if let Some(want) = case.get("tool") {
            tool_faces += 1;
            let dir = temp_dir("dir");
            let (url, _log) = script_server(script).await;
            let dir_s = dir.to_string_lossy().into_owned();
            let pairs = [
                ("{server}", url.as_str()),
                ("{dir}", dir_s.as_str()),
                ("{inputs}", inputs_s.as_str()),
            ];
            let c = subst(case, &pairs);
            let want = subst(
                want,
                &[("{inputs}", inputs_s.as_str()), ("{server}", url.as_str())],
            );
            let (cfg, _) = config(c.get("config"));
            let media = create_media([client(kind, cfg)]).unwrap();
            let tname = tool_name(&want, verb);
            let tool = media
                .tools()
                .into_iter()
                .find(|t| t.name == tname)
                .unwrap_or_else(|| panic!("{name}: no tool {tname}"));
            let args = c["args"].clone();
            let r = tool.call(args, ToolContext::default()).await;
            check_tool(name, &want, &r, &dir);
        }
        clear_env(&c);
    }
    assert_eq!(tool_faces, 55, "clients.json tool-face count");
}

#[tokio::test]
async fn builtin_tool_schemas_follow_client_args() {
    let tools = create_media([OpenAIMediaClient::new(MediaClientConfig::default())])
        .unwrap()
        .tools();
    let names: Vec<&str> = tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(
        names,
        [
            "generate_image",
            "generate_video",
            "generate_speech",
            "transcribe_audio"
        ]
    );
    assert_eq!(
        tools[0].input_schema["properties"]
            .as_object()
            .unwrap()
            .keys()
            .cloned()
            .collect::<Vec<_>>()
            .len(),
        3
    );
    assert_eq!(
        tools[0].input_schema["required"],
        serde_json::json!(["prompt"])
    );
}
