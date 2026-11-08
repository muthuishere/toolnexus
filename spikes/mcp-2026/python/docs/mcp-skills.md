# MCP skills extension

> **Python port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/python` (package `mcp2026`, `import mcp2026 as mcp`); the Go spike is the reference.
> Every code block is copied verbatim from a program under `examples/` that `tests/test_examples.py` runs and
> checks; `tests/test_docs.py` fails if a block here stops matching its source.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills/main.py -->
```python
    # 1. Does the server speak the skills extension?
    d = c.call("server/discover")
    has_skills = mcp.SKILLS_EXT in d["capabilities"].get("extensions", {})
    print("skills extension:", str(has_skills).lower())

    # 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
    for sk in c.call("skills/list")["skills"]:
        print(f"skill {sk['uri']}: {sk['frontmatter']['name']}")

    # 3. Load one skill's instructions: resources/read on its SKILL.md.
    uri = "skill://hello-world/SKILL.md"
    body = c.call("resources/read", {"uri": uri})["contents"][0]["text"]
    print("first heading:", first_heading(body))
```

## Download a whole skill

<!-- from: examples/04-skills/main.py -->
```python
    # 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
    with tempfile.TemporaryDirectory() as dir:
        c.download_skill(uri, dir)
        for root, _, files in sorted(os.walk(dir)):
            for f in sorted(files):
                print("downloaded:", os.path.relpath(os.path.join(root, f), dir).replace(os.sep, "/"))
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
