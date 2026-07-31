;; S17 — everything at once. The question is no longer "does each source port"
;; but "do they COMPOSE", which is the only question that matters, because
;; toolnexus is a composition library.
;;
;; The chain this spike builds and then drives end to end:
;;
;;   A2A peer  --JSON-RPC-->  serve() §7B
;;                              |
;;                              +-- client loop §0.10 (scripted LLM over HTTP)
;;                                    |
;;                                    +-- toolkit T2, whose tools come from
;;                                          MCP streamable-HTTP §2  ---------+
;;                                                                           |
;;   serve() §7C  <--------------------------------------------------------- +
;;     re-exposes toolkit T1, which is
;;       MCP stdio  (a real child: @modelcontextprotocol/server-everything)
;;     + agent skills (examples/skills/**/SKILL.md, §0.5/§0.6)
;;     + a native tool (§0.8)
;;
;; So one A2A message reaches a stdio child process, a skill on disk and a
;; native fn, THROUGH an MCP-over-HTTP hop. Every leg is real; nothing is
;; stubbed. Hermetic: the only network is 127.0.0.1, the LLM is scripted.
;;
;; Still one .cljc, no reader conditional, no java.*, no Go interop.

(ns toolnexus.composition
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as http]
            [koine.process :as proc]
            [koine.time :as ktime]
            [koine.server :as server]))

;; ---------------------------------------------------------------------------
;; ids
;; ---------------------------------------------------------------------------

(def ^:private ids (atom 0))
(defn- next-id [] (swap! ids inc))

;; ---------------------------------------------------------------------------
;; §0.2  naming
;; ---------------------------------------------------------------------------

(defn sanitize [s] (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))
(defn mcp-tool-name [server tool] (str (sanitize server) "_" (sanitize tool)))

;; ---------------------------------------------------------------------------
;; §0.4  ToolResult shaping — one function, both transports
;; ---------------------------------------------------------------------------

(defn mcp-result [result]
  (let [text (str/join "\n" (keep :text (:content result)))]
    (cond
      (:isError result)           {:output text :isError true}
      (:structuredContent result) {:output (json/write-str (:structuredContent result)) :isError false}
      :else                       {:output text :isError false})))

;; ---------------------------------------------------------------------------
;; §2  MCP transport — stdio
;; ---------------------------------------------------------------------------

(defn- stdio-rpc! [child id method params]
  (proc/send-line! child (json/write-str {:jsonrpc "2.0" :id id :method method :params params}))
  (loop [seen 0]
    (when (> seen 500) (throw (ex-info "mcp stdio: no response" {:method method})))
    (let [line (proc/read-line! child)]
      (cond
        (nil? line)       (throw (ex-info "mcp stdio: peer exited" {:method method}))
        (str/blank? line) (recur (inc seen))
        :else (let [msg (json/read-str line)]
                (if (= id (:id msg)) msg (recur (inc seen))))))))

(defn stdio-transport
  "A transport is just {:rpc! fn :close! fn}. Everything above §2 is written
  against this map, never against a host object — which is why the same code
  drives stdio and streamable-HTTP."
  [command]
  (let [child (proc/spawn command)]
    (stdio-rpc! child (next-id) "initialize"
                {:protocolVersion "2024-11-05" :capabilities {}
                 :clientInfo {:name "toolnexus-clj" :version "0.0.1"}})
    (proc/send-line! child (json/write-str {:jsonrpc "2.0"
                                            :method "notifications/initialized"
                                            :params {}}))
    {:kind   "stdio"
     :rpc!   (fn [method params] (stdio-rpc! child (next-id) method params))
     :close! (fn [] (proc/close! child))}))

;; ---------------------------------------------------------------------------
;; §2  MCP transport — streamable-HTTP (the remote leg)
;; ---------------------------------------------------------------------------

(defn http-transport
  "The SAME §2 client over one HTTP POST per JSON-RPC message. A transport
  failure is data (koine 0.4.2 http/failed?), never a throw, so a dead remote
  server is isolated exactly like a dead stdio child (SPEC §0.3)."
  [url]
  {:kind "http"
   :rpc! (fn [method params]
           (let [res (http/post-json url {"content-type" "application/json"}
                                     (json/write-str {:jsonrpc "2.0" :id (next-id)
                                                      :method method :params params}))]
             (if (http/failed? res)
               (throw (ex-info (str "mcp http: " (name (:error res))) {:url url}))
               (json/read-str (:body res)))))
   :close! (fn [] nil)})

;; ---------------------------------------------------------------------------
;; §2  MCP tools — transport-agnostic
;; ---------------------------------------------------------------------------

