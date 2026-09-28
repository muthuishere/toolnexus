# Tasks — add-judge-batteries

## 1. Contract
- [x] 1.1 ADR 0035 D6 records the owner decision (ModelRouter opt-in)
- [x] 1.2 SPEC §8 *Hooks* + *Right-size routing* amended (beforeLLM `model` override; default verbatim)
- [x] 1.3 SPEC §8B *Batteries* subsection + idiom table
- [x] 1.4 Shared fixtures `examples/judge/batteries/` (8 batteries + user-text cases)
- [x] 1.5 CHANGELOG `## Unreleased` entry; Cookbook judge page

## 2. Per-language parity (8 batteries + beforeLLM model override + all fixtures + hook tests + mutation check)
- [x] 2.1 golang
- [x] 2.2 js
- [x] 2.3 python
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir (coverage gate ≥ 95%)
- [x] 2.7 clojure

## 3. Verify
- [x] 3.1 Existing verbatim-model conformance tests still pass in every port
- [x] 3.2 New test per port: override `model` is transmitted for that turn; absent ⇒ configured
- [x] 3.3 Every port's full suite green
- [x] 3.4 Adversarial review of the diff

## 4. Follow-up gaps (review of the batteries diff)
- [x] 4.1 beforeLLM failure stops the call in every loop + translate (Go `Translate` bug; Elixir `{:error,_}`); SPEC §8 + delta; tests in all 7 ports
- [x] 4.2 AgentRouter duplicate names: first node wins (criterion + descent); SPEC §8B; fixture `duplicate-name-first-wins`; fixed go/java/csharp/clojure (descent) and js/python/elixir (criterion)
- [x] 4.3 Provider tool entry absent/non-string name/description ⇒ `""`; SPEC §8B; `tool-relevance.json` `hookCases` (2 cases); fixed clojure/python/elixir
- [x] 4.4 Reported model = transmitted model (`llm`/`run` metrics, RunResult.model, translate result.model); SPEC §8/§11 + delta; all 7 ports
- [x] 4.5 Model-override tests for every loop (run/stream × openai/anthropic, translate, agent run) in all 7 ports (clojure: no streaming loop)
- [x] 4.6 Cookbook Batteries tabs for Java, C#, Elixir, Clojure (Java + C# snippets compile-checked)
- [x] 4.7 js package-lock version 0.20.0
- [x] 4.8 Mutation check of the new fixture cases in every port

## 5. Follow-up gaps (owner, 2026-09-28)
- [x] 5.1 Agent-run hook-failure parity pinned: loop run throws, handle turn = `isError`/`"error"`, no request; SPEC §8 + §7D *Errors* + delta scenarios; fixture `examples/agent-hooks` H7; both entry points tested in all 7 ports (behaviour already agreed — no library change)
- [x] 5.2 Clojure translate-model-override flake: root cause = cljgo's koine/bri server binds the wildcard `:0`, and macOS hands it ports another process holds on 127.0.0.1 (which then receives the requests → Go "404 page not found"); fixed with a probe handshake in test-only `toolnexus.test-support/serve`, used by every test server; 50/50 green under a deliberate port-shadowing load (was 3/3 red), 50/50 quiet
- [x] 5.3 Clojure `:request-params` keys canonicalised: exactly one `model` on the wire for a per-call model (run, translate, agent Loop); string-spelled forbidden keys now forbidden
- [x] 5.4 golang gofmt clean; CI go job fails on `gofmt -l` output
