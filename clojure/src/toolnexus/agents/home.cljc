(ns toolnexus.agents.home
  "Agent home — the persona surface (SPEC.md §7E).

  A persona is an identity that lives in FILES, keeps durable MEMORY it can edit,
  and runs on a HEARTBEAT so it can act unprompted. All three ride seams that
  already ship: the composed soul is just a system prompt, the memory tool is a
  plain `Tool`, and the heartbeat is `post` + `wake` on the §7D runtime's
  INJECTABLE CLOCK. This layer adds no runtime behavior — it is a directory
  convention plus composition, not new machinery.

      (def ava (home/from-dir \"./personas/ava\"))

      ;; one-shot, or as a tool in someone else's toolkit
      (def rt (rt/create-runtime {:registry {(:name ava) ava} :llm llm}))
      (rt/run-agent rt (:name ava) \"what is on my plate?\")
      (rt/agent-tool rt (:name ava))

      ;; …or give it its own clock
      (def started (home/start-agent ava {:llm llm} {:every-ms 1800000
                                                     :on-beat println}))
      ((:stop started))

  Everything here is also usable against a plain client with no runtime at all —
  a composed soul is a `:system-prompt`, and the memory tool is a tool like any
  other."
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.process :as proc]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.native :as native]
            [toolnexus.skill :as skill]
            [toolnexus.tool :as tool]))

(def bootstrap-order
  "Bootstrap discovery order (§7E): identity first, durable memory last. Each
  present file is injected into the soul as a `## <filename>` section, in exactly
  this order. The order is part of the cross-port contract — a persona whose
  MEMORY.md outranked its SOUL.md would behave differently in seven languages."
  ["AGENTS.md"
   "SOUL.md"
   "IDENTITY.md"
   "USER.md"
   "TOOLS.md"
   "HEARTBEAT.md"
   "MEMORY.md"])

(def max-file-bytes
  "Per-file bootstrap cap (§7E), measured in BYTES (2 MiB), not characters. A
  larger file is injected truncated; the file on disk is untouched."
  2097152)

(def truncation-notice
  "Notice appended to a bootstrap file truncated at `max-file-bytes`."
  "\n[truncated: exceeds 2 MB bootstrap cap]")

(def heartbeat-ok
  "The silent-no-op sentinel: a heartbeat reply containing this surfaces nothing."
  "HEARTBEAT_OK")

(def heartbeat-prompt
  "The prompt a heartbeat wakes the persona with. It contains `HEARTBEAT_OK` so
  the model knows the silent-reply contract, and the word \"Heartbeat\" so a
  HEARTBEAT.md that keys off it can recognise the trigger."
  (str "Heartbeat. Read your HEARTBEAT.md section and follow it. "
       "If nothing needs attention, reply " heartbeat-ok "."))

;; ---------------------------------------------------------------------------
;; Reading, with the byte cap
;; ---------------------------------------------------------------------------

(defn- read-capped
  "The file at `path` as a string, truncated to `max-file-bytes` BYTES; nil when
  the file is absent (absent bootstrap files are skipped, §7E).

  The cap is measured on the encoded bytes rather than on characters because the
  spec says bytes: a 2 MB budget that counted characters would admit twice the
  payload for a Devanagari persona and a different one for an ASCII persona.
  Truncation may split a multibyte character — that is spec'd, and the split byte
  decodes to the replacement character rather than throwing."
  [path]
  (when (fs/exists? path)
    (let [bs (fs/read-bytes path)]
      (if (<= (count bs) max-file-bytes)
        (fs/read-file path)
        ;; Base64 is the portable bytes->string seam koine gives both hosts;
        ;; `String.` is java.lang and Go's []byte->string is not reachable from
        ;; portable Clojure, so the round trip is what keeps this one source tree.
        (str (codec/decode (codec/encode (byte-array (take max-file-bytes (seq bs)))))
             truncation-notice)))))

