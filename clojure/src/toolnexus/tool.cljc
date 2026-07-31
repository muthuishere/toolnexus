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

(defn ok
  "A successful ToolResult (SPEC §0.1)."
  ([output] (ok output nil))
  ([output metadata]
   (cond-> {:output (str output) :isError false}
     metadata (assoc :metadata metadata))))

(defn err
  "A failed ToolResult. Note a tool ERROR is a VALUE handed back to the model,
  not a thrown exception — the model is meant to see it and try again."
  ([output] (err output nil))
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

(defn tool-names
  "Sorted, always. Two runtimes must not disagree on order."
  [tk]
  (vec (sort (keys (:tools tk)))))

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
         (err (or (ex-message e) (str e)))))
     (err (str "unknown tool: " name)))))
