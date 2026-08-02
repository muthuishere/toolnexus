;; SPEC §7A — outbound A2A. The peer is a SCRIPTED koine.server on 127.0.0.1:0
;; speaking real JSON-RPC 2.0, not a stub: every branch of §7A is measured over
;; the wire. Hermetic — no LLM, no key, no internet.
;;
;; Task ids are FIXED strings (`task-ok`, `task-fail`, …) rather than uuids
;; because the error strings ARE the contract and must be asserted literally.
;; The timeout budget is 300ms with a 60ms poll interval, so the whole suite
;; runs in well under a second.
(ns toolnexus.a2a-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.a2a :as a2a]))

;; ---------------------------------------------------------------------------
;; the scripted remote agent
;; ---------------------------------------------------------------------------

(def ^:private script
  {"ok"     "task-ok"
   "hist"   "task-hist"
   "fail"   "task-fail"
   "cancel" "task-cancel"
   "hang"   "task-hang"
   "rpcerr" "task-rpcerr"})

(defn- task-state
  "The Task the peer reports for `kind` after `n` GetTask polls."
  [kind n]
  (let [task-id (get script kind "task-unknown")]
    (cond
      ;; `n` is the count of GetTask calls SO FAR INCLUDING this one, so `< n 2`
      ;; means "answer `working` to the first GetTask" — which is what makes the
      ;; poll loop actually loop instead of settling on its first look.
      (and (not= "hang" kind) (< n 2))
      {:id task-id :status {:state "working"}}

      (= "ok" kind)
      {:id task-id :status {:state "completed"}
       :artifacts [{:artifactId "artifact-1"
                    :parts [{:kind "text" :text "line one"}
                            {:kind "data" :data {:ignored true}}
                            {:kind "text" :text "line two"}]}]}

      ;; no artifacts ⇒ the history fallback (the LAST role:"agent" message)
      (= "hist" kind)
      {:id task-id :status {:state "completed"}
       :history [{:role "user"  :parts [{:kind "text" :text "hist"}]}
                 {:role "agent" :parts [{:kind "text" :text "earlier reply"}]}
                 {:role "agent" :parts [{:kind "text" :text "final reply"}]}]}

      (= "fail" kind)
      {:id task-id :status {:state "failed"
                            :message {:role "agent"
                                      :parts [{:kind "text" :text "boom"}]}}}

      ;; canceled with NO status.message — the "[: <text>]" half is optional
      (= "cancel" kind)
      {:id task-id :status {:state "canceled"}}

      ;; never leaves working — the timeout path
      :else
      {:id task-id :status {:state "working"}})))

(defn- agent-card-json [base with-url?]
  (merge {:name               "Demo Bot"
          :description        "A scripted A2A peer"
          :version            "0.1.0"
          :protocolVersion    "0.3.0"
          :capabilities       {:streaming false :pushNotifications false}
          :defaultInputModes  ["text"]
          :defaultOutputModes ["text"]
          :skills [{:id "echo" :name "Echo" :description "Echo a task back"}
                   ;; no :id — the tool name falls back to sanitize(name)
                   {:name "Slow Work" :description "Never finishes"}
                   ;; a dotted id — sanitize must be applied to the skill id
                   {:id "fail.now" :name "Fail" :description "Always fails"}]}
         (when with-url? {:url (str base "/")})))

(defn- rpc-handler [polls body]
  (let [req    (try (json/read-str body) (catch Throwable _ nil))
        method (:method req)
        rpc-id (:id req)]
    (cond
      (nil? req)
      {:jsonrpc "2.0" :id nil :error {:code -32700 :message "parse error"}}

      (= "SendMessage" method)
      (let [text (or (get-in req [:params :message :parts 0 :text]) "")
            kind (if (contains? script text) text "hang")
            tid  (get script kind)]
        (swap! polls assoc tid 0)
        {:jsonrpc "2.0" :id rpc-id :result {:id tid :status {:state "submitted"}}})

      (= "GetTask" method)
      (let [tid  (get-in req [:params :id])
            kind (or (some (fn [[k v]] (when (= v tid) k)) script) "hang")]
        (if (= "rpcerr" kind)
          ;; a JSON-RPC error object arriving MID-POLL (§7A is silent on this)
          {:jsonrpc "2.0" :id rpc-id :error {:code -32000 :message "agent exploded"}}
          (let [n (get (swap! polls update tid (fn [x] (inc (or x 0)))) tid)]
            {:jsonrpc "2.0" :id rpc-id :result (task-state kind n)})))

      :else
      {:jsonrpc "2.0" :id rpc-id :error {:code -32601 :message "method not found"}})))

