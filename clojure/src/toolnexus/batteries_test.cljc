;; add-judge-batteries — every case of examples/judge/batteries/*.json, the
;; latest-user-text cases, the per-turn beforeLLM `:model` override, and the
;; hook behaviours (mirroring golang/judge_batteries_test.go).
(ns toolnexus.batteries-test
  (:require [toolnexus.shared-examples-test :as te]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [toolnexus.batteries :as b]
            [toolnexus.classifier :as jev]
            [toolnexus.client :as client]
            [toolnexus.client-test :as ct]
            [toolnexus.tool :as tool]
            [toolnexus.translate :as tr]))

(defn- fixture [name]
  (json/read-str (fs/read-file (str (te/examples-dir) "/judge/batteries/" name))
                 {:key-fn str}))

(defn- cases [name]
  (let [cs (get (fixture name) "cases")]
    (is (seq cs) (str name ": no cases"))
    cs))

(defn- failing-classifier []
  (jev/create-classifier {:style "custom" :evaluate (fn [_ _] (throw (ex-info "boom" {})))}))

(defn- battery-classifier [c]
  (cond
    (get c "error") (failing-classifier)
    (empty? (get c "calls"))
    (jev/create-classifier {:style "custom"
                            :evaluate (fn [_ _]
                                        (is false (str (get c "name") ": classifier must not be called"))
                                        (throw (ex-info "unexpected call" {})))})
    :else
    (jev/static-classifier (mapv (fn [k] {:state (get k "state") :questions (get k "questions")
                                          :response (get k "response")})
                                 (get c "calls")))))

(defn- norm
  "Numbers compared by value (2 and 2.0 alike), keys as strings, so a verdict
  and a fixture compare structurally."
  [x]
  (cond
    (number? x) (double x)
    (map? x) (reduce-kv (fn [m k v] (assoc m (if (keyword? k) (name k) (str k)) (norm v))) {} x)
    (sequential? x) (mapv norm x)
    :else x))

(defn- assert-verdict [want v]
  (doseq [[k w] want]
    (if (= "error" k)
      (is (= w (contains? v :error)) (str "error present, verdict " (pr-str v)))
      (do (is (contains? v (keyword k)) (str "verdict lacks " k))
          (is (= (norm w) (norm (get v (keyword k)))) k)))))

(defn- on-error [o] (when-let [x (get o "onError")] (keyword x)))
(defn- bands [o] (when-let [x (get o "bands")] {:low (get x "low") :high (get x "high")}))
(defn- items [xs] (mapv (fn [m] {:name (get m "name") :description (get m "description")}) xs))
(defn- nodes [xs]
  (mapv (fn [m] (cond-> {:name (get m "name") :description (get m "description")}
                  (contains? m "agents") (assoc :agents (nodes (get m "agents")))))
        xs))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(deftest tool-guard-cases
  (doseq [c (cases "tool-guard.json")]
    (testing (get c "name")
      (let [o (get c "options") in (get c "input")
            g (b/tool-guard (battery-classifier c)
                            {:on-error (on-error o) :bands (bands o) :role (get o "role")
                             :ask-at (get o "askAt") :deny-at (get o "denyAt")})]
        (assert-verdict (get c "want")
                        (b/check g {:name (get in "name") :arguments (get in "arguments")
                                    :description (get in "description")}))))))

(deftest relevance-cases
  (doseq [[f ctor k] [["tool-relevance.json" b/tool-relevance "tools"]
                      ["skill-relevance.json" b/skill-relevance "skills"]]
          c (cases f)]
    (testing (str f "/" (get c "name"))
      (let [o (get c "options") in (get c "input")
            r (ctor (battery-classifier c) {:on-error (on-error o) :bands (bands o) :role (get o "role")})]
        (assert-verdict (get c "want") (b/select r (get in "prompt") (items (get in k))))))))

(deftest tool-result-filter-cases
  (doseq [c (cases "tool-result-filter.json")]
    (testing (get c "name")
      (let [o (get c "options") in (get c "input")
            f (b/tool-result-filter (battery-classifier c) {:on-error (on-error o) :bands (bands o)})]
        (assert-verdict (get c "want") (b/filter-chunks f (get in "query") (get in "chunks")))))))

(deftest is-complete-cases
  (doseq [c (cases "is-complete.json")]
    (testing (get c "name")
      (let [o (get c "options") in (get c "input")
            ic (b/is-complete (battery-classifier c) {:on-error (on-error o) :bands (bands o)})]
        (assert-verdict (get c "want") (b/check ic (get in "task") (get in "answer")))))))

(deftest agent-router-cases
  (doseq [c (cases "agent-router.json")]
    (testing (get c "name")
      (let [o (get c "options") in (get c "input")
            r (b/agent-router (battery-classifier c) {:bands (bands o)})]
        (assert-verdict (get c "want")
                        (b/pick r (get in "task") (nodes (get in "agents")) (get in "fallback")))))))

(deftest content-guard-cases
  (doseq [c (cases "content-guard.json")]
    (testing (get c "name")
      (let [o (get c "options")
            g (b/content-guard (battery-classifier c)
                               {:on-error (on-error o) :bands (bands o)
                                :dimensions (mapv (fn [d] {:name (get d "name") :instructions (get d "instructions")})
                                                  (get o "dimensions"))})]
        (assert-verdict (get c "want") (b/check g (get-in c ["input" "text"])))))))

(deftest model-router-cases
  (doseq [c (cases "model-router.json")]
    (testing (get c "name")
      (let [o (get c "options") in (get c "input")
            r (b/model-router (battery-classifier c)
                              (mapv (fn [m] {:id (get m "id") :description (get m "description")}) (get in "models"))
                              {:bands (bands o)})]
        (assert-verdict (get c "want") (b/pick r (get in "prompt") (get in "fallback")))))))

(deftest user-text-cases
  (let [cs (cases "user-text-cases.json")]
    (is (<= 5 (count cs)))
    (doseq [c cs]
      (is (= (get c "want") (b/latest-user-text (get c "messages"))) (get c "name")))))

(deftest on-error-is-required
  (let [cl (jev/static-classifier [])]
    (doseq [[nm f] [["tool-guard" #(b/tool-guard cl {})]
                    ["tool-relevance" #(b/tool-relevance cl {})]
                    ["skill-relevance" #(b/skill-relevance cl {})]
                    ["tool-result-filter" #(b/tool-result-filter cl {:on-error :maybe})]
                    ["is-complete" #(b/is-complete cl {:on-error "open"})]
                    ["content-guard" #(b/content-guard cl {})]]]
      (let [e (try (f) nil (catch Throwable t t))]
        (is (some? e) nm)
        (is (str/includes? (str (ex-message e)) "on-error") nm)))))

;; ---------------------------------------------------------------------------
;; hooks
;; ---------------------------------------------------------------------------

(defn- fixed [answers]
  (jev/create-classifier {:style "custom"
                          :evaluate (fn [_ _] (jev/parse-decision {"model" "m" "answers" answers}))}))

(defn- risk [score]
  (fixed {"risk" {"type" "score" "score" score "confidence" 0.9
                  "probabilities" {"0" 0.25 "1" 0.25 "2" 0.25 "3" 0.25}
                  "legend" {"0" "a" "1" "b" "2" "c" "3" "d"}}}))

(def ^:private deploy-tk
  (tool/toolkit [(tool/tool {:name "deploy" :description "deploy"
                             :execute (fn ([_] (tool/success "DEPLOYED")) ([_ _] (tool/success "DEPLOYED")))})]))

(defn- run-with [opts prompt tk]
  (let [out (atom nil)]
    (ct/with-llm "openai" [{:calls [{:id "c1" :name "deploy" :args {}}]} {:text "done"}]
      (fn [{:keys [base requests]}]
        (reset! out {:result (client/run (client/create-client
                                          (merge {:base-url base :style "openai" :model "configured"
                                                  :max-turns 4}
                                                 opts))
                                         prompt {:toolkit tk})
                     :bodies @requests})))
    @out))

(deftest before-llm-model-override-is-per-turn
  (let [seen (atom [])
        {:keys [bodies]} (run-with {:hooks {:before-llm (fn [ev] (when (= 0 (:turn ev)) {:model "small-fast"}))
                                            :after-llm (fn [ev] (swap! seen conj (:model ev)))}}
                                   "go" deploy-tk)]
    (is (= ["small-fast" "configured"] (mapv :model bodies)))
    (is (= ["small-fast" "configured"] @seen)))
  (testing "empty / nil model => configured verbatim"
    (let [{:keys [bodies]} (run-with {:hooks {:before-llm (fn [ev] (if (= 0 (:turn ev)) {:model ""} {:model nil}))}}
                                     "go" deploy-tk)]
      (is (= ["configured" "configured"] (mapv :model bodies))))))

(deftest tool-guard-hook
  (let [ran (atom false)
        nxt (fn [_] (reset! ran true) nil)]
    (testing "ask halts pending with the guard's Request; the tool never runs"
      (let [ran-tool (atom false)
            tk (tool/toolkit [(tool/tool {:name "deploy" :execute (fn [_] (reset! ran-tool true) (tool/success "DEPLOYED"))})])
            g  (b/tool-guard (risk 1.8) {:on-error :closed})
            r  (:result (run-with {:hooks {:before-tool (b/as-hook g nxt)}} "ship it" tk))
            p  (:pending r)]
        (is (= "pending" (:status r)))
        (is (= "toolguard:c1" (:id p)))
        (is (= "approval" (:kind p)))
        (is (= "Approve the call to deploy? (medium risk)" (:prompt p)))
        (is (= {:tool "deploy" :arguments {} :reason "medium risk" :risk 1.8} (:data p)))
        (is (false? @ran-tool))
        (is (false? @ran))))
    (testing "deny short-circuits"
      (let [g (b/tool-guard (risk 2.9) {:on-error :closed})
            r (:result (run-with {:hooks {:before-tool (b/as-hook g nxt)}} "ship it" deploy-tk))]
        (is (false? @ran))
        (is (= ["denied by tool guard: high risk"] (mapv :output (:tool-calls r))))))
    (testing "allow calls next and runs the tool"
      (let [g (b/tool-guard (risk 0.1) {:on-error :closed})
            r (:result (run-with {:hooks {:before-tool (b/as-hook g nxt)}} "ship it" deploy-tk))]
        (is (true? @ran))
        (is (= ["DEPLOYED"] (mapv :output (:tool-calls r))))))
    (testing "approved through wait-for: the tool runs once"
      (let [n  (atom 0)
            tk (tool/toolkit [(tool/tool {:name "deploy"
                                          :execute (fn ([_] (swap! n inc) (tool/success "DEPLOYED"))
                                                     ([_ _] (swap! n inc) (tool/success "DEPLOYED")))})])
            g  (b/tool-guard (risk 1.8) {:on-error :closed})
            r  (:result (run-with {:hooks {:before-tool (b/as-hook g)}
                                   :wait-for (fn [q] (client/make-answer (:id q) true))}
                                  "ship it" tk))]
        (is (not= "pending" (:status r)))
        (is (= ["DEPLOYED"] (mapv :output (:tool-calls r))))
        (is (= 1 @n))))))

(deftest tool-relevance-hook-drops-a-tool
  (let [tk  (tool/toolkit [(tool/tool {:name "deploy" :description "deploy" :execute (fn [_] (tool/success "D"))})
                           (tool/tool {:name "send_email" :description "email" :execute (fn [_] (tool/success "E"))})])
        rel (b/tool-relevance (fixed {"deploy" {"type" "noul" "noul" 0.9}
                                      "send_email" {"type" "noul" "noul" 0.05}})
                              {:on-error :open})
        {:keys [bodies]} (run-with {:hooks {:before-llm (b/as-hook rel)}} "ship it" tk)]
    (is (= ["deploy"] (mapv #(get-in % [:function :name]) (:tools (first bodies)))))))

(deftest content-guard-hook
  (testing "block throws before any request"
    (let [g   (b/content-guard (fixed {"harmful" {"type" "noul" "noul" 0.96}
                                       "prompt_injection" {"type" "noul" "noul" 0.9}})
                               {:on-error :closed})
          n   (atom -1)
          err (atom nil)]
      (ct/with-llm "openai" [{:text "done"}]
        (fn [{:keys [base requests]}]
          (reset! err (try (client/run (client/create-client {:base-url base :style "openai" :model "configured"
                                                              :hooks {:before-llm (b/as-hook g)}})
                                       "idiot" {:toolkit deploy-tk})
                           nil
                           (catch Throwable t (ex-message t))))
          (reset! n (count @requests))))
      (is (= "content guard blocked: harmful, prompt_injection" @err))
      (is (= 0 @n))))
  (testing "review delegates to next"
    (let [called (atom false)
          g (b/content-guard (fixed {"harmful" {"type" "noul" "noul" 0.5}
                                     "prompt_injection" {"type" "noul" "noul" 0.1}})
                             {:on-error :closed})]
      ((b/as-hook g (fn [_] (reset! called true) nil)) {:messages [{:role "user" :content "meh"}]})
      (is (true? @called))))
  (testing "closed error"
    (let [g (b/content-guard (failing-classifier) {:on-error :closed})
          e (try ((b/as-hook g) {:messages [{:role "user" :content "x"}]}) nil
                 (catch Throwable t (ex-message t)))]
      (is (= "content guard blocked: classifier error" e)))))

(deftest tool-result-filter-hook
  (let [f   (b/tool-result-filter (fixed {"0" {"type" "noul" "noul" 0.9}
                                          "1" {"type" "noul" "noul" 0.05}
                                          "2" {"type" "noul" "noul" 0.5}})
                                  {:on-error :open})
        saw (atom nil)
        ov  ((b/as-hook f (fn [ev] (reset! saw (:output (:result ev))) nil))
             {:name "t" :args {} :result (tool/success "a\n\nb\n\nc")})]
    (is (= "a\n\nc" (:output (:result ov))))
    (is (= "a\n\nc" @saw)))
  (let [fe (b/tool-result-filter (failing-classifier) {:on-error :closed})]
    (doseq [r [(tool/success "one")
               (tool/failure "a\n\nb")
               (tool/with-parts (tool/success "a\n\nb") [{:type "image" :mimeType "image/png" :data "x"}])]]
      (is (nil? ((b/as-hook fe) {:name "t" :args {} :result r})) (pr-str r)))))

(deftest model-router-hook
  (let [models [{:id "small-fast" :description "cheap"} {:id "large-reasoning" :description "dear"}]
        sure   {"model" {"type" "choice" "choice" "small-fast" "confidence" 0.91
                         "probabilities" {"small-fast" 0.91 "large-reasoning" 0.09}}}
        unsure {"model" {"type" "choice" "choice" "small-fast" "confidence" 0.6
                         "probabilities" {"small-fast" 0.6 "large-reasoning" 0.4}}}]
    (doseq [[ans want] [[sure "small-fast"] [unsure "configured"]]]
      (let [r (b/model-router (fixed ans) models)
            {:keys [bodies]} (run-with {:hooks {:before-llm (b/as-hook r)}} "capital of France?" deploy-tk)]
        (is (= [want want] (mapv :model bodies)))))
    (testing "unsure, or sure of the configured model itself: no override"
      (doseq [a [unsure sure]]
        (is (nil? ((b/as-hook (b/model-router (fixed a) models))
                   {:model "small-fast" :messages [{:role "user" :content "x"}]})))))
    (testing "no router: verbatim"
      (is (= "configured" (:model (first (:bodies (run-with {} "x" deploy-tk)))))))
    (testing "next's model wins and next sees the routed model"
      (let [saw (atom nil)
            r   (b/model-router (fixed sure) models)
            {:keys [bodies]} (run-with {:hooks {:before-llm (b/as-hook r (fn [ev] (reset! saw (:model ev)) {:model "pinned"}))}}
                                       "x" deploy-tk)]
        (is (= "small-fast" @saw))
        (is (= "pinned" (:model (first bodies))))))))

;; ---------------------------------------------------------------------------
;; tool-relevance.json hookCases — the beforeLLM hook with a provider-entry event
;; ---------------------------------------------------------------------------

(deftest tool-relevance-hook-cases
  (let [cs (get (fixture "tool-relevance.json") "hookCases")]
    (is (seq cs) "tool-relevance.json: no hookCases")
    (doseq [c cs]
      (testing (get c "name")
        (let [o   (get c "options")
              ev  (json/read-str (json/write-str (get c "event")))
              h   (b/as-hook (b/tool-relevance (battery-classifier c)
                                               {:on-error (on-error o) :bands (bands o) :role (get o "role")}))
              ov  (h ev)
              want (get-in c ["want" "tools"])]
          (if (nil? want)
            (is (nil? (:tools ov)))
            (is (= (json/write-str (mapv #(nth (:tools ev) %) want))
                   (json/write-str (:tools ov))))))))))

;; ---------------------------------------------------------------------------
;; §8 beforeLLM — failure stops the call; the reported model is the transmitted one
;; ---------------------------------------------------------------------------

(defn- run-style
  "Run `style` with a scripted tool turn then a text turn; returns
  {:result :bodies :metrics} or {:error :bodies}."
  [style hooks tk]
  (let [out (atom nil)]
    (ct/with-llm style [{:calls [{:id "c1" :name "deploy" :args {}}]} {:text "done"}]
      (fn [{:keys [base requests]}]
        (let [ms (atom [])
              c  (client/create-client {:base-url base :style style :model "configured"
                                        :max-turns 4 :hooks hooks
                                        :on-metric (fn [m] (swap! ms conj m))})
              r  (try {:result (client/run c "go" {:toolkit tk})}
                      (catch Throwable t {:error (ex-message t)}))]
          (reset! out (assoc r :bodies @requests :metrics @ms)))))
    @out))

(defn- boom [_] (throw (ex-info "hook boom" {})))

(deftest before-llm-failure-stops-every-run-style
  (doseq [style ["openai" "anthropic"]]
    (testing style
      (let [{:keys [error bodies result]} (run-style style {:before-llm boom} deploy-tk)]
        (is (nil? result))
        (is (= "hook boom" error))
        (is (= 0 (count bodies)))))
    (testing (str style " — failing on turn 1 stops before the second request")
      (let [{:keys [error bodies]} (run-style style {:before-llm (fn [ev] (when (= 1 (:turn ev)) (boom ev)))} deploy-tk)]
        (is (= "hook boom" error))
        (is (= 1 (count bodies)))))))

(deftest before-llm-failure-stops-translate
  (doseq [style ["openai" "anthropic"]]
    (testing style
      (ct/with-llm style [{:text "x"}]
        (fn [{:keys [base requests]}]
          (let [c (client/create-client {:base-url base :style style :model "configured"
                                         :hooks {:before-llm boom}})
                e (try (tr/translate c {:messages [{:role "user" :content "hi"}]}) nil
                       (catch Throwable t (ex-message t)))]
            (is (= "hook boom" e))
            (is (= 0 (count @requests)))))))))

(deftest reported-model-is-the-transmitted-model
  (doseq [style ["openai" "anthropic"]]
    (testing (str style " — override on turn 0 only: last call used the configured model")
      (let [{:keys [result bodies metrics]}
            (run-style style {:before-llm (fn [ev] (when (= 0 (:turn ev)) {:model "small-fast"}))} deploy-tk)]
        (is (= ["small-fast" "configured"] (mapv :model bodies)))
        (is (= ["small-fast" "configured"] (mapv :model (filter #(= "llm" (:event %)) metrics))))
        (is (= "configured" (:model result)))
        (is (= ["configured"] (mapv :model (filter #(= "run" (:event %)) metrics))))))
    (testing (str style " — override on every turn: run reports it")
      (let [{:keys [result bodies metrics]}
            (run-style style {:before-llm (fn [_] {:model "small-fast"})} deploy-tk)]
        (is (= ["small-fast" "small-fast"] (mapv :model bodies)))
        (is (= "small-fast" (:model result)))
        (is (= ["small-fast"] (mapv :model (filter #(= "run" (:event %)) metrics))))))
    (testing (str style " — absent override: configured, verbatim")
      (let [{:keys [result bodies metrics]} (run-style style {} deploy-tk)]
        (is (= ["configured" "configured"] (mapv :model bodies)))
        (is (= "configured" (:model result)))
        (is (every? (fn [m] (= "configured" (:model m))) (filter (fn [m] (#{"llm" "run"} (:event m))) metrics)))))
    (testing (str style " — pending run reports the last call's model")
      (let [g (b/tool-guard (risk 1.8) {:on-error :closed})
            {:keys [result bodies metrics]}
            (run-style style {:before-llm (fn [_] {:model "small-fast"})
                              :before-tool (b/as-hook g)} deploy-tk)]
        (is (= "pending" (:status result)))
        (is (= 1 (count bodies)))
        (is (= "small-fast" (:model result)))
        (is (= ["small-fast"] (mapv :model (filter #(= "run" (:event %)) metrics)))
            "a pending run emits exactly one run metric")))))

(deftest request-params-model-is-reported-as-sent
  (testing "run: a :request-params model beats the hook's on the wire, and is what is reported"
    (let [out (atom nil)]
      (ct/with-llm "openai" [{:text "done"}]
        (fn [{:keys [base requests]}]
          (let [ms (atom [])
                c  (client/create-client {:base-url base :style "openai" :model "configured"
                                          :request-params {"model" "per-call"}
                                          :hooks {:before-llm (fn [_] {:model "small-fast"})}
                                          :on-metric (fn [m] (swap! ms conj m))})
                r  (client/run c "go" {:toolkit deploy-tk})]
            (reset! out [r @requests @ms]))))
      (let [[r bodies ms] @out]
        (is (= ["per-call"] (mapv :model bodies)))
        (is (= "per-call" (:model r)))
        (is (= ["per-call" "per-call"] (mapv :model (filter #(#{"llm" "run"} (:event %)) ms)))))))
  (testing "translate reports the :request-params model it sent"
    (ct/with-llm "openai" [{:text "x"}]
      (fn [{:keys [base requests]}]
        (let [c (client/create-client {:base-url base :style "openai" :model "configured"
                                       :request-params {"model" "per-call"}})
              r (tr/translate c {:messages [{:role "user" :content "hi"}]})]
          (is (= ["per-call"] (mapv :model @requests)))
          (is (= "per-call" (:model r))))))))

(deftest translate-model-override
  (doseq [style ["openai" "anthropic"]
          [hooks want] [[{:before-llm (fn [_] {:model "small-fast"})} "small-fast"]
                        [{} "configured"]
                        [{:before-llm (fn [_] {:model ""})} "configured"]]]
    (testing (str style " " want)
      (ct/with-llm style [{:text "x"}]
        (fn [{:keys [base requests]}]
          (let [ms (atom [])
                c  (client/create-client {:base-url base :style style :model "configured" :hooks hooks
                                          :on-metric (fn [m] (swap! ms conj m))})
                r  (tr/translate c {:messages [{:role "user" :content "hi"}]})]
            (is (= [want] (mapv :model @requests)))
            (is (= want (:model r)))
            (is (= [want] (mapv :model (filter #(= "llm" (:event %)) @ms))))))))))
