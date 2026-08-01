# Complete the Clojure port to full parity

## Why

The Clojure port ships at tier `core` in `conformance/check_options_parity.py`:
every SPEC §0 conformance option is present, and **17 options are genuinely
absent**, printed by name on every gate run. That tier was the honest way to
join the gate without exempting the port — it was never meant to be permanent.

Nothing here is a new behaviour question. All seventeen options are already
specified by archived capability specs and already shipped in six ports, so this
change carries **no spec deltas against those capabilities** — inventing new
wording for settled behaviour is how ports drift. What it adds is a delta to
`clojure-port` (the tier claim itself) plus the per-capability task list.

## What changes

- Implement the 17 absent options against their existing capability specs.
- Promote the port from tier `core` to tier `full` in
  `conformance/options_manifest.json` — a one-line diff that the other six ports
  review, which is the whole point of the tier living in the shared manifest.
- No behaviour change in any other port. No `SPEC.md` change: the
  cross-language contract does not move.

## Method: test-first, against the shipped ports

Each capability is written **tests first**, and the expected values come from
the shipped ports or the capability spec — never from this port's own output.
That rule is not ceremony: this port has twice been caught by a value snapshotted
from itself (a skill payload measured at 1127 bytes on all three runtimes when
the right answer was 995, and an assertion accepting any of three strings that
could not fail). An expected value taken from your own run enshrines whatever
defect produced it.

Every capability must land green in **all five execution modes** — jvm-main,
jvm-repl, cljgo-aot, cljgo-run, cljgo-repl — not just the JVM.

## Out of scope

- The two SPEC defects found while building this port (§7C's `mcp.tools`
  filtering the list but leaving excluded tools callable; §4A vs §3-S2 defining
  `builtins.tools` as two different functions). Those are **cross-port
  behaviour changes** affecting all seven ports and need their own proposals
  with real spec deltas. They are not parity work and must not be smuggled in
  here.
- Real SSE streaming. The loop buffers, and faking deltas out of a buffered body
  would be a lie; that is a separate seam.
