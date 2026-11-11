## 1. Contract

- [x] 1.1 SPEC.md §8C "ACP content parts" (outbound blocks, inbound parts, RunResult.files)
- [x] 1.2 Spec delta `specs/acp-client/spec.md`
- [x] 1.3 CHANGELOG `## Unreleased` entry

## 2. Ports (outbound blocks + inbound parts + RunResult.files, with tests from the porting brief)

- [x] 2.1 js
- [x] 2.2 python
- [x] 2.3 golang (`golang/acp.go`, `golang/inprocess.go`, `golang/client.go`; tests `golang/acp_image_test.go`)
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir
- [x] 2.7 clojure
- [x] 2.8 rust

## 3. Verify

- [x] 3.1 Each port's narrowest suite green; cross-port behaviour matches the four test cases
