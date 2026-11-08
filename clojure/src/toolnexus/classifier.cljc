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
            [koine.codec :as codec]
            [koine.env :as env]
            [koine.http :as khttp]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.client :as client]
            [toolnexus.content :as content]))

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

(def backends
  "§8B `:backend` — the backend table, AS DATA (change add-classifier-backends,
  PD-2). Each row is a PRESET that sets base-url + model + api-key-env AS A UNIT
  (D5 / ADR 0027), plus the wire it speaks, whether it sends the
  `TypeSafe-Danger-Zone-Images` header, and `:supports` — `(fn [model]
  {:images bool})`, the image column. `custom` has no base-url and no
  `:supports`: the caller must declare both.

  The table is a fail-fast for WHAT THE TABLE SAYS, never a claim about the
  vendor; `:supports {:images true}` on a classifier overrides it. Every default
  here is a PROPOSED default, owner to confirm (design.md PD-1..PD-4)."
  {"typesafe"   {:base-url "https://api.typesafe.ai/v1"
                 :model    "jev-latest"
                 :api-key-env "TYPESAFE_API_KEY"
                 :wire "systemone" :images-header true
                 :supports (fn [_] {:images true})}
   "openrouter" {:base-url "https://openrouter.ai/api/v1"
                 :model    "typesafe/jev-1.14"
                 :api-key-env "OPENROUTER_API_KEY"
                 :wire "systemone" :images-header true
                 :supports (fn [_] {:images true})}
   "cloudflare" {:base-url "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run"
                 :model    "typesafe/jev"
                 :api-key-env "CLOUDFLARE_JEV_API_KEY"
                 :wire "cloudflare" :images-header false
                 :supports (fn [model] {:images (str/includes? (str model) "clef")})}
   "vercel"     {:base-url "https://ai-gateway.vercel.sh/typesafe/v1"
                 :model    "typesafe-ai/jev"
                 :api-key-env "AI_GATEWAY_API_KEY"
                 :wire "systemone" :images-header true
                 :supports (fn [_] {:images false})}
   ;; OpenAI's Decisions API (#157): another wire, not a System One host.
   "openai"     {:base-url "https://api.openai.com/v1"
                 :model    "gpt-6-luna"
                 :api-key-env "OPENAI_API_KEY"
                 :wire "decisions" :images-header false
                 :supports (fn [_] {:images true})}
   "custom"     {:base-url nil
                 :model    "jev-latest"
                 ;; No default key: a self-hosted endpoint is not TypeSafe's host,
                 ;; so no preset credential may go to it (#155).
                 :api-key-env ""
                 :wire "systemone" :images-header true
                 :supports nil}})

(def backend-names
  "The six backend values, in the order every port's error message lists them."
  ["typesafe" "openrouter" "cloudflare" "vercel" "openai" "custom"])

(def images-header
  "TypeSafe's opt-in for image (\"danger zone\") input. Sent as `true` only when
  a request carries assets AND the backend row says so."
  "TypeSafe-Danger-Zone-Images")

(def image-min-side 280)
(def image-max-side 1568)
(def image-max-bytes
  "Per image, DECODED bytes (16 MiB)."
  (* 16 1024 1024))
(def request-image-max-bytes
  "All images of one request, decoded bytes (32 MiB)."
  (* 32 1024 1024))

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
  "The four styles (§8B). `\"systemone\"` is the default; `\"static\"` is what
  CI runs — no network, no credential.

  `\"llm\"` is DEPRECATED (ADR 0038 D3, PD-15): System One is the single
  product wire. It keeps working byte-identically, as an alias, for at least one
  minor release, with no runtime warning. A string value carries no `:deprecated`
  metadata, so this docstring is the marker."
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

(defn raw-question
  "The OPEN end of the question union (§8B, PD-14): a System One question type
  the library does not model yet (e.g. Jev 1.14 `box`), sent AS WRITTEN —
  `{type, instructions, criteria?}` — with no client-side limits, because the
  library does not know the type. `type` must be non-empty. Its answer decodes
  to a raw answer `{:type t :value <the answer object>}`."
  ([type instructions] {:type type :instructions instructions})
  ([type instructions criteria]
   (cond-> {:type type :instructions instructions}
     (some? criteria) (assoc :criteria criteria))))

(defn- option-id
  "An option key as its PLAIN NAME, which is what §8B says reaches the wire.
  `str` is the wrong coercion for the idiomatic Clojure key: `(str :billing)` is
  `\":billing\"`, sigil included, so a keyword roster produced a schema-valid
  request whose option ids differed from every other port's — Elixir's
  `to_string(:billing)` is `\"billing\"` — and from the string the caller then
  compares `(:choice answer)` against. Namespaces are kept (`:a/b` => `\"a/b\"`)
  because dropping them can collide two distinct options into one id."
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    ;; a symbol already prints without a sigil, and a string is itself
    (str k)))

(defn choice-over
  "A `choice` over any (name, description) pairs — a Tool, a skill, an agent, an
  A2A card skill. The description must say what picking that option would MEAN;
  see the encoding obligation on `choice-question`.

  Keys may be strings, keywords or symbols; each reaches the wire as its plain
  name, with no `:` sigil (§8B)."
  [instructions items]
  (choice-question instructions (reduce (fn [m e] (assoc m (option-id (key e)) (val e)))
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

      ;; Any other type is a RAW question (the open union): sent as written,
      ;; no limits — only a type is required.
      (and (string? t) (not= "" t)) nil

      :else
      (throw (question-error key "a raw question needs a type" {:type t})))))

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
;; sha256, portable — the cache keys and the metric's questions hash
;; ---------------------------------------------------------------------------
;;
;; Neither koine nor the JVM-free subset of clojure.core has a digest, and the
;; cache keys are SPECIFIED BYTES (a cache written by one port reads in
;; another), so this is ~50 lines of clojure.core over 32-bit words: both hosts
;; run the same code rather than two digests that agree until they don't.

(defn- utf8-bytes
  "The UTF-8 bytes of `s` as unsigned 0..255 values, through koine's base64 seam
  — the one place in the stack that owns string <-> UTF-8 bytes on BOTH hosts."
  [s]
  (mapv (fn [b] (bit-and (long b) 255)) (seq (codec/decode-bytes (codec/encode s)))))

