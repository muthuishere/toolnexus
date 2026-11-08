## ADDED Requirements

### Requirement: afterRound hook injects messages at each round boundary

`ClientOptions.hooks.afterRound` SHALL, when set, be called once at the end of each loop round in
run and stream, for every style and on the §7D agent path. On a round where the model called tools
it SHALL fire after all of that round's tool results are appended and before the next request, with
`final: false`. On a round where the model answered with no tool call it SHALL fire only when a turn
remains (`turn + 1 < maxTurns`), with `final: true` and `text` set to the answer. Each non-empty
string in the returned `inject` SHALL be appended, in order, as a plain-text user message. A
non-empty injection on a final round SHALL continue the loop with another request; an empty or
absent one SHALL end the run unchanged. The hook SHALL NOT fire on a pending halt, an error,
`translate`, or an ACP model source. A failing hook SHALL propagate and no further request SHALL be
sent. With the hook absent behaviour SHALL be byte-identical.

#### Scenario: Reminder after tool results

- **WHEN** the model calls a tool and `afterRound` returns `{ inject: ["todo: 2 open"] }`
- **THEN** the next request's transcript ends with the tool result followed by the user message `todo: 2 open`

#### Scenario: Continue when the model stops

- **WHEN** the model answers with no tool call, a turn remains, and `afterRound` returns a non-empty `inject`
- **THEN** the injected user message is appended and another request is sent
- **AND** the run's result is the later round's answer

#### Scenario: Empty injection ends normally

- **WHEN** `afterRound` returns nothing on a final round
- **THEN** the run ends exactly as without the hook

#### Scenario: No continuation past maxTurns

- **WHEN** the final round is the last allowed turn
- **THEN** `afterRound` is not called for it and the run ends with that answer
