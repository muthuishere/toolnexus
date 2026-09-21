;; toolnexus.classifier — the SPEC §8B conformance suite.
;;
;; Every assertion is driven off the SHARED fixtures in `examples/judge/`
;; (path from TN_EXAMPLES, like the rest of the suite). The fixtures are the
;; contract: nothing here re-derives what this port believes correct looks like,
;; and there is no per-language copy of any of them.
;;
;; The one thing this file builds for itself is SHA-256, because neither koine
;; nor the port has a digest and the fixtures pin their canonical bytes by hash.
;; It is ~50 lines of clojure.core over 32-bit words, so both hosts run the same
;; code rather than two digests that agree until they don't — the same reasoning
;; koine gives for owning its JSON encoder.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.classifier-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.codec :as codec]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.text :as ktext]
            [koine.time :as ktime]
            [toolnexus.classifier :as jev]
            [toolnexus.client :as client]))

;; ---------------------------------------------------------------------------
;; sha256, portable
;; ---------------------------------------------------------------------------

(defn- utf8-bytes
  "The UTF-8 bytes of `s` as unsigned 0..255 values. Routed through koine's
  base64 seam, which is the one place in the stack that already owns
  string <-> UTF-8 bytes correctly on BOTH hosts — hand-rolling a second
  encoder here is exactly the drift this port exists to prevent (and a naive
  one emits CESU-8 above the BMP)."
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

(defn- schedule [block]
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

(defn- compress [h block]
  (let [w (schedule block)]
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

(defn- sha256-hex
  "SHA-256 of the UTF-8 bytes of `s`, lower-case hex."
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
                   (recur (+ i 64) (compress h (subvec padded i (+ i 64))))))]
    (apply str (map (fn [x]
                      (let [s (str/join (map (fn [n] (nth "0123456789abcdef" n))
                                             [(bit-and (bit-shift-right x 28) 15)
                                              (bit-and (bit-shift-right x 24) 15)
                                              (bit-and (bit-shift-right x 20) 15)
                                              (bit-and (bit-shift-right x 16) 15)
                                              (bit-and (bit-shift-right x 12) 15)
                                              (bit-and (bit-shift-right x 8) 15)
                                              (bit-and (bit-shift-right x 4) 15)
                                              (bit-and x 15)]))]
                        s))
                    digest))))