(defn compose-soul
  "Compose the bootstrap files present in `dir` into one soul string — the frozen
  snapshot injected as the system prompt for a whole run.

  Returns `{:soul \"…\" :found [\"SOUL.md\" …]}`. Only present files appear, always
  in `bootstrap-order`, each as a `## <filename>` section with its body trimmed.
  Composition happens once, at session start: the soul is fixed for the run, which
  is what keeps a long-lived persona cache-stable."
  [dir]
  (let [entries (keep (fn [file]
                        (when-let [body (read-capped (str dir "/" file))]
                          [file (str "## " file "\n\n" (str/trim body))]))
                      bootstrap-order)]
    {:soul  (str/join "\n\n" (map second entries))
     :found (mapv first entries)}))

;; ---------------------------------------------------------------------------
;; The `memory` builtin (§7E) — file-backed, opt-in
;; ---------------------------------------------------------------------------

(def memory-tool-description
  (str "Persist durable memory. action=add appends an entry; replace swaps an "
       "existing substring (with); remove deletes one. target=self (MEMORY.md, "
       "default) or user (USER.md). Writes persist to disk and load at the START of "
       "your NEXT session — they do NOT change your current context."))

(def memory-tool-input-schema
  {:type       "object"
   :properties {:action {:type "string" :enum ["add" "replace" "remove"]}
                :target {:type "string" :enum ["self" "user"]
                         :description "self=MEMORY.md (default), user=USER.md"}
                :text   {:type "string"
                         :description "For add: the entry. For replace/remove: the existing text."}
                :with   {:type "string" :description "For replace: the replacement text."}}
   :required   ["action" "text"]})

(defn- memory-file [dir target]
  (str dir "/" (if (= "user" (str target)) "USER.md" "MEMORY.md")))

