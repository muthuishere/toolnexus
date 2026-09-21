(ns toolnexus.agents.compaction
  "Context compaction (SPEC.md §7F) — keep a long-lived agent under budget.

  A long-lived agent grows its transcript until it overflows the model's window.
  `compactor` returns a §8 `:before-llm` hook that summarizes the older transcript
  and keeps a recent tail. It rides the seam that already exists: the loop applies
  a `:before-llm` message rewrite by REPLACING the working transcript, and that
  vector flows on into the run result and the conversation store. So compaction is
  a pure `messages -> messages` helper and adds no loop behavior — it is the
  canonical use of the `:before-llm` hook.

  Three invariants (SPEC §7F):

    1. At or below `:max-tokens` the hook is a NO-OP — it returns nil and the run is
       byte-identical to a run with no compactor.
    2. Tool-pair safety — the retained tail always begins at a `user` turn, so no
       `tool` message is ever orphaned from the `assistant` carrying its
       `tool_call_id`.
    3. The leading system prompt (identity / soul / skills) is preserved verbatim;
       only the body between it and the tail is summarized.

  Messages are the same maps `toolnexus.client` builds: `:role`, `:content`, and
  where present `:tool_calls` / `:tool_call_id`.

      (client/create {:hooks {:before-llm (compaction/compactor
                                            {:max-tokens 120000
                                             :summarize  my-summarizer})}})"
  (:require [koine.json :as json]
            [toolnexus.content :as content]))

;; The system reminder injected when `:flush-to-memory` is set. Byte-identical to
;; the other ports — a host that greps its transcripts must find the same string.
(def ^:private flush-note
  "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized.")

;; Prefix on the summary system message; hosts and tests key off this to detect
;; that a compaction happened.
(def ^:private summary-prefix "[Summary of earlier conversation]\n")

(def ^:private min-part-tokens
  "SPEC §1B pins the floor at 85 — a part is never free, because a small image
  still costs a provider far more than its bytes suggest (the 82-byte shared
  fixture measured ~8 500 prompt tokens on gpt-4o-mini)."
  85)

(defn part-charge
  "SPEC §1B — a BYTE-DERIVED per-part token estimate.

  `max(85, floor(decodedBytes / 750))`, NORMATIVE in §1B rather than advisory,
  because both extremes were reached independently by different ports: the
  `mimeType` string's length scores a 5 MB image at ~3 tokens (so the compactor
  evicts TEXT and keeps every image forever), and the default `ceil(chars/4)` over
  the base64 scores it at ~1.7M (so the image is the only thing ever evicted)."
  [part]
  (max min-part-tokens (quot (content/part-bytes part) 750)))

(defn- parts-in
  "Every §1B part hanging off one message — a parts-array `:content`, a tool
  turn's `:parts`, and the `:parts` on an anthropic `tool_result` block."
  [m]
  (concat (when (content/part-array? (:content m)) (:content m))
          (:parts m)
          (when (sequential? (:content m))
            (mapcat :parts (filter map? (:content m))))))

(defn- all-parts [messages]
  (mapcat parts-in (filter map? messages)))

(defn- strip-parts
  "The message with every part payload replaced by its `{type, mimeType, bytes}`
  rendering, so the JSON half of the estimate never counts base64 twice — and so
  nothing here can put a payload where a payload must not go."
  [m]
  (if-not (map? m)
    m
    (let [strip (fn [ps] (mapv content/describe-part ps))]
      (cond-> m
        (content/part-array? (:content m)) (assoc :content (strip (:content m)))
        (seq (:parts m))                   (assoc :parts (strip (:parts m)))
        (and (sequential? (:content m)) (not (content/part-array? (:content m))))
        (assoc :content (mapv (fn [b]
                                (if (and (map? b) (seq (:parts b)))
                                  (assoc b :parts (strip (:parts b)))
                                  b))
                              (:content m)))))))

(defn estimate-tokens
  "Cheap, deterministic token estimate: `ceil(chars/4)` over each message's JSON
  serialization, summed.

  An ESTIMATOR, not a tokenizer — exactness is the host's call, which is why
  `:count-tokens` exists. The counts are not promised to be equal across ports:
  each port serializes with its own JSON writer, and SPEC §7F pins the formula,
  not the byte count."
  [messages]
  ;; `(quot (+ n 3) 4)` is ceil(n/4) on integers — `Math/ceil` is Java interop and
  ;; would end the cljgo half of this port.
  (reduce (fn [total m] (+ total (quot (+ (count (json/write-str (strip-parts m))) 3) 4)))
          (reduce + 0 (map part-charge (all-parts messages)))
          messages))

