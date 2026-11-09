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
net.clojars.muthuishere/toolnexus {:mvn/version "0.29.1"}   ; this port
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

### Backends, images, cache and helpers

Every default below is a **proposed default, owner to confirm**
(`openspec/changes/add-classifier-backends`).

```clojure
(def c (jev/create-classifier {:backend "openrouter" :cache (jev/memory-cache 3600000 1000)}))

(jev/evaluate-state (jev/state c {"note" "listing" "photo" {:type "image" :mimeType "image/png" :data b64}})
                    (j/noul "red" "Is the object in `photo` red?"))   ; fails closed on an invalid reply
(jev/evaluate-states (jev/states c ["a" "b"]) (j/noul "q" "…"))       ;=> [{:decision d} | {:error e}], in order
```

- `:backend`: `"typesafe"` (default) · `"openrouter"` (`typesafe/jev-1.14`) · `"cloudflare"` (`/ai/run`
  envelope; `${CLOUDFLARE_ACCOUNT_ID}` in the URL expands at call time, errors name it unexpanded)
  · `"vercel"` · `"openai"` (OpenAI Decisions API, `gpt-6-luna`, `OPENAI_API_KEY`; POSTs
  `{baseUrl}/decisions`, answers are `:calibrated false`, raw questions are unsupported) · `"custom"` (requires `:base-url` and `:supports`). The table is data:
  `jev/backends`; `:supports {:images true}` overrides it per classifier.
- Images (style `systemone` only): content-part maps in the state are lifted into `assets`
  (cloudflare: data URLs). PNG/JPEG, 280–1568 px a side, ≤ 16 MiB each and 32 MiB per request —
  never resized. A backend without image support throws a `ClassifierUnsupported` ex-info
  (`jev/unsupported?`) before any request. A state with no content part is sent byte-identically
  to before. Any map shaped like a §1B part (`{:type "text" :text …}`, `{:type "image" :mimeType …}`)
  counts as a part.
- `:cache {:get (fn [k]) :set (fn [k v])}` (string values) — off when absent; only valid replies
  are stored; keys follow the served model (`jev/model-cache-key`, `jev/decision-cache-key`). A hit
  sets `:cached true` on the decision and on the `classifier.evaluate` event. Every such event
  also carries `:questions` and `:questions_hash`. The digest is pure Clojure (both hosts), so
  caching megabytes of image data costs real time.
- Helpers: `jev/validate-decision` → nil or an ex-info; `jev/threshold-band` with
  `jev/default-thresholds` 0.8 / 0.2 / 0.6 (inclusive; the bands stay the `ask`/`gate` default);
  `jev/chunk-questions`.
- Open union: `j/raw` / `jev/raw-question`; an answer of an unmodelled type decodes to
  `{:type t :value <answer>}` (`jev/raw-answer`).
- `:style "llm"` is **deprecated** — it still works unchanged, with no runtime warning.

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

Images, video, speech, music, transcription, voice cloning and talking-head video, with **one
client per media object**. The quickest start is a **CLI preset**: `media/xai-grok-cli`, `media/openai-codex-cli` and `media/google-agy-cli` drive the vendor's
official CLI (`grok`, `codex`, `agy`) in its documented headless mode (`grok -p`, `codex exec`,
`agy -p`), signed in by you. Grok does image and video; Codex and Antigravity do image. Each preset
has a mock twin (`media/xai-grok-cli-mock`, …) for tests. Your provider's terms of service apply to how you use its CLI.
Every Grok image carries a small visible **"Grok" mark in the bottom-right corner**; toolnexus does
not remove it. Install, sign-in, Grok video, limits and models:
<https://muthuishere.github.io/toolnexus/cookbook/cli-media-setup/>. SPEC §7G; guide: <https://muthuishere.github.io/toolnexus/media/>.

