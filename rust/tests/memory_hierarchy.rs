//! Memory hierarchy + dreaming (SPEC §7E "Memory hierarchy" / "Dreaming"),
//! driven by the shared fixtures examples/memory-hierarchy/fixture.json and
//! examples/dreaming/fixture.json. Hermetic: a scripted in-process model, a
//! virtual clock, temp dirs. Mirrors js/test/memory-hierarchy.test.ts.

mod agents_support;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use agents_support::*;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use toolnexus::agents::{
    ComposeSoulOptions, DREAM_PROMPT, DreamOptions, DreamerOptions, FromDirOptions, HEARTBEAT_OK,
    HEARTBEAT_PROMPT, MEMORY_INDEX_CAP_PRESET, MEMORY_NOTES_DESCRIPTION, MemoryIndexCap,
    MemoryToolOptions, Options, StartOptions, compose_soul, compose_soul_with, dreamer, from_dir,
    memory_tool, memory_tool_with, start_agent,
};
use toolnexus::client::GenerateFn;
use toolnexus::store::{ConversationInfo, ConversationStore, InMemoryConversationStore};
use toolnexus::{Tool, ToolContext, TranscriptSearchOptions, transcript_search_tool};

static SEQ: AtomicUsize = AtomicUsize::new(0);

fn load(name: &str) -> Value {
    let p = Path::new(env!("CARGO_MANIFEST_DIR")).join(format!("../examples/{name}/fixture.json"));
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

fn s(v: &Value) -> &str {
    v.as_str().unwrap_or("")
}

fn mk_home(files: &Value) -> PathBuf {
    let d = std::env::temp_dir().join(format!(
        "tn-memh-{}-{}",
        std::process::id(),
        SEQ.fetch_add(1, Ordering::SeqCst)
    ));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    if let Some(m) = files.as_object() {
        for (f, body) in m {
            let p = d.join(f);
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, s(body)).unwrap();
        }
    }
    d
}

