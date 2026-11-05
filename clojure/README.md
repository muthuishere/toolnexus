# toolnexus (Clojure)

**One `.cljc` source tree, two runtimes**: Clojure on the JVM and
[cljgo](https://github.com/muthuishere/cljgo) (Clojure hosted on Go, both AOT-compiled and
interpreted). Not two implementations that agree — *one* implementation, byte-identical on both.

The Clojure port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, Java, C#, Elixir and Rust**.

**Zero reader conditionals in this tree.** Not "few" — zero. Every host difference lives behind
[koine](https://github.com/muthuishere/koine), the portability seam, which is the only place a
`#?(:clj … :cljgo …)` belongs. koine is the only third-party dependency.

## Install

```clojure
;; deps.edn
net.clojars.muthuishere/toolnexus {:mvn/version "0.24.0"}   ; this port
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

## API key by host (BREAKING in 0.22.0)

A client with no `:api-key` reads only the env var of its `:base-url` host, over https:
`api.openai.com` → `OPENAI_API_KEY`, `api.anthropic.com` → `ANTHROPIC_API_KEY`, `openrouter.ai` →
`OPENROUTER_API_KEY`. Any other host — a local model server, a proxy, plain http — throws unless you
set `:api-key` (the value) or `:api-key-env` (the **name** of the env var). Before 0.22.0 the client
fell back across all three variables and could send a key to a host it did not belong to.

```clojure
(client/create-client {:base-url "http://localhost:11434/v1" :model "llama3"
                        :api-key-env "LOCAL_LLM_KEY"})
```

## Prompt cache and tool tiers

```clojure
(def llm (client/create-client
           {:base-url "https://api.anthropic.com" :style "anthropic" :model "claude-sonnet-4-5"
            :prompt-cache {:long-ttl true}                 ; cache breakpoints on tools, system, last message
            :tool-tiers   {:core ["read" "grep"]}}))         ; others load through tool_search / call_tool

(client/tool-tier-plan llm toolkit)      ;=> {"read" "core" "bash" "listed" …}
(client/reset-tool-tiers! llm toolkit)   ; at a new session start
;; usage now carries :cache-read-tokens / :cache-write-tokens
```

Runnable: `clojure -M -m examples.prompt-cache` (from `examples/clj`).

## Host additions (0.22.0)

`mcp/from-config` `:on-server` (each server as soon as it is ready), `:wait-for-ctx` plus a
`:cancel` token on `run`/`ask`, compactor `:on-compact`, `:hints` on tools and `:before-tool` events
(permission gates), opt-in `:background` / `:web-search` / `:notebook-edit` builtins,
`mcp/resource-tools`, grep `context`/`before`/`after` and truncation metadata,
`agents.runtime/as-tool-with` and runtime `:wait-for`, `:call-id` on the tool context,
`client/list-models`. Each has a page in the [API reference](https://muthuishere.github.io/toolnexus/api/clojure/).

## Verified on both hosts, in five execution modes

652 tests / 3963 assertions, 0 failures, identical in every mode — and the gate fails on any
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

## Simple judgments

`toolnexus.judge` is a thin layer over any `Classifier` (SPEC §8B); the wire is unchanged.

```clojure
(require '[toolnexus.classifier :as jev] '[toolnexus.judge :as j])

(def qs [(j/noul :is_appropriate "Does message_received contain insults or harmful topics?")
         (j/noul :does_this_help "Does message_received help Donkey Kong win?")])

(def answers (j/ask classifier (j/state "You are Donkey Kong, you want to win."
                                        {"message_received" "jump off the stage now"})
                    qs))
(:band (get answers "is_appropriate"))   ;=> "yes" | "no" | "uncertain"

(j/gate classifier st qs [{:question :fixable :below 0.3 :action "fail"}
                          {:question :component :is :pricing :action "skip_to" :target "fix-pricing"}])
;=> {:action … :target … :escalated … :request <§10 input Request when unsure>}
```

- Bands default to `{:low 0.30 :high 0.70}`, exclusive on the confident side; pass your own as the last arg.
- `j/decide` takes a policy `{:rules :default :bands :skip-uncertain}`; an empty `:default` escalates.
- `j/value` / `j/picked` read one number / the picked option; `j/wire-questions` is the §8B map.
- `j/tape` + `j/recording` / `j/replaying` record live decisions by call name and replay them offline.
- `jev/static-classifier` builds a hermetic classifier in one call; `jev/evaluate-batch` runs the
  same questions over many states (state order, fail-closed, 16 in flight).

### Batteries

`toolnexus.batteries` ships eight ready-made judgments (SPEC §8B *Batteries*): `tool-guard`,
`tool-relevance`, `skill-relevance`, `tool-result-filter`, `is-complete`, `agent-router`,
`content-guard`, `model-router`. Each constructor returns plain data; use it with `b/check`,
`b/select`, `b/filter-chunks` or `b/pick`, or attach it as a §8 hook with `b/as-hook`
(`(b/as-hook battery next)`, `next` may be nil). Six of them require `:on-error :open | :closed`.

```clojure
(require '[toolnexus.batteries :as b])

(def guard (b/tool-guard classifier {:on-error :closed}))
(b/check guard {:name "delete_file" :arguments {"path" "/tmp/x"}})
;=> {:action "ask" :reason "medium risk" :risk 1.8 :sure true :calibrated true}

(client/create-client {… :hooks {:before-tool (b/as-hook guard)
                                 :before-llm  (b/as-hook (b/model-router classifier
                                                          [{:id "small-fast" :description "Short factual answers; cheapest."}
                                                           {:id "large-reasoning" :description "Multi-step reasoning and code."}]))}})
```

A `:before-llm` hook may return `:model`: a non-empty value is sent for that turn only.

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```clojure
(require '[toolnexus.media :as media] '[toolnexus.core :as tn] '[toolnexus.client :as client])

(def m (media/create-media (media/load-style "examples/media-styles/a2e.json")
                           {:on-progress (fn [{:keys [verb status]}] (println verb status))}))
(def img (media/image m {:prompt "a red fox in fresh snow" :width 1024 :height 768}))
(def vid (media/video m {:prompt "the fox blinks" :image (-> img :files first :url) :seconds 5}))

(def tk (tn/build {:media m}))                         ; generate_image, …
(def res (client/run agent "Draw a fox, then animate it." {:toolkit tk}))
(doseq [f (:files res)] (println (:from f) (:mimeType f) (:url f)))
```

## MCP 2026-07-28 + OAuth

Every server is probed with `server/discover` first. A 2026-07-28 server is used on the in-house 2026
path (no `initialize`, no session; `_meta` envelope, `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name`
headers, JSON or SSE replies, stdio demultiplexed by id). Anything else falls back to the frozen pre-2026
path unchanged (SPEC §2.2a). Tools, naming, results and status are identical on both paths.

```clojure
(core/build {:mcp {:mcpServers
                   {"docs" {:url "https://mcp.example/mcp"
                            :auth {:clientId "my-app"}           ; pre-registered, or :clientMetadataUrl (CIMD)
                            :toolSearch true :description "the docs server"}}}
             :wait-for    (fn [request] ...)        ; MRTR input + `pause`-mode OAuth (§10)
             :token-store {:load f :save f :delete f} ; default in-memory
             :open-url    (fn [url] ...)})          ; `auto` mode browser opener; default the OS opener
```

- **MRTR** (`input_required`) goes through `:wait-for`; without one the tool returns a §10 `pending`
  result and re-executing it with `ctx :answer` resumes that exact round. Request ids are `mrtr-<random>`.
- **OAuth** (§2A.1): PRM → AS metadata → pre-registered `clientId`/`clientIds` or CIMD (never DCR) →
  PKCE S256 + `resource` → iss/state check → token (JSON or form reply) → single-flight refresh → step-up.
  `auth.mode` `"auto"` (default; loopback callback) or `"pause"` (a §10 `authorization` request answered
  with `{:data {:callbackUrl …}}`; needs `auth.redirectUri`). Tokens print as `#object[fn]`.
- **Skills** a 2026 server serves join the `skill` tool; `mcp/download-skill!` fetches one, digest-checked.
- **Tool search** (`toolSearch`): `<server>_search_tools` + `<server>_call_tool` replace the server's tools.

Known gaps, honestly:

- The frozen fallback carries a bearer obtained during `server/discover` as a **static** `Authorization`
  header; it does not refresh it or react to a later 401 there.
- `koine.server` exposes no query string, so the `auto` loopback callback page re-POSTs `location.search`
  with a line of script. A real browser runs it; a non-browser `open-url` must POST the query itself.
- `search_tools` hits keep the pinned key order, but `inputSchema` is re-encoded by koine (sorted keys),
  not byte-for-byte as the server sent it.
- Only one input request per MRTR round can be resumed without `:wait-for` (the §10 loop resumes once).

## Embeddable MCP server

`toolnexus.mcp-handler/mcp-handler` returns a **Ring handler** you mount on your own stack, behind
your own TLS, CORS and OAuth (SPEC §7C.1, ADR 0039). Every policy is a hook — auth, the tool list
*per request* (a name outside it is never executed), an allow/deny/confirm gate (confirm asks the
client over MCP elicitation, else returns your shaped result), an audit event, server info,
resources, prompts and pluggable session ids. `toolnexus.serve/serve` is unchanged. Tools may set
`:output-schema` and `:annotations`; results may carry `:structured` / `:meta`
(`tool/with-structured`), which become `structuredContent` / `_meta` on both MCP paths.

```clojure
(require '[toolnexus.mcp-handler :as mh])

(def h (mh/mcp-handler tk {:authenticate (fn [req] (user-for (get-in req [:headers "authorization"])))
                           :list-tools   (fn [ctx] (tools-for (mh/mcp-identity ctx)))
                           :before-call  (fn [ctx tool args] (when (destructive? tool) {:kind :confirm :message "Sure?"}))}))
;; mount (:handler h) at /mcp on any Ring server; (mh/notify-tools-changed! h) when tools move
```

SSE responses (GET streams, a confirm round trip) are lazy-seq Ring bodies, so the adapter must
write a seq body chunk by chunk. Example: `examples/src/examples/mcp_handler.cljc`.

### MCP 2026-07-28 (opt-in)

List `"2026-07-28"` in `:protocol-versions` and the same handler also speaks the stateless
2026-07-28 wire (SPEC §7C.2, ADR 0040): `server/discover`, header validation, `resultType`, and
MRTR — a `:confirm` decision or a §10 tool suspension comes back as `input_required`, and the
retry's answer reaches the tool as `(:answer ctx)`. `["2026-07-28" "2025-06-18"]` serves both wires
on one mount; leave it out and nothing changes. `:list-skills` publishes skills through the
skills extension (`skills/list`, `skills/get`, `skill://` reads); `load-mcp-skills` builds the list
from a directory. `mcp-server-auth` turns the handler into an OAuth 2.1 protected resource: an
`:authenticate` hook (RS256 JWTs checked against the issuer's JWKS, or your own `:verify`; per-tool
scopes) and the RFC 9728 metadata handler. The Principal `{:subject :scopes :claims}` is
`(mh/mcp-identity ctx)`.

```clojure
(require '[toolnexus.mcp-server-auth :as sa])

(def auth (sa/mcp-server-auth {:issuer "https://login.example" :scopes {"*" ["mcp"] "wipe" ["mcp:admin"]}}))
(def h (mh/mcp-handler tk {:protocol-versions ["2026-07-28" "2025-06-18"]
                           :list-skills  (fn [_] (mh/load-mcp-skills "skills"))
                           :authenticate (:authenticate auth)}))
;; route GET /.well-known/oauth-protected-resource[/mcp] to (:metadata auth)
```

Bring your own authorization server: `mcp-server-auth` verifies its tokens. The auto-approving dev authorization
server the suite uses is test tooling (`src/toolnexus/mcp_dev_auth_test_support.cljc`, kept out of the jar), not part of the library.

## Agents via ACP

A local coding agent that speaks ACP can be the model behind an ordinary client, with no
per-agent code in the library (SPEC §8C). Three plain pieces:

- `toolnexus.serve/as-mcp-server` serves your toolkit on `127.0.0.1` behind a fresh bearer
  token: `{:url :token :close}`.
- `toolnexus.acp/connect` starts the agent and passes `:mcp-servers`, `:session-meta`,
  `:client-caps-meta` and `:model` through unchanged.
- `toolnexus.acp/generate-stream` plugs into `create-in-process-client`'s `:generate-stream`, so
  the agent's text reaches `run`'s `:on-event` sink as incremental `text` deltas (plus `thought`
  and ACP `tool_call`/`tool_result` events, `:via "acp"`).

```clojure
(require '[toolnexus.acp :as acp] '[toolnexus.client :as client]
         '[toolnexus.core :as tn] '[toolnexus.serve :as serve])

(def tk  (tn/build {:skills "skills"}))
(def srv (serve/as-mcp-server tk))
(def a   (acp/connect ["my-agent" "acp"]
                      {:mcp-servers [{:type "http" :name "toolnexus" :url (:url srv)
                                      :headers [{:name "Authorization"
                                                 :value (str "Bearer " (:token srv))}]}]}))
(def c   (client/create-in-process-client {:model "agent" :generate-stream (acp/generate-stream a)}))
(client/run c "What is my badge number?" {:on-event prn})
(acp/close a) ((:close srv))
```

Which binary, flags and permissions an agent needs is the caller's recipe, not library API.

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

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
