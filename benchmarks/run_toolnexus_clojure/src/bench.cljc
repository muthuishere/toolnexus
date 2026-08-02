;; toolnexus (Clojure port) benchmark runner — ONE file, BOTH hosts.
;;
;; Measures, against the shared mock LLM + the shared stdio MCP server:
;;   * cold init  = build the toolkit (spawn MCP, discover tools) + build client
;;   * per-request wall time for the fixed scenario
;; and prints ONE JSON line. Peak RSS is captured by the orchestrator
;; (`/usr/bin/time -l`) wrapping this process.
;;
;; The framework name reports the HOST, not just the port:
;;   toolnexus-clojure-jvm     `clojure -M -m bench`      (Clojure on the JVM)
;;   toolnexus-clojure-cljgo   `./bench`                  (cljgo AOT binary)
;; Same source, same koine, same toolkit — only the runtime differs. Both
;; numbers are published; the JVM's cold init includes JVM startup and that is
;; real, not an artefact to excuse away.
;;
;; PORTABILITY RULES this file obeys (they are why it runs on both hosts):
;; no `java.*` interop, no reader conditionals, no `future` — everything
;; host-shaped goes through koine.
;;
;; ---------------------------------------------------------------------------
;; The one methodological difference from the other ports, stated up front
;; ---------------------------------------------------------------------------
;; koine's portable monotonic clock, `koine.time/mono-ms`, has MILLISECOND
;; resolution on both hosts. The scenario itself costs ~1 ms, so timing single
;; runs would quantise every sample to 0/1/2 ms and report noise as signal. A
;; sub-millisecond timer exists on each host (System/nanoTime, cljg.date/nano-time)
;; but reaching for either means host-specific code in a file whose whole claim
;; is that it has none.
;;
;; So a SAMPLE here is a batch of `BENCH_BATCH` (default 10) consecutive
;; scenario runs, and the reported latency is that batch's wall time divided by
;; the batch size — 0.1 ms effective resolution. Mean is unaffected. p50 and p95
;; are percentiles over BATCH MEANS, so they are smoother than the other ports'
;; per-run percentiles: a single slow run is averaged across its batch instead
;; of standing alone in the tail. Read the Clojure p95 as "the 95th-percentile
;; 10-run stretch", not "the 95th-percentile request". Disclosed on the results
;; page for the same reason it is disclosed here.
(ns bench
  (:require [koine.env :as env]
            [koine.host :as host]
            [koine.json :as json]
            [koine.time :as time]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]))

(def question "What's the weather in Paris and what is 2+2?")

(defn- getenv [k d]
  (let [v (env/get-env k)] (if (or (nil? v) (= "" v)) d v)))

(def ^:private digits
  {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})

(defn- int-env
  "Parse a non-negative integer out of the environment. Digit-by-digit rather
  than `Integer/parseInt`, which is `java.*` interop and would not compile on
  cljgo — the same rule the port itself lives by. Anything unparseable falls
  back to the default instead of throwing: a benchmark should not die on a typo
  in an env var, it should run the documented default and say what it ran."
  [k d]
  (let [v (getenv k nil)
        cs (seq (or v ""))]
    (if (and (seq cs) (every? (fn [c] (contains? digits c)) cs))
      (reduce (fn [acc c] (+ (* 10 acc) (get digits c))) 0 cs)
      d)))

(defn- mcp-config [repo mcp-python]
  {:mcpServers
   {:bench-tools {:type    "local"
                  :command [mcp-python (str repo "/benchmarks/mcp_server.py")]
                  :enabled true
                  :timeout 30000}}})

(defn- multiply-tool []
  (native/native-tool
   {:name         "multiply"
    :description  "Multiply two integers locally."
    :input-schema {:type       "object"
                   :properties {:a {:type "integer"} :b {:type "integer"}}
                   :required   ["a" "b"]}
    :run          (fn [args] (str (* (long (:a args)) (long (:b args)))))}))

(defn- weather-tool []
  (native/native-tool
   {:name         "get_weather"
    :description  "Get the weather for a city."
    :input-schema {:type       "object"
                   :properties {:city {:type "string"}}
                   :required   ["city"]}
    :run          (fn [args] (str "Weather in " (:city args) ": Sunny, 22C"))}))

(defn- add-tool []
  (native/native-tool
   {:name         "add"
    :description  "Add two integers."
    :input-schema {:type       "object"
                   :properties {:a {:type "integer"} :b {:type "integer"}}
                   :required   ["a" "b"]}
    :run          (fn [args] (str (+ (long (:a args)) (long (:b args)))))}))

(defn- build-toolkit
  "mcp    : only the 3 MCP tools (builtins off)            -> apples-to-apples
   full   : 3 MCP tools + 1 agent skill + 1 native tool    -> the real shape
   native : 2 in-process tools, no MCP subprocess          -> loop overhead only"
  [config repo mcp-python]
  (cond
    (= config "native")
    (toolnexus/build {:builtins false :tools [(weather-tool) (add-tool)]})

    (= config "full")
    (toolnexus/build {:mcp      (mcp-config repo mcp-python)
                      :skills   (str repo "/examples/skills")
                      :tools    [(multiply-tool)]
                      :builtins false})

    :else
    (toolnexus/build {:mcp (mcp-config repo mcp-python) :builtins false})))

(defn- round3 [x]
  (/ (double (long (+ 0.5 (* (double x) 1000.0)))) 1000.0))

(defn -main [& _]
  (let [repo    (getenv "BENCH_REPO" ".")
        mcp-py  (getenv "MCP_PYTHON" (getenv "PYTHON" "python3"))
        mock    (getenv "MOCK_URL" "http://127.0.0.1:8900")
        config  (getenv "BENCH_CONFIG" "mcp")
        runs    (int-env "BENCH_RUNS" 30)
        warmup  (int-env "BENCH_WARMUP" 5)
        batch   (int-env "BENCH_BATCH" 10)

        ;; ---- cold init: toolkit build (MCP connect + discovery) + client ----
        t0      (time/mono-ms)
        tk      (build-toolkit config repo mcp-py)
        agent   (client/create-client {:base-url mock :style "openai" :model "mock-model"})
        init-ms (time/elapsed-ms t0)

        tools   (count (toolnexus/tool-names tk))]

    ;; ---- warmup ----
    (dotimes [_ warmup]
      (client/run agent question {:toolkit tk}))

    ;; ---- measured: `runs` samples, each the mean of `batch` scenario runs ----
    (let [final (atom "")
          lat   (vec (for [_ (range runs)]
                       (let [s (time/mono-ms)]
                         (dotimes [_ batch]
                           (reset! final (:text (client/run agent question {:toolkit tk}))))
                         (round3 (/ (double (time/elapsed-ms s)) (double batch))))))]
      (toolnexus/shutdown! tk)
      (println
       (json/write-str
        {:framework    (str "toolnexus-clojure-" (name host/id) "-" config)
         :language     "clojure"
         :host         (name host/id)
         :init_ms      init-ms
         :tool_count   tools
         :batch        batch
         :latencies_ms lat
         :final_text   @final})))))
