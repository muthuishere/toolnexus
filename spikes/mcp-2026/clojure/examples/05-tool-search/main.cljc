;; Example 05 — lazy tool search: give the model 2 tools instead of 100.
;;
;; Run: ./run.sh example 05-tool-search
;;
;; With :tool-search true, (mcp/tools c) returns two meta-tools, search_tools(query, limit?) and
;; call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
;; calls what it found. mcp/invoke runs whatever tool call the model emits.
(ns example.tool-search
  (:require [mcp2026.core :as mcp]
            [mcp2026.wire :as w]))

(defn- size [v] (count (w/write-json v)))

(defn main []
  ;; A server with 100 tools: 10 services x 10 verbs.
  (let [srv (reduce (fn [srv [svc verb]]
                      (let [name (str svc "_" verb)]
                        (mcp/tool srv name (str verb " an item in " svc ".")
                                  (fn [_ctx args] (str name " ran with " (get args "text"))))))
                    (mcp/server)
                    (for [svc ["github" "slack" "jira" "gmail" "calendar" "drive" "stripe" "postgres" "s3" "notion"]
                          verb ["create" "get" "list" "update" "delete" "search" "archive" "export" "share" "comment"]]
                      [svc verb]))
        ts (mcp/serve! srv)
        url (str (:url ts) "/mcp")
        all (mcp/tools (mcp/client url))]

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

    ((:stop ts))))

(main)
(shutdown-agents)
(System/exit 0)
