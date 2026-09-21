## ADDED Requirements

### Requirement: The three-state `inheritEnv` control

Every spawn path SHALL accept an optional `inheritEnv` list of environment variable **names**,
with three distinguishable states. A port MUST be able to tell an omitted `inheritEnv` from an
empty one — an empty list is a deliberate instruction to narrow, not an absence.

- omitted / `undefined` / `null` ⇒ the child receives the **entire** parent environment
- `[]` ⇒ the child receives the **safe base only**
- `[name, …]` ⇒ the child receives the safe base **plus** each named parent variable

`inheritEnv` narrows only *which names* cross the process boundary. Values SHALL always be read
from the live parent environment at spawn time, SHALL never be read from a configuration file,
and SHALL never be logged.

#### Scenario: Omitted inheritEnv is byte-identical to today

- **WHEN** a child is spawned and `inheritEnv` is omitted
- **THEN** the child's environment is the full parent environment merged with any explicit entries
- **AND** the spawn is byte-identical to one performed before this capability existed

#### Scenario: Empty list narrows to the safe base

- **WHEN** the parent environment contains `ACME_BILLING_TOKEN` and a child is spawned with `inheritEnv: []`
- **THEN** the child's environment contains the safe-base names that are present in the parent
- **AND** the child's environment does not contain `ACME_BILLING_TOKEN`

#### Scenario: A named variable is passed through alongside the safe base

- **WHEN** a child is spawned with `inheritEnv: ["ACME_BILLING_TOKEN"]`
- **THEN** the child's environment contains `ACME_BILLING_TOKEN` with the parent's value
- **AND** it contains the safe-base names present in the parent
- **AND** it contains no other parent variable

#### Scenario: A requested name absent from the parent is omitted, not blanked

- **WHEN** a child is spawned with `inheritEnv: ["NOT_SET_ANYWHERE"]` and that name is absent from the parent
- **THEN** `NOT_SET_ANYWHERE` is absent from the child's environment
- **AND** it is not present with an empty value

### Requirement: The safe base is a pinned, byte-identical set

The safe base SHALL be the following names, and SHALL be identical in every port, because a set
that drifts per language means one configuration leaks differently depending on which port a team
deployed.

On every platform: `HOME`, `LANG`, `LC_ALL`, `PATH`, `TMPDIR`, `TZ`.

On Windows, additionally: `APPDATA`, `COMSPEC`, `HOMEDRIVE`, `HOMEPATH`, `LOCALAPPDATA`,
`PATHEXT`, `SYSTEMDRIVE`, `SYSTEMROOT`, `TEMP`, `TMP`, `USERPROFILE`, `WINDIR`.

A safe-base name absent from the parent environment SHALL be omitted from the child's environment
rather than set to an empty value. Name matching SHALL be case-sensitive on POSIX platforms and
case-insensitive on Windows, matching each platform's own environment semantics.

#### Scenario: The safe base carries no credential-bearing name

- **WHEN** the safe-base set is inspected
- **THEN** it contains only names required to locate binaries, write temporary files, resolve a home directory, and set locale and timezone
- **AND** it contains no name conventionally used to carry a credential

#### Scenario: Ports agree on the set

- **WHEN** the same `inheritEnv: []` configuration is used in each port against the same parent environment
- **THEN** every port produces the same set of child environment variable names

### Requirement: Explicit entries always win and are never logged

An explicitly configured environment entry SHALL be applied after inheritance and SHALL override
any inherited value of the same name, whatever `inheritEnv` says. Explicit entries SHALL be usable
without being named in `inheritEnv`. Neither an explicit value nor an inherited value SHALL appear
in any log, error message, or tool output produced by the library.

#### Scenario: An explicit entry overrides an inherited one

- **WHEN** the parent environment sets `PATH` and the configuration also sets `PATH` explicitly
- **THEN** the child receives the explicitly configured value

#### Scenario: An explicit entry needs no passthrough

- **WHEN** a child is spawned with `inheritEnv: []` and an explicit entry named `ACME_MODE`
- **THEN** the child's environment contains `ACME_MODE` with the configured value

### Requirement: A loader-level default applies only where a spawn target is silent

A loader or toolkit SHALL accept `defaultInheritEnv`, applied to every spawn target that omits its
own `inheritEnv`. A target's own `inheritEnv` SHALL override the default, including when that value
is an empty list. When neither is set, the behaviour SHALL be full inheritance.

#### Scenario: The default reaches a silent target

- **WHEN** `defaultInheritEnv: []` is set and a spawn target omits `inheritEnv`
- **THEN** that target's child receives the safe base only

#### Scenario: A target's own empty list overrides a permissive default

- **WHEN** `defaultInheritEnv: ["ACME_BILLING_TOKEN"]` is set and a target declares `inheritEnv: []`
- **THEN** that target's child does not receive `ACME_BILLING_TOKEN`

### Requirement: Scoping an environment is not process isolation

Documentation for this capability SHALL state that narrowing a child's environment is not a
sandbox. A scoped child retains the parent's filesystem reach, network reach, and user identity.
The library SHALL NOT describe `inheritEnv` as a security boundary.

#### Scenario: Documentation states the limit

- **WHEN** a reader consults the documentation for `inheritEnv`
- **THEN** it states that filesystem and network access are unchanged and remain the host's responsibility
