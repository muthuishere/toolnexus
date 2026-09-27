(ns judge.core
  "Judge adapters: plain-data questions, bands, ask, gate. Over toolnexus.classifier."
  (:require [toolnexus.classifier :as tc]))

;; ---- questions: plain maps, ordered list, named --------------------------
(defn noul
  ([nm instructions] {:name (name nm) :type "noul" :instructions instructions})
  ([nm instructions criteria] (assoc (noul nm instructions) :criteria criteria)))
(defn choice [nm instructions options] {:name (name nm) :type "choice" :instructions instructions :criteria options})
(defn score  [nm instructions levels]  {:name (name nm) :type "score"  :instructions instructions :criteria (vec levels)})

(defn questions
  "Ordered list -> the §8B {name -> question} map. Duplicate names throw."
  [qs]
  (reduce (fn [m {:keys [name] :as q}]
            (when (contains? m name)
              (throw (ex-info (str "duplicate question name \"" name "\"") {:name name})))
            (assoc m name (dissoc q :name)))
          {} qs))

;; ---- state ---------------------------------------------------------------
(defn state
  "A map is the state. Sugar: (state context message) / (state context message extra)."
  ([m] m)
  ([context message] {"context" context "message" message})
  ([context message extra] (merge (state context message) extra)))

;; ---- bands ---------------------------------------------------------------
(def default-bands {:low 0.30 :high 0.70})

(defn band
  "yes | no | uncertain for one parsed answer. Cut points exclusive on the sure side."
  [{:keys [low high]} a]
  (case (:type a)
    "noul"   (let [p (:noul a)] (cond (< p low) "no" (> p high) "yes" :else "uncertain"))
    "choice" (if (and (not (:near-uniform a)) (> (:confidence a) high)) "yes" "uncertain")
    "score"  (if (> (:confidence a) high) "yes" "uncertain")))

;; ---- ask -----------------------------------------------------------------
(defn ask
  "Answers by name, each carrying :band."
  ([c st qs] (ask c st qs default-bands))
  ([c st qs bands]
   (let [b (merge default-bands bands)
         d (tc/evaluate c st (questions qs))]
     (update-vals (:answers d) #(assoc % :band (band b %))))))

;; ---- gate ----------------------------------------------------------------
(defn- check
  "[fired? escalate-reason]"
  [answers {:keys [question below at-least is]}]
  (let [a (get answers (name question))]
    (cond
      (nil? a)                    [false "missing answer"]
      (= "uncertain" (:band a))   [false (str (:type a) " answer is uncertain")]
      (some? is)                  (if (= "choice" (:type a)) [(= is (:choice a)) nil] [false "is-rule on non-choice"])
      :else (let [v (case (:type a) "noul" (:noul a) "score" (:score a) nil)]
              (cond (nil? v)   [false "numeric rule on choice"]
                    below      [(< v below) nil]
                    at-least   [(>= v at-least) nil]
                    :else      [false "rule has no condition"])))))

(defn apply-rules
  "Pure half of gate. First-match in order; an escalation on rule i wins."
  [answers rules]
  (or (some (fn [[i r]]
              (let [[fired reason] (check answers r)]
                (cond
                  reason {:action "needs_input" :target "" :escalated true :answers answers
                          :request {:id (str "gate:" i ":" (name (:question r)))
                                    :kind "input"
                                    :prompt (str "Classifier is unsure about \"" (name (:question r)) "\" (" reason ").")
                                    :data {:question (name (:question r)) :reason reason :answers answers}}}
                  fired  {:action (:action r) :target (or (:target r) "") :escalated false :answers answers})))
            (map-indexed vector rules))
      {:action "" :target "" :escalated false :answers answers}))

(defn gate
  ([c st qs rules] (gate c st qs rules default-bands))
  ([c st qs rules bands] (apply-rules (ask c st qs bands) rules)))
