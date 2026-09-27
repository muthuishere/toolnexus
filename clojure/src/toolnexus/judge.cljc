;; SPEC.md §8B "Simple judgments" — `ask` / `gate` over any `Classifier`
;; (change add-judge-adapters, ADR 0035).
;;
;; A thin layer; the wire is unchanged. Builders produce exactly the §8B
;; `evaluate(state, questions)` inputs, so a request built here is byte-identical
;; to one built from hand-written maps. Everything is PLAIN DATA: a question is a
;; map with a `:name`, an answer is the classifier's answer map plus `:band`
;; (noul) or `:sure` (choice / score), an outcome is a map.
;;
;; A gate NEVER authorises. It only declines to decide: an uncertain, unsure or
;; missing answer becomes a §10 `input` Request for a human.
;;
;; No reader conditionals. No java.*.
(ns toolnexus.judge
  (:require [toolnexus.classifier :as jev]))

;; ---------------------------------------------------------------------------
;; questions — an ordered list of named questions
;; ---------------------------------------------------------------------------

(defn- qname [nm] (if (keyword? nm) (name nm) (str nm)))

(defn noul
  "A named noul question. `criteria`, when given, is `{\"true\" … \"false\" …}`.
  Instructions should NAME the state field they judge (ADR 0035 D9)."
  ([nm instructions] (assoc (jev/noul-question instructions) :name (qname nm)))
  ([nm instructions criteria] (assoc (jev/noul-question instructions criteria) :name (qname nm))))

(defn choice
  "A named choice question; `options` maps an option id to what picking it MEANS."
  [nm instructions options]
  (assoc (jev/choice-over instructions options) :name (qname nm)))

(defn score
  "A named score question over an ORDERED vector of levels."
  [nm instructions levels]
  (assoc (jev/score-question instructions levels) :name (qname nm)))

(defn wire-questions
  "The public question -> wire conversion: an ordered list of named questions
  as the §8B `{name -> question}` map. A duplicate name throws naming it, before
  any request."
  [qs]
  (reduce (fn [m q]
            (let [n (:name q)]
              (when (contains? m n)
                (throw (ex-info (str "duplicate question name \"" n "\"") {:name n})))
              (assoc m n (dissoc q :name))))
          {} qs))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defn state
  "State(role, data): the data's fields at the top level plus `role`. A value
  that is not a map goes under `data`. The wire has no role field — the state
  carries it (ADR 0035 D7); never copy the role into question instructions."
  [role data]
  (if (map? data)
    (assoc data (if (some keyword? (keys data)) :role "role") role)
    {"role" role "data" data}))

(defn context
  "Sugar: `{context, message}` plus any `extra` fields."
  ([ctx message] {"context" ctx "message" message})
  ([ctx message extra] (merge (context ctx message) extra)))

;; ---------------------------------------------------------------------------
;; bands
;; ---------------------------------------------------------------------------

(def default-bands
  "Cut-points, exclusive on the confident side: p < low -> no, p > high -> yes,
  else uncertain."
  {:low 0.30 :high 0.70})

(defn- read-answer [{:keys [low high]} a]
  (case (:type a)
    "noul"   (let [p (:noul a)]
               (assoc a :band (cond (< p low) "no" (> p high) "yes" :else "uncertain")))
    "choice" (assoc a :sure (boolean (and (not (:near-uniform a)) (> (:confidence a) high))))
    "score"  (assoc a :sure (boolean (> (:confidence a) high)))
    a))

(defn value
  "The one number of an answer: noul probability, score value, choice confidence."
  [a]
  (case (:type a) "noul" (:noul a) "score" (:score a) "choice" (:confidence a) nil))

(defn picked
  "The picked option of a choice answer (nil otherwise)."
  [a]
  (when (= "choice" (:type a)) (:choice a)))

;; ---------------------------------------------------------------------------
;; ask
;; ---------------------------------------------------------------------------

(defn ask
  "Answers by name. A noul answer carries `:band` (yes | no | uncertain); a
  choice or score answer carries `:sure`. `bands` merges over the default."
  ([c st qs] (ask c st qs nil))
  ([c st qs bands]
   (let [b (merge default-bands bands)
         d (jev/evaluate c st (wire-questions qs))]
     (reduce-kv (fn [m k a] (assoc m k (read-answer b a))) {} (:answers d)))))

