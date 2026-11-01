## ADDED Requirements

### Requirement: Cache token usage
Usage SHALL report cacheReadTokens and cacheWriteTokens from the provider's response, and SHALL leave the existing prompt/completion/total counts unchanged.

#### Scenario: Anthropic usage
- **WHEN** a response carries cache_read_input_tokens and cache_creation_input_tokens
- **THEN** they are summed into cacheReadTokens and cacheWriteTokens

#### Scenario: OpenAI usage
- **WHEN** a response carries prompt_tokens_details.cached_tokens
- **THEN** it is summed into cacheReadTokens and promptTokens is unchanged

### Requirement: Prompt cache option
With `promptCache` set, the anthropic body SHALL carry cache_control breakpoints on the last tool, the system prompt and the last content block of the latest message, with ttl "1h" when longTTL is set; the openai body SHALL carry prompt_cache_key when a key is set. The transcript's own message objects SHALL NOT be mutated. Without `promptCache` the body SHALL be byte-identical to before.

#### Scenario: Replay keeps the cache warm
- **WHEN** a multi-turn session with a fixed tool array and a large system prompt, including a mid-session deferred schema load, replays against a simulated prefix cache
- **THEN** cache reads are zero only on the first request, cover the stable prefix from the second request on, and never shrink

#### Scenario: Off by default
- **WHEN** promptCache is absent
- **THEN** no cache_control appears and system stays a string

### Requirement: Tool tiers
With `toolTiers` set, the tools array SHALL be the core tools sorted by name followed by `tool_search` and `call_tool`, and SHALL stay byte-identical for the life of the plan. Listed tools SHALL appear as name and first line, and deferred tools by name only, in `tool_search`'s description. `tool_search` SHALL return full definitions as a tool result. `call_tool` SHALL be unwrapped before hooks run. The plan SHALL be built once per toolkit and frozen; a budget SHALL demote the least-used core tools to listed, then listed to deferred, until the up-front cost fits.

#### Scenario: Deferred tool loaded and called
- **WHEN** the model searches `select:deploy` and then calls `call_tool {name: deploy}`
- **THEN** deploy runs, `beforeTool` sees `deploy` with its hints, and every request carried the same tools array

#### Scenario: Late registration
- **WHEN** a tool is registered after the plan froze
- **THEN** the tools array and the tool_search description are unchanged and the tool is found by search

#### Scenario: Budget
- **WHEN** the core schemas exceed the budget
- **THEN** the least-used core tool moves to listed first
