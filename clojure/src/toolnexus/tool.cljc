;; The uniform abstraction every tool source collapses into (SPEC §0.1).
;;
;; A Tool is a plain map. That is deliberate and not laziness: a map of data
;; plus one closure needs nothing but `fn`, `get` and `assoc`, so it ports to
;; any Clojure without a protocol, a record or a deftype — none of which behave
;; identically across hosts.
(ns toolnexus.tool
  (:require [clojure.string :as str]
            [koine.text :as text]))

(defn sanitize
  "SPEC §0.2 — replace [^a-zA-Z0-9_-] with _."
  [s]
  (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))

(defn tool
  "Build a Tool. `execute` takes (args) or (args ctx) and returns a ToolResult.

  Two optional keys (SPEC §4C, change add-go-host-asks):

    :hints     {:read-only :destructive :open-world} — advisory behaviour hints
               for a host permission gate; passed on `:before-tool` events as
               `:hints`. nil = unknown (treat as mutating). Never serialized into
               any adapter output.
    :concurrency-safe  (fn [args] bool) — decides per call whether this tool may
               run alongside other safe calls of one model response (SPEC §8
               Tool scheduling). Absent ⇒ safe only when `:hints :read-only`.
    :output-schema  JSON Schema of the result's `:structured` (SPEC §1);
               MCP serve advertises it as `outputSchema`.
    :annotations    MCP tool annotations, wire-named:
               {:title :readOnlyHint :destructiveHint :idempotentHint
                :openWorldHint} — passed verbatim by `mcp-handler` (§7C.1).
    :context?  true when `execute` has the (args ctx) arity and wants the loop's
               ToolContext on EVERY call — `{:call-id :cancel}` (plus `:answer`
               on a §10 re-run). Absent, the loop calls (execute args) exactly as
               before: there is no portable way to ask a fn its arity, and a
               single-arity tool handed a ctx would fail."
  [{:keys [name description input-schema source execute hints context? concurrency-safe
           output-schema annotations]}]
  {:pre [(some? name) (fn? execute)]}
  (cond-> {:name        (str name)
           :description (or description "")
           :input-schema (or input-schema {:type "object"})
           :source      (or source "custom")
           :execute     execute}
    hints    (assoc :hints hints)
    context? (assoc :context? true)
    concurrency-safe (assoc :concurrency-safe concurrency-safe)
    ;; SPEC §1 / §7C.1 — both absent ⇒ the map is byte-identical to before.
    output-schema (assoc :output-schema output-schema)
    annotations (assoc :annotations annotations)))

(defn concurrency-safe?
  "SPEC §8 \"Tool scheduling\" — may a call to tool `t` with `args` run alongside
  other safe calls of the same model response? The tool's own `:concurrency-safe`
  predicate wins; otherwise only a tool whose hints say `:read-only`. A tool
  without hints (or nil) is unsafe."
  [t args]
  (cond
    (nil? t) false
    (:concurrency-safe t) (true? ((:concurrency-safe t) args))
    :else (true? (get-in t [:hints :read-only]))))

(defn success
  "A successful ToolResult (SPEC §0.1).

  NOT named `ok`: cljgo's clojure.core HAS `ok` and `err` (the JVM's does not),
  so `(defn ok ...)` shadows a core name on one host only — measured 2026-07-31.
  It warns today; per cljgo's static interop scan a bare core-shaped symbol can
  reject the WHOLE namespace, and a hazard that fires on one host and not the
  other is the exact thing this port exists to avoid."
  ([output] (success output nil))
  ([output metadata]
   (cond-> {:output (str output) :isError false}
     metadata (assoc :metadata metadata))))

(defn failure
  "A failed ToolResult. Note a tool ERROR is a VALUE handed back to the model,
  not a thrown exception — the model is meant to see it and try again.
  NOT named `err`, for the reason given on `success`."
  ([output] (failure output nil))
  ([output metadata]
   (cond-> {:output (str output) :isError true}
     metadata (assoc :metadata metadata))))

(defn with-structured
  "SPEC §1 — attach a machine-readable `structured` value (MCP
  `structuredContent`) and/or wire `meta` (MCP `_meta`). nil ⇒ unchanged."
  ([result structured] (with-structured result structured nil))
  ([result structured meta]
   (cond-> result
     (some? structured) (assoc :structured structured)
     (some? meta)       (assoc :meta meta))))

(defn with-parts
  "SPEC §1B — attach non-text `parts` to a ToolResult.

  `cond->`, so an EMPTY or absent parts list leaves the result byte-identical to
  what `success`/`failure` produced. That is the whole compatibility story: the
  52 construction sites in this port are untouched, and a text-only tool's JSON
  does not move by a byte.

  `output` stays REQUIRED and stays what the transcript, compaction, token
  estimation and any text-only provider see — a tool returning an image sets
  `output` to a description and `parts` to the image."
  [result parts]
  (cond-> result
    (seq parts) (assoc :parts (vec parts))))


