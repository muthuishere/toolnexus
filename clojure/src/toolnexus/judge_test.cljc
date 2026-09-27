;; add-judge-adapters — every case of examples/judge/adapters/{state,gate}-cases.json,
;; the byte-identity claim, the batch, and the policy/tape behaviours.
(ns toolnexus.judge-test
  (:require [toolnexus.shared-examples-test :as te]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [toolnexus.classifier :as jev]
            [toolnexus.judge :as j]))

(defn- fixture [name]
  (json/read-str (fs/read-file (str (te/examples-dir) "/judge/adapters/" name ".json"))
                 {:key-fn str}))

(defn- ->q [q]
  (let [n (get q "name") i (get q "instructions")]
    (case (get q "kind")
      "noul"   (if (contains? q "criteria") (j/noul n i (get q "criteria")) (j/noul n i))
      "choice" (j/choice n i (get q "options"))
      "score"  (j/score n i (get q "levels")))))

(defn- ->rule [r]
  (cond-> {:question (get r "question") :action (get r "action")}
    (contains? r "below")    (assoc :below (get r "below"))
    (contains? r "at_least") (assoc :at-least (get r "at_least"))
    (contains? r "is")       (assoc :is (get r "is"))
    (contains? r "target")   (assoc :target (get r "target"))))

(defn- ->bands [b] (when b {:low (get b "low") :high (get b "high")}))

;; ---------------------------------------------------------------------------

(deftest every-state-case
  (doseq [c (get (fixture "state-cases") "cases")]
    (testing (get c "name")
      (let [qs (mapv ->q (get c "questions"))]
        (if-let [want-err (get c "wantError")]
          (let [e (try (j/wire-questions qs) nil (catch Throwable t t))]
            (is (some? e))
            (is (= want-err (ex-message e))))
          (let [st (cond
                     (get c "context") (let [cx (get c "context")]
                                         (j/context (get cx "context") (get cx "message") (get cx "extra")))
                     (get c "roleState") (let [rs (get c "roleState")]
                                           (j/state (get rs "role") (get rs "data")))
                     :else (get c "state"))]
            (is (= (get c "wantState") st))
            (is (= (json/write-str (get c "wantQuestions")) (json/write-str (j/wire-questions qs))))))))))

(deftest every-gate-case
  (let [f      (fixture "gate-cases")
        qmap   (get f "questions")
        qs     (mapv (fn [[n q]]
                       (case (get q "type")
                         "noul"   (j/noul n (get q "instructions") (get q "criteria"))
                         "choice" (j/choice n (get q "instructions") (get q "criteria"))
                         "score"  (j/score n (get q "instructions") (get q "criteria"))))
                     qmap)
        rules  (mapv ->rule (get f "rules"))
        cases  (get f "cases")]
    (is (<= 20 (count cases)) "the shared gate cases are all present")
    (doseq [c cases]
      (testing (get c "name")
        (let [st {"case" (get c "name")}
              cl (jev/static-classifier [{:state st :questions (j/wire-questions qs)
                                          :answers (get c "answers")}])
              b  (->bands (get c "bands"))
              p  (get c "policy")
              rules (if-let [rs (get c "rules")] (mapv ->rule rs) rules)
              o  (if p
                   (j/decide cl st qs {:rules rules :bands b :default (get p "default")
                                       :skip-uncertain (get p "skipUncertain")})
                   (j/gate cl st qs rules b))
              w  (get c "want")]
          (doseq [[n wa] (get c "wantAnswers")]
            (let [a (get (:answers o) n)]
              (is (= (get wa "value") (j/value a)) n)
              (when (contains? wa "band") (is (= (get wa "band") (:band a)) n))
              (when (contains? wa "sure") (is (= (get wa "sure") (:sure a)) n))
              (when (contains? wa "choice") (is (= (get wa "choice") (j/picked a)) n))))
          (is (= (get w "action") (:action o)))
          (is (= (get w "target") (:target o)))
          (is (= (get w "escalated") (:escalated o)))
          (when (:escalated o)
            (is (= "input" (get-in o [:request :kind])))
            (is (= (get w "question") (get-in o [:request :data :question])))
            (when (contains? w "reason")
              (is (= (get w "reason") (get-in o [:request :data :reason]))))
            (is (= (get w "requestId") (get-in o [:request :id])))
            (is (map? (get-in o [:request :data :answers])))))))))

;; ---------------------------------------------------------------------------

(deftest builders-are-byte-identical-to-hand-written-maps
  (let [bodies (atom [])
        http   (fn [_ _ body] (swap! bodies conj body)
                 {:status 200 :body "{\"answers\":{}}"})
        c      (jev/create-classifier {:http-client http :model "jev-1.13.0"})
        st     (j/state "You are Donkey Kong, you want to win." {"message_received" "jump off"})
        hand   {"is_appropriate" {:type "noul" :instructions "Does message_received contain insults?"}
                "component" {:type "choice" :instructions "Which?" :criteria {"pricing" "p" "checkout" "c"}}
                "fix" {:type "score" :instructions "How fixable?" :criteria ["no" "yes"]}}]
    (jev/evaluate c {"role" "You are Donkey Kong, you want to win." "message_received" "jump off"} hand)
    (j/ask c st [(j/noul "is_appropriate" "Does message_received contain insults?")
                 (j/choice :component "Which?" {:pricing "p" :checkout "c"})
                 (j/score "fix" "How fixable?" ["no" "yes"])])
    (is (= 2 (count @bodies)))
    (is (= (first @bodies) (second @bodies)))))

(deftest state-role-and-wrapping
  (is (= {"role" "r" "message_received" "m"} (j/state "r" {"message_received" "m"})))
  (is (= {"role" "r" "data" "plain text"} (j/state "r" "plain text"))))

