;; toolnexus.agents.home — memory hierarchy + dreaming. SPEC §7E.
;;
;; Every expected value is READ from the shared fixtures
;; `examples/memory-hierarchy/fixture.json` and `examples/dreaming/fixture.json`
;; (via TN_EXAMPLES / the examples/ lookup, like the rest of the suite). What is
;; not read from the files is the mock LLM's behaviour: the dreaming fixture
;; writes its scripts as prose, so they are implemented natively here, exactly
;; as js/test/memory-hierarchy.test.ts does.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.agents.memory-hierarchy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.text :as ktext]
            [koine.time :as ktime]
            [toolnexus.agents.home :as home]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.mcp-crypto :as crypto]
            [toolnexus.native :as native]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.transcript-search :as ts]))

(defn- fixture [nm]
  (json/read-str (fs/read-file (str (te/examples-dir) "/" nm "/fixture.json"))))

(defn- kstr
  "A parsed-JSON key as its original string (\"memory/a.md\" parses to a
  NAMESPACED keyword, so `name` would drop the directory)."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- sha256-hex [s]
  (apply str (map (fn [b] (str (nth "0123456789abcdef" (quot b 16))
                               (nth "0123456789abcdef" (rem b 16))))
                  (crypto/sha256 s))))

(defn- utf8-len [s] (ktext/utf8-length s))

(defn- with-home [files f]
  (let [dir (str (fs/temp-dir! "tn-memh"))]
    (try
      (doseq [[k content] files]
        (let [p (str dir "/" (kstr k))]
          (fs/mkdirs! (subs p 0 (str/last-index-of p "/")))
          (fs/write-file p content)))
      (f dir)
      (finally (fs/delete-tree! dir)))))

(defn- tree
  "Every regular file under `dir` as {relative-path content}."
  [dir]
  (into {}
        (keep (fn [p]
                (let [p (str p)]
                  (when (and (not= p dir) (not (fs/directory? p)))
                    [(subs p (inc (count dir))) (fs/read-file p)]))))
        (fs/list-tree dir)))

(defn- call [t args]
  (let [r (native/execute-native t args)]
    {:output (:output r) :isError (boolean (:isError r))}))

(defn- cap-opt
  "Fixture `memoryIndexCap` (wire casing) -> this port's option."
  [c]
  (if (map? c)
    (cond-> {}
      (contains? c :maxLines) (assoc :max-lines (:maxLines c))
      (contains? c :maxBytes) (assoc :max-bytes (:maxBytes c)))
    c))

;; ---------------------------------------------------------------------------
;; G1 — the index cap
;; ---------------------------------------------------------------------------

(deftest index-cap-fixture-cases
  (let [fx (:indexCap (fixture "memory-hierarchy"))]
    (is (= {:max-lines (:maxLines (:preset fx)) :max-bytes (:maxBytes (:preset fx))}
           home/memory-index-cap-preset))
    (doseq [c (:cases fx)]
      (testing (:name c)
        (let [memory (if-let [g (:generate c)]
                       (apply str (map (fn [i] (str (str/replace (:line g) "<i>" (str i)) "\n"))
                                       (range 1 (inc (:count g)))))
                       (:memory c))]
          (with-home (assoc (:files c) "MEMORY.md" memory)
            (fn [dir]
              (let [o    (:options c)
                    opts (cond-> {}
                           (contains? o :memoryIndexCap) (assoc :memory-index-cap (cap-opt (:memoryIndexCap o)))
                           (contains? o :notes) (assoc :notes (:notes o)))
                    soul (:soul (home/compose-soul dir opts))
                    ex   (:expect c)]
                (is (= (:soulSha256 ex) (sha256-hex soul)))
                (is (= (:soulBytes ex) (utf8-len soul)))
                (is (str/ends-with? soul (:memorySectionTail ex)))
                (is (= (:equalsUncappedSoul ex) (= soul (:soul (home/compose-soul dir)))))
                (is (= memory (fs/read-file (str dir "/MEMORY.md"))) "disk unchanged")))))))))

