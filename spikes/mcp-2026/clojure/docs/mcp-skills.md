# MCP skills extension

> **Clojure port of the spike (openspec `upgrade-mcp-2026-07-28`); the Go spike in `spikes/mcp-2026/golang` is
> the reference.** The API below is `spikes/mcp-2026/clojure` (namespaces `mcp2026.core`, `mcp2026.auth`,
> `mcp2026.devauth`). Every code block is copied verbatim from a program under `examples/` that
> `examples_test.cljc` runs and checks; `docs_test.cljc` fails if a block here stops matching its source.

A server that advertises `capabilities.extensions["io.modelcontextprotocol/skills"]` (SEP-2640) serves
`skills/list` (URI + frontmatter), `skills/get` (one skill's files with sha256 digests and sizes) and the files
themselves through `resources/read` on `skill://<name>/<path>` URIs.

<!-- from: examples/04-skills/main.cljc -->
```clojure
    ;; 1. Does the server speak the skills extension?
    (let [d (mcp/call c "server/discover")]
      (println "skills extension:" (contains? (get-in d ["capabilities" "extensions"]) mcp/skills-ext)))

    ;; 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
    (doseq [sk (get (mcp/call c "skills/list") "skills")]
      (println (str "skill " (get sk "uri") ": " (get-in sk ["frontmatter" "name"]))))

    ;; 3. Load one skill's instructions: resources/read on its SKILL.md.
    (let [uri "skill://hello-world/SKILL.md"
          r (mcp/call c "resources/read" {"uri" uri})
          body (get-in r ["contents" 0 "text"])]
      (println "first heading:" (first-heading body))
```

## Download a whole skill

`download-skill` fetches every file of a skill into `dir/<name>/`. Every resource URI is validated **before
anything is written**: it is percent-decoded once, then refused if it still contains `%` (double encoding), a
backslash, NUL, `:`, an empty / `.` / `..` segment, or names another skill. Each file's sha256 must match the
digest from `skills/get`.

<!-- from: examples/04-skills/main.cljc -->
```clojure
      ;; 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
      (let [dir (.toFile (java.nio.file.Files/createTempDirectory "skills" (make-array java.nio.file.attribute.FileAttribute 0)))]
        (mcp/download-skill c uri dir)
        (doseq [f (sort (for [f (file-seq dir) :when (.isFile f)] (str (.relativize (.toPath dir) (.toPath f)))))]
          (println "downloaded:" f))))
```

```text
skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh
```

## Serving skills

`(mcp/load-server "fixture.json")` walks `skillsDir` for `**/SKILL.md`; the skill name must equal its directory.
The server applies the same URI check before lookup and answers `-32602` for a traversal attempt or an unknown
skill. Frontmatter is the flat `key: value` subset (no YAML parser in the spike).
