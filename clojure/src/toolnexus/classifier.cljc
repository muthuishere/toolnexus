;; SPEC.md §8B — `Classifier`, the contract for a JUDGMENT, as `Tool` is the
;; contract for an ACTION.
;;
;; A System One model takes a state plus pre-declared, typed questions and
;; returns calibrated answers with no free text. It has no messages, no tool
;; calling and no streaming, so it NEVER enters the §8 client loop and is never
;; selected as a model for `run` / `ask`.
;;
;; A classifier INTERPRETS; it never AUTHORISES. Schema validity is not
;; correctness — a decision can be confidently wrong, and "cannot hallucinate"
;; means only that the returned value is inside the declared schema. Numeric
;; limits, permission checks and allowlists stay in code. NOTHING HERE IS A
;; SECURITY CONTROL.
;;
;; Everything a caller hands in and everything they get back is PLAIN DATA — a
;; question is a map, a decision is a map. There is no record, no protocol and
;; no deftype: this file must load identically on the JVM and on cljgo, and the
;; whole seam is three shapes in and three shapes out.
;;
;; No reader conditionals. No java.*. The wire is ONE POST, and the retry policy
;; is the §8 one, reused from `toolnexus.client` rather than copied.
(ns toolnexus.classifier
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.http :as khttp]
            [koine.json :as json]
            [koine.time :as ktime]
            [toolnexus.client :as client]))

;; ---------------------------------------------------------------------------
;; constants — pinned across every port
;; ---------------------------------------------------------------------------

(def default-base-url
  "The System One endpoint base. OpenRouter (https://openrouter.ai/api/v1)
  serves this wire today; self-hosted and open-weights implementations speak it
  too."
  "https://api.typesafe.ai/v1")

(def default-model
  "The floating alias. PIN IT (e.g. \"jev-1.13.0\") once thresholds are tuned —
  `:model` on the decision echoes what actually answered, which may be more
  specific."
  "jev-latest")

(def default-api-key-env
  "The NAME of the environment variable holding the credential, never a value."
  "TYPESAFE_API_KEY")

(def default-timeout-ms
  "Bounds ONE request. A classifier has no loop to bound."
  10000)

(def max-choice-options
  "The client-side cap on a choice's named options (§8B)."
  255)

(def min-score-levels 2)
(def max-score-levels 10)

(def near-uniform-tolerance
  "The ABSOLUTE tolerance on max|p - 1/n|, compared INCLUSIVELY (§8B). Pinned
  across every port; `examples/judge/near-uniform.json` pins both sides at
  0.0499 / 0.0501, so no port needs an epsilon."
  0.05)

(def metric-evaluate
  "The `:event` value of the per-evaluation metric. It goes into the SAME §8
  `:on-metric` sink as the client's `\"llm\"` event and is NOT folded into any
  registry, so rendered metrics text is unchanged by this file's existence."
  "classifier.evaluate")

(def metric-warning
  "The `:event` value of the degenerate-criteria warning. Carries `:question`."
  "classifier.warning")

