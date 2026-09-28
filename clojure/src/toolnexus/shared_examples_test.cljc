(ns toolnexus.shared-examples-test
  "Test helper (no tests; the _test suffix keeps it out of the examples/src
  library mirror). Where the suite finds the repo's shared `examples/` fixture tree.

  `TN_EXAMPLES`, when set and non-empty, wins (the gate scripts set it as a hard
  override). Unset, the suite looks for `examples/` in the working directory and
  up to three parents, so `clojure -M -m toolnexus.test-main` run from `clojure/`
  (or the repo root) works out of the box. A candidate counts only if it holds
  the shared fixtures (`mcp.json`, `skills/`, `judge/`), not merely a directory
  called examples — `clojure/examples/` is the port's own example tree."
  (:require [koine.env :as env]
            [koine.fs :as fs]))

(defn- shared-fixtures? [d]
  (and (fs/exists? (str d "/mcp.json"))
       (fs/directory? (str d "/skills"))
       (fs/directory? (str d "/judge"))))

(defn examples-dir
  "Absolute path of the shared examples/ dir, or nil when none is found."
  []
  (let [e (env/get-env "TN_EXAMPLES")]
    (if (seq e)
      e
      (some (fn [d] (when (shared-fixtures? d) (fs/real-path d)))
            ["examples" "../examples" "../../examples" "../../../examples"]))))