(deftest bands-values-and-choice
  (let [qs [(j/noul "a" "a?") (j/choice "c" "c?" {"x" "x" "y" "y"}) (j/score "s" "s?" ["0" "1"])]
        ans (fn [answers bands]
              (j/ask (jev/static-classifier [{:state "s" :questions (j/wire-questions qs) :answers answers}])
                     "s" qs bands))
        base {"c" {"type" "choice" "choice" "x" "confidence" 0.8 "probabilities" {"x" 0.9 "y" 0.1}}
              "s" {"type" "score" "score" 1 "confidence" 0.71 "probabilities" {"0" 0.2 "1" 0.8}}}]
    (is (= "uncertain" (:band (get (ans (assoc base "a" {"type" "noul" "noul" 0.3}) nil) "a"))))
    (is (= "uncertain" (:band (get (ans (assoc base "a" {"type" "noul" "noul" 0.7}) nil) "a"))))
    (is (= "yes" (:band (get (ans (assoc base "a" {"type" "noul" "noul" 0.55}) {:low 0.2 :high 0.5}) "a"))))
    (let [a (ans (assoc base "a" {"type" "noul" "noul" 0.96}) nil)]
      (is (= 0.96 (j/value (get a "a"))))
      (is (= "yes" (:band (get a "a"))))
      (is (true? (:sure (get a "c"))))
      (is (= "x" (j/picked (get a "c"))))
      (is (= 0.8 (j/value (get a "c"))))
      (is (= 1 (j/value (get a "s"))))
      (is (nil? (:band (get a "c"))) "choice carries :sure, not a fake band"))
    (testing "a near-uniform choice is not sure"
      (let [a (ans (assoc base "a" {"type" "noul" "noul" 0.5}
                          "c" {"type" "choice" "choice" "x" "confidence" 0.8
                               "probabilities" {"x" 0.5 "y" 0.5}}) nil)]
        (is (false? (:sure (get a "c"))))))))

(deftest policy-default-and-skip-uncertain
  (let [qs [(j/noul "a" "a?") (j/noul "b" "b?")]
        cl (fn [a b] (jev/static-classifier
                      [{:state "s" :questions (j/wire-questions qs)
                        :answers {"a" {"type" "noul" "noul" a} "b" {"type" "noul" "noul" b}}}]))
        rules [{:question "a" :below 0.3 :action "fail"}
               {:question "b" :at-least 0.7 :action "skip_to" :target "t"}]]
    (testing "no rule fired + empty default escalates"
      (let [o (j/decide (cl 0.9 0.1) "s" qs {:rules rules})]
        (is (:escalated o))
        (is (= "no rule fired" (get-in o [:request :data :reason])))
        (is (= "input" (get-in o [:request :kind])))))
    (testing "a non-empty default is the action"
      (is (= "continue" (:action (j/decide (cl 0.9 0.1) "s" qs {:rules rules :default "continue"})))))
    (testing "skip-uncertain lets a later confident rule fire"
      (let [o (j/decide (cl 0.5 0.9) "s" qs {:rules rules :skip-uncertain true})]
        (is (= ["skip_to" "t" false] [(:action o) (:target o) (:escalated o)])))
      (is (:escalated (j/decide (cl 0.5 0.9) "s" qs {:rules rules}))))))

(deftest tape-records-and-replays
  (let [qs   [(j/noul "a" "a?")]
        live (jev/static-classifier [{:state "s" :questions (j/wire-questions qs)
                                      :answers {"a" {"type" "noul" "noul" 0.96}}}])
        t    (j/tape)]
    (is (= "yes" (:band (get (j/ask (j/recording t "plan" live) "s" qs) "a"))))
    (is (= "yes" (:band (get (j/ask (j/replaying t "plan") "other state" qs) "a"))))
    (let [e (try (j/ask (j/replaying t "review") "s" qs) nil (catch Throwable x x))]
      (is (some? e))
      (is (= "tape: no recorded decision for call \"review\"" (ex-message e))))))

;; ---------------------------------------------------------------------------

(deftest evaluate-batch-order-failure-and-empty
  (let [qs  {"a" (jev/noul-question "a?")}
        rec (fn [p] {:state {"i" p} :questions qs :answers {"a" {"type" "noul" "noul" p}}})
        c   (jev/static-classifier [(rec 0.1) (rec 0.5) (rec 0.9)])]
    (testing "decisions come back in state order"
      (let [ds (jev/evaluate-batch c [{"i" 0.9} {"i" 0.1} {"i" 0.5}] qs {:concurrency 2})]
        (is (= [0.9 0.1 0.5] (mapv #(get-in % [:answers "a" :noul]) ds)))))
    (testing "one failure fails the batch, naming its index"
      (let [e (try (jev/evaluate-batch c [{"i" 0.1} {"i" 0.42} {"i" 0.9}] qs) nil
                   (catch Throwable x x))]
        (is (some? e))
        (is (str/includes? (ex-message e) "state 1"))
        (is (= 1 (:index (ex-data e))))))
    (testing "several failures name the lowest index"
      (let [e (try (jev/evaluate-batch c [{"i" 0.42} {"i" 0.1} {"i" 0.43}] qs {:concurrency 3}) nil
                   (catch Throwable x x))]
        (is (str/includes? (ex-message e) "state 0"))
        (is (= 0 (:index (ex-data e))))))
    (testing "empty is an error and sends nothing"
      (let [calls (atom 0)
            live  (jev/create-classifier {:http-client (fn [_ _ _] (swap! calls inc) {:status 200 :body "{}"})})
            e     (try (jev/evaluate-batch live [] qs) nil (catch Throwable x x))]
        (is (some? e))
        (is (zero? @calls))))))