(def styles
  "The four backends (§8B). `:systemone` is the default; `:static` is what CI
  runs — no network, no credential."
  #{"systemone" "llm" "custom" "static"})

;; ---------------------------------------------------------------------------
;; questions — plain data, and the map IS the wire form
;; ---------------------------------------------------------------------------
;;
;; The shell keys are keywords (`:type` / `:instructions` / `:criteria`) because
;; `koine.json` renders a keyword key as its name, so they land as "type",
;; "instructions", "criteria" with no conversion step.
;;
;; The keys INSIDE `:criteria` are STRINGS and must stay strings: an option id
;; is caller-chosen and may be digit-leading ("09_digit_lead"), upper-case
;; ("Zebra_upper") or non-ASCII ("café"), and a score answer's legend is keyed
;; "0", "1", …  Keywordizing any of those mangles them, which is the single
;; trap this port has to get right (tasks.md 4.6).
;;
;; ABSENT AND EMPTY ARE DIFFERENT VALUES. A noul with no `:criteria` key emits
;; no `criteria` at all; one with `{"true" "" "false" ""}` emits
;; `"criteria":{"false":"","true":""}`. Both survive the wire.

(defn noul-question
  "The probability that a statement holds, one number in 0..1. It reports NO
  confidence: the number IS the answer.

  `criteria`, when given, is `{\"true\" <desc> \"false\" <desc>}`. Omit the
  arity rather than passing `nil` or `{}` — the three are different values and
  the wire carries the difference."
  ([instructions] {:type "noul" :instructions instructions})
  ([instructions criteria]
   {:type "noul" :instructions instructions :criteria criteria}))

(defn choice-question
  "One option from a named set, 1..255 options. `criteria` maps an option id to
  WHAT PICKING IT WOULD MEAN.

  THE ENCODING OBLIGATION IS THE CALLER'S (§8B, docs/adr/0021 D1). `criteria[id]`
  is the ONLY thing that differentiates one option from another to the model:
  the instructions describe the question and the state describes the situation,
  and neither says what picking `left` rather than `right` would mean. Passing
  the id itself, an empty string, or one value repeated is schema-valid, passes
  validation, returns HTTP 200 and a well-formed distribution — and ranks at
  chance (measured: 17 apples described by consequence, 0/1/0 described by id,
  against a shuffle control's 1/0/1)."
  [instructions criteria]
  {:type "choice" :instructions instructions :criteria criteria})

(defn score-question
  "A rating against an ORDERED rubric of 2..10 levels. `criteria` is a VECTOR
  and its order IS the level numbering, so it is never sorted — a \"sort
  everything\" canonicaliser silently renumbers the rubric."
  [instructions criteria]
  {:type "score" :instructions instructions :criteria (vec criteria)})

(defn choice-over
  "A `choice` over any (name, description) pairs — a Tool, a skill, an agent, an
  A2A card skill. The description must say what picking that option would MEAN;
  see the encoding obligation on `choice-question`."
  [instructions items]
  (choice-question instructions (reduce (fn [m e] (assoc m (str (key e)) (val e)))
                                        {} items)))

(defn- question-error [key msg data]
  (ex-info (str "classifier: question \"" key "\": " msg)
           (assoc data :question key)))

(defn- validate-question
  "The client-side limits, enforced BEFORE the request so a caller finds out
  faster and more legibly than from the backend's own
  `400 \"Too many choices. Must have at most 255 choices.\"` (which is still
  surfaced intact if it arrives). The message names the offending question KEY
  and the limit."
  [key q]
  (when-not (map? q)
    (throw (question-error key "is not a question map" {})))
  (let [t (:type q)]
    (cond
      (= t "noul") nil

      (= t "choice")
      (let [n (count (:criteria q))]
        (when (or (< n 1) (> n max-choice-options))
          (throw (question-error key (str "a choice needs 1.." max-choice-options
                                          " options, got " n)
                                 {:limit :choice-options :count n}))))

      (= t "score")
      (let [n (count (:criteria q))]
        (when-not (sequential? (:criteria q))
          (throw (question-error key "a score's criteria must be an ORDERED sequence" {})))
        (when (or (< n min-score-levels) (> n max-score-levels))
          (throw (question-error key (str "a score needs " min-score-levels ".."
                                          max-score-levels " ordered levels, got " n)
                                 {:limit :score-levels :count n}))))

      :else
      (throw (question-error key (str "unknown type " (pr-str t)
                                      " — expected \"noul\", \"choice\" or \"score\"")
                             {:type t})))))

;; ---------------------------------------------------------------------------
;; the canonical request
;; ---------------------------------------------------------------------------

(defn canonical-request
  "The bytes the byte-identity claim covers: `model` + `questions`, keys sorted
  recursively in ASCII (code-point) order, arrays NEVER reordered, compact
  separators, and `< > & ' \"` plus non-ASCII transmitted RAW.

  `koine.json/write-str` already is that encoder — it owns key order, escaping
  and number formatting precisely so the two hosts cannot drift — so there is no
  second canonicaliser here to keep in agreement with it.

  `state` is DELIBERATELY NOT HERE. It is transmitted verbatim as the host
  supplied it and is outside the claim, because numbers do not canonicalise
  across languages (`-0.0` renders four ways across our own seven runtimes). Do
  not re-widen this: a caller who needs their state pinned canonicalises it
  themselves before handing it over."
  [model questions]
  (json/write-str {:model model :questions questions}))

;; ---------------------------------------------------------------------------
;; nearUniform — derived, advisory, pinned to one tolerance
;; ---------------------------------------------------------------------------

(defn- abs* [x] (if (neg? x) (- x) x))

(defn near-uniform?
  "Whether a choice answer's probability map is indistinguishable from flat:

      nearUniform  <=>  max over i of |p_i - 1/n|  <=  0.05

  `n` is the number of ENTRIES IN THE MAP and the values are taken AS RETURNED
  — not renormalised, not sorted, not rounded; an offered option absent from the
  map counts as 0 by not being an entry. The tolerance is ABSOLUTE (a relative
  band collapses below the wire's two-decimal rounding on a 255-option roster)
  and the comparison is INCLUSIVE. `n = 1` is trivially uniform; an EMPTY map
  has no distribution at all and is false.

  DERIVED ON DECODE, never read from the wire: no wire change, no request
  change, no fixture change.

  ADVISORY, NOT A CORRECTNESS SIGNAL. It detects an encoding that gave the model
  nothing to rank on — the one encoding health check available with no ground
  truth. It cannot tell a good encoding from a subtly wrong one, because a
  wrong-but-answerable question still reads as answerable. `:calibrated` carries
  the same caveat."
  [probabilities]
  (let [n (count probabilities)]
    (cond
      (zero? n) false
      (= 1 n)   true
      :else     (let [target (/ 1.0 n)]
                  (every? (fn [p] (<= (abs* (- (double p) target)) near-uniform-tolerance))
                          (vals probabilities))))))

;; ---------------------------------------------------------------------------
;; degenerate criteria — detect and report, NEVER repair
;; ---------------------------------------------------------------------------

(defn degenerate-criteria
  "The §8B predicate. Returns a human reason string when a `choice`'s criteria
  are degenerate, nil otherwise. Degenerate <=> ANY of:

    1. every value is empty (empty string or absent), or
    2. every value equals its own key, or
    3. every value is identical to every other value (n >= 2).

  A single-option choice (n = 1) is NEVER reported — there is nothing to
  differentiate. Note the ordering: with n = 1 rules 1 and 2 can still hold and
  rule 3 is vacuous, so the n < 2 gate comes first for all three."
  [criteria]
  (when (>= (count criteria) 2)
    (let [pairs (map (fn [e] [(str (key e)) (if (nil? (val e)) "" (str (val e)))]) criteria)
          vs    (map second pairs)]
      (cond
        (every? (fn [v] (= "" v)) vs)                    "every description is empty"
        (every? (fn [p] (= (second p) (first p))) pairs) "every description is just its own option id"
        (apply = vs)                                     "every description is identical"))))

;; ---------------------------------------------------------------------------
;; answers and the decision
;; ---------------------------------------------------------------------------

(defn- num* [x] (when (number? x) x))

(defn- parse-answer
  "One answer, discriminated by the wire's `type`. The set is CLOSED: an unknown
  type is an error, never a guess (ADR 0020 — never repair a bad response)."
  [key a]
  (let [t (get a "type")]
    (cond
      (= t "noul")
      {:type "noul" :noul (get a "noul")}

      (= t "choice")
      (let [probs (or (get a "probabilities") {})]
        {:type          "choice"
         :choice        (get a "choice")
         :probabilities probs
         :confidence    (get a "confidence")
         ;; DERIVED here and nowhere else — the wire never carries it.
         :near-uniform  (near-uniform? probs)})

      (= t "score")
      {:type          "score"
       :score         (get a "score")
       :legend        (or (get a "legend") {})
       :probabilities (or (get a "probabilities") {})
       :confidence    (get a "confidence")}

      :else
      (throw (ex-info (str "classifier: answer \"" key "\": unknown type " (pr-str t))
                      {:question key :type t})))))

(defn parse-decision
  "A recorded or live response body (already parsed JSON with STRING keys) as a
  Decision:

      {:model      what actually answered
       :answers    {caller-key -> answer}
       :usage      {:input-tokens :output-tokens :cost}
       :calibrated true|false}

  `calibrated` ABSENT means true: the systemone wire reports calibration by
  being itself, and a backend that is not calibrated says so explicitly. A
  THRESHOLD TUNED AGAINST ONE BACKEND DOES NOT TRANSFER TO ANOTHER."
  [body]
  (let [usage (or (get body "usage") {})]
    {:model      (get body "model")
     :calibrated (let [c (get body "calibrated")] (if (nil? c) true (boolean c)))
     :usage      (cond-> {:input-tokens  (num* (get usage "input_tokens"))
                          :output-tokens (num* (get usage "output_tokens"))}
                   (contains? usage "cost") (assoc :cost (get usage "cost")))
     :answers    (reduce (fn [m e] (assoc m (str (key e)) (parse-answer (str (key e)) (val e))))
                         {}
                         (or (get body "answers") {}))}))

(defn- answer-of
  "A typed read. A wrong-type or absent key is an ERROR, so a caller never
  destructures a shape that is not there."
  [decision key want]
  (let [a (get-in decision [:answers key])]
    (cond
      (nil? a)            (throw (ex-info (str "classifier: no answer \"" key
                                               "\" in this decision")
                                          {:question key :want want}))
      (not= want (:type a)) (throw (ex-info (str "classifier: answer \"" key "\" is a "
                                                 (:type a) " answer, not " want)
                                            {:question key :want want :got (:type a)}))
      :else a)))

(defn noul   "The noul answer at `key`, or an error." [decision key] (answer-of decision key "noul"))
(defn choice "The choice answer at `key`, or an error." [decision key] (answer-of decision key "choice"))
(defn score  "The score answer at `key`, or an error." [decision key] (answer-of decision key "score"))

(defn levels
  "A score answer's legend in LEVEL order, which the map itself loses. Sorted
  numerically-by-length-then-lexicographically, because \"2\" < \"10\" as levels
  and \"10\" < \"2\" as strings."
  [answer]
  (let [ks (sort (fn [a b] (if (= (count a) (count b))
                             (compare a b)
                             (compare (count a) (count b))))
                 (map str (keys (:legend answer))))]
    (mapv (fn [k] (get (:legend answer) k)) ks)))

;; ---------------------------------------------------------------------------
;; the classifier
;; ---------------------------------------------------------------------------

(defn- static-key
  "A recorded decision is identified by the canonical request AND the state.
  Several recorded entries legitimately share one questions payload and differ
  only in state — the three guard bands of `examples/judge/decisions.json` do
  exactly that, and every one of them carries the same canonicalSha256 — so a
  corpus keyed on the canonical request alone cannot tell them apart."
  [model state questions]
  (str (canonical-request model questions) "\u0000" (json/write-str state)))

(defn create-classifier
  "Build a classifier. Options mirror §8 `create-client` field-for-field
  wherever a field makes sense, so a host that has configured one has configured
  the other:

    :style          \"systemone\" | \"llm\" | \"custom\" | \"static\"
                    (default \"systemone\")
    :base-url       default \"https://api.typesafe.ai/v1\"
    :model          default \"jev-latest\"
    :api-key-env    the NAME of an env var, never a value — read at call time
                    and never logged. Default \"TYPESAFE_API_KEY\". §8's
                    `:api-key` takes a value; this option deliberately does not.
    :headers        extra request headers; values expand ${ENV_VAR} from the
                    environment AT CALL TIME and are NEVER logged, identically
                    to remote-MCP headers (§2)
    :timeout-ms     per REQUEST, not per run — default 10000
    :http-client    the §8 injectable transport, `(fn [url headers body])`;
                    scope is the classifier path only
    :retries        transient-failure budget (default 2); retries on
                    `408`/`429`/`500`/`502`/`503`/`504`/`529` + network. Widen
                    the status set with `:retryable-statuses`.
    :retryable-statuses
                    extra HTTP statuses to treat as retryable, ADDED to the
                    default set (`429`/`500`/`502`/`503`/`504`/`529`, plus `408`
                    here). It can only widen: a host cannot remove `429` and
                    lose `Retry-After` handling with it. This sets the DEFAULT
                    classification; `:on-error` still runs per attempt and has
                    the final say, so `:on-error` returning `:fail` overrides a
                    status listed here. Example: a Cloudflare-fronted origin
                    that answers `520`–`527`.
    :on-error       the §8 `ErrorInfo -> :retry | :fail` classifier, REUSED
                    verbatim from `toolnexus.client` along with the Retry-After
                    delay-seconds rule. There is no second retry policy here and
                    no `:suspend` tier.
    :request-params extra top-level body keys, shallow-merged AFTER the
                    classifier builds its own; a param WINS on collision
    :body-transform runs LAST on the assembled body; its return value is sent
    :on-metric      the SAME §8 sink — `\"classifier.evaluate\"` events and the
                    degenerate-criteria `\"classifier.warning\"`
    :client         `:style \"llm\"` only — the §8 client to emulate over
    :evaluate       `:style \"custom\"` only — `(fn [state questions] decision)`;
                    every wire option is ignored
    :decisions      `:style \"static\"` only — a vector of
                    `{:state … :questions … :response <parsed body>}`

  A style whose required option is missing, and an unknown style, are rejected
  HERE rather than at the first call."
  [opts]
  (let [style (str (or (:style opts) "systemone"))
        opts  (assoc opts
                     :style       style
                     :base-url    (or (:base-url opts) default-base-url)
                     :model       (or (:model opts) default-model)
                     :api-key-env (or (:api-key-env opts) default-api-key-env)
                     :timeout-ms  (or (:timeout-ms opts) default-timeout-ms)
                     :retries     (or (:retries opts) 2))]
    (when-not (contains? styles style)
      (throw (ex-info (str "classifier: unknown style " (pr-str style)
                           " — expected one of " (pr-str (vec (sort styles))))
                      {:style style})))
    (when (and (= "llm" style) (nil? (:client opts)))
      (throw (ex-info "classifier: style \"llm\" requires :client" {:style style})))
    (when (and (= "custom" style) (nil? (:evaluate opts)))
      (throw (ex-info "classifier: style \"custom\" requires :evaluate" {:style style})))
    (cond-> opts
      (= "static" style)
      (assoc :corpus (reduce (fn [m rec]
                               (assoc m (static-key (:model opts) (:state rec) (:questions rec))
                                      (:response rec)))
                             {}
                             (:decisions opts)))
      ;; The once-per-question-key set for the degenerate warning, so a per-turn
      ;; judge does not flood the sink. An atom, not a rebound map: `evaluate`
      ;; takes the classifier by value and a caller holds one instance.
      true (assoc :warned (atom #{})))))

(defn- emit! [c ev]
  (when-let [f (:on-metric c)] (f ev))
  nil)

(defn- report-degenerate!
  "ONE warning per degenerate question key per classifier, NAMING THE KEY, and
  nothing else changes. The request goes out BYTE-UNCHANGED: repairing would
  invent option descriptions the caller did not write, and the library has no
  way to know what the options mean (ADR 0020/0021 D2). The warning is the
  entire observable effect."
  [c questions]
  (doseq [[key q] (sort-by first (map (fn [e] [(str (key e)) (val e)]) questions))]
    (do
      (when (= "choice" (:type q))
        (when-let [reason (degenerate-criteria (:criteria q))]
          (when-not (contains? @(:warned c) key)
            (swap! (:warned c) conj key)
            ;; `:warning`, never `:error` — advisory, not a failure.
            (emit! c {:event    metric-warning
                      :question key
                      :warning  (str "classifier: question \"" key
                                     "\" has degenerate criteria (" reason
                                     ") — every option reads the same to the model and the "
                                     "answer ranks at chance; describe what picking each "
                                     "option would MEAN (SPEC.md §8B)")})))))))

;; ---------------------------------------------------------------------------
;; backends
;; ---------------------------------------------------------------------------

(def ^:private forbidden-request-params
  "Keys `:request-params` may NOT set: they are what this seam IS. Rewriting
  them is what `:body-transform` is for, which sees the assembled body and can
  do it deliberately rather than by collision."
  #{:questions "questions"})

(defn- request-body
  "The request: the canonical model + questions, plus `state` VERBATIM as the
  host supplied it, then the §8 Gap 1 pipeline in §8 order —

     base body -> :request-params merge -> :body-transform -> marshal

  Marshalled through the SAME encoder as `canonical-request`, so the `model` +
  `questions` region of the body is byte-for-byte the region the claim covers."
  [c state questions]
  (let [base   {:model (:model c) :questions questions :state state}
        params (apply dissoc (:request-params c) forbidden-request-params)
        merged (if (seq params) (merge base params) base)]
    (json/write-str (if-let [f (:body-transform c)] (f merged) merged))))

(defn- request-headers
  "Built at CALL TIME and never logged, returned or put in an error. The
  credential is read from the NAMED environment variable and `${ENV_VAR}` in a
  header value expands here — both are use-only values that must not reach an
  agent's context, a log line or a metric."
  [c]
  (let [credential (env/get-env (:api-key-env c))]
    (cond-> (reduce (fn [m e]
                      (assoc m (str/lower-case (str (if (keyword? (key e)) (name (key e)) (key e))))
                             (env/expand (str (val e)))))
                    {"content-type" "application/json"}
                    (:headers c))
      (seq (str credential)) (assoc "authorization" (str "Bearer " credential)))))

(defn- safe-cause
  "A backend's reported cause, surfaced INTACT so a caller can tell a limit error
  from a transport fault — EXCEPT on an authentication status. A 401/403 body
  routinely reflects the credential or the header that was sent (measured: a
  gateway echoing the whole Authorization value), so it never reaches a log, a
  metric, an error or a return value."
  [status body]
  (if (or (= 401 status) (= 403 status))
    ""
    (let [s (str/trim (str body))]
      (cond
        (str/blank? s) ""
        (> (count s) 200) (str ": " (subs s 0 200) "…")
        :else (str ": " s)))))

(defn- post!
  "The one POST, with the §8 retry loop around it — `toolnexus.client/classify`
  and `toolnexus.client/retry-after-ms` are called, not reimplemented, so there
  is exactly one retry policy in this library."
  [c body]
  (let [url     (str (str/replace (str (:base-url c)) #"/+$" "") "/systemone")
        headers (request-headers c)
        budget  (or (:retries c) 2)]
    (loop [attempt 0]
      (let [res     (if-let [f (:http-client c)]
                      (f url headers body)
                      (khttp/request {:method :post :url url :headers headers
                                      :body body :timeout-ms (:timeout-ms c)}))
            failed? (khttp/failed? res)
            status  (:status res)
            ok?     (and (not failed?) status (<= 200 status) (< status 300))]
        (if ok?
          (:body res)
          (let [info    {:error      (if failed? (:error res) status)
                         :status     status
                         :attempt    attempt
                         :retryable? (boolean (or failed?
                                                  (= 408 status)
                                                  (client/retryable-status?
                                                   status (:retryable-statuses c))))}
                verdict (client/classify c info)]
            (if (and (= :retry verdict) (< attempt budget))
              (do (ktime/sleep! (or (client/retry-after-ms res)
                                    (* 500 (bit-shift-left 1 attempt))))
                  (recur (inc attempt)))
              (throw (if failed?
                       (ex-info (str "classifier: POST " url " transport " (name (:error res)))
                                {:url url :error (:error res)})
                       ;; The status and the ENDPOINT, and — on an auth status —
                       ;; nothing else.
                       (ex-info (str "classifier: POST " url " HTTP " status
                                     (safe-cause status (:body res)))
                                {:url url :status status}))))))))))

(defn- evaluate-static
  "Recorded decisions. THIS IS WHAT CI RUNS: no network, no credential. It is not
  a convenience — the live backend is non-deterministic, so it is the only
  backend a test may assert a number against."
  [c state questions]
  (let [k (static-key (:model c) state questions)]
    (if (contains? (:corpus c) k)
      (parse-decision (get (:corpus c) k))
      ;; An unrecorded state is an ERROR, never a neighbouring band.
      (throw (ex-info "classifier: static: no recorded decision for this request+state"
                      {:style "static"})))))

(defn- last-brace
  "The index of the last `}` in `s`, or nil. Hand-scanned rather than
  `clojure.string/last-index-of`, which this port has never proven on cljgo —
  `index-of` is the only one in use elsewhere in the tree."
  [s]
  (loop [i (dec (count s))]
    (cond (neg? i)            nil
          (= \} (nth s i))    i
          :else               (recur (dec i)))))

(defn- evaluate-llm
  "The three question types rendered as ONE structured-output call on any §8
  client — the vendor-neutral fallback, so a host with no System One credential
  runs the same questions on a cheap chat model. `:calibrated` is FALSE: the
  numbers are the model's self-report, not token probabilities."
  [c state questions]
  (let [prompt (str "Answer every question about the state below. Questions are INDEPENDENT: "
                    "one answer is never context for another.\n\n"
                    "STATE:\n" (json/write-str state) "\n\n"
                    "QUESTIONS:\n" (json/write-str questions) "\n\n"
                    "Reply with JSON only, no prose and no code fence, shaped exactly:\n"
                    "{\"answers\":{\"<key>\":{\"type\":\"noul\",\"noul\":0.0}}}\n"
                    "A \"noul\" answer is {\"type\":\"noul\",\"noul\":<0..1>}. A \"choice\" answer is "
                    "{\"type\":\"choice\",\"choice\":\"<one offered option id>\","
                    "\"probabilities\":{\"<every offered option id>\":<0..1>},\"confidence\":<0..1>}. "
                    "A \"score\" answer is {\"type\":\"score\",\"score\":<a number within the rubric "
                    "bounds, fractional allowed>,\"legend\":{\"0\":\"<level 0>\"},"
                    "\"probabilities\":{\"0\":<0..1>},\"confidence\":<0..1>}.")
        r      (client/run (:client c) prompt {})
        text   (str (:text r))
        start  (str/index-of text "{")
        end    (last-brace text)]
    (when-not (and start end (> end start))
      (throw (ex-info "classifier: llm: no JSON object in the reply" {:style "llm"})))
    ;; NOT repaired — an unparseable answer is no answer (ADR 0020).
    (let [d (parse-decision (json/read-str (subs text start (inc end)) {:key-fn str}))]
      (assoc d
             ;; The model reports no calibration and none is derived here.
             :calibrated false
             :model (or (:model d) (:model c))
             :usage {:input-tokens  (get-in r [:usage :prompt-tokens])
                     :output-tokens (get-in r [:usage :completion-tokens])}))))

;; ---------------------------------------------------------------------------
;; evaluate — the whole contract, one verb
;; ---------------------------------------------------------------------------

(defn evaluate
  "A state plus typed questions in, a decision out.

  `state` is a string, a map, or a vector — whatever the host already has.
  `questions` maps CALLER-CHOSEN keys to question maps. The keys are ADDRESSING,
  NOT CONTENT: they are never transmitted to the model, so a key MAY be a tool,
  skill or agent name verbatim, and two evaluations differing only in their keys
  send identical content.

  Questions are INDEPENDENT — one answer is never context for another. A backend
  that cannot guarantee that reports `:calibrated false`, which carries both
  caveats."
  [c state questions]
  (let [t0 (ktime/now-ms)
        fail! (fn [e]
                (emit! c {:event metric-evaluate :model (:model c) :status "error"
                          :ms (- (ktime/now-ms) t0) :error (ex-message e)})
                (throw e))]
    (when (empty? questions)
      (fail! (ex-info "classifier: no questions to evaluate" {})))
    ;; Limits are enforced CLIENT-SIDE, before the request. Keys are walked in
    ;; sorted order so the same malformed set always names the same key first.
    (doseq [[key q] (sort-by first (map (fn [e] [(str (key e)) (val e)]) questions))]
      (try (validate-question key q)
           (catch Throwable e (fail! e))))
    ;; Detection, never repair: the request goes out BYTE-UNCHANGED.
    (report-degenerate! c questions)
    (let [d (try
              (let [style (:style c)]
                (cond
                  (= "custom" style) ((:evaluate c) state questions)
                  (= "static" style) (evaluate-static c state questions)
                  (= "llm" style)    (evaluate-llm c state questions)
                  :else (parse-decision (json/read-str (post! c (request-body c state questions))
                                                       {:key-fn str}))))
              (catch Throwable e (fail! e)))]
      (emit! c {:event             metric-evaluate
                :model             (:model d)
                :status            "ok"
                :ms                (- (ktime/now-ms) t0)
                :prompt_tokens     (get-in d [:usage :input-tokens])
                :completion_tokens (get-in d [:usage :output-tokens])})
      d)))