(def ^:private sha-k
  [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1 0x923f82a4 0xab1c5ed5
   0xd807aa98 0x12835b01 0x243185be 0x550c7dc3 0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174
   0xe49b69c1 0xefbe4786 0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
   0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147 0x06ca6351 0x14292967
   0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13 0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85
   0xa2bfe8a1 0xa81a664b 0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
   0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a 0x5b9cca4f 0x682e6ff3
   0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208 0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])

(defn- m32 [x] (bit-and x 0xFFFFFFFF))
(defn- rotr [x n] (m32 (bit-or (bit-shift-right x n) (bit-shift-left x (- 32 n)))))
(defn- shr [x n] (bit-shift-right x n))

(defn- sha-schedule [block]
  (loop [w (mapv (fn [i] (m32 (+ (bit-shift-left (nth block (* 4 i)) 24)
                                 (bit-shift-left (nth block (+ 1 (* 4 i))) 16)
                                 (bit-shift-left (nth block (+ 2 (* 4 i))) 8)
                                 (nth block (+ 3 (* 4 i))))))
                 (range 16))
         t 16]
    (if (= t 64)
      w
      (let [w15 (nth w (- t 15))
            w2  (nth w (- t 2))
            s0  (bit-xor (rotr w15 7) (rotr w15 18) (shr w15 3))
            s1  (bit-xor (rotr w2 17) (rotr w2 19) (shr w2 10))]
        (recur (conj w (m32 (+ (nth w (- t 16)) s0 (nth w (- t 7)) s1))) (inc t))))))

(defn- sha-compress [h block]
  (let [w (sha-schedule block)]
    (loop [t 0 v h]
      (if (= t 64)
        (mapv (fn [i] (m32 (+ (nth h i) (nth v i)))) (range 8))
        (let [[a b c d e f g hh] v
              s1    (bit-xor (rotr e 6) (rotr e 11) (rotr e 25))
              ch    (bit-xor (bit-and e f) (bit-and (bit-xor e 0xFFFFFFFF) g))
              temp1 (m32 (+ hh s1 ch (nth sha-k t) (nth w t)))
              s0    (bit-xor (rotr a 2) (rotr a 13) (rotr a 22))
              maj   (bit-xor (bit-and a b) (bit-and a c) (bit-and b c))
              temp2 (m32 (+ s0 maj))]
          (recur (inc t) [(m32 (+ temp1 temp2)) a b c (m32 (+ d temp1)) e f g]))))))

(defn sha256-hex
  "SHA-256 of the UTF-8 bytes of `s`, lower-case hex. Pure Clojure: correct on
  both hosts, not fast — a cache key over megabytes of image data costs real
  time, which is one more reason the cache is opt-in."
  [s]
  (let [bs     (utf8-bytes s)
        len    (count bs)
        bitlen (* 8 len)
        padded (vec (concat bs
                            [0x80]
                            (repeat (mod (- 56 (inc len)) 64) 0)
                            (mapv (fn [i] (bit-and (bit-shift-right bitlen (* 8 (- 7 i))) 255))
                                  (range 8))))
        digest (loop [i 0
                      h [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a
                         0x510e527f 0x9b05688c 0x1f83d9ab 0x5be0cd19]]
                 (if (>= i (count padded))
                   h
                   (recur (+ i 64) (sha-compress h (subvec padded i (+ i 64))))))]
    (apply str (map (fn [x]
                      (apply str (map (fn [sh] (nth "0123456789abcdef"
                                                   (bit-and (bit-shift-right x sh) 15)))
                                      [28 24 20 16 12 8 4 0])))
                    digest))))

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
  "One answer, discriminated by the wire's `type`. The union is OPEN (§8B,
  PD-14): a type the library does not model (Jev 1.14 `box`) decodes to a RAW
  answer `{:type t :value <the answer object, string keys>}` instead of failing
  the whole decision. Nothing is guessed or repaired (ADR 0020)."
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

      (and (string? t) (not= "" t))
      {:type t :value a}

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

