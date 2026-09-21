## ADDED Requirements

### Requirement: A stdio server's child environment is scoped, not inherited wholesale

A local (stdio) MCP server SHALL build its child environment per the `child-process-env`
capability rather than unconditionally passing the parent environment. Each server entry SHALL
accept `inheritEnv`, and the loader options SHALL accept `defaultInheritEnv`. A server's existing
explicit `env` / `environment` map SHALL continue to be applied last and to win on conflict.

This closes gap M4 of `docs/adr/0003-mcp-host-lifecycle-and-liveness.md`: a server binary named in
a configuration file a user copied from the internet SHALL NOT receive the host's unrelated
credentials merely because it was launched.

#### Scenario: A pasted server does not receive the host's keyring

- **WHEN** the parent environment contains `ACME_BILLING_TOKEN` and a stdio server declares `inheritEnv: []`
- **THEN** the launched server's environment does not contain `ACME_BILLING_TOKEN`
- **AND** it contains the safe-base names present in the parent

#### Scenario: A server receives exactly the one secret it needs

- **WHEN** a stdio server declares `inheritEnv: ["CRM_TOKEN"]` and the parent sets both `CRM_TOKEN` and `ACME_BILLING_TOKEN`
- **THEN** the launched server's environment contains `CRM_TOKEN`
- **AND** it does not contain `ACME_BILLING_TOKEN`

#### Scenario: An omitted control leaves the launch unchanged

- **WHEN** a stdio server omits `inheritEnv` and the loader sets no `defaultInheritEnv`
- **THEN** the server is launched with the full parent environment merged with its explicit `env`
- **AND** the launch is byte-identical to one performed before this capability existed

#### Scenario: One loader option narrows every server

- **WHEN** the loader sets `defaultInheritEnv: []` and a configuration names three stdio servers that omit `inheritEnv`
- **THEN** all three are launched with the safe base only

#### Scenario: Remote servers are unaffected

- **WHEN** a configuration contains a remote server whose `headers` expand `${CRM_TOKEN}`
- **THEN** `inheritEnv` does not apply to it
- **AND** its header expansion behaviour is unchanged

### Requirement: A scoped launch reports failures the same way as any other

Narrowing a server's environment SHALL NOT change failure handling. A server that fails to start
because a variable it needed was not passed through SHALL be isolated with status `failed` like any
other failing server, SHALL NOT be fatal to the load, and its diagnostic SHALL name the missing
variable's **name** without ever including a value.

#### Scenario: A server starved of a variable fails in isolation

- **WHEN** a stdio server requires `CRM_TOKEN`, declares `inheritEnv: []`, and fails to start
- **THEN** that server's status is `failed`
- **AND** the other servers in the configuration still load
- **AND** no environment value appears in the diagnostic
