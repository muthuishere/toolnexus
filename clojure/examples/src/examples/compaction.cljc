;; 5. KEEPING A LONG RUN UNDER BUDGET (SPEC §7F)
;;
;; A long-lived agent grows its transcript until it overflows the model's
;; window. `compactor` returns a §8 `:before-llm` hook that summarises the older
;; turns and keeps a recent tail. It adds NO loop behavior — it rides the hook
;; seam that already exists, so compaction is a pure messages -> messages step.
;;
;; Two invariants this example measures rather than asserts in prose:
;;   - the leading system prompt survives verbatim (identity is never summarised)
;;   - the retained tail begins at a `user` turn, so a `tool` message is never
;;     orphaned from the `assistant` carrying its tool_call_id
(ns examples.compaction
  (:require [clojure.string :as str]
            [toolnexus.agents.compaction :as compaction]
            [toolnexus.client :as client]))

(def transcript
  (into [{:role "system" :content "You are Ava. Answer in one sentence."}]
        (concat
         (mapcat (fn [i] [{:role "user" :content (str "question " i)}
                          {:role "assistant" :content (str "answer " i)}])
                 (range 4))
         ;; A tool group: assistant -> tool. Splitting these two apart would
         ;; produce a transcript the provider rejects.
         [{:role "user" :content "what is the weather?"}
          {:role "assistant" :content nil :tool_calls [{:id "c1" :name "weather"}]}
          {:role "tool" :tool_call_id "c1" :content "21C, clear"}
          {:role "assistant" :content "It is 21C and clear."}])))

(defn -main [& _]
  ;; Under budget: the hook returns nil and the run is byte-identical to one
  ;; with no compactor at all. Wiring it in early costs nothing.
  (let [idle (compaction/compactor {:max-tokens 1000000
                                    :summarize (fn [_] "never called")})]
    (println "under budget =>" (pr-str (idle {:messages transcript})))
    (assert (nil? (idle {:messages transcript}))))

  ;; Over budget. `:count-tokens` is injected so the example is deterministic —
  ;; one "token" per message — instead of depending on a JSON writer's bytes.
  (let [hook (compaction/compactor
              {:max-tokens   5
               :keep-tail    4
               :count-tokens count
               :summarize    (fn [older]
                               (str (count older) " earlier turns: "
                                    (str/join ", " (keep :content (take 3 older)))
                                    " …"))})
        out  (:messages (hook {:messages transcript}))]

    (println "\nbefore:" (count transcript) "messages")
    (println "after: " (count out) "messages\n")
    (doseq [m out]
      (println " " (:role m) "|" (pr-str (or (:content m)
                                             (str "tool_calls " (count (:tool_calls m)))))))

    (assert (= (first transcript) (first out))
            "the system prompt is preserved verbatim")
    (assert (str/starts-with? (:content (second out)) "[Summary of earlier conversation]\n")
            "the summary carries the prefix every port emits")
    (assert (= "user" (:role (nth out 2)))
            "the tail begins at a user turn — no orphaned tool message")
    (assert (< (count out) (count transcript)))

    ;; And in a real client it is one option.
    (let [c (client/create-client
             {:base-url "http://127.0.0.1:1" :style "openai"
              :model "gpt-4o-mini" :api-key "not-used"
              :hooks {:before-llm hook}})]
      (assert (fn? (get-in c [:hooks :before-llm])))
      (println "\nwired onto a client via :hooks {:before-llm …}")))

  (println "OK"))
