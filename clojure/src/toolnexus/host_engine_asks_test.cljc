;; SPEC §4C — the go-agent-cli host asks, the prompt cache and tool tiers, ported
;; from the Go reference (golang/host_engine_asks_test.go, resolve_key_test.go,
;; host_builtin_extras_test.go, prompt_cache_test.go, tool_tiers_test.go,
;; agents/compaction_test.go, agents/astool_ctx_test.go).
;;
;; Hermetic: the LLM is a koine.server on 127.0.0.1 (or an :http-client), the MCP
;; server is the shared examples/mcp-progress-server.mjs. No java.*, no reader
;; conditionals.
(ns toolnexus.host-engine-asks-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.server :as server]
            [koine.time :as ktime]
            [toolnexus.agents.compaction :as compaction]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.builtin :as builtin]
            [toolnexus.client :as client]
            [toolnexus.client-test :as ct]
            [toolnexus.core :as core]
            [toolnexus.mcp :as mcp]
            [toolnexus.native :as native]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.test-support :as ts]
            [toolnexus.tool :as tool]))

(defn- throws [f] (try (f) nil (catch Throwable e e)))

(defn- json-response [body]
  {:status 200 :headers {"content-type" "application/json"} :body (json/write-str body)})

(defn- with-server
  "A koine.server answering `(handler parsed-body req)`; hands `f` the base url."
  [handler f]
  (let [srv (ts/serve (fn [req] (handler (try (json/read-str (str (:body req))) (catch Throwable _ nil)) req))
                      {:port 0})]
    (try (f (str "http://127.0.0.1:" (server/port srv)))
         (finally (server/stop! srv)))))

