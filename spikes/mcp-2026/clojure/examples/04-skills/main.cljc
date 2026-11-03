;; Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
;;
;; Run: ./run.sh example 04-skills
;;
;; A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
;; skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
;; files themselves through resources/read on skill:// URIs.
(ns example.skills
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mcp2026.core :as mcp]))

(defn- first-heading [md]
  (or (first (filter #(str/starts-with? % "# ") (str/split-lines md))) ""))

(defn main []
  (let [srv (mcp/serve! (mcp/fixture-server)) ; serves examples/skills/hello-world
        c (mcp/client (str (:url srv) "/mcp"))]

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

      ;; 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
      (let [dir (.toFile (java.nio.file.Files/createTempDirectory "skills" (make-array java.nio.file.attribute.FileAttribute 0)))]
        (mcp/download-skill c uri dir)
        (doseq [f (sort (for [f (file-seq dir) :when (.isFile f)] (str (.relativize (.toPath dir) (.toPath f)))))]
          (println "downloaded:" f))))

    ((:stop srv))))

(main)
(shutdown-agents)
(System/exit 0)
