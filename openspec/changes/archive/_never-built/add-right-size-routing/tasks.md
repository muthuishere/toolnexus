## 1. Shared contract

- [x] 1.1 Add a "Right-size routing" note to `SPEC.md` (alongside §8 Hooks/Observability):
      model-faithfulness guarantee, the `beforeLLM` route-gate seam, and the routing-tier
      registry shape (`base_url`/`style`/`api_key_env`/`models.<tier>`). Cite FrugalGPT +
      RouteLLM.
- [x] 1.2 Document the routing-tier registry contract that `fleet-nexus`/`huddle-nexus` consume,
      so both sides check one shape.

## 2. golang — conformance test (CLI path the fleet drives)

- [x] 2.1 Add a hermetic test that stands up an `httptest` OpenAI-style `/chat/completions`
      server, runs the `Client` with `model = "cheap-tier/model"` on a classify prompt, and
      asserts the request body carried exactly that `model` id and the run returned the mock's
      answer. (Model faithfulness — the CEO use case, hermetic.)
- [x] 2.2 Add a hermetic test proving a `beforeLLM` gate aborts an expensive-tier route (endpoint
      never hit) while permitting a cheap-tier route (endpoint hit, answer returned).

## 3. Parity note — model faithfulness is already cross-port

- [x] 3.1 Model faithfulness (the `model` field is sent verbatim) is exercised by each port's
      existing client tests — the same client loop code sends `opts.model`/`self.model`/`Model`
      on every turn: js `client_test`/example, python `test_client`, go `client_*_test.go`,
      java client tests, csharp client tests. The NEW assertion in §2 pins it as the *routing*
      contract end-to-end through the CLI surface the fleet uses; no per-port library change is
      required, so this change does not introduce parity drift.

## 4. Complementarity (this organ + the others)

- [x] 4.1 Verify the contract `fleet-nexus`/`huddle-nexus` depend on: `toolnexus run --base-url …
      --style openai --model <slug> --once "<prompt>"` returns the model's answer on stdout. The
      §2.1 test asserts the library equivalent; a live smoke against the cheap tier proves the
      CLI end-to-end (recorded in the organ report).
- [x] 4.2 Document the `brain` route-gate hook: `beforeLLM` is the seam; the reference gate
      shells `brain check "<why this model>" --json` and aborts unless `guaranteed`. Hard veto at
      the tool layer remains `add-governed-execution-layer`; the two compose.

## 5. Verify

- [x] 5.1 `go test -race ./...` green with the new tests included.
- [x] 5.2 Confirm the other four ports still pass unchanged (no library change): js/python/java/csharp.
- [ ] 5.3 Owner-gated: a `--tier` CLI flag that resolves a job class against the registry path
      (design.md Open Questions) — deferred, not built here.
