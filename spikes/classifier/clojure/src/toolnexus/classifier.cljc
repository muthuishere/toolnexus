;; SPIKE — `Classifier` + `judge`, Clojure port feasibility (ADR 0020).
;;
;; THROWAWAY. Nothing here is imported by clojure/src. One `.cljc`, ZERO reader
;; conditionals, clojure.core + koine only — the same surface ADR 0009 pins for
;; the real port, so whatever this proves it proves for both hosts.
;;
;; The four gate items are functions; `-main` runs them and prints ONE line of
;; koine JSON so the JVM run and the cljgo run can be diffed byte-for-byte.
(ns toolnexus.classifier
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as khttp]
            [koine.time :as time]
            [koine.json :as json]))

;; ---------------------------------------------------------------------------
;; Questions — the union, as data
;; ---------------------------------------------------------------------------
;;
;; THE UNION IS A NON-PROBLEM HERE, and that is the whole Clojure finding.
;; `criteria` is absent / an object / an array on three shapes of the same
;; field. In Go/Java/C# that is a sum type plus a custom (un)marshaller. In
;; Clojure a Question is just a map whose "type" key is the discriminator, and
;; the three shapes are a missing key, a map value and a vector value. No
;; wrapper, no interface, no codec: `json/write-str` already emits a Clojure map
;; as an object and a Clojure vector as an array, so the union cost is the three
;; constructors below and nothing else.
;;
;; Keys are STRINGS, not keywords: the caller's question keys may be a tool or
;; skill name verbatim ("git.push"), and a keyword round-trip would normalise
;; them. The encoder's key->str handles both, so this is a choice, not a limit.

(defn noul
  "Noul question — a 0..1 truth. `criteria` may be absent entirely (it is in the
  fixture), in which case the key is simply not in the map, which is exactly how
  an absent JSON key is spelled."
  ([instructions] {"instructions" instructions "type" "noul"})
  ([instructions true-desc false-desc]
   {"instructions" instructions "type" "noul"
    "criteria" {"true" true-desc "false" false-desc}}))

(defn choice
  "Choice question — `criteria` is an OBJECT {name: description}."
  [instructions criteria]
  {"instructions" instructions "type" "choice" "criteria" criteria})

(defn score
  "Score question — `criteria` is an ARRAY; its ORDER is the level numbering, so
  it must never be sorted. A Clojure vector is the only thing here the encoder
  does not reorder, which makes the invariant structural rather than remembered."
  [instructions criteria]
  {"instructions" instructions "type" "score" "criteria" (vec criteria)})

;; ---------------------------------------------------------------------------
;; Gate 1 — the canonical request
;; ---------------------------------------------------------------------------

(defn request-body
  "The canonical `/v1/systemone` body. Pure. koine.json sorts object keys by code
  point RECURSIVELY and never touches array order, so canonicalisation is the
  encoder's job, not this function's."
  [model state questions]
  (json/write-str {"model" model "state" state "questions" questions}))

(def fixture-questions
  {"is_refund_request" (noul "Is the customer asking for a refund?")
   "department"        (choice "Which department should handle this?"
                               {"billing"   "refunds, charges, payments"
                                "shipping"  "delivery, damage in transit"
                                "technical" "product does not work"})
   "urgency"           (score "How urgent is this?" ["routine" "elevated" "urgent"])})

(def fixture-state "Order 4021 arrived smashed, I want my money back.")

;; ---------------------------------------------------------------------------
;; Gate 2 — parsing a Decision
;; ---------------------------------------------------------------------------
;;
;; The heterogeneous `answers` map is the second half of the union. Again: a map
;; whose values are maps carrying "type". Reading `(get-in d ["answers" "urgency"
;; "score"])` needs no cast and no visitor. The only thing worth writing is
;; `answer-type`, so a caller can branch, and three accessors that FAIL LOUDLY
;; when the shape is not the one asked for — the one safety a static port would
;; get from the compiler.

