## MODIFIED Requirements

### Requirement: Inbound translation preserves tool-call structure
When translating an OpenAI message list to a provider that uses native content blocks, the
port SHALL preserve tool-call structure rather than flattening messages to text.
Specifically: an assistant turn's `tool_calls` SHALL become native tool-use blocks with the
arguments parsed back from their JSON string into an object; a `tool`-role result SHALL
become a native tool-result block keyed by its `tool_call_id`; consecutive tool results
SHALL be merged into a **single** user turn, as providers requiring one result-bearing turn
per preceding assistant turn expect; and `system` messages SHALL be hoisted into the
provider's separate system field.

A message whose `content` is an array SHALL have its **non-text parts preserved** and
translated into the provider's native block shape by the same mapping the loop's adapters use;
text parts SHALL be concatenated as before. Non-text parts SHALL NOT be flattened away or
dropped. Six ports today pass a text-empty `content` array through to the provider raw and
undocumented; that behavior SHALL be replaced in every port by this one specified mapping, so the
seven ports agree by specification rather than by coincidence.

#### Scenario: A multi-turn tool exchange survives translation
- **WHEN** the message list contains a system message, a user message, an assistant turn
  carrying `tool_calls`, and a `tool`-role result carrying `tool_call_id`
- **THEN** the provider receives the system prompt in its own field, a native tool-use block
  whose arguments are an object, and a native tool-result block referencing the same
  `tool_call_id`

#### Scenario: Three consecutive tool results become one user turn
- **WHEN** an assistant turn calls three tools and three `tool`-role results follow
- **THEN** the provider receives one assistant turn with three tool-use blocks and exactly
  **one** user turn carrying all three tool-result blocks

#### Scenario: Content supplied as parts is flattened to text
- **WHEN** a message's `content` is an array of text parts
- **THEN** the parts are concatenated into the translated message's text

#### Scenario: An image part in content survives translation
- **WHEN** a message's `content` is an array holding a text part and an image part
- **THEN** the translated message carries the text and the provider's native image block, in
  the order given, and nothing is dropped
