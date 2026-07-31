;; The public API. One call builds a toolkit from every source.
;;
;; Registration ORDER is the contract, not a detail: `tool/add-tools` lets a
;; later tool win a name collision, and SPEC §0.11 requires MCP to take
;; precedence over a builtin of the same name. So builtins go in FIRST and MCP
;; LAST, and the ordering below is load-bearing rather than stylistic.
(ns toolnexus.core
  (:require [toolnexus.tool :as tool]
            [toolnexus.mcp :as mcp]
            [toolnexus.skill :as skill]
            [toolnexus.builtin :as builtin]
            [toolnexus.adapter :as adapter]))

(defn build
  "Build a toolkit from every configured source.

  opts:
    :mcp       — an mcp.json string or map (SPEC §2/§0.3)
    :skills    — a skills root, or a seq of roots (§3)
    :builtins  — the §0.11 toggle: false | {:disabled ..} | {:enabled ..} | {:tools {..}}
    :tools     — extra Tools of your own (native, http, a2a, anything)

  Returns a toolkit plus what it took to get there:
    {:tools .. :sources .. :skills .. :statuses .. :errors .. :connections ..}

  Every source is ISOLATED (§0.3): a source that fails contributes an error and
  a status, never an exception. A toolkit with one dead MCP server is still a
  working toolkit — that is the whole point of the status map."
  [{:keys [mcp skills builtins tools]}]
  (let [loaded   (when skills (skill/load-skills skills))
        skill-ts (when (seq loaded) [(skill/skill-tool loaded)])
        mcp-res  (when mcp
                   (try (mcp/from-config mcp)
                        (catch Throwable e
                          {:tools [] :statuses {} :connections []
                           :errors {"<config>" (or (ex-message e) (str e))}})))]
    (-> (builtin/builtin-toolkit builtins)          ; first: lowest precedence
        (tool/add-tools (or skill-ts []))
        (tool/add-tools (or tools []))
        (tool/add-tools (:tools mcp-res []))        ; last: §0.11 MCP precedence
        (assoc :skills      (or loaded {})
               :statuses    (merge {"builtin" (if (builtin/source-on? builtins)
                                                "connected" "disabled")}
                                   (:statuses mcp-res))
               :errors      (or (:errors mcp-res) {})
               :connections (or (:connections mcp-res) [])))))

(defn shutdown!
  "Disconnect every MCP connection a toolkit opened. Idempotent."
  [tk]
  (doseq [c (:connections tk)]
    (try (mcp/disconnect c) (catch Throwable _ nil)))
  (assoc tk :connections []))

(defn skills-prompt
  "SPEC §3/§0.6 — the skills preamble for the system prompt."
  [tk]
  (skill/skills-prompt (:skills tk)))

;; Adapters, re-exported so a caller needs one require, not four.
(defn to-openai    [tk] (adapter/to-openai tk))
(defn to-anthropic [tk] (adapter/to-anthropic tk))
(defn to-gemini    [tk] (adapter/to-gemini tk))

(defn tool-names [tk] (tool/tool-names tk))

(defn execute
  "Run a tool by name. A throw becomes an error ToolResult (§0.8)."
  ([tk name args]     (tool/execute tk name args))
  ([tk name args ctx] (tool/execute tk name args ctx)))