(defn mcp-tools
  "tools/list over ANY transport -> uniform Tools (§0.1) with §0.2 names."
  [transport server-name]
  (->> (get-in ((:rpc! transport) "tools/list" {}) [:result :tools])
       (map (fn [t]
              {:name        (mcp-tool-name server-name (:name t))
               :description (or (:description t) "")
               :inputSchema (or (:inputSchema t) {:type "object"})
               :source      "mcp"
               :execute     (fn [args]
                              (mcp-result (:result ((:rpc! transport) "tools/call"
                                                    {:name (:name t) :arguments args}))))}))
       (sort-by :name)
       vec))

;; ---------------------------------------------------------------------------
;; §0.5 / §0.6  agent skills
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
                                    :dir         (parent-dir path)
                                    :files       (vec (sort (remove fs/directory?
                                                                    (fs/list-tree (parent-dir path)))))}))))
               {})))

(defn skill-output [skill]
  (str "<skill_content name=\"" (:name skill) "\">\n"
       "# Skill: " (:name skill) "\n\n"
       (:body skill) "\n\n"
       "Base directory for this skill: file://" (:dir skill) "\n"
       "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\n"
       "Note: file list is sampled.\n\n"
       "<skill_files>\n"
       (str/join "" (map #(str "<file>" % "</file>\n") (:files skill)))
       "</skill_files>\n"
       "</skill_content>"))

(defn skill-tool
  "§0.6 — ONE `skill` tool over every discovered skill (progressive disclosure)."
  [skills]
  {:name        "skill"
   :description "Load a skill's instructions and resources on demand."
   :inputSchema {:type "object"
                 :properties {:name {:type "string"}}
                 :required ["name"]}
   :source      "skill"
   :execute     (fn [args]
                  (if-let [s (get skills (str (:name args)))]
                    {:output (skill-output s) :isError false}
                    {:output (str "unknown skill: " (:name args)) :isError true}))})

;; ---------------------------------------------------------------------------
;; §0.8  native
;; ---------------------------------------------------------------------------

(defn native-tool [tool-name description f]
  {:name tool-name :description description
   :inputSchema {:type "object"} :source "native"
   :execute (fn [args]
              (try {:output (str (f args)) :isError false}
                   (catch Throwable e {:output (str (ex-message e)) :isError true})))})

;; ---------------------------------------------------------------------------
;; the toolkit
;; ---------------------------------------------------------------------------

(defn toolkit [tools]
  {:tools (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools)})

(defn tool-names [tk] (vec (sort (keys (:tools tk)))))

(defn execute-tool [tk tool-name args]
  (if-let [t (get (:tools tk) tool-name)]
    (try ((:execute t) args)
         (catch Throwable e {:output (str (ex-message e)) :isError true}))
    {:output (str "unknown tool: " tool-name) :isError true}))

;; ---------------------------------------------------------------------------
;; §7C  serve the toolkit AS an MCP streamable-HTTP server
;; §7B  serve the toolkit AS an A2A agent
;; both co-mounted on one koine.server, dispatched by path
;; ---------------------------------------------------------------------------

(defn- mcp-serve-handler [tk profile body]
  (let [msg    (json/read-str body)
        method (:method msg)
        id     (:id msg)
        params (:params msg)]
    (case method
      "initialize"
      {:jsonrpc "2.0" :id id
       :result {:protocolVersion "2024-11-05"
                :capabilities {:tools {}}
                :serverInfo {:name (:name profile) :version (:version profile)}}}

      "tools/list"
      {:jsonrpc "2.0" :id id
       :result {:tools (mapv (fn [t] {:name        (:name t)   ; §7C: verbatim, NOT re-sanitized
                                      :description (:description t)
                                      :inputSchema (:inputSchema t)})
                             (sort-by :name (vals (:tools tk))))}}

      "tools/call"
      (let [r (execute-tool tk (:name params) (or (:arguments params) {}))]
        {:jsonrpc "2.0" :id id
         :result {:content [{:type "text" :text (:output r)}]
                  :isError (boolean (:isError r))}})

      {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}})))

(defn- agent-card [profile base skills]
  {:name             (:name profile)
   :description      (:description profile)
   :version          (:version profile)
   :protocolVersion  "0.3.0"
   :capabilities     {:streaming false :pushNotifications false}
   :defaultInputModes  ["text"]
   :defaultOutputModes ["text"]
   ;; §7B: skills come from the SkillSource, NEVER raw tools.
   :skills           (mapv (fn [s] {:id (:name s) :name (:name s) :description (:description s)})
                           (sort-by :name (vals skills)))
   :url              (str base "/")})

(defn serve-toolkit
  "One server, both inbound profiles: A2A on / and /.well-known/agent-card.json
  (§7B), MCP streamable-HTTP on /mcp (§7C)."
  [tk skills run-fn profile]
  (let [tasks (atom {})
        h     (server/serve
                (fn [req]
                  (let [path (:path req)]
                    (cond
                      (= path "/.well-known/agent-card.json")
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (agent-card profile (:base @tasks) skills))}

                      (= path "/mcp")
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (mcp-serve-handler tk profile (:body req)))}

                      (= path "/")
                      (let [msg    (json/read-str (:body req))
                            method (:method msg)
                            id     (:id msg)]
                        (case method
                          "SendMessage"
                          (let [task-id (str "task-" (next-id))
                                text    (->> (get-in msg [:params :message :parts])
                                             (keep :text) (str/join " "))
                                task    {:id task-id :status {:state "submitted"}}]
                            (swap! tasks assoc task-id task)
                            ;; §7B — return immediately, fulfil async. A
                            ;; fulfilment error never crashes the server.
                            (future
                              (swap! tasks assoc-in [task-id :status :state] "working")
                              (let [t (try
                                        (let [r (run-fn text)]
                                          {:id task-id
                                           :status {:state "completed"}
                                           :artifacts [{:artifactId (str "art-" (next-id))
                                                        :parts [{:kind "text" :text (:text r)}]}]
                                           :calls (:calls r)})
                                        (catch Throwable e
                                          {:id task-id
                                           :status {:state "failed"
                                                    :message {:role "agent"
                                                              :parts [{:kind "text"
                                                                       :text (str (ex-message e))}]}}}))]
                                (swap! tasks assoc task-id t)))
                            {:status 200 :headers {"content-type" "application/json"}
                             :body (json/write-str {:jsonrpc "2.0" :id id :result task})})

                          "GetTask"
                          (let [t (get @tasks (get-in msg [:params :id]))]
                            {:status 200 :headers {"content-type" "application/json"}
                             :body (json/write-str
                                     (if t
                                       {:jsonrpc "2.0" :id id :result t}
                                       {:jsonrpc "2.0" :id id
                                        :error {:code -32001 :message "Task not found"}}))})

                          {:status 200 :headers {"content-type" "application/json"}
                           :body (json/write-str {:jsonrpc "2.0" :id id
                                                  :error {:code -32601 :message "Method not found"}})}))

                      :else {:status 404 :body "Not Found"})))
                {:port 0})]
    (swap! tasks assoc :base (str "http://127.0.0.1:" (server/port h)))
    {:handle h :url (str "http://127.0.0.1:" (server/port h)) :tasks tasks}))

