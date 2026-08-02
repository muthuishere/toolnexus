# Five examples, two hosts, one source tree

```sh
./clj/run.sh      # all five on Clojure (JVM)
./cljgo/run.sh    # all five on cljgo — AOT binary AND interpreted
```

Both scripts fail loudly if any example does not finish. Each example prints `OK`
as its last line and every run is judged on that marker, not on the exit status:
a program that printed most of its report and then died would otherwise exit 0
and look identical to one that finished.

## The two projects

| | `clj/` | `cljgo/` |
|---|---|---|
| project file | `deps.edn` | `build.cljgo` |
| run it | `clojure -M -m examples.skills` | `cljgo run src/run_skills.cljc` |
| build it | — | `cljgo build` → `ex-skills` |
| source | `src` → `../src` | `src` → `../src` |

**Both `src` are symlinks to the same directory.** Two project files, one source
tree — forking the examples per host would quietly destroy the only claim this
port makes. If you want proof that a given example is genuinely the same bytes on
both hosts, that symlink is the proof.

`cljgo run` evaluates top-level forms and does **not** call `-main`, which is why
each example has a two-line `src/run_<name>.cljc` entry. Point `cljgo run` at the
namespace file instead and it prints the dependency banner, exits 0, and proves
nothing — a trap worth knowing about before you write your own.

## The five

| # | example | what it shows |
|---|---------|---------------|
| 1 | `toolnexus.demo` | **MCP + skills + native, side by side.** Starts a real MCP server as a child process, discovers its tools, loads a real skill off disk, and calls one tool from each source. Not a mock. |
| 2 | `examples.native-and-http` | **Your own function and a REST endpoint as tools** (§0.8 / §0.9) — and both emitting the same provider schema. The "API" is a local koine server, so it is hermetic. |
| 3 | `examples.skills` | **Progressive disclosure** (§3): a folder of `SKILL.md` files becomes ONE `skill` tool; the catalog goes in the prompt, the instructions load on demand. Asserts the byte-exact output every port shares. |
| 4 | `examples.persona-memory` | **A persona that remembers** (§7E): the directory is the agent, and the `memory` tool edits its own notes on disk — including the frozen-snapshot rule that a write loads *next* session. |
| 5 | `examples.compaction` | **Keeping a long run under budget** (§7F): the `:before-llm` compactor, with the system prompt preserved and a tool-pair never split across the summary boundary. |

Examples 4 and 5 exercise the two subsystems this port has that are newest; 1–3
are the ones to read first if you have never used the library.

## What these do NOT need

No API key, no network beyond loopback, and no live model. Example 1 needs `npx`
because it launches a real MCP server — that is a genuine dependency of talking
MCP, not a shortcut being taken.

`TN_EXAMPLES` points at the repo's shared fixtures (`examples/mcp.json`,
`examples/skills/`) and defaults correctly from either project directory. The
fixtures are the same ones all seven ports run against.
