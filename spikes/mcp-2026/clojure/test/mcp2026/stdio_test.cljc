(ns mcp2026.stdio-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mcp2026.checks :as checks]
            [mcp2026.core :as mcp]))

(defn stdio-client [mode stderr]
  (mcp/stdio-client ["java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "mcp2026.stdio-server"]
                    {:env {"MCP2026_STDIO_SERVER" mode} :stderr stderr}))

(deftest stdio-same-eight-steps
  (is (checks/run-checks "" #(stdio-client "1" :discard))))

(deftest stdio-stderr-captured-not-parsed-and-concurrent
  (let [lines (atom [])
        c (stdio-client "1" #(swap! lines conj %))
        results (doall (for [i (range 20)] (future (mcp/text (mcp/call-tool c "echo" {"text" (str "msg-" i)})))))]
    (is (= (map #(str "msg-" %) (range 20)) (map deref results)))
    (mcp/close c)
    (Thread/sleep 100)
    (is (some #(str/includes? % "stdio-test-server ready") @lines) "stderr captured")))

(deftest stdio-server-exit-is-an-error
  (let [c (stdio-client "exit" :discard)]
    (try (is (thrown? Exception (mcp/call c "tools/list"))) (finally (mcp/close c)))))
