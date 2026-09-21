# Action-game feasibility — Chrome Dino (measured 2026-09-20)

## Drivability: CONFIRMED
`chromedino.com` and `elgoog.im/t-rex` both expose `Runner.instance_`:
`horizon.obstacles[]` (xPos, yPos, width, typeConfig.type), `currentSpeed`,
`crashed`, `distanceRan`. Canvas is irrelevant — state is in JS.
Flappy Bird exposes `window.__game` (second option).

## The numbers that decide it

| metric | value |
|---|---|
| Playwright `evaluate` round-trip | **0.3 ms** (≈4,100 reads/sec) — not a bottleneck |
| obstacles in 25 s | 173 |
| obstacle lead time p10 | **678 ms** |
| lead time p50 | **2,531 ms** |
| lead time p90 | 4,442 ms |
| dumb code reflex | crashed at distance 1,887 |

Measured Jev latency via OpenRouter this session: **457–870 ms**.

## Verdict: feasible, and the tight tail is the honest finding

- **90% of obstacles give >678 ms of warning; the median gives 2.5 s.** A ~500 ms
  judge call fits comfortably in the common case.
- **The bottom ~10% is marginal** — that is where the demo should *show* degradation
  rather than hide it. Report which decile it starts failing in.
- Because reads cost 0.3 ms, we detect an obstacle the frame it spawns and fire the
  call immediately — banking the whole lead time. This is `pong-jev`'s `--aim` idea:
  move latency into the encoding, not into concurrency.
- A frontier LLM at 1.5–2.5 s fails the p10–p50 band **visibly on screen**. That is the
  demo: same agent, same browser tool, swap only the decision backend.

## Question shape (per jaggedness: never send a number)
One `choice` over `{jump, duck, run}` + speculative nouls, criteria as code-generated
sentences: distance→"about to hit you"/"closing fast"/"still far off",
type→"a tall cactus"/"a low bird", speed→"the ground is racing past".
Code owns all arithmetic (time-to-impact, extrapolation); the judge only ranks.

## Latency, corrected (2026-09-20) — Jev was never slow; the connection was cold

Earlier figures of 457–870 ms were **cold-connection** measurements. Warm:

| call | TLS | TTFB |
|---|---|---|
| 1 (cold) | 39 ms | 384 ms |
| 2–4 (keep-alive) | **0 ms** | **331 / 351 / 331 ms** |

**~335 ms warm median** — inside TypeSafe's published 70–500 ms band.

### Going direct to api.typesafe.ai would be SLOWER, not faster

| host | connect | TLS | TTFB |
|---|---|---|---|
| openrouter.ai | 46 ms | 76 ms | **151 ms** |
| api.typesafe.ai | 280 ms | 545 ms | **814 ms** |

OpenRouter has edge presence near this machine; TypeSafe's origin does not — its TLS
handshake alone costs 545 ms. The gateway is the shortcut, not the tax. (It also needs
no TypeSafe waitlist key.) **Decision: stay on OpenRouter.**

### Consequences for the build
1. **One `http.Client` / one connection, reused for the whole run.** Per-call TLS is
   ~40–70 ms of pure waste; a fresh connection per decision would add ~20% to every tick.
2. Budget **~335 ms** per judge call, not 500–800.
3. Against the dino lead times (p10 678 ms, p50 2,531 ms), 335 ms clears even the tight
   decile with room. Feasibility is stronger than the first pass suggested.
4. Measure and report **p95, not just median** — the corpus only publishes medians.
