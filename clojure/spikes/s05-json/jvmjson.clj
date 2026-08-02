(require '[clojure.data.json :as j])
(doseq [c [{"b" 1 "a" 2 "c" 3}
           {"html" "a<b>c&d"}
           {"f" 1.0 "g" 1.5 "h" 100.0}
           {"uni" "café ☃"}
           {"esc" "tab\there\nnl"}
           [1 2.0 "x" nil true]]]
  (println (j/write-str c)))