;; ---------------------------------------------------------------------------
;; SECURITY — the API key by BaseURL host (go-agent-cli ask #8)
;; ---------------------------------------------------------------------------

(deftest the-key-env-belongs-to-the-base-url-host
  (is (= "OPENAI_API_KEY" (client/key-env-for-base-url "https://api.openai.com/v1")))
  (is (= "OPENAI_API_KEY" (client/key-env-for-base-url "https://API.OpenAI.com/v1")) "case-insensitive host")
  (is (= "ANTHROPIC_API_KEY" (client/key-env-for-base-url "https://api.anthropic.com")))
  (is (= "OPENROUTER_API_KEY" (client/key-env-for-base-url "https://openrouter.ai/api/v1")))
  (is (= "OPENROUTER_API_KEY" (client/key-env-for-base-url "https://eu.openrouter.ai/api/v1")) "a subdomain")
  (is (= "OPENAI_API_KEY" (client/key-env-for-base-url "https://user@api.openai.com:443/v1")) "userinfo and port dropped")
  (doseq [base ["https://example.com/v1"            ; unknown host
                "http://api.openai.com/v1"          ; plain http
                "https://api.openai.com.evil.test/" ; lookalike
                "https://evil-openrouter.ai/v1"     ; not a subdomain
                "http://localhost:11434/v1"
                "api.openai.com/v1"                 ; no scheme
                ""]]
    (is (nil? (client/key-env-for-base-url base)) (str "no env key may go to " base))))

(deftest an-explicit-key-or-a-named-env-var-wins
  (is (= "fake-explicit" (client/resolve-key {:base-url "https://example.com/v1" :api-key "fake-explicit"})))
  (is (= "fake-explicit" (client/resolve-key {:base-url "https://api.openai.com/v1" :api-key "fake-explicit"})))
  ;; :api-key-env names the variable; PATH is set on every machine and is not a secret
  (let [path-val (env/get-env "PATH")]
    (is (true? (= path-val (client/resolve-key {:base-url "https://example.com/v1" :api-key-env "PATH"})))
        ":api-key-env reads the NAMED variable")))

(deftest no-cross-provider-fallback
  (let [values (keep env/get-env ["OPENAI_API_KEY" "ANTHROPIC_API_KEY" "OPENROUTER_API_KEY"])]
    (doseq [base ["https://example.com/v1" "http://api.openai.com/v1" "https://api.openai.com.evil.test/"
                  "https://evil-openrouter.ai/v1" "http://localhost:11434/v1" "http://127.0.0.1:1"]]
      (let [e (throws #(client/resolve-key {:base-url base}))]
        (is (some? e) (str base ": must not resolve an env key"))
        (is (= :config (:toolnexus/error (ex-data e))))
        ;; compared as a boolean so a failure can never print a key value
        (is (false? (boolean (some #(and (seq %) (str/includes? (str (ex-message e)) %)) values)))
            "the error never carries a key value"))))
  (when-not (env/get-env "OPENAI_API_KEY")
    (let [e (throws #(client/resolve-key {:base-url "https://api.openai.com/v1"}))]
      (is (str/includes? (str (ex-message e)) "OPENAI_API_KEY")
          "the right env var unset ⇒ an error naming it, not another provider's key"))))

(deftest a-run-against-an-unknown-host-sends-nothing
  (let [hits (atom 0)
        c    (client/create-client {:base-url "http://127.0.0.1:1" :model "m" :retries 0
                                    :http-client (fn [_ _ _] (swap! hits inc) {:status 200 :body "{}"})})
        e    (throws #(client/run c "hi" {}))]
    (is (= :config (:toolnexus/error (ex-data e))))
    (is (= 0 @hits) "no request leaves without a key that belongs to the host")))

(deftest the-header-carries-the-explicit-key
  (let [seen (atom nil)
        c    (client/create-client {:base-url "https://api.openai.com/v1" :model "m" :api-key "fake-explicit"
                                    :http-client (fn [_ h _] (reset! seen (get h "authorization"))
                                                   (json-response {:choices [{:message {:role "assistant" :content "ok"}}]}))})]
    (client/run c "hi" {})
    (is (= "Bearer fake-explicit" @seen))))

;; ---------------------------------------------------------------------------
;; list-models (ask #9)
;; ---------------------------------------------------------------------------

(deftest list-models-openai-and-anthropic
  (let [paths (atom []) auths (atom []) pages (atom 0)]
    (with-server
      (fn [_ req]
        (swap! paths conj (:path req))
        (let [h (into {} (map (fn [[k v]] [(str/lower-case (name k)) v]) (:headers req)))]
          (if (get h "x-api-key")
            (do (swap! auths conj "x-api-key")
                (if (= 1 (swap! pages inc))
                  (json-response {:data [{:id "claude-b" :display_name "B"}] :has_more true :last_id "claude-b"})
                  (json-response {:data [{:id "claude-a" :display_name "A"}] :has_more false})))
            (do (swap! auths conj (get h "authorization"))
                (json-response {:data [{:id "z/model" :name "Z" :context_length 200000} {:id "a/model"}]})))))
      (fn [base]
        (let [ms (client/list-models {:base-url (str base "/api/v1") :api-key "fake"})]
          (is (= ["a/model" "z/model"] (mapv :id ms)) "sorted by id")
          (is (= 200000 (:context-length (second ms))))
          (is (= "Z" (:name (second ms))))
          (is (= ["Bearer fake"] @auths))
          (is (= ["/api/v1/models"] @paths)))
        (let [ms (client/list-models {:base-url base :style "anthropic" :api-key "fake"})]
          (is (= ["claude-a" "claude-b"] (mapv :id ms)) "both pages, sorted")
          (is (= "B" (:name (second ms))))
          (is (= 2 @pages) "has_more was followed")
          (is (= "/v1/models" (last @paths))))
        (is (some? (throws #(client/list-models {:base-url base})))
            "an unknown host without :api-key never resolves an env key")))))

;; ---------------------------------------------------------------------------
;; per-server MCP readiness — from-config :on-server
;; ---------------------------------------------------------------------------

(defn- node-available? []
  (try (= 0 (:exit (proc/sh ["node" "--version"] {:timeout-ms 10000})))
       (catch Throwable _ false)))

(defn- fixture []
  (let [dir (te/examples-dir)]
    (when (and dir (node-available?)) (str dir "/mcp-progress-server.mjs"))))

(deftest on-server-reports-a-fast-server-first
  (if-let [fx (fixture)]
    (let [t0      (ktime/mono-ms)
          seen    (atom [])
          at      (atom {})
          tk      (atom (tool/toolkit []))
          res     (mcp/from-config
                   {:mcpServers {"fast" {:command ["node" fx] :timeout 5000}
                                 "slow" {:command ["sh" "-c" (str "sleep 1; exec node '" fx "'")] :timeout 5000}
                                 "bad"  {:command ["/nonexistent/toolnexus-no-such-binary"] :timeout 2000}
                                 "off"  {:command ["node" fx] :enabled false}}}
                   {:on-server (fn [r]
                                 (swap! seen conj r)
                                 (swap! at assoc (:name r) (- (ktime/mono-ms) t0))
                                 (swap! tk tool/add-tools (:tools r)))})
          by      (into {} (map (fn [r] [(:name r) r]) @seen))]
      (try
        (is (= 4 (count @seen)) "one call per server")
        (is (= "connected" (:status (by "fast"))))
        (is (seq (:tools (by "fast"))))
        (is (= "connected" (:status (by "slow"))))
        (is (= "failed" (:status (by "bad"))))
        (is (string? (:error (by "bad"))))
        (is (= "disabled" (:status (by "off"))))
        (is (< (@at "fast") (@at "slow")))
        (is (>= (- (@at "slow") (@at "fast")) 500) "the fast server never waits for the slow one")
        (is (contains? (:tools @tk) "fast_slow") "a fast server's tools can be registered from :on-server")
        (is (= {"bad" "failed" "fast" "connected" "off" "disabled" "slow" "connected"} (:statuses res))
            "the returned source is the same as the serial path's")
        (finally (mcp/disconnect-all res))))
    (println "  skip: node or examples/ not found — on-server test skipped")))

;; ---------------------------------------------------------------------------
;; wait-for-ctx — waitFor with the turn's cancellation
;; ---------------------------------------------------------------------------

(deftest wait-for-ctx-cancels-with-the-turn
  (ct/with-llm "openai" [{:calls [{:id "c1" :name "login" :args {}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [cancel (atom nil)
            saw    (atom nil)
            plain  (atom 0)
            c      (client/create-client
                    {:base-url base :model "m" :api-key "k"
                     :wait-for (fn [_] (swap! plain inc) {:ok true})
                     :wait-for-ctx (fn [ctx req]
                                     (proc/run-async! (fn [] (ktime/sleep! 50) (reset! cancel "turn cancelled")))
                                     (let [deadline (+ (ktime/mono-ms) 5000)]
                                       (loop []
                                         (cond
                                           ((:cancelled? ctx)) (do (reset! saw :cancelled)
                                                                   (throw (client/cancelled-error @(:cancel ctx))))
                                           (> (ktime/mono-ms) deadline) (do (reset! saw :timeout)
                                                                            (client/make-answer (:id req) true))
                                           :else (do (ktime/sleep! 5) (recur))))))})
            t0     (ktime/mono-ms)
            e      (throws #(client/run c "what is my balance?" {:toolkit ct/toolkit :cancel cancel}))]
        (is (= :cancelled (:toolnexus/error (ex-data e))))
        (is (= :cancelled @saw) "wait-for-ctx saw the turn cancelled")
        (is (= 0 @plain) ":wait-for must not run when :wait-for-ctx is set")
        (is (< (- (ktime/mono-ms) t0) 2000))))))

(deftest wait-for-ctx-resolves
  (ct/with-llm "openai" [{:calls [{:id "c1" :name "login" :args {}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :wait-for-ctx (fn [ctx req]
                                                     (is (= "c1" (:call-id ctx)))
                                                     (client/make-answer (:id req) true))})
            r (client/run c "go" {:toolkit ct/toolkit})]
        (is (= "done" (:status r)))
        (is (= "session valid" (:output (first (:tool-calls r)))))))))

(deftest a-cancelled-run-stops-before-the-next-call
  (ct/with-llm "openai" [{:text "never"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"})
            e (throws #(client/run c "go" {:toolkit ct/toolkit :cancel (atom "stop")}))]
        (is (= :cancelled (:toolnexus/error (ex-data e))))
        (is (= 0 (count @requests)))))))

;; ---------------------------------------------------------------------------
;; tool hints + the permission gate
;; ---------------------------------------------------------------------------

(deftest builtin-hints
  (let [ro {"read" true "grep" true "glob" true "webfetch" true "question" true "todowrite" true
            "bash" false "write" false "edit" false "apply_patch" false}]
    (doseq [t (builtin/builtin-tools-for nil)]
      (is (contains? ro (:name t)) (:name t))
      (is (map? (:hints t)) (:name t))
      (is (= (get ro (:name t)) (:read-only (:hints t))) (:name t))
      (is (not= (get ro (:name t)) (:destructive (:hints t))) (:name t)))
    (is (true? (:open-world (get builtin/builtin-hints "bash"))))
    (is (true? (:open-world (get builtin/builtin-hints "webfetch"))))
    (is (= (mapv :input-schema builtin/builtin-tools)
           (mapv :input-schema (builtin/builtin-tools-for nil))))
    (testing "hints never reach the wire"
      (is (not (str/includes? (json/write-str (core/to-openai (builtin/builtin-toolkit))) "read-only"))))))

(deftest mcp-hints-mapping
  (is (nil? (mcp/mcp-hints nil)) "no annotations ⇒ unknown")
  (is (nil? (mcp/mcp-hints {:title "x"})))
  (is (= {:read-only true :destructive false :open-world true} (mcp/mcp-hints {:readOnlyHint true})))
  (is (= {:read-only false :destructive false :open-world false}
         (mcp/mcp-hints {:destructiveHint false :openWorldHint false})))
  (is (= {:read-only false :destructive true :open-world true} (mcp/mcp-hints {:readOnlyHint false}))
      "MCP's defaults"))

(defn- recording-tool [nm hints out]
  (native/native-tool {:name nm :description (str nm " tool") :hints hints
                       :input-schema {:type "object" :properties {:x {:type "string"}}}
                       :run (fn [args] (swap! out conj (str nm "(" (:x args) ")")) (str nm " ok"))}))

(deftest a-permission-gate-denies-a-mutating-tool
  (ct/with-llm "openai" [{:calls [{:id "c1" :name "deploy" :args {}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [ran  (atom [])
            seen (atom nil)
            c    (client/create-client
                  {:base-url base :model "m" :api-key "k"
                   :hooks {:before-tool (fn [ev]
                                          (reset! seen (:hints ev))
                                          (when-not (:read-only (:hints ev))
                                            {:result (tool/failure "denied: plan mode allows read-only tools only")}))}})
            r    (client/run c "deploy?" {:toolkit (tool/toolkit [(recording-tool "deploy" {:read-only false :destructive true :open-world false} ran)])})]
        (is (= "done" (:status r)))
        (is (true? (:destructive @seen)) "the gate saw the tool's hints")
        (is (= [] @ran) "the denied tool never ran")
        (is (= ["denied: plan mode allows read-only tools only"] (mapv :output (:tool-calls r))))
        (is (true? (:isError (first (:tool-calls r)))))))))

;; ---------------------------------------------------------------------------
;; ToolContext :call-id (Karya)
;; ---------------------------------------------------------------------------

(deftest parallel-calls-each-see-their-own-call-id
  (doseq [style ["openai" "anthropic"]]
    (ct/with-llm style [{:calls [{:id "c1" :name "fast" :args {}} {:id "c2" :name "slow" :args {}}]}]
      (fn [{:keys [base]}]
        (let [seen (atom {})
              mk   (fn [nm] (tool/tool {:name nm :context? true
                                        :execute (fn ([_] (tool/success "no ctx"))
                                                   ([_ ctx] (when (= nm "slow") (ktime/sleep! 30))
                                                    (swap! seen assoc nm (:call-id ctx))
                                                    (tool/success "ok")))}))
              c    (client/create-client {:base-url base :style style :model "m" :api-key "k" :max-turns 1})]
          (client/run c "go" {:toolkit (tool/toolkit [(mk "fast") (mk "slow")])})
          (is (= {"fast" "c1" "slow" "c2"} @seen) style))))))

(deftest a-single-arity-tool-is-still-called-without-a-context
  (ct/with-llm "openai" [{:calls [{:id "c1" :name "plain" :args {}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"})
            r (client/run c "go" {:toolkit (tool/toolkit [(tool/tool {:name "plain" :execute (fn [_] (tool/success "fine"))})])})]
        (is (= ["fine"] (mapv :output (:tool-calls r))))))))

;; ---------------------------------------------------------------------------
;; opt-in builtins, grep context, truncation metadata (ask #7)
;; ---------------------------------------------------------------------------

(defn- builtin-named [cfg nm]
  (first (filter #(= nm (:name %)) (builtin/builtin-tools-for cfg))))

(defn- exec [t args] ((:execute t) args))

(deftest opt-in-builtins-are-absent-by-default
  (let [names (set (map :name (builtin/builtin-tools-for {})))]
    (doseq [n ["bash_output" "kill_shell" "websearch" "notebook_edit"]]
      (is (not (contains? names n)) (str n " must be opt-in"))))
  (is (nil? (get-in (builtin-named {} "bash") [:input-schema :properties :run_in_background]))
      "bash offers run_in_background only with :background")
  (is (= 10 (count (builtin/builtin-tools-for nil)))))

(deftest background-shell-signalled-from-outside-is-minus-one
  ;; Not via kill_shell: the shell signals itself. Go reports -1, never 128+signal.
  (let [bg  (builtin/background-shells)
        cfg {:background bg :kill-grace-ms 100}
        bash (builtin-named cfg "bash") out (builtin-named cfg "bash_output")]
    (try
      (let [id (get-in (exec bash {:command "kill -TERM $$; sleep 5" :run_in_background true}) [:metadata :backgroundId])
            deadline (+ (ktime/mono-ms) 5000)
            o (loop []
                (let [o (exec out {:id id})]
                  (cond (= "exited" (get-in o [:metadata :status])) o
                        (> (ktime/mono-ms) deadline) nil
                        :else (do (ktime/sleep! 50) (recur)))))]
        (is (= -1 (get-in o [:metadata :exitCode])) (pr-str o))
        (is (str/includes? (str (:output o)) "[status: exited with code -1]")))
      (finally (builtin/bg-close-all! bg)))))

(deftest background-shell-lifecycle
  (let [bg  (builtin/background-shells)
        cfg {:background bg :kill-grace-ms 100}
        bash (builtin-named cfg "bash") out (builtin-named cfg "bash_output") kill (builtin-named cfg "kill_shell")]
    (try
      (is (some? (get-in bash [:input-schema :properties :run_in_background])))
      (let [r  (exec bash {:command "echo one; sleep 0.2; echo two" :run_in_background true})
            id (get-in r [:metadata :backgroundId])]
        (is (false? (:isError r)) (pr-str r))
        (is (string? id))
        (let [deadline (+ (ktime/mono-ms) 5000)
              all (loop [acc ""]
                    (let [o   (exec out {:id id})
                          acc (str acc (first (str/split (:output o) #"\n\[status:" 2)))]
                      (cond
                        (= "exited" (get-in o [:metadata :status]))
                        (do (is (= 0 (get-in o [:metadata :exitCode])) (pr-str o)) acc)
                        (> (ktime/mono-ms) deadline) (do (is false "never exited") acc)
                        :else (do (ktime/sleep! 50) (recur acc)))))]
          (is (= 1 (count (re-seq #"one" all))) (pr-str all))
          (is (= 1 (count (re-seq #"two" all))) "incremental output is neither repeated nor lost")))
      (let [r   (exec bash {:command "trap '' TERM; sleep 30 & wait" :run_in_background true})
            id2 (get-in r [:metadata :backgroundId])
            _   (ktime/sleep! 100)
            t0  (ktime/mono-ms)
            k   (exec kill {:id id2})]
        (is (true? (get-in k [:metadata :killed])) (pr-str k))
        (is (< (- (ktime/mono-ms) t0) 2000) "a stubborn job with a child is killed as a tree, promptly")
        (is (= "killed" (get-in (exec out {:id id2}) [:metadata :status])))
        (is (false? (get-in (exec kill {:id id2}) [:metadata :killed])) "a second kill reports it already stopped"))
      (is (true? (:isError (exec out {:id "nope"}))) "an unknown id is an error")
      (is (= 2 (count (builtin/bg-list bg))))
      (finally (builtin/bg-close-all! bg)))))

(deftest websearch-uses-the-host-provider
  (let [got (atom nil)
        ws  (builtin-named {:web-search (fn [q n] (reset! got [q n])
                                          [{:title "A" :url "https://a.example" :snippet "sa"}
                                           {:title "B" :url "https://b.example"}])}
                           "websearch")
        r   (exec ws {:query "go" :limit 1})]
    (is (= {:read-only true :destructive false :open-world true} (:hints ws)))
    (is (= ["go" 1] @got))
    (is (= "1. A\n   https://a.example\n   sa" (:output r)))
    (is (true? (:isError (exec ws {:query " "}))))))

(deftest notebook-edit-replaces-inserts-and-deletes
  (let [dir (fs/temp-dir! "tn-nb")
        p   (str dir "/n.ipynb")
        _   (fs/write-file p (str "{\"cells\":[{\"cell_type\":\"code\",\"id\":\"a\",\"metadata\":{},\"source\":[\"x = 1\\n\"],"
                                  "\"outputs\":[{\"output_type\":\"stream\",\"text\":[\"1\"]}],\"execution_count\":3},"
                                  "{\"cell_type\":\"markdown\",\"id\":\"b\",\"metadata\":{},\"source\":[\"# T\"]}],"
                                  "\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}"))
        ne  (builtin-named {:notebook-edit true :base-dir dir} "notebook_edit")
        cells (fn [] (:cells (json/read-str (fs/read-file p))))]
    (try
      (is (false? (:isError (exec ne {:path "n.ipynb" :cell_id "a" :new_source "x = 2\ny = 3"}))))
      (let [c0 (first (cells))]
        (is (= [] (:outputs c0)) "replacing a code cell clears its outputs")
        (is (nil? (:execution_count c0)))
        (is (= ["x = 2\n" "y = 3"] (:source c0))))
      (is (str/starts-with? (fs/read-file p) "{\n \"cells\": [\n  {\n   \"cell_type\": \"code\",")
          "written the way the Go reference marshals it: sorted keys, one-space indent")
      (is (false? (:isError (exec ne {:path "n.ipynb" :cell_index 0 :edit_mode "insert"
                                      :cell_type "markdown" :new_source "hi"}))))
      (is (= 3 (count (cells))))
      (is (= "markdown" (:cell_type (second (cells)))))
      (let [r (exec ne {:path "n.ipynb" :cell_id "b" :edit_mode "delete"})]
        (is (false? (:isError r)))
        (is (= 2 (count (cells))))
        (is (seq (get-in r [:metadata :files])) "the edit carries diff metadata"))
      (is (true? (:isError (exec ne {:path "n.ipynb" :cell_id "zz"}))) "an unknown cell id is an error")
      (finally (fs/delete-tree! dir)))))

(deftest grep-context-and-truncation-metadata
  (let [dir (fs/temp-dir! "tn-grep")]
    (try
      (fs/write-file (str dir "/f.txt") "a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n")
      (let [grep (builtin-named {:base-dir dir} "grep")
            read (builtin-named {:base-dir dir} "read")]
        (is (= "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
               (:output (exec grep {:pattern "HIT" :context 1}))))
        (is (= "f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
               (:output (exec grep {:pattern "HIT" :before 0 :after 4})))
            "touching groups merge")
        (let [r (exec grep {:pattern "HIT" :limit 1})]
          (is (= "f.txt:3:HIT1" (:output r)) "the text is the pinned output")
          (is (= {:count 1 :truncated true :total 2} (:metadata r))))
        (is (nil? (:metadata (exec grep {:pattern "HIT"}))) "an uncut list is byte-identical to before")
        (let [r (exec read {:path "f.txt" :limit 2})]
          (is (= "a\nb" (:output r)))
          (is (= {:truncated true :totalLines 9 :nextOffset 3} (:metadata r))))
        (is (nil? (:metadata (exec read {:path "f.txt" :offset 8}))) "a window that reaches the end is not cut"))
      (finally (fs/delete-tree! dir)))))

;; ---------------------------------------------------------------------------
;; MCP resource tools (the fixture now offers fixture://hello)
;; ---------------------------------------------------------------------------

(deftest mcp-resource-tools
  (if-let [fx (fixture)]
    (let [res (mcp/from-config {:mcpServers {"progress" {:command ["node" fx] :timeout 5000}}})
          [list-t read-t] (mcp/resource-tools res)]
      (try
        (is (= "connected" (get (:statuses res) "progress")))
        (let [r (exec list-t {})]
          (is (false? (:isError r)) (pr-str r))
          (is (= "progress fixture://hello — hello (text/plain): A fixed text resource." (:output r)))
          (is (= 1 (get-in r [:metadata :count]))))
        (let [r (exec read-t {:server "progress" :uri "fixture://hello"})]
          (is (false? (:isError r)) (pr-str r))
          (is (= "hello resource" (:output r))))
        (is (true? (:isError (exec read-t {:server "progress" :uri "fixture://nope"}))) "a missing resource is an error")
        (is (true? (:isError (exec read-t {:server "nope" :uri "x"}))) "an unknown server is an error")
        (is (true? (:isError (exec list-t {:server "nope"}))))
        (is (and (:read-only (:hints list-t)) (:read-only (:hints read-t))) "resource tools are read-only")
        (finally (mcp/disconnect-all res))))
    (println "  skip: node or examples/ not found — resource-tools test skipped")))

(deftest resource-tools-without-a-server-say-so
  (let [[list-t read-t] (mcp/resource-tools {:connections []})]
    (is (= "No resources." (:output (exec list-t {}))))
    (is (true? (:isError (exec read-t {:server "x" :uri "y"}))))))

;; ---------------------------------------------------------------------------
;; compaction — :on-compact and the compactor fixes (ask #6)
;; ---------------------------------------------------------------------------

(defn- transcript [user-turns]
  (let [pad (apply str (repeat 40 "pad ")) data (apply str (repeat 40 "data "))]
    (into [{:role "system" :content "You are Ava. SOUL."}]
          (mapcat (fn [i]
                    (let [id (str "c" i)]
                      [{:role "user" :content (str "question " i " " pad)}
                       {:role "assistant" :content nil
                        :tool_calls [{:id id :type "function" :function {:name "lookup" :arguments "{}"}}]}
                       {:role "tool" :tool_call_id id :content (str "result " i " " data)}
                       {:role "assistant" :content (str "answer " i)}]))
                  (range user-turns)))))

(defn- summarize [older] (str "summarized " (count older) " messages"))

(deftest on-compact-reports-each-compaction
  (let [got  (atom [])
        hook (compaction/compactor {:max-tokens 50 :keep-tail 20 :summarize summarize
                                    :on-compact (fn [i] (swap! got conj i))})
        msgs (transcript 10)
        out  (hook {:messages msgs})
        i    (first @got)]
    (is (some? out))
    (is (= 1 (count @got)))
    (is (= (count msgs) (:messages-before i)))
    (is (= (count (:messages out)) (:messages-after i)))
    (is (pos? (:summarized i)))
    (is (= (compaction/estimate-tokens msgs) (:tokens-before i)))
    (is (= (compaction/estimate-tokens (:messages out)) (:tokens-after i)))
    (is (< (:tokens-after i) (:tokens-before i)))
    (is (not= "" (:summary i)))
    (testing "Folded carries the summarized messages verbatim"
      (is (= (:summarized i) (count (:folded i))))
      (is (= (subvec msgs 1 (inc (count (:folded i)))) (vec (:folded i))))))
  (let [hook (compaction/compactor {:max-tokens 1073741824 :summarize summarize
                                    :on-compact (fn [_] (is false "on-compact on a no-op"))})]
    (is (nil? (hook {:messages (transcript 10)})) "under budget ⇒ no callback")))

(deftest the-sole-user-prompt-is-kept
  (let [data (apply str (repeat 40 "data "))
        msgs (into [{:role "system" :content "SOUL"} {:role "user" :content "do the task"}]
                   (mapcat (fn [i] (let [id (str "c" i)]
                                     [{:role "assistant" :content nil
                                       :tool_calls [{:id id :type "function" :function {:name "lookup" :arguments "{}"}}]}
                                      {:role "tool" :tool_call_id id :content data}]))
                           (range 8)))
        info (atom nil)
        out  ((compaction/compactor {:max-tokens 100 :keep-tail 20 :summarize summarize
                                     :on-compact (fn [i] (reset! info i))})
              {:messages msgs})]
    (is (= 3 (count (:messages out))) "[system, prompt, summary]")
    (is (= "do the task" (:content (second (:messages out)))))
    (is (= (- (count msgs) 2) (count (:folded @info))))))

(deftest a-compaction-that-would-not-shrink-is-a-no-op
  (let [long (fn [_] (apply str (repeat 500 "long summary ")))
        hook (compaction/compactor {:max-tokens 10 :keep-tail 5 :summarize long
                                    :on-compact (fn [_] (is false "on-compact on a no-op"))})]
    (is (nil? (hook {:messages (transcript 2)})))))

;; ---------------------------------------------------------------------------
;; agents as tools under the parent's gates (ask #7 follow-up)
;; ---------------------------------------------------------------------------

(defn- llm-reply [body]
  {:status 200 :headers {"content-type" "application/json"} :body (json/write-str body)})

(deftest as-tool-is-cancelled-with-the-calling-turn
  (let [slow (fn [_ _ _] (ktime/sleep! 3000)
               (llm-reply {:choices [{:message {:role "assistant" :content "late"}}]}))
        t    (rt/as-tool {:name "child" :does "d" :soul "s"} {:http-client slow})
        cancel (atom nil)
        _    (proc/run-async! (fn [] (ktime/sleep! 100) (reset! cancel "turn cancelled")))
        t0   (ktime/mono-ms)
        r    ((:execute t) {:prompt "go"} {:cancel cancel})]
    (is (< (- (ktime/mono-ms) t0) 2500) "the child does not hold the caller")
    (is (true? (:isError r)))
    (is (str/starts-with? (:output r) "cancelled") (pr-str r))))

(deftest as-tool-with-runs-the-child-under-the-gate-and-the-ask-bridge
  (let [ran   (atom false)
        n     (atom 0)
        danger (native/native-tool {:name "danger" :description "mutates"
                                    :hints {:read-only false :destructive true :open-world false}
                                    :run (fn [_] (reset! ran true) "did it")})
        gated (atom [])
        asked (atom 0)
        t     (rt/as-tool-with
               {:name "child" :does "d" :soul "s" :tools [danger]}
               (fn [_ctx]
                 {:http-client (fn [_ _ _]
                                 (if (= 1 (swap! n inc))
                                   (llm-reply {:choices [{:message {:role "assistant" :content nil
                                                                    :tool_calls [{:id "d1" :type "function"
                                                                                  :function {:name "danger" :arguments "{}"}}]}}]})
                                   (llm-reply {:choices [{:message {:role "assistant" :content "child done"}}]})))
                  :hooks {:before-tool (fn [ev]
                                         (swap! gated conj (:name ev))
                                         (when-not (:read-only (:hints ev))
                                           {:result (client/suspend (client/make-request "approval" (str "allow " (:name ev) "?")))}))}
                  :wait-for (fn [req] (swap! asked inc) (client/make-answer (:id req) true))}))
        r     ((:execute t) {:prompt "go"} {})]
    (is (false? (:isError r)) (pr-str r))
    (is (= "child done" (:output r)))
    (is (= ["danger"] @gated) "the parent's gate saw the child's tool call")
    (is (= 1 @asked) "the child's ask reached the runtime-level wait-for")
    (is (true? @ran))))

;; ---------------------------------------------------------------------------
;; prompt caching (add-prompt-cache phase 1) — a simulated provider cache
;; ---------------------------------------------------------------------------

(defn- strip-cache [v]
  (cond (map? v) (into {} (keep (fn [[k x]] (when-not (= :cache_control k) [k (strip-cache x)])) v))
        (sequential? v) (mapv strip-cache v)
        :else v))

(defn- canonical [v] (builtin/json-indent (strip-cache v) ""))

(defn- has-cache? [v] (str/includes? (json/write-str v) "\"cache_control\""))

(defn- cache-sim
  "A mock anthropic server that SIMULATES the provider's prefix cache: a request
  reads the longest prefix ending at a breakpoint an earlier request wrote."
  []
  (let [st (atom {:written #{} :reads [] :bodies [] :turn 0})]
    {:state st
     :handler
     (fn [body _req]
       (let [segs  (into [(canonical (:tools body)) (canonical (:system body))]
                         (map canonical (:messages body)))
             marks (into [(has-cache? (:tools body)) (has-cache? (:system body))]
                         (map has-cache? (:messages body)))
             key-of (fn [i] (str/join "\u0000" (take i segs)))
             len-of (fn [i] (quot (count (apply str (take i segs))) 4))
             read  (or (first (keep (fn [i] (when (contains? (:written @st) (key-of i)) (len-of i)))
                                    (range (count segs) 0 -1)))
                       0)
             write (reduce (fn [w i]
                             (if (and (nth marks i) (not (contains? (:written @st) (key-of (inc i)))))
                               (do (swap! st update :written conj (key-of (inc i)))
                                   (- (len-of (inc i)) read))
                               w))
                           0 (range (count segs)))
             _     (swap! st #(-> % (update :reads conj read) (update :bodies conj body)))
             last  (last (:messages body))
             tool-result? (and (sequential? (:content last))
                               (some #(= "tool_result" (:type %)) (:content last)))
             content (if tool-result?
                       [{:type "text" :text "ok"}]
                       (let [n (:turn (swap! st update :turn inc))]
                         [{:type "tool_use" :id (str "t" n) :name (if (= 2 n) "tool_search" "read_notes") :input {}}]))]
         (json-response {:content content
                         :stop_reason (if tool-result? "end_turn" "tool_use")
                         :usage {:input_tokens 10 :output_tokens 5
                                 :cache_read_input_tokens read :cache_creation_input_tokens write}})))}))

(defn- karya-toolkit []
  (core/build {:tools [(native/native-tool {:name "read_notes" :description "Read the notes." :run (fn [_] "notes")})
                       (native/native-tool {:name "tool_search" :description "Load a deferred tool's schema."
                                            :run (fn [_] "{\"name\":\"deploy\",\"description\":\"Deploy.\",\"inputSchema\":{\"type\":\"object\"}}")})]}))

(defn- run-cache-session [cache]
  (let [{:keys [state handler]} (cache-sim)
        usage (atom {})]
    (with-server handler
      (fn [base]
        (let [catalog (apply str (repeat 700 "skill: does a thing in a stable way. "))
              c  (client/create-client (cond-> {:base-url base :style "anthropic" :model "m" :api-key "k"
                                                :system-prompt (str "You are Karya.\n\n" catalog)}
                                         cache (assoc :prompt-cache cache)))
              tk (karya-toolkit)]
          (doseq [p ["first" "second" "third" "fourth"]]
            (let [r (client/ask c p {:toolkit tk :id "session-1"})]
              (swap! usage #(merge-with + % (select-keys (:usage r) [:cache-read-tokens :cache-write-tokens]))))))))
    (assoc @state :usage @usage)))

(deftest prompt-cache-replay-never-regresses
  (let [sim   (run-cache-session {:key "session-1"})
        reads (:reads sim)]
    (is (= 8 (count reads)) "4 turns x 2 requests")
    (is (= 0 (first reads)) "the first request cannot read the cache")
    (is (>= (second reads) 6000) (str "2nd request reads the ~6.6k stable prefix: " (pr-str reads)))
    (is (apply <= reads) (str "the cache read never shrinks, including after the deferred load: " (pr-str reads)))
    (is (pos? (:cache-read-tokens (:usage sim) 0)) "usage reports cache-read tokens")
    (is (apply = (map #(json/write-str (:tools %)) (:bodies sim))) "the tools prefix never changes")
    (is (apply = (map #(json/write-str (:system %)) (:bodies sim))) "the system prefix never changes")))

(deftest prompt-cache-off-is-byte-identical
  (let [sim (run-cache-session nil)]
    (doseq [b (:bodies sim)]
      (is (not (has-cache? b)) "no cache_control without :prompt-cache")
      (is (string? (:system b)) "system stays a string"))
    (is (nil? (:cache-read-tokens (:usage sim))) "an uncached usage map has no cache keys")))

(deftest prompt-cache-openai-key-and-usage
  (is (= "s1" (:prompt_cache_key (client/apply-prompt-cache {:messages []} "openai" {:key "s1"}))))
  (is (= {:messages []} (client/apply-prompt-cache {:messages []} "openai" {})) "no key ⇒ unchanged")
  (let [u (client/add-usage client/zero-usage {:style "openai"}
                            {:prompt_tokens 100 :completion_tokens 5 :prompt_tokens_details {:cached_tokens 80}})]
    (is (= 80 (:cache-read-tokens u)))
    (is (= 100 (:prompt-tokens u)) "openai prompt tokens already include the cached part"))
  (let [u (client/add-usage client/zero-usage {:style "anthropic"}
                            {:input_tokens 10 :output_tokens 5 :cache_read_input_tokens 70 :cache_creation_input_tokens 20})]
    (is (= [10 70 20] [(:prompt-tokens u) (:cache-read-tokens u) (:cache-write-tokens u)]))))

(deftest prompt-cache-anthropic-breakpoints
  (let [msg  {:role "user" :content "hi"}
        body {:tools [{:name "x"}] :system "s" :messages [msg]}
        out  (client/apply-prompt-cache body "anthropic" {:long-ttl true})]
    (is (= "hi" (:content msg)) "the caller's message is untouched")
    (is (= 3 (count (re-seq #"\"ttl\":\"1h\"" (json/write-str out)))) "3 breakpoints, each with the 1h ttl")
    (is (= [{:type "text" :text "s" :cache_control {:type "ephemeral" :ttl "1h"}}] (:system out)))
    (is (= {:type "ephemeral"} (get-in (client/apply-prompt-cache body "anthropic" {}) [:tools 0 :cache_control])))))

;; ---------------------------------------------------------------------------
;; tool tiers (add-prompt-cache phase 2)
;; ---------------------------------------------------------------------------

(defn- tier-toolkit [ran]
  (tool/toolkit [(recording-tool "zeta_read" {:read-only true :destructive false :open-world false} ran)
                 (assoc (recording-tool "alpha_read" {:read-only true :destructive false :open-world false} ran)
                        :description "Read alpha.")
                 (assoc (recording-tool "deploy" {:read-only false :destructive true :open-world false} ran)
                        :description "Deploy the service to production.")
                 (-> (recording-tool "lint" nil ran) (assoc :description "Lint the code.") (dissoc :hints))]))

(deftest tool-tier-plan-and-description
  (let [ran (atom [])
        tk  (update-in (tier-toolkit ran) [:tools "zeta_read"] assoc :description "Read zeta.\nsecond line hidden")
        c   (client/create-client {:base-url "http://x" :model "m" :api-key "k"
                                   :tool-tiers {:core ["zeta_read" "alpha_read"] :deferred ["deploy"]}})
        got (client/request-tools c tk)
        desc (:description (nth got 2))]
    (is (= ["alpha_read" "zeta_read" "tool_search" "call_tool"] (mapv :name got)))
    (is (str/includes? desc "- lint — Lint the code."))
    (is (str/includes? desc "(name only): deploy"))
    (is (not (str/includes? desc "Deploy the service")))
    (is (= {"deploy" "deferred" "lint" "listed" "alpha_read" "core" "zeta_read" "core"} (client/tool-tier-plan c tk)))
    (testing "a tool registered after the plan froze is not offered, but search finds it"
      (let [tk2   (tool/add-tools tk [(native/native-tool {:name "late" :description "A late tool." :run (fn [_] "late ok")})])
            again (client/request-tools c tk2)]
        (is (= 4 (count again)))
        (is (= desc (:description (nth again 2))) "the tools array is frozen")
        (is (str/includes? (:output (client/tool-search tk2 {:query "late"})) "\"name\": \"late\""))))
    (testing "reset rebuilds at the next session start"
      (client/reset-tool-tiers! c tk)
      (is (= "core" (get (client/tool-tier-plan c tk) "alpha_read"))))))

(deftest tool-search-json-escapes-like-go
  ;; Same bytes as golang TestToolSearchJSONIsHTMLEscaped.
  (let [amp (native/native-tool {:name "amp" :description "Use <a & b> here"
                                 :input-schema {"type" "object" "properties" {"x" {"type" "string"}}}
                                 :run (fn [_] "")})
        tk  (tool/add-tools (tier-toolkit (atom [])) [amp])]
    (is (= (str "Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Use \\u003ca \\u0026 b\\u003e here\",\n"
                "    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n"
                "        }\n      },\n      \"type\": \"object\"\n    },\n    \"name\": \"amp\"\n  }\n]")
           (:output (client/tool-search tk {:query "select:amp"}))))))

(deftest tool-tier-first-line-cuts-on-a-character-boundary
  (is (= (str (apply str (repeat 119 "a")) "…") (client/first-line (str (apply str (repeat 119 "a")) "é tail"))))
  (is (= (str (apply str (repeat 117 "a")) "€…") (client/first-line (str (apply str (repeat 117 "a")) "€z"))))
  (is (= (str (apply str (repeat 118 "a")) "…") (client/first-line (str (apply str (repeat 118 "a")) "😀z")))))

(deftest tool-tier-budget-demotes-the-least-used
  (let [ran   (atom [])
        tools (tool/all-tools (tier-toolkit ran))
        uses  {"alpha_read" 9 "zeta_read" 1 "lint" 5 "deploy" 0}
        count-t (fn [v] (quot (+ (count (json/write-str v)) 3) 4))
        def-of  (fn [t] {"name" (:name t) "description" (:description t) "inputSchema" (:input-schema t)})
        base  (client/build-tier-plan {:core ["alpha_read" "zeta_read"]} tools)
        fixed (+ (count-t (def-of (:search base))) (count-t (def-of (:call base))))
        one   (count-t (def-of (first (filter #(= "alpha_read" (:name %)) tools))))
        p     (client/build-tier-plan {:core ["alpha_read" "zeta_read"] :budget-tokens (+ fixed one 15)
                                       :usage-count uses} tools)]
    (is (= "core" (get-in p [:tier "alpha_read"])))
    (is (= "listed" (get-in p [:tier "zeta_read"])) "the least-used core tool is demoted")
    (let [p (client/build-tier-plan {:core ["alpha_read"] :budget-tokens 1 :usage-count uses} tools)]
      (is (every? #(= "deferred" %) (vals (:tier p))) "a tiny budget defers everything"))))

(deftest tool-tier-loop-unwraps-call-tool
  (let [ran    (atom [])
        tk     (tier-toolkit ran)
        arrays (atom [])
        step   (atom 0)
        gated  (atom [])]
    (with-server
      (fn [body _]
        (swap! arrays conj (json/write-str (:tools body)))
        (json-response
         {:content (case (swap! step inc)
                     1 [{:type "tool_use" :id "s1" :name "tool_search" :input {:query "select:deploy"}}]
                     2 [{:type "tool_use" :id "c1" :name "call_tool" :input {:name "deploy" :arguments {:x "prod"}}}]
                     3 [{:type "tool_use" :id "d1" :name "lint" :input {:x "direct"}}]
                     [{:type "text" :text "done"}])
          :stop_reason "tool_use" :usage {:input_tokens 1 :output_tokens 1}}))
      (fn [base]
        (let [c (client/create-client
                 {:base-url base :style "anthropic" :model "m" :api-key "k"
                  :tool-tiers {:core ["alpha_read"] :deferred ["deploy"]}
                  :hooks {:before-tool (fn [ev]
                                         (swap! gated conj (str (:name ev) ":"
                                                                (if (:hints ev) (:destructive (:hints ev)) "?")))
                                         nil)}})
              r (client/run c "ship it" {:toolkit tk})]
          (is (= "done" (:status r)))
          (is (str/includes? (:output (first (:tool-calls r))) "\"name\": \"deploy\""))
          (is (= ["deploy(prod)" "lint(direct)"] @ran))
          (is (= ["deploy:true" "lint:?"] @gated)
              "tool_search is engine-internal; call_tool is unwrapped before the hook")
          (is (= "call_tool" (:name (second (:tool-calls r)))) "the transcript keeps the wrapper")
          (is (apply = @arrays) "the tools array is the same bytes on every request"))))))

(deftest tool-tier-events-name-the-real-tool
  (ct/with-llm "openai" [{:calls [{:id "c1" :name "call_tool" :args {:name "deploy" :arguments {:x "prod"}}}]}
                         {:text "done"}]
    (fn [{:keys [base]}]
      (let [ran (atom [])
            evs (atom [])
            c   (client/create-client {:base-url base :model "m" :api-key "k"
                                       :tool-tiers {:core ["alpha_read"]}})]
        (client/run c "go" {:toolkit (tier-toolkit ran)
                            :on-event (fn [e] (when (#{"tool_call" "tool_result"} (:type e))
                                                (swap! evs conj (str (:type e) ":" (:name e) ":" (:via e) ":"
                                                                     (get-in e [:args :x])))))})
        (is (= ["tool_call:deploy:call_tool:prod" "tool_result:deploy:call_tool:"] @evs))
        (is (= ["deploy(prod)"] @ran))))))