```clojure
(require '[toolnexus.media :as media] '[toolnexus.core :as tn])

;; one line
(def r (media/generate-media media/xai-grok-cli "image" {:prompt "a red apple" :aspectRatio "16:9"}))
(println (-> r :files first :path))

;; or keep a media object, and give it to your agent
(def m (media/create-media (media/load-media-style media/xai-grok-cli)))
(def clip (media/video m {:prompt "the apple rolls off the table"}))
(def tk (tn/build {:media m}))                          ; generate_image, generate_video
```

For an HTTP API, give `create-media` one named client — `openai-media-client`, `openrouter-media-client`, `fal-media-client`, `elevenlabs-media-client` or `a2e-media-client`. Each reads its key from its env
var (`OPENAI_API_KEY`, `FAL_API_KEY`, `ELEVENLABS_API_KEY`, …) at call time and never logs it.

```clojure
(def voice (media/create-media (media/elevenlabs-media-client)))

(try
  (println (-> (media/speech voice {:text "Hello from the fox."}) :files first :path))
  (catch clojure.lang.ExceptionInfo e
    (if (media/unavailable? e)
      (println "unavailable:" (ex-message e))
      (println (:status (ex-data e)) (ex-message e)))))
```

- **Verbs** — `image`, `video`, `speech`, `music`, `transcribe`, `clone-voice`, `talk`, each
  `(verb m args)` / `(verb m args ctx)` → `{:files [..] :text? :jobId? :status}`; or
  `(media/generate m verb args)`. CLI presets take `:prompt` plus size hints, folded into the prompt.
- **Tools** — `(tn/build {:media m})` (or `(media/tools m)`) registers one tool per verb some
  client supports. Every file a tool makes lands in the run result's `:files`; only **image** parts
  are sent back to the model.
- **Config** — every built-in takes the same map: `:model`, `:models` (per verb), `:key`, `:url`,
  `:command` (CLI), `:loop {:interval-ms :timeout-ms :on-progress}`,
  `:gather {:mode "url"|"download"|"inline" :dir :max-bytes :keep}` (built-ins default to
  `"download"` into the temp dir), `:http-client`.
- **Your own client** — a map: `:name`, `:supports (fn [verb])`, optional `:args`, and one
  `(fn [args ctx])` per verb you serve (`:image`, `:video`, … `:clone-voice`, `:talk`); throw
  `(media/media-error msg :unavailable)` when it cannot serve a call.
- **Errors** — the verbs throw `ex-info` with `(:status (ex-data e))` one of `:failed :timeout
  :cancelled :unavailable` (`media/unavailable?` tests it); a media tool never throws.
- **Advanced** — a JSON style as a client: `(media/load-style path)` + `(media/style-media-client style)`.
- **Known gap** — koine carries HTTP bodies as UTF-8 text, so binary that travels as a raw HTTP
  body is not byte-exact on Clojure yet: ElevenLabs speech/music, OpenAI speech and video, any
  `"download"`/`"inline"` fetch of an `http(s)` result, and uploaded inputs (`:image`/`:audio`/
  `:samples` files). Base64 and `data:` URL results, `"url"` mode and CLI files are byte-exact.
  Pending a bytes mode in koine.

Run it: `cd examples/clj && clojure -M -m examples.media`. It adds each client only when its CLI is
on `PATH` (`grok`, `codex`) or its key is set (`ELEVENLABS_API_KEY`, then
`OPENAI_API_KEY`, `FAL_API_KEY`, `A2E_API_KEY`), makes an image, ElevenLabs speech (`MEDIA_VOICE`
picks the voice) and a `generate_image` tool call, and prints client, verb, path or URL, mime type,
size and usage per file. Grok video only with `MEDIA_VIDEO=1`; paid video/talk only with
`MEDIA_PAID=1`. Exits non-zero when a step fails; with no client at all it prints a hint and exits 0.

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
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Dreaming](https://muthuishere.github.io/toolnexus/dreaming/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | **[Clojure](https://muthuishere.github.io/toolnexus/api/clojure/)** |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
