(ns mcp2026.docs-test
  "Every code block in docs/*.md is preceded by <!-- from: <path> --> and must appear verbatim in
  that file (an example that examples_test runs). Edit the example, then re-copy the block."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest docs-snippets-match-examples
  (let [pages (sort (filter #(str/ends-with? (str %) ".md") (.listFiles (io/file "docs"))))
        total (atom 0)]
    (is (= 6 (count pages)))
    (doseq [page pages :let [s (slurp page)]]
      (is (str/includes? s "Clojure port of the spike") (str page ": missing the spike banner"))
      (doseq [[_ src block] (re-seq #"(?s)<!-- from: (\S+) -->\n```[a-z]*\n(.*?)\n```" s)]
        (swap! total inc)
        (is (str/includes? (slurp src) block) (str page ": block from " src " no longer matches:\n" block))))
    (is (pos? @total))
    (println @total "code blocks checked across" (count pages) "pages")))
