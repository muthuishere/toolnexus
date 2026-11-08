## ADDED Requirements

### Requirement: Read-only transcript search tool
The library SHALL provide `transcriptSearchTool(store, opts?)`, a read-only `Tool` over a
`ConversationStore` with `list()` (construction fails otherwise). A non-empty `query` SHALL
return case-insensitive substring hits (ASCII-only case folding: `A–Z` only, never Unicode
lowercasing) over message text as
`<conversationId>#<index> <role>: <snippet>`, `no matches` when none. It SHALL never write.

#### Scenario: Search finds a fact with its location
- **WHEN** conversation `s1` message 4 is an assistant reply containing "Mt. Moon exit is north" and the tool is called with query `mt. moon`
- **THEN** the output contains a line starting `s1#4 assistant:` with a snippet including the match

#### Scenario: Case folding is ASCII-only
- **WHEN** a message holds `ÉLITE` and the tool is called with query `élite`
- **THEN** the output is `no matches`, while query `Élite` finds it

#### Scenario: Empty query is an error
- **WHEN** the tool is called with an empty query
- **THEN** the result is `isError` and the store is not read

#### Scenario: Store without list is rejected
- **WHEN** `transcriptSearchTool` is constructed over a custom store that implements only `get`/`save`
- **THEN** construction fails with an error naming the missing `list()`
