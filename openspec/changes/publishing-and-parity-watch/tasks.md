## 1. Pre-release parity check

- [ ] 1.1 Confirm `js/package.json`, `python/pyproject.toml`, the intended Go tag, and `java/build.gradle` all carry the same target version
- [ ] 1.2 Confirm all four hermetic CI suites are green at that version

## 2. Publish each port

- [ ] 2.1 Publish `js/` to npm (`npm publish`, token from env)
- [ ] 2.2 Build and upload `python/` to PyPI (`twine upload`, token from env)
- [ ] 2.3 Tag `golang/vX.Y.Z` and push so `go get` resolves the release
- [ ] 2.4 Publish `java/` to Maven Central (Gradle → Sonatype, signing + token from env)

## 3. Verify installability

- [ ] 3.1 From a clean environment, install each port from its registry at the matching version and import it
- [ ] 3.2 Run the shared `examples/` (mcp.json + hello-world skill) against each installed port and confirm parity

## 4. Document

- [ ] 4.1 Write per-port manual release steps into `PUBLISHING.md`
- [ ] 4.2 Confirm no token value appears in any committed file, CI definition, or example
