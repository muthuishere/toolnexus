(ns mcp2026.toolsearch-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [mcp2026.core :as mcp]
            [mcp2026.wire :as w]))

(defn- hundred-tool-server []
  (let [tools (for [[s noun] [["github" "issue"] ["slack" "message"] ["jira" "ticket"] ["gmail" "email"]
                              ["calendar" "event"] ["drive" "file"] ["stripe" "invoice"] ["postgres" "row"]
                              ["s3" "object"] ["notion" "page"]]
                    v ["create" "get" "list" "update" "delete" "search" "archive" "export" "share" "comment"]]
                {"name" (str s "_" v "_" noun)
                 "description" (str v " a " noun " in " s ". Requires the workspace to be connected; returns the "
                                    noun " as JSON with its id, timestamps and owner.")
                 "inputSchema" {"type" "object"
                                "properties" {"id" {"type" "string" "description" (str "The " noun " id (omit when creating).")}
                                              "query" {"type" "string" "description" "Free-text filter applied server-side."}
                                              "body" {"type" "object" "description" (str "Fields of the " noun " to write.")}
                                              "options" {"type" "object" "properties" {"dryRun" {"type" "boolean"}
                                                                                       "limit" {"type" "integer"}}}}
                                "required" ["query"]}})
        dir (.toFile (java.nio.file.Files/createTempDirectory "ts" (make-array java.nio.file.attribute.FileAttribute 0)))
        f (io/file dir "f.json")]
    (spit f (w/write-json {"server" {"name" "big" "version" "1"} "tools" tools "skillsDir" "."}))
    (mcp/serve! (mcp/load-server (str f)))))

(deftest tool-search-ranking-and-size
  (let [ts (hundred-tool-server) url (str (:url ts) "/mcp")]
    (try
      (let [all (mcp/tools (mcp/client url))
            lazy (mcp/client url {:tool-search true :description "SaaS workspace (GitHub, Slack, Jira, ...)"})
            meta (mcp/tools lazy)]
        (is (= 100 (count all)))
        (doseq [[q want] {"create slack message" "slack_create_message" "search stripe invoices" "stripe_search_invoice"
                          "archive a notion page" "notion_archive_page" "s3 export" "s3_export_object"}]
          (let [hits (mcp/search-tools lazy q 5)]
            (is (= want (get (first hits) "name")) q)
            (is (= (:description lazy) (get (first hits) "server")))))
        (let [r (mcp/invoke lazy "search_tools" {"query" "create slack message"})
              eb (count (w/write-json all)) lb (count (w/write-json meta)) rb (count (w/write-json (get r "content")))]
          (println (format "tool JSON bytes: eager(100 tools)=%d  lazy(2 meta-tools)=%d  + one search_tools result(5 hits)=%d" eb lb rb))
          (is (< (* lb 10) eb) "lazy not smaller")))
      (finally ((:stop ts))))))

(deftest tool-search-invoke-calls-found-tool
  (let [ts (mcp/serve! (mcp/fixture-server))
        c (mcp/client (str (:url ts) "/mcp") {:tool-search true})]
    (try
      (is (= "echo" (get (first (mcp/search-tools c "echo text back" 3)) "name")))
      (is (= "found" (mcp/text (mcp/invoke c "call_tool" {"name" "echo" "arguments" {"text" "found"}}))))
      (is (thrown? Exception (mcp/invoke c "echo" {})) "direct tool names are hidden in tool-search mode")
      (finally ((:stop ts))))))
