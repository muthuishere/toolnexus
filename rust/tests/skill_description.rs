//! SPEC.md §3, issue #84: oversized descriptions are warned, never modified.
//! Shared fixture: conformance/skill_description/.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde_json::Value;
use toolnexus::skills::*;

fn fixture() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../conformance/skill_description")
}

fn check(label: &str, got: &[SkillWarning], expect: &[Value]) {
    assert_eq!(got.len(), expect.len(), "{label}: {got:?}");
    for (g, w) in got.iter().zip(expect) {
        let loc = g.location.replace('\\', "/");
        assert!(
            loc.ends_with(w["location_suffix"].as_str().unwrap()),
            "{label}: {loc}"
        );
        assert_eq!(g.reason.as_str(), w["reason"].as_str().unwrap(), "{label}");
        assert_eq!(g.chars as u64, w["chars"].as_u64().unwrap(), "{label}");
    }
}

#[test]
fn oversized_description_warns_and_is_kept_in_full() {
    let raw = std::fs::read_to_string(fixture().join("cases.json")).unwrap();
    let cases: Value = serde_json::from_str(&raw).unwrap();
    let expect = cases["expect_warnings"].as_array().unwrap();
    let opts = LoadSkillsOptions {
        dirs: vec![fixture().join("skills")],
        ..Default::default()
    };

    let src = load_skills_with(opts.clone());
    check("loader", &src.warnings, expect);
    check("inventory", &list_skills(&opts).warnings, expect);

    let prompt = src.prompt();
    for (name, desc) in cases["full_descriptions"].as_object().unwrap() {
        let desc = desc.as_str().unwrap();
        assert_eq!(src.skills[name].description, desc, "{name} modified");
        assert!(
            prompt.contains(&format!("- **{name}**: {desc}")),
            "{name}: prompt lacks full description"
        );
    }

    // Loader warnings are over EXPOSED skills only.
    let filtered = load_skills_with(LoadSkillsOptions {
        filter: HashMap::from([("long-desc".to_string(), false)]),
        ..opts
    });
    assert!(filtered.warnings.is_empty(), "{:?}", filtered.warnings);
}
