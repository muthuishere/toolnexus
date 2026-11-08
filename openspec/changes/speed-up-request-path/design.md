# Design

Method per port: measure a raw floor (no framework, same work), profile, fix only what the profile
shows, measure before/after interleaved against a clean HEAD worktree.

| port | root cause | fix |
|---|---|---|
| python | `urllib` opened a TCP connection per POST; `anyio.to_thread` per stdio read/write; macOS proxy lookup per POST | thread-local `http.client` keep-alive pool with one stale-retry; `add_reader`/`add_writer` pipe I/O (thread path kept for Windows / non-asyncio); `lru_cache` on proxy decision |
| js | undici `fetch` ~1.5 ms per local call; Ajv built per MCP connect | `nodeFetch` over `node:http` keep-alive, lazily imported on Node only; lazy Ajv |
| java | static SSL `HttpClient` at init; JDK client executor hop; child spawned after mapper; `FutureTask` thread per tool call polling 20 ms; two Jackson stacks | lazy clients; `executor(Runnable::run)`; spawn first; SDK prewarm thread; inline call + shared watchdog; Jackson 3 only |
| csharp | SDK JIT after child ready; OS proxy lookup per send; reflection serializer over a copied tree | background SDK warm-up; `CachedProxy`; direct `Utf8JsonWriter` |

Proxy caching matches `urllib`'s own default opener, which reads proxy settings once per process.
