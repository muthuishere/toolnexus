"""Example 04 — list and load a remote agent skill (skills extension, SEP-2640).

Run: python examples/04-skills/main.py

A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves skills/list
(names + frontmatter), skills/get (one skill's files with sha256 digests), and the files themselves
through resources/read on skill:// URIs.
"""
import os
import tempfile

import mcp2026 as mcp


def first_heading(md: str) -> str:
    return next((line for line in md.split("\n") if line.startswith("# ")), "")


with mcp.serve(mcp.fixture_server()) as ts:  # serves examples/skills/hello-world
    c = mcp.Client(ts.url + "/mcp")

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

    # 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
    with tempfile.TemporaryDirectory() as dir:
        c.download_skill(uri, dir)
        for root, _, files in sorted(os.walk(dir)):
            for f in sorted(files):
                print("downloaded:", os.path.relpath(os.path.join(root, f), dir).replace(os.sep, "/"))
