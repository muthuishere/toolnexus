# Host engine needs: verification run

Branch `add-host-engine-needs`, base `f357c91` (tag `golang/v0.21.0`), uncommitted. macOS, run
2026-10-25 by the toolnexus maintainer agent. Covers OpenSpec changes `add-host-engine-needs`,
`add-mcp-progress-timeout` and `add-host-turn-durability`. One log per port; `exit-codes.txt` has the
exit codes.

| Port | Command | Result |
|---|---|---|
| golang | `go vet ./... && go test -race -count=1 -v ./...` | ok (root 29.8 s, agents 2.8 s) |
| js | `npm test` | 501 pass, 0 fail |
| python | `.venv/bin/python -m pytest -q` | 614 passed |
| java | `./gradlew test --no-daemon` | exit 0 |
| csharp | `dotnet test` | 573/573 |
| elixir | `mix coveralls` | 756 passed, 95.4 % (gate 95) |
| clojure | `./all-modes-check.sh` | 612 tests / 3693 assertions, all 5 modes agree |

## Cancel to process gone (S6 input)

The job ignores SIGTERM and has a TERM-ignoring background child. Timed from cancel until every
process is gone, 10 runs.

- **Product config, `killGraceMs` = 100.** golang 101.0 / 101.4 / 101.8 ms (min/median/max, from
  `golang.log`, this run). The other ports come from their agents' runs: js 100.6–104.0, python
  102–107, java 103–111, elixir 137–141, csharp 165–188, clojure 175–228 (median 204; it is the
  only port whose median misses a 200 ms bar).
- **Stock default, 2000 ms.** elixir measured 2036 ms. go-agent-cli e2e measured about 2.0 s on
  golang. No other port's stock value was measured.

Only golang's numbers were measured in this run. The others are as reported by each port's
implementing agent.

## Not verified here
Windows and Linux, a real provider (all LLM traffic is a mock), and power-loss durability.
