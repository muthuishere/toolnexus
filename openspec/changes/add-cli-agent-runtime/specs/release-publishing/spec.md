# release-publishing — spec delta

## ADDED Requirements

### Requirement: A release ships the CLI as prebuilt binaries and an npm wrapper
A release SHALL make the `toolnexus` CLI usable without a Go toolchain. It SHALL attach prebuilt
`toolnexus` binaries for the common OS/arch matrix (at minimum darwin/amd64, darwin/arm64,
linux/amd64, linux/arm64, windows/amd64), together with a `SHA256SUMS` checksums file, as assets
on the GitHub Release. It SHALL also publish an npm CLI wrapper package that, on install, fetches
and verifies the matching binary for the host platform. Both SHALL be gated by the same `ENABLE_GO`
control that governs the Go module tag, and SHALL run only for an actual release (not a dry-run
dispatch that has no Release to attach to).

#### Scenario: Binaries attached to the release
- **WHEN** version `X.Y.Z` is released with `ENABLE_GO` enabled
- **THEN** the GitHub Release `vX.Y.Z` carries a `toolnexus` binary asset for each matrix
  platform plus a `SHA256SUMS` file

#### Scenario: npm wrapper installs the matching binary
- **WHEN** a user runs `npm install -g @muthuishere/toolnexus-cli` at version `X.Y.Z`
- **THEN** the wrapper downloads the `X.Y.Z` binary for the host platform, verifies it against
  the published checksums, and exposes a working `toolnexus` command

#### Scenario: Secrets stay in the environment
- **WHEN** the CLI binaries and npm wrapper are published
- **THEN** no credential value appears in any committed file, CI definition, or command output
  (npm uses OIDC trusted publishing; the binary upload uses the run's `GITHUB_TOKEN`)
