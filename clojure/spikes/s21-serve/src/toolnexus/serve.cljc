;; S21 — the INBOUND side. `toolkit.serve` (SPEC §7B A2A + §7C MCP server),
;; both profiles, on one koine.server, including every error path the spec
;; names and the ones it forgets to name.
;;
;; S17 proved the happy path composes. This spike asks the harder question:
;;
;;   does serve() hold up when things go WRONG, on both hosts?
;;
;; So: a fulfilment that throws, a tool that throws, an unknown task id, an
;; unknown method, a malformed body, a profile that is absent. After every one
;; of those the server must still answer the NEXT request — that is the whole
;; point of "never crashes the server", and it is not provable by asserting on
;; the failing call alone.
;;
;; Four servers are started, because absence is a behaviour too:
;;
;;   S1  a2a(configured) + mcp(configured)   card fields, provider, skills filter,
;;                                           mcp.tools filter, configured serverInfo
;;   S2  a2a{}           + mcp{}             DEFAULTS, and the whole task lifecycle
;;   S3  a2a only                            => POST /mcp 404
;;   S4  mcp only                            => GET card 404, POST / 404
;;
;; Hermetic: 127.0.0.1 only, no LLM (fulfilment is a local fn — §7B's contract
;; is about the Task state machine, not about what the client loop does inside
;; it; S16/S17 cover the loop). One .cljc, no reader conditional, no java.*.

(ns toolnexus.serve
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as http]
            [koine.time :as ktime]
            [koine.server :as server]))

;; ---------------------------------------------------------------------------
;; ids — deliberately NOT in the report (§ non-determinism rule)
;; ---------------------------------------------------------------------------

(def ^:private ids (atom 0))
(defn- next-id [] (swap! ids inc))

;; §0.2 — the sanitizer §7A/§7B skill ids go through and §7C tool names DO NOT.
(defn sanitize [s] (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))

;; ---------------------------------------------------------------------------
;; §0.5 skills — the SkillSource the Agent Card advertises
;; ---------------------------------------------------------------------------

(defn- parse-frontmatter [text]
  (let [lines (str/split-lines text)]
    (if-not (= "---" (str/trim (first lines)))
      [{} text]
      (loop [remaining (rest lines) acc {}]
        (cond
          (empty? remaining)                     [acc ""]
          (= "---" (str/trim (first remaining))) [acc (str/join "\n" (rest remaining))]
          :else (let [line (first remaining)
                      idx  (str/index-of line ":")]
                  (recur (rest remaining)
                         (if idx
                           (assoc acc (keyword (str/trim (subs line 0 idx)))
                                  (str/trim (subs line (inc idx))))
                           acc))))))))

(defn- parent-dir [path]
  (let [idx (str/last-index-of path "/")] (if idx (subs path 0 idx) ".")))

(defn discover-skills [root]
  (->> (fs/find-files root "SKILL.md")
       (reduce (fn [acc path]
                 (let [[meta body] (parse-frontmatter (fs/read-file path))
                       nm          (:name meta)]
                   (if (or (str/blank? (str nm)) (contains? acc nm))
                     acc
                     (assoc acc nm {:name        nm
                                    :description (or (:description meta) "")
                                    :body        (str/trim body)
                                    :dir         (parent-dir path)}))))
               {})))

(defn skill-tool [skills]
  {:name        "skill"
   :description "Load a skill's instructions and resources on demand."
   :inputSchema {:type "object"
                 :properties {:name {:type "string"}}
                 :required ["name"]}
   :source      "skill"
   :execute     (fn [args]
                  (if-let [s (get skills (str (:name args)))]
                    {:output (str "<skill_content name=\"" (:name s) "\">") :isError false}
                    {:output (str "unknown skill: " (:name args)) :isError true}))})

;; ---------------------------------------------------------------------------
;; §0.8 native tools + the toolkit
;; ---------------------------------------------------------------------------

(defn native-tool [tool-name description f]
  {:name tool-name :description description
   :inputSchema {:type "object"} :source "native"
   :execute (fn [args] {:output (str (f args)) :isError false})})

(defn toolkit [tools]
  {:tools (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools)})

(defn tool-names [tk] (vec (sort (keys (:tools tk)))))