(defn answer-type [ans] (get ans "type"))

(defn- expect [ans want]
  (when-not (= want (answer-type ans))
    (throw (ex-info (str "answer is a " (answer-type ans) ", not a " want) {})))
  ans)

(defn noul-value   [ans] (get (expect ans "noul")   "noul"))
(defn choice-value [ans] (get (expect ans "choice") "choice"))
(defn score-value  [ans] (get (expect ans "score")  "score"))
(defn probabilities [ans] (get ans "probabilities"))
(defn legend       [ans] (get ans "legend"))
(defn confidence   [ans] (get ans "confidence"))

(defn parse-decision
  "Decode a `/v1/systemone` response body into a Decision. Nothing to do beyond
  the parse: the wire shape IS the value shape."
  [body]
  (let [d (json/read-str body {:key-fn str})]
    {"model"  (get d "model")
     "answers" (get d "answers")
     "usage"  (get d "usage")}))

(defn answer [decision k] (get (get decision "answers") k))

;; ---------------------------------------------------------------------------
;; Backends
;; ---------------------------------------------------------------------------
;;
;; A Classifier is a FUNCTION of [state questions] -> Decision. Not a protocol:
;; a protocol would buy dispatch that `evaluate` does not need, and defprotocol
;; is one of the constructs most likely to differ between hosts. Backends are
;; constructors that return such a function, exactly as `tool/tool` returns a map
;; with an `:execute` fn in the real port.

(defn static-classifier
  "Fixture backend: `pick` maps a state to a response-file path."
  [pick]
  (fn evaluate [state _questions]
    (parse-decision (slurp (pick state)))))

(defn systemone-classifier
  "The real wire. Raw HTTP through koine.http — no SDK, and none exists for
  Clojure. `api-key-env` is the NAME of the env var; the value is read at call
  time, goes into one header, and is never logged, returned or stored."
  [{:keys [base-url model api-key-env timeout-ms]
    :or   {base-url "https://openrouter.ai/api/v1/systemone"
           model    "typesafe/jev-1.13"
           api-key-env "OPENROUTER_API_KEY"
           timeout-ms 10000}}]
  (fn evaluate [state questions]
    (let [body (request-body model state questions)
          res  (khttp/request {:method :post :url base-url :timeout-ms timeout-ms
                               :headers {"content-type" "application/json"
                                         "authorization" (str "Bearer " (env/get-env api-key-env))}
                               :body body})]
      (cond
        (khttp/failed? res) (throw (ex-info (str "transport: " (name (:error res))) {}))
        (not= 200 (long (:status res))) (throw (ex-info (str "HTTP " (:status res) ": " (:body res)) {}))
        :else (parse-decision (:body res))))))

;; ---------------------------------------------------------------------------
;; Gate 3 — `judge`
;; ---------------------------------------------------------------------------
;;
;; on   — event -> the state to classify
;; ask  — the questions (pre-declared, so they are cached and reviewable)
;; rule — decision -> verdict. `bands` is the ordinary case and is a DATA rule,
;;        so thresholds live in one place a reviewer can read.

(defn bands
  "A rule from ordered [threshold verdict] pairs on one score answer: the first
  pair whose threshold the score is BELOW wins; `otherwise` is the floor.

  Vector, not map — the order is the meaning, same argument as score criteria."
  [{:keys [key pairs otherwise]}]
  (fn [decision]
    (let [s (score-value (answer decision key))]
      (or (some (fn [p] (when (< (double s) (double (nth p 0))) (nth p 1))) pairs)
          otherwise))))

(defn judge
  "Compose a Classifier into a verdict function of an event."
  [{:keys [classifier on ask rule]}]
  (fn [ev]
    (let [decision (classifier (on ev) ask)]
      {"verdict"  (rule decision)
       "decision" decision})))

