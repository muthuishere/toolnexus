# `examples/judge/` — shared `Classifier` fixtures

Cross-language fixtures for `SPEC.md §8B`. Every port runs against these files; they are the
contract, not per-port test data. Do not fork a per-language copy — change a fixture once, then
re-verify every port.

## File shape

Each fixture is one JSON object:

| field | meaning |
|---|---|
| `name` | the fixture's id |
| `why` | what a port would get away with if this fixture did not exist |
| `request` | `{ model, state, questions }` — what a caller hands `evaluate` |
| `canonical` | the **exact bytes** a port must emit for `model` + `questions`, as a string |
| `canonicalSha256` | sha256 of those bytes, UTF-8 |
| `canonicalBytes` | their length in bytes |
| `response` | a recorded backend response, gateway-only fields stripped |
| `expect` | what a port must derive from the response |

`decisions.json` is the exception: it holds an `entries` array, each entry carrying its own
`request` / `canonical` / `canonicalSha256` / `response`.

## What the hash covers, and what it does not

`canonicalSha256` covers **`model` + `questions` only** — keys sorted recursively in ASCII order,
arrays never reordered, compact separators, no escaping of `<>&'"` or non-ASCII.

`request.state` is **excluded by construction**, and this is not an oversight: numbers do not
canonicalise across languages (`-0.0` renders four ways, `1e-5` three), so a hash over the whole
body would be unreachable for any state containing a float. `state` is transmitted verbatim as the
host supplied it. `hardened.json` carries `-0.0` and `1e+21` in its state precisely so a port can
see that they are passed through and **not** asserted on.

Recompute any hash with:

```sh
python3 -c "import json,hashlib,sys;d=json.load(open(sys.argv[1]));r=d['request'];b={'model':r['model'],'questions':r['questions']};c=json.dumps(b,sort_keys=True,separators=(',',':'),ensure_ascii=False);print(hashlib.sha256(c.encode()).hexdigest())" base.json
```

## The seven fixtures

| fixture | asserts | a port that skips it |
|---|---|---|
| `base.json` | canonical bytes + parse, one question of each type | cannot serialise or parse at all |
| `hardened.json` | unescaped `<>&"\`, unicode, ASCII key order, unsorted rubric, absent-vs-empty | escapes HTML, sorts culture-sensitively, or renumbers a rubric |
| `numbers.json` | numeric **parse** of `0`, `1.21`, `0.000016716` | round-trips base byte-perfectly while emitting `0.0` for `0` |
| `wide.json` | 40-key criteria and probability maps | passes by accident where small maps iterate in term order only to 32 keys |
| `decisions.json` | the `static` backend corpus: the three guard bands | has no hermetic backend, so CI needs a network and a key |
| `degenerate.json` | one warning per degenerate question key, request bytes unchanged | ships a choice that ranks at chance and says nothing |
| `near-uniform.json` | `nearUniform` at 0.0499 (true) and 0.0501 (false), tolerance 0.05 inclusive | picks its own tolerance and no test notices |

Provenance: `base.json` and `hardened.json` are lifted from `spikes/classifier/fixture/`, the
`decisions.json` bands from the live run recorded in
`spikes/classifier/reports/00-live-backend.md` F3. The rest are constructed against the failures
named in `spikes/classifier/reports/99-verdict.md` §3 and `docs/adr/0021`.