(defn- carries-tool-result?
  "Does this message carry a tool result? Under the anthropic dialect a tool result
  is a `user` message whose `:content` holds `tool_result` blocks, making it a member
  of a tool group rather than a conversational boundary.

  clojure.core only — no host interop (ADR 0009: this file compiles on the JVM and
  three Go-hosted dialects)."
  [m]
  (let [content (:content m)]
    (boolean
     (when (sequential? content)
       (some (fn [b]
               (and (map? b)
                    (contains? #{"tool_result" :tool_result} (:type b))))
             content)))))

(defn- boundary?
  "A valid tail boundary: a `user` turn that is not carrying a tool result."
  [m]
  (and (= "user" (:role m)) (not (carries-tool-result? m))))

(defn- tail-start
  "Index where the retained tail begins.

  Tool-pair safety requires the tail to start at a `user` turn: take the LARGEST
  user-boundary tail that still fits `keep-tail`. If no user boundary fits, extend
  back to the most recent user turn — safety over size. Returns `(count msgs)` (no
  tail at all) only when there is no user turn after the head."
  [msgs head-end keep-tail count-fn]
  (let [n     (count msgs)
        split (loop [i (dec n) split n]
                (if (<= i head-end)
                  split
                  (let [tail-tokens (count-fn (subvec msgs i))]
                    (if (> tail-tokens keep-tail)
                      split                       ; past budget — stop scanning back
                      (recur (dec i)
                             (if (boundary? (nth msgs i)) i split))))))]
    (if (not= split n)
      split
      ;; No user boundary fit within keep-tail — fall back to the most recent one.
      ;;
      ;; Counted with an explicit `loop`, NOT `(range (dec n) head-end -1)`: on
      ;; cljgo a DESCENDING range is an inconsistent seq — `(range 6 1 -1)` counts
      ;; 5 but traverses to `(6)`, so `some` over it silently finds nothing and the
      ;; fallback never fires. Measured on cljgo v0.8.9; the JVM is correct, which
      ;; is precisely why only a test of this branch could catch it. Reported
      ;; upstream.
      (loop [i (dec n)]
        (cond
          (<= i head-end)                  n
          (boundary? (nth msgs i))         i
          :else                            (recur (dec i)))))))

(defn compactor
  "Build a §8 `:before-llm` hook that compacts the transcript once it exceeds
  `:max-tokens`.

  Below budget it returns nil (a byte-identical no-op); above it, it replaces the
  transcript with

      [leading system prompt, summary system message, (flush reminder?), ...tail]

  splitting at a clean `user` boundary so tool groups are never broken.

    :max-tokens       compact only when the estimate exceeds this; at/below => no-op
                      (REQUIRED)
    :summarize        (fn [older] \"summary\") — produces the summary. MAY call an
                      LLM; the library makes no model call on the host's behalf
                      (REQUIRED)
    :keep-tail        keep at least this many tokens of the most recent tail
                      (default: half of :max-tokens)
    :count-tokens     (fn [messages] n) — token estimate (default `estimate-tokens`)
    :flush-to-memory  when true, inject a pre-compact system reminder telling the
                      model to persist durable facts through the §7E `memory` tool
                      before the head is summarized (off by default)"
  [{:keys [max-tokens summarize keep-tail count-tokens flush-to-memory]}]
  (let [count-fn  (or count-tokens estimate-tokens)
        keep-tail (or keep-tail (quot max-tokens 2))]
    (fn [{:keys [messages]}]
      (let [msgs (vec messages)]
        (when (> (count-fn msgs) max-tokens)      ; at/below budget => no-op
          ;; A leading system prompt is preserved verbatim — never summarized.
          (let [head-end (if (= "system" (:role (first msgs))) 1 0)
                split    (tail-start msgs head-end keep-tail count-fn)]
            ;; split <= head-end means nothing above the preserved head is safely
            ;; compactible; leaving the transcript alone is the honest answer.
            (when (> split head-end)
              (let [system (subvec msgs 0 head-end)
                    older  (subvec msgs head-end split)
                    tail   (subvec msgs split)
                    flush  (if flush-to-memory
                             [{:role "system" :content flush-note}]
                             [])]
                {:messages (vec (concat system
                                        [{:role    "system"
                                          :content (str summary-prefix (summarize older))}]
                                        flush
                                        tail))}))))))))
