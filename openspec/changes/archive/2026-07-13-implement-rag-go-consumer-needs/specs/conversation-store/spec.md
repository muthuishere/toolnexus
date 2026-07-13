## ADDED Requirements

### Requirement: Host-facing conversation-store accessor

The client SHALL expose an accessor returning its conversation store — the exact instance the host
passed in options, else the default in-memory store the client created. A host may read (`Get`) and
write (`Save`) that store directly to share conversation state with the client's stateful `ask`, so
no host ever needs to maintain a shadow copy of the transcript.

#### Scenario: Accessor returns the supplied store instance

- **WHEN** a client is created with a host-supplied store
- **THEN** the accessor returns that same instance

#### Scenario: Default store is reachable and reflects saved turns

- **WHEN** a client is created with no store and then runs a stateful `ask` under an id
- **THEN** the accessor returns a non-nil store whose `Get(id)` yields the saved transcript

#### Scenario: Host-written history is continued by ask

- **WHEN** a host writes a transcript under an id via the accessor and then calls `ask` with that id
- **THEN** the client continues from that history