(deftest sha256-is-correct
  (testing "the digest this file measures the fixtures with is itself pinned"
    ;; The two canonical NIST vectors plus one multi-block input, so a broken
    ;; digest cannot quietly agree with a broken canonicaliser.
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (sha256-hex "")))
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (sha256-hex "abc")))
    (is (= "59f109d9533b2b70e7c3b814a2bd218f78ea5d3714455bc67987cf0d664399cf"
           (sha256-hex (str/join (repeat 2 "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))
        "two blocks, so the schedule and the chaining are exercised")
    (is (= "9eaaf0526050195381750506ba54042d6cf31ec94bd3b2dfc29cff6b8d5b4dc0"
           (sha256-hex "café — 日本語"))
        "non-ASCII goes through UTF-8, not UTF-16 code units")))

;; ---------------------------------------------------------------------------
;; the shared fixtures
;; ---------------------------------------------------------------------------

(defn- fixture
  "One `examples/judge/` fixture, parsed with STRING keys throughout — a
  criteria key may be digit-leading, upper-case or non-ASCII and a legend key is
  \"0\"/\"1\"/…, none of which survive keywordizing."
  [name]
  (let [dir (env/get-env "TN_EXAMPLES")]
    (json/read-str (fs/read-file (str dir "/judge/" name ".json")) {:key-fn str})))

(defn- ->question
  "Rebuild one typed question from a fixture's raw wire form. The
  absent-vs-empty distinction is preserved: a noul with NO `criteria` key gets
  the one-arity constructor, one with `{\"true\":\"\",\"false\":\"\"}` gets the
  two-arity, and the two produce different bytes."
  [q]
  (let [t (get q "type") i (get q "instructions") crit (get q "criteria")]
    (cond
      (= t "noul")   (if (contains? q "criteria") (jev/noul-question i crit) (jev/noul-question i))
      (= t "choice") (jev/choice-question i crit)
      (= t "score")  (jev/score-question i crit)
      :else          (throw (ex-info (str "fixture: unknown question type " (pr-str t)) {})))))

(defn- ->questions [request]
  (reduce (fn [m e] (assoc m (str (key e)) (->question (val e))))
          {} (get request "questions")))

(defn- static-for
  "A `static` classifier answering this fixture's own request — the hermetic
  backend CI runs, with no network and no credential."
  [f extra]
  (let [r (get f "request")]
    (jev/create-classifier
     (merge {:style "static"
             :model (get r "model")
             :decisions [{:state (get r "state") :questions (->questions r)
                          :response (get f "response")}]}
            extra))))

;; ---------------------------------------------------------------------------
;; the bytes
;; ---------------------------------------------------------------------------

(deftest canonical-bytes-match-every-fixture
  (doseq [name ["base" "hardened" "numbers" "wide" "degenerate" "near-uniform"]]
    (testing name
      (let [f   (fixture name)
            r   (get f "request")
            got (jev/canonical-request (get r "model") (->questions r))]
        (is (= (get f "canonical") got) "canonical bytes")
        (is (= (get f "canonicalBytes") (ktext/utf8-length got)) "byte length")
        (is (= (get f "canonicalSha256") (sha256-hex got)) "sha256")))))

(deftest decisions-fixture-shares-one-payload-and-one-hash
  (testing "the three guard entries differ ONLY in state"
    (let [entries (get (fixture "decisions") "entries")]
      (is (= 3 (count entries)))
      (doseq [e entries]
        (let [r   (get e "request")
              got (jev/canonical-request (get r "model") (->questions r))]
          (is (= (get e "canonical") got))
          (is (= (get e "canonicalSha256") (sha256-hex got)))))
      (is (= 1 (count (set (map (fn [e] (get e "canonicalSha256")) entries))))
          "one hash across all three — which is why static must key on state too"))))

(deftest absent-criteria-is-not-empty-criteria
  (let [absent (jev/canonical-request "m" {"q" (jev/noul-question "i")})
        empty  (jev/canonical-request "m" {"q" (jev/noul-question "i" {"true" "" "false" ""})})]
    (is (not (str/includes? absent "criteria")) "an absent criteria emits no key at all")
    (is (str/includes? empty "\"criteria\":{\"false\":\"\",\"true\":\"\"}")
        "an empty criteria survives with both keys")
    (is (not= absent empty))))

;; ---------------------------------------------------------------------------
;; the parse
;; ---------------------------------------------------------------------------

(deftest base-fixture-parses
  (let [f (fixture "base")
        r (get f "request")
        d (jev/evaluate (static-for f {}) (get r "state") (->questions r))]
    (is (true? (:calibrated d)) "a recorded systemone decision is calibrated")
    (is (= "typesafe/jev-1.13-20260917" (:model d)) "the model that ANSWERED, not the one asked for")
    (let [ch (jev/choice d "department")]
      (is (= "shipping" (:choice ch)))
      (is (== 0.41 (:confidence ch)))
      (is (false? (:near-uniform ch)))
      (is (contains? (:probabilities ch) "technical") "a ZERO probability stays an entry")
      (is (== 0 (get (:probabilities ch) "technical"))))
    (is (== 0.98 (:noul (jev/noul d "is_refund_request"))))
    (let [s (jev/score d "urgency")]
      (is (== 1.21 (:score s)))
      (is (== 0.57 (:confidence s)))
      (is (= ["routine" "elevated" "urgent"] (jev/levels s))))
    (testing "a wrong-type or absent read is an ERROR, never a nil that flows on"
      (is (thrown? Throwable (jev/noul d "department")))
      (is (thrown? Throwable (jev/choice d "nope"))))))

(deftest numbers-fixture-parses-numerically
  (testing "0 vs 0.0 and 1.6716e-5 vs 0.000016716 are the same VALUE and different bytes"
    (let [f (fixture "numbers")
          r (get f "request")
          d (jev/evaluate (static-for f {}) (get r "state") (->questions r))
          s (jev/score d "urgency")]
      (is (== 1.21 (:score s)))
      (is (== 0.04 (get (:probabilities s) "0")))
      (is (== 0 (:noul (jev/noul d "is_expensive"))))
      (is (== 1.6716e-5 (get-in d [:usage :cost])))
      (testing "state round-trips verbatim — it is the caller's, outside the byte claim"
        (let [st (get r "state")]
          (is (== 0 (get st "attempts")))
          (is (== 1.21 (get st "elapsed")))
          (is (== 1.6716e-5 (get st "unit_cost"))))))))

(deftest wide-fixture-parses
  (testing "40 keys — above the width at which some runtimes stop iterating small maps in term order"
    (let [f (fixture "wide")
          r (get f "request")
          d (jev/evaluate (static-for f {}) (get r "state") (->questions r))
          ch (jev/choice d "skill")]
      (is (= "skill_07" (:choice ch)))
      (is (= 40 (count (:probabilities ch))))
      (is (false? (:near-uniform ch)) "a peaked answer is not near-uniform"))))

;; ---------------------------------------------------------------------------
;; nearUniform
;; ---------------------------------------------------------------------------

(deftest near-uniform-matches-the-fixture
  (let [f      (fixture "near-uniform")
        expect (get f "expect")
        r      (get f "request")]
    (is (== (get expect "tolerance") jev/near-uniform-tolerance)
        "the fixture's tolerance IS this port's constant")
    (let [d (jev/evaluate (static-for f {}) (get r "state") (->questions r))]
      (doseq [[key want] (get expect "answers")]
        (testing key
          (let [ch     (jev/choice d key)
                target (/ 1.0 (count (:probabilities ch)))
                dev    (reduce max 0.0 (map (fn [p] (let [x (- (double p) target)]
                                                      (if (neg? x) (- x) x)))
                                            (vals (:probabilities ch))))]
            (is (= (get want "nearUniform") (:near-uniform ch)))
            (is (< (let [x (- dev (get want "maxDeviation"))] (if (neg? x) (- x) x)) 1e-9)
                "the deviation the rule is decided on is the one the fixture records")))))))

(deftest near-uniform-boundary
  (testing "0.05 ABSOLUTE and INCLUSIVE; the fixture pins 1e-4 either side so no epsilon is needed"
    (is (true?  (jev/near-uniform? {"a" 0.5499 "b" 0.4501})) "just inside")
    (is (false? (jev/near-uniform? {"a" 0.5501 "b" 0.4499})) "just outside")
    (is (true?  (jev/near-uniform? {"only" 1})) "n = 1 is trivially uniform")
    (is (false? (jev/near-uniform? {})) "an empty map has no distribution at all")
    (testing "values are taken AS RETURNED — never renormalised"
      ;; Three entries summing to 0.3: renormalising would read as perfectly
      ;; uniform, so a port that renormalises passes where it must fail.
      (is (false? (jev/near-uniform? {"a" 0.1 "b" 0.1 "c" 0.1}))))))

;; ---------------------------------------------------------------------------
;; degenerate criteria — detect, never repair
;; ---------------------------------------------------------------------------

(deftest degenerate-criteria-warns-once-per-key-and-changes-nothing
  (let [f       (fixture "degenerate")
        expect  (get f "expect")
        r       (get f "request")
        qs      (->questions r)
        warned  (atom [])
        c       (jev/create-classifier
                 {:style "custom"
                  :model (get r "model")
                  :evaluate (fn [_ _] {:model (get r "model") :answers {} :calibrated true})
                  :on-metric (fn [ev]
                               (when (= jev/metric-warning (:event ev))
                                 (swap! warned conj (:question ev))
                                 (is (nil? (:error ev))
                                     "a warning is advisory, never a failure")
                                 (is (str/includes? (:warning ev) (:question ev))
                                     "the warning NAMES the offending question key")))})]
    ;; Twice: detection is ONCE PER QUESTION KEY, so a per-turn judge does not
    ;; flood the sink.
    (jev/evaluate c (get r "state") qs)
    (jev/evaluate c (get r "state") qs)
    (is (= (get expect "warnings") (vec (sort @warned))))
    (doseq [key (get expect "noWarning")]
      (is (not (contains? (set @warned) key)) (str key " is described and must not warn")))
    (testing "detection, never repair — the request is byte-unchanged"
      (is (= (get f "canonicalSha256")
             (sha256-hex (jev/canonical-request (get r "model") qs)))))))

(deftest degenerate-predicate
  (is (some? (jev/degenerate-criteria {"a" "" "b" ""})) "all empty")
  (is (some? (jev/degenerate-criteria {"a" "a" "b" "b"})) "all equal their own key")
  (is (some? (jev/degenerate-criteria {"a" "same" "b" "same"})) "all identical")
  (is (nil? (jev/degenerate-criteria {"a" "one thing" "b" "another"})) "described")
  (is (nil? (jev/degenerate-criteria {"a" "" "b" "described"})) "one empty is not all empty")
  (is (nil? (jev/degenerate-criteria {"only" "only"}))
      "a single option is NEVER reported — there is nothing to differentiate"))

;; ---------------------------------------------------------------------------
;; the static backend
;; ---------------------------------------------------------------------------

(deftest static-backend-distinguishes-the-three-guard-bands
  (let [entries (get (fixture "decisions") "entries")
        c       (jev/create-classifier
                 {:style "static"
                  :model (get-in (first entries) ["request" "model"])
                  :decisions (mapv (fn [e]
                                     (let [r (get e "request")]
                                       {:state (get r "state")
                                        :questions (->questions r)
                                        :response (get e "response")}))
                                   entries)})]
    (doseq [[e want] (map vector entries [0.02 2.25 2.97])]
      (let [r (get e "request")
            d (jev/evaluate c (get r "state") (->questions r))]
        (is (== want (:score (jev/score d "risk")))
            (str "band " (get e "band") " — one payload, one hash, three states"))))
    (testing "an unrecorded state is an ERROR, never a neighbouring band"
      (is (thrown? Throwable
                   (jev/evaluate c {"command" "unseen" "cwd" "/repo" "tool" "bash"}
                                 (->questions (get (first entries) "request"))))))))

;; ---------------------------------------------------------------------------
;; limits
;; ---------------------------------------------------------------------------

(deftest limits-are-rejected-pre-flight-with-no-http-call
  (let [calls (atom 0)
        http  (fn [_ _ _] (swap! calls inc) {:status 200 :body "{}"})
        many  (reduce (fn [m i] (assoc m (str "opt_" i) (str "description " i)))
                      {} (range (inc jev/max-choice-options)))]
    (doseq [[label q want]
            [["too many options"    (jev/choice-question "?" many)                  "1..255 options"]
             ["no options"          (jev/choice-question "?" {})                    "1..255 options"]
             ["one rubric level"    (jev/score-question "?" ["only"])               "2..10 ordered levels"]
             ["eleven rubric levels" (jev/score-question "?" (vec (repeat 11 "l"))) "2..10 ordered levels"]]]
      (testing label
        (let [c (jev/create-classifier {:http-client http})
              e (try (jev/evaluate c "s" {"the_key" q}) nil (catch Throwable t t))]
          (is (some? e) "a pre-flight failure, not a request")
          (is (str/includes? (ex-message e) "the_key") "the error names the offending question KEY")
          (is (str/includes? (ex-message e) want) "and the limit"))))
    (is (zero? @calls) "no request is sent when validation fails")))

;; ---------------------------------------------------------------------------
;; secrets — use-only, never in a log, metric, error or return value
;; ---------------------------------------------------------------------------

(deftest credentials-and-expanded-headers-never-leak
  ;; The suite cannot set an environment variable in-process on either host, so
  ;; the credential is a variable the runner ALREADY exports: TN_EXAMPLES, which
  ;; `toolnexus.test-main` refuses to run without. It is not a real secret; it is
  ;; a value that must behave exactly like one — reach the wire and nowhere else.
  (let [secret (env/get-env "TN_EXAMPLES")
        seen   (atom nil)
        events (atom [])
        http   (fn [_ headers _]
                 (reset! seen headers)
                 ;; A real gateway happily reflects what it was sent.
                 {:status 401
                  :body (str "{\"error\":\"bad credential " (get headers "authorization")
                             " for " (get headers "x-tenant") "\"}")})
        c      (jev/create-classifier {:base-url "https://judge.example/v1"
                                       :api-key-env "TN_EXAMPLES"
                                       :headers {"X-Tenant" "${TN_EXAMPLES}"}
                                       :retries 1
                                       :http-client http
                                       :on-metric (fn [ev] (swap! events conj ev))})
        err    (try (jev/evaluate c "s" {"q" (jev/noul-question "?")}) nil
                    (catch Throwable t t))]
    (is (some? secret) "TN_EXAMPLES must be set — see toolnexus.test-main")
    (is (some? err) "a 401 fails")
    (testing "the credential and the ${ENV} header DID reach the wire — they are use-only, not unused"
      (is (= (str "Bearer " secret) (get @seen "authorization")))
      (is (= secret (get @seen "x-tenant")) "${ENV_VAR} expands at CALL TIME"))
    (testing "and nowhere else"
      (is (not (str/includes? (ex-message err) secret)) "not in the error message")
      (doseq [ev @events]
        (is (not (str/includes? (json/write-str ev) secret))
            "not in any metric event — including the one the 401 body reflected back")))
    (testing "an authentication failure names the status and the endpoint, and nothing else"
      (is (str/includes? (ex-message err) "401"))
      (is (str/includes? (ex-message err) "https://judge.example/v1/systemone")))))

(deftest a-backend-cause-survives-intact-on-a-non-auth-status
  (testing "so a caller can tell a LIMIT error from a transport fault"
    (let [http (fn [_ _ _] {:status 400
                            :body "{\"error\":\"Too many choices. Must have at most 255 choices.\"}"})
          c    (jev/create-classifier {:http-client http :retries 0})
          err  (try (jev/evaluate c "s" {"q" (jev/noul-question "?")}) nil
                    (catch Throwable t t))]
      (is (str/includes? (ex-message err) "Too many choices")))))

;; ---------------------------------------------------------------------------
;; the wire, end to end
;; ---------------------------------------------------------------------------

(deftest systemone-posts-the-canonical-body-and-parses-the-reply
  (let [f    (fixture "base")
        r    (get f "request")
        sent (atom nil)
        http (fn [url _ body]
               (reset! sent {:url url :body body})
               {:status 200 :body (json/write-str (get f "response"))})
        c    (jev/create-classifier {:model (get r "model") :http-client http})
        d    (jev/evaluate c (get r "state") (->questions r))]
    (is (= "https://api.typesafe.ai/v1/systemone" (:url @sent)) "the default base URL + /systemone")
    (testing "the body carries state verbatim, and the model+questions region is the canonical one"
      (let [parsed (json/read-str (:body @sent) {:key-fn str})]
        (is (= (get r "state") (get parsed "state")))
        (is (= (get f "canonical")
               (jev/canonical-request (get parsed "model")
                                      (->questions {"questions" (get parsed "questions")}))))))
    (is (= "shipping" (:choice (jev/choice d "department"))))))

(deftest request-params-merge-then-body-transform
  (testing "base body -> :request-params merge -> :body-transform -> marshal, in that order"
    (let [sent (atom nil)
          http (fn [_ _ body] (reset! sent body) {:status 200 :body "{\"answers\":{}}"})
          c    (jev/create-classifier
                {:http-client http
                 :request-params {:tenant "acme" :model "overridden-by-the-param"}
                 :body-transform (fn [b] (assoc b :stamped true))})]
      (jev/evaluate c "s" {"q" (jev/noul-question "?")})
      (let [b (json/read-str @sent {:key-fn str})]
        (is (= "acme" (get b "tenant")) "a param lands")
        (is (= "overridden-by-the-param" (get b "model")) "and WINS on collision")
        (is (true? (get b "stamped")) ":body-transform runs last and its return value is sent")))))

(deftest retries-reuse-the-section-8-policy
  (testing "a 429 retries within the budget and there is no second retry policy here"
    (let [n    (atom 0)
          http (fn [_ _ _]
                 (if (< (swap! n inc) 3)
                   {:status 429 :headers {"retry-after" "0"} :body "slow down"}
                   {:status 200 :body "{\"answers\":{},\"model\":\"m\"}"}))
          c    (jev/create-classifier {:http-client http :retries 3})]
      (is (= "m" (:model (jev/evaluate c "s" {"q" (jev/noul-question "?")}))))
      (is (= 3 @n))))
  (testing "a host :on-error verdict of :fail stops a retryable status dead"
    (let [n    (atom 0)
          http (fn [_ _ _] (swap! n inc) {:status 503 :body "nope"})
          c    (jev/create-classifier {:http-client http :retries 5
                                       :on-error (fn [_] :fail)})]
      (is (thrown? Throwable (jev/evaluate c "s" {"q" (jev/noul-question "?")})))
      (is (= 1 @n) "one attempt, because the host said fail"))))

(deftest the-retryable-status-matrix
  ;; `:retry-base-ms 1` keeps the suite from sleeping through a backoff it is
  ;; not testing — the wait is configured, not routed around with a faked header. The default set is an ENUMERATION on purpose: "any 5xx" would
  ;; sweep in permanently-broken statuses (501, 505) and silently change the
  ;; retry behaviour of every existing host. A backend with its own transient
  ;; status opts in through :retryable-statuses instead.
  (letfn [(attempts [status opts]
            (let [n    (atom 0)
                  http (fn [_ _ _]
                         (if (= 1 (swap! n inc))
                           {:status status :body "nope"}
                           {:status 200 :body "{\"answers\":{},\"model\":\"m\"}"}))
                  c    (jev/create-classifier
                        (merge {:http-client http :retries 2 :retry-base-ms 1} opts))]
              (try (jev/evaluate c "s" {"q" (jev/noul-question "?")})
                   (catch Throwable _ nil))
              @n))]
    (testing "529 Overloaded retries by default — TypeSafe documents it as retry-with-backoff"
      (is (= 2 (attempts 529 {}))))
    (testing "an unlisted 5xx and a permanently-broken one stay terminal by default"
      (is (= 1 (attempts 520 {})))
      (is (= 1 (attempts 501 {}))))
    (testing ":retryable-statuses is ADDITIVE — it widens, it never replaces"
      (let [cf {:retryable-statuses [520 521 522 523 524 525 526 527]}]
        (is (= 2 (attempts 520 cf)) "the Cloudflare status the host opted into retries")
        (is (= 2 (attempts 429 cf)) "429 STILL retries — the default set is not replaced")
        (is (= 1 (attempts 501 cf)) "a status nobody listed is still terminal")))
    (testing "a 4xx other than 429 stays terminal"
      (is (= 1 (attempts 422 {}))))
    (testing ":on-error :fail overrides a status the host itself listed"
      (is (= 1 (attempts 520 {:retryable-statuses [520] :on-error (fn [_] :fail)}))))))

(deftest a-typesafe-shaped-usage-block-reports-an-absent-cost
  ;; TypeSafe's own API returns model/answers/usage and no `cost` key at all.
  ;; Reporting 0 there would read as "this call was free" when the truth is
  ;; "this backend does not say".
  (let [http (fn [_ _ _]
               {:status 200
                :body (json/write-str
                       {"model" "jev-1.13.0"
                        "answers" {"q" {"type" "noul" "noul" 0.98}}
                        "usage" {"input_tokens" 331 "output_tokens" 48}})})
        c    (jev/create-classifier {:base-url "https://api.typesafe.ai/v1"
                                     :model "jev-latest"
                                     :http-client http})
        d    (jev/evaluate c "s" {"q" (jev/noul-question "?")})]
    (is (== 331 (get-in d [:usage :input-tokens])))
    (is (== 48 (get-in d [:usage :output-tokens])))
    (is (not (contains? (:usage d) :cost)) "absent is not zero")
    (is (nil? (get-in d [:usage :cost])))))

;; ---------------------------------------------------------------------------
;; the other backends
;; ---------------------------------------------------------------------------

(deftest custom-backend-is-the-hosts-own-function
  (let [c (jev/create-classifier
           {:style "custom"
            :evaluate (fn [state questions]
                        {:model "mine" :calibrated false
                         :answers {"q" {:type "noul" :noul (count questions)}}
                         :usage {:input-tokens 0 :output-tokens 0}
                         :echo state})})
        d (jev/evaluate c "the state" {"q" (jev/noul-question "?")})]
    (is (= "mine" (:model d)))
    (is (= "the state" (:echo d)))
    (is (== 1 (:noul (jev/noul d "q"))))))

(deftest llm-backend-is-never-calibrated
  (testing "the numbers are the model's self-report, not token probabilities"
    (let [reply "Here you go:\n```json\n{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.75}}}\n```"
          fake  (client/create-in-process-client
                 {:model "fake-llm" :generate (fn [_] {:content reply})})
          c     (jev/create-classifier {:style "llm" :client fake :model "fake-llm"})
          d     (jev/evaluate c "s" {"q" (jev/noul-question "?")})]
      (is (false? (:calibrated d)))
      (is (== 0.75 (:noul (jev/noul d "q")))))))

;; ---------------------------------------------------------------------------
;; construction
;; ---------------------------------------------------------------------------

(deftest create-classifier-applies-defaults-and-rejects-incomplete-styles
  (let [c (jev/create-classifier {})]
    (is (= "systemone" (:style c)))
    (is (= jev/default-base-url (:base-url c)))
    (is (= jev/default-model (:model c)))
    (is (= jev/default-api-key-env (:api-key-env c)))
    (is (= jev/default-timeout-ms (:timeout-ms c))))
  (is (thrown? Throwable (jev/create-classifier {:style "llm"}))
      "llm without :client fails at construction, not at the first call")
  (is (thrown? Throwable (jev/create-classifier {:style "custom"}))
      "custom without :evaluate fails at construction")
  (is (thrown? Throwable (jev/create-classifier {:style "nonsense"}))
      "an unknown style fails at construction"))

(deftest metrics-land-in-the-same-section-8-sink
  (let [f      (fixture "base")
        r      (get f "request")
        events (atom [])
        c      (static-for f {:on-metric (fn [ev] (swap! events conj ev))})]
    (jev/evaluate c (get r "state") (->questions r))
    (let [ev (first (filter (fn [e] (= jev/metric-evaluate (:event e))) @events))]
      (is (some? ev))
      (is (= "ok" (:status ev)))
      (is (= "typesafe/jev-1.13-20260917" (:model ev)))
      (is (== 398 (:prompt_tokens ev)))
      (is (== 72 (:completion_tokens ev)))
      (is (number? (:ms ev))))
    (testing "a failure emits status error, and the metric never carries a decision"
      (let [bad (jev/create-classifier {:style "static" :decisions [] :on-metric
                                        (fn [ev] (swap! events conj ev))})]
        (try (jev/evaluate bad "s" {"q" (jev/noul-question "?")}) (catch Throwable _ nil))
        (is (= "error" (:status (last @events))))))))

;; ---------------------------------------------------------------------------
;; a host that constructs no Classifier
;; ---------------------------------------------------------------------------

(deftest constructing-a-classifier-changes-no-client-request
  (testing "proven, not asserted (§8B): the §8 body is byte-identical either way"
    (let [capture (fn []
                    (let [sent (atom nil)
                          cl   (client/create-client
                                {:base-url "https://llm.example" :model "m" :api-key "k"
                                 :http-client (fn [_ _ body]
                                                (reset! sent body)
                                                {:status 200
                                                 :body (json/write-str
                                                        {:choices [{:message {:role "assistant"
                                                                              :content "done"}}]
                                                         :usage {}})})})]
                      (client/run cl "hello" {})
                      @sent))
          without (capture)
          _       (let [c (jev/create-classifier
                           {:style "custom"
                            :evaluate (fn [_ _] {:model "m" :answers {} :calibrated true})})]
                    (jev/evaluate c "s" {"q" (jev/noul-question "?")}))
          with    (capture)]
      (is (= without with)))))

(deftest retry-base-ms-scales-the-backoff
  ;; :retry-base-ms is the classifier's half of the §8 client option it mirrors:
  ;; the same default (500) and the same `base * 2^attempt` shape. The default is
  ;; asserted as a LOWER bound on a real wait, because an unset option that
  ;; silently became 1ms would pass every other test faster. No `retry-after`
  ;; header here — it wins over backoff and would hide the rule under test.
  (letfn [(waited [opts]
            (let [n    (atom 0)
                  http (fn [_ _ _]
                         (if (= 1 (swap! n inc))
                           {:status 503 :body "transient"}
                           {:status 200 :body "{\"answers\":{},\"model\":\"m\"}"}))
                  c    (jev/create-classifier (merge {:http-client http :retries 2} opts))
                  t0   (ktime/now-ms)]
              (jev/evaluate c "s" {"q" (jev/noul-question "?")})
              (is (= 2 @n) "the retry must actually have happened")
              (- (ktime/now-ms) t0)))]
    (testing "an unset base still backs off ~500ms"
      (is (>= (waited {}) 450)))
    (testing "a short base shortens the wait, not the behaviour"
      (is (< (waited {:retry-base-ms 1}) 200)))))

(deftest choice-over-sends-plain-option-names
  ;; §8B: a convenience constructor must not decorate the caller's ids. `str` on
  ;; a keyword keeps the sigil (`":billing"`), which is schema-valid, returns 200
  ;; and a well-formed distribution — while disagreeing with every other port
  ;; (Elixir's `to_string(:billing)` is "billing") and with the string the caller
  ;; then compares `(:choice answer)` against. A namespace is KEPT, because
  ;; dropping it collides `:a/x` and `:b/x` into one option.
  (let [q (jev/choice-over "Which desk?" {:billing "money moved" :shipping "a parcel is late"})]
    (is (= #{"billing" "shipping"} (set (keys (:criteria q))))))
  (testing "strings are untouched and symbols lose nothing"
    (is (= #{"billing" "ship"}
           (set (keys (:criteria (jev/choice-over "?" {"billing" "a" 'ship "b"})))))))
  (testing "a namespaced keyword keeps its namespace"
    (is (= #{"desk/billing"}
           (set (keys (:criteria (jev/choice-over "?" {:desk/billing "a"})))))))
  (testing "a keyword roster and its string equivalent are the SAME request"
    (is (= (jev/canonical-request "m" {"r" (jev/choice-over "?" {:a "x" :b "y"})})
           (jev/canonical-request "m" {"r" (jev/choice-over "?" {"a" "x" "b" "y"})})))))

(deftest absent-calibrated-decodes-as-true
  ;; §8B: the systemone wire reports calibration by being itself; a backend that
  ;; is not calibrated says so explicitly. Only the literal `false` is false, so
  ;; a host's tuned threshold cannot be flipped by a field it never set.
  (letfn [(cal [body]
            (let [c (jev/create-classifier {:http-client (fn [_ _ _] {:status 200 :body body})})]
              (:calibrated (jev/evaluate c "s" {"q" (jev/noul-question "?")}))))]
    (is (true?  (cal "{\"model\":\"m\",\"answers\":{}}"))                      "absent => true")
    (is (true?  (cal "{\"model\":\"m\",\"answers\":{},\"calibrated\":null}"))  "null => true")
    (is (true?  (cal "{\"model\":\"m\",\"answers\":{},\"calibrated\":true}")))
    (is (false? (cal "{\"model\":\"m\",\"answers\":{},\"calibrated\":false}")))))
