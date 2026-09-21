;; 7. CLASSIFIER — THE CONTRACT FOR A JUDGMENT (SPEC §8B)
;;
;; `Tool` is the contract for an ACTION; `Classifier` is the contract for a
;; JUDGMENT. A System One model takes a state plus pre-declared, typed questions
;; and returns calibrated answers with no free text — no messages, no tool
;; calling, no streaming, so it never enters the §8 client loop.
;;
;; Three backends, picked by what is in the environment: `TYPESAFE_API_KEY`
;; calls TypeSafe's own API, `OPENROUTER_API_KEY` calls the same wire through
;; OpenRouter's gateway, and with no key at all it replays ONE recorded decision
;; through the `static` backend — so this runs offline and uncredentialed, with
;; the same output shape. Either key is read from the environment BY NAME and is
;; never printed or logged.
(ns examples.judge
  (:require [koine.env :as env]
            [toolnexus.classifier :as jev]))

;; ---- the state: whatever the host already has. Sent verbatim, never canonicalised. ----
(def ticket
  (str "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second "
       "charge has not been refunded. I am not blocked from working, but I would like the money "
       "back this week."))

(def model "typesafe/jev-1.13")

;; All three question types in ONE call: many questions, one round trip, one
;; state ingest. The questions are INDEPENDENT — one answer is never context
;; for another.
;;
;; The `choice` descriptions are the whole ball game (ADR 0021): `criteria[id]`
;; is the only thing that tells the model what picking "billing" rather than
;; "technical" would MEAN. Options described by their own id are schema-valid,
;; return HTTP 200 — and rank at chance. So every option carries a real
;; sentence, all three use the SAME template, and no arithmetic is pushed onto
;; the model: the host does the counting and hands over the conclusion.
(def questions
  {"wants_money_back"
   (jev/noul-question "Is the customer asking for money to be returned?")

   "department"
   (jev/choice-question
    "Which desk should own this ticket?"
    {"billing"   "own it here when the problem is money that moved: a duplicate charge, a wrong invoice, a refund owed"
     "shipping"  "own it here when the problem is a physical parcel: a late delivery, a package damaged in transit"
     "technical" "own it here when the problem is the product itself: a login that fails, a feature that errors"})

   "urgency"
   (jev/score-question
    "How fast does this ticket need a human?"
    ["the customer is working normally and is waiting on an answer"
     "the customer is inconvenienced and will chase if nobody replies today"
     "the customer is blocked from working right now and every hour costs them"])})

;; One decision recorded off the live backend, so this file runs with no key and
;; no network. `:response` is a parsed body — string keys, exactly the wire.
(def recorded
  {:state     ticket
   :questions questions
   :response
   {"model" "typesafe/jev-1.13-20260917"
    "answers"
    {"wants_money_back" {"type" "noul" "noul" 0.99}
     "department"       {"type"          "choice"
                         "choice"        "billing"
                         "probabilities" {"technical" 0 "shipping" 0 "billing" 1}
                         "confidence"    1}
     "urgency"          {"type"   "score"
                         "score"  0.49
                         "legend" {"0" "the customer is working normally and is waiting on an answer"
                                   "1" "the customer is inconvenienced and will chase if nobody replies today"
                                   "2" "the customer is blocked from working right now and every hour costs them"}
                         "probabilities" {"0" 0.52 "1" 0.48 "2" 0}
                         "confidence"    0.27}}
    "usage" {"input_tokens" 516 "output_tokens" 72 "cost" 0.000021672}}})

(defn -main [& _]
  (let [env?    (fn [n] (not (empty? (str (env/get-env n)))))
        ;; Same wire, two ways in. TypeSafe's own API is a first-party key and no
        ;; gateway in the path; OpenRouter is a gateway you may already hold a key
        ;; for, and the only one of the two that reports `usage.cost`. They are
        ;; equivalent in latency — neither is the "fast" one.
        backend (cond (env? "TYPESAFE_API_KEY")   :typesafe
                      (env? "OPENROUTER_API_KEY") :openrouter
                      :else                       :static)
        on-metric (fn [ev]
                    (when (= jev/metric-warning (:event ev))
                      (println "warning:" (:warning ev))))
        judge   (case backend
                  :typesafe
                  (jev/create-classifier
                   {:base-url    "https://api.typesafe.ai/v1" ; the library default; spelled out so it is visible
                    :model       "jev-latest"
                    :api-key-env "TYPESAFE_API_KEY" ; the NAME of an env var, never the value
                    :on-metric   on-metric})

                  :openrouter
                  (jev/create-classifier
                   {:base-url    "https://openrouter.ai/api/v1" ; a gateway that serves the same System One wire
                    :model       model
                    :api-key-env "OPENROUTER_API_KEY"
                    :on-metric   on-metric})

                  (jev/create-classifier {:style "static" :model model :decisions [recorded]}))]

    (println (case backend
               :typesafe   "backend: systemone via api.typesafe.ai (live)"
               :openrouter "backend: systemone via openrouter.ai (live)"
               "backend: static (recorded — set TYPESAFE_API_KEY or OPENROUTER_API_KEY to go live)"))

    (let [d     (jev/evaluate judge ticket questions)
          want  (jev/noul d "wants_money_back")
          dept  (jev/choice d "department")
          urg   (jev/score d "urgency")
          levels (jev/levels urg)
          ;; a score is never negative, so +0.5 then truncate IS round-half-up,
          ;; with no java.Math interop to break on the cljgo host.
          level  (long (+ 0.5 (:score urg)))]

      (println (str "\nmodel answering: " (:model d)))
      (println (str "wants_money_back: " (:noul want)
                    "   (a noul carries NO confidence — the number IS the answer)"))
      (println (str "department:       " (:choice dept)
                    "  p=" (pr-str (:probabilities dept))
                    " confidence=" (:confidence dept)))
      (println (str "urgency:          " (:score urg)
                    "  of 0.." (dec (count levels))
                    "  p=" (pr-str (:probabilities urg))))
      (println (str "  level " level ": " (nth levels level)
                    "   (a score MAY fall between levels)"))

      ;; The two health flags, and what they actually mean.
      (println (str "\ncalibrated: " (:calibrated d)
                    "  — these probabilities came from a calibrated backend, so a threshold tuned "
                    "here transfers. An \"llm\"-style backend reports false and your thresholds do "
                    "NOT carry over."))
      (println (str "near-uniform(department): " (:near-uniform dept)
                    "  — max|p - 1/n| <= 0.05, derived from the response. True would mean the model "
                    "had nothing to rank on (usually undescribed options). Advisory, NOT correctness."))

      (println (str "\nusage: " (get-in d [:usage :input-tokens]) " in / "
                    (get-in d [:usage :output-tokens]) " out"
                    ;; Cost is a gateway field. TypeSafe's own API does not return
                    ;; one, and absent is NOT zero — say "not reported" rather than
                    ;; a $0.00 that would read as a free call.
                    (if (contains? (:usage d) :cost)
                      (str " / $" (get-in d [:usage :cost]))
                      " / cost: not reported by this backend"))))
    (println "OK")))
