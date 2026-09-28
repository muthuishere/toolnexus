# Judge batteries — shared parity fixtures (change `add-judge-batteries`)

One file per battery (SPEC §8B *Batteries*). Every port asserts every case.

Each case:

- `options` — constructor options (`onError`, `bands`, `role`, `askAt`, `denyAt`, `dimensions`).
- `input` — the standalone method's arguments.
- `calls` — the recorded classifier calls, in order: `state` + `questions` (the exact
  `evaluate` inputs the battery must build) and `response` (the backend body). Build a
  **`static`** classifier from them: a battery that builds a different state, question text or
  key misses the corpus, so default text is pinned byte-for-byte.
- `error: true` — use a classifier whose evaluate fails (message free), instead.
- `calls: []` without `error` — the battery must not call the classifier at all.
- `want` — the verdict. `error` is a boolean (verdict carries an error or not); `null` means
  absent/nil/none in the port's idiom.

`tool-relevance.json` also carries `hookCases`: the `beforeLLM` hook driven with a provider-entry
`event` (no `next`); `want.tools` is the kept entries' indices, or `null` for no override. It pins
that a nameless provider entry is judged under the key `""`.

`user-text-cases.json` pins the latest-user-text extraction the beforeLLM hooks use.
Hook behaviour (`hook` blocks in tool-guard / content-guard) is asserted by port-local tests.
