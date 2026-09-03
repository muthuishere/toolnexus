# Tasks

## 1. Contract

- [x] 1.1 Pin the strengthened cancellation clause and the two new scenarios in the
  `a2a-outbound` spec delta (this change). `SPEC.md:601-602` already pins the outcome — no
  SPEC.md edit.

## 2. Ports — the error-path re-check (one per port)

- [x] 2.1 golang — guard the `SendMessage` error path (`golang/a2a.go:451-453`) with the same
  `aborted(parent)` re-check the `GetTask` path has (`:481-487`). Verify:
  `go build ./... && go vet ./... && go test -race ./...`.
- [x] 2.2 java — in `A2A.java`'s `catch (Exception e)` (`:241-244`), return the canceled result
  when `cancelled(ctx)`. Verify: `cd java && ./gradlew test --no-daemon`.
- [x] 2.3 python — in `a2a.py`'s `except Exception` (`:355-356`), return the canceled result when
  `_aborted(signal)`. Verify: `cd python && python -m pytest -q`.
- [x] 2.4 clojure — in `run-task`, check `(aborted? ctx)` on the `SendMessage` error path
  (`a2a.cljc:206-207`) and the mid-poll `GetTask` error path (`:243-245`) before reporting the
  transport error. Verify: the port's suite + 5-mode exact-agree gate.
- [x] 2.5 elixir — define the `signal` semantics per design (zero-arity predicate, or pid
  alive-check; `reference()` removed from the `@type`), then wire the four points in
  `elixir/lib/toolnexus/a2a.ex`: top-of-loop check, abortable sleep, post-sleep check, `rescue`
  re-check. Verify: `cd elixir && mix test` + `mix coveralls` (≥95%).

## 3. Regression tests — the deterministic mid-request abort (design §shared shape)

- [x] 3.1 Per port: abort mid-`GetTask` (hang-then-500 stub) ⇒ `isError`, `"A2A task t1 canceled"`,
  `metadata.state == "canceled"`, no further polls. Verified to fail without the fix in
  golang/java/python/clojure/elixir; js's existing `a2a: ctx cancel mid-poll` test and csharp's
  `CancelMidPollStopsFurtherGetTask` remain as evidence, strengthened to the mid-request shape if
  they only cover between-polls.
- [x] 3.2 Per port: abort mid-`SendMessage` ⇒ same assertions.

## 4. Ship

- [x] 4.1 `CHANGELOG.md` under `## Unreleased`: an A2A tool call aborted while a request was in
  flight reported a raw transport error (golang/java) or had no cancel support at all (elixir) —
  it now reports the canceled result in every port. Name python/clojure's narrower race as fixed
  too.
- [x] 4.2 `openspec validate audit-a2a-cancel-mid-request`.
- [x] 4.3 Close issue #64 in the PR body (`Closes #64`).
