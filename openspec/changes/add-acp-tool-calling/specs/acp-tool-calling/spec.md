## ADDED Requirements

### Requirement: The ACP prompt carries the OpenAI-shaped request and the tools

Each turn, the ACP model source SHALL send one `session/prompt` whose text is the pinned
preamble, then `REQUEST:` followed by a JSON object holding the assembled request's `messages`
and `tools` arrays unmodified, then the supersedes marker line naming the latest user turn.

#### Scenario: Tool schemas reach the agent

- **WHEN** a host runs an in-process client over an ACP agent with a toolkit that offers a tool
- **THEN** the prompt's REQUEST JSON parses to an object whose `tools` array contains that tool's
  OpenAI-shaped schema, and whose `messages` array is the assembled conversation

#### Scenario: Tool results reach the agent on the next turn

- **WHEN** the agent asked for a tool on one turn and the loop executed it
- **THEN** the next prompt's `messages` contain the assistant `tool_calls` message and the `tool`
  role message carrying the result, with its `tool_call_id`

#### Scenario: The preamble is identical in every port

- **WHEN** any port renders a prompt
- **THEN** the text before `REQUEST:` is byte-identical to the preamble pinned in `SPEC.md`

### Requirement: The agent's reply is parsed into tool calls or content

The ACP model source SHALL parse the accumulated message text with the pinned algorithm,
returning tool calls when the reply is a tool-calling envelope with at least one named call, and
content otherwise; a reply that is not an envelope SHALL pass through as content untouched.

#### Scenario: A tool-calling reply becomes tool calls the loop executes

- **WHEN** the agent replies `{"tool_calls": [{"id": "c1", "type": "function", "function": {"name": "add", "arguments": "{\"a\":2,\"b\":3}"}}]}`
- **THEN** the loop executes `add` with `{"a":2,"b":3}` and sends its result back on the next turn

#### Scenario: Fences, prose and envelopes are tolerated

- **WHEN** the reply wraps the JSON in markdown fences, surrounds it with prose, or nests it as
  `choices[0].message`, or gives `arguments` as an object instead of a string
- **THEN** the same tool calls are produced

#### Scenario: A final answer becomes content

- **WHEN** the agent replies `{"content": "The answer is 5."}`
- **THEN** the run finishes with the text `The answer is 5.`

#### Scenario: Non-envelope replies pass through untouched

- **WHEN** the agent replies with plain prose, or with a JSON object that has none of
  `tool_calls`, `content`, `choices` or `message`
- **THEN** the reply text, exactly as received, is the content

### Requirement: Agent-native tools are refused unless the host opts in

The ACP model source SHALL answer every `session/request_permission` immediately, choosing the
first `reject`-kind option (or `cancelled` when there is none) by default, and the first
`allow`-kind option only when the host sets the allow-agent-tools option.

#### Scenario: Default refuses

- **WHEN** the agent requests permission offering `allow_once` and `reject_once`
- **THEN** the client selects `reject_once` and the turn completes without waiting

#### Scenario: Opt-in allows

- **WHEN** the host set allow-agent-tools and the agent requests permission
- **THEN** the client selects the first `allow`-kind option
