# spike: jevx on toolnexus

This spike is a mini jevx (`is`, `pick`, `rate` and `--profile`) built only on `github.com/muthuishere/toolnexus/golang`.
It goes with ADR 0038. It changes no library code and nothing in jevx's repo.

- `core.go` is the jevx side: the profile registry, the Jev→llm fallback and the config live here (jevx state;
  toolnexus stays stateless). The stateless pieces it had to write (inclusive thresholds, fail-closed validation) are
  marked `GAP <id>` and are proposed for toolnexus. See `MAP.md`.
- `cmd/minijevx` is the CLI. It keeps jevx's exit codes: 0 yes, 1 no, 3 unsure, 4 error.
- `cmd/gate` is the cutover gate. It runs the same items through the current `jevx` and through `minijevx`, and
  prints where their verdicts agree.
- `MAP.md` maps every jevx package to "core" or "CLI", with file:line on both sides.

## Results

- `go test ./...` runs against a fake System One endpoint, with no network and no key. It checks the inclusive
  thresholds, a profile that changes the verdict, retries on 429/503, fail-closed checks on bad replies, that an
  error never contains the key, the Jev→llm fallback, and that an image item fails loudly. The gate tests use fake
  CLIs.
- **Live run (2026-11-04)** on hosted Jev (`jev-latest`), with the key brokered by `sec run`: **11/11 verdicts
  matched** on `items/readme-quickstart.jsonl`. That is fewer than the 20–30 the gate requires, so it is not a
  cutover decision.