(def ^:private peer (atom nil))

(defn- start-peer! []
  (let [polls (atom {})
        base  (atom "")
        h     (server/serve
                (fn [req]
                  (let [p (:path req)]
                    (cond
                      (= p "/.well-known/agent-card.json")
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (agent-card-json @base true))}

                      ;; a card with NO `url` — the endpoint must fall back to
                      ;; the card URL's origin
                      (= p "/.well-known/agent-card-nourl.json")
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (agent-card-json @base false))}

                      (= :post (:method req))
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (rpc-handler polls (:body req)))}

                      :else {:status 404 :body "no card here"})))
                {:port 0})]
    (reset! base (str "http://127.0.0.1:" (server/port h)))
    {:handle h :base @base}))

(use-fixtures :once
  (fn [f]
    (reset! peer (start-peer!))
    (try (f)
         (finally (server/stop! (:handle @peer))
                  (reset! peer nil)))))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- base [] (:base @peer))

(defn- resolved []
  (a2a/remote-agent {:card       (str (base) "/.well-known/agent-card.json")
                     :timeout    300
                     :poll-every 60}))

(defn- tool-named [tools nm]
  (first (filter (fn [t] (= nm (:name t))) tools)))

(defn- call
  ([tools nm task] (call tools nm task nil))
  ([tools nm task ctx]
   (let [t (tool-named tools nm)]
     (if ctx ((:execute t) {:task task} ctx) ((:execute t) {:task task})))))

;; ---------------------------------------------------------------------------
;; §7A resolve
;; ---------------------------------------------------------------------------

