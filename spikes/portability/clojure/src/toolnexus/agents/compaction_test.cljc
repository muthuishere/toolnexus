;; toolnexus.agents.compaction — the suite. SPEC §7F.
;;
;; Every expected value comes from SPEC.md §7F and `js/src/agents/compaction.ts`,
;; not from this port's own output: a snapshot of what we happen to emit would
;; prove only that we are self-consistent, which is the one thing seven ports
;; never need proving.
;;
;; The counting function is INJECTED in most tests (one token per message) so the
;; split arithmetic is exercised against a number the test controls, rather than
;; against whatever a JSON writer happens to produce. `estimate-tokens` gets its
;; own tests for the formula itself.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.agents.compaction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [toolnexus.agents.compaction :as compaction]))

(defn- msg [role content] {:role role :content content})

;; One token per message: makes "keep-tail 3" mean "keep three messages".
(defn- per-message [ms] (count ms))

(defn- summarize-marker [older] (str "SUMMARIZED " (count older)))

(defn- transcript
  "system, then `n` user/assistant pairs — user turns at odd indexes."
  [n]
  (into [(msg "system" "soul")]
        (mapcat (fn [i] [(msg "user" (str "u" i)) (msg "assistant" (str "a" i))])
                (range n))))

(deftest under-budget-is-a-no-op
  (testing "at or below :max-tokens the hook returns nil — a byte-identical run"
    (let [f (compaction/compactor {:max-tokens 10
                                   :count-tokens per-message
                                   :summarize summarize-marker})]
      (is (nil? (f {:messages (transcript 2)}))       ; 5 messages <= 10
          "under budget must not compact")
      (is (nil? (f {:messages (transcript 4)}))       ; 9 messages <= 10
          "under budget must not compact")
      (is (nil? (f {:messages (vec (repeat 10 (msg "user" "x")))}))
          "EXACTLY at the budget is still a no-op — the rule is `exceeds`"))))

