# MCP tool search

> **Clojure port of the spike (openspec `upgrade-mcp-2026-07-28`); the Go spike in `spikes/mcp-2026/golang` is
> the reference.** The API below is `spikes/mcp-2026/clojure` (namespaces `mcp2026.core`, `mcp2026.auth`,
> `mcp2026.devauth`). Every code block is copied verbatim from a program under `examples/` that
> `examples_test.cljc` runs and checks; `docs_test.cljc` fails if a block here stops matching its source.

A server with 100 tools costs the model every schema on every turn. With `:tool-search true` the client exposes
two tools instead: `search_tools(query, limit?)` returns ranked `{name, description, inputSchema, server}` and
`call_tool(name, arguments)` runs one. Ranking is BM25 over the tool name (counted twice, split on `_` and
camelCase) and description. `tools/list` is cached for the server's `ttlMs`.

<!-- from: examples/05-tool-search/main.cljc -->
```clojure
    ;; 1. Turn on tool search; :description tells the model what this server is for.
    (let [c (mcp/client url {:tool-search true
                             :description "SaaS workspace (GitHub, Slack, Jira, ...)"})
          meta (mcp/tools c)]
      (println (format "eager: %d tools, %d bytes of schema" (count all) (size all)))
      (println (format "lazy:  %d tools, %d bytes of schema" (count meta) (size meta)))

      ;; 2. The model calls search_tools ...
      (let [hits (mcp/search-tools c "send a slack message create" 3)]
        (println "top hit:" (get (first hits) "name"))

        ;; 3. ... then call_tool with the name it found. invoke routes both meta-tools.
        (let [res (mcp/invoke c "call_tool" {"name" (get (first hits) "name") "arguments" {"text" "hi team"}})]
          (println "result:" (mcp/text res)))))
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

With realistic schemas (`toolsearch_test.cljc`) the gap is 56,421 bytes eager vs 519 lazy, plus 3,532 for one
5-hit search result — the same numbers as the Go port (koine's JSON sorts keys like Go's `encoding/json`).

`:description` tells the model what the server is for and is echoed in each hit's `server`. Search is
client-side: the spec has no server-side search method.
