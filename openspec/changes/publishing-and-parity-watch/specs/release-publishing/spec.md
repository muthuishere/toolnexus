## ADDED Requirements

### Requirement: Each port is installable from its native registry
Every language port SHALL be publishable to, and installable from, the registry native to its
ecosystem: npm for `js/`, PyPI for `python/`, a tagged Go module for `golang/`, and Maven Central
for `java/`.

#### Scenario: Install each port from its registry
- **WHEN** a user runs the native install command for a port (`npm install toolnexus`,
  `pip install toolnexus`, `go get github.com/muthuishere/toolnexus/golang`, or the Maven/Gradle
  coordinate `io.github.muthuishere:toolnexus`)
- **THEN** the published package resolves and imports successfully without referencing this
  source repository directly

### Requirement: Releases publish all four ports at a matching version
A release SHALL publish `js/`, `python/`, `golang/`, and `java/` at the same version string, so a
user pinning a version gets the same contract in any language.

#### Scenario: Version parity across registries
- **WHEN** version `X.Y.Z` is released
- **THEN** all four ports are published at `X.Y.Z` (npm, PyPI, the Go module tag, and the Maven
  coordinate all resolve to `X.Y.Z`)
- **AND** a port is never released at a version the others have not reached without a recorded
  reason

### Requirement: Publishing uses no committed secrets
Registry credentials SHALL be supplied as environment variables at publish time only. They MUST
NOT be committed to the repository, written into CI configuration, or printed to logs.

#### Scenario: Token stays in the environment
- **WHEN** a port is published
- **THEN** the publish command reads its token from an environment variable at the moment of use
- **AND** no token value appears in any committed file, CI definition, or command output
