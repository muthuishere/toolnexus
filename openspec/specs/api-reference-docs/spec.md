# api-reference-docs Specification

## Purpose
TBD - created by archiving change extend-skill-source. Update Purpose after archive.
## Requirements
### Requirement: Published docs carry per-port API reference

The published documentation site SHALL include an API Reference section documenting the public
surface of every port (js, python, golang, java, csharp): the toolkit construction options, the
client construction options, skill loading and inventory (`LoadSkills` / `ListSkills` and
equivalents), MCP load, and the OpenAI/Anthropic/Gemini adapters. Each documented surface SHALL name
its five per-language equivalents, so a reader on any port can find the corresponding symbol.

#### Scenario: Every port has an API reference page

- **WHEN** the docs site is built
- **THEN** the API Reference section contains a page for each of the five ports covering toolkit options, client options, skill loading/inventory, MCP load, and adapters

#### Scenario: A documented symbol lists its cross-language equivalents

- **WHEN** a reader views the API reference entry for the skill-inventory operation
- **THEN** the entry names the equivalent symbol in all five ports (e.g. `ListSkills` / `listSkills` / `list_skills`)

### Requirement: API reference stays in five-language parity

The API Reference SHALL be kept in parity across the five ports: when a change adds, renames, or
removes a public symbol in one port, the same change SHALL update the reference for every port that
carries that symbol, so the documented surface never drifts between languages.

#### Scenario: A new public symbol is documented for all ports that ship it

- **WHEN** a change introduces a new public toolkit option in all five ports
- **THEN** the API Reference documents that option for all five ports in the same change, with no port left undocumented

