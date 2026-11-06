# MCP quickstart: zero to tool call

> **Clojure port of the spike (openspec `upgrade-mcp-2026-07-28`); the Go spike in `spikes/mcp-2026/golang` is
> the reference.** The API below is `spikes/mcp-2026/clojure` (namespaces `mcp2026.core`, `mcp2026.auth`,
> `mcp2026.devauth`). Every code block is copied verbatim from a program under `examples/` that
> `examples_test.cljc` runs and checks; `docs_test.cljc` fails if a block here stops matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client: `(mcp/client url)` is a plain map.

## Over HTTP

<!-- from: examples/01-connect-http/main.cljc -->
```clojure
    ;; 1. Point a client at the server's MCP endpoint. No handshake happens here.
    (let [c (mcp/client (str (:url srv) "/mcp"))]

      ;; 2. Optional: server/discover tells you which versions and capabilities the server has.
      (let [info (mcp/call c "server/discover")]
        (println "supported versions:" (get info "supportedVersions")))

      ;; 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
      (println "tools:" (str/join ", " (map #(get % "name") (mcp/tools c))))

      ;; 4. Call a tool. mcp/text returns the first text content of the result.
      (let [res (mcp/call-tool c "echo" {"text" "hello over HTTP"})]
        (println "echo:" (mcp/text res))))
```

Output (`./run.sh example 01-connect-http`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed
(`:stderr` is `:inherit`, `:discard` or a per-line fn). stdio has no HTTP headers and no OAuth: the server
reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio/main.cljc -->
```clojure
    ;; 1. Describe the process to spawn (command vector + env, like ProcessBuilder).
    (let [cmd ["java" "-cp" (System/getProperty "java.class.path") "clojure.main" self]

          ;; 2. Start it and get a client. Same API as the HTTP client: call, tools, call-tool.
          c (mcp/stdio-client cmd {:env {"SERVE_STDIO" "1"}})]
      (try
        ;; 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
        (println "tool count:" (count (mcp/tools c)))

        (let [res (mcp/call-tool c "echo" {"text" "hello over stdio"})]
          (println "echo:" (mcp/text res)))
        (finally (mcp/close c)))))) ; stops the server process
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `call-tool` answers them with your responder fn and re-sends
the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/main.cljc -->
```clojure
    ;; The responder is called once per input request. key identifies the request inside the
    ;; round; req is the request itself ({"method" "elicitation/create", "params" {...}}).
    ;; In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
    (let [ask-user (fn [key req]
                     (println (str "server asks (" (get req "method") "): " (get-in req ["params" "message"])))
                     ;; Answer like a person who filled the form. "decline" / "cancel" are the other actions.
                     {"action" "accept" "content" {"name" "Ada"}})

          res (mcp/call-tool c "greet" {} ask-user)]
      (println "result:" (mcp/text res)))
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

Errors are exceptions: a JSON-RPC error carries its code (`(mcp/rpc-code e)`), a paused authorization carries a
pending (`(mcp/pending-of e)`).

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
