;; SPEC §0.8 / §6 — native tools (source: "native").
;;
;; "fn -> Tool; string return => output, throw/err => isError." That is the
;; whole contract, and it is deliberately the smallest namespace in the port.
;;
;; Zero reader conditionals, zero java.*: `Throwable` is a bare symbol, which is
;; the one catch target both hosts accept (koine's own rule — throw `ex-info`,
;; catch `Throwable`).
(ns toolnexus.native
  (:require [toolnexus.tool :as tool]))

(defn- normalize
  "Whatever the user's fn returned, as a ToolResult (§1).

  - a ToolResult map (has :output) passes through, with :isError forced boolean
  - nil        => empty output   (a fn that only performs an effect)
  - anything else is `str`-ed, which covers §6's 'a plain string => output'"
  [v]
  (cond
    (and (map? v) (contains? v :output))
    (assoc v :output (str (:output v)) :isError (true? (:isError v)))

    (nil? v) (tool/success "")
    :else    (tool/success v)))

(defn native-tool
  "Build a native Tool from a plain function.

  Map form (preferred):

      (native-tool {:name \"upper\" :description \"Uppercase\"
                    :input-schema {:type \"object\"}
                    :run (fn [args] (str/upper-case (:text args)))})

  `:run` receives ONE argument — the args map. A tool that needs the §1
  Context declares `:ctx? true` and receives two:

      (native-tool {:name \"ask\" :run (fn [args ctx] …) :ctx? true})

  PORTABILITY NOTE — why the flag exists. JS/Python/Go can hand `run` both
  values and let the callee ignore the extra one; Clojure cannot. Arity
  introspection is `java.lang.reflect` on the JVM and absent on cljgo, so there
  is no portable way to ask a fn how many arguments it takes. The flag is the
  honest version of what the other ports get for free. It costs a keyword and
  never guesses wrong."
  ([m]
   (let [{:keys [name description input-schema run ctx?]} m
         f (if ctx? run (fn [args _ctx] (run args)))]
     (tool/tool
       {:name         name
        :description  description
        :input-schema input-schema
        :source       "native"
        :execute      (fn
                        ([args] (normalize (f args nil)))
                        ([args ctx] (normalize (f args ctx))))})))
  ([tool-name description input-schema run]
   (native-tool {:name tool-name :description description
                 :input-schema input-schema :run run})))

;; NOTE on `throw => isError` (§0.8): the conversion lives in
;; `toolnexus.tool/execute`, which already wraps every tool call in
;; `(catch Throwable …)` and returns `err`. Catching a second time here would
;; make a native tool the ONE source whose throw never reaches the toolkit's
;; boundary rule, which is exactly the kind of per-source special case the spec
;; exists to prevent. `execute-native` below is the direct-call path, for a host
;; that holds a Tool without a toolkit; it applies the same rule.

(defn execute-native
  "Call a Tool's :execute directly, with §0.8's throw-becomes-isError rule
  applied. Equivalent to `toolnexus.tool/execute` on a one-tool toolkit."
  ([t args] (execute-native t args nil))
  ([t args ctx]
   (try
     (if ctx ((:execute t) args ctx) ((:execute t) args))
     (catch Throwable e
       (tool/failure (or (ex-message e) (str e)))))))
