;; S24 — the small piece of real toolnexus logic the test suite exercises.
;;
;; Deliberately boring and pure: the point of this spike is the TEST HARNESS,
;; not the logic. But it has to be real enough that "the suite ran" means
;; something — so this is the actual SPEC §0.2 sanitize/naming rule plus a
;; little shaping, not `(= 1 1)`.
;;
;; One .cljc, zero reader conditionals, zero java.*.
(ns toolnexus.logic
  (:require [clojure.string :as strings]))

;; ---------------------------------------------------------------------------
;; The deliberate-failure flag (see toolnexus.logic-test/deliberate-failure-canary).
;;
;; An atom rather than only an env var, so ONE -main run can measure both the
;; passing and the failing arrangement in-process, on every host, without
;; mutating the environment (which is not portable).
;; ---------------------------------------------------------------------------
(def force-fail? (atom false))

(defn forced-failure?
  "True when the canary test should deliberately fail. Set either by the
  harness (the atom) or from outside the process (TN_FORCE_FAIL=1)."
  [env-value]
  (or @force-fail? (= "1" env-value)))

;; ---------------------------------------------------------------------------
;; SPEC §0.2 — tool naming
;; ---------------------------------------------------------------------------
(def ^:private allowed
  (set (concat (map char (range 97 123))     ;; a-z
               (map char (range 48 58))      ;; 0-9
               [\_])))

(defn sanitize
  "Lowercase; every run of characters outside [a-z0-9_] collapses to a single
  underscore; leading and trailing underscores are trimmed. No regex — the
  fold is spelled out so it behaves identically on every host."
  [s]
  (let [lowered (strings/lower-case (str s))
        folded  (reduce (fn [acc c]
                          (let [c' (if (contains? allowed c) c \_)]
                            (if (and (= c' \_) (= \_ (last acc)))
                              acc
                              (conj acc c'))))
                        []
                        lowered)
        trimmed (->> folded
                     (drop-while #(= \_ %))
                     reverse
                     (drop-while #(= \_ %))
                     reverse)]
    (apply str trimmed)))

(defn qualified-name
  "MCP tool naming: <server>_<tool>, both sanitized."
  [server tool]
  (str (sanitize server) "_" (sanitize tool)))

(defn unique-names
  "First-wins de-duplication over an ordered seq of [server tool] pairs,
  preserving order. Collisions are dropped, not renamed."
  [pairs]
  (:names (reduce (fn [acc [server tool]]
                    (let [n (qualified-name server tool)]
                      (if (contains? (:seen acc) n)
                        acc
                        (-> acc (update :seen conj n) (update :names conj n)))))
                  {:seen #{} :names []}
                  pairs)))

(defn tool-summary
  "Shape a tool map down to the three fields every adapter needs."
  [{:keys [name description input-schema]}]
  {:name        (sanitize name)
   :description (or description "")
   :parameters  (or input-schema {:type "object" :properties {}})})

(defn merge-config
  "Later maps win, but a nil value never clobbers an earlier real one."
  [& maps]
  (reduce (fn [acc m]
            (reduce (fn [a [k v]] (if (nil? v) a (assoc a k v))) acc m))
          {}
          maps))