(deftest defaults-match-the-spec
  (is (= 300000 a2a/default-timeout))
  (is (= 1000 a2a/default-poll-every))
  (is (= #{"completed" "failed" "canceled"} a2a/terminal-states))
  (is (= #{"submitted" "working" "completed" "failed" "canceled"} a2a/task-states)))

(deftest card-is-fetched-and-parsed
  (let [c (:card (resolved))]
    (is (= "Demo Bot" (:name c)))
    (is (= "0.3.0" (:protocolVersion c)))
    (is (= {:streaming false :pushNotifications false} (:capabilities c)))
    (is (= ["text"] (:defaultInputModes c)))
    (is (= 3 (count (:skills c))))))

(deftest one-tool-per-skill-named-by-the-spec
  (let [tools (:tools (resolved))]
    (is (= 3 (count tools)))
    (testing "sanitize(card.name)_sanitize(skill.id ?? skill.name)"
      (is (= ["Demo_Bot_Slow_Work" "Demo_Bot_echo" "Demo_Bot_fail_now"]
             (vec (sort (map :name tools))))))
    (testing "source and inputSchema are verbatim §7A"
      (is (every? (fn [t] (= "a2a" (:source t))) tools))
      (is (every? (fn [t] (= a2a/task-input-schema (:input-schema t))) tools))
      (is (= {:type "object" :properties {:task {:type "string"}} :required ["task"]}
             a2a/task-input-schema)))
    (testing "description comes off the card"
      (is (= "Echo a task back" (:description (tool-named tools "Demo_Bot_echo")))))))

(deftest endpoint-is-card-url-with-origin-fallback
  (is (= (str (base) "/") (:endpoint (resolved))))
  (let [no-url (a2a/remote-agent {:card (str (base) "/.well-known/agent-card-nourl.json")
                                  :timeout 300 :poll-every 60})]
    (is (= (base) (:endpoint no-url))
        "no card.url ⇒ the endpoint falls back to the card URL's origin")))

(deftest a-failing-agent-is-isolated-never-fatal
  (let [missing (a2a/remote-agent {:card (str (base) "/.well-known/nope.json")
                                   :timeout 300 :poll-every 60})]
    (is (= [] (:tools missing)))
    (is (= "HTTP 404: no card here" (:error missing)))
    (is (nil? (:card missing))))
  (testing "a dead port is data, not a throw (koine transport failures)"
    (let [h    (server/serve (fn [_] {:status 200 :body "{}"}) {:port 0})
          dead (str "http://127.0.0.1:" (server/port h))
          _    (server/stop! h)
          r    (a2a/remote-agent {:card (str dead "/.well-known/agent-card.json")
                                  :timeout 300 :poll-every 60})]
      (is (= [] (:tools r)))
      (is (str/starts-with? (:error r) "HTTP transport ")))))

;; ---------------------------------------------------------------------------
;; §7A execute — the five terminal outcomes
;; ---------------------------------------------------------------------------

(deftest completed-joins-every-text-part
  (let [r (call (:tools (resolved)) "Demo_Bot_echo" "ok")]
    (is (false? (:isError r)))
    (is (= "line one\nline two" (:output r))
        "kind:\"data\" parts are skipped; text parts join with \\n")))

(deftest completed-falls-back-to-the-last-agent-history-message
  (let [r (call (:tools (resolved)) "Demo_Bot_echo" "hist")]
    (is (false? (:isError r)))
    (is (= "final reply" (:output r)))))

(deftest failed-carries-the-status-message-detail
  (let [r (call (:tools (resolved)) "Demo_Bot_fail_now" "fail")]
    (is (true? (:isError r)))
    (is (= "A2A task task-fail failed: boom" (:output r)))))

(deftest canceled-omits-the-detail-when-there-is-no-status-message
  (let [r (call (:tools (resolved)) "Demo_Bot_echo" "cancel")]
    (is (true? (:isError r)))
    (is (= "A2A task task-cancel canceled" (:output r))
        "the \"[: <text>]\" half is absent, with no stray colon")))

(deftest timeout-reports-the-configured-budget-not-the-elapsed-time
  ;; §7A AMBIGUITY: the same paragraph uses "ms" for both the message and
  ;; metadata.ms. The message carries the CONFIGURED BUDGET (the only reading
  ;; that is deterministic and therefore testable); metadata.ms is elapsed.
  (let [r (call (:tools (resolved)) "Demo_Bot_Slow_Work" "hang")]
    (is (true? (:isError r)))
    (is (= "A2A task task-hang timed out after 300ms (state=working)" (:output r)))
    (is (>= (get-in r [:metadata :ms]) 300)
        "metadata.ms is ELAPSED, and is a different number from the budget")))

(deftest ctx-abort-stops-before-the-next-get-task
  (let [r (call (:tools (resolved)) "Demo_Bot_Slow_Work" "hang" {:aborted? (fn [] true)})]
    (is (true? (:isError r)))
    (is (= "A2A task task-hang canceled" (:output r)))
    (is (= "canceled" (get-in r [:metadata :state]))
        "metadata.state is the LOCAL verdict; the remote task is still working")))

(deftest an-rpc-error-mid-poll-is-an-error-result-never-a-throw
  ;; §7A is SILENT here. koine returns transport failures as DATA and a JSON-RPC
  ;; error object is likewise not an exception, so there is nothing to catch —
  ;; both become an isError ToolResult carrying the metadata built so far.
  (let [r (call (:tools (resolved)) "Demo_Bot_echo" "rpcerr")]
    (is (true? (:isError r)))
    (is (= "agent exploded" (:output r)))
    (is (= "task-rpcerr" (get-in r [:metadata :taskId])))))

;; ---------------------------------------------------------------------------
;; §7A metadata
;; ---------------------------------------------------------------------------

(deftest metadata-is-on-every-result
  (let [tools (:tools (resolved))
        rs    [(call tools "Demo_Bot_echo" "ok")
               (call tools "Demo_Bot_echo" "hist")
               (call tools "Demo_Bot_fail_now" "fail")
               (call tools "Demo_Bot_echo" "cancel")
               (call tools "Demo_Bot_Slow_Work" "hang")
               (call tools "Demo_Bot_Slow_Work" "hang" {:aborted? (fn [] true)})]]
    (is (= 6 (count rs)))
    (doseq [r rs]
      (is (= #{:agent :taskId :state :polls :ms} (set (keys (:metadata r))))
          "§7A: metadata on EVERY result = {agent, taskId, state, polls, ms}")
      (is (= "Demo Bot" (get-in r [:metadata :agent]))
          "agent is the CARD's name, unsanitized")
      (is (number? (get-in r [:metadata :polls])))
      (is (number? (get-in r [:metadata :ms]))))))

(deftest polls-counts-successful-get-task-calls-only
  ;; §7A AMBIGUITY: `polls` is never defined. Decision: it counts SUCCESSFUL
  ;; `GetTask` responses. `SendMessage` is not a poll; a GetTask that errored is
  ;; not counted either.
  (let [tools (:tools (resolved))
        ok    (call tools "Demo_Bot_echo" "ok")
        rpce  (call tools "Demo_Bot_echo" "rpcerr")
        abort (call tools "Demo_Bot_Slow_Work" "hang" {:aborted? (fn [] true)})]
    (is (= 2 (get-in ok [:metadata :polls]))
        "the peer answers `working` once, then `completed` — two GetTask calls")
    (is (= 0 (get-in rpce [:metadata :polls]))
        "the failing GetTask is not counted")
    (is (= 0 (get-in abort [:metadata :polls]))
        "abort fires before the first GetTask, so SendMessage counts as zero")))

;; ---------------------------------------------------------------------------
;; §7A — the top-level `agents` config block
;; ---------------------------------------------------------------------------
;;
;; js/src/a2a.ts parseAgentsConfig: a NAME->descriptor object (mirroring
;; `mcpServers`), entries with no string `card` skipped, `disabled:true` /
;; `enabled:false` skipped, `pollEvery` accepted in its config-file spelling.

(deftest agents-config-block-parses-into-descriptors
  (let [ds (a2a/parse-agents-config {:one {:card "http://a/card" :timeout 5 :pollEvery 7}
                                     :two {:card "http://b/card"}})]
    (is (= ["http://a/card" "http://b/card"] (vec (sort (map :card ds)))))
    (let [one (first (filter #(= "http://a/card" (:card %)) ds))]
      (is (= 5 (:timeout one)))
      (is (= 7 (:poll-every one))))))

(deftest agents-config-block-skips-the-unusable-and-the-disabled
  (is (= [] (a2a/parse-agents-config nil)))
  (is (= [] (a2a/parse-agents-config {})))
  (is (= [] (a2a/parse-agents-config {:nocard {:timeout 1}})))
  (is (= [] (a2a/parse-agents-config {:off {:card "http://a" :disabled true}})))
  (is (= [] (a2a/parse-agents-config {:off {:card "http://a" :enabled false}}))))

(deftest agents-config-block-is-ordered-by-name
  ;; Two runtimes must not disagree about which agent registered first, and the
  ;; §7A tool names collide the moment two peers advertise the same card name.
  (is (= ["c-card" "m-card" "z-card"]
         (mapv :card (a2a/parse-agents-config {:zeta {:card "z-card"}
                                               :mid  {:card "m-card"}
                                               :alfa {:card "c-card"}})))))

(deftest descriptors-from-the-config-block-resolve-like-the-option
  (let [d (first (a2a/parse-agents-config
                  {:demo {:card (str (base) "/.well-known/agent-card.json")
                          :timeout 300 :pollEvery 60}}))]
    (is (= 3 (count (a2a/agent-tools d))))))
