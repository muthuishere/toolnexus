;; The uniform abstraction every tool source collapses into (SPEC §0.1).
;;
;; A Tool is a plain map. That is deliberate and not laziness: a map of data
;; plus one closure needs nothing but `fn`, `get` and `assoc`, so it ports to
;; any Clojure without a protocol, a record or a deftype — none of which behave
;; identically across hosts.
(ns toolnexus.tool
  (:require [clojure.string :as str]))

(defn sanitize
  "SPEC §0.2 — replace [^a-zA-Z0-9_-] with _."
  [s]
  (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))

(defn tool
  "Build a Tool. `execute` takes (args) or (args ctx) and returns a ToolResult."
  [{:keys [name description input-schema source execute]}]
  {:pre [(some? name) (fn? execute)]}
  {:name        (str name)
   :description (or description "")
   :input-schema (or input-schema {:type "object"})
   :source      (or source "custom")
   :execute     execute})

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

;; --------------------------------------------------------------------------
;; the toolkit
;; --------------------------------------------------------------------------

(defn toolkit
  "A toolkit is {:tools {name -> Tool} :sources {source -> status}}.
  Later tools win on a name collision, which is how SPEC §0.11's MCP precedence
  over builtins is expressed: register builtins first, MCP after."
  ([tools] (toolkit tools {}))
  ([tools sources]
   {:tools   (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools)
    :sources sources}))

(defn add-tools [tk tools]
  (update tk :tools #(reduce (fn [acc t] (assoc acc (:name t) t)) % tools)))

(defn code-points
  "`s` as a vector of Unicode CODE POINTS, on either host.

  `seq` over a string is NOT portable: the JVM yields UTF-16 code units, so
  \"\uD83D\uDE00\" arrives as two, while cljgo yields one rune. Folding a
  surrogate pair back into its code point makes both hosts produce the same
  vector, and cljgo — which never presents surrogates — passes straight through."
  [s]
  (loop [cs (seq (str s)) out []]
    (if-let [c (first cs)]
      (let [v  (int c)
            lo (when (second cs) (int (second cs)))]
        (if (and (<= 0xD800 v 0xDBFF) lo (<= 0xDC00 lo 0xDFFF))
          (recur (nnext cs)
                 (conj out (+ 0x10000 (bit-shift-left (- v 0xD800) 10) (- lo 0xDC00))))
          (recur (next cs) (conj out v))))
      out)))

(defn compare-strings
  "Compare two strings by CODE POINT, lexicographically, identically on both hosts.

  `sort` alone is host-dependent above the BMP: the JVM orders by UTF-16 code
  unit (a surrogate `D83D` sorts below `E000`) and cljgo orders by UTF-8 byte
  (`F0` sorts above `EE`), so `(sort [\"\uD83D\uDE00\" \"\uE000\"])` returns
  opposite answers. That fed §0.6's byte-exact `<skill_files>` block, the §3
  not-found list, adapter order and `glob` — so \"byte-identical on both hosts\"
  held only while every name stayed inside the BMP.

  Comparing `code-points` vectors with `compare` would not do: Clojure orders
  vectors by LENGTH first, so a 1-element vector sorts below any 2-element one
  regardless of content. This walks them element-wise, then by length — the
  lexicographic order Go, Python and Elixir already produce.

  CROSS-PORT NOTE: js and java sort by UTF-16 code unit, which differs from this
  above the BMP. The seven ports therefore do not all agree for non-BMP names.
  Recorded rather than silently matched: this port is at least self-consistent
  across its own two hosts, which it was not before."
  [a b]
  (let [xs (code-points a) ys (code-points b)]
    (loop [i 0]
      (cond
        (and (= i (count xs)) (= i (count ys))) 0
        (= i (count xs)) -1
        (= i (count ys)) 1
        :else (let [x (nth xs i) y (nth ys i)]
                (if (= x y) (recur (inc i)) (if (< x y) -1 1)))))))

(defn sort-strings
  "`coll` sorted by `compare-strings` — the portable order for every surface the
  spec calls byte-exact."
  [coll]
  (vec (sort compare-strings coll)))

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