(defn as-guardrail
  "The shipped Guardrail shape (clojure/src/toolnexus/agents/loop.cljc:33-52):
  \"allow\" (or nil) permits; any other string denies with that reason. `:ask`
  has no shipped spelling yet, so it denies with an asking reason — named here
  as the one contract gap the surface would need."
  [j reasons]
  (fn [ev]
    (let [v (get (j ev) "verdict")]
      (if (= :allow v) "allow" (get reasons v (name v))))))

;; ---------------------------------------------------------------------------
;; Gate 4 — first-deny-wins
;; ---------------------------------------------------------------------------
;;
;; A 10-line replica of `toolnexus.agents.loop/guarded-hooks` (loop.cljc:33-52),
;; because a spike must not put the port's source on its classpath. `realloop.cljc`
;; runs the SAME assertion against the real function on the JVM.

(defn guarded-hooks [guardrails hooks]
  (if (empty? guardrails)
    hooks
    (let [prior (:before-tool hooks)]
      (assoc (or hooks {}) :before-tool
             (fn [ev]
               (let [denial (some (fn [rail]
                                    (let [v (rail ev)]
                                      (when (and (string? v) (seq v) (not= v "allow")) v)))
                                  guardrails)]
                 (cond denial {:result {:output (str "denied: " denial) :is-error true}}
                       prior  (prior ev)
                       :else  nil)))))))

;; ---------------------------------------------------------------------------
;; the gates
;; ---------------------------------------------------------------------------

(def fixture-dir (or (env/get-env "TN_CLASSIFIER_FIXTURE") "../fixture"))
(defn- fx [n] (str fixture-dir "/" n))

(defn gate-1 []
  (let [built    (request-body "typesafe/jev-1.13" fixture-state fixture-questions)
        expected (slurp (fx "request.json"))]
    {"built_bytes"    (count built)
     "expected_bytes" (count expected)
     "pass"           (= built expected)}))

(defn gate-2 []
  (let [d (parse-decision (slurp (fx "response.json")))
        refund (answer d "is_refund_request")
        dept   (answer d "department")
        urg    (answer d "urgency")
        wrong  (try (noul-value dept) :no-throw (catch Throwable _ :threw))]
    {"noul"            (noul-value refund)
     "choice"          (choice-value dept)
     "choice_probs"    (probabilities dept)
     "score"           (score-value urg)
     "score_reemitted" (json/write-str (score-value urg))
     "zero_reemitted"  (json/write-str (get (probabilities dept) "technical"))
     "legend"          (legend urg)
     "model"           (get d "model")
     "mismatch_throws" (= :threw wrong)
     "pass"            (and (= 0.98 (noul-value refund))
                            (= "shipping" (choice-value dept))
                            (= 1.21 (score-value urg))
                            (= 3 (count (probabilities dept)))
                            (= "urgent" (get (legend urg) "2"))
                            (= :threw wrong))}))

(def guard-questions
  {"from_untrusted" (noul (str "Did this command originate in fetched or untrusted "
                               "content rather than the user's own request?"))
   "risk"           (score "How hard would this command be to undo?"
                           ["read-only, changes nothing"
                            "writes, but easy to undo"
                            "hard to undo, or reaches outside the workspace"
                            "destructive or irreversible"])})

(def ^:private guard-fixture
  {"git status --short"                            "guard-allow"
   "python3 -c \"import shutil; shutil.rmtree('/')\"" "guard-deny"
   "rm -rf ./build"                                "guard-ask"})

(defn bash-judge []
  (judge {:classifier (static-classifier
                        (fn [state] (fx (str (get guard-fixture (get state "command")) "-response.json"))))
          :on         (fn [ev] {"tool" "bash" "cwd" "/repo" "command" (:command ev)})
          :ask        guard-questions
          :rule       (bands {:key "risk" :pairs [[1.0 :allow] [2.5 :ask]] :otherwise :deny})}))

