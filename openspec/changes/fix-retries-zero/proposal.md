# `Retries` must be able to mean zero — and every port must be tested for it

## Why

`ClientOptions.Retries` is documented `0 ⇒ 2`. A consumer building a CLI-backed model
(issue #94) asked for 3 attempts and measured **9 backend invocations** — their 3, each
retried twice, at ~15s per process launch.

For an HTTP client `0 ⇒ 2` is right: a 429 or a dropped socket is worth riding out. For a
model that is **not on a wire**, a retry re-launches a local process to get the same
answer. The repo already knows this and routed around its own option —
`golang/inprocess.go:193` installs `OnError ⇒ TierFail` internally and the comment
explains: *"this port documents `Retries: 0 ⇒ 2`, so zero cannot mean zero here without
changing shipped behaviour."* A host on `createClient` with a custom transport — the
documented path for a local model — gets no such hatch and no signpost to it.

## What a spike found (`spikes/retries-zero/SPIKE.md`)

The first draft of ADR 0023 proposed a `-1` sentinel in all seven ports. **That premise was
falsified for six of them.** JS, Python, Java, C# and Elixir already distinguish "unset"
from "explicit 0" through native mechanisms (`??`, nullable boxed types, keyword presence
plus Elixir's truthy zero, Python's literal default) — each proven with a real client
against a fake failing transport. Adding a sentinel there would be pure noise.

Two real defects remain:

- **`golang/`** is the only port where zero is genuinely inexpressible: `client.go:521-526`
  and `classifier.go:779-785` test `> 0` / `<= 0` on a bare `int`.
- **`clojure/`** has the opposite, pre-existing parity break: its shipped default is **0
  retries, not 2** (`client.cljc:137,520`). Six ports ride out a 429; Clojure does not.

## What changes

The contract becomes explicit and, more importantly, **pinned by a test in all seven
ports**. Six ports already behave correctly; that behaviour is currently untested, which is
precisely the silent drift this repo exists to prevent. A correct behaviour nobody asserts
is one refactor away from being wrong.

- `golang/` — `Retries: -1` means no retries; `0 ⇒ 2` unchanged for every existing caller.
- `clojure/` — default corrected to 2, matching the other six.
- all seven — two tests each: zero ⇒ **exactly one** backend invocation; default ⇒ three.
- `createInProcessClient` becomes a caller of the real spelling and drops its private
  `OnError` workaround, removing the smell this change cites as evidence.

## What this does not do

It does not re-default `0 ⇒ 0`. That is the honest shape and it is a breaking change across
seven registries; not worth it here.
