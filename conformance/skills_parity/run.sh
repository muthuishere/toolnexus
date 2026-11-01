#!/usr/bin/env bash
# Skills byte-parity across ports (SPEC §0 items 5–6): build one edge-case corpus,
# run the Go reference and each other port's dump over it, and require identical
# JSON — the system-prompt catalog, every `skill` tool output (incl. not-found),
# and the skip list. Today: Go vs Rust. Add a port = add a dump command below.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
corpus="$work/corpus"
mkdir -p "$corpus"

mk() { mkdir -p "$(dirname "$corpus/$1")"; printf '%b' "$2" > "$corpus/$1"; }
mk single/SKILL.md "---\nname: single\ndescription: A one-liner.\n---\nBody.\n"
mk folded/SKILL.md "---\nname: folded\ndescription: >\n  first line\n  second line\n---\n  Body with spaces  \n"
mk literal/SKILL.md "---\nname: literal\ndescription: |\n  line one\n  line two\n---\nBody.\n"
mk empty/SKILL.md "---\nname: emptydesc\ndescription:\n---\nBody.\n"
mk colon/SKILL.md "---\nname: colon\ndescription: Use when: things happen\n---\nBody.\n"
mk quoted/SKILL.md "---\nname: \"quoted name\"\ndescription: 'single quoted'\n---\nB\n"
mk num/SKILL.md "---\nname: 123\ndescription: true\n---\nB\n"
mk bad/SKILL.md "---\nname: [unterminated\ndescription: broken: : :\n---\nBody.\n"
mk noname/SKILL.md "---\ndescription: no name\n---\nbody"
mk nofence/SKILL.md "just body, no frontmatter\n"
mk crlf/SKILL.md "---\r\nname: crlf\r\ndescription: windows\r\n---\r\nBody\r\n"
mk "unicode dir é/SKILL.md" "---\nname: Ünï\ndescription: unicode name\n---\nB\n"
mk docx/SKILL.md "---\nname: docx\ndescription: top\n---\nb"
mk xlsx/SKILL.md "---\nname: xlsx\ndescription: top\n---\nb"
mk synced/u1/docx/SKILL.md "---\nname: docx\ndescription: nested\n---\nb"
mk synced/u1/xlsx/SKILL.md "---\nname: xlsx\ndescription: nested\n---\nb"
for f in z-root.txt a-root.txt zeta/f.txt alpha/f.txt beta/f.txt "sp ace.txt" B.txt; do mk "single/$f" x; done
for i in $(seq 1 12); do mk "literal/r$i.txt" x; done
ln -s ../single/alpha "$corpus/literal/linkdir"
cp -R "$repo/examples/skills/hello-world" "$corpus/hello-world"

(cd "$here/godump" && GOFLAGS=-mod=mod go run . "$corpus") > "$work/go.json"
(cd "$repo/rust" && cargo run -q --example skills_dump -- "$corpus") > "$work/rust.json"

python3 - "$work/go.json" "$work/rust.json" <<'PY'
import json, sys
g, r = (json.load(open(p)) for p in sys.argv[1:3])
bad = []
if g["prompt"] != r["prompt"]: bad.append("prompt")
if g["skipped"] != r["skipped"]: bad.append("skipped")
for n in sorted(set(g["tools"]) | set(r["tools"])):
    if g["tools"].get(n) != r["tools"].get(n): bad.append(f"skill tool output for {n!r}")
if len(g["tools"]) < 12: bad.append(f"corpus too small: {len(g['tools'])} tool outputs")
if bad:
    print("skills parity FAILED (go vs rust):", *bad, sep="\n  ")
    for n in sorted(g["tools"]):
        if g["tools"].get(n) != r["tools"].get(n):
            print(f"\n--- go {n}\n{g['tools'][n]}\n--- rust {n}\n{r['tools'].get(n)}")
    sys.exit(1)
print(f"skills parity OK: go == rust over {len(g['tools'])} tool outputs, prompt, skip list")
PY