(defn raw-answer
  "The raw answer at `key` — an answer of a type the library does not model —
  or an error when absent or when it IS a modelled type."
  [decision key]
  (let [a (get-in decision [:answers key])]
    (cond
      (nil? a) (throw (ex-info (str "classifier: no answer \"" key "\" in this decision")
                               {:question key :want "raw"}))
      (contains? #{"noul" "choice" "score"} (:type a))
      (throw (ex-info (str "classifier: answer \"" key "\" is a " (:type a) " answer, not raw")
                      {:question key :want "raw" :got (:type a)}))
      :else a)))

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
    :retry-base-ms  base of the retry backoff in ms (default 500); the delay is
                    `base * 2^attempt` with no jitter, and a `Retry-After`
                    header still wins.
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
    :backend        \"typesafe\" (default) | \"openrouter\" | \"cloudflare\" |
                    \"vercel\" | \"openai\" | \"custom\" — a row of `backends`. An explicit
                    `:base-url` / `:model` / `:api-key-env` still wins.
                    `${NAME}` in the base URL expands AT CALL TIME; errors
                    name it unexpanded. `custom` requires `:base-url` and
                    `:supports` (systemone style).
    :supports       `{:images bool}` — overrides the backend row's image column
    :cache          opt-in `{:get (fn [k]) -> string|nil, :set (fn [k v])}`;
                    systemone style only; see `memory-cache`. Absent = off.

  `:style \"llm\"` is DEPRECATED (kept working, no runtime warning).

  A style whose required option is missing, and an unknown style, are rejected
  HERE rather than at the first call."
  [opts]
  (let [style   (str (or (:style opts) "systemone"))
        backend (if (some? (:backend opts)) (str (:backend opts)) "typesafe")
        _       (when-not (contains? backends backend)
                  (throw (ex-info (str "classifier: unknown backend " (pr-str backend)
                                       " (want " (str/join ", " (map pr-str (butlast backend-names)))
                                       " or " (pr-str (last backend-names)) ")")
                                  {:backend backend})))
        ;; A self-hosted endpoint: the library cannot know what an unknown
        ;; server takes, so both are declarations. Wire styles only — static,
        ;; custom and llm never touch the wire.
        _       (when (and (= "custom" backend) (= "systemone" style)
                           (or (nil? (:base-url opts)) (nil? (:supports opts))))
                  (throw (ex-info "classifier: backend \"custom\" requires baseUrl and supports"
                                  {:backend backend})))
        row     (get backends backend)
        ;; Did the caller NAME the key variable? A named one goes wherever the
        ;; base URL points; the preset's own only to its own host (#155).
        explicit-key-env? (boolean (seq (str (:api-key-env opts))))
        preset  (into {} (remove (fn [e] (nil? (val e))))
                      (select-keys row [:base-url :model :api-key-env]))
        opts    (merge preset opts)
        ;; The KNOWN mismatch, refused at construction rather than at the first
        ;; 404: `jev-latest` is TypeSafe's own spelling of the floating alias and
        ;; openrouter.ai does not serve it under that name.
        _       (let [url   (str (or (:base-url opts) default-base-url))
                      model (str (or (:model opts) default-model))]
                  (when (and (str/includes? url "openrouter.ai") (= "jev-latest" model))
                    (throw (ex-info (str "classifier: model \"jev-latest\" is TypeSafe's spelling; "
                                         "on openrouter.ai use "
                                         (pr-str (get-in backends ["openrouter" :model])))
                                    {:base-url url :model model}))))
        model   (or (:model opts) default-model)
        opts  (assoc opts
                     :backend     backend
                     :wire        (:wire row)
                     :images-header? (boolean (:images-header row))
                     ;; The resolved image column: the caller's `:supports`
                     ;; overrides the table row.
                     :supports    (merge {:images false}
                                         (or (:supports opts)
                                             (when-let [f (:supports row)] (f model))))
                     :style       style
                     :base-url    (or (:base-url opts) default-base-url)
                     :model       model
                     :api-key-env (or (:api-key-env opts) default-api-key-env)
                     :explicit-key-env? explicit-key-env?
                     :timeout-ms  (or (:timeout-ms opts) default-timeout-ms)
                     :retries     (or (:retries opts) 2)
                     :retry-base-ms (or (:retry-base-ms opts) 500))]
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

;; ---------------------------------------------------------------------------
;; the Decisions wire (SPEC §8B, issue #157)
;; ---------------------------------------------------------------------------
;;
;; OpenAI's POST /v1/decisions takes the three question kinds under other names
;; (predicate / choice / score) as an ORDERED LIST and answers in that order.
;; Caller keys are NOT sent: they are addressing, not content.

(declare classifier-unsupported)

(def max-decisions-questions
  "The Decisions API's per-request question cap."
  400)

(defn- question-by-name [questions]
  (reduce (fn [m e] (assoc m (option-id (key e)) (val e))) {} questions))

(defn- decisions-questions
  "The question set as the Decisions API list, in ASCII key order. Every check
  here fires before any request."
  [c questions]
  (when (> (count questions) max-decisions-questions)
    (throw (ex-info (str "classifier: the decisions wire takes at most " max-decisions-questions
                         " questions, got " (count questions))
                    {:wire "decisions"})))
  (let [by-name (question-by-name questions)]
    (mapv (fn [k]
            (let [q    (get by-name k)
                  t    (:type q)
                  crit (:criteria q)]
              (cond
                (= "noul" t)
                {:type "predicate"
                 :instructions (str (:instructions q)
                                    (when (contains? q :criteria)
                                      (str "\n\nTrue means: " (get crit "true")
                                           "\nFalse means: " (get crit "false"))))}

                (= "choice" t)
                (let [n (count crit)]
                  (when (or (< n 2) (> n max-choice-options))
                    (throw (question-error k (str "the decisions wire needs 2.." max-choice-options
                                                  " choice options, got " n)
                                           {:wire "decisions"})))
                  {:type "choice" :instructions (:instructions q)
                   :choices (mapv (fn [[id d]]
                                    (cond-> {:value id}
                                      (not= "" (str d)) (assoc :description d)))
                                  (sort-by first (map (fn [e] [(option-id (key e)) (val e)]) crit)))})

                (= "score" t)
                {:type "score" :instructions (:instructions q)
                 :levels (mapv (fn [l] {:label l}) crit)}

                :else
                (throw (classifier-unsupported (:backend c) (:model c)
                                               (str "raw question type " (pr-str (str t))))))))
          (sort (keys by-name)))))

(defn- decisions-input
  "A string state verbatim, any other state as its canonical JSON text; with
  images, one user message holding that text then the images as data URLs."
  [{:keys [state images]}]
  (let [text (if (string? state) state (json/write-str state))]
    (if (empty? images)
      text
      [{:role "user"
        :content (into [{:type "input_text" :text text}]
                       (map (fn [im] {:type "input_image"
                                      :image_url (str "data:image/" (:format im) ";base64," (:data im))}))
                       images)}])))

(defn- decisions-value
  "A choice value or a level index as the §8B key: a string as written, a
  boolean as \"true\"/\"false\", a number in its shortest form."
  [v]
  (cond (string? v) v
        (boolean? v) (if v "true" "false")
        (and (number? v) (== v (long v))) (str (long v))
        :else (str v)))

(defn decisions-reply
  "A Decisions API reply (the raw body) read back into a System One-shaped body,
  as canonical JSON text, so decode, validation and the cache are the one path.
  `calibrated` is false: a Decisions model is not a System One model."
  [body questions]
  (let [malformed #(ex-info "classifier: decisions: malformed reply" {:wire "decisions"})
        r       (try (json/read-str (str body) {:key-fn str}) (catch Throwable _ nil))
        answers (when (map? r) (get r "answers"))
        _       (when-not (sequential? answers) (throw (malformed)))
        by-name (question-by-name questions)
        ks      (vec (sort (keys by-name)))
        _       (when (not= (count answers) (count ks))
                  (throw (ex-info (str "classifier: decisions: " (count answers) " answers for "
                                       (count ks) " questions")
                                  {:wire "decisions"})))
        num     (fn [v] (if (number? v) v 0))
        out     (reduce
                 (fn [m [k a]]
                   (when-not (map? a) (throw (malformed)))
                   (let [q     (get by-name k)
                         at    (get a "type")
                         _     (when (= "refusal" at)
                                 (throw (ex-info (str "classifier: decisions: question " (pr-str k)
                                                      " was refused")
                                                 {:wire "decisions"})))
                         want  (get {"noul" "predicate" "choice" "choice" "score" "score"} (:type q))
                         _     (when (not= want at)
                                 (throw (ex-info (str "classifier: decisions: answer " (pr-str k)
                                                      " is a " at " answer, not " want)
                                                 {:wire "decisions"})))
                         probs (reduce (fn [pm p]
                                         (assoc pm (decisions-value (get p "value"))
                                                (num (get p "probability"))))
                                       {} (get a "probabilities"))]
                     (assoc m k
                            (case (:type q)
                              "noul"   {"type" "noul" "noul" (num (get a "probability"))}
                              "choice" {"type" "choice" "choice" (decisions-value (get a "choice"))
                                        "probabilities" probs
                                        "confidence" (num (get a "confidence"))}
                              "score"  {"type" "score" "score" (num (get a "score"))
                                        "legend" (into {} (map-indexed (fn [i l] [(str i) l])
                                                                       (:criteria q)))
                                        "probabilities" probs
                                        "confidence" (num (get a "confidence"))}))))
                 {} (map vector ks answers))
        usage   (get r "usage")]
    (json/write-str {"answers"    out
                     "calibrated" false
                     "model"      (str (get r "model"))
                     "usage"      {"input_tokens"  (long (num (get usage "input_tokens")))
                                   "output_tokens" (long (num (get usage "output_tokens")))}})))

(defn- wire-body
  "The request for the backend's wire, then the §8 Gap 1 pipeline in §8 order —

     base body -> :request-params merge -> :body-transform -> marshal

  systemone: `{model, questions, state}` + `assets` when the state carried
  images — with no images this is byte-for-byte the body before backends
  existed. cloudflare: `{model, input:{questions, state, images?}}`, images as
  data URLs in collection order. Marshalled through the SAME encoder as
  `canonical-request`, so the `model` + `questions` region is the claimed one."
  [c prepared questions]
  (let [{:keys [state images assets]} prepared
        base   (cond
                 (= "decisions" (:wire c))
                 {:model (:model c)
                  :input (decisions-input prepared)
                  :questions (decisions-questions c questions)}

                 (= "cloudflare" (:wire c))
                 {:model (:model c)
                  :input (cond-> {:questions questions :state state}
                           (seq images)
                           (assoc :images (mapv (fn [im]
                                                  (str "data:image/" (:format im)
                                                       ";base64," (:data im)))
                                                images)))}
                 :else
                 (cond-> {:model (:model c) :questions questions :state state}
                   (seq assets) (assoc :assets assets)))
        params (apply dissoc (:request-params c) forbidden-request-params)
        merged (if (seq params) (merge base params) base)]
    (json/write-str (if-let [f (:body-transform c)] (f merged) merged))))

(defn key-env-for-backend
  "The NAME of the env var whose key may be sent to `base-url` (already
  `${NAME}`-expanded, i.e. as dialled) for `backend`, or \"\" when none may
  (SPEC §8B key-to-host, #155): the backend's own variable, and only when
  `base-url` is that backend's own host over https (case-insensitive hostname;
  openrouter also its `*.openrouter.ai` subdomains). `custom` has no preset key,
  so it is always \"\". A key is never sent to a host the caller did not name."
  [backend base-url]
  (let [row (get backends (str backend))
        env (:api-key-env row)
        own (client/host-of (:base-url row))
        h   (client/host-of base-url)]
    (if (and (seq env) own h
             (or (= h own)
                 (and (= "openrouter" (str backend)) (str/ends-with? h ".openrouter.ai"))))
      env
      "")))

(defn- request-headers
  "Built at CALL TIME and never logged, returned or put in an error. The
  credential is read from the NAMED environment variable and `${ENV_VAR}` in a
  header value expands here — both are use-only values that must not reach an
  agent's context, a log line or a metric. `extra` (the images header) goes in
  BEFORE the host's headers, so a host header wins."
  [c url extra]
  (let [key-env    (if (:explicit-key-env? c)
                     (:api-key-env c)
                     (key-env-for-backend (:backend c) url))
        get-env    (or (:get-env c) env/get-env)
        credential (when (seq (str key-env)) (get-env key-env))]
    (cond-> (reduce (fn [m e]
                      (assoc m (str/lower-case (str (if (keyword? (key e)) (name (key e)) (key e))))
                             (env/expand (str (val e)))))
                    (merge {"content-type" "application/json"} extra)
                    (:headers c))
      (seq (str credential)) (assoc "authorization" (str "Bearer " credential)))))

(defn- safe-cause
  "A backend's reported cause, surfaced so a caller can tell a limit error from a
  transport fault — blanked on an authentication status, REDACTED of account
  identifiers otherwise, and capped at 200 characters in the message.

  The policy itself now lives in `toolnexus.client` and is CALLED, not copied:
  D5 lifts this rule to the §8 path, and two implementations of one policy is
  how they stop being the same policy."
  [status body]
  (let [s (client/cap-message (client/safe-body status body))]
    (if (str/blank? s) "" (str ": " s))))

(defn- post!
  "The one POST, with the §8 retry loop around it — `toolnexus.client/classify`
  and `toolnexus.client/retry-after-ms` are called, not reimplemented, so there
  is exactly one retry policy in this library.

  `endpoint` is what errors NAME: the configured base, `${ENV}` UNEXPANDED, so
  an account id in a templated URL never reaches an error. `url` is what is
  dialled, expanded here at call time (unset => empty)."
  ([c body] (post! c body nil))
  ([c body extra-headers]
   (let [base     (str/replace (str (:base-url c)) #"/+$" "")
         endpoint (case (:wire c)
                    "cloudflare" base
                    "decisions"  (str base "/decisions")
                    (str base "/systemone"))
         url      (if (str/includes? endpoint "${") (env/expand endpoint) endpoint)
         headers  (request-headers c url extra-headers)
         budget   (or (:retries c) 2)
         base-ms  (or (:retry-base-ms c) 500)]
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
                                     (* base-ms (bit-shift-left 1 attempt))))
                   (recur (inc attempt)))
               (throw (if failed?
                        (ex-info (str "classifier: POST " endpoint " transport " (name (:error res)))
                                 {:url endpoint :error (:error res)})
                        ;; The status and the ENDPOINT, and — on an auth status —
                        ;; nothing else.
                        ;; The typed error carries the FULL redacted body; only the
                        ;; message is capped (addendum A5).
                        (ex-info (str "classifier: POST " endpoint " HTTP " status
                                      (safe-cause status (:body res)))
                                 {:toolnexus/error :provider :url endpoint :status status
                                  :body (client/safe-body status (:body res))})))))))))))

