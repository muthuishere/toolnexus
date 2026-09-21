## ADDED Requirements

### Requirement: The static backend's recorded corpus is a named, gated option

`ClassifierOptions` SHALL expose the `static` style's recorded corpus as an option, spelled
idiomatically per port (`decisions` / `Decisions` / `:decisions`), and `SPEC.md §8B` SHALL name
it in the options table alongside the style it serves.

This option SHALL be registered in `conformance/options_manifest.json` under `classifierOptions`
at tier **core**, not `full`. It is the backend CI runs on — no network, no credential, and the
only backend a test may assert a numeric answer against — so a port that lacks it cannot run the
shared `examples/judge/` fixtures at all. A permitted absence would be a hole in the test
strategy, not a missing convenience.

#### Scenario: The parity gate sees the corpus option

- **WHEN** the options-parity checker runs over the seven ports
- **THEN** `decisions` is one of the classifier options it checks
- **AND** a port missing it fails the check regardless of that port's declared tier

#### Scenario: The spec names the field the static style reads

- **WHEN** a reader looks up the `"static"` style in the `ClassifierOptions` table
- **THEN** the table names the option that supplies its recorded decisions

### Requirement: An absent calibrated decodes as true

A `Decision` decoded from the wire SHALL report `calibrated` as `true` when the response carries
no `calibrated` key and when it carries `null`, and as `false` only when it carries the literal
`false`.

The systemone wire reports calibration by being itself, and a backend that is not calibrated says
so explicitly. Every port already behaves this way; stating it makes the agreement a contract
rather than a coincidence, so that a port cannot later default the field to `false` and silently
flip every threshold a host has tuned on a field the host never set.

#### Scenario: A response with no calibrated key

- **WHEN** a backend returns `{"model":"m","answers":{}}`
- **THEN** the decoded decision reports `calibrated` true

#### Scenario: A response with a null calibrated

- **WHEN** a backend returns `{"model":"m","answers":{},"calibrated":null}`
- **THEN** the decoded decision reports `calibrated` true

#### Scenario: Only the literal false is false

- **WHEN** a backend returns `{"model":"m","answers":{},"calibrated":false}`
- **THEN** the decoded decision reports `calibrated` false

### Requirement: Option ids travel as their plain name

Where a port offers a `choiceOver`-style constructor over `(name, description)` pairs, a key that
is not already a string SHALL be coerced to its **plain name** — the identifier a host would
write — and never to that host's printed form.

An Elixir atom `:billing` and a Clojure keyword `:billing` SHALL both reach the wire as
`billing`. A qualified key SHALL keep its qualifier (`:desk/billing` ⇒ `desk/billing`), because
dropping it collides two distinct options into one id. A printed form that retains a sigil
(`":billing"`) yields a schema-valid request whose option ids differ from every other port's, and
from the string the caller then compares the returned `choice` against.

#### Scenario: A keyword or atom roster loses the sigil

- **WHEN** a choice is built over the pairs `{:billing => "money moved", :shipping => "a parcel is late"}`
- **THEN** the emitted criteria keys are exactly `billing` and `shipping`

#### Scenario: A symbolic roster and its string equivalent are the same request

- **WHEN** the same pairs are supplied once with keyword keys and once with the equivalent string keys
- **THEN** the canonical request bytes are identical

#### Scenario: A qualified key keeps its qualifier

- **WHEN** a choice is built over a key qualified as `desk/billing`
- **THEN** the emitted criteria key is `desk/billing`, not `billing`
