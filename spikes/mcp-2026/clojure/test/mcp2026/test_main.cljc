(ns mcp2026.test-main
  (:require [clojure.test :as t]
            mcp2026.auth-test mcp2026.retry-test mcp2026.skills-test mcp2026.toolsearch-test
            mcp2026.stdio-test mcp2026.examples-test mcp2026.docs-test mcp2026.keycloak-test))

(defn -main [& nses]
  (let [all '[mcp2026.auth-test mcp2026.retry-test mcp2026.skills-test mcp2026.toolsearch-test
              mcp2026.stdio-test mcp2026.examples-test mcp2026.docs-test mcp2026.keycloak-test]
        r (apply t/run-tests (if (seq nses) (map symbol nses) all))]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
