;; A CLI-backed `generate` for toolnexus's Clojure `create-in-process-client`
;; (ADR 0026 shape), wired into the REAL clojure/ port unmodified — same
;; single `.cljc` source, NO reader conditionals, loaded on BOTH hosts (JVM
;; Clojure and cljgo) to answer the task's explicit dual-host question.
;;
;; Uses `koine.process/sh` — the ONE-SHOT form (koine also ships `spawn` for a
;; long-lived piped child, which is what an ACP adapter would need instead;
;; ADR 0026's CLI source is one-shot-per-turn, so `sh` is the right primitive
;; here, already proven dual-host by the port's own `bash` builtin tool,
;; clojure/src/toolnexus/builtin.cljc).
;;
;; Envelope: `<openai_request>{body}</openai_request>` written to a prompt
;; FILE (never argv — matches ADR 0026's stated preference), CLI writes
;; `<openai_response>{...}</openai_response>` to an --out FILE. Response is
;; strictly parsed: dispatch on whether `message.tool_calls` is populated,
;; never on `finish_reason`.
(ns toolnexus.climodel
  (:require [koine.process :as proc]
            [koine.json :as json]
            [koine.fs :as fs]
            [clojure.string :as str]))

(defn- extract-envelope [tag s]
  (let [open  (str "<" tag)
        close (str "</" tag ">")
        start (str/index-of s open)
        gt    (when start (str/index-of s ">" start))
        end   (when gt (str/index-of s close gt))]
    (when (and gt end)
      (subs s (inc gt) end))))

(defn make-cli-generate
  "Returns a synchronous `generate` fn (clojure's create-in-process-client
  calls it synchronously, same as every other port). `opts`:
    :fakecli-path  path to fakecli.py
    :python-exe    interpreter to run it with (defaults \"python3\")"
  [{:keys [fakecli-path python-exe] :or {python-exe "python3"}}]
  (fn [req]
    (let [body      (:body req)
          envelope  (str "<openai_request endpoint=\"/v1/chat/completions\">"
                          (json/write-str body) "</openai_request>")
          tmp-dir   (fs/temp-dir! "toolnexus-climodel-")
          prompt-fp (str tmp-dir "/prompt.txt")
          out-fp    (str tmp-dir "/out.txt")
          _         (fs/write-file prompt-fp envelope)
          model     (or (get body :model) "")
          result    (proc/sh [python-exe fakecli-path
                               "--prompt-file" prompt-fp
                               "--out" out-fp
                               "--model" (str model)]
                              {:timeout-ms 10000})]
      (when (:timed-out? result)
        (throw (ex-info "climodel: CLI timed out" {:result result})))
      (when (not= 0 (:exit result))
        (throw (ex-info (str "climodel: CLI exited " (:exit result) ": " (:err result)) {:result result})))
      (let [raw-out (fs/read-file out-fp)
            payload (extract-envelope "openai_response" raw-out)]
        (when-not payload
          (throw (ex-info (str "climodel: no <openai_response> envelope in CLI output: " raw-out) {})))
        ;; koine.json/read-str keywordizes keys (matches every other read-str
        ;; call in the real port, e.g. client.cljc's in-process-http-client) —
        ;; string-keyed `get` here silently returned nil on every field and is
        ;; exactly the kind of drift a portability spike exists to catch.
        (let [parsed     (json/read-str payload)
              choice     (first (get parsed :choices))
              message    (get choice :message)
              tool-calls (get message :tool_calls)]
          (if (seq tool-calls)
            {:tool-calls
             (mapv (fn [c]
                     (let [fn-obj (get c :function)
                           args   (get fn-obj :arguments)]
                       (when-not (string? args)
                         (throw (ex-info "climodel: arguments must be a JSON-encoded string" {:args args})))
                       {:id (get c :id) :name (get fn-obj :name) :arguments args}))
                   tool-calls)}
            {:content (or (get message :content) "")}))))))
