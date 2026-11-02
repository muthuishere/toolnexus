//! §8A content-part emission and the tool-result relocation rule.

mod common;

use std::sync::{Arc, Mutex};

use common::*;
use serde_json::{Value, json};
use toolnexus::client::*;
use toolnexus::{ContentPart, Tool, ToolResult, create_toolkit, define_tool};

fn opts(url: &str) -> ClientOptions {
    ClientOptions {
        base_url: url.into(),
        model: "m".into(),
        api_key: "k".into(),
        ..Default::default()
    }
}

fn image_tool() -> Tool {
    let mut t = define_tool("shot", "screenshot", None, |_, _| async {
        Ok::<_, String>(String::new())
    });
    t.execute = Arc::new(|_, _| {
        Box::pin(async {
            ToolResult {
                output: "captured".into(),
                parts: vec![ContentPart {
                    kind: "image".into(),
                    mime_type: "image/png".into(),
                    data: "iVBORw0KGgo=".into(),
                    ..Default::default()
                }],
                ..Default::default()
            }
        })
    });
    t
}

#[tokio::test]
async fn tool_parts_relocated_for_openai_native_for_anthropic() {
    for style in [Style::OpenAI, Style::Anthropic] {
        let wire = Arc::new(Mutex::new(Vec::<Value>::new()));
        let w2 = wire.clone();
        let srv = serve(move |r: Req| {
            let w = w2.clone();
            async move {
                let anthropic = r.path.contains("messages");
                if r.messages().len() > 1 {
                    *w.lock().unwrap() = r.messages();
                    if anthropic {
                        return Resp::json(json!({"content": [{"type": "text", "text": "seen"}]}));
                    }
                    return Resp::json(oa_text("seen"));
                }
                if anthropic {
                    return Resp::json(
                        json!({"content": [{"type": "tool_use", "id": "t1", "name": "shot", "input": {}}]}),
                    );
                }
                Resp::json(oa_calls(&[("t1", "shot", "{}")]))
            }
        })
        .await;
        let tk = create_toolkit(toolnexus::ToolkitOptions {
            builtins: Some(toolnexus::BuiltinsConfig::off()),
            extra_tools: vec![image_tool()],
            ..Default::default()
        })
        .await
        .unwrap();
        let res = create_client(ClientOptions {
            style,
            ..opts(&srv.url)
        })
        .run("look", &tk)
        .await
        .unwrap();
        assert_eq!(res.text, "seen");
        let w = wire.lock().unwrap().clone();
        match style {
            Style::OpenAI => {
                assert_eq!(
                    w[2],
                    json!({"role": "tool", "tool_call_id": "t1", "content": "captured"})
                );
                assert_eq!(w[3]["role"], "user");
                assert_eq!(w[3]["content"][0]["text"], "Output of tool shot (t1):");
                assert_eq!(
                    w[3]["content"][1]["image_url"]["url"],
                    "data:image/png;base64,iVBORw0KGgo="
                );
            }
            Style::Anthropic => {
                let tr = &w[2]["content"][0];
                assert!(
                    tr.get("parts").is_none() && tr.get("name").is_none(),
                    "{tr}"
                );
                assert_eq!(
                    tr["content"][0],
                    json!({"type": "text", "text": "captured"})
                );
                assert_eq!(tr["content"][1]["source"]["media_type"], "image/png");
            }
        }
    }
}

#[tokio::test]
async fn multimodal_prompt_and_part_validation() {
    let seen = Arc::new(Mutex::new(Value::Null));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let s = s2.clone();
        async move {
            *s.lock().unwrap() = r.messages()[0].clone();
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let c = create_client(ClientOptions {
        max_part_bytes: 4,
        ..opts(&srv.url)
    });
    let text = ContentPart {
        kind: "text".into(),
        text: "hi".into(),
        ..Default::default()
    };
    let img = ContentPart {
        kind: "image".into(),
        mime_type: "image/png".into(),
        url: "https://x/i.png".into(),
        ..Default::default()
    };
    c.run(vec![text, img], &tk).await.unwrap();
    let m = seen.lock().unwrap().clone();
    assert_eq!(m["content"][0], json!({"type": "text", "text": "hi"}));
    assert_eq!(
        m["content"][1],
        json!({"type": "image_url", "image_url": {"url": "https://x/i.png"}})
    );
    let big = ContentPart {
        kind: "image".into(),
        mime_type: "image/png".into(),
        data: "AAAAAAAAAAAA".into(),
        ..Default::default()
    };
    assert!(matches!(
        c.run(vec![big], &tk).await,
        Err(ClientError::Part(_))
    ));
}