;; ---------------------------------------------------------------------------
;; gate / policy
;; ---------------------------------------------------------------------------

(defn- unsure? [a]
  (or (= "uncertain" (:band a)) (false? (:sure a))))

(defn- check
  "[fired? escalate-reason] for one rule."
  [answers {:keys [question below at-least is]}]
  (let [a (get answers (qname question))]
    (cond
      (nil? a)    [false (str "missing answer \"" (qname question) "\"")]
      (unsure? a) [false (str (:type a) " answer is uncertain")]
      (some? is)  (if (= "choice" (:type a))
                    [(= (qname is) (:choice a)) nil]
                    [false "is-rule on a non-choice answer"])
      :else (let [v (case (:type a) "noul" (:noul a) "score" (:score a) nil)]
              (cond (nil? v)         [false "numeric rule on a choice answer"]
                    (some? below)    [(< v below) nil]
                    (some? at-least) [(>= v at-least) nil]
                    :else            [false "rule has no condition"])))))

(defn- escalate [answers id question reason]
  {:action "needs_input" :target "" :escalated true :answers answers
   :request {:id     id
             :kind   "input"
             :prompt (if (= "" question)
                       (str "Classifier gate: " reason ".")
                       (str "Classifier is unsure about \"" question "\" (" reason ")."))
             :data   {:question question :reason reason :answers answers}}})

(defn- run-rules
  "First match in order; an escalation on rule i wins over later rules unless
  `skip-uncertain` (then an UNCERTAIN rule is skipped; a missing answer still
  escalates). Returns nil when no rule fired."
  [answers rules skip-uncertain]
  (some (fn [[i r]]
          (let [[fired reason] (check answers r)
                a (get answers (qname (:question r)))]
            (cond
              (and reason skip-uncertain a (unsure? a)) nil
              reason (escalate answers (str "gate:" i ":" (qname (:question r)))
                               (qname (:question r)) reason)
              fired  {:action (:action r) :target (or (:target r) "")
                      :escalated false :answers answers})))
        (map-indexed vector rules)))

(defn apply-rules
  "The pure half of `gate`: rules over answers `ask` returned. No rule fired ->
  action \"\" (not escalated)."
  [answers rules]
  (or (run-rules answers rules false)
      {:action "" :target "" :escalated false :answers answers}))

(defn gate
  "Rules `{:question :below|:at-least|:is :action :target}`, first match wins.
  Returns `{:action :target :escalated :request :answers}`."
  ([c st qs rules] (gate c st qs rules nil))
  ([c st qs rules bands] (apply-rules (ask c st qs bands) rules)))

(defn decide
  "Policy `{:rules :default :bands :skip-uncertain}`: the gate's rules, then the
  declared fall-through. A non-empty `:default` is the no-rule-fired action; an
  empty one escalates with reason \"no rule fired\"."
  [c st qs policy]
  (let [answers (ask c st qs (:bands policy))]
    (or (run-rules answers (:rules policy) (:skip-uncertain policy))
        (if (seq (:default policy))
          {:action (:default policy) :target "" :escalated false :answers answers}
          (escalate answers "gate:default" "" "no rule fired")))))

;; ---------------------------------------------------------------------------
;; tape — record live decisions by call name, replay them offline
;; ---------------------------------------------------------------------------

(defn tape
  "An empty tape: an atom of `{call-name decision}`."
  ([] (atom {}))
  ([recorded] (atom recorded)))

(defn recording
  "A classifier that evaluates through `live` and records each decision under
  `call-name`."
  [t call-name live]
  (jev/create-classifier
   {:style "custom"
    :evaluate (fn [st qs]
                (let [d (jev/evaluate live st qs)]
                  (swap! t assoc (qname call-name) d)
                  d))}))

(defn replaying
  "A classifier that replays the decision recorded under `call-name`, with no
  network. A miss throws naming the key and sends nothing."
  [t call-name]
  (jev/create-classifier
   {:style "custom"
    :evaluate (fn [_ _]
                (let [k (qname call-name)]
                  (or (get @t k)
                      (throw (ex-info (str "tape: no recorded decision for call \"" k "\"")
                                      {:call k})))))}))
