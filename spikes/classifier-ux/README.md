# spike: classifier UX — client → state → evaluate, one backend enum, client cache

This spike goes with ADR 0038. It is built over `github.com/muthuishere/toolnexus/golang` and changes no library code.

```go
c, _ := cx.New(cx.Options{Backend: cx.TypeSafe, Cache: cx.NewMemoryCache(time.Hour, 10_000)})

r, err := c.State("I was charged twice").Evaluate(ctx,
    tn.Noul("refund", "Is the customer asking for money back?"),
    tn.Choice("team", "Which team?", map[string]string{"billing": "Charges", "tech": "Bugs"}))

r, err = c.State(map[string]any{"note": "listing photo", "photo": tn.File("mug.png")}).
    Evaluate(ctx, tn.Noul("broken", "Is the mug in `photo` broken?"))

rs, err := c.States("ticket 1", "ticket 2").Evaluate(ctx, tn.Noul("bug", "Is this a bug report?"))
```

## Rules

- **One constructor, one enum.** The backends are `typesafe | openrouter | cloudflare | vercel | custom`. Each one carries
  its URL, default model, key env name, and a `Supports` value computed from backend and model. `custom` requires the
  host to declare `Supports`.
- **What a backend can't do fails before the network.** An image sent to a backend that lacks images, an image that
  is not PNG/JPEG, or one outside 280–1568 px returns an error and nothing is sent.
- **Batch is never an error.** No backend has a public native batch, so `States(...).Evaluate` fans out on the client
  (16 at a time by default, results in order). If anything fails, the lowest failing index is reported.
- **Images.** Any `tn.ContentPart` image anywhere in the state is moved into `assets` under its map key, the reference
  takes its place, and `TypeSafe-Danger-Zone-Images: true` is added. On Cloudflare Clef, images go to `input.images`
  as data URLs.
- **Client-level cache, per question.**
  - The key is a hash of endpoint, model, state, image bytes and the question's wire form.
  - The question name and headers are not part of the key.
  - Asking again with one extra question sends only the new one. Renaming a question still hits the cache.
  - `MemoryCache` (TTL + size cap) ships with the spike. Hosts can persist by implementing `Get`/`Set` (jevx's file
    cache would).
- **Replies are validated.** A missing or extra answer, a choice that was not offered, or probabilities that do not sum
  to 1 are rejected.

## Live (2026-11-04, keys via `sec run`)

```
typesafe/jev-1.14.0             text : model=jev-1.14.0 refund=0.70 team=billing (1.00) 552ms
typesafe/jev-1.14.0             cache: cached=[refund team] 0ms
typesafe/jev-1.14.0             image: model=jev-1.14.0 red=0.99
openrouter/typesafe/jev-1.14    text : model=typesafe/jev-1.14-20261014 refund=0.71 team=billing (1.00) 877ms
openrouter (probe, images on)   image: model=typesafe/jev-1.14-20261014 red=0.99   -> Supports.Images = true
cloudflare/typesafe/jev         text : model=jev-1.13.0 refund=0.76 team=billing (1.00) 3699ms
cloudflare/typesafe/jev         image: refused before the wire (not proven)
cloudflare/@cf/cloudflare/clef  text : model=clef refund=0.95 team=billing (0.96) 769ms
cloudflare/@cf/cloudflare/clef  image: model=clef red=0.99
```

Vercel is not live-tested because there is no `AI_GATEWAY_API_KEY` in `sec`. Cloudflare-Jev images are not tested
yet.

## Gaps this spike works around (to move into toolnexus)

- **A1:** the body has no `assets` field. The spike lifts images in through `BodyTransform`, plus a second classifier
  that carries the image header.
- **U1:** the Cloudflare envelope is a `StyleCustom` evaluator written in the spike.
- **Box:** a `tn.Box` question cannot be added because `tn.Question` is closed, so `Supports.Box` is reported but
  cannot be used yet.
- **V1:** validation lives in the spike, not in toolnexus.
