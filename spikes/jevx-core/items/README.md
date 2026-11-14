# Gate items

`readme-quickstart.jsonl` has 11 items. Each one is a documented input from jevcli `README.md:81-108`, where the
README shows real hosted-Jev answers from 2026-10-04. No item was made up for this spike.

That is **below the CEO gate's 20–30 items**. The gate needs real items from real work. Candidate sources, all of
them the owner's call:

- **Turns from the Stop hook.** jevx's ledger (`~/.local/share/jevx/calls.jsonl`) has no content by design, so it
  cannot supply items. Real turns would have to be collected on purpose, for example with
  `jevx judge --request … --proposal …` on transcripts the owner picks.
- **The wfnexus `wfx judge` item files** that the workflows already use (`workflows/*/` verify scripts).
- **The myjev question pack**, in the model-training workspace.

Run the gate (the key goes through `sec`, so its value is never printed):

    go build -o minijevx ./cmd/minijevx && go build -o gate ./cmd/gate
    sec run TYPESAFE_API_KEY -- ./gate --items items/readme-quickstart.jsonl --mini ./minijevx
