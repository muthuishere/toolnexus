## ADDED Requirements

### Requirement: Lazy tool exposure
With `toolSearch` enabled for an MCP source, the toolkit SHALL expose `search_tools(query)` and `call_tool(name, arguments)` instead of every server tool. Search SHALL rank tools by name and description and include the server's configured `description`. Absent the option, behavior SHALL be unchanged.

#### Scenario: Hundred tools
- **WHEN** a server exposes 100 tools and toolSearch is on
- **THEN** the model sees two tools, and a found tool runs through call_tool with the same result as a direct call
