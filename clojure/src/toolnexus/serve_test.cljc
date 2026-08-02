;; SPEC §7B + §7C — the inbound side, over the wire, on 127.0.0.1 only.
;;
;; FIVE servers, because ABSENCE IS A BEHAVIOUR:
;;
;;   S1  a2a(configured) + mcp(configured)   card fields, provider, skills filter,
;;                                           mcp.tools filter, configured serverInfo
;;   S2  a2a{}           + mcp{}             every §7B/§7C DEFAULT, the whole task
;;                                           lifecycle, and every error path
;;   S3  a2a{} only                          POST /mcp ⇒ 404
;;   S4  mcp{} only                          GET card ⇒ 404, POST / ⇒ 404
;;   S5  neither                             everything 404s
;;
;; Two assertions here are not about the happy path at all and are the reason
;; this file is long: after a fulfilment that THROWS, and after a malformed
;; body, the server must still answer the NEXT request. "Never crashes the
;; server" is not provable by asserting on the failing call alone.
(ns toolnexus.serve-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [koine.fs :as fs]
            [koine.http :as http]
            [koine.json :as json]
            [koine.time :as time]
            [toolnexus.serve :as serve]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the toolkit under test
;; ---------------------------------------------------------------------------

(def ^:private skills
  ;; A SkillSource as DATA. The shared examples/ fixture ships exactly one
  ;; skill and a filter is meaningless over one, so a second is provided here —
  ;; still not a forked copy of the fixture.
  [{:name "hello-world" :description "Greets the world."}
   {:name "inline note" :description "A data-provided skill."}])

(defn- native [nm description f]
  (tool/tool {:name nm :description description
              :input-schema {:type "object"} :source "native"
              :execute (fn [args] (tool/success (f args)))}))

(def ^:private tk
  (tool/toolkit
    [;; the dot is the point: sanitize would make this "calc_sum",
     ;; and §7C must NOT touch it.
     (native "calc.sum" "Join two strings" (fn [a] (str (:x a) "|" (:y a))))
     (native "echo_ok" "Echo" (fn [a] (str "echo:" (:msg a))))
     (tool/tool {:name "kaboom" :description "Always throws"
                 :input-schema {:type "object"} :source "native"
                 :execute (fn [_] (throw (ex-info "tool exploded" {})))})
     (tool/tool {:name "skill" :description "Load a skill on demand."
                 :input-schema {:type "object"} :source "skill"
                 :execute (fn [args]
                            (if (some (fn [s] (= (:name s) (str (:name args)))) skills)
                              (tool/success (str "<skill_content name=\"" (:name args) "\">"))
                              (tool/failure (str "unknown skill: " (:name args)))))})]))

;; ---------------------------------------------------------------------------
;; the fulfilment under test
;; ---------------------------------------------------------------------------

(def ^:private gate (atom false))

(defn- run-fn
  "`gate` lets the suite OBSERVE `working` deterministically instead of racing
  it: the task named \"slow\" parks until the peer has seen `working`."
  [text]
  (cond
    (= text "boom") (throw (ex-info "fulfilment exploded" {:text text}))
    (= text "slow") (do (loop [n 0]
                          (when (and (not @gate) (< n 500))
                            (time/sleep! 20)
                            (recur (inc n))))
                        {:text "slow task done"})
    :else           {:text (str "ran: " text)}))

;; ---------------------------------------------------------------------------
;; servers
;; ---------------------------------------------------------------------------

(def ^:private servers (atom nil))
(def ^:private calls (atom []))

(defn- s [k] (get @servers k))
(defn- url [k] (:url (s k)))

(use-fixtures :once
  (fn [f]
    (reset! gate false)
    (reset! calls [])
    (reset! servers
            {:s1 (serve/serve tk {:port 0
                                  :a2a {:name "tn-agent" :description "inbound suite"
                                        :version "9.9.9"
                                        :provider {:organization "toolnexus"
                                                   :url "https://example.invalid"}
                                        :skills ["hello-world"]}
                                  :mcp {:name "tn-mcp" :version "2.0.0"
                                        :tools ["calc.sum" "echo_ok" "no-such-tool"]}
                                  :skills skills
                                  :run run-fn})
             :s2 (serve/serve tk {:port 0 :a2a {} :mcp {} :skills skills :run run-fn
                                  :on-call (fn [c] (swap! calls conj c))})
             :s3 (serve/serve tk {:port 0 :a2a {} :skills skills :run run-fn})
             :s4 (serve/serve tk {:port 0 :mcp {} :skills skills})
             :s5 (serve/serve tk {:port 0 :skills skills})})
    (try (f)
         (finally
           (doseq [k [:s1 :s2 :s3 :s4 :s5]] (serve/stop! (s k)))
           (reset! servers nil)))))

;; ---------------------------------------------------------------------------
;; the peer — a plain JSON-RPC client over koine.http
;; ---------------------------------------------------------------------------

(defn- post! [u body] (http/post-json u {} body))

(defn- rpc! [u method params]
  (let [res (post! u (json/write-str {:jsonrpc "2.0" :id 1 :method method :params params}))]
    (when (http/failed? res) (throw (ex-info (str "transport: " (name (:error res))) {:url u})))
    (json/read-str (:body res))))

(defn- get-json [u]
  (let [res (http/request {:method :get :url u})]
    (when-not (http/failed? res) (json/read-str (:body res)))))

(defn- get-status [u]
  (let [res (http/request {:method :get :url u})]
    (when-not (http/failed? res) (:status res))))

(defn- post-status [u body]
  (let [res (post! u body)]
    (when-not (http/failed? res) (:status res))))

(defn- card [k] (get-json (str (url k) "/.well-known/agent-card.json")))
(defn- send-message! [k text]
  (rpc! (str (url k) "/") "SendMessage"
        {:message {:role "user" :parts [{:kind "text" :text text}]}}))
(defn- get-task [k id] (rpc! (str (url k) "/") "GetTask" {:id id}))
(defn- mcp!
  ([k method] (mcp! k method {}))
  ([k method params] (rpc! (str (url k) "/mcp") method params)))

(defn- poll-for [k id pred limit]
  (loop [n 0]
    (let [t     (:result (get-task k id))
          state (get-in t [:status :state])]
      (cond
        (pred state) t
        (>= n limit) t
        :else (do (time/sleep! 20) (recur (inc n)))))))

;; ---------------------------------------------------------------------------
;; §7B — the Agent Card
;; ---------------------------------------------------------------------------

(deftest card-defaults-are-exactly-the-spec
  (let [c (card :s2)]
    (is (= ["capabilities" "defaultInputModes" "defaultOutputModes" "description"
            "name" "protocolVersion" "skills" "url" "version"]
           (vec (sort (map name (keys c)))))
        "no `provider` key at all when it is not configured — absent, not null")
    (is (= "toolnexus-agent" (:name c)))
    (is (= "" (:description c)))
    (is (= "0.1.0" (:version c)))
    (is (= "0.3.0" (:protocolVersion c)))
    (is (= {:streaming false :pushNotifications false} (:capabilities c)))
    (is (= ["text"] (:defaultInputModes c)))
    (is (= ["text"] (:defaultOutputModes c)))
    (is (= (str (url :s2) "/") (:url c)) "url = base + \"/\"")))

(deftest card-reports-configured-values-and-provider
  (let [c (card :s1)]
    (is (= "tn-agent" (:name c)))
    (is (= "inbound suite" (:description c)))
    (is (= "9.9.9" (:version c)))
    (is (contains? c :provider))
    (is (= {:organization "toolnexus" :url "https://example.invalid"} (:provider c)))))

(deftest card-skills-come-from-the-skill-source-never-the-tools
  (let [c2 (card :s2)
        c1 (card :s1)]
    (is (= 2 (count (:skills c2))) "unfiltered ⇒ every skill")
    (is (= 1 (count (:skills c1))) "filtered to a2a.skills")
    (is (= ["hello-world"] (mapv :name (:skills c1))))
    (is (every? (fn [s'] (not (str/blank? (:description s')))) (:skills c2)))
    (testing "NEVER raw tools"
      (let [ids (set (concat (map :id (:skills c2)) (map :name (:skills c2))))]
        (is (empty? (filter ids (tool/tool-names tk))))))
    (testing "the §7B/§7C asymmetry: skill IDs are sanitized, names are not"
      (is (= [{:id "hello-world" :name "hello-world"}
              {:id "inline_note" :name "inline note"}]
             (mapv (fn [s'] {:id (:id s') :name (:name s')}) (:skills c2)))))))

(deftest unknown-names-in-a2a-skills-are-ignored
  ;; §7B is silent; §7C says unknown mcp.tools names are ignored. Symmetry.
  (let [c (serve/agent-card {:skills ["hello-world" "no-such-skill"]} "http://x" skills)]
    (is (= ["hello-world"] (mapv :name (:skills c))))))

;; ---------------------------------------------------------------------------
;; §7B — the task lifecycle
;; ---------------------------------------------------------------------------

(deftest send-message-returns-submitted-immediately-then-fulfils-async
  (reset! gate false)
  (let [sent (send-message! :s2 "slow")
        id   (get-in sent [:result :id])]
    (is (= "submitted" (get-in sent [:result :status :state]))
        "returned IMMEDIATELY, before any fulfilment")
    (is (some? id))
    (let [working (poll-for :s2 id (fn [st] (= "working" st)) 200)]
      (is (= "working" (get-in working [:status :state]))
          "the store records `working` before the run"))
    (reset! gate true)
    (let [done (poll-for :s2 id (fn [st] (= "completed" st)) 400)]
      (is (= "completed" (get-in done [:status :state])))
      (is (= 1 (count (:artifacts done))))
      (is (some? (get-in done [:artifacts 0 :artifactId])))
      (is (= ["text"] (mapv :kind (get-in done [:artifacts 0 :parts]))))
      (is (= "slow task done" (get-in done [:artifacts 0 :parts 0 :text]))))))

(deftest a-throwing-fulfilment-fails-the-task-and-never-crashes-the-server
  (let [id     (get-in (send-message! :s2 "boom") [:result :id])
        failed (poll-for :s2 id (fn [st] (contains? #{"failed" "completed"} st)) 400)]
    (is (= "failed" (get-in failed [:status :state])))
    (is (= "agent" (get-in failed [:status :message :role])))
    (is (= ["text"] (mapv :kind (get-in failed [:status :message :parts]))))
    (is (= "fulfilment exploded" (get-in failed [:status :message :parts 0 :text])))
    (is (nil? (:artifacts failed)) "a failed task carries no artifacts key"))
  (testing "the server is STILL ANSWERING afterwards"
    (let [id (get-in (send-message! :s2 "after the crash") [:result :id])
          t  (poll-for :s2 id (fn [st] (= "completed" st)) 400)]
      (is (= "completed" (get-in t [:status :state])))
      (is (= "ran: after the crash" (get-in t [:artifacts 0 :parts 0 :text]))))))

;; ---------------------------------------------------------------------------
;; §7B — error paths
;; ---------------------------------------------------------------------------

(deftest a2a-error-codes-and-survival
  (let [unknown-task (get-task :s2 "task-does-not-exist")]
    (is (= -32001 (get-in unknown-task [:error :code])))
    (is (not (contains? unknown-task :result))))
  (is (= -32601 (get-in (rpc! (str (url :s2) "/") "NoSuchMethod" {}) [:error :code])))
  (testing "a malformed body is -32700 with a null id, on HTTP 200"
    (let [res (post! (str (url :s2) "/") "{not json at all")
          m   (json/read-str (:body res))]
      (is (= 200 (:status res)))
      (is (= -32700 (get-in m [:error :code])))
      (is (nil? (:id m)))))
  (testing "…and the server still answers the NEXT request"
    (is (= -32601 (get-in (rpc! (str (url :s2) "/") "NoSuchMethod" {}) [:error :code])))))

;; ---------------------------------------------------------------------------
;; §7C — MCP
;; ---------------------------------------------------------------------------

(deftest mcp-initialize-advertises-server-info
  (let [d (mcp! :s2 "initialize" {})
        c (mcp! :s1 "initialize" {})]
    (is (= {:name "toolnexus" :version "0.1.0"} (get-in d [:result :serverInfo])))
    (is (= "2024-11-05" (get-in d [:result :protocolVersion])))
    (is (contains? (get-in d [:result :capabilities]) :tools))
    (is (= {:name "tn-mcp" :version "2.0.0"} (get-in c [:result :serverInfo])))))

(deftest tools-list-uses-toolkit-names-verbatim
  ;; The load-bearing asymmetry: §7A/§7B skill ids are sanitized, §7C tool names
  ;; are NOT. Re-sanitizing at a gateway double-prefixes names on every hop.
  (let [entries (get-in (mcp! :s2 "tools/list") [:result :tools])
        names   (mapv :name entries)]
    (is (= ["calc.sum" "echo_ok" "kaboom" "skill"] names))
    (is (= "calc_sum" (tool/sanitize "calc.sum")))
    (is (not (contains? (set names) "calc_sum"))
        "the sanitized spelling must appear NOWHERE in the list")
    (is (not (str/includes? (json/write-str entries) "calc_sum"))
        "…nor anywhere in the serialized payload")
    (is (every? (fn [e] (map? (:inputSchema e))) entries))
    (is (= "Join two strings" (:description (first entries))))))

(deftest mcp-tools-filters-the-list-and-ignores-unknown-names
  (let [names (mapv :name (get-in (mcp! :s1 "tools/list") [:result :tools]))]
    (is (= ["calc.sum" "echo_ok"] names))
    (is (not (contains? (set names) "no-such-tool")) "unknown names ignored, never an error")
    (is (not (contains? (mcp! :s1 "tools/list") :error)))))

(deftest tools-call-maps-tool-result-to-call-tool-result
  (let [ok (mcp! :s2 "tools/call" {:name "echo_ok" :arguments {:msg "hi"}})]
    (is (= ["text"] (mapv :type (get-in ok [:result :content]))))
    (is (= "echo:hi" (get-in ok [:result :content 0 :text])))
    (is (false? (get-in ok [:result :isError]))))
  (testing "isError propagates from the ToolResult"
    (let [e (mcp! :s2 "tools/call" {:name "skill" :arguments {:name "nope"}})]
      (is (true? (get-in e [:result :isError])))
      (is (= "unknown skill: nope" (get-in e [:result :content 0 :text])))))
  (testing "an execute THROW becomes isError:true, never a JSON-RPC error"
    (let [t (mcp! :s2 "tools/call" {:name "kaboom" :arguments {}})]
      (is (true? (get-in t [:result :isError])))
      (is (= "tool exploded" (get-in t [:result :content 0 :text])))
      (is (not (contains? t :error)))))
  (testing "…and the very next call succeeds"
    (let [a (mcp! :s2 "tools/call" {:name "calc.sum" :arguments {:x "a" :y "b"}})]
      (is (= "a|b" (get-in a [:result :content 0 :text])))
      (is (false? (get-in a [:result :isError])))))
  (testing "an unknown tool name is -32602, with no result"
    (let [u (mcp! :s2 "tools/call" {:name "not_a_tool" :arguments {}})]
      (is (= -32602 (get-in u [:error :code])))
      (is (not (contains? u :result))))))

(deftest mcp-tools-allowlist-is-authoritative-for-calls-too
  ;; KNOWN SPEC DEFECT (§7C): the spec says `mcp.tools` filters the LIST and says
  ;; nothing about `tools/call`. Ported literally, an excluded tool stays
  ;; CALLABLE and the allowlist is cosmetic. We make it authoritative.
  (let [r (mcp! :s1 "tools/call" {:name "kaboom" :arguments {}})]
    (is (= -32602 (get-in r [:error :code]))
        "`kaboom` exists in the toolkit but is filtered out of this profile")
    (is (not (contains? r :result))))
  (testing "a tool that IS in the allowlist still works on the same server"
    (is (= "x|y" (get-in (mcp! :s1 "tools/call" {:name "calc.sum" :arguments {:x "x" :y "y"}})
                         [:result :content 0 :text])))))

(deftest mcp-parse-error-is-32700-and-the-server-survives
  (let [res (post! (str (url :s2) "/mcp") "}{")
        m   (json/read-str (:body res))]
    (is (= 200 (:status res)))
    (is (= -32700 (get-in m [:error :code]))))
  (is (= -32601 (get-in (mcp! :s2 "NoSuchMethod" {}) [:error :code])))
  (is (= {:name "toolnexus" :version "0.1.0"}
         (get-in (mcp! :s2 "initialize" {}) [:result :serverInfo]))))

(deftest on-call-fires-per-inbound-tools-call
  ;; Self-contained on purpose: clojure.test's var order is NOT the source
  ;; order, and it differs between the JVM and cljgo — a test that reads an
  ;; atom another test filled is green on one host and red on the other.
  (reset! calls [])
  (mcp! :s2 "tools/call" {:name "echo_ok" :arguments {:msg "x"}})
  (mcp! :s2 "tools/call" {:name "kaboom" :arguments {}})
  (mcp! :s2 "tools/call" {:name "not_a_tool" :arguments {}})
  (let [evs @calls]
    (is (= 2 (count evs)) "an unknown tool never reaches a tool, so never fires on-call")
    (is (every? (fn [c] (= #{:name :source :isError} (set (keys c)))) evs))
    (is (= ["echo_ok" "kaboom"] (mapv :name evs)) "in call order")
    (is (= [false true] (mapv :isError evs)))
    (is (= ["native" "native"] (mapv :source evs)))))

(deftest co-mounted-profiles-carry-two-independent-identities
  ;; NOT a bug in this port — a SPEC observation, pinned here so it cannot drift
  ;; away unnoticed (S21 finding 7). §7B and §7C give the two profiles
  ;; independent name/version with DIFFERENT defaults, so one base URL answers
  ;; as two differently-named agents; and the Agent Card has no field at all
  ;; that advertises the co-mounted /mcp surface, so an A2A peer that fetches
  ;; the card can never discover the MCP server one path away.
  (let [c (card :s2)]
    (is (= "toolnexus-agent" (:name c)))
    (is (= "toolnexus" (get-in (mcp! :s2 "initialize") [:result :serverInfo :name]))
        "same server, same base URL, two names")
    (is (= (str (url :s2) "/") (:url c))
        "card.url is the A2A JSON-RPC endpoint, not the MCP one")
    (is (not (str/includes? (json/write-str c) "/mcp"))
        "nothing in the card advertises the co-mounted MCP surface")))

;; ---------------------------------------------------------------------------
;; absence
;; ---------------------------------------------------------------------------

(deftest an-absent-mcp-profile-mounts-no-mcp
  (is (= 404 (post-status (str (url :s3) "/mcp")
                          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")))
  (is (= 200 (get-status (str (url :s3) "/.well-known/agent-card.json")))))

(deftest an-absent-a2a-profile-mounts-no-a2a
  (is (= 404 (get-status (str (url :s4) "/.well-known/agent-card.json"))))
  (is (= 404 (post-status (str (url :s4) "/")
                          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"x\"}}")))
  (is (= "toolnexus" (get-in (mcp! :s4 "initialize" {}) [:result :serverInfo :name]))
      "…and /mcp still answers: the two profiles mount independently"))

(deftest neither-profile-mounts-nothing-at-all
  (is (= 404 (get-status (str (url :s5) "/.well-known/agent-card.json"))))
  (is (= 404 (post-status (str (url :s5) "/") "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetTask\"}")))
  (is (= 404 (post-status (str (url :s5) "/mcp") "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))))

;; ---------------------------------------------------------------------------
;; §7B TaskStore
;; ---------------------------------------------------------------------------

(deftest resolve-store-honours-the-three-forms
  (testing "default / \"memory\""
    (let [m (serve/resolve-store nil)]
      ((:save m) {:id "t1" :status {:state "submitted"}})
      (is (= "submitted" (get-in ((:get m) "t1") [:status :state])))
      (is (nil? ((:get m) "nope")))))
  (testing "an object is used as-is"
    (let [seen (atom nil)
          obj  {:get (fn [_] {:id "fixed"}) :save (fn [t] (reset! seen t) t)}]
      (is (identical? obj (serve/resolve-store obj)))))
  (testing "\"file:<dir>\" round-trips through disk"
    (let [dir (fs/temp-dir! "tn-serve-store")
          fst (serve/resolve-store (str "file:" dir))]
      (try
        ((:save fst) {:id "t2" :status {:state "completed"}})
        (is (= "completed" (get-in ((:get fst) "t2") [:status :state])))
        (is (nil? ((:get fst) "absent")))
        (finally (fs/delete-tree! dir))))))

(deftest a-custom-store-carries-the-whole-lifecycle
  (let [saved (atom [])
        mem   (serve/memory-store)
        spy   {:get  (:get mem)
               :save (fn [t] (swap! saved conj (get-in t [:status :state])) ((:save mem) t))}
        h     (serve/serve tk {:port 0 :a2a {} :skills skills :run run-fn :store spy})]
    (try
      (let [id (get-in (rpc! (str (:url h) "/") "SendMessage"
                             {:message {:role "user" :parts [{:kind "text" :text "hi"}]}})
                       [:result :id])]
        (loop [n 0]
          (when (and (< n 400)
                     (not= "completed" (get-in (rpc! (str (:url h) "/") "GetTask" {:id id})
                                               [:result :status :state])))
            (time/sleep! 20)
            (recur (inc n))))
        (is (= ["submitted" "working" "completed"] @saved)
            "every Task read/write goes through the store"))
      (finally (serve/stop! h)))))
