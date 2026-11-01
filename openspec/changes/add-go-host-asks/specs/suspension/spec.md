## ADDED Requirements

### Requirement: waitFor receives the turn context
The client SHALL accept `waitForCtx(ctx, request)`, called with the turn's cancellation context in place of `waitFor` when both are set.

#### Scenario: Cancelling the turn ends a pending ask
- **WHEN** the turn context is cancelled while `waitForCtx` is waiting
- **THEN** `waitForCtx` observes the cancellation and the run returns the cancellation error

### Requirement: Permission gates on beforeTool
A host permission gate SHALL be expressible with `beforeTool` alone: no override allows the call, an error result denies it and is fed to the model as that call's result without failing the run, and a pending result asks through `waitFor`. The `beforeTool` event SHALL carry the tool's hints.

#### Scenario: Plan mode denies a mutating tool
- **WHEN** `beforeTool` returns an error result for a tool whose hints are not read-only
- **THEN** the tool does not run, the model receives the error result, and the run finishes `done`
