# Design — response adapters for `waitFor`

The `waitFor` contract is settled and unchanged:

```
waitFor(request: Request) -> Answer        // async per port; Request/Answer are §10 data
```

This change ships **pre-built values that satisfy that contract** and names the shape. Everything
below marked **[OPEN]** is an owner decision — pick per dimension; the spec delta only pins what's
already settled, and the follow-up build change resolves the rest.

## Settled

- **S1 — Back-compat is absolute.** A plain function keeps working, byte-for-byte. Additive only.
- **S2 — Adapters are interchangeable with functions.** Wherever `waitFor` accepts a function it
  accepts an adapter; a host cannot tell the difference at the call site.
- **S3 — `stdioResponder` ships in core.** Prints `"<prompt> [y/N] "` for a yes/no `Request`,
  reads one line, maps `"y"` (case-insensitive, trimmed) → `Answer{ok:true}`, anything else →
  `ok:false`. For `kind:"input"` it reads a line and returns it in `Answer.data`. Byte-identical
  prompt + parse across ports (shared fixture).
- **S4 — Durable stays the no-`waitFor` path.** Adapters are blocking resolvers; the
  return-now/answer-later halt is unchanged. (D5 refines how far an adapter may bridge a wait.)

## [OPEN] D1 — Is a `Responder` a bare function or an object?

- **D1a — Bare function / factory (RECO).** `type Responder = (request) -> Answer` — literally the
  `waitFor` signature, named. Adapters are factories returning that function (`stdioResponder()`).
  "Define your own" = write a function. Zero new machinery; most faithful to "don't reinvent."
  *Cost:* a stateful adapter (server/socket) has nowhere to expose `close()` except a closure it
  manages itself.
- **D1b — Object with a method + optional lifecycle.** `Responder = { respond(request) -> Answer;
  close?() }`. `waitFor` accepts `Function | Responder`. Bare function still valid (S1).
  *Benefit:* stateful transport adapters (http/ws/browser) get a real `close()`. *Cost:* two
  shapes to document.
- **D1c — Function + separate disposal convention.** Bare function (D1a), and stateful adapters
  are `AsyncDisposable`/`Closeable` per port (`await using` / `defer close()` / try-with-resources)
  *in addition to* being callable. Idiomatic per language; slightly more surface than D1a.

## [OPEN] D2 — Which transport adapters ship in THIS change?

- **D2a — stdio only (leanest).** Interface/name + `stdioResponder` + test helpers. Transports
  follow in phase 2.
- **D2b — stdio + http-poll.** Adds the "expose requests over HTTP, poll for answers" adapter —
  the owner's request+poll idea. Reuses each port's existing `serve()` HTTP infra.
- **D2c — stdio + http-poll + websocket.** Adds live push/await over a socket.
- **D2d — + browser-page.** Adds an HTML form/consent adapter on top of http. Heaviest parity
  cost (HTML must be byte-identical across 5 ports). Owner flagged as uncertain.

## [OPEN] D3 — http-poll wire contract (if D2b+)

- Endpoints (proposed): `GET /requests` → open `Request`s as JSON; `POST /requests/:id/answer`
  with an `Answer` body → resolves it. Adapter blocks (long-poll or interval) until an answer
  posts for the in-flight `id`.
- **D3a — interval poll** (simple, a tick loop). **D3b — long-poll** (server holds the GET).
  **D3c — both, caller-configurable.**

## [OPEN] D4 — Naming

`Responder` (RECO — it *responds* to a Request with an Answer, matches §10 vocab) ·
`Resolver` · `Elicitor` · `HumanChannel` · `InputChannel` · `Prompter`. Factory naming follows:
`stdioResponder` / `httpPollResponder` / `websocketResponder` / `browserResponder`.

## [OPEN] D5 — How far may one adapter bridge a wait?

Owner wants "one interface spans both postures." Refinement: the *interface* (a blocking
`waitFor`) already spans them by degree —

- A **stdio** adapter blocks seconds.
- An **http-poll / websocket** adapter can block minutes-to-hours *in-process* (the run stays
  alive, resolving when the answer arrives over the wire) — this is the "answer later" case
  handled without ever returning `pending`, as long as the process lives.
- True **cross-process / survive-restart** durability still needs the no-`waitFor` pending path
  (the run halts, another process resumes). No adapter can span that without persistence.

**D5a — Keep it by-degree (RECO):** adapters bridge as long as the process lives; cross-process
durability stays the pending path. One contract, no overload. **D5b — Give adapters an optional
persistence hook** so a poll/ws adapter can also drive the pending+resume path (a `Responder` that,
when its process dies, leaves the `Request` persisted for another process). More unified, more
surface — defer unless owner wants it in v1.

## Recommendation (for a fast, safe v1)

D1a (bare function) · D2b (stdio + http-poll) · D3c · D4 `Responder` · D5a. Ships the batteries
that remove the most boilerplate, reuses existing `serve()` infra, keeps the base import lean, and
overloads nothing. websocket + browser as a clean phase-2 once the pattern is proven in five ports.
