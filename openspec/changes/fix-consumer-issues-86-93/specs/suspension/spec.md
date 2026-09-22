## ADDED Requirements

### Requirement: Resume returns the resumed result

Resuming a suspended run SHALL return the settled result of the topmost handle the resume cascade
re-ran — the result the host would have received from the original run had the suspension never
happened — together with that port's idiomatic error channel. A port SHALL NOT return only an
error, or nothing at all, leaving the resumed agent's answer unreachable through the public API.

The returned result SHALL satisfy every other requirement on a task result: cumulative tokens, a
closed status, and a populated `limit` where the resumed run stopped at a limit.

#### Scenario: A host reads the answer it resumed for

- **WHEN** a host answers a durable suspension and the resumed run completes
- **THEN** the resume call returns that run's result, carrying its text and status

#### Scenario: The resumed result is a full task result

- **WHEN** a resumed run stops at its turn cap
- **THEN** the returned result carries `status: "incomplete"` and the limit name, not merely an error

### Requirement: A resume replays the suspended turn, and says so

A durable resume replays the suspended turn from its pre-turn checkpoint: every tool that ran in
that turn runs again, and the §10 resolution itself re-executes the suspended tool. A tool
reachable in a suspendable turn MUST therefore be idempotent, and the specification and the
documentation SHALL state this **at the point of use** — on the suspension handler's own
documentation and on the pending-request helper — not only in a specification paragraph read
once.

Documentation SHALL NOT describe a resume as continuing from a checkpoint without also stating
what is rewound. Where the runtime's reattachment mechanism is described as the idempotency
guarantee, the specification SHALL state that it covers delegation calls, and that a leaf agent's
own side-effecting tools are the host's responsibility.

#### Scenario: A tool in a suspended turn runs again

- **WHEN** a turn calls a side-effecting tool and then suspends, and the run is later resumed
- **THEN** that tool is invoked again during the replay
- **AND** the documented contract stated this before the host wrote the tool

#### Scenario: The reattachment guarantee is scoped honestly

- **WHEN** a reader looks up what makes a resume idempotent
- **THEN** the text states that reattachment covers delegation calls, and that a leaf's own tools are not covered

### Requirement: An unresolvable answer is an error to the host, never a fabricated result to the model

On a durable resume where the answer reports success, the engine SHALL determine a result for
**every** outstanding tool call from the answer's payload. If it cannot, it SHALL return an error
to the host and SHALL NOT splice a fabricated tool result into the transcript, and SHALL NOT
report the run as done.

The recognised payload keys SHALL be `results`, then `output` (carrying an optional `isError`
alongside it), in that precedence: a payload carrying both resolves through `results`. The keys
and their precedence are the same in every port.

The fabricated filler result SHALL survive only for a genuine partial relay answer, which is
defined as a payload carrying **at least one recognised key** while leaving some outstanding call
unresolved — the multi-call turn where the host deliberately supplied results for some calls and
not others and the transcript must stay balanced. It SHALL NEVER be the response to a payload
carrying no recognised key at all.

The engine SHALL NOT guess a payload from the shape of the map. A single-keyed payload SHALL NOT
be treated as the result merely because it has one key.

Payload type mismatches SHALL fail loudly: a payload whose output value is not a string SHALL
produce an error, never an empty string.

#### Scenario: An unrecognised payload stops the run

- **WHEN** a host resumes with an answer reporting success and a payload the engine cannot map to the outstanding call
- **THEN** the resume returns an error to the host
- **AND** the run is not reported as done, and no fabricated result reaches the model

#### Scenario: The recognised keys and their precedence

- **WHEN** a payload carries both `results` and `output`
- **THEN** the result is taken from `results`, in every port
- **AND** a payload carrying only `output` resolves through it, with its optional `isError` honoured

#### Scenario: A partial relay answer keeps its filler

- **WHEN** a host answers two outstanding relay calls and supplies a recognised key resolving only one
- **THEN** the unanswered call receives the filler result and the transcript stays balanced

#### Scenario: A payload with no recognised key never gets the filler

- **WHEN** a host resumes with a payload carrying neither `results` nor `output`
- **THEN** the resume errors to the host, and no call receives the filler result

#### Scenario: A single-keyed payload is not guessed at

- **WHEN** a host resumes with a payload of one key that is not a recognised result key
- **THEN** the resume errors rather than treating that key's value as the result

#### Scenario: A non-string output is an error

- **WHEN** a host supplies a recognised result key whose value is not a string
- **THEN** the resume errors, and the tool result is not silently an empty string

### Requirement: The answer payload is built by a constructor, not by hand

Every port SHALL provide a constructor that builds an answer carrying a tool's output for a given
request id — named per port, e.g. `AnswerOutput(id, output)` — alongside the existing relay-answer
constructor. The documentation and the error raised on an unresolvable payload SHALL both point at
it, so that the one map a host would otherwise hand-build stops being hand-built.

Every port SHALL also provide its natural pair, `AnswerDeclined(id, reason)`, which builds an
answer reporting that the request was declined and carries the decline reason. A human who says
no is not an error, and a host SHALL NOT have to hand-build that map either. Both constructors
SHALL ship in all seven ports and in the same canonical shape; a constructor present in one port
alone is the drift this change exists to end.

The specification SHALL name the recognised payload keys — `results`, then `output` — and their
precedence, so the contract is discoverable without reading a port's source.

#### Scenario: A host builds an answer without knowing a key

- **WHEN** a host constructs an answer through the constructor with a request id and an output string
- **THEN** the resulting answer resolves the suspension without the host naming any payload key

#### Scenario: A declined request is built by its own constructor

- **WHEN** a host constructs an answer through the decline constructor with a request id and a reason
- **THEN** the resulting answer reports the request as declined, carrying that reason
- **AND** the run resolves as a decline rather than erroring on an unresolvable payload

#### Scenario: Both constructors exist in every port

- **WHEN** the answer constructors are enumerated in each port
- **THEN** both the output constructor and the decline constructor are present, in the same canonical shape

#### Scenario: The error points at the constructor

- **WHEN** a resume fails on an unrecognised payload
- **THEN** the error names the recognised keys and the constructor that avoids them

### Requirement: Request and Answer survive a JSON round trip in every port

`Request` and `Answer` keys are fixed across all ports because they serialize and cross agent
boundaries. Every port SHALL therefore accept an answer whose keys arrive in the wire spelling —
plain strings — regardless of that port's idiomatic key type, and SHALL read the same fields from
it as from a natively-keyed value.

A port SHALL NOT raise on a string-keyed answer, and SHALL NOT read a string-keyed answer's
missing native key as a decline.

#### Scenario: An answer read back from a JSON column resolves

- **WHEN** an answer is serialized to JSON, stored, read back as string-keyed data, and passed to the resume entry point
- **THEN** the resume resolves the suspension exactly as a natively-constructed answer would

#### Scenario: A string-keyed success is not a decline

- **WHEN** a string-keyed answer reports success
- **THEN** it is treated as success in every port, never as a declined or cancelled answer