(defn memory-tool
  "The `memory` builtin (§7E) — file-backed and OPT-IN. Not one of the default
  §4A builtins: it exists only when a home directory is wired.

  One tool, three actions over `MEMORY.md` (the agent's own notes) and `USER.md`
  (its model of the user):

    add      append an entry
    replace  swap an existing substring for `with`
    remove   delete an existing substring

  Every action writes to DISK. It does NOT touch the live session's system
  prompt: under the frozen-snapshot rule the edit loads at the START of the next
  session, which is what keeps a long-lived persona cache-stable — and the tool's
  own description says so, because the model is the one that has to know. A
  `replace`/`remove` whose substring is absent is a loud `isError`, never a
  silent no-op."
  [dir]
  (native/native-tool
   {:name         "memory"
    :description  memory-tool-description
    :input-schema memory-tool-input-schema
    :run
    (fn [args]
      (let [file    (memory-file dir (or (:target args) "self"))
            text    (str (:text args))
            current (or (read-capped file) "")
            action  (str (:action args))
            next-s  (case action
                      "add"     (str/triml (str (str/trimr current) "\n- " text "\n"))
                      "replace" (when (str/includes? current text)
                                  (str/replace-first current text (str (or (:with args) ""))))
                      "remove"  (when (str/includes? current text)
                                  (str/replace-first current text ""))
                      nil)]
        (cond
          (and (nil? next-s) (contains? #{"replace" "remove"} action))
          (tool/failure (str "not found: " text))

          (nil? next-s)
          (tool/failure (str "unknown action: " action))

          :else
          (do (fs/mkdirs! (skill/parent-dir file))
              (fs/write-file file next-s)
              (str "ok (" action " → " (skill/file-name file) "); loads next session")))))}))

;; ---------------------------------------------------------------------------
;; The directory IS the agent (§7E)
;; ---------------------------------------------------------------------------

(defn from-dir
  "Build a persona AgentDef from a home directory (§7E).

  Discovers the bootstrap files in `dir`, composes them into a FROZEN soul
  snapshot (once, here — the soul is fixed for the whole run, which is what keeps
  a long-lived persona cache-stable), and wires a `memory` tool over the same
  directory unless `:memory false` asks for a read-only persona.

  Options:

    :name    the agent name; default the directory's last segment
    :does    the routing description a delegating model sees
    :model   default `\"inherit\"` — the runtime's own `:llm` model
    :tools   extra tools, placed BEFORE the memory tool
    :memory  `false` omits the memory tool

  The return value is a plain AgentDef map, which is this port's spelling of
  §7D's Level-1 agent: put it in a runtime's `:registry` and both directions of
  the axiom work on it unchanged — `(rt/run-agent rt name prompt)` is `.run`, and
  `(rt/agent-tool rt name)` is `.asTool`. There is no Agent object to learn,
  because a def IS the agent here."
  ([dir] (from-dir dir {}))
  ;; `:name` is read off the map rather than destructured: binding a local called
  ;; `name` would shadow `clojure.core/name`, and cljgo's core carries MORE names
  ;; than the JVM's, so shadowing is never a local decision here.
  ([dir {:keys [does model tools memory] :as opts}]
   (let [dir  (str/replace (str dir) #"/+$" "")
         nm   (or (:name opts) (skill/file-name dir))]
     {:name  nm
      :does  (or does (str "persona agent from " dir))
      :soul  (:soul (compose-soul dir))
      :model (or model "inherit")
      :tools (cond-> (vec tools)
               (not (false? memory)) (conj (memory-tool dir)))})))

;; ---------------------------------------------------------------------------
;; The heartbeat (§7E)
;; ---------------------------------------------------------------------------

(defn start-agent
  "Give a persona its own clock (§7E).

  On each `:every-ms` interval the persona `post`s a tick to its OWN inbox — the
  unsolicited rail, where timer ticks COALESCE, so a beat slower than the turn it
  starts can never pile up — and, WHEN IDLE, `wake`s it with `heartbeat-prompt`.
  A reply containing `heartbeat-ok` is SILENT: only a substantive reply is
  collected and handed to `:on-beat`. Silence is the default, which is the whole
  point — a persona that reported every beat would be a cron job with a bill.

  Every timer goes through the RUNTIME'S INJECTABLE CLOCK, never a sleep: pass
  `{:clock (rt/virtual-clock)}` in `run-opts` and a fixture drives the beats with
  `((:advance! clock) ms)`, deterministically.

  `run-opts` is anything `rt/create-runtime` takes except `:registry`, which is
  derived from `agent-def` (plus any registry the caller supplies, so a persona
  with a `:team` still resolves its team-mates).

  Returns:

    :runtime  the live runtime — the host's seam for INBOUND channels. §7E is
              explicit that channels are the host's job: deliver an external
              event by calling `rt/post` / `rt/wake` on `:handle`
    :handle   the persona's handle id
    :beats    an atom holding the substantive beats so far (HEARTBEAT_OK
              excluded)
    :stop     `(fn [])` — cancel the heartbeat and close the tree gracefully

  Throws only if the persona cannot be spawned at all; a spawn failure is a
  configuration error at the root, which is the one place §7D permits a throw."
  [agent-def run-opts {:keys [every-ms on-beat]}]
  (let [nm      (:name agent-def)
        runtime (rt/create-runtime
                 (assoc run-opts :registry (assoc (:registry run-opts) nm agent-def)))
        h       (rt/spawn runtime rt/root nm)]
    (when (rt/verb-error? h) (throw (ex-info (:error h) {:agent nm})))
    (let [beats   (atom [])
          stopped (atom false)
          cancel  (atom nil)
          clock   (:clock runtime)]
      (letfn [(collect! []
                ;; The wait runs off the beat thread so a slow turn cannot delay
                ;; the next tick. `run-async!`, never `future` — a library may not
                ;; hold its consumer's process open.
                (proc/run-async!
                 (fn []
                   (let [r (rt/wait runtime h)]
                     (when (and (not (:isError r))
                                (not (str/includes? (str (:text r)) heartbeat-ok)))
                       (swap! beats conj (:text r))
                       (when on-beat (on-beat (:text r))))))))
              (beat []
                (when-not @stopped
                  (rt/post runtime h {:from "clock" :channel "timer" :text "tick"})
                  ;; Only at idle. A persona still working through the last beat
                  ;; just accumulates one coalesced tick and picks it up on its
                  ;; next turn — the unsolicited rail, exactly as §7D writes it.
                  (when (= "idle" (:state (rt/inspect runtime h)))
                    (when (:ok (rt/wake runtime h heartbeat-prompt))
                      (collect!)))
                  ;; Self-reschedule, so there is only ever ONE live timer.
                  (reset! cancel ((:set-timeout clock) beat every-ms))))]
        (reset! cancel ((:set-timeout clock) beat every-ms))
        {:runtime runtime
         :handle  h
         :beats   beats
         :stop    (fn []
                    (reset! stopped true)
                    (when-let [c @cancel] (c))
                    (rt/close runtime rt/root)
                    nil)}))))