(deftest index-cap-rejects-bad-values
  (with-home {"MEMORY.md" "x"}
    (fn [dir]
      (doseq [bad [{:max-lines 0} {:max-bytes 0} {:max-lines 1.5}]]
        (is (thrown? Throwable (home/compose-soul dir {:memory-index-cap bad})))
        (is (thrown? Throwable (home/from-dir dir {:memory-index-cap bad})))))))

(deftest from-dir-applies-the-cap-and-user-md-is-not-capped
  (let [big (apply str (map #(str "- l" % "\n") (range 300)))]
    (with-home {"MEMORY.md" big "USER.md" big}
      (fn [dir]
        (let [soul (:soul (home/from-dir dir {:memory-index-cap true :notes true}))]
          (is (str/includes? soul "## USER.md\n\n- l0"))
          (is (str/includes? soul "- l299\n\n## MEMORY.md") "USER.md is not capped")
          (is (str/ends-with? soul (home/memory-index-notice 200 25600 true))))))))

;; ---------------------------------------------------------------------------
;; G2 — the notes tier of the memory tool
;; ---------------------------------------------------------------------------

(deftest memory-tool-shapes-match-fixture
  (let [fx (:memoryTool (fixture "memory-hierarchy"))]
    (with-home {}
      (fn [dir]
        (let [off (home/memory-tool dir)
              on  (home/memory-tool dir {:notes true})]
          (is (= (:description (:notesOff fx)) (:description off)))
          (is (= (:inputSchema (:notesOff fx)) (:input-schema off)))
          (is (= (:description (:notesOn fx)) (:description on)))
          (is (= (:inputSchema (:notesOn fx)) (:input-schema on)))
          (testing "property order is fixture-pinned"
            (is (= [:action :target :text :with] (vec (keys (:properties (:input-schema on))))))
            (is (= [:action :target :text :with] (vec (keys (:properties (:input-schema off))))))))))))

(deftest memory-tool-fixture-cases
  (doseq [c (:cases (:memoryTool (fixture "memory-hierarchy")))]
    (testing (:name c)
      (with-home (:seed c)
        (fn [dir]
          (let [t (home/memory-tool dir {:notes (:notes c)})]
            (doseq [op (:ops c)]
              (is (= {:output (:output (:expect op)) :isError (:isError (:expect op))}
                     (call t (:args op)))
                  (pr-str (:args op)))))
          (is (= (into {} (map (fn [[k v]] [(kstr k) v]) (:expectFiles c))) (tree dir)))
          (when-let [s (:soulMustNotContain c)]
            (is (not (str/includes? (:soul (home/compose-soul dir {:notes true})) s)))))))))

(deftest notes-off-is-byte-identical
  (with-home {}
    (fn [dir]
      (let [plain (home/memory-tool dir)
            off   (home/memory-tool dir {:notes false})]
        (is (= (:description plain) (:description off)))
        (is (= (:input-schema plain) (:input-schema off)))
        (is (= {:output "ok (add → MEMORY.md); loads next session" :isError false}
               (call off {:action "add" :target "note:x" :text "t"}))
            "notes off: an unrecognised target still means self")
        (is (= {"MEMORY.md" "- t\n"} (tree dir)))))))

(deftest list-ignores-invalid-slugs-and-directories
  (with-home {"memory/ok.md" "abc" "memory/Bad.md" "x" "memory/sub/x.md" "y" "memory/notmd.txt" "z"}
    (fn [dir]
      (is (= "ok\t3" (:output (call (home/memory-tool dir {:notes true}) {:action "list"})))))))

;; ---------------------------------------------------------------------------
;; G3 — transcript search
;; ---------------------------------------------------------------------------

(defn- stub-store [conversations reads]
  {:list (fn [] (swap! reads inc) (mapv (fn [c] {:id (:id c)}) conversations))
   :get  (fn [id] (swap! reads inc) (some #(when (= id (:id %)) (:messages %)) conversations))
   :save (fn [& _] (throw (ex-info "save must never be called" {})))})

(deftest transcript-search-fixture
  (let [fx    (:transcriptSearch (fixture "memory-hierarchy"))
        reads (atom 0)
        store (stub-store (:conversations (:store fx)) reads)
        dflt  (ts/transcript-search-tool store)]
    (is (= "transcript_search" (:name dflt) (:name (:defaults fx))))
    (is (= (:description fx) (:description dflt)))
    (is (= (:inputSchema fx) (:input-schema dflt)))
    (doseq [c (:cases fx)]
      (testing (:name c)
        (let [o (:options c)
              t (if o
                  (ts/transcript-search-tool store (cond-> {}
                                                     (:snippetChars o) (assoc :snippet-chars (:snippetChars o))
                                                     (:maxResults o) (assoc :max-results (:maxResults o))))
                  dflt)]
          (is (= {:output (:output (:expect c)) :isError (:isError (:expect c))}
                 (call t (:args c)))))))
    (testing "storeWithoutList"
      (is (= (:error (:storeWithoutList fx))
             (try (ts/transcript-search-tool {:get (fn [_] nil) :save (fn [& _])}) nil
                  (catch Throwable e (ex-message e))))))
    (testing "emptyQueryDoesNotReadStore"
      (reset! reads 0)
      (is (:isError (call dflt {:query "  "})))
      (is (zero? @reads)))
    (testing "bad options throw"
      (is (thrown? Throwable (ts/transcript-search-tool store {:max-results 0})))
      (is (thrown? Throwable (ts/transcript-search-tool store {:snippet-chars 0}))))))

;; ---------------------------------------------------------------------------
;; G4 — write safety
;; ---------------------------------------------------------------------------

(deftest concurrent-adds-lose-nothing
  (let [ws (:concurrentAdds (:writeSafety (fixture "memory-hierarchy")))]
    (with-home {}
      (fn [dir]
        (let [tools (vec (repeatedly (:writers ws) #(home/memory-tool dir)))
              done  (atom 0)
              jobs  (for [w (range (:writers ws)) n (range (:addsEach ws))] [w n])]
          (doseq [[w n] jobs]
            (proc/run-async!
             (fn []
               (call (nth tools w) {:action "add" :text (str "w" w "-" n)})
               (swap! done inc))))
          (loop [i 0] (when (and (< @done (count jobs)) (< i 2000)) (ktime/sleep! 5) (recur (inc i))))
          (let [lines (str/split-lines (fs/read-file (str dir "/" (:file ws))))]
            (is (= (:expectEntries ws) (count lines)))
            (doseq [[w n] jobs]
              (is (= 1 (count (filter #(= % (str "- w" w "-" n)) lines)))))))))))

(deftest large-file-add-keeps-every-byte
  (let [lf  (:largeFileAdd (:writeSafety (fixture "memory-hierarchy")))
        big (apply str (repeat (:bytes lf) (:fill lf)))]
    (with-home {"MEMORY.md" big}
      (fn [dir]
        (is (false? (:isError (call (home/memory-tool dir {:notes true})
                                    {:action "add" :text (:addText lf)}))))
        (is (= (str big "\n- x\n") (fs/read-file (str dir "/MEMORY.md"))))
        (is (= ["MEMORY.md"] (keys (tree dir))) "no temp file left")))))

;; ---------------------------------------------------------------------------
;; Dreaming — the dreamer preset (Mt. Moon build gate)
;; ---------------------------------------------------------------------------

(defn- msg-calls [calls]
  {:choices [{:message {:role "assistant" :content nil
                        :tool_calls (vec (map-indexed
                                          (fn [i c] {:id (str "f" (inc i)) :type "function"
                                                     :function {:name (:name c)
                                                                :arguments (json/write-str (:args c))}})
                                          calls))}}]
   :usage {:prompt_tokens 30 :completion_tokens 10 :total_tokens 40}})

(defn- msg-text [t]
  {:choices [{:message {:role "assistant" :content t}}]
   :usage {:prompt_tokens 30 :completion_tokens 10 :total_tokens 40}})

(defn- step-calls [script n]
  (:toolCalls (first (filter #(= n (:whenToolMsgs %)) script))))

(defn- respond
  "The dreaming fixture's `mockLLM.scripts`, natively. The dreamer is
  recognised by its system prompt (its model is \"inherit\"); everything else
  routes by model id. `dream-mode` picks dreamer-fix or dreamer-idle."
  [fx dream-mode body]
  (let [sys  (str (:content (first (filter #(= "system" (:role %)) (:messages body)))))
        tms  (filterv #(= "tool" (:role %)) (:messages body))
        n    (count tms)
        fix  (:dreamer-fix (:scripts (:mockLLM fx)))]
    (cond
      (str/includes? sys "You are dreaming:")
      (if (= :idle @dream-mode)
        (msg-text "HEARTBEAT_OK")
        (case n
          0 (msg-calls (step-calls fix 0))
          1 (msg-calls (step-calls fix 1))
          2 (if (and (str/includes? (str (:content (nth tms 0))) "Mt. Moon exit is south")
                     (str/includes? (str (:content (nth tms 1))) "exit is north"))
              (msg-calls (step-calls fix 2))
              (msg-text "HEARTBEAT_OK"))
          3 (msg-calls (step-calls fix 3))
          (msg-text (:text (last fix)))))

      (= "m-navigator" (:model body))
      (msg-text (if (str/includes? sys "Mt. Moon exit is north")
                  "Head north out of Mt. Moon."
                  "Head south out of Mt. Moon."))

      :else (msg-text "HEARTBEAT_OK"))))

(defn- mock [fx dream-mode]
  (let [requests (atom [])]
    {:requests requests
     :http-client (fn [_url _headers body]
                    (let [b (json/read-str body)]
                      (swap! requests conj b)
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (respond fx dream-mode b))}))}))

(defn- dream-fx [] (fixture "dreaming"))

(defn- dream-store [fx] (stub-store (:conversations (:store fx)) (atom 0)))

(defn- run-once [agent-def http-client prompt]
  (let [r (rt/create-runtime {:registry {(:name agent-def) agent-def}
                              :llm {:base-url "http://mock.local" :model "m-base"}
                              :http-client http-client})]
    (rt/run-agent r (:name agent-def) prompt)))

(deftest dream-prompt-is-byte-identical
  (is (= (:dreamPrompt (dream-fx)) home/dream-prompt)))

(deftest d1-mt-moon-build-gate
  (let [fx  (dream-fx)
        g   (:D1_mtMoonBuildGate (:expect fx))
        mode (atom :fix)
        {:keys [http-client]} (mock fx mode)]
    (with-home (:home fx)
      (fn [dir]
        (let [before (run-once (home/from-dir dir {:model (:model (:before g))})
                               http-client (:prompt (:before g)))]
          (is (= (:text (:before g)) (:text before))))
        (let [d    (home/dreamer dir {:store (dream-store fx)})
              pass (run-once d http-client home/heartbeat-prompt)]
          (is (= (:replyText (:pass g)) (:text pass))))
        (doseq [[k v] (:files (:after g))]
          (is (= v (fs/read-file (str dir "/" (kstr k)))) (kstr k)))
        (let [nxt (:nextSession (:after g))
              after (run-once (home/from-dir dir {:model (:model nxt)}) http-client (:prompt nxt))]
          (is (= (:text nxt) (:text after)) "a NEXT session answers differently"))
        (testing "D2: a second pass over the fixed memory is silent and writes nothing"
          (let [files (tree dir)
                pass2 (run-once (home/dreamer dir {:store (dream-store fx)}) http-client home/heartbeat-prompt)]
            (is (str/includes? (:text pass2) "HEARTBEAT_OK"))
            (is (= files (tree dir)))))))))

(deftest d2-idle-dream-is-silent
  (let [fx (dream-fx)
        {:keys [http-client]} (mock fx (atom :idle))]
    (with-home (:home fx)
      (fn [dir]
        (let [files (tree dir)
              pass  (run-once (home/dreamer dir {:store (dream-store fx)}) http-client home/heartbeat-prompt)]
          (is (str/includes? (:text pass) "HEARTBEAT_OK"))
          (is (= files (tree dir))))))))

(deftest d3-dreamer-tools-and-soul
  (let [fx (dream-fx)
        d3 (:D3_dreamerToolsExactly (:expect fx))
        sb (:D3b_dreamerSoul (:expect fx))
        mk (fn [nm] (native/native-tool {:name nm :description nm
                                         :input-schema {:type "object" :properties {}}
                                         :run (fn [_] "ok")}))]
    (with-home (:home fx)
      (fn [dir]
        (let [persona (home/from-dir dir {:tools (mapv mk (:personaTools d3))})
              d       (home/dreamer dir {:store (dream-store fx)})]
          (is (= (into (:personaTools d3) ["memory"]) (mapv :name (:tools persona))))
          (is (= (:dreamerTools d3) (mapv :name (:tools d))))
          (is (= home/memory-notes-description (:description (first (:tools d)))))
          (is (= (:soulWithoutExtras sb) (:soul d)))
          (is (= (str (last (str/split dir #"/")) "-dreamer") (:name d)))
          (is (= "inherit" (:model d)))
          (is (= (str "dreams over the memory in " dir) (:does d))))
        (fs/write-file (str dir "/DREAM.md") (:DREAM.md sb))
        (is (= (:soul sb) (:soul (home/dreamer dir {:store (dream-store fx)
                                                    :instructions (:instructions sb)}))))
        (is (= "dreamer: store is required"
               (try (home/dreamer dir {}) nil (catch Throwable e (ex-message e)))))
        (is (= "transcriptSearchTool: the store must implement list()"
               (try (home/dreamer dir {:store {:get (fn [_] nil)}}) nil
                    (catch Throwable e (ex-message e)))))
        (is (thrown? Throwable (home/from-dir dir {:dream {:every-ms 0 :store (dream-store fx)}})))))))

(defn- until! [pred]
  (loop [i 0]
    (if (or (pred) (>= i 400))
      (boolean (pred))
      (do (ktime/sleep! 5) (recur (inc i))))))

(deftest d4-from-dir-dream-runs-on-the-same-runtime
  (let [fx    (dream-fx)
        g     (:D4_fromDirDream (:expect fx))
        {:keys [http-client]} (mock fx (atom :fix))
        heard (atom [])]
    (with-home (:home fx)
      (fn [dir]
        (let [clock   (rt/virtual-clock)
              persona (home/from-dir dir {:model "m-quiet" :dream {:every-ms 50 :store (dream-store fx)}})
              started (home/start-agent persona
                                        {:llm {:base-url "http://mock.local" :model "m-base"}
                                         :http-client http-client :clock clock}
                                        {:every-ms 1000 :on-beat #(swap! heard conj %)})]
          (try
            (is (= [(:name persona) (str (:name persona) "-dreamer")]
                   (mapv #(str/replace (last (str/split (:id %) #"/")) #"\.\d+$" "") (rt/handles (:runtime started))))
                "the dreamer is spawned under the same runtime's root")
            ((:advance! clock) 50)
            (is (until! #(str/includes? (str (when (fs/exists? (str dir "/memory/log.md"))
                                               (fs/read-file (str dir "/memory/log.md"))))
                                        "dream: ")))
            (doseq [[k v] (:files (:after g))]
              (is (= v (fs/read-file (str dir "/" (kstr k)))) (kstr k)))
            (ktime/sleep! 60)
            (is (= [] @heard) "dream replies never reach on-beat")
            (is (= [] @(:beats started)))
            (finally ((:stop started)))))))))

(deftest d5-absent-dream-is-unchanged
  (let [fx (dream-fx)
        {:keys [http-client requests]} (mock fx (atom :fix))]
    (with-home (:home fx)
      (fn [dir]
        (let [files   (tree dir)
              clock   (rt/virtual-clock)
              persona (home/from-dir dir {:model "m-quiet"})
              started (home/start-agent persona
                                        {:llm {:base-url "http://mock.local" :model "m-base"}
                                         :http-client http-client :clock clock}
                                        {:every-ms 50})]
          (try
            (is (not (contains? persona ::home/dream)))
            (is (= 1 (count (rt/handles (:runtime started)))) "only the persona is spawned")
            (dotimes [_ 4]
              ((:advance! clock) 50)
              (until! #(= "idle" (:state (rt/inspect (:runtime started) (:handle started))))))
            (is (until! #(pos? (count @requests))))
            (is (every? #(= "m-quiet" (:model %)) @requests))
            (is (= files (tree dir)))
            (finally ((:stop started)))))))))
