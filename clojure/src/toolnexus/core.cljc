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
            [toolnexus.a2a :as a2a]
            [toolnexus.builtin :as builtin]
            [toolnexus.adapter :as adapter]))

;; ---------------------------------------------------------------------------
;; the skill source's options (§3 S1/S2/S5)
;; ---------------------------------------------------------------------------

(defn- provider-defs
  "Resolve the lazy skill provider. §3 S1 'Provider failure is isolated': a
  throwing provider contributes NOTHING and every other source still loads —
  the same rule §0.3 applies to a dead MCP server."
  [f]
  (if f (try (vec (f)) (catch Throwable _ [])) []))

(defn- string-keys
  "Filter maps arrive keyworded from Clojure callers and stringy from a parsed
  config. One spelling downstream, so `:disable-skills` and `:skills-filter` can
  actually be merged against each other."
  [m]
  (reduce (fn [acc e] (assoc acc (if (keyword? (key e)) (name (key e)) (str (key e))) (val e)))
          {} m))

(defn- skill-source-opts
  "Fold every skill-shaped build option into ONE `skill/load-skills` options map.

  `:skills` keeps its shipped meaning (a root, or a seq of roots) and also
  accepts a seq of skill DEFS; `:skill-defs` is the explicit data list, and
  `:skill-provider` a 0-arg fn producing more. All three compose, resolved in
  that order, so the existing first-name-wins rule settles collisions across
  sources without a second rule.

  `:disable-skills` is sugar over a `:skills-filter` drop-list, folded in FIRST
  so an explicit filter entry overrides it (js/src/toolkit.ts spreads
  disableSkills then skillsFilter — the merge order IS the rule)."
  [{:keys [skills skill-defs skill-provider skills-filter skill-sample-limit disable-skills]}]
  (let [base (skill/load-opts skills)
        defs (-> (vec (:skills base))
                 (into (vec skill-defs))
                 (into (provider-defs skill-provider)))
        filt (if (seq disable-skills)
               (merge (reduce (fn [acc n] (assoc acc (str n) false)) {} disable-skills)
                      (string-keys skills-filter))
               (when skills-filter (string-keys skills-filter)))]
    (cond-> (assoc base :skills defs)
      filt               (assoc :filter filt)
      skill-sample-limit (assoc :sample-limit skill-sample-limit))))

(defn- agent-tools
  "§7A remote agents, from the `:agents` option and from a top-level `agents`
  block on a PARSED config map (mirroring how `mcpServers` is read off the same
  object). A failing agent contributes no tools and never a throw."
  [agents config]
  (->> (into (vec agents) (a2a/parse-agents-config (when (map? config) (:agents config))))
       (mapcat (fn [a] (try (a2a/agent-tools a) (catch Throwable _ []))))
       vec))

(defn build
  "Build a toolkit from every configured source.

  opts:
    :mcp                 — an mcp.json string or map (SPEC §2/§0.3)
    :skills              — a skills root, a seq of roots, or a seq of skill defs (§3)
    :skill-defs          — skills supplied as DATA, bypassing the filesystem (§3 S1)
    :skill-provider      — a 0-arg fn producing skill defs, resolved here (§3 S1)
    :skills-filter       — name->bool allowlist over the skill catalog (§3 S2)
    :skill-sample-limit  — 0 ⇒ default 10 · n>0 ⇒ cap · -1 ⇒ no <skill_files> (§3 S5)
    :disable-skills      — names to drop from the catalog; sugar over the filter
    :builtins            — the §0.11 toggle: false | {:disabled ..} | {:enabled ..} | {:tools {..}}
    :agents              — remote A2A agents; each advertised skill becomes a tool (§7A)
    :tools               — extra Tools of your own (native, http, a2a, anything)
    :disable-tools       — final exposed names to drop, across EVERY source (§4A)
    :wait-for            — the ONE §10 host resolver. Passed to the MCP source so a
                           connected server may elicit input mid-`tools/call`; absent
                           ⇒ the `elicitation` capability is not advertised at all.

  Returns a toolkit plus what it took to get there:
    {:tools .. :sources .. :skills .. :statuses .. :errors .. :connections .. :wait-for ..}

  Every source is ISOLATED (§0.3): a source that fails contributes an error and
  a status, never an exception. A toolkit with one dead MCP server is still a
  working toolkit — that is the whole point of the status map."
  [{:keys [mcp skills skill-defs skill-provider builtins tools agents
           disable-tools wait-for] :as opts}]
  (let [skill?   (or (some? skills) (some? skill-defs) (some? skill-provider))
        loaded   (when skill? (skill/load-skills (skill-source-opts opts)))
        skill-ts (when loaded [(skill/skill-tool loaded)])
        agent-ts (agent-tools agents mcp)
        mcp-res  (when mcp
                   (try (mcp/from-config mcp {:wait-for wait-for})
                        (catch Throwable e
                          {:tools [] :statuses {} :connections []
                           :errors {"<config>" (or (ex-message e) (str e))}})))]
    (-> (builtin/builtin-toolkit builtins)          ; first: lowest precedence
        (tool/add-tools (or skill-ts []))
        ;; Agents sit between the local skill tool and host-supplied extras.
        ;; DIVERGENCE, recorded: js/src/toolkit.ts aggregates FIRST-wins over
        ;; [mcp, skill, builtins, agents, extras], so there builtins outrank
        ;; agents and agents outrank extras. This port has always aggregated
        ;; LAST-wins with extras above skill/builtins, so no single slot can
        ;; reproduce both relations without moving `:tools` — which would change
        ;; shipped behaviour for a collision (a host extra named exactly like a
        ;; remote agent's `Card_Bot_echo`) that essentially cannot occur.
        (tool/add-tools agent-ts)
        (tool/add-tools (or tools []))
        (tool/add-tools (:tools mcp-res []))        ; last: §0.11 MCP precedence
        (update :tools (fn [m] (apply dissoc m (map str (or disable-tools [])))))
        (assoc :skills      (or loaded {})
               :wait-for    wait-for
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
