;; Gate item 1, Clojure: does the map key `:retries` (clojure/src/toolnexus/client.cljc
;; :520 `(let [budget (or (:retries client) 0)]`) distinguish "unset" from "explicit 0"?
;; NOTE: unlike every other port, Clojure's own default here is already 0, not 2 --
;; `create-client` (src/toolnexus/client.cljc ~line 126-178) never normalizes :retries
;; at all; only `post-with-retry` applies `(or (:retries client) 0)`. Since `or` in
;; Clojure only falls through on nil/false and 0 is truthy, `(:retries client)` being
;; present-as-0 or absent-as-nil both currently resolve to the SAME budget (0), so this
;; port cannot even exhibit the "0 silently becomes 2" bug the ADR is about -- its
;; documented default deviates from the other six ports already (separate finding,
;; not this ADR's target). Prove both paths with a real client + injected :http-client.

(require '[toolnexus.core :as core])
(require '[toolnexus.client :as client])

(def calls (atom 0))

(defn failing-http-client [_url _headers _body]
  (swap! calls inc)
  {:status 500 :body "{\"error\":\"boom\"}"})

(def toolkit (core/build {}))

;; explicit zero
(reset! calls 0)
(def client0
  (client/create-client
   {:base-url "http://example.invalid"
    :style "openai"
    :model "test-model"
    :api-key "x"
    :retries 0
    :http-client failing-http-client}))

(try
  (client/run client0 "hi" {:toolkit toolkit})
  (catch Exception _ :expected))

(println "calls with :retries 0 ->" @calls)
(when (not= 1 @calls)
  (println "FAIL: expected 1 call, got" @calls)
  (System/exit 1))

;; unset
(reset! calls 0)
(def client1
  (client/create-client
   {:base-url "http://example.invalid"
    :style "openai"
    :model "test-model"
    :api-key "x"
    ;; no :retries key at all
    :http-client failing-http-client}))

(try
  (client/run client1 "hi" {:toolkit toolkit})
  (catch Exception _ :expected))

(println "calls with :retries UNSET ->" @calls)
(when (not= 1 @calls)
  (println "FAIL: expected 1 call (this port's own default is 0, not 2), got" @calls)
  (System/exit 1))

(println "CLOJURE VERDICT: explicit 0 and unset are indistinguishable in EFFECT here only because this port's shipped default is already 0 (not the documented 2) -- a pre-existing parity deviation, unrelated to ADR-0023's `0 => 2` premise. `(or 0 0)` is truthy-safe either way; no -1 sentinel is needed to make retries=0 work.")
