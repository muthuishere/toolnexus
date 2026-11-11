## 1. Contract

- [x] 1.1 SPEC.md §8C "ACP content parts" (outbound blocks, inbound parts, RunResult.files)
- [x] 1.2 Spec delta `specs/acp-client/spec.md`
- [x] 1.3 CHANGELOG `## Unreleased` entry

## 2. Ports (outbound blocks + inbound parts + RunResult.files, with tests from the porting brief)

- [ ] 2.1 js
- [ ] 2.2 python
- [x] 2.3 golang (`golang/acp.go`, `golang/inprocess.go`, `golang/client.go`; tests `golang/acp_image_test.go`)
- [ ] 2.4 java
- [ ] 2.5 csharp
- [ ] 2.6 elixir
- [ ] 2.7 clojure
- [ ] 2.8 rust

## 3. Verify

- [ ] 3.1 Each port's narrowest suite green; cross-port behaviour matches the four test cases