;; ---------------------------------------------------------------------------
;; §7B — the Agent Card
;; ---------------------------------------------------------------------------

(defn agent-card
  "Every field §7B names, with §7B's defaults when the profile omits them.
  `provider` is present ONLY when configured. `skills[]` come from the
  SkillSource — never from the tools — filtered to `a2a.skills` when given."
  [cfg base skills]
  (let [allow    (:skills cfg)
        visible  (cond->> (vals skills)
                   (seq allow) (filter (fn [s] (contains? (set allow) (:name s)))))
        card     {:name               (or (:name cfg) "toolnexus-agent")
                  :description        (or (:description cfg) "")
                  :version            (or (:version cfg) "0.1.0")
                  :protocolVersion    "0.3.0"
                  :capabilities       {:streaming false :pushNotifications false}
                  :defaultInputModes  ["text"]
                  :defaultOutputModes ["text"]
                  :skills             (mapv (fn [s] {:id (sanitize (:name s))
                                                     :name (:name s)
                                                     :description (:description s)})
                                            (sort-by :name visible))
                  :url                (str base "/")}]
    (if (:provider cfg) (assoc card :provider (:provider cfg)) card)))

;; ---------------------------------------------------------------------------
;; §7B — the JSON-RPC endpoint
;; ---------------------------------------------------------------------------

(defn- rpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- parse-body
  "Returns [:ok msg] or [:parse-error]. A malformed body must become -32700,
  never a 500 and never a dead server, so this is the only place a decode
  happens and it catches Throwable (portable on both hosts)."
  [body]
  (let [r (try (let [m (json/read-str body)] (if (map? m) [:ok m] [:parse-error]))
               (catch Throwable _ [:parse-error]))]
    r))

(defn- fulfil!
  "§7B async fulfilment. `working` is saved BEFORE the run so a peer can observe
  it; a throw becomes `failed` with status.message, and — the load-bearing part
  — nothing escapes this fn, so the server thread is untouched either way."
  [tasks task-id text run-fn on-task]
  (swap! tasks assoc-in [task-id :status :state] "working")
  (let [settled (try
                  (let [r (run-fn text)]
                    {:id task-id
                     :status {:state "completed"}
                     :artifacts [{:artifactId (str "artifact-" (next-id))
                                  :parts [{:kind "text" :text (:text r)}]}]})
                  (catch Throwable e
                    {:id task-id
                     :status {:state "failed"
                              :message {:role "agent"
                                        :parts [{:kind "text" :text (str (ex-message e))}]}}}))]
    (swap! tasks assoc task-id settled)
    (when on-task (on-task {:id task-id :task text :state (get-in settled [:status :state])}))
    settled))

(defn- a2a-rpc [tasks run-fn on-task body]
  (let [[tag msg] (parse-body body)]
    (if (= tag :parse-error)
      (rpc-error nil -32700 "Parse error")
      (let [id (:id msg)]
        (case (:method msg)
          "SendMessage"
          (let [task-id (str "task-" (next-id))
                text    (->> (get-in msg [:params :message :parts]) (keep :text) (str/join " "))
                task    {:id task-id :status {:state "submitted"}}]
            (swap! tasks assoc task-id task)
            (future (fulfil! tasks task-id text run-fn on-task))
            ;; returned IMMEDIATELY, in the submitted state
            {:jsonrpc "2.0" :id id :result task})

          "GetTask"
          (if-let [t (get @tasks (get-in msg [:params :id]))]
            {:jsonrpc "2.0" :id id :result t}
            (rpc-error id -32001 "Task not found"))

          (rpc-error id -32601 "Method not found"))))))

;; ---------------------------------------------------------------------------
;; §7C — the MCP streamable-HTTP endpoint
;; ---------------------------------------------------------------------------

(defn- exposed-tools
  "`mcp.tools` filters to exactly those names; UNKNOWN NAMES ARE IGNORED, never
  an error. Omitted ⇒ every toolkit tool."
  [tk cfg]
  (let [allow (:tools cfg)
        ts    (vals (:tools tk))]
    (sort-by :name (if (seq allow) (filter (fn [t] (contains? (set allow) (:name t))) ts) ts))))

