;; SPEC §8 "Stop-reason recovery" — conformance/stop_recovery/cases.json replayed
;; case by case, in BOTH styles (openai, anthropic), through an injected
;; `:http-client` that answers from the case's neutral responses and records
;; every request body.
;;
;; Only the `run` loop: this port's network client has no SSE streaming path
;; (see `client/run`'s docstring), so the fixture's "stream" loop has nothing to
;; replay here.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.stop-recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [toolnexus.client :as client]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.tool :as tool]))

(defn- fixture []
  (json/read-str (fs/read-file (str (te/examples-dir) "/../conformance/stop_recovery/cases.json"))))

(def ^:private stops
  {"openai"    {"end" "stop" "truncated" "length" "tool" "tool_calls"}
   "anthropic" {"end" "end_turn" "truncated" "max_tokens" "tool" "tool_use"}})

(defn- wire-response [style r i]
  (let [stop  (get-in stops [style (:stop r)])
        calls (map-indexed (fn [j c] (assoc c :id (str "call_" i "_" j))) (:toolCalls r))]
    (if (= "anthropic" style)
      {:id (str "msg_" i) :type "message" :role "assistant" :stop_reason stop
       :content (vec (concat
                      (when (:thinking r) [{:type "thinking" :thinking (:thinking r) :signature "sig"}])
                      (when (seq (:text r)) [{:type "text" :text (:text r)}])
                      (for [c calls]
                        (let [v (try (json/read-str (:arguments c)) (catch Throwable _ nil))]
                          {:type "tool_use" :id (:id c) :name (:name c)
                           :input (if (map? v) v (:arguments c))}))))
       :usage {:input_tokens 1 :output_tokens 1}}
      {:choices [{:index 0 :finish_reason stop
                  :message (cond-> {:role "assistant" :content (:text r)}
                             (:thinking r) (assoc :reasoning_content (:thinking r))
                             (seq calls) (assoc :tool_calls
                                                (mapv (fn [c] {:id (:id c) :type "function"
                                                               :function {:name (:name c)
                                                                          :arguments (:arguments c)}})
                                                      calls)))}]
       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})))

(defn- replay [style messages kase]
  (let [bodies (atom [])
        runs   (atom [])
        echo   (tool/tool {:name "echo" :description "echo tool"
                           :input-schema {:type "object" :properties {}}
                           :execute (fn [args] (swap! runs conj args) (tool/success "ok"))})
        rs     (:responses kase)
        c      (client/create-client
                (cond-> {:base-url "http://127.0.0.1:1" :style style :model "m" :api-key "k"
                         :retries 0 :stop-recovery (boolean (:stopRecovery kase))
                         :http-client (fn [_u _h body]
                                        (let [i (count @bodies)]
                                          (swap! bodies conj (if (string? body) (json/read-str body) body))
                                          {:status 200
                                           :body (json/write-str (wire-response style (nth rs i) i))}))}
                  (:maxTurns kase) (assoc :max-turns (:maxTurns kase))))
        r      (client/run c "go" {:toolkit (tool/toolkit [echo])})
        ex     (:expect kase)]
    (testing (str style " " (:name kase))
      (is (= (:turns ex) (count @bodies)) "provider calls")
      (is (= (:turns ex) (:turns r)))
      (is (= (:text ex) (:text r)))
      (is (= (mapv #(get messages (keyword %)) (:recoveries ex))
             (vec (keep (fn [b]
                          (let [m (last (:messages b))]
                            (when (and (= "user" (:role m)) (some #{(:content m)} (vals messages)))
                              (:content m))))
                        @bodies)))
          "recoveries, each the last message of the next request")
      (doseq [b @bodies
              :let [m (last (:messages b))]
              :when (some #{(:content m)} (vals messages))]
        (is (= {:role "user" :content (:content m)} m) "plain string content"))
      (is (= (:toolRuns ex) @runs) "echo args"))))

(deftest stop-recovery-conformance
  (let [{:keys [messages cases]} (fixture)]
    (is (= 11 (count cases)))
    (doseq [style ["openai" "anthropic"] kase cases]
      (replay style messages kase))))