;; ---------------------------------------------------------------------------
;; ClassifierUnsupported — raised BEFORE the network, for the table only
;; ---------------------------------------------------------------------------

(defn classifier-unsupported
  "The typed `ClassifierUnsupported` error (§8B, PD-5): an ex-info whose data
  carries `:toolnexus/error :classifier-unsupported` plus `:backend`, `:model`
  and `:feature`. Message, exact: `classifier: <backend> (<model>) does not
  support <feature>`."
  [backend model feature]
  (ex-info (str "classifier: " backend " (" model ") does not support " feature)
           {:toolnexus/error :classifier-unsupported
            :backend backend :model model :feature feature}))

(defn unsupported?
  "True when `e` is a `ClassifierUnsupported` error."
  [e]
  (= :classifier-unsupported (:toolnexus/error (ex-data e))))

;; ---------------------------------------------------------------------------
;; images — lifted out of the state into `assets` (systemone style only)
;; ---------------------------------------------------------------------------

(defn- contains-part? [v]
  (cond
    (content/content-part? v) true
    (map? v)        (boolean (some contains-part? (vals v)))
    (sequential? v) (boolean (some contains-part? v))
    :else           false))

(defn- key-name [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn- lift-images
  "The §8B lifting walk. Returns `{:state <to send> :images [{:ref :mime :data}]}`
  with images in COLLECTION order. A state holding no content part comes back
  VERBATIM (the same value), so a text-only state is byte-identical to before.

  Depth-first, object keys in ASCII order, arrays in order. An image part with
  inline data becomes a reference string — the enclosing key when unused, else
  the smallest unused `image<n>` — a text part becomes its text, any other part
  is an error."
  [state]
  (if-not (contains-part? state)
    {:state state :images []}
    (let [used   (atom #{})
          images (atom [])
          next!  (fn [] (loop [n 1]
                          (let [nm (str "image" n)]
                            (if (contains? @used nm) (recur (inc n)) nm))))
          walk   (fn walk [v k]
                   (cond
                     (content/content-part? v)
                     (let [t (:type v)]
                       (cond
                         (= "text" t) (:text v)
                         (= "image" t)
                         (do (when (nil? (:data v))
                               (throw (ex-info (str "classifier: state: an image part needs inline "
                                                    "data (a URL image cannot be sent)")
                                               {:type t})))
                             (let [ref (if (or (nil? k) (contains? @used k)) (next!) k)]
                               (swap! used conj ref)
                               (swap! images conj {:ref ref :mime (:mimeType v) :data (str (:data v))})
                               ref))
                         :else
                         (throw (ex-info (str "classifier: state: " t " parts are not supported")
                                         {:type t}))))

                     (map? v)
                     (reduce (fn [m kk] (assoc m kk (walk (get v kk) (key-name kk))))
                             {}
                             (sort-by key-name (keys v)))

                     (sequential? v) (mapv (fn [e] (walk e nil)) v)
                     :else v))
          out    (walk state nil)]
      {:state out :images @images})))

(defn- byte-at [bs i] (bit-and (long (nth bs i)) 255))

(defn image-size
  "`[width height]` read from a PNG IHDR or the first JPEG SOF marker of the
  decoded bytes `bs` (a host byte array or a seq of byte values), or nil when
  the bytes are not a readable header of `format` (\"png\" | \"jpeg\")."
  [format bs]
  (let [n    (count bs)
        b    (fn [i] (byte-at bs i))
        be16 (fn [i] (+ (* 256 (b i)) (b (inc i))))
        be32 (fn [i] (+ (* 16777216 (b i)) (* 65536 (b (+ i 1))) (* 256 (b (+ i 2))) (b (+ i 3))))]
    (if (= "png" format)
      (when (and (>= n 24)
                 (= [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A] (mapv b (range 8)))
                 (= [0x49 0x48 0x44 0x52] (mapv b (range 12 16))))
        [(be32 16) (be32 20)])
      (when (and (>= n 4) (= 0xFF (b 0)) (= 0xD8 (b 1)))
        (loop [i 2]
          (when (< (+ i 3) n)
            (when (= 0xFF (b i))
              (let [m (b (inc i))]
                (cond
                  (= 0xFF m) (recur (inc i))
                  (and (<= 0xC0 m 0xCF) (not (contains? #{0xC4 0xC8 0xCC} m)))
                  (when (< (+ i 8) n) [(be16 (+ i 7)) (be16 (+ i 5))])
                  :else (recur (+ i 2 (be16 (+ i 2)))))))))))))

(def ^:private image-formats
  {"image/png" "png" "image/jpeg" "jpeg" "image/jpg" "jpeg"})

(defn- check-images
  "Format, per-image and per-request limits, in collection order, BEFORE the
  network. Errors start `classifier: image \"<ref>\": `. Returns the images with
  `:format` normalised. Never resized, never described as text."
  [images]
  (let [out (mapv (fn [im]
                    (let [ref    (:ref im)
                          fail   (fn [msg] (throw (ex-info (str "classifier: image " (pr-str ref) ": " msg)
                                                           {:image ref})))
                          format (get image-formats (:mime im))
                          _      (when-not format
                                   (fail (str "type " (pr-str (str (:mime im)))
                                              " is not supported (PNG or JPEG only)")))
                          bs     (try (codec/decode-bytes (:data im))
                                      (catch Throwable _ (fail "data is not base64")))
                          size   (count bs)
                          _      (when (> size image-max-bytes)
                                   (fail (str size " bytes, over 16 MiB")))
                          [w h]  (or (image-size format bs)
                                     (fail (str "not a readable " (str/upper-case format))))]
                      (when (or (< w image-min-side) (> w image-max-side)
                                (< h image-min-side) (> h image-max-side))
                        (fail (str w "x" h "; each side must be " image-min-side ".."
                                   image-max-side " px")))
                      (assoc im :format format :bytes size)))
                  images)
        total (reduce + 0 (map :bytes out))]
    (when (> total request-image-max-bytes)
      (throw (ex-info (str "classifier: image " (pr-str (:ref (peek out)))
                           ": request images total " total " bytes, over 32 MiB")
                      {:image (:ref (peek out))})))
    out))

(defn- prepare
  "Lift, then (in this order) unsupported, then limits — all before the network."
  [c state]
  (let [{sent :state images :images} (lift-images state)]
    (when (and (seq images) (not (:images (:supports c))))
      (throw (classifier-unsupported (:backend c) (:model c) "images")))
    (let [images (check-images images)]
      {:state  sent
       :images images
       :assets (when (seq images)
                 (reduce (fn [m im] (assoc m (:ref im) {"type" "image" "format" (:format im)
                                                       "data_base64" (:data im)}))
                         {} images))})))

;; ---------------------------------------------------------------------------
;; cloudflare envelope
;; ---------------------------------------------------------------------------

(defn- unwrap-cloudflare
  "The System One reply inside a Cloudflare `/ai/run` envelope, as parsed JSON.
  A failed envelope surfaces `errors[0]` as `<code> <message>` through the same
  redaction and 200-character cap as every other backend cause (SPEC §8B): the
  list can carry an account id, which `safe-cause` redacts."
  [body]
  (let [env (try (json/read-str (str body) {:key-fn str}) (catch Throwable _ nil))]
    (when-not (and (map? env) (true? (get env "success")))
      (let [e0   (let [es (when (map? env) (get env "errors"))]
                   (when (and (sequential? es) (seq es) (map? (first es))) (first es)))
            code (get e0 "code")
            msg  (get e0 "message")
            text (str/join " " (remove str/blank?
                                       [(if (or (string? code) (number? code)) (str code) "")
                                        (if (string? msg) msg "")]))]
        (throw (ex-info (str "classifier: cloudflare: envelope not successful"
                             (safe-cause 200 text))
                        {:wire "cloudflare"}))))
    (let [res (get env "result")]
      (if (and (map? res) (string? (get res "state")))
        (if (= "Completed" (get res "state"))
          (get res "result")
          (throw (ex-info (str "classifier: cloudflare: run state " (pr-str (get res "state")))
                          {:wire "cloudflare"})))
        res))))

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
;; cache — opt-in, per REQUEST, keyed on the SERVED model
;; ---------------------------------------------------------------------------

(defn- wire-questions-of
  "Caller key -> question wire, keys as strings (what the body carries)."
  [questions]
  (reduce (fn [m e] (assoc m (str (key e)) (val e))) {} questions))

(defn model-cache-key
  "§8B modelKey = sha256hex(canon({endpoint, kind:\"model\", model})). Its
  value is the model the endpoint SERVED for this requested model last time."
  [endpoint model]
  (sha256-hex (json/write-str {"endpoint" endpoint "kind" "model" "model" model})))

(defn decision-cache-key
  "§8B decisionKey = sha256hex(canon({assets?, endpoint, kind:\"decision\",
  model:<served>, questions, state})). `endpoint` is the configured base URL,
  `${ENV}` unexpanded; `state` is the state AS SENT (after lifting); an empty or
  nil `assets` omits the member."
  [endpoint served state assets questions]
  (sha256-hex (json/write-str (cond-> {"endpoint" endpoint "kind" "decision" "model" served
                                       "questions" (wire-questions-of questions) "state" state}
                                (seq assets) (assoc "assets" assets)))))

(defn memory-cache
  "An in-process `{:get :set}` cache, safe for concurrent use. `ttl-ms` 0 => no
  expiry; `max` 0 => unbounded; over `max` the OLDEST-INSERTED entries go first.
  toolnexus persists nothing itself — a host persists by implementing
  `:get`/`:set`."
  ([] (memory-cache 0 0))
  ([ttl-ms max]
   (let [state (atom {:seq 0 :m {}})]
     {:get (fn [k]
             (let [e (get-in @state [:m k])]
               (cond
                 (nil? e) nil
                 (and (pos? ttl-ms) (> (- (ktime/now-ms) (:at e)) ttl-ms))
                 (do (swap! state update :m dissoc k) nil)
                 :else (:v e))))
      :set (fn [k v]
             (swap! state
                    (fn [{n :seq m :m}]
                      (let [n (inc n)
                            m (assoc m k {:v (str v) :at (ktime/now-ms) :seq n})
                            m (if (and (pos? max) (> (count m) max))
                                (let [drop (take (- (count m) max)
                                                 (sort-by (fn [k] (:seq (get m k))) (keys m)))]
                                  (apply dissoc m drop))
                                m)]
                        {:seq n :m m})))
             nil)})))

;; ---------------------------------------------------------------------------
;; V1 — validate-decision, fail closed, first failure wins
;; ---------------------------------------------------------------------------

(defn- in01? [p] (and (number? p) (== p p) (<= 0 p) (<= p 1)))

(defn validate-decision
  "nil when `decision` answers EXACTLY `questions` within their schema, else an
  ex-info whose message starts `classifier: invalid decision: ` and names the
  key as `\"<key>\"`. Checks, first failure wins: a missing answer (question
  keys ASCII), an unexpected answer (ASCII), then per key the type, noul in
  [0,1], choice offered / probability keys offered / each in [0,1] / a
  non-empty map sums to 1 ± 0.02 / confidence in [0,1], score in
  [0, levels-1] with the same probability rules over level indices. A raw
  question only needs an answer of its own type."
  [questions decision]
  (let [qs      (wire-questions-of questions)
        answers (or (:answers decision) {})
        qkeys   (sort (keys qs))
        bad     (fn [msg] (ex-info (str "classifier: invalid decision: " msg)
                                   {:toolnexus/error :invalid-decision}))
        dist    (fn [k probs offered?]
                  (or (some (fn [o]
                              (let [p (get probs o)]
                                (cond
                                  (not (offered? o))
                                  (bad (str "answer " (pr-str k) ": probability for " (pr-str o)
                                            ", which was not offered"))
                                  (not (in01? p))
                                  (bad (str "answer " (pr-str k) ": probability " p " outside [0,1]")))))
                            (sort (map str (keys probs))))
                      (let [sum (reduce + 0.0 (map double (vals probs)))]
                        (when (and (seq probs) (> (abs* (- sum 1.0)) 0.02))
                          (bad (str "answer " (pr-str k) ": probabilities sum to " sum))))))]
    (or (some (fn [k] (when-not (contains? answers k) (bad (str "no answer " (pr-str k))))) qkeys)
        (some (fn [k] (bad (str "unexpected answer " (pr-str k))))
              (sort (remove (fn [k] (contains? qs k)) (map str (keys answers)))))
        (some (fn [k]
                (let [q (get qs k) a (get answers k) qt (:type q)]
                  (cond
                    (not= (:type a) qt)
                    (bad (str "answer " (pr-str k) " is a " (:type a) " answer, not " qt))

                    (= "noul" qt)
                    (when-not (in01? (:noul a))
                      (bad (str "answer " (pr-str k) ": noul " (:noul a) " outside [0,1]")))

                    (= "choice" qt)
                    (let [offered (set (map option-id (keys (:criteria q))))]
                      (cond
                        (not (contains? offered (:choice a)))
                        (bad (str "answer " (pr-str k) ": choice " (pr-str (:choice a)) " was not offered"))
                        :else
                        (or (dist k (:probabilities a) offered)
                            (when-not (in01? (:confidence a))
                              (bad (str "answer " (pr-str k) ": confidence " (:confidence a)
                                        " outside [0,1]"))))))

                    (= "score" qt)
                    (let [n       (count (:criteria q))
                          sc      (:score a)
                          offered (set (map str (range n)))]
                      (if (not (and (number? sc) (== sc sc) (<= 0 sc) (<= sc (dec n))))
                        (bad (str "answer " (pr-str k) ": score " sc " outside [0," (dec n) "]"))
                        (or (dist k (:probabilities a) offered)
                            (when-not (in01? (:confidence a))
                              (bad (str "answer " (pr-str k) ": confidence " (:confidence a)
                                        " outside [0,1]"))))))

                    :else nil)))
              qkeys))))

;; ---------------------------------------------------------------------------
;; T1 thresholds and C1 chunking — pure helpers
;; ---------------------------------------------------------------------------

(def default-thresholds
  "jevx's INCLUSIVE cut-points (T1): 0.8 / 0.2 / 0.6. They sit ALONGSIDE the
  judge layer's exclusive `default-bands`, which stay the default of `ask` /
  `gate`."
  {:yes 0.8 :no 0.2 :min-confidence 0.6})

(defn threshold-band
  "\"yes\" | \"no\" | \"uncertain\" for one answer, INCLUSIVE: noul `p >= yes`
  => yes, else `p <= no` => no; choice/score `confidence >= min-confidence` =>
  yes. No near-uniform demotion (that is the bands' rule). A raw answer is
  uncertain. `thresholds` merges over `default-thresholds`."
  ([answer] (threshold-band answer nil))
  ([answer thresholds]
   (let [{:keys [yes no min-confidence]} (merge default-thresholds thresholds)
         t (:type answer)]
     (cond
       (= "noul" t) (let [p (:noul answer)]
                      (cond (>= p yes) "yes" (<= p no) "no" :else "uncertain"))
       (contains? #{"choice" "score"} t)
       (if (>= (:confidence answer) min-confidence) "yes" "uncertain")
       :else "uncertain"))))

(defn chunk-questions
  "Split a question map into consecutive chunks of `size`, keys in ASCII order.
  A helper, NOT automatic: joint calibration across a split is unproven, so the
  host decides to split and merges. `size < 1` is an error."
  [questions size]
  (when (or (not (integer? size)) (< size 1))
    (throw (ex-info (str "classifier: chunk size must be at least 1, got " size) {:size size})))
  (->> (sort-by first (map (fn [e] [(str (key e)) (val e)]) questions))
       (partition-all size)
       (mapv (fn [chunk] (into {} chunk)))))

;; ---------------------------------------------------------------------------
;; evaluate — the whole contract, one verb
;; ---------------------------------------------------------------------------

(defn- evaluate-wire
  "The systemone style on every backend: lift, check, cache, post, decode.
  `validate?` fails closed on V1; either way only a V1-valid reply is cached."
  [c state questions validate?]
  (let [prepared (prepare c state)
        cache    (:cache c)
        endpoint (:base-url c)
        mkey     (when cache (model-cache-key endpoint (:model c)))
        hit      (when cache
                   (when-let [served ((:get cache) mkey)]
                     (when-let [raw ((:get cache) (decision-cache-key endpoint served (:state prepared)
                                                                      (:assets prepared) questions))]
                       ;; A corrupt or invalid entry is a MISS, never an error.
                       (let [d (try (parse-decision (json/read-str (str raw) {:key-fn str}))
                                    (catch Throwable _ nil))]
                         (when (and d (nil? (validate-decision questions d)))
                           (assoc d :cached true))))))]
    (or hit
        (let [extra (when (and (seq (:images prepared)) (:images-header? c))
                      {(str/lower-case images-header) "true"})
              body  (post! c (wire-body c prepared questions) extra)
              ;; Normalised to the System One shape right after the post, so
              ;; decode, validation and the cache are the one path.
              body  (if (= "decisions" (:wire c)) (decisions-reply body questions) body)
              [parsed raw] (if (= "cloudflare" (:wire c))
                             (let [r (unwrap-cloudflare body)] [r (json/write-str r)])
                             [(json/read-str body {:key-fn str}) body])
              d     (parse-decision parsed)
              verr  (validate-decision questions d)]
          (when (and validate? verr) (throw verr))
          (when (and cache (nil? verr))
            (let [served (or (:model d) (:model c))]
              ((:set cache) mkey served)
              ((:set cache) (decision-cache-key endpoint served (:state prepared)
                                                (:assets prepared) questions)
                            raw)))
          d))))

(defn- evaluate*
  "The one path. `validate?` = fail closed on V1 (the `state` builders); plain
  `evaluate` keeps its old behaviour."
  [c state questions validate?]
  (let [t0    (ktime/now-ms)
        n     (count questions)
        qhash (try (sha256-hex (canonical-request (:model c) questions))
                   (catch Throwable _ ""))
        fail! (fn [e]
                (emit! c {:event metric-evaluate :model (:model c) :status "error"
                          :ms (- (ktime/now-ms) t0) :error (ex-message e)
                          :questions n :questions_hash qhash :cached false})
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
              (let [style (:style c)
                    d     (cond
                            (= "custom" style) ((:evaluate c) state questions)
                            (= "static" style) (evaluate-static c state questions)
                            (= "llm" style)    (evaluate-llm c state questions)
                            :else              (evaluate-wire c state questions validate?))]
                (when (and validate? (not= "systemone" style))
                  (when-let [e (validate-decision questions d)] (throw e)))
                d)
              (catch Throwable e (fail! e)))]
      (emit! c {:event             metric-evaluate
                :model             (:model d)
                :status            "ok"
                :ms                (- (ktime/now-ms) t0)
                :prompt_tokens     (get-in d [:usage :input-tokens])
                :completion_tokens (get-in d [:usage :output-tokens])
                :questions         n
                :questions_hash    qhash
                :cached            (true? (:cached d))})
      d)))

(defn evaluate
  "A state plus typed questions in, a decision out.

  `state` is a string, a map, or a vector — whatever the host already has.
  `questions` maps CALLER-CHOSEN keys to question maps. The keys are ADDRESSING,
  NOT CONTENT: they are never transmitted to the model, so a key MAY be a tool,
  skill or agent name verbatim, and two evaluations differing only in their keys
  send identical content.

  Questions are INDEPENDENT — one answer is never context for another. A backend
  that cannot guarantee that reports `:calibrated false`, which carries both
  caveats.

  On style systemone, content parts in the state are lifted into `assets`
  (§8B images). A reply that fails `validate-decision` is still RETURNED here
  (unchanged behaviour) but never cached; `evaluate-state` fails closed on it.
  A decision served from `:cache` carries `:cached true`."
  [c state questions]
  (evaluate* c state questions false))

;; ---------------------------------------------------------------------------
;; static in one line, and the batch
;; ---------------------------------------------------------------------------

(defn static-classifier
  "A `static` classifier from recorded decisions in ONE call — what a hermetic
  test wants. Each entry is `{:state … :questions … :response <parsed body>}`,
  or `{:state … :questions … :answers <wire answers>}` as shorthand for a body
  that is only its answers. `opts` merges into `create-classifier` (e.g.
  `:model`)."
  ([decisions] (static-classifier decisions {}))
  ([decisions opts]
   (create-classifier
    (merge opts
           {:style     "static"
            :decisions (mapv (fn [d]
                               {:state     (:state d)
                                :questions (:questions d)
                                :response  (or (:response d) {"answers" (:answers d)})})
                             decisions)}))))

(def default-batch-concurrency
  "At most this many `evaluate` calls in flight in one batch (§8B Batch)."
  16)

(defn evaluate-batch
  "§8B Batch — the SAME questions over many states. Each state goes through the
  per-state `evaluate` (every style), at most `:concurrency` (default 16) in
  flight, and the decisions come back IN STATE ORDER regardless of completion
  order. FAIL CLOSED: if any state fails, this throws naming the first failing
  state's index and returns no decisions. No states is an error and sends
  nothing.

  `koine.process/run-async!` + a promise, not `future` — the same reason as the
  client's parallel tool calls: library code never holds its host's process
  open."
  ([c states questions] (evaluate-batch c states questions {}))
  ([c states questions opts]
   (when (empty? states)
     (throw (ex-info "classifier: evaluateBatch: no states to evaluate" {})))
   (let [n        (max 1 (or (:concurrency opts) default-batch-concurrency))
         outcomes (into []
                        (mapcat
                         (fn [wave]
                           (->> wave
                                (mapv (fn [st]
                                        (let [p (promise)]
                                          (proc/run-async!
                                           (fn []
                                             (deliver p (try {:value (evaluate c st questions)}
                                                             (catch Throwable e {:thrown e})))))
                                          p)))
                                (mapv deref))))
                        (partition-all n states))]
     (doseq [[i o] (map-indexed vector outcomes)]
       (when-let [e (:thrown o)]
         (throw (ex-info (str "classifier: evaluateBatch: state " i " failed: " (ex-message e))
                         {:index i} e))))
     (mapv :value outcomes))))

;; ---------------------------------------------------------------------------
;; value builders — state(v).evaluate(qs…) and states(…).evaluate(qs…)
;; ---------------------------------------------------------------------------

(defn- named->questions
  "An ordered list of NAMED questions (`toolnexus.judge/noul` …, or any question
  map with a `:name`) as the §8B `{name -> question}` map. A duplicate name
  throws naming it, before any request."
  [qs]
  (reduce (fn [m q]
            (let [n (:name q)]
              (when (contains? m n)
                (throw (ex-info (str "duplicate question name \"" n "\"") {:name n})))
              (assoc m n (dissoc q :name))))
          {} qs))

(defn state
  "Bind a state to a classifier. A VALUE, not a session: nothing is sent or
  stored until `evaluate-state`."
  [c v]
  {::classifier c ::state v})

(defn states
  "Bind a batch of states. See `evaluate-states`."
  [c vs]
  {::classifier c ::states (vec vs)})

(defn evaluate-state
  "`state(v).evaluate(questions…)`: one request with the NAMED questions `qs`,
  and the reply validated FAIL-CLOSED (`validate-decision`) — an invalid reply
  is an error, reported on the metric event."
  [bound & qs]
  (evaluate* (::classifier bound) (::state bound) (named->questions qs) true))

(defn evaluate-states
  "`states(…).evaluate(questions…)`: `evaluate-state` per state, at most 16 in
  flight, results IN STATE ORDER, one per state: `{:decision d}` or
  `{:error e}`. One state's failure NEVER fails the batch. A question-set error
  (duplicate name) throws once, before any request. No states => `[]`.
  `evaluate-batch` (fail-closed) is unchanged."
  [bound & qs]
  (let [c         (::classifier bound)
        questions (named->questions qs)]
    (into []
          (mapcat
           (fn [wave]
             (->> wave
                  (mapv (fn [st]
                          (let [p (promise)]
                            (proc/run-async!
                             (fn []
                               (deliver p (try {:decision (evaluate* c st questions true)}
                                               (catch Throwable e {:error e})))))
                            p)))
                  (mapv deref))))
          (partition-all default-batch-concurrency (::states bound)))))