;; ---------------------------------------------------------------------------
;; §0.10  the client loop + the scripted LLM
;; ---------------------------------------------------------------------------
;;
;; Turn 1 calls THREE tools in parallel, one per source, so the composition is
;; exercised rather than asserted: an MCP stdio tool (reached through the HTTP
;; hop), the skill tool, and a native tool.

(defn- llm-response [n]
  (if (= 1 n)
    {:choices [{:message
                {:role "assistant" :content nil
                 :tool_calls
                 [{:id "c1" :type "function"
                   :function {:name "gateway_everything_echo"
                              :arguments "{\"message\":\"composed\"}"}}
                  {:id "c2" :type "function"
                   :function {:name "gateway_skill" :arguments "{\"name\":\"hello-world\"}"}}
                  {:id "c3" :type "function"
                   :function {:name "gateway_now" :arguments "{}"}}]}}]}
    {:choices [{:message {:role "assistant" :content "composed"}}]}))

(defn scripted-llm! []
  (let [n (atom 0)]
    (server/serve (fn [_req] {:status 200 :headers {"content-type" "application/json"}
                              :body (json/write-str (llm-response (swap! n inc)))})
                  {:port 0})))

(defn run-client-loop [tk llm-base system user max-turns]
  (loop [messages [{:role "system" :content system} {:role "user" :content user}]
         turn     1
         executed []]
    (let [res  (http/post-json (str llm-base "/v1/chat/completions")
                               {"content-type" "application/json"}
                               (json/write-str {:model "mock" :messages messages}))
          msg  (get-in (json/read-str (:body res)) [:choices 0 :message])
          tcs  (mapv (fn [tc] {:id (:id tc)
                               :name (get-in tc [:function :name])
                               :args (json/read-str (or (get-in tc [:function :arguments]) "{}"))})
                     (:tool_calls msg))]
      (cond
        (empty? tcs)   {:turns turn :text (:content msg) :calls executed}
        (>= turn max-turns) {:turns turn :text "max turns reached" :calls executed}
        :else
        (let [done (mapv deref (mapv (fn [c] (future (assoc c :result (execute-tool tk (:name c) (:args c)))))
                                     tcs))]
          (recur (into (conj messages msg)
                       (mapv (fn [d] {:role "tool" :tool_call_id (:id d)
                                      :content (:output (:result d))}) done))
                 (inc turn)
                 (into executed (mapv (fn [d] {:name (:name d)
                                               :error (:isError (:result d))
                                               :bytes (count (:output (:result d)))}) done))))))))

