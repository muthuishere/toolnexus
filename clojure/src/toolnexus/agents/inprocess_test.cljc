;; ADR 0024 — the in-process seam stops at the top-level client, and the fix:
;; `toolnexus.client/in-process-http-client` is now PUBLIC so `create-runtime`'s
;; new `:in-process` option can call it directly (zero duplicated adapter
;; logic). Mirrors golang/agents/inprocess_test.go — the reference port.
;;
;; No java.*, no `future`, no reader conditionals.
(ns toolnexus.agents.inprocess-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.time :as ktime]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]))

(defn- adef [nm model & {:as extra}]
  (merge {:name nm :does (str nm " does things") :model model} extra))

(defn- bare-toolkit [] (toolnexus/build {:builtins false}))

;; ===========================================================================
;; 1. ONE generate function serves both constructors, with no copied adapter
;;    code — both the top-level client and the sub-agent runtime end up
;;    calling the SAME `toolnexus.client/in-process-http-client`.
;; ===========================================================================

(deftest one-generate-function-serves-both-the-top-level-client-and-a-sub-agent-runtime
  (let [calls (atom [])
        ;; A single semantic `generate`, shared verbatim by both constructors.
        shared-generate (fn [req]
                          (swap! calls conj (:model req))
                          {:content (str "answer for " (:model req))})

        ;; Path A: the existing top-level constructor.
        top-client (client/create-in-process-client
                    {:model "top" :generate shared-generate})
        top-result (client/run top-client "hi" {:toolkit (bare-toolkit)})

        ;; Path B: the NEW agent-runtime `:in-process` option — the same fn,
        ;; unmodified, handed to `create-runtime` instead of `create-client`.
        rt (rt/create-runtime {:registry {"sub" (adef "sub" "sub")}
                               :in-process shared-generate})
        h  (rt/spawn rt rt/root "sub")
        _  (rt/wake rt h "go")
        sub-result (rt/wait rt h)]
    (is (= "answer for top" (:text top-result)))
    (is (= "answer for sub" (:text sub-result)))
    (is (= ["top" "sub"] @calls)
        "the SAME generate fn served both a top-level client turn and a sub-agent turn")))

;; ===========================================================================
;; 2. Construction-time validation — mutually exclusive, never resolved by
;;    precedence. Both `:http-client` and `:llm` are each individually
;;    incompatible with `:in-process`.
;; ===========================================================================

(deftest in-process-and-http-client-are-mutually-exclusive-at-construction
  (is (thrown-with-msg?
       Throwable
       #":http-client and :in-process are mutually exclusive"
       (rt/create-runtime {:registry {"w" (adef "w" "m")}
                           :in-process (fn [_] {:content "x"})
                           :http-client (fn [_ _ _] {:status 200 :body "{}"})}))))

(deftest in-process-and-llm-are-mutually-exclusive-at-construction
  (is (thrown-with-msg?
       Throwable
       #":llm and :in-process are mutually exclusive"
       (rt/create-runtime {:registry {"w" (adef "w" "m")}
                           :in-process (fn [_] {:content "x"})
                           :llm {:base-url "http://mock.local" :model "m"}}))))

;; ===========================================================================
;; 3. The global turn gate (gate 3) still wraps the `:in-process`-derived
;;    transport — no special-casing. Negative control: gate 1 observes ZERO
;;    overlaps; gate 5 (same work) DOES observe overlaps, proving the
;;    detector is not a tautology.
;; ===========================================================================

(deftest the-turn-gate-bounds-concurrent-in-process-calls
  (testing "gate 1 serializes in-process generate calls; a wider gate lets them overlap"
    (let [measure
          (fn [gate-size]
            (let [in-flight (atom 0)
                  peak (atom 0)
                  overlap-observed (atom false)
                  generate (fn [_req]
                             (let [n (swap! in-flight inc)]
                               (swap! peak max n)
                               (when (> n 1) (reset! overlap-observed true))
                               (ktime/sleep! 60)
                               (swap! in-flight dec)
                               {:content "ok"}))
                  rt (rt/create-runtime {:registry {"w" (adef "w" "m")}
                                         :in-process generate
                                         :max-concurrent-turns gate-size})
                  hs (mapv (fn [_] (rt/spawn rt rt/root "w")) (range 5))]
              (doseq [h hs] (rt/wake rt h "go"))
              (doseq [h hs] (rt/wait rt h))
              [@peak @overlap-observed (:max-observed (rt/gate-stats rt))]))]
      (let [[peak overlap? max-observed] (measure 1)]
        (is (= 1 peak) "gate 1: never more than one in-process call in flight")
        (is (false? overlap?) "negative control — zero overlaps observed under gate 1")
        (is (= 1 max-observed)))
      (let [[peak overlap? _max-observed] (measure 5)]
        (is (< 1 peak)
            "positive control — gate 5 on the same work DOES let calls overlap, so the gate-1 result is not a tautology")
        (is (true? overlap?))))))
