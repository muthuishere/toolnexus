## 1. Contract

- [x] 1.1 SPEC.md §3: frontmatter parsed with a standard YAML lib per port; scalar-coerce + trim; malformed-safe

## 2. Implement across all five ports (standard YAML lib + trim + tests)

- [x] 2.1 js (reference): `yaml` lib; scalar+trim; dep added; tests (single/folded/literal/empty/malformed) — 40 green
- [x] 2.2 python: PyYAML `safe_load`; runtime dep; tests — 57 green
- [x] 2.3 golang: `gopkg.in/yaml.v3` map + trim (was struct, no trim); tests — go test -race green
- [x] 2.4 java: SnakeYAML 2.3; scalar+strip; dep added; tests — 56 green
- [x] 2.5 csharp: YamlDotNet 16.3.0; scalar+trim; PackageReference; tests — 78 green

## 3. Verify

- [x] 3.1 All five suites green; parity spot-checked (real YAML lib + trim + dep in each)
- [x] 3.2 Live-verified on real folded skills (huddle, reqsume-kernel, video-transcribe) via the JS port
- [ ] 3.3 `openspec validate fix-frontmatter-yaml` passes
- [ ] 3.4 Bump 0.2.1, PR, CI green, merge, archive, release
