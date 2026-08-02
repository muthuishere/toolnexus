# Add the Clojure port

## Why

toolnexus ships six ports (js, python, golang, java, csharp, elixir). A seventh —
Clojure — is different in kind from the others: it must run on **two runtimes
from one source**, Clojure (JVM) and cljgo (Go-hosted), because the value of a
Clojure port that only runs on the JVM is small next to one that also produces a
single static Go binary.

ADR 0009 fixed the design: **one `.cljc` tree**, `clojure.core` + **koine** only,
with every reader conditional confined to koine. A ten-spike battery
(`clojure/spikes/`) has since measured that design against the shared `examples/`
fixtures, and every capability in `SPEC.md §0` produced **byte-identical output on
Clojure (JVM), a `cljgo build` AOT binary, and `cljgo run` interpreted** — with
zero reader conditionals in toolnexus' own source.

The feasibility question is answered. This change builds the port.

## What changes

- A new `clojure/` port: a `.cljc` namespace tree implementing `SPEC.md §0`, with
  `deps.edn` (JVM) and `build.cljgo` (cljgo) resolving the **same** koine artifact
  from Clojars.
- A dual-host test suite gated on a **nonzero test count**, because on cljgo exit 0
  means nothing threw rather than that anything ran.
- `frontmatter` — a documented YAML subset for `SKILL.md`, which **throws** outside
  the subset rather than misparsing silently. koine declined to own YAML (it
  touches no host, so by charter it is not a seam), so the port owns it.
- No change to `SPEC.md` behaviour and no change to any existing port.

## Impact

- Affected specs: `clojure-port` (new capability).
- Affected code: `clojure/` (new). Nothing outside it.
- Parity: this change adds a port; it does not alter the contract, so the other six
  are untouched. Two SPEC defects the battery found are filed **separately** —
  they change the contract and must not ride in on a port.