(defn- mcp-rpc [tk cfg on-call body]
  (let [[tag msg] (parse-body body)]
    (if (= tag :parse-error)
      (rpc-error nil -32700 "Parse error")
      (let [id     (:id msg)
            params (:params msg)]
        (case (:method msg)
          "initialize"
          {:jsonrpc "2.0" :id id
           :result {:protocolVersion "2024-11-05"
                    :capabilities {:tools {}}
                    :serverInfo {:name (or (:name cfg) "toolnexus")
                                 :version (or (:version cfg) "0.1.0")}}}

          "tools/list"
          {:jsonrpc "2.0" :id id
           :result {:tools (mapv (fn [t]
                                   ;; §7C: Tool.name VERBATIM. No sanitize here —
                                   ;; that is the §7A/§7B skill-id rule, not this one.
                                   {:name (:name t)
                                    :description (:description t)
                                    :inputSchema (:inputSchema t)})
                                 (exposed-tools tk cfg))}}

          "tools/call"
          (let [tool-name (:name params)
                t         (first (filter (fn [x] (= tool-name (:name x))) (exposed-tools tk cfg)))]
            (if-not t
              (rpc-error id -32602 (str "Unknown tool: " tool-name))
              (let [r (try ((:execute t) (or (:arguments params) {}))
                           (catch Throwable e {:output (str (ex-message e)) :isError true}))]
                (when on-call (on-call {:name tool-name :source (:source t) :isError (boolean (:isError r))}))
                {:jsonrpc "2.0" :id id
                 :result {:content [{:type "text" :text (:output r)}]
                          :isError (boolean (:isError r))}})))

          (rpc-error id -32601 "Method not found"))))))

;; ---------------------------------------------------------------------------
;; serve — both profiles, co-mounted, each strictly opt-in
;; ---------------------------------------------------------------------------

(defn serve-toolkit
  "opts {:a2a cfg|nil :mcp cfg|nil :run-fn f :on-task f :on-call f}.
  A nil profile mounts NOTHING for that protocol; its paths 404 like any other."
  [tk skills {:keys [a2a mcp run-fn on-task on-call]}]
  (let [tasks (atom {})
        base  (atom "")
        calls (atom [])
        h     (server/serve
                (fn [req]
                  (let [path (:path req)
                        json-res (fn [m] {:status 200
                                          :headers {"content-type" "application/json"}
                                          :body (json/write-str m)})]
                    (cond
                      (and a2a (= path "/.well-known/agent-card.json"))
                      (json-res (agent-card a2a @base skills))

                      (and a2a (= path "/"))
                      (json-res (a2a-rpc tasks run-fn on-task (:body req)))

                      (and mcp (= path "/mcp"))
                      (json-res (mcp-rpc tk mcp
                                         (fn [c] (swap! calls conj c) (when on-call (on-call c)))
                                         (:body req)))

                      :else {:status 404 :headers {"content-type" "text/plain"} :body "Not Found"})))
                {:port 0})
        url   (str "http://127.0.0.1:" (server/port h))]
    (reset! base url)
    {:handle h :url url :tasks tasks :calls calls}))

;; ---------------------------------------------------------------------------
;; the peer side — a plain A2A / MCP client over koine.http
;; ---------------------------------------------------------------------------

(defn- post-raw [url body]
  (http/post-json url {} body))

(defn- rpc! [url method params]
  (let [res (post-raw url (json/write-str {:jsonrpc "2.0" :id (next-id)
                                           :method method :params params}))]
    (if (http/failed? res)
      (throw (ex-info (str "transport: " (name (:error res))) {:url url}))
      (json/read-str (:body res)))))

(defn- get-status [url]
  (let [res (http/request {:method :get :url url})]
    (if (http/failed? res) nil (:status res))))

(defn- post-status [url body]
  (let [res (post-raw url body)]
    (if (http/failed? res) nil (:status res))))

(defn- send-message! [base text]
  (rpc! (str base "/") "SendMessage"
        {:message {:role "user" :parts [{:kind "text" :text text}]}}))

(defn- get-task [base task-id] (rpc! (str base "/") "GetTask" {:id task-id}))

(defn- poll-for
  "Poll GetTask until `pred` holds on the state, or give up. Returns the task."
  [base task-id pred limit]
  (loop [n 0]
    (let [t     (get-in (get-task base task-id) [:result])
          state (get-in t [:status :state])]
      (cond
        (pred state) t
        (>= n limit) t
        :else (do (ktime/sleep! 20) (recur (inc n)))))))

