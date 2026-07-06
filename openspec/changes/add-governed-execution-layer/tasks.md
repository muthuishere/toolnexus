## 1. Shared contract

- [ ] 1.1 Add a "Governed execution (governance hook)" section to `SPEC.md`, alongside §8
      Hooks/Observability: the `governance` option shape, canonical-JSON event format (fixed key
      order), hash-chain rule, and the `toolnexus-receipt/v0` schema (design.md D2–D4).
- [ ] 1.2 Add shared conformance fixtures under `examples/governance/`: a fixed sequence of
      calls (allow, deny, mixed sources) with the expected canonical JSON + sha256 hashes for
      each event and the expected receipt `root`, so every port's test suite can assert against
      the exact same bytes.

## 2. js — reference implementation

- [ ] 2.1 `Toolkit` accepts `governance?: { shield?, log? }`; wrap `execute()` per design.md D1.
- [ ] 2.2 Implement canonical-JSON event serialization + sha256 chain (`prev`/`hash`).
- [ ] 2.3 Implement `rollupReceipt(log, { operator, from?, to? }) -> Receipt`.
- [ ] 2.4 Reference `brain` shield adapter (shells out to `brain check ... --json`, maps
      `veto`/`guaranteed` → `{allow, reason, guaranteed}`); documented as swappable, not required.
- [ ] 2.5 Tests: shield allow/deny short-circuits `execute()`; log chain verifies against the
      shared fixtures; receipt rollup matches fixture `root`; empty-range rollup; no-governance
      no-op path unchanged (byte-identical `ToolResult` vs. before this change).

## 3. python — parity

- [ ] 3.1 Same `Toolkit` option + wrap per design.md D1 (idiomatic Python naming).
- [ ] 3.2 Canonical-JSON + sha256 chain matching the js fixtures exactly.
- [ ] 3.3 `rollup_receipt(...)` matching the js output for the same fixture input.
- [ ] 3.4 Reference `brain` shield adapter.
- [ ] 3.5 Tests mirroring 2.5, asserting against the same shared fixtures (byte-identical
      hashes to js/go/java/csharp).

## 4. golang — parity

- [ ] 4.1 Same `Toolkit` option + wrap per design.md D1 (idiomatic Go naming: `Governance`
      struct with `Shield`/`Log` fields).
- [ ] 4.2 Canonical-JSON + sha256 chain matching the shared fixtures exactly.
- [ ] 4.3 `RollupReceipt(...)` matching the js output for the same fixture input.
- [ ] 4.4 Reference `brain` shield adapter.
- [ ] 4.5 Tests mirroring 2.5 (`go test -race`), asserting against the shared fixtures.

## 5. java — parity

- [ ] 5.1 Same `Toolkit` option + wrap per design.md D1 (idiomatic Java naming: a `Governance`
      builder option with `Shield`/`LogSink` interfaces).
- [ ] 5.2 Canonical-JSON + sha256 chain matching the shared fixtures exactly.
- [ ] 5.3 `rollupReceipt(...)` matching the js output for the same fixture input.
- [ ] 5.4 Reference `brain` shield adapter.
- [ ] 5.5 Tests mirroring 2.5, asserting against the shared fixtures.

## 6. csharp — parity

- [ ] 6.1 Same `Toolkit` option + wrap per design.md D1 (idiomatic C# naming: `GovernanceOptions`
      with `Shield`/`Log` delegates).
- [ ] 6.2 Canonical-JSON + sha256 chain matching the shared fixtures exactly.
- [ ] 6.3 `RollupReceipt(...)` matching the js output for the same fixture input.
- [ ] 6.4 Reference `brain` shield adapter.
- [ ] 6.5 Tests mirroring 2.5, asserting against the shared fixtures.

## 7. Dogfood + verify

- [ ] 7.1 Wire our own fleet's tool routing (the toolnexus instance our agents already run on)
      through the new `governance` option in at least one live host, in shadow mode first
      (log-only, `shield` always-allow) before any deny path is trusted.
      **Owner-gated**: do not flip a real veto on in a shared/production fleet path without
      explicit CEO sign-off — this is exactly the kind of outward-facing, hard-to-reverse
      change the repo's workflow reserves for the owner.
- [ ] 7.2 Confirm CI green across all 5 ports with the new fixtures + tests included.
- [ ] 7.3 Record `company validated toolnexus governed-execution-parity` (or the applicable gate)
      once all 5 ports pass the shared fixtures identically.
- [ ] 7.4 Record a short demo (per the standard demo protocol) showing: a call being shield-
      denied, the resulting log entry, and a receipt rollup verifying against it.

## 8. Explicitly out of scope for this change (tracked, not done here)

- [ ] 8.1 Wiring `ClientOptions.hooks.beforeTool`/`afterTool` to delegate to this layer (design.md
      Open Questions) — separate follow-up change if the owner wants the two guardrail surfaces
      unified.
- [ ] 8.2 A hosted/remote audit plane (roadmap's "Product (later)" stream) — not this change.
- [ ] 8.3 Publishing/release changes of any kind — public release stays owner-gated per the
      roadmap; this change does not touch `release.yml`, version numbers, or `ENABLE_*` vars.
