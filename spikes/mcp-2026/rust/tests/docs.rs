//! Every code block in docs/*.md is preceded by `<!-- from: <path> -->` and must appear verbatim in
//! that file (an example tests/examples.rs runs). Edit the example, then re-copy the block.
#[test]
fn docs_snippets_match_examples() {
    let root = env!("CARGO_MANIFEST_DIR");
    let re = regex::Regex::new("(?s)<!-- from: (\\S+) -->\n```[a-z]*\n(.*?)\n```").unwrap();
    let mut pages: Vec<_> = std::fs::read_dir(format!("{root}/docs"))
        .unwrap()
        .flatten()
        .map(|e| e.path())
        .collect();
    pages.retain(|p| p.extension().is_some_and(|x| x == "md"));
    assert_eq!(pages.len(), 6, "the 6 doc pages");
    let mut total = 0;
    for page in pages {
        let b = std::fs::read_to_string(&page).unwrap();
        assert!(
            b.contains("Rust port of the MCP 2026-07-28 spike"),
            "{}: missing the spike banner",
            page.display()
        );
        for m in re.captures_iter(&b) {
            total += 1;
            let src = std::fs::read_to_string(format!("{root}/{}", &m[1]))
                .unwrap_or_else(|e| panic!("{}: {e}", page.display()));
            assert!(
                src.contains(&m[2]),
                "{}: block from {} no longer matches the example:\n{}",
                page.display(),
                &m[1],
                &m[2]
            );
        }
    }
    assert!(total >= 15, "{total} code blocks checked");
}
