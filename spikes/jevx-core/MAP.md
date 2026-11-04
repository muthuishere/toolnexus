# jevx → toolnexus map

jevx = `~/muthu/gitworkspace/jevcli` at v0.12.13 (`ff51603`). toolnexus = `golang/` on this branch.
**Core** means the work becomes a call into toolnexus. **CLI** means it stays in jevx. **Gap** means
toolnexus has to add something before jevx can move.

## Decision core (moves to toolnexus)

| jevx | what it does | toolnexus today | verdict |
|---|---|---|---|
| `core/core.go:76` `Profile` (url, model, style, headers, question pack, defaults) | one endpoint | `ClassifierOptions` `classifier.go:481` (BaseURL, Model, APIKeyEnv, Headers, Timeout, Retries) | **Core** for the options; the profile itself is **jevx state** |
| `core/core.go:205` `Config.Profiles` + `:312` `Config.Profile(name)` | named profiles, a default | none, by design | **jevx** (stateless library; ADR 0038 D2) |
| `core/core.go:624` `MissingEnv`, `:636` `Expanded` | `$VAR` in headers, expanded per request | `attempt` `classifier.go:968`: key from `APIKeyEnv` and `${ENV}` headers, both at call time | **Core** (same rule) |
| `core/core.go:750` `AskRaw` | builds `{model,state,questions}` and POSTs it | `Evaluate` `classifier.go:620` → `evaluateSystemOne` `:867` | **Core** |
| `core/core.go:770` Cloudflare `/ai/run` envelope | wraps the request, unwraps `result` | the base URL always gets `/systemone` appended (`post` `:890`) | **Gap U1 → D3**: a `cloudflare` value on the `ClassifierBackend` enum (with `typesafe`, `openrouter`, `openai`) |
| `core/core.go:789` retry loop + `:844` `post` | 3 tries, 429/5xx/network, backoff `try²·500ms` | `post` `:890`: 2 retries, 408/429/5xx/529, `500·2ⁿ`, **honours Retry-After** | **Core** (toolnexus is stricter; jevx ignores Retry-After) |
| `core/core.go:865` `validate` | fails closed: answer count, P in [0,1], probabilities sum to ~1, choice is an offered key | `Decision.UnmarshalJSON` `:340` decodes the type only | **Gap V1**: fail-closed reply validation |
| `core/core.go:903` `ledger` | one content-free JSONL line per call (host, model, qhash, ms, usage, redacted, error) | `OnMetric` emits `classifier.evaluate` `:1058` (model, status, ms, tokens) | **Core** for the event. The file, profile and caller stay **CLI**. Small gap L1: no question hash or count in the event |
| `cmd/jevx/ask.go:109` `verdict` + `core.go:109` `Builtin` thresholds 0.8 / 0.2 / 0.6 | yes, no or unsure, **inclusive** | `Bands` `judge.go:89`/`:105`: **exclusive**, 0.30 / 0.70, one `High` for choice confidence, near-uniform demotes | **Gap T1**: inclusive thresholds per profile and per question, or jevx keeps its own rule |
| `core/core.go:157` `Question` with per-question yes/no/min | thresholds per question | `JudgeQuestion` `judge.go:20` has no thresholds | **Gap T1** |
| `cmd/jevx/ask.go:67` `askChunks` (chunk 32) | splits large question sets | none | **Gap C1** (small): `MaxQuestionsPerRequest` |
| `core/cache.go:39–107` hash-keyed answer cache with a TTL | repeat calls cost nothing | `Tape` `judge.go:378` is record and replay for tests, not a TTL cache | **jevx** (persistence is host state) |
| `core/redact.go:17` `Hosted`, `:46` `Scrub`, `core.go:992` `Redact` | scrubs secrets, email addresses and phone numbers before a **hosted** call | nothing (`RedactProviderBody` only scrubs error bodies) | **jevx**: it scrubs the state before calling `Evaluate`; no library hook needed |
| `cmd/jevx/ask.go:427` `batch` (parallel) | many inputs, one question set | `EvaluateBatch` `classifier.go:698` (16 in flight, results in order, fails closed) | **Core** |
| `core/core.go:964–980` `NoulQ/ChoiceQ/ScoreQ` + `questions.json` pack per profile | built-in questions (accepts, wanted_more, destroys, …) | the battery questions `judge_batteries.go` | **Core** for the shapes. The pack **wording stays jevx data** (a Jev model is trained on that wording) |
| none | image items | `ContentPart` `content.go:37`, `Client.RunParts` `client.go:846`, but `evaluateLLM` `classifier.go:997` calls text-only `Run`, and the §8B state is JSON | **Gap I1** |
| none | Jev default with an llm fallback | `StyleLLM` exists (`Calibrated=false`) | **jevx** picks the fallback profile; toolnexus just evaluates |

## Stays in jevx (CLI only)

| jevx | why it stays |
|---|---|
| `cmd/jevx/main.go:480` `main`, `ask.go:256` `cmdAsk`, `:801` `cmdVerb` (`is/pick/filter/rank`), `:173` `parseQ` compact syntax | command surface, flags, output format |
| exit codes 0/1/3/4 (`main.go:39` `die` → 4) | the CLI contract |
| `main.go:144` install, `:207` uninstall, `:112` `skillDirs` | installs the agent skill |
| `main.go:234` `cmdHook`, `:288` `hookRun`; `core/plugin.go:27–670` (plugins, `Eval` rule DSL `:160`, `Run` `:235`, `Output` `:383`, `InstallHooks` `:545`) | Claude Code hooks. Plugins *call* the core, but their DSL and output format are jevx's. `Policy`/`Rule` in `judge.go:222` could replace `Eval` later (optional) |
| `core/core.go:356–591` context (`LocalContext`, `GlobalContext`, `Section`, `WithContext`, `capContext`) | reads the `## Jev` section of AGENTS.md / CLAUDE.md, which is host policy |
| `core/core.go:1016` `State`, `:1132` `LastTurn` | builds a turn from the Claude transcript |
| `core/memory.go` (BM25 memory, cites) and `cmd/jevx/memory.go` | jevx feature that only adds to the state |
| `main.go:60` `cmdJudge`, `:341` `cmdReview`, `:635` `cmdStats`, `:529` `cmdProfile`, `ask.go:584` `cmdQuestion`, `:684` `cmdDefaults`, `:762` `cmdContext`, `:870` `cmdCache` | user commands. Their storage becomes registry/cache *config* |
| `core/core.go:239` `migrate`, `:265` `LoadConfig` | the config file format |
| the ledger *file* and `Caller()` `core.go:932` | jevx's local accounting |

## Gaps toolnexus must fill (stateless only; follow-up OpenSpec change across all ports)

T1 inclusive thresholds as a pure verdict function · V1 fail-closed reply validation · I1 image items (TypeSafe now accepts them) ·
D3 backend enum `typesafe | openrouter | cloudflare | openai` · C1 question chunking · L1 question hash in the metric event.

Owned by jevx, not gaps (owner 2026-11-04: "jevx is frontend … profile belongs to jevx, ours is a stateless
library"): R1 profiles/registry/default/fallback, K1 answer cache, P1 redaction, the ledger file, config.
