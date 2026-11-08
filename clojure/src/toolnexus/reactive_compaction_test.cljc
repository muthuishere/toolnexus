;; SPEC §7F.1 — reactive compaction on context overflow (issue #194). Replays
;; conformance/reactive_compaction/cases.json (read in place, never copied):
;; every detection case, and every loop case through run AND the streaming
;; form (run with an :on-event sink), in both styles, against an in-test fake
;; endpoint (an injected :http-client answering from the case's script).
;;
;; No java.*, no reader conditionals.
(ns toolnexus.reactive-compaction-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [toolnexus.client :as client]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.tool :as tool]))

(def ^:private fixture
  (delay (json/read-str (fs/read-file (str (te/examples-dir)
                                           "/../conformance/reactive_compaction/cases.json")))))

(deftest detection-replays-the-fixture
  (doseq [c (:detection @fixture)]
    (is (= (:overflow c) (client/context-overflow? (:status c) (:body c))) (:name c))))

(defn- respond [style step n]
  (let [ok (fn [m] {:status 200 :body (json/write-str m)})
        f  @fixture]
    (case step
      "text"     (ok (if (= "anthropic" style)
                       {:content [{:type "text" :text "done"}]}
                       {:choices [{:message {:role "assistant" :content "done"}}]}))
      "tool"     (let [id (str "c" n)]
                   (ok (if (= "anthropic" style)
                         {:content [{:type "tool_use" :id id :name "t" :input {}}]}
                         {:choices [{:message {:role "assistant" :content nil
                                               :tool_calls [{:id id :type "function"
                                                             :function {:name "t" :arguments "{}"}}]}}]})))
      "overflow" (get-in f [:overflowBodies (keyword style)])
      "bad400"   (:bad400 f))))

(def ^:private history
  (vec (for [i (range 8)]
         {:role (if (even? i) "user" "assistant") :content (str "h" i)})))

(defn- replay [style lc streaming?]
  (let [calls  (atom 0)
        hooks  (atom 0)
        befores (atom 0)
        events (atom [])
        script (:script lc)
        c (client/create-client
           (cond-> {:base-url "http://127.0.0.1:1" :model "m" :api-key "k" :style style
                    :retries 0
                    :hooks {:before-llm (fn [_] (swap! befores inc) nil)}
                    :http-client (fn [_u _h _b]
                                   (let [i (swap! calls inc)]
                                     (respond style (nth script (dec i) "text") i)))}
             (:option lc)
             (assoc :compact-on-overflow
                    (fn [{:keys [messages]}]
                      (swap! hooks inc)
                      (when-not (:hookNoop lc) {:messages (vec (rest messages))})))))
        tk (tool/toolkit [(tool/tool {:name "t" :description "t" :execute (fn [_] (tool/success "ok"))})])
        ctx (cond-> {:toolkit tk :history history}
              streaming? (assoc :on-event #(swap! events conj %)))
        r (try {:result (client/run c "go" ctx)} (catch Throwable e {:error e}))]
    {:r r :calls @calls :hooks @hooks :befores @befores :events @events}))

(deftest loop-cases-replay-the-fixture
  (doseq [style ["openai" "anthropic"]
          streaming? [false true]
          lc (:loops @fixture)]
    (testing (str style (if streaming? " stream " " run ") (:name lc))
      (let [{:keys [r calls hooks befores events]} (replay style lc streaming?)
            ex (:expect lc)
            overflows (count (filter #{"overflow"} (:script lc)))]
        (is (= (:calls lc) calls) "calls")
        (if-let [txt (:text ex)]
          (let [res (:result r)]
            (is (= txt (:text res)))
            (is (= (:compactions ex) hooks) "compactions = hook calls")
            ;; retries are not turns, and :before-llm does not re-run for them
            (is (= (- calls overflows) (:turns res)))
            (is (= (- calls overflows) befores))
            (when streaming? (is (= "done" (:type (last events))))))
          (let [d (ex-data (:error r))]
            (is (some? (:error r)))
            (case (:error ex)
              "LlmHttpError" (do (is (= :provider (:toolnexus/error d)))
                                 (is (= (:status ex) (:status d))))
              "ContextOverflowError"
              (do (is (= :context-overflow (:toolnexus/error d)))
                  (is (= (:compactions ex) (:compactions d)))
                  (is (= (:message ex) (ex-message (:error r))))
                  (is (= 400 (:status d)))
                  (is (client/context-overflow? (:status d) (:body d)))))))))))

(deftest compaction-replaces-the-working-transcript
  (let [lc {:option true :script ["overflow" "text"]}
        {:keys [r]} (replay "openai" lc false)]
    ;; 8 history + prompt, minus the first message, plus the answer
    (is (= 9 (count (:messages (:result r)))))
    (is (= "h1" (:content (first (:messages (:result r))))))))