/// Every file under `dir`, keyed by '/'-separated relative path.
fn snapshot(dir: &Path) -> BTreeMap<String, String> {
    fn walk(p: &Path, rel: &str, out: &mut BTreeMap<String, String>) {
        for e in std::fs::read_dir(p).unwrap().flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if e.path().is_dir() {
                walk(&e.path(), &format!("{rel}{name}/"), out);
            } else {
                out.insert(
                    format!("{rel}{name}"),
                    std::fs::read_to_string(e.path()).unwrap(),
                );
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(dir, "", &mut out);
    out
}

fn str_map(v: &Value) -> BTreeMap<String, String> {
    v.as_object()
        .map(|m| {
            m.iter()
                .map(|(k, v)| (k.clone(), s(v).to_string()))
                .collect()
        })
        .unwrap_or_default()
}

/// The fixtures' store: `list()` in fixture order, `get(id)`, and a `save`
/// that must never be called.
struct StubStore {
    convs: Vec<(String, Vec<Value>)>,
    reads: AtomicUsize,
    lists: bool,
}

fn stub_store(conversations: &Value) -> Arc<StubStore> {
    Arc::new(StubStore {
        convs: conversations
            .as_array()
            .unwrap()
            .iter()
            .map(|c| {
                (
                    s(&c["id"]).to_string(),
                    c["messages"].as_array().unwrap().clone(),
                )
            })
            .collect(),
        reads: AtomicUsize::new(0),
        lists: true,
    })
}

impl ConversationStore for StubStore {
    fn get(&self, id: &str) -> Result<Option<Vec<Value>>, String> {
        self.reads.fetch_add(1, Ordering::SeqCst);
        Ok(self
            .convs
            .iter()
            .find(|(i, _)| i == id)
            .map(|(_, m)| m.clone()))
    }
    fn save(&self, _: &str, _: &[Value]) -> Result<(), String> {
        panic!("transcript search must never write")
    }
    fn supports_list(&self) -> bool {
        self.lists
    }
    fn list(&self) -> Result<Vec<ConversationInfo>, String> {
        self.reads.fetch_add(1, Ordering::SeqCst);
        Ok(self
            .convs
            .iter()
            .map(|(id, _)| ConversationInfo {
                id: id.clone(),
                updated_at: std::time::UNIX_EPOCH,
            })
            .collect())
    }
}

/// A store with only get/save (the default, unsupported `list`).
struct NoListStore;
impl ConversationStore for NoListStore {
    fn get(&self, _: &str) -> Result<Option<Vec<Value>>, String> {
        Ok(None)
    }
    fn save(&self, _: &str, _: &[Value]) -> Result<(), String> {
        Ok(())
    }
}

async fn exec(t: &Tool, args: Value) -> Value {
    let r = t.call(args, ToolContext::default()).await;
    json!({"output": r.output, "isError": r.is_error})
}

// ===========================================================================
// G1 — index cap
// ===========================================================================

fn cap_memory(c: &Value) -> String {
    if let Some(m) = c["memory"].as_str() {
        return m.to_string();
    }
    let g = &c["generate"];
    (1..=g["count"].as_u64().unwrap())
        .map(|i| s(&g["line"]).replace("<i>", &i.to_string()) + "\n")
        .collect()
}

fn cap_opts(o: &Value) -> ComposeSoulOptions {
    let memory_index_cap = match &o["memoryIndexCap"] {
        Value::Bool(true) => Some(MemoryIndexCap::default()),
        Value::Object(m) => Some(MemoryIndexCap {
            max_lines: m
                .get("maxLines")
                .and_then(Value::as_u64)
                .map(|n| n as usize),
            max_bytes: m
                .get("maxBytes")
                .and_then(Value::as_u64)
                .map(|n| n as usize),
        }),
        _ => None,
    };
    ComposeSoulOptions {
        memory_index_cap,
        notes: o["notes"].as_bool().unwrap_or(false),
    }
}

fn home_with_memory(c: &Value) -> (PathBuf, String) {
    let mem = cap_memory(c);
    let mut files = c["files"].clone();
    files["MEMORY.md"] = json!(mem);
    (mk_home(&files), mem)
}

fn sha(s: &str) -> String {
    Sha256::digest(s.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

#[test]
fn g1_index_cap_cases() {
    let mh = load("memory-hierarchy");
    let p = &mh["indexCap"]["preset"];
    assert_eq!(
        (
            p["maxLines"].as_u64().unwrap() as usize,
            p["maxBytes"].as_u64().unwrap() as usize
        ),
        MEMORY_INDEX_CAP_PRESET
    );
    for c in mh["indexCap"]["cases"].as_array().unwrap() {
        let name = s(&c["name"]);
        let (dir, mem) = home_with_memory(c);
        let (soul, _) = compose_soul_with(&dir, cap_opts(&c["options"])).unwrap();
        let e = &c["expect"];
        assert_eq!(sha(&soul), s(&e["soulSha256"]), "{name}");
        assert_eq!(
            soul.len() as u64,
            e["soulBytes"].as_u64().unwrap(),
            "{name}"
        );
        assert!(soul.ends_with(s(&e["memorySectionTail"])), "{name}");
        assert_eq!(
            soul == compose_soul(&dir).0,
            e["equalsUncappedSoul"].as_bool().unwrap(),
            "{name}"
        );
        assert_eq!(
            std::fs::read_to_string(dir.join("MEMORY.md")).unwrap(),
            mem,
            "{name}"
        );
    }
}

#[test]
fn g1_from_dir_threads_cap_and_invalid_cap_errors() {
    let mh = load("memory-hierarchy");
    let c = mh["indexCap"]["cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["name"] == "lines-cap-preset-notes-off")
        .unwrap();
    let (dir, _) = home_with_memory(c);
    let soul = from_dir(
        &dir,
        FromDirOptions {
            memory_index_cap: Some(MemoryIndexCap::default()),
            ..Default::default()
        },
    )
    .spec
    .soul;
    assert_eq!(sha(&soul), s(&c["expect"]["soulSha256"]));
    assert!(
        soul.contains("- fact 200\n\n[MEMORY.md index truncated") && !soul.contains("- fact 201")
    );
    let bad = ComposeSoulOptions {
        memory_index_cap: Some(MemoryIndexCap {
            max_lines: Some(0),
            max_bytes: None,
        }),
        notes: false,
    };
    assert!(compose_soul_with(&dir, bad).is_err());
    let panicked = std::panic::catch_unwind(|| {
        from_dir(
            &dir,
            FromDirOptions {
                memory_index_cap: Some(MemoryIndexCap {
                    max_lines: None,
                    max_bytes: Some(0),
                }),
                ..Default::default()
            },
        )
    });
    assert!(panicked.is_err());
}

// ===========================================================================
// G2 — notes tier
// ===========================================================================

#[test]
fn g2_schema_and_description_pinned() {
    let mt = &load("memory-hierarchy")["memoryTool"];
    let off = memory_tool("/x");
    assert_eq!(off.description, s(&mt["notesOff"]["description"]));
    assert_eq!(off.input_schema, mt["notesOff"]["inputSchema"]);
    let off2 = memory_tool_with("/x", MemoryToolOptions { notes: false });
    assert_eq!(
        (off2.description, off2.input_schema),
        (off.description, off.input_schema)
    );
    let on = memory_tool_with("/x", MemoryToolOptions { notes: true });
    assert_eq!(on.description, s(&mt["notesOn"]["description"]));
    assert_eq!(on.input_schema, mt["notesOn"]["inputSchema"]);
    let dir = mk_home(&json!({"SOUL.md": "S"}));
    let p = from_dir(
        &dir,
        FromDirOptions {
            notes: true,
            ..Default::default()
        },
    );
    let mem = p.spec.tools.iter().find(|t| t.name == "memory").unwrap();
    assert_eq!(mem.description, s(&mt["notesOn"]["description"]));
}

#[tokio::test]
async fn g2_memory_tool_cases() {
    let mt = &load("memory-hierarchy")["memoryTool"];
    for c in mt["cases"].as_array().unwrap() {
        let name = s(&c["name"]);
        let dir = mk_home(&c["seed"]);
        let tool = memory_tool_with(
            &dir,
            MemoryToolOptions {
                notes: c["notes"].as_bool().unwrap_or(false),
            },
        );
        for op in c["ops"].as_array().unwrap() {
            assert_eq!(
                exec(&tool, op["args"].clone()).await,
                op["expect"],
                "{name}: {}",
                op["args"]
            );
        }
        assert_eq!(snapshot(&dir), str_map(&c["expectFiles"]), "{name}");
        if let Some(must) = c["soulMustNotContain"].as_str() {
            let opts = ComposeSoulOptions {
                notes: true,
                ..Default::default()
            };
            assert!(
                !compose_soul_with(&dir, opts).unwrap().0.contains(must),
                "{name}"
            );
        }
    }
}

// ===========================================================================
// G3 — transcript search
// ===========================================================================

#[tokio::test]
async fn g3_transcript_search_cases() {
    let ts = &load("memory-hierarchy")["transcriptSearch"];
    let convs = &ts["store"]["conversations"];
    let t = transcript_search_tool(stub_store(convs), TranscriptSearchOptions::default()).unwrap();
    assert_eq!(t.name, s(&ts["defaults"]["name"]));
    assert_eq!(t.description, s(&ts["description"]));
    assert_eq!(t.input_schema, ts["inputSchema"]);
    for c in ts["cases"].as_array().unwrap() {
        let o = &c["options"];
        let opts = TranscriptSearchOptions {
            max_results: o["maxResults"].as_u64().map(|n| n as usize),
            snippet_chars: o["snippetChars"].as_u64().map(|n| n as usize),
            name: None,
        };
        let tool = transcript_search_tool(stub_store(convs), opts).unwrap();
        let want = c["expect"].clone();
        assert_eq!(
            exec(&tool, c["args"].clone()).await,
            want,
            "{}",
            s(&c["name"])
        );
    }
}

#[tokio::test]
async fn g3_empty_query_store_checks_and_in_memory_store() {
    let ts = &load("memory-hierarchy")["transcriptSearch"];
    let store = stub_store(&ts["store"]["conversations"]);
    let t = transcript_search_tool(store.clone(), TranscriptSearchOptions::default()).unwrap();
    let r = exec(&t, json!({"query": ""})).await;
    assert_eq!(r["isError"], true);
    assert_eq!(store.reads.load(Ordering::SeqCst), 0);
    let err = transcript_search_tool(Arc::new(NoListStore), TranscriptSearchOptions::default())
        .err()
        .unwrap();
    assert_eq!(err, s(&ts["storeWithoutList"]["error"]));
    let zero = TranscriptSearchOptions {
        max_results: Some(0),
        ..Default::default()
    };
    assert!(transcript_search_tool(store.clone(), zero).is_err());

    let mem = Arc::new(InMemoryConversationStore::new());
    mem.save(
        "only",
        &[json!({"role": "user", "content": "The vault code is 4417"})],
    )
    .unwrap();
    let t = transcript_search_tool(mem, TranscriptSearchOptions::default()).unwrap();
    assert_eq!(
        exec(&t, json!({"query": "VAULT"})).await["output"],
        "only#0 user: The vault code is 4417"
    );
}

// ===========================================================================
// G4 — write safety
// ===========================================================================

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn g4_concurrent_adds_keep_every_entry() {
    let ca = &load("memory-hierarchy")["writeSafety"]["concurrentAdds"];
    let (writers, each, want) = (
        ca["writers"].as_u64().unwrap(),
        ca["addsEach"].as_u64().unwrap(),
        ca["expectEntries"].as_u64().unwrap() as usize,
    );
    let dir = mk_home(&json!({}));
    let mut joins = Vec::new();
    for w in 0..writers {
        let tool = Arc::new(memory_tool(&dir)); // one tool per writer, ONE home
        for n in 0..each {
            let tool = tool.clone();
            joins.push(tokio::spawn(async move {
                let r = tool
                    .call(
                        json!({"action": "add", "text": format!("w{w}-{n}")}),
                        ToolContext::default(),
                    )
                    .await;
                assert!(!r.is_error, "{}", r.output);
            }));
        }
    }
    for j in joins {
        j.await.unwrap();
    }
    let body = std::fs::read_to_string(dir.join("MEMORY.md")).unwrap();
    let lines: Vec<&str> = body.trim_end().split('\n').collect();
    let uniq: std::collections::HashSet<&&str> = lines.iter().collect();
    assert_eq!((lines.len(), uniq.len()), (want, want));
}

#[tokio::test]
async fn g4_large_file_add_keeps_disk_no_temp_left() {
    let lf = &load("memory-hierarchy")["writeSafety"]["largeFileAdd"];
    let original = s(&lf["fill"]).repeat(lf["bytes"].as_u64().unwrap() as usize);
    let dir = mk_home(&json!({"MEMORY.md": original}));
    let tool = memory_tool_with(&dir, MemoryToolOptions { notes: true });
    let r = exec(&tool, json!({"action": "add", "text": lf["addText"]})).await;
    assert_eq!(r["isError"], false, "{r}");
    assert!(
        std::fs::read_to_string(dir.join("MEMORY.md")).unwrap()
            == format!("{original}\n- {}\n", s(&lf["addText"]))
    );
    let names: Vec<String> = std::fs::read_dir(&dir)
        .unwrap()
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    assert_eq!(names, vec!["MEMORY.md"]);
}

// ===========================================================================
// Dreaming
// ===========================================================================

/// The dreaming fixture's scripted models. The dreamer is recognised by its
/// system prompt, every other call by model id.
fn dream_mock(scripts: Value, which: Arc<Mutex<&'static str>>) -> GenerateFn {
    let scripts = Arc::new(scripts);
    script(move |p: Parsed| {
        let (scripts, which) = (scripts.clone(), which.clone());
        Box::pin(async move {
            let fix = &scripts["dreamer-fix"];
            let call = |n: usize| {
                let tc = &fix[n]["toolCalls"][0];
                calls(vec![call_part(
                    s(&tc["name"]),
                    tc["args"].clone(),
                    &format!("d{n}"),
                )])
            };
            if p.system.contains("You are dreaming:") {
                let w = *which.lock().unwrap();
                if w == "dreamer-idle" {
                    return Ok(text(s(&scripts[w][0]["text"])));
                }
                let t = &p.tool_msgs;
                return Ok(match t.len() {
                    0 | 1 => call(t.len()),
                    2 if t[0].contains("Mt. Moon exit is south")
                        && t[1].contains("exit is north") =>
                    {
                        call(2)
                    }
                    2 => text(HEARTBEAT_OK),
                    3 => call(3),
                    _ => text(s(&fix[4]["text"])),
                });
            }
            Ok(match p.model.as_str() {
                "m-navigator" if p.system.contains("Mt. Moon exit is north") => {
                    text("Head north out of Mt. Moon.")
                }
                "m-navigator" => text("Head south out of Mt. Moon."),
                _ => text(HEARTBEAT_OK),
            })
        })
    })
}

struct Dream {
    fx: Value,
    which: Arc<Mutex<&'static str>>,
}

impl Dream {
    fn new() -> Self {
        Dream {
            fx: load("dreaming"),
            which: Arc::new(Mutex::new("dreamer-fix")),
        }
    }
    fn opts(&self) -> Options {
        Options {
            in_process: Some(dream_mock(
                self.fx["mockLLM"]["scripts"].clone(),
                self.which.clone(),
            )),
            ..Default::default()
        }
    }
    fn store(&self) -> Arc<dyn ConversationStore> {
        stub_store(&self.fx["store"]["conversations"])
    }
    fn home(&self) -> PathBuf {
        mk_home(&self.fx["home"])
    }
    fn dreamer(&self, home: &Path) -> toolnexus::agents::Agent {
        dreamer(
            home,
            DreamerOptions {
                store: Some(self.store()),
                ..Default::default()
            },
        )
        .unwrap()
    }
}

#[test]
fn d0_dream_prompt_is_the_fixture_text() {
    assert_eq!(DREAM_PROMPT, s(&load("dreaming")["dreamPrompt"]));
}

/// D1 — the BUILD GATE (Mt. Moon): one dream pass fixes the wrong claim, logs
/// it once, and the NEXT session answers differently. Then a second pass is
/// silent and writes nothing.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d1_mt_moon_build_gate() {
    let d = Dream::new();
    let g = &d.fx["expect"]["D1_mtMoonBuildGate"];
    let home = d.home();
    let persona = |model: &str| {
        from_dir(
            &home,
            FromDirOptions {
                model: model.into(),
                ..Default::default()
            },
        )
    };
    let (before, _) = persona(s(&g["before"]["model"]))
        .run(d.opts(), s(&g["before"]["prompt"]))
        .await;
    assert_eq!(before.text, s(&g["before"]["text"]));

    let (pass, _) = d.dreamer(&home).run(d.opts(), HEARTBEAT_PROMPT).await;
    assert_eq!(pass.text, s(&g["pass"]["replyText"]));
    let snap = snapshot(&home);
    for (f, body) in str_map(&g["after"]["files"]) {
        assert_eq!(snap.get(&f), Some(&body), "{f}");
    }
    let entries = snap["memory/log.md"]
        .split('\n')
        .filter(|l| l.starts_with("- "))
        .count();
    assert_eq!(entries, 1);

    let next = &g["after"]["nextSession"];
    let (after, _) = persona(s(&next["model"]))
        .run(d.opts(), s(&next["prompt"]))
        .await;
    assert_eq!(after.text, s(&next["text"]));

    // D2 (second case): the claim is already right — silent, nothing written.
    let frozen = snapshot(&home);
    let (again, _) = d.dreamer(&home).run(d.opts(), HEARTBEAT_PROMPT).await;
    assert!(again.text.contains(HEARTBEAT_OK), "{}", again.text);
    assert_eq!(snapshot(&home), frozen);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d2_idle_dreamer_writes_nothing() {
    let d = Dream::new();
    *d.which.lock().unwrap() = "dreamer-idle";
    let home = d.home();
    let frozen = snapshot(&home);
    let (r, _) = d.dreamer(&home).run(d.opts(), HEARTBEAT_PROMPT).await;
    assert!(r.text.contains(HEARTBEAT_OK));
    assert_eq!(snapshot(&home), frozen);
}

#[test]
fn d3_dreamer_tools_exactly() {
    let d = Dream::new();
    let e = &d.fx["expect"]["D3_dreamerToolsExactly"];
    let home = d.home();
    let names = |v: &Value| -> Vec<String> {
        v.as_array()
            .unwrap()
            .iter()
            .map(|x| s(x).to_string())
            .collect()
    };
    let persona_tools: Vec<Tool> = names(&e["personaTools"])
        .into_iter()
        .map(|n| {
            let mut t = memory_tool("/unused");
            t.name = n.clone();
            t.description = n;
            t
        })
        .collect();
    let persona = from_dir(
        &home,
        FromDirOptions {
            tools: persona_tools,
            ..Default::default()
        },
    );
    let mut want = names(&e["personaTools"]);
    want.push("memory".into());
    let got: Vec<String> = persona.spec.tools.iter().map(|t| t.name.clone()).collect();
    assert_eq!(got, want);
    let dr = d.dreamer(&home);
    let got: Vec<String> = dr.spec.tools.iter().map(|t| t.name.clone()).collect();
    assert_eq!(got, names(&e["dreamerTools"]));
    assert_eq!(dr.spec.tools[0].description, MEMORY_NOTES_DESCRIPTION);
}

#[test]
fn d3b_dreamer_soul_name_and_store_checks() {
    let d = Dream::new();
    let e = &d.fx["expect"]["D3b_dreamerSoul"];
    let plain = d.home();
    let dr = d.dreamer(&plain);
    assert_eq!(dr.spec.soul, s(&e["soulWithoutExtras"]));
    assert_eq!(
        dr.name,
        format!("{}-dreamer", plain.file_name().unwrap().to_string_lossy())
    );
    let mut files = d.fx["home"].clone();
    files["DREAM.md"] = e["DREAM.md"].clone();
    let extras = mk_home(&files);
    let dr = dreamer(
        &extras,
        DreamerOptions {
            store: Some(d.store()),
            instructions: s(&e["instructions"]).into(),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(dr.spec.soul, s(&e["soul"]));
    let no_list = dreamer(
        &plain,
        DreamerOptions {
            store: Some(Arc::new(NoListStore)),
            ..Default::default()
        },
    );
    assert!(no_list.err().unwrap().contains("list()"));
    let missing = dreamer(&plain, DreamerOptions::default());
    assert_eq!(missing.err().unwrap(), "dreamer: store is required");
    let zero = std::panic::catch_unwind(|| {
        from_dir(
            &plain,
            FromDirOptions {
                dream: Some(DreamOptions {
                    every_ms: 0,
                    store: d.store(),
                    instructions: String::new(),
                }),
                ..Default::default()
            },
        )
    });
    assert!(zero.is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d4_from_dir_dream_runs_on_the_persona_runtime() {
    let d = Dream::new();
    let e = &d.fx["expect"]["D4_fromDirDream"];
    let home = d.home();
    let clk = FakeClock::new();
    let on_beat: Arc<Mutex<Vec<String>>> = Arc::default();
    let persona = from_dir(
        &home,
        FromDirOptions {
            model: "m-quiet".into(),
            dream: Some(DreamOptions {
                every_ms: 50,
                store: d.store(),
                instructions: String::new(),
            }),
            ..Default::default()
        },
    );
    let ob = on_beat.clone();
    let sa = start_agent(
        &persona,
        StartOptions {
            options: Options {
                clock: Some(clk.clone()),
                ..d.opts()
            },
            every_ms: 1000,
            on_beat: Some(Arc::new(move |t: &str| ob.lock().unwrap().push(t.into()))),
        },
    )
    .unwrap();
    for _ in 0..1000 {
        if clk.armed() >= 2 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(2)).await;
    }
    assert!(clk.armed() >= 2, "persona + dreamer heartbeats armed");
    clk.fire();
    let want = str_map(&e["after"]["files"]);
    let mut ok = false;
    for _ in 0..2000 {
        let snap = snapshot(&home);
        if want.iter().all(|(f, b)| snap.get(f) == Some(b)) {
            ok = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert!(ok, "the dreamer never fixed memory: {:?}", snapshot(&home));
    let trace = sa.runtime().trace();
    sa.stop().await;
    assert!(on_beat.lock().unwrap().is_empty());
    assert!(sa.beats().is_empty());
    assert!(trace.iter().any(|l| l.contains("-dreamer")), "{trace:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d5_absent_dream_is_unchanged() {
    let d = Dream::new();
    let home = d.home();
    let frozen = snapshot(&home);
    let persona = from_dir(
        &home,
        FromDirOptions {
            model: "m-quiet".into(),
            ..Default::default()
        },
    );
    assert_eq!(persona.registry().len(), 1);
    let clk = FakeClock::new();
    let sa = start_agent(
        &persona,
        StartOptions {
            options: Options {
                clock: Some(clk.clone()),
                ..d.opts()
            },
            every_ms: 50,
            ..Default::default()
        },
    )
    .unwrap();
    for _ in 0..4 {
        clk.wait_armed().await;
        clk.fire();
        sa.next_beat().await;
    }
    let trace = sa.runtime().trace();
    sa.stop().await;
    assert_eq!(snapshot(&home), frozen);
    assert!(!trace.iter().any(|l| l.contains("dreamer")), "{trace:?}");
}