(deftest compacts-above-budget
  (testing "above budget the transcript becomes [system, summary, ...tail]"
    (let [msgs (transcript 6)                        ; 13 messages
          f    (compaction/compactor {:max-tokens 6
                                      :keep-tail 3
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (some? out) "over budget must compact")
      (is (= (first msgs) (first out))
          "the leading system prompt is preserved VERBATIM")
      (is (= "system" (:role (second out))))
      (is (str/starts-with? (:content (second out)) "[Summary of earlier conversation]\n")
          "the summary message carries the pinned prefix")
      (is (= (last msgs) (last out))
          "the most recent message is always retained"))))

(deftest tail-begins-at-a-user-turn
  (testing "tool-pair safety — a `tool` message is never orphaned from its assistant"
    (let [msgs [(msg "system" "soul")
                (msg "user" "u0")
                (msg "assistant" "a0")
                (msg "user" "u1")
                (assoc (msg "assistant" nil) :tool_calls [{:id "c1"}])
                (assoc (msg "tool" "result") :tool_call_id "c1")
                (msg "assistant" "a1")]
          f    (compaction/compactor {:max-tokens 3
                                      :keep-tail 4
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (some? out))
      ;; index 0 system, 1 summary, then the tail
      (is (= "user" (:role (nth out 2)))
          "the retained tail MUST begin at a user turn")
      (is (some (fn [m] (= "c1" (:tool_call_id m))) out)
          "the tool message survived")
      (is (some (fn [m] (seq (:tool_calls m))) out)
          "…and so did the assistant carrying its tool_call_id"))))

(deftest falls-back-to-the-most-recent-user-turn
  (testing "when no user boundary fits keep-tail, extend back — safety over size"
    ;; keep-tail 1, but the last user turn is 4 messages back: the split must
    ;; still land on it rather than orphaning the tool group.
    (let [msgs [(msg "system" "soul")
                (msg "user" "u0")
                (msg "assistant" "a0")
                (msg "user" "u1")
                (msg "assistant" "a1")
                (msg "assistant" "a2")
                (msg "assistant" "a3")]
          f    (compaction/compactor {:max-tokens 2
                                      :keep-tail 1
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (some? out))
      (is (= "user" (:role (nth out 2))))
      (is (= "u1" (:content (nth out 2)))
          "the MOST RECENT user turn, not the first one"))))

(deftest no-user-turn-at-all-summarizes-everything
  (testing "with no user boundary the whole body is summarized and the tail is empty"
    ;; `findTailStart` in js/src/agents/compaction.ts returns msgs.length when no
    ;; user turn follows the head — the split is still > headEnd, so the body IS
    ;; compacted and nothing is retained. Keeping a tool group intact is the rule;
    ;; there is no tool group here, so there is nothing to protect.
    (let [msgs [(msg "system" "soul")
                (msg "assistant" "a0")
                (msg "assistant" "a1")
                (msg "assistant" "a2")]
          f    (compaction/compactor {:max-tokens 1
                                      :keep-tail 1
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (= 2 (count out)) "system + summary, no tail")
      (is (= (first msgs) (first out)))
      (is (= "[Summary of earlier conversation]\nSUMMARIZED 3" (:content (second out)))
          "all three assistant turns went to the summarizer"))))

(deftest summarize-receives-only-the-older-body
  (testing "the summarizer sees the messages between the head and the tail"
    (let [seen (atom nil)
          msgs (transcript 6)
          f    (compaction/compactor {:max-tokens 6
                                      :keep-tail 3
                                      :count-tokens per-message
                                      :summarize (fn [older]
                                                   (reset! seen older)
                                                   "S")})
          out  (:messages (f {:messages msgs}))]
      (is (some? out))
      (is (not-any? (fn [m] (= "system" (:role m))) @seen)
          "the preserved system prompt is never handed to the summarizer")
      (is (= (:content (second msgs)) (:content (first @seen)))
          "the older body starts immediately after the head")
      (is (= "[Summary of earlier conversation]\nS" (:content (second out)))
          "the summarizer's string is used verbatim after the prefix"))))

(deftest flush-to-memory-is-off-by-default
  (let [msgs (transcript 6)
        run  (fn [opts]
               (:messages ((compaction/compactor
                            (merge {:max-tokens 6
                                    :keep-tail 3
                                    :count-tokens per-message
                                    :summarize summarize-marker}
                                   opts))
                           {:messages msgs})))
        note "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized."]
    (testing "absent by default"
      (is (not-any? (fn [m] (= note (:content m))) (run {}))))
    (testing "present, immediately after the summary, when asked for"
      (let [out (run {:flush-to-memory true})]
        (is (= note (:content (nth out 2))))
        (is (= "system" (:role (nth out 2))))))))

(deftest no-leading-system-prompt-is-handled
  (testing "a transcript that does not start with `system` still compacts safely"
    (let [msgs (vec (rest (transcript 6)))          ; drop the system message
          f    (compaction/compactor {:max-tokens 6
                                      :keep-tail 3
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (some? out))
      (is (= "system" (:role (first out)))
          "the summary is the first message when there was no head to preserve")
      (is (str/starts-with? (:content (first out)) "[Summary of earlier conversation]\n")))))

(deftest estimate-tokens-is-ceil-chars-over-four
  (testing "the default estimator — a formula, not a tokenizer"
    (is (= 0 (compaction/estimate-tokens [])))
    (is (pos? (compaction/estimate-tokens [(msg "user" "hello")])))
    (is (= (+ (compaction/estimate-tokens [(msg "user" "a")])
              (compaction/estimate-tokens [(msg "user" "bb")]))
           (compaction/estimate-tokens [(msg "user" "a") (msg "user" "bb")]))
        "summed per message, so it is additive")
    (is (> (compaction/estimate-tokens [(msg "user" (apply str (repeat 400 "x")))])
           (compaction/estimate-tokens [(msg "user" "x")]))
        "longer content estimates larger")))

(deftest keep-tail-defaults-to-half-of-max-tokens
  (testing "the §7F default is `max-tokens / 2` — asserted as the EXACT transcript"
    ;; A length BAND cannot see this default move: with :max-tokens 8 every
    ;; divisor from 2 to 8 lands inside `3 <= n < 8`, so `(quot max-tokens 8)`
    ;; used to pass. The split is arithmetic, so the whole output vector is
    ;; pinned instead.
    ;;
    ;; 21 messages at one token each, keep-tail = 8/2 = 4: scanning back from the
    ;; end, index 17 ("u8") is the LARGEST user-boundary tail still within 4
    ;; messages, so the tail is indexes 17..20 and the summarizer sees the 16
    ;; messages between the preserved system head and it.
    (let [msgs (transcript 10)                      ; 21 messages
          out  (:messages ((compaction/compactor {:max-tokens 8
                                                  :count-tokens per-message
                                                  :summarize summarize-marker})
                           {:messages msgs}))]
      (is (= (into [(msg "system" "soul")
                    (msg "system" "[Summary of earlier conversation]\nSUMMARIZED 16")]
                   (subvec msgs 17))
             out))
      (is (= 4 (count (drop 2 out)))
          "four messages of tail — keep-tail 1 (max-tokens/8) would keep two"))))

(deftest the-hook-shape-matches-the-section-8-seam
  (testing "it is a plain :before-llm hook — event in, {:messages} or nil out"
    (let [f (compaction/compactor {:max-tokens 6
                                   :keep-tail 3
                                   :count-tokens per-message
                                   :summarize summarize-marker})
          r (f {:messages (transcript 6) :tools [] :model "m" :turn 1})]
      (is (map? r))
      (is (= [:messages] (keys r))
          "it returns ONLY :messages — a hook that also rewrote :tools would be a
           silent contract nobody asked for"))))

;; Dialect-blind tool-pair boundary (fix-compaction-tool-pair-dialect).
;;
;; Under the anthropic dialect a tool result is a `user` message carrying
;; tool_result blocks, so a "clean user boundary" can be the tool-result carrier
;; itself — orphaning it from the assistant tool_use that gets summarized away.
(deftest anthropic-tool-result-carrier-is-not-a-boundary
  (testing "a user message carrying tool_result blocks is NOT a tail boundary"
    (let [msgs [(msg "system" "soul")
                (msg "user" "u0")
                (msg "assistant" "a0")
                (msg "user" "u1")
                (msg "assistant" [{:type "tool_use" :id "tu_1"}])
                (msg "user" [{:type "tool_result" :tool_use_id "tu_1"}])
                (msg "assistant" "done")]
          f    (compaction/compactor {:max-tokens 3
                                      :keep-tail 3
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (some? out))
      (let [first-non-system (first (remove #(= "system" (:role %)) out))
            carries? (fn [m] (and (sequential? (:content m))
                                  (some #(= "tool_result" (:type %)) (:content m))))]
        (is (not (carries? first-non-system))
            "the tail MUST NOT begin on a tool-result carrier — its tool_use is gone")
        (is (some (fn [m] (= "done" (:content m))) out)
            "the most recent turn survived")))))

(deftest openai-dialect-boundaries-unchanged
  (testing "every user message stays a valid boundary in the openai dialect"
    (let [msgs [(msg "system" "soul")
                (msg "user" "u0")
                (msg "assistant" "a0")
                (assoc (msg "assistant" nil) :tool_calls [{:id "c1"}])
                (assoc (msg "tool" "result") :tool_call_id "c1")
                (msg "user" "u1")
                (msg "assistant" "done")]
          f    (compaction/compactor {:max-tokens 3
                                      :keep-tail 3
                                      :count-tokens per-message
                                      :summarize summarize-marker})
          out  (:messages (f {:messages msgs}))]
      (is (some? out))
      (is (= "user" (:role (nth out 2)))
          "the tail still begins at the plain user turn"))))