;; ---------------------------------------------------------------------------
;; the fulfilment fn under test
;; ---------------------------------------------------------------------------

(defn- make-run-fn
  "`gate` lets the spike OBSERVE the working state deterministically instead of
  racing it: the fulfilment of the task named \"slow\" parks until the peer has
  seen `working` and releases the gate."
  [gate]
  (fn [text]
    (cond
      (= text "boom")
      (throw (ex-info "fulfilment exploded" {:text text}))

      (= text "slow")
      (do (loop [n 0] (when (and (not @gate) (< n 500)) (ktime/sleep! 20) (recur (inc n))))
          {:text "slow task done"})

      :else {:text (str "ran: " text)})))

;; ---------------------------------------------------------------------------
;; the spike
;; ---------------------------------------------------------------------------

(defn run-spike [examples-dir]
  (let [on-disk  (discover-skills (str examples-dir "/skills"))
        ;; The shared fixture ships exactly ONE skill, and a filter is only
        ;; meaningful over two. The second comes from a data SkillSource
        ;; (§0.5 skills-as-data) — still not a forked copy of the fixture.
        skills   (assoc on-disk "inline note"
                        {:name "inline note" :description "A data-provided skill." :body "" :dir "."})
        tk       (toolkit [(skill-tool skills)
                           ;; the dot is the point: sanitize would make it
                           ;; "calc_sum". §7C must NOT touch it.
                           ;; strings only: a number's rendering is a host
                           ;; question, and this spike is not about that.
                           (native-tool "calc.sum" "Join two strings" (fn [a] (str (:x a) "|" (:y a))))
                           (native-tool "echo_ok" "Echo" (fn [a] (str "echo:" (:msg a))))
                           {:name "kaboom" :description "Always throws" :source "native"
                            :inputSchema {:type "object"}
                            :execute (fn [_] (throw (ex-info "tool exploded" {})))}])
        gate     (atom false)
        run-fn   (make-run-fn gate)

        ;; ---- S1: both profiles, fully configured
        s1 (serve-toolkit tk skills
                          {:a2a {:name "s21-agent" :description "S21 inbound spike"
                                 :version "9.9.9"
                                 :provider {:organization "toolnexus" :url "https://example.invalid"}
                                 :skills ["hello-world"]}
                           :mcp {:name "s21-mcp" :version "2.0.0"
                                 :tools ["calc.sum" "echo_ok" "no-such-tool"]}
                           :run-fn run-fn})
        ;; ---- S2: both profiles, EMPTY config => every §7B/§7C default
        s2 (serve-toolkit tk skills {:a2a {} :mcp {} :run-fn run-fn})
        ;; ---- S3 / S4: one profile each, to prove absence
        s3 (serve-toolkit tk skills {:a2a {} :run-fn run-fn})
        s4 (serve-toolkit tk skills {:mcp {}})]
    (try
      (let [;; ================= §7B agent card =================
            card1 (json/read-str (:body (http/request {:method :get :url (str (:url s1) "/.well-known/agent-card.json")})))
            card2 (json/read-str (:body (http/request {:method :get :url (str (:url s2) "/.well-known/agent-card.json")})))

            ;; ================= §7B task lifecycle =================
            ;; 1. submitted returned immediately, 2. working observable,
            ;; 3. completed with artifacts.
            slow-sent (send-message! (:url s2) "slow")
            slow-id   (get-in slow-sent [:result :id])
            working   (poll-for (:url s2) slow-id #(= "working" %) 200)
            _         (reset! gate true)
            completed (poll-for (:url s2) slow-id #(= "completed" %) 400)

            ;; 4. a fulfilment that THROWS
            boom-sent (send-message! (:url s2) "boom")
            boom-id   (get-in boom-sent [:result :id])
            failed    (poll-for (:url s2) boom-id #(contains? #{"failed" "completed"} %) 400)

            ;; 5. …and the server is STILL ANSWERING afterwards. This is the
            ;;    assertion that "never crashes the server" actually means.
            after     (send-message! (:url s2) "after the crash")
            after-id  (get-in after [:result :id])
            after-t   (poll-for (:url s2) after-id #(= "completed" %) 400)

            ;; ================= §7B error paths =================
            unknown-task   (get-task (:url s2) "task-does-not-exist")
            unknown-method (rpc! (str (:url s2) "/") "NoSuchMethod" {})
            parse-res      (post-raw (str (:url s2) "/") "{not json at all")
            parse-body-m   (json/read-str (:body parse-res))
            ;; and again: still alive after a parse error
            after-parse    (rpc! (str (:url s2) "/") "NoSuchMethod" {})

            ;; ================= §7C MCP =================
            init1  (rpc! (str (:url s1) "/mcp") "initialize" {})
            init2  (rpc! (str (:url s2) "/mcp") "initialize" {})
            list2  (rpc! (str (:url s2) "/mcp") "tools/list" {})
            list1  (rpc! (str (:url s1) "/mcp") "tools/list" {})
            names2 (mapv :name (get-in list2 [:result :tools]))
            names1 (mapv :name (get-in list1 [:result :tools]))

            call-ok  (rpc! (str (:url s2) "/mcp") "tools/call" {:name "echo_ok" :arguments {:msg "hi"}})
            call-err (rpc! (str (:url s2) "/mcp") "tools/call" {:name "skill" :arguments {:name "nope"}})
            call-thr (rpc! (str (:url s2) "/mcp") "tools/call" {:name "kaboom" :arguments {}})
            ;; still alive after a tool threw
            call-after (rpc! (str (:url s2) "/mcp") "tools/call" {:name "calc.sum" :arguments {:x "a" :y "b"}})
            call-unk   (rpc! (str (:url s2) "/mcp") "tools/call" {:name "not_a_tool" :arguments {}})
            ;; a tool that EXISTS in the toolkit but is filtered out of s1
            call-filtered (rpc! (str (:url s1) "/mcp") "tools/call" {:name "kaboom" :arguments {}})
            mcp-parse  (json/read-str (:body (post-raw (str (:url s2) "/mcp") "}{")))

            ;; ================= absence =================
            s3-mcp-post  (post-status (str (:url s3) "/mcp") "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
            s4-card      (get-status (str (:url s4) "/.well-known/agent-card.json"))
            s4-root      (post-status (str (:url s4) "/")
                                      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"x\"}}")
            s4-mcp-alive (rpc! (str (:url s4) "/mcp") "initialize" {})
            s3-card      (get-status (str (:url s3) "/.well-known/agent-card.json"))]

        {:host (name host/id)

         :card
         {:configured
          {:keys       (vec (sort (map name (keys card1))))
           :name       (:name card1)
           :description (:description card1)
           :version    (:version card1)
           :protocolVersion (:protocolVersion card1)
           :capabilities (:capabilities card1)
           :defaultInputModes (:defaultInputModes card1)
           :defaultOutputModes (:defaultOutputModes card1)
           :provider-present (contains? card1 :provider)
           :provider-keys (vec (sort (map name (keys (:provider card1)))))
           :skills     (mapv (fn [s] {:id (:id s) :name (:name s)}) (:skills card1))
           :skill-descriptions-nonempty (every? (fn [s] (not (str/blank? (:description s)))) (:skills card1))
           :url-is-base-slash (= (str (:url s1) "/") (:url card1))
           ;; §7B: skills[] is the SkillSource, NEVER the tools
           :no-tool-leaked (empty? (filter (set (tool-names tk)) (map :id (:skills card1))))}
          :defaults
          {:keys       (vec (sort (map name (keys card2))))
           :name       (:name card2)
           :description (:description card2)
           :version    (:version card2)
           :protocolVersion (:protocolVersion card2)
           :streaming  (get-in card2 [:capabilities :streaming])
           :pushNotifications (get-in card2 [:capabilities :pushNotifications])
           :provider-present (contains? card2 :provider)
           ;; the §7B/§7C asymmetry in one line: the skill ID is sanitized
           ;; ("inline note" -> "inline_note"), the skill NAME is not, and §7C
           ;; tool names below are not either.
           :skills     (mapv (fn [s] {:id (:id s) :name (:name s)}) (:skills card2))}
          :filter
          {:source-skill-count (count skills)
           :configured-count   (count (:skills card1))
           :unfiltered-count   (count (:skills card2))}}

         :a2a
         {:send-immediate-state (get-in slow-sent [:result :status :state])
          :send-has-id          (some? slow-id)
          :working-observed     (get-in working [:status :state])
          :completed
          {:state          (get-in completed [:status :state])
           :artifact-count (count (:artifacts completed))
           :artifact-id-present (some? (get-in completed [:artifacts 0 :artifactId]))
           :part-kinds     (mapv :kind (get-in completed [:artifacts 0 :parts]))
           :text           (get-in completed [:artifacts 0 :parts 0 :text])}
          :failed
          {:state          (get-in failed [:status :state])
           :message-role   (get-in failed [:status :message :role])
           :part-kinds     (mapv :kind (get-in failed [:status :message :parts]))
           :text           (get-in failed [:status :message :parts 0 :text])
           :no-artifacts   (nil? (:artifacts failed))}
          :server-alive-after-failed-task
          {:state (get-in after-t [:status :state])
           :text  (get-in after-t [:artifacts 0 :parts 0 :text])}
          :errors
          {:unknown-task   {:code (get-in unknown-task [:error :code])
                            :has-result (contains? unknown-task :result)}
           :unknown-method {:code (get-in unknown-method [:error :code])}
           :parse-error    {:http-status (:status parse-res)
                            :code (get-in parse-body-m [:error :code])
                            :id-null (nil? (:id parse-body-m))}
           :alive-after-parse-error {:code (get-in after-parse [:error :code])}}}

         :mcp
         {:initialize
          {:configured {:serverInfo (get-in init1 [:result :serverInfo])
                        :has-tools-capability (contains? (get-in init1 [:result :capabilities]) :tools)}
           :defaults   {:serverInfo (get-in init2 [:result :serverInfo])
                        :protocolVersion (get-in init2 [:result :protocolVersion])}}
          :tools-list
          {:all           names2
           :count         (count names2)
           :verbatim      {:toolkit-name "calc.sum"
                           :served-name  (first (filter #(= "calc.sum" %) names2))
                           :would-be-if-sanitized (sanitize "calc.sum")
                           :re-sanitized? (contains? (set names2) (sanitize "calc.sum"))}
           :has-schemas   (every? (fn [t] (map? (:inputSchema t))) (get-in list2 [:result :tools]))}
          :filter
          {:configured        names1
           :count             (count names1)
           :unknown-ignored   (not (contains? (set names1) "no-such-tool"))
           :filtered-out      (vec (sort (remove (set names1) names2)))
           :error?            (contains? list1 :error)
           ;; a filtered-out tool is not callable either
           :filtered-call-code (get-in call-filtered [:error :code])}
          :call
          {:ok      {:content-types (mapv :type (get-in call-ok [:result :content]))
                     :text (get-in call-ok [:result :content 0 :text])
                     :isError (get-in call-ok [:result :isError])}
           :is-error-propagates
                    {:isError (get-in call-err [:result :isError])
                     :text (get-in call-err [:result :content 0 :text])}
           :execute-throw
                    {:isError (get-in call-thr [:result :isError])
                     :text (get-in call-thr [:result :content 0 :text])
                     :has-rpc-error (contains? call-thr :error)}
           :alive-after-throw
                    {:text (get-in call-after [:result :content 0 :text])
                     :isError (get-in call-after [:result :isError])}
           :unknown-tool
                    {:code (get-in call-unk [:error :code])
                     :has-result (contains? call-unk :result)}
           :on-call-events (mapv (fn [c] {:name (:name c) :isError (:isError c)}) @(:calls s2))}
          :parse-error {:code (get-in mcp-parse [:error :code])}}

         :absent
         {:mcp-profile-absent {:mcp-post-status s3-mcp-post
                              :card-status s3-card}
          :a2a-profile-absent {:card-status s4-card
                              :root-post-status s4-root
                              :mcp-still-works (get-in s4-mcp-alive [:result :serverInfo :name])}}})
      (finally
        (server/stop! (:handle s1))
        (server/stop! (:handle s2))
        (server/stop! (:handle s3))
        (server/stop! (:handle s4))))))

(defn -main [& _]
  (let [dir (env/get-env "TN_EXAMPLES")]
    (when-not dir (throw (ex-info "set TN_EXAMPLES" {})))
    (println (json/write-str (run-spike dir)))))
