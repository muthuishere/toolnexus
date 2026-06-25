---
name: hello-world
description: A tiny example skill. Use when you want to see how skill loading (progressive disclosure) works end to end.
---

# Hello World Skill

This is the skill body. When the model calls the `skill` tool with
`{ "name": "hello-world" }`, everything below the frontmatter is injected into the
conversation, along with a sampled list of files in this directory.

## Steps

1. Read `scripts/greet.sh` in this skill's base directory.
2. Run it with the user's name.
3. Report the greeting back.

Relative paths (like `scripts/greet.sh`) are resolved against the base directory
printed in the tool output.
