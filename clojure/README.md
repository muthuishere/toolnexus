# toolnexus (Clojure)

**One `.cljc` source tree, two runtimes**: Clojure on the JVM and
[cljgo](https://github.com/muthuishere/cljgo) (Clojure hosted on Go, both AOT-compiled and
interpreted). Not two implementations that agree — *one* implementation, byte-identical on both.

The Clojure port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, Java, C# and Elixir**.

**Zero reader conditionals in this tree.** Not "few" — zero. Every host difference lives behind
[koine](https://github.com/muthuishere/koine), the portability seam, which is the only place a
`#?(:clj … :cljgo …)` belongs. koine is the only third-party dependency.

## Install

```clojure
;; deps.edn
net.clojars.muthuishere/toolnexus {:mvn/version "0.18.0"}   ; this port
net.clojars.muthuishere/koine     {:mvn/version "0.11.0"}   ; its only dependency
```

## Zero to agent

```clojure
(require '[toolnexus.core :as tn]
         '[toolnexus.client :as client])

(def toolkit (tn/create-toolkit {:mcp "mcp.json" :skills ["skills"]}))
(def agent   (client/create-client {:base-url "https://openrouter.ai/api/v1"
                                    :style "openai" :model "openai/gpt-4o-mini"}))

(println (:text (client/run agent "What tools do you have? Use one." {:toolkit toolkit})))
```

## Verified on both hosts, in five execution modes

495 tests / 2051 assertions, 0 failures, identical in every mode — and the gate fails on any
divergence between them:

| runtime | how |
|---|---|
| Clojure (JVM), compiled-on-load | `clojure -M -m toolnexus.test-main` |
| Clojure (JVM), REPL evaluator | forms piped into `clojure -r` |
| cljgo, AOT binary | `cljgo build && ./toolnexus-test` |
| cljgo, interpreted | `cljgo run src/run_tests.cljc` |
| cljgo, REPL evaluator | forms piped into `cljgo repl` |

A port proven only through its compiled path is proven in the mode developers use least, so all
five run on every change (`./all-modes-check.sh`).

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | **[Clojure](https://muthuishere.github.io/toolnexus/api/clojure/)** |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
