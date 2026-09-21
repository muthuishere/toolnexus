;; Provider adapters (SPEC §0.7) — turn the uniform tool list into each LLM's
;; tool schema. Execution is identical for every provider: read the tool name +
;; args the model returned, call `toolnexus.tool/execute`, feed the output back.
;;
;; Schema only. Nothing here touches a network, a key or a host.
;;
;; ZERO reader conditionals. ZERO `java.*`.
(ns toolnexus.adapter
  (:require [toolnexus.tool :as tool]))

(defn tool-seq
  "Accept either a toolkit (`{:tools … :sources …}`) or an already-ordered seq
  of Tools, and return a DETERMINISTIC seq of Tools.

  A toolkit is name-keyed, and map order is not a contract on either host, so a
  toolkit goes through `tool/all-tools`, which sorts by name. An explicit seq is
  the caller's order and is left exactly as given — that is the only way a
  caller can pin a non-alphabetical tool order."
  [tools-or-toolkit]
  (if (and (map? tools-or-toolkit) (contains? tools-or-toolkit :tools))
    (tool/all-tools tools-or-toolkit)
    (vec tools-or-toolkit)))

(defn to-openai
  "SPEC §0.7 — `{type:\"function\", function:{name, description, parameters}}`."
  [tools-or-toolkit]
  (mapv (fn [t]
          {:type     "function"
           :function {:name        (:name t)
                      :description (:description t)
                      :parameters  (:input-schema t)}})
        (tool-seq tools-or-toolkit)))

(defn to-anthropic
  "SPEC §0.7 — `{name, description, input_schema}`. Note the snake_case key:
  it is the wire name, not a Clojure convention, and must not be kebab-ised."
  [tools-or-toolkit]
  (mapv (fn [t]
          {:name         (:name t)
           :description  (:description t)
           :input_schema (:input-schema t)})
        (tool-seq tools-or-toolkit)))

(defn to-gemini
  "SPEC §0.7 — `[{functionDeclarations:[{name, description, parameters}]}]`.
  ALWAYS a one-element vector, even with no tools: every shipped port emits the
  wrapper with an empty `functionDeclarations` array rather than `[]`."
  [tools-or-toolkit]
  [{:functionDeclarations
    (mapv (fn [t]
            {:name        (:name t)
             :description (:description t)
             :parameters  (:input-schema t)})
          (tool-seq tools-or-toolkit))}])
