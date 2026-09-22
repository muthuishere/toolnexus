;; Runner: print the Clojure port's skill inventory as JSON for the issue-93 harness.
;; Run from the clojure/ port dir:  clojure -M <abs path to this file> <dirs...>
(require '[toolnexus.skill :as skill])

(defn- esc [s] (-> (str s) (clojure.string/replace "\\" "\\\\") (clojure.string/replace "\"" "\\\"")))

(let [inv (skill/list-skills {:dirs (vec *command-line-args*)})]
  (println
   (str "{\"skills\":["
        (clojure.string/join "," (map #(str "{\"location\":\"" (esc (:location %)) "\"}") (:skills inv)))
        "],\"skipped\":["
        (clojure.string/join "," (map #(str "{\"location\":\"" (esc (:location %))
                                            "\",\"reason\":\"" (esc (:reason %)) "\"}") (:skipped inv)))
        "]}")))
