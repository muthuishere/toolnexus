//! Phase 2: skills (SPEC §0 items 5–6, §3), ported from golang/skill_test.go
//! plus the ordering rules (A15 depth-first, A25 global sample sort before cap).

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde_json::json;
use toolnexus::ToolContext;
use toolnexus::skills::*;

fn examples() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../examples/skills")
        .canonicalize()
        .unwrap()
}

fn write_skill(body: &str) -> tempdir::Dir {
    let d = tempdir::Dir::new();
    let s = d.path().join("s");
    std::fs::create_dir_all(&s).unwrap();
    std::fs::write(s.join("SKILL.md"), body).unwrap();
    d
}

mod tempdir {
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    pub struct Dir(PathBuf);
    impl Dir {
        pub fn new() -> Self {
            let p = std::env::temp_dir().canonicalize().unwrap().join(format!(
                "tn-skills-{}-{}",
                std::process::id(),
                N.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&p).unwrap();
            Dir(p)
        }
        pub fn path(&self) -> &Path {
            &self.0
        }
    }
    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}

async fn run(src: &SkillSource, name: &str) -> toolnexus::ToolResult {
    src.tool
        .call(json!({"name": name}), ToolContext::default())
        .await
}

#[tokio::test]
async fn hello_world_block_is_byte_identical() {
    let root = examples();
    let src = load_skills(&[&root]);
    let res = run(&src, "hello-world").await;
    assert!(!res.is_error);
    let dir = root.join("hello-world");
    let content = src.skills["hello-world"].content.trim().to_owned();
    let want = [
        "<skill_content name=\"hello-world\">".to_owned(),
        "# Skill: hello-world".to_owned(),
        String::new(),
        content,
        String::new(),
        format!("Base directory for this skill: file://{}", dir.display()),
        "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.".to_owned(),
        "Note: file list is sampled.".to_owned(),
        String::new(),
        "<skill_files>".to_owned(),
        format!("<file>{}</file>", dir.join("scripts/greet.sh").display()),
        "</skill_files>".to_owned(),
        "</skill_content>".to_owned(),
    ]
    .join("\n");
    assert_eq!(res.output, want);
    let meta = res.metadata.unwrap();
    assert_eq!(meta["name"], "hello-world");
    assert_eq!(meta["dir"], dir.to_string_lossy().as_ref());
}

#[test]
fn skills_prompt_is_byte_identical() {
    let src = load_skills(&[examples()]);
    assert_eq!(
        src.prompt(),
        format!(
            "{SKILLS_PROMPT_PREAMBLE}\n\n## Available Skills\n- **hello-world**: A tiny example skill. Use when you want to see how skill loading (progressive disclosure) works end to end."
        )
    );
    let none = load_skills_with(LoadSkillsOptions {
        skills: vec![SkillDef {
            name: "x".into(),
            ..Default::default()
        }],
        ..Default::default()
    });
    assert_eq!(none.prompt(), "No skills are currently available.");
}

#[test]
fn frontmatter_variants() {
    let cases = [
        (
            "---\nname: single\ndescription: A one-liner.\n---\nBody.\n",
            "single",
            "A one-liner.",
        ),
        (
            "---\nname: folded\ndescription: >\n  first line\n  second line\n---\nBody.\n",
            "folded",
            "first line second line",
        ),
        (
            "---\nname: literal\ndescription: |\n  line one\n  line two\n---\nBody.\n",
            "literal",
            "line one\nline two",
        ),
        (
            "---\nname: emptydesc\ndescription:\n---\nBody.\n",
            "emptydesc",
            "",
        ),
        (
            "---\nname: huddle\ndescription: >\n  Runs a repo-aware expert huddle for engineering decisions,\n  planning, research, verification, and spec capture.\n---\n# Huddle\n\nBody.\n",
            "huddle",
            "Runs a repo-aware expert huddle for engineering decisions, planning, research, verification, and spec capture.",
        ),
        // YAML refuses (": " inside a plain scalar); the guarded rescue takes the line
        (
            "---\nname: colon\ndescription: Use when: things happen\n---\nBody.\n",
            "colon",
            "Use when: things happen",
        ),
        ("---\nname: 123\n---\nBody.\n", "123", ""),
    ];
    for (body, name, desc) in cases {
        let d = write_skill(body);
        let src = load_skills(&[d.path()]);
        let info = src
            .skills
            .get(name)
            .unwrap_or_else(|| panic!("{name} not discovered: {:?}", src.skipped));
        assert_eq!(info.description, desc, "{name}");
    }
}

#[test]
fn malformed_yaml_is_skipped_with_reason() {
    let d = write_skill("---\nname: [unterminated\ndescription: broken: : :\n---\nBody.\n");
    let src = load_skills(&[d.path()]);
    assert!(src.skills.is_empty());
    assert_eq!(src.skipped.len(), 1);
    assert_eq!(src.skipped[0].reason, SkillSkipReason::MalformedFrontmatter);
    assert!(!src.skipped[0].detail.is_empty());
}

#[test]
fn s3_list_skills_typed_skips() {
    let root = tempdir::Dir::new();
    for (n, body) in [
        ("good", "---\nname: good\ndescription: ok\n---\nbody"),
        ("noname", "---\ndescription: no name\n---\nbody"),
        ("bad", "---\nname: [unclosed\n---\nbody"),
    ] {
        std::fs::create_dir_all(root.path().join(n)).unwrap();
        std::fs::write(root.path().join(n).join("SKILL.md"), body).unwrap();
    }
    let inv = list_skills(&LoadSkillsOptions {
        dirs: vec![root.path().to_path_buf()],
        skills: vec![SkillDef {
            name: "good".into(),
            description: "dup".into(),
            content: "dup".into(),
            ..Default::default()
        }],
        ..Default::default()
    });
    assert_eq!(inv.skills.len(), 1);
    assert_eq!(inv.skills[0].name, "good");
    let mut reasons: Vec<_> = inv.skipped.iter().map(|s| s.reason.as_str()).collect();
    reasons.sort();
    assert_eq!(
        reasons,
        ["duplicate-name", "malformed-frontmatter", "missing-name"]
    );
}

#[tokio::test]
async fn s1_data_skill_logical_base_and_instruction_only() {
    let src = load_skills_with(LoadSkillsOptions {
        skills: vec![
            SkillDef {
                name: "d".into(),
                description: "D".into(),
                content: "  body  ".into(),
                resources: vec!["a.txt".into()],
                ..Default::default()
            },
            SkillDef {
                name: "plain".into(),
                content: "p".into(),
                ..Default::default()
            },
        ],
        ..Default::default()
    });
    let r = run(&src, "d").await;
    assert_eq!(
        r.output,
        "<skill_content name=\"d\">\n# Skill: d\n\nbody\n\nBase directory for this skill: skill://d/\nRelative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\nNote: file list is sampled.\n\n<skill_files>\n<file>a.txt</file>\n</skill_files>\n</skill_content>"
    );
    let p = run(&src, "plain").await;
    assert!(
        !p.output.contains("<skill_files>") && !p.output.contains("sampled"),
        "{}",
        p.output
    );
}

#[tokio::test]
async fn s1_dir_beats_data_and_unknown_name_lists_sorted() {
    let src = load_skills_with(LoadSkillsOptions {
        dirs: vec![examples()],
        skills: vec![
            SkillDef {
                name: "hello-world".into(),
                description: "shadow".into(),
                content: "shadow body".into(),
                ..Default::default()
            },
            SkillDef {
                name: "Zed".into(),
                content: "z".into(),
                ..Default::default()
            },
            SkillDef {
                name: "alpha".into(),
                content: "a".into(),
                ..Default::default()
            },
        ],
        ..Default::default()
    });
    assert!(
        !run(&src, "hello-world")
            .await
            .output
            .contains("shadow body")
    );
    let miss = run(&src, "nope").await;
    assert!(miss.is_error);
    // code point: uppercase before lowercase
    assert_eq!(
        miss.output,
        "Skill \"nope\" not found. Available skills: Zed, alpha, hello-world"
    );
    let empty = load_skills_with(LoadSkillsOptions::default());
    assert_eq!(
        run(&empty, "x").await.output,
        "Skill \"x\" not found. Available skills: none"
    );
}

#[test]
fn s2_filter_semantics() {
    let defs: Vec<SkillDef> = ["a", "b", "c"]
        .iter()
        .map(|n| SkillDef {
            name: (*n).into(),
            description: n.to_uppercase(),
            content: (*n).into(),
            ..Default::default()
        })
        .collect();
    let keys = |f: &[(&str, bool)]| {
        let filter: HashMap<String, bool> = f.iter().map(|(k, v)| ((*k).into(), *v)).collect();
        load_skills_with(LoadSkillsOptions {
            skills: defs.clone(),
            filter,
            ..Default::default()
        })
        .skills
        .keys()
        .cloned()
        .collect::<Vec<_>>()
        .join(",")
    };
    assert_eq!(keys(&[("a", true), ("b", true)]), "a,b");
    assert_eq!(keys(&[("c", false)]), "a,b");
    assert_eq!(keys(&[("a", true), ("nope", true)]), "a");
    assert_eq!(keys(&[]), "a,b,c");
}

#[tokio::test]
async fn s5_sample_cap_and_disable() {
    let defs = vec![SkillDef {
        name: "z".into(),
        content: "z".into(),
        resources: vec!["one".into(), "two".into(), "three".into()],
        ..Default::default()
    }];
    let capped = run(
        &load_skills_with(LoadSkillsOptions {
            skills: defs.clone(),
            sample_limit: 2,
            ..Default::default()
        }),
        "z",
    )
    .await;
    assert!(
        capped.output.contains("<file>one</file>\n<file>two</file>")
            && !capped.output.contains("three")
    );
    let off = run(
        &load_skills_with(LoadSkillsOptions {
            skills: defs,
            sample_limit: -1,
            ..Default::default()
        }),
        "z",
    )
    .await;
    assert!(
        !off.output.contains("<skill_files>") && !off.output.contains("Note: file list is sampled")
    );
}

#[test]
fn a15_shallower_copy_wins_for_every_name() {
    let root = tempdir::Dir::new();
    for (path, name, desc) in [
        ("docx/SKILL.md", "docx", "top"),
        ("xlsx/SKILL.md", "xlsx", "top"),
        ("synced/u1/docx/SKILL.md", "docx", "nested"),
        ("synced/u1/xlsx/SKILL.md", "xlsx", "nested"),
    ] {
        let f = root.path().join(path);
        std::fs::create_dir_all(f.parent().unwrap()).unwrap();
        std::fs::write(f, format!("---\nname: {name}\ndescription: {desc}\n---\nb")).unwrap();
    }
    let src = load_skills(&[root.path()]);
    assert_eq!(src.skills["docx"].description, "top");
    assert_eq!(
        src.skills["xlsx"].description, "top",
        "depth beats code point (x > s)"
    );
    assert_eq!(
        src.skipped
            .iter()
            .filter(|s| s.reason == SkillSkipReason::DuplicateName)
            .count(),
        2
    );
}

#[tokio::test]
async fn a25_sample_is_one_global_sort_before_the_cap() {
    let root = tempdir::Dir::new();
    let s = root.path().join("s");
    for f in [
        "z-root.txt",
        "a-root.txt",
        "zeta/f.txt",
        "alpha/f.txt",
        "beta/f.txt",
    ] {
        let p = s.join(f);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, "x").unwrap();
    }
    std::fs::write(s.join("SKILL.md"), "---\nname: s\ndescription: d\n---\nb").unwrap();
    let src = load_skills_with(LoadSkillsOptions {
        dirs: vec![root.path().to_path_buf()],
        sample_limit: 4,
        ..Default::default()
    });
    let out = run(&src, "s").await.output;
    let files: Vec<&str> = out
        .lines()
        .filter_map(|l| l.strip_prefix("<file>"))
        .map(|l| l.trim_end_matches("</file>"))
        .collect();
    let rel: Vec<String> = files
        .iter()
        .map(|f| {
            f.strip_prefix(&format!("{}/", s.display()))
                .unwrap()
                .to_owned()
        })
        .collect();
    assert_eq!(
        rel,
        ["a-root.txt", "alpha/f.txt", "beta/f.txt", "z-root.txt"]
    );
}
