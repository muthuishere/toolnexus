## ADDED Requirements

### Requirement: waitFor accepts a pre-built response adapter interchangeably with a function
The client's `waitFor` host SHALL accept either the existing `(request) -> Answer` function
(unchanged) or a pre-built **response adapter** that satisfies the identical Request→Answer
contract. Passing an adapter SHALL be behaviorally interchangeable with passing a function at the
call site: the loop resolves a suspension the same way regardless of which form was supplied. A
host that supplies a plain function SHALL observe behavior byte-identical to before this change
(additive only; no existing usage changes). When no `waitFor` is supplied, the run SHALL still
halt durably with `status: "pending"` exactly as today.

#### Scenario: A bare function still resolves a suspension unchanged
- **WHEN** a client is given `waitFor` as a plain `(request) -> Answer` function and a tool suspends
- **THEN** the loop calls it, feeds the `Answer` back, and continues to `status: "done"` exactly as before this change

#### Scenario: A pre-built adapter resolves a suspension identically
- **WHEN** a client is given `waitFor` as a pre-built response adapter and a tool suspends
- **THEN** the loop resolves the `Request` through the adapter and reaches `status: "done"` with the same result it would produce from an equivalent function

#### Scenario: No waitFor still halts durably
- **WHEN** a client is given no `waitFor` (neither function nor adapter) and a tool suspends
- **THEN** `run()` returns `status: "pending"` with the `Request`, unchanged from prior behavior

### Requirement: A named Responder shape is exposed for custom adapters
Each port SHALL expose a named shape (idiomatic per language, e.g. a `Responder` type alias or
interface) that denotes "a value `waitFor` accepts." The named shape SHALL be the same contract
`waitFor` already speaks, so that a host author defines a custom adapter without learning a new
interface. A user-defined value conforming to that shape SHALL be accepted by `waitFor` on equal
footing with the shipped adapters.

#### Scenario: A user-defined responder is accepted
- **WHEN** a host author supplies their own value conforming to the port's `Responder` shape as `waitFor`
- **THEN** the client accepts it and resolves suspensions through it exactly as it would a shipped adapter

### Requirement: A stdio response adapter is shipped with byte-identical prompt and parse
Each port SHALL ship a `stdioResponder` adapter (idiomatic name per language) that, for a yes/no
`Request`, prints the request prompt followed by `" [y/N] "` to standard output, reads one line
from standard input, and returns `Answer{ ok: true }` when the trimmed, case-insensitive line
equals `"y"` and `Answer{ ok: false }` otherwise. For a `kind: "input"` `Request` it SHALL read one
line and return it in the answer's `data`. The prompt string and parse rule SHALL be byte-identical
across all five ports, verified by a shared conformance fixture.

#### Scenario: stdio adapter approves on "y"
- **WHEN** the `stdioResponder` receives a yes/no `Request` and the operator types `y`
- **THEN** it emits the prompt followed by `" [y/N] "` and returns `Answer{ ok: true }` with the request's id

#### Scenario: stdio adapter declines on anything else
- **WHEN** the `stdioResponder` receives a yes/no `Request` and the operator types anything other than `y` (e.g. empty line or `n`)
- **THEN** it returns `Answer{ ok: false }` with the request's id and the loop reports the decline to the model

### Requirement: Response adapters are blocking resolvers; the durable path is unchanged
A shipped response adapter SHALL be a blocking resolver — it keeps the run alive while it reads,
polls, or awaits, and returns an `Answer` inline. Supplying an adapter SHALL NOT change the
meaning of omitting `waitFor`: the return-now / answer-later durable halt (`status: "pending"`)
remains the no-`waitFor` path. An adapter MAY bridge a long in-process wait (e.g. an HTTP-poll or
socket adapter awaiting a remote answer), but SHALL NOT be required to survive process restarts;
cross-process durability remains the pending/resume path.

#### Scenario: Adapter bridges an in-process wait
- **WHEN** a poll- or socket-backed response adapter is supplied and the human answers while the process is alive
- **THEN** the adapter returns the `Answer` inline and the run completes to `status: "done"` without ever returning `pending`

#### Scenario: Omitting an adapter preserves durable halt
- **WHEN** no adapter (and no function) is supplied and a tool suspends
- **THEN** the run halts with `status: "pending"` and the `Request`, resumable by a different process, unchanged by this change
