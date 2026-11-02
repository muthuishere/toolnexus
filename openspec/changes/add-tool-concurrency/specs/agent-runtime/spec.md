## ADDED Requirements

### Requirement: Background runs by composition
The runtime SHALL support running a subagent in the background with the existing verbs: `spawn` SHALL return a handle whose id is available at once, `wake` SHALL return without waiting for the turn, and `wait` SHALL deliver the next settle with its full `TaskResult`, so a host can call its own completion callback from a background `wait`.

#### Scenario: Background completion
- **WHEN** a host spawns and wakes an agent, then waits on it from a background task
- **THEN** wake returns at once with the id known, and the background wait receives the done result
