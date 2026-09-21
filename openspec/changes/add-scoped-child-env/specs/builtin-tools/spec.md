## MODIFIED Requirements

### Requirement: Filesystem and command tools honor safety rules

The command and filesystem tools (`bash`, `write`, `edit`, `apply_patch`) SHALL follow existing repo
secrets rules, since they grant host command execution and filesystem mutation. Environment-expanded values and
command output MUST NOT be logged, and no secret value SHALL be written into spec, test, or example
fixtures. The global toggle SHALL be the supported mechanism for disabling these tools on
locked-down hosts.

Additionally, the `bash` tool SHALL build its child environment per the `child-process-env`
capability rather than unconditionally passing the parent environment. The toolkit's builtin
options SHALL accept `inheritEnv` for this purpose. This matters more here than on any other spawn
path, because the command is written by the model: an instruction reaching the model from fetched
content can otherwise read any credential the host process holds, and `bash` returns that output
to the model.

#### Scenario: Toggle off removes command and mutation tools

- **WHEN** a host disables the builtin source via the global toggle
- **THEN** `bash`, `write`, `edit`, and `apply_patch` are absent from the toolkit

#### Scenario: A model-written command cannot read an unrelated credential

- **WHEN** the host process environment contains `ACME_BILLING_TOKEN`, the builtin options set `inheritEnv: []`, and the model runs a command that prints the environment
- **THEN** the tool result does not contain the value of `ACME_BILLING_TOKEN`

#### Scenario: A command still finds its interpreter and temporary directory

- **WHEN** the builtin options set `inheritEnv: []` and the model runs a command that invokes a binary on `PATH` and writes to a temporary file
- **THEN** the command succeeds

#### Scenario: An omitted control leaves command execution unchanged

- **WHEN** the builtin options omit `inheritEnv`
- **THEN** a command runs with the full parent environment
- **AND** the execution is byte-identical to one performed before this capability existed

#### Scenario: Scoping the environment is not claimed to sandbox the command

- **WHEN** a reader consults the documentation for the `bash` tool's `inheritEnv`
- **THEN** it states that the command retains the host's filesystem and network reach
- **AND** it does not describe `inheritEnv` as a sandbox or a security boundary
