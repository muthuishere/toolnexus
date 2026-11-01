## 1. Contract
- [x] 1.1 SPEC.md §2 command string+args; SPEC.md Conversation memory: FileConversationStore + list/delete
- [x] 1.2 CHANGELOG Unreleased entry

## 2. Ports (command+args, FileConversationStore+list/delete, with tests)
- [x] 2.1 js
- [x] 2.2 python
- [x] 2.3 golang
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir
- [x] 2.7 clojure

## 2b. File store durability mode `sync` (full|fsync|none) — asked by go-agent-cli S5
- [x] 2b.1 golang (NewFileConversationStoreWith + FileStoreOptions{Sync})
- [x] 2b.2 js
- [x] 2b.3 python
- [x] 2b.4 java
- [x] 2b.5 csharp
- [x] 2b.6 elixir
- [x] 2b.7 clojure

## 3. Verify
- [x] 3.1 every port suite green
