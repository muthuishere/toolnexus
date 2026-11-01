## ADDED Requirements

### Requirement: File-backed conversation store

Every port SHALL ship a `FileConversationStore` constructed with a required root directory. It SHALL
store each conversation as `<root>/<id>.json` holding `{"version":1,"messages":[...]}`, replace it
atomically on `save` (temp file, fsync, rename), and return none from `get` for a missing id. A
corrupt file or unknown version SHALL be an error from `get`, never silently none. An id that is not
`^[A-Za-z0-9._-]{1,128}$` or is `.`/`..` SHALL be rejected by every operation.

#### Scenario: Round trip survives a new store instance

- **WHEN** messages are saved under an id and a new store on the same root calls `get(id)`
- **THEN** the exact messages are returned

#### Scenario: Traversal id rejected

- **WHEN** any operation is called with id `../x` or `a/b`
- **THEN** it fails and no file outside root is touched

#### Scenario: Corrupt file is an error

- **WHEN** `<root>/<id>.json` holds invalid JSON
- **THEN** `get(id)` fails rather than returning none

### Requirement: Conversation stores list and delete

`FileConversationStore` and `InMemoryConversationStore` SHALL provide `list()` returning
`{id, updatedAt}` entries newest first (ties by id ascending) and `delete(id)` which removes the
conversation and succeeds when it is absent. The `ConversationStore` contract SHALL remain the two
operations `get`/`save`; listing and deleting are capabilities of the shipped stores.

#### Scenario: List is newest first

- **WHEN** ids `a` then `b` are saved
- **THEN** `list()` returns `b` before `a`

#### Scenario: Delete then get

- **WHEN** an id is deleted
- **THEN** `get(id)` returns none, `list()` omits it, and deleting again succeeds

### Requirement: File store durability mode

The file store SHALL accept a `sync` option of `full` (default), `fsync`, or `none`, controlling
only the flush before the rename; every mode SHALL keep temp-then-rename replacement. An unknown
mode SHALL fail construction.

#### Scenario: Every mode round-trips

- **WHEN** a store is built with each of `full`, `fsync`, `none` and saves then gets an id
- **THEN** the messages round-trip and no temp file remains

#### Scenario: Unknown mode rejected

- **WHEN** a store is built with `sync` = `bogus`
- **THEN** construction fails
