# clojure-port Specification Delta

## MODIFIED Requirements

### Requirement: Parity tier

The Clojure port SHALL be held to the tier declared for it in
`conformance/options_manifest.json`, never to a tier it declares about itself.
On completion of this change the port SHALL be held to tier `full`: every
logical client and toolkit option in the manifest present, with no permitted
absences.

Until every option lands, the port SHALL remain at tier `core` and its absences
SHALL continue to be printed by name on every gate run. A permitted absence that
stops being reported is indistinguishable from one that was implemented.

#### Scenario: The port is held to full tier

- **WHEN** `conformance/check_options_parity.py` runs with the Clojure port at tier `full`
- **THEN** it exits 0 with no tier-debt rows for the Clojure port

#### Scenario: A missing option fails rather than being excused

- **WHEN** the port is at tier `full` and any manifest option has no alias in its options file
- **THEN** the check reports it as a FAILURE and exits non-zero

#### Scenario: Parity work is proven in every execution mode

- **WHEN** a capability is implemented in the Clojure port
- **THEN** `all-modes-check.sh` reports it green in jvm-main, jvm-repl, cljgo-aot, cljgo-run and cljgo-repl
