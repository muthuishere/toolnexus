## ADDED Requirements

### Requirement: One-line server protection
`serve` SHALL accept MCP auth config `{issuer, audience?, scopes?, verify?}`. With it the server SHALL serve Protected Resource Metadata at the well-known path, answer a missing or invalid token with 401 and `WWW-Authenticate` naming that metadata, and verify JWTs via the issuer's JWKS (signature, `iss`, `aud` = audience defaulting to the server URL, `exp`). A `verify` hook SHALL replace the default check.

#### Scenario: Wrong audience
- **WHEN** a token for another resource is presented
- **THEN** the server answers 401 invalid_token and the tool does not run

### Requirement: Per-tool scopes and principal
When a tool lists required scopes and the token lacks one, the server SHALL answer 403 `insufficient_scope` with the required scope. The verified principal (subject, scopes, claims) SHALL be visible to the tool during execution.

#### Scenario: Step-up
- **WHEN** a token without `mcp:greet` calls `greet`
- **THEN** the server answers 403 naming `mcp:greet`

### Requirement: Dev authorization server
Each port SHALL ship an in-process development authorization server, documented as not for production, usable in one call for local runs and tests.

#### Scenario: Local end to end
- **WHEN** a test starts the dev AS and a protected server
- **THEN** a toolnexus client logs in and calls a tool with no external services
