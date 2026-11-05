//! Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
//!
//! Run: cargo run --example 04-skills
//!
//! A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
//! skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
//! files themselves through resources/read on skill:// URIs.
use std::path::Path;

use mcp2026::{self as mcp, Client, HttpServer};
use serde_json::{Value, json};

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    let ts = HttpServer::start(mcp::fixture_server()).await; // serves examples/skills/hello-world
    let c = Client::new(format!("{}/mcp", ts.url));

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

    // 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
    let dir = std::env::temp_dir().join(format!("skills-{}", std::process::id()));
    c.download_skill(URI, &dir).await?;
    for f in files(&dir) {
        println!("downloaded: {f}");
    }
    let _ = std::fs::remove_dir_all(&dir);
    Ok(())
}

fn files(root: &Path) -> Vec<String> {
    let mut out = vec![];
    let mut stack = vec![root.to_path_buf()];
    while let Some(d) = stack.pop() {
        for e in std::fs::read_dir(&d).into_iter().flatten().flatten() {
            let p = e.path();
            if p.is_dir() {
                stack.push(p);
            } else {
                let rel = p.strip_prefix(root).unwrap();
                out.push(
                    rel.components()
                        .map(|c| c.as_os_str().to_string_lossy())
                        .collect::<Vec<_>>()
                        .join("/"),
                );
            }
        }
    }
    out.sort();
    out
}