;; ---------------------------------------------------------------------------
;; the A2A client leg (§7A shape, minimal)
;; ---------------------------------------------------------------------------

(defn a2a-send! [base text]
  (let [res (http/post-json (str base "/") {"content-type" "application/json"}
                            (json/write-str {:jsonrpc "2.0" :id (next-id) :method "SendMessage"
                                             :params {:message {:parts [{:kind "text" :text text}]}}}))]
    (get-in (json/read-str (:body res)) [:result])))

(defn a2a-get [base task-id]
  (let [res (http/post-json (str base "/") {"content-type" "application/json"}
                            (json/write-str {:jsonrpc "2.0" :id (next-id) :method "GetTask"
                                             :params {:id task-id}}))]
    (json/read-str (:body res))))

(defn a2a-await [base task-id]
  (loop [n 0]
    (let [t     (get-in (a2a-get base task-id) [:result])
          state (get-in t [:status :state])]
      (cond
        (contains? #{"completed" "failed"} state) t
        (> n 600) (throw (ex-info "a2a: task did not settle" {:id task-id :state state}))
        :else (do (ktime/sleep! 50) (recur (inc n)))))))

;; ---------------------------------------------------------------------------
;; the whole chain
;; ---------------------------------------------------------------------------

(defn run-composition [examples-dir]
  (let [skills   (discover-skills (str examples-dir "/skills"))
        cfg      (json/read-str (fs/read-file (str examples-dir "/mcp.json")))
        local    (->> (:mcpServers cfg)
                      (map (fn [e] (assoc (val e) :name (name (key e)))))
                      (filter #(and (:command %) (not (false? (:enabled %)))))
                      first)

        ;; ---- leg 1: MCP stdio, a real child process
        stdio    (stdio-transport (vec (:command local)))
        t1       (toolkit (concat (mcp-tools stdio (:name local))
                                  [(skill-tool skills)
                                   (native-tool "now" "A native tool" (fn [_] "native-ok"))]))

        ;; ---- leg 2: re-expose T1 as an MCP streamable-HTTP server (§7C)
        ;;      and as an A2A agent (§7B), on one server.
        llm      (scripted-llm!)
        llm-base (str "http://127.0.0.1:" (server/port llm))
        ;; The A2A fulfilment runs the loop over T2 — which reaches T1 over
        ;; HTTP. Declared as a promise so serve can close over it.
        t2-box   (atom nil)
        served   (serve-toolkit t1 skills
                                (fn [text] (run-client-loop @t2-box llm-base "compose" text 10))
                                {:name "toolnexus-gateway" :description "S17"
                                 :version "0.1.0"})

        ;; ---- leg 3: an MCP streamable-HTTP CLIENT against our own /mcp
        remote   (http-transport (str (:url served) "/mcp"))
        t2       (toolkit (mcp-tools remote "gateway"))
        _        (reset! t2-box t2)]
    (try
      (let [card    (json/read-str (:body (http/request {:method :get
                                                         :url (str (:url served)
                                                                   "/.well-known/agent-card.json")})))
            ;; ---- leg 4: drive it as an A2A peer
            task    (a2a-send! (:url served) "compose everything")
            settled (a2a-await (:url served) (:id task))]
        {:host (name host/id)
         :chain ["a2a" "client-loop" "mcp-http" "toolkit" "mcp-stdio+skill+native"]
         :t1    {:tool-count (count (:tools t1))
                 :sources    (vec (sort (distinct (map :source (vals (:tools t1))))))}
         :t2    {:tool-count  (count (:tools t2))
                 :names-match (= (mapv #(str "gateway_" %) (tool-names t1))
                                 (tool-names t2))
                 :sample      (vec (take 3 (tool-names t2)))}
         :card  {:name     (:name card)
                 :protocol (:protocolVersion card)
                 :skills   (mapv :id (:skills card))
                 :streaming (get-in card [:capabilities :streaming])}
         :a2a   {:submitted (get-in task [:status :state])
                 :final     (get-in settled [:status :state])
                 :artifact  (get-in settled [:artifacts 0 :parts 0 :text])
                 :calls     (:calls settled)}})
      (finally
        ((:close! stdio))
        (server/stop! (:handle served))
        (server/stop! llm)))))

(defn -main [& _]
  (let [dir (env/get-env "TN_EXAMPLES")]
    (when-not dir (throw (ex-info "set TN_EXAMPLES" {})))
    (println (json/write-str (run-composition dir)))))
