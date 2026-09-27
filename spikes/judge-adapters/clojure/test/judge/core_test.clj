(ns judge.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.json :as json]
            [judge.core :as j]
            [toolnexus.classifier :as tc]))

(defn- load-json [f] (json/read-str (slurp (str "../shared/" f)) {:key-fn str}))

(defn- ->q [{:strs [kind name instructions options levels]}]
  (case kind "noul" (j/noul name instructions) "choice" (j/choice name instructions options) "score" (j/score name instructions levels)))

(deftest state-cases
  (doseq [{:strs [name state context questions wantState wantQuestions wantError]} (get (load-json "state-cases.json") "cases")]
    (testing name
      (let [qs (map ->q questions)]
        (if wantError
          (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern (java.util.regex.Pattern/quote wantError)) (j/questions qs)))
          (let [st (if context (j/state (context "context") (context "message") (context "extra")) (j/state state))]
            (is (= wantState st))
            (is (= wantQuestions (update-vals (j/questions qs) #(update-keys % clojure.core/name))))))))))

(defn- json->q [nm {:strs [type instructions criteria]}]
  (case type "noul" (j/noul nm instructions criteria) "choice" (j/choice nm instructions criteria) "score" (j/score nm instructions criteria)))

(deftest gate-cases
  (let [{:strs [questions rules cases]} (load-json "gate-cases.json")
        qs    (mapv (fn [[k v]] (json->q k v)) questions)
        rules (mapv (fn [r] {:question (r "question") :below (r "below") :at-least (r "at_least")
                             :is (r "is") :action (r "action") :target (r "target")}) rules)
        st    {"bug" "recorded"}]
    (doseq [{:strs [name answers bands want]} cases]
      (testing name
        (let [c   (tc/create-classifier {:style "static"
                                         :decisions [{:state st :questions (j/questions qs)
                                                      :response {"answers" answers}}]})
              out (j/gate c st qs rules (when bands {:low (bands "low") :high (bands "high")}))]
          (is (= want (update-keys (select-keys out [:action :target :escalated]) clojure.core/name)))
          (when (:escalated out)
            (is (= "input" (get-in out [:request :kind])))
            (is (every? #(contains? (get-in out [:request :data]) %) [:question :reason :answers]))))))))
