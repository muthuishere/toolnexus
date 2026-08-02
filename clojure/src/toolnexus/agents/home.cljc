(ns toolnexus.agents.home
  "Agent home — the persona surface (SPEC.md §7E).

  A persona is an identity that lives in FILES, keeps durable MEMORY it can edit,
  and (once the §7D runtime lands here) runs on a HEARTBEAT so it can act
  unprompted. All three ride seams that already ship: the composed soul is just a
  system prompt, the memory tool is a plain `Tool`, and the heartbeat is
  `post` + `wake`. This layer adds no runtime behavior — it is a directory
  convention plus composition, not new machinery.

      (def soul (:soul (home/compose-soul \"./personas/ava\")))
      (def tools [(home/memory-tool \"./personas/ava\")])

  `from-dir` and `start-agent` are the two entry points that need the §7D
  runtime; they land with it. Everything here is usable today against a plain
  client — a composed soul is a `:system-prompt`, and the memory tool is a tool
  like any other."
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.fs :as fs]
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