;; --------------------------------------------------------------------------
;; the toolkit
;; --------------------------------------------------------------------------

(def ^:private toolkit-counter (atom 0))

(defn toolkit
  "A toolkit is {:tools {name -> Tool} :sources {source -> status}}.
  Later tools win on a name collision, which is how SPEC §0.11's MCP precedence
  over builtins is expressed: register builtins first, MCP after.

  Each toolkit carries an IDENTITY in its metadata (`toolkit-id`), kept by
  `add-tools`/`assoc`/`update` and ignored by `=`. A toolkit is a value, so a
  later registration is a new value — the identity is what lets a client freeze
  one SPEC §4C tool-tier plan per toolkit across those values."
  ([tools] (toolkit tools {}))
  ([tools sources]
   (with-meta
     {:tools   (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools)
      :sources sources}
     {::id (swap! toolkit-counter inc)})))

(defn toolkit-id
  "The toolkit's identity (see `toolkit`), or nil for a hand-built map."
  [tk]
  (::id (meta tk)))

(defn add-tools [tk tools]
  (update tk :tools #(reduce (fn [acc t] (assoc acc (:name t) t)) % tools)))

(def code-points
  "`s` as a vector of Unicode code points — `koine.text/code-points`.

  Lived here first: this port found the divergence (nine sites sorting
  differently on the two hosts) and carried the fix locally until koine 0.11.0
  lifted it into the seam, where a clojure.core divergence belongs. The vars
  stay so call sites and tests read `tool/…`, but there is ONE implementation."
  text/code-points)

(def compare-strings
  "Code-point string comparison, identical on both hosts — `koine.text/compare-strings`.
  See `code-points` for why this delegates."
  text/compare-strings)

(defn last-index-of-char
  "Index of the last `ch` in `s`, or nil — scanned with `count`/`nth` instead of
  `clojure.string/last-index-of`.

  THIS IS A HOST DIVERGENCE, and it belongs in the seam beside `compare-strings`
  for the same reason that one does. On the cljgo host `last-index-of` returns a
  BYTE offset while `subs`, `count` and `nth` work in RUNES, so for any string
  containing a non-ASCII character the index and the slice disagree and the cut
  lands mid-character-sequence:

      (parent-dir \"/base/\\uE000dir/SKILL.md\")  =>  \"/base/\\uE000dir/S\"   on cljgo
      (base-name  \"/docs/\\uD83D\\uDE00.pdf\")       =>  \".pdf\" mis-sliced   on cljgo

  U+E000 is 3 UTF-8 bytes and 1 rune, so the index runs 2 too high; an astral
  character is 4 bytes and 1 rune, so it runs 3 too high. On the JVM host
  `last-index-of` agrees with `subs` and nothing is wrong, which is exactly why
  this survived: every path in every fixture was ASCII. `classifier.cljc` had
  already recorded that `last-index-of` was never proven on cljgo.

  `count`, `nth` and `subs` are all in the SAME units as each other on each host
  (UTF-16 units on the JVM, runes on cljgo), so a scan is correct on both
  without either host needing to know which unit it is in."
  [s ch]
  (let [s (str s)]
    (loop [i (dec (count s))]
      (cond (neg? i)         nil
            (= ch (nth s i)) i
            :else            (recur (dec i))))))

(defn sort-strings
  "`coll` sorted by `compare-strings` — `koine.text/sort-strings`, returned as a
  vector because every caller here treats tool lists as vectors."
  [coll]
  (vec (text/sort-strings coll)))

(defn tool-names
  "Sorted, always. Two runtimes must not disagree on order — which is why this
  uses `compare-strings` and not `sort`; see there."
  [tk]
  (sort-strings (keys (:tools tk))))

(defn all-tools [tk]
  (mapv #(get (:tools tk) %) (tool-names tk)))

(defn execute
  "Run a tool by name. A THROW from a tool becomes an error ToolResult — a
  misbehaving tool must not take down the loop (SPEC §0.8)."
  ([tk name args] (execute tk name args nil))
  ([tk name args ctx]
   (if-let [t (get (:tools tk) name)]
     (try
       (let [f (:execute t)]
         (if ctx (f args ctx) (f args)))
       ;; `Throwable` is a bare symbol, not a java.* class name, so cljgo
       ;; accepts it. This is what lets §0.8 be written once for both hosts.
       (catch Throwable e
         (failure (or (ex-message e) (str e)))))
     (failure (str "unknown tool: " name)))))