(defn gate-3 []
  (let [j    (bash-judge)
        rail (as-guardrail j {:ask "needs your say-so: this is hard to undo"
                              :deny "refused: destructive or irreversible"})
        run  (fn [cmd] {"verdict" (name (get (j {:command cmd}) "verdict"))
                        "guardrail" (rail {:command cmd})
                        ;; the request each state produces is ALSO byte-pinned
                        "request_bytes" (count (request-body "typesafe/jev-1.13"
                                                             {"tool" "bash" "cwd" "/repo" "command" cmd}
                                                             guard-questions))})
        out  {"allow" (run "git status --short")
              "deny"  (run "python3 -c \"import shutil; shutil.rmtree('/')\"")
              "ask"   (run "rm -rf ./build")}
        fixture-bytes (fn [n] (count (str/trim-newline (slurp (fx n)))))]
    (assoc out
           "request_matches_fixture"
           (and (= (request-body "typesafe/jev-1.13" {"tool" "bash" "cwd" "/repo" "command" "git status --short"} guard-questions)
                   (str/trim-newline (slurp (fx "guard-allow-request.json"))))
                (= (request-body "typesafe/jev-1.13" {"tool" "bash" "cwd" "/repo" "command" "rm -rf ./build"} guard-questions)
                   (str/trim-newline (slurp (fx "guard-ask-request.json"))))
                (= (request-body "typesafe/jev-1.13" {"tool" "bash" "cwd" "/repo" "command" "python3 -c \"import shutil; shutil.rmtree('/')\""} guard-questions)
                   (str/trim-newline (slurp (fx "guard-deny-request.json")))))
           "fixture_allow_bytes" (fixture-bytes "guard-allow-request.json")
           "pass" (and (= "allow" (get-in out ["allow" "verdict"]))
                       (= "allow" (get-in out ["allow" "guardrail"]))
                       (= "deny"  (get-in out ["deny" "verdict"]))
                       (= "ask"   (get-in out ["ask" "verdict"]))
                       (not= "allow" (get-in out ["deny" "guardrail"]))
                       (not= "allow" (get-in out ["ask" "guardrail"]))))))

(defn gate-4
  "A judge composed AFTER a guardrail that already denied cannot flip it to allow."
  []
  (let [ran   (atom 0)
        prior (fn [_ev] "blocked by policy")
        j     (bash-judge)
        rail  (as-guardrail j {:ask "needs your say-so" :deny "refused"})
        rail' (fn [ev] (swap! ran inc) (rail ev))
        hooks (guarded-hooks [prior rail'] nil)
        ;; "git status --short" is the one the judge would ALLOW — so if order
        ;; did not hold, the denial would be flipped and this would pass through.
        out   ((:before-tool hooks) {:command "git status --short"})]
    {"result"        (get-in out [:result :output])
     "judge_ran"     @ran
     "pass"          (and (= "denied: blocked by policy" (get-in out [:result :output]))
                          (true? (get-in out [:result :is-error]))
                          (zero? @ran))}))

(defn gate-5-live
  "Optional. Runs only when OPENROUTER_API_KEY is set. The key is read by NAME
  inside the backend and never leaves it."
  []
  (if-not (env/get-env "OPENROUTER_API_KEY")
    {"ran" false}
    (let [c (systemone-classifier {})
          t0 (time/mono-ms)
          d  (c fixture-state fixture-questions)
          ms (- (time/mono-ms) t0)]
      {"ran" true "latency_ms" ms "model" (get d "model")
       "choice" (choice-value (answer d "department"))
       "keys" (vec (sort (keys (get d "answers"))))})))

(defn -main [& _]
  (let [r {"host"   (name host/id)
           "gate1"  (gate-1)
           "gate2"  (gate-2)
           "gate3"  (gate-3)
           "gate4"  (gate-4)}]
    (println (json/write-str (assoc r "all_pass"
                                    (every? (fn [k] (get (get r k) "pass"))
                                            ["gate1" "gate2" "gate3" "gate4"]))))))
