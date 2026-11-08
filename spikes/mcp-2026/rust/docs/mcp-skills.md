# MCP skills extension

> **Rust port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/rust` (crate `mcp2026`; reference: `spikes/mcp-2026/golang`). Every code block is
> copied verbatim from a program under `examples/` that `tests/examples.rs` runs and checks; `tests/docs.rs`
> fails if a block here stops matching its source.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills/main.rs -->
```rust
    // 1. Does the server speak the skills extension?
    let d = c.call("server/discover", Value::Null).await?;
    let has_skills = d["capabilities"]["extensions"]
        .get(mcp::SKILLS_EXT)
        .is_some();
    println!("skills extension: {has_skills}");

    // 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
    let list = c.call("skills/list", Value::Null).await?;
    for sk in list["skills"].as_array().into_iter().flatten() {
        println!(
            "skill {}: {}",
            sk["uri"].as_str().unwrap_or_default(),
            sk["frontmatter"]["name"].as_str().unwrap_or_default()
        );
    }

    // 3. Load one skill's instructions: resources/read on its SKILL.md.
    const URI: &str = "skill://hello-world/SKILL.md";
    let r = c.call("resources/read", json!({"uri": URI})).await?;
    let body = r["contents"][0]["text"].as_str().unwrap_or_default();
    let heading = body.lines().find(|l| l.starts_with("# "));
    println!("first heading: {}", heading.unwrap_or_default());
```

## Download a whole skill

<!-- from: examples/04-skills/main.rs -->
```rust
    // 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
    let dir = std::env::temp_dir().join(format!("skills-{}", std::process::id()));
    c.download_skill(URI, &dir).await?;
```

```text
skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh
```

`download_skill` validates every resource URI before writing anything: the URI is percent-decoded once, then any
leftover `%`, `\`, NUL, `:`, or empty / `.` / `..` segment is refused, as is a file outside the skill. Every
file's sha256 must match its `digest`. The server applies the same URI check (`-32602`).

## Serving skills

The spike server serves the skills directory named in its fixture (`skillsDir`). Frontmatter parsing is flat
`key: value` only; a real YAML subset is pending in the ports.
