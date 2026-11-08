;; toolnexus against the shared mock model server (tools/mock-llm/mock_llm.py).
;;
;; Unlike the in-process fakes elsewhere in the suite, these tests go over real
;; HTTP to the one mock every port shares, so the wire shapes asserted here are
;; the same bytes the other seven ports see. The mock is started as a python3
;; child on a free port (its first stdout line names the base URL), unless
;; TN_MOCK_LLM_URL points at one already running. No python3 => every test here
;; reports a skip and passes.
;;
;; Streaming is not covered: this port's network client has no SSE path (see
;; `client/run`'s docstring); only an in-process `:generate-stream` streams.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.mock-llm-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.http :as khttp]
            [koine.json :as json]
            [koine.process :as proc]
            [toolnexus.acp :as acp]
            [toolnexus.classifier :as jev]
            [toolnexus.client :as client]
            [toolnexus.content :as content]
            [toolnexus.core :as tn]
            [toolnexus.http :as http]
            [toolnexus.media :as media]
            [toolnexus.native :as native]
            [toolnexus.serve :as serve]
            [toolnexus.shared-examples-test :as te]))

(def ^:private base (atom nil))   ; ".../v1", or nil when skipped

(defn- python3? []
  (try (= 0 (:exit (proc/sh ["python3" "--version"] {:timeout-ms 10000})))
       (catch Throwable _ false)))

(defn- kit-file
  "A file of the mock kit. TN_MOCK_KIT_DIR (a directory holding mock_llm.py,
  mock_mcp.py, fake_cli.py, fake_acp_agent.py) wins over the repo's
  tools/mock-llm/ — the mutation check points it at a broken copy."
  [f]
  (let [d (env/get-env "TN_MOCK_KIT_DIR")
        d (if (seq d) d (when-let [ex (te/examples-dir)] (str ex "/../tools/mock-llm")))
        p (when d (str d "/" f))]
    (when (and p (fs/exists? p)) p)))

(defn- mock-script [] (kit-file "mock_llm.py"))

(defn- with-mock [f]
  (let [preset (env/get-env "TN_MOCK_LLM_URL")]
    (cond
      (seq preset)
      (do (reset! base (str/replace preset #"/+$" "")) (f) (reset! base nil))

      (not (python3?))
      (do (println "SKIP toolnexus.mock-llm-test: python3 not available") (f))

      (nil? (mock-script))
      (do (println "SKIP toolnexus.mock-llm-test: tools/mock-llm/mock_llm.py not found") (f))

      :else
      (let [child (proc/spawn ["python3" (mock-script) "--port" "0" "--quiet"])]
        (try
          (let [line (proc/read-line! child)]
            (reset! base (second (re-find #"(http://\S+)" (str line))))
            (f))
          (finally
            (reset! base nil)
            (proc/kill! child)))))))

(use-fixtures :once with-mock)

(defmacro ^:private when-mock [& body]
  `(if-let [~'b @base] (do ~@body) (is true "skipped: no mock model server")))

(defn- post! [b path body]
  (khttp/request {:method :post :url (str b path)
                  :headers {"content-type" "application/json"}
                  :body (json/write-str body) :timeout-ms 10000}))

(defn- counts [b]
  (json/read-str (:body (khttp/request {:method :get :url (str b "/_mock/requests")
                                        :timeout-ms 10000}))
                 {:key-fn str}))

(defn- add-tool [seen]
  (native/native-tool
   {:name "add" :description "Add two integers"
    :input-schema {:type "object"
                   :properties {:a {:type "integer"} :b {:type "integer"}}
                   :required ["a" "b"]}
    :run (fn [args] (swap! seen conj args) (str (+ (:a args) (:b args))))}))

(defn- tool-run [style]
  (when-mock
   (let [seen (atom [])
         tk   (tn/build {:tools [(add-tool seen)]})
         c    (client/create-client {:base-url b :style style :model "mock" :api-key "mock-key"})
         r    (client/run c "add one and one" {:toolkit tk})]
     (is (= [{:a 1 :b 1}] (mapv #(select-keys % [:a :b]) @seen)) "tool ran with the mock's args")
     (is (= "Tool result: 2" (:text r))))))

(deftest openai-run-executes-a-native-tool (tool-run "openai"))
(deftest anthropic-run-executes-a-native-tool (tool-run "anthropic"))

(deftest plain-run-returns-the-default-text
  (when-mock
   (let [c (client/create-client {:base-url b :style "openai" :model "mock" :api-key "mock-key"})]
     (is (= "This is a reply from the toolnexus mock model."
            (:text (client/run c "hi" {})))))))

(deftest an-image-attachment-arrives-intact
  (when-mock
   (let [ex   (te/examples-dir)
         hex  (subs (str/trim (fs/read-file (str ex "/media/fixture.png.sha256"))) 0 12)
         part (content/image-file (str ex "/media/fixture.png"))
         c    (client/create-client {:base-url b :style "openai" :model "mock" :api-key "mock-key"})
         r    (client/run c [(content/text-part "what is this?") part] {})]
     (is (str/includes? (:text r) (str "image/png 82 bytes sha256=" hex)) (:text r)))))

(deftest a-429-is-retried-once
  (when-mock
   (post! b "/_mock/reset" {})
   (post! b "/_mock/faults" {:path "/chat/completions" :status 429 :times 1 :retryAfter "0"})
   (let [c (client/create-client {:base-url b :style "openai" :model "mock" :api-key "mock-key"})
         r (client/run c "hi" {})]
     (is (= "This is a reply from the toolnexus mock model." (:text r)))
     (is (= 2 (some (fn [[k v]] (when (str/ends-with? k "/chat/completions") v)) (counts b)))
         (pr-str (counts b))))))

(def ^:private questions
  {"ok"   (jev/noul-question "Is the user happy?")
   "team" (jev/choice-question "Which team owns this?"
                               {"billing" "Billing owns money questions"
                                "infra"   "Infra owns outages"
                                "sales"   "Sales owns new deals"})
   "risk" (jev/score-question "How risky is it?" ["none" "low" "medium" "high"])})

(defn- check-classifier [c]
  (let [d1 (jev/evaluate c "the user says the invoice is wrong" questions)
        d2 (jev/evaluate c "the user says the invoice is wrong" questions)
        ch (jev/choice d1 "team")
        sc (:score (jev/score d1 "risk"))]
    (is (== 0.95 (:noul (jev/noul d1 "ok"))))
    (is (contains? #{"billing" "infra" "sales"} (:choice ch)))
    (is (< -1e-9 (- 1.0 (reduce + (vals (:probabilities ch)))) 1e-9))
    (is (<= 0 sc 3) (pr-str sc))
    (is (= (:choice ch) (:choice (jev/choice d2 "team"))) "deterministic")
    (is (= sc (:score (jev/score d2 "risk"))))))

(deftest systemone-custom-backend-against-the-mock
  (when-mock
   (check-classifier (jev/create-classifier {:backend "custom" :base-url b :model "mock"
                                             :supports {:images false}
                                             :api-key-env "MOCK_LLM_KEY"}))))

(deftest cloudflare-backend-against-the-mock
  (when-mock
   (let [origin (str/replace b #"/v1$" "")]
     (check-classifier (jev/create-classifier {:backend "cloudflare" :model "mock"
                                               :base-url (str origin "/v1/accounts/acc/ai/run")
                                               :api-key-env "MOCK_LLM_KEY"})))))

(deftest every-media-verb-against-the-mock
  (when-mock
   (let [dir  (fs/temp-dir! "tn-mock-media")
         path (str dir "/style.json")
         _    (fs/write-file path (:body (khttp/request {:method :get :timeout-ms 10000
                                                         :url (str b "/_mock/media-style.json")})))
         m    (media/create-media (media/load-style path))
         one  (fn [r] (first (:files r)))]
     (try
       (testing "image"
         (let [f (one (media/image m {:prompt "a fox"}))]
           (is (str/ends-with? (:url f) "/files/image.png"))
           (is (= "image/png" (:mimeType f)))))
       (testing "video"
         (let [f (one (media/video m {:prompt "the fox blinks"}))]
           (is (str/ends-with? (:url f) "/video.mp4"))
           (is (= "video/mp4" (:mimeType f)))))
       (testing "talking"
         (let [f (one (media/talking m {:image "http://x/i.png" :audio "http://x/a.wav"}))]
           (is (str/ends-with? (:url f) "/talking.mp4") (:url f))
           (is (= "video/mp4" (:mimeType f)))))
       (testing "speech"
         (let [f (one (media/speech m {:text "hello"}))]
           (is (str/ends-with? (:url f) "/speech.wav"))
           (is (= "audio/wav" (:mimeType f)))))
       (testing "transcribe"
         (is (= "Mock transcript of http://x/a.wav."
                (:text (media/transcribe m {:audio "http://x/a.wav"})))))
       (testing "cloneVoice"
         (is (= (str "voice-" (subs (jev/sha256-hex "narrator") 0 8))
                (:text (media/clone-voice m {:name "narrator" :samples ["http://x/a.wav"]})))))
       (testing "an async job that fails"
         (let [e (try (media/image m {:prompt "boom [fail]"}) nil (catch Throwable e e))]
           (is (some? e))
           (is (str/includes? (str (ex-message e)) "mock: job asked to fail") (ex-message e))))
       (finally (fs/delete-tree! dir))))))

;; ---------------------------------------------------------------------------
;; round 2 — MCP, HTTP, A2A, skills, one-shot CLI and ACP against the kit
;; ---------------------------------------------------------------------------

(defn- origin-of [b] (str/replace b #"/v1$" ""))

(defn- spawn-http-mcp
  "mock_mcp.py --http on a free port: [child url]."
  [& flags]
  (let [child (proc/spawn (into ["python3" (kit-file "mock_mcp.py") "--http" "--port" "0"] flags))
        line  (proc/read-line! child)]
    [child (second (re-find #"(http://\S+)" (str line)))]))

(defn- mixed-toolkit [b seen http-url legacy-url]
  (tn/build {:builtins false
             :skills   [(str (te/examples-dir) "/skills")]
             :mcp      {:mcpServers {"stdio"  {:command ["python3" (kit-file "mock_mcp.py")] :timeout 10000}
                                     "web"    {:url http-url :timeout 10000}
                                     "legacy" {:url legacy-url :timeout 10000}}}
             :agents   [{:card (str (origin-of b) "/.well-known/agent-card.json")
                         :timeout 10000 :poll-every 20}]
             :tools    [(add-tool seen)
                        (http/http-tool {:name "http_add" :description "Add over HTTP"
                                         :input-schema {:type "object"
                                                        :properties {:a {:type "integer"} :b {:type "integer"}}}
                                         :method :post :url (str b "/tools/add")})]}))

(defn- with-mixed [f]
  (let [[c1 u1] (spawn-http-mcp)
        [c2 u2] (spawn-http-mcp "--legacy")]
    (try
      (let [seen (atom [])
            tk   (mixed-toolkit @base seen u1 u2)]
        (try (f tk seen) (finally (tn/shutdown! tk))))
      (finally (proc/kill! c1) (proc/kill! c2)))))

(deftest a-mixed-toolkit-runs-a-tool-from-every-source
  (when-mock
   (with-mixed
     (fn [tk seen]
       (let [names (set (tn/tool-names tk))]
         (is (every? names ["stdio_add" "stdio_echo" "web_add" "legacy_add" "add" "http_add"
                            "mockagent_summarize" "skill"])
             (pr-str (sort names))))
       (is (= "5" (:output (tn/execute tk "stdio_add" {:a 2 :b 3}))) "MCP stdio, 2026 discover path")
       (is (= "5" (:output (tn/execute tk "web_add" {:a 2 :b 3}))) "MCP http, 2026 discover path")
       (is (= "5" (:output (tn/execute tk "legacy_add" {:a 2 :b 3}))) "MCP http, legacy initialize path")
       (is (= "{\"text\":\"hi\"}" (:output (tn/execute tk "web_echo" {:text "hi"}))) "SPEC §0.4: structuredContent wins")
       (is (= "5" (:output (tn/execute tk "add" {:a 2 :b 3}))))
       (is (= [{:a 2 :b 3}] @seen))
       (is (= {"sum" 5} (json/read-str (:output (tn/execute tk "http_add" {:a 2 :b 3})) {:key-fn str})))
       (is (= "A2A mock done: x" (:output (tn/execute tk "mockagent_summarize" {:task "x"}))))
       (let [out (:output (tn/execute tk "skill" {:name "hello-world"}))]
         (is (str/includes? out "<skill_content name=\"hello-world\">") out))))))

(deftest a-client-run-over-the-mixed-toolkit
  (when-mock
   (with-mixed
     (fn [tk _]
       (let [c (client/create-client {:base-url @base :style "openai" :model "mock" :api-key "mock-key"})
             r (client/run c "use a tool" {:toolkit tk})]
         (is (= 1 (:tool-call-count r)))
         (is (= "done" (:status r)))
         (is (str/starts-with? (:text r) "Tool result: ") (:text r))
         (is (= (str "Tool result: " (str/trim (str (:output (first (:tool-calls r))))))
                (:text r))
             (pr-str (first (:tool-calls r)))))))))

;; The one-shot CLI recipe (site/recipes/in-process-client/clojure/one-shot-cli).
(defn- render [{:keys [messages tools]}]
  (let [called (atom {})
        lines  (transient ["You are the model inside a tool-calling loop. Tools you may call:"])]
    (doseq [{f :function} tools]
      (conj! lines (str "- " (:name f) ": " (:description f) " args=" (json/write-str (:parameters f)))))
    (conj! lines "") (conj! lines "Conversation:")
    (doseq [m messages]
      (cond
        (= "tool" (:role m))
        (conj! lines (str "[tool " (get @called (:tool_call_id m) (:tool_call_id m)) "] " (:content m)))
        (seq (:tool_calls m))
        (do (doseq [c (:tool_calls m)] (swap! called assoc (:id c) (get-in c [:function :name])))
            (conj! lines (str "[assistant called] " (json/write-str (mapv :function (:tool_calls m))))))
        :else
        (conj! lines (str "[" (:role m) "] " (if (string? (:content m)) (:content m) (json/write-str (:content m)))))))
    (conj! lines "")
    (conj! lines "Reply with ONLY one JSON object: {\"tool_calls\":[{\"name\":\"...\",\"arguments\":{...}}]} to call tools,")
    (conj! lines "or {\"content\":\"...\"} with the final answer.")
    (str/join "\n" (persistent! lines))))

(defn- parse-reply [out]
  (let [r (json/read-str (subs out (str/index-of out "{") (inc (str/last-index-of out "}"))))]
    (if (seq (:tool_calls r))
      {:tool-calls (mapv (fn [c] {:name (:name c) :arguments (or (:arguments c) {})}) (:tool_calls r))}
      {:content (:content r)})))

(deftest a-one-shot-cli-drives-the-loop
  (if-let [cli (when @base (kit-file "fake_cli.py"))]
    (let [seen (atom [])
          c    (client/create-in-process-client
                {:model "fake-cli"
                 :generate (fn [req]
                             (let [r (proc/sh ["python3" cli] {:in (render req) :timeout-ms 30000})]
                               (is (= 0 (:exit r)) (:err r))
                               (parse-reply (:out r))))})
          r    (client/run c "Use the add tool to add 19 and 23." {:toolkit (tn/build {:builtins false
                                                                                       :tools [(add-tool seen)]})})]
      (is (= [{:a 19 :b 23}] @seen))
      (is (= "The sum is 42." (:text r))))
    (is true "skipped: no python3 / fake_cli.py")))

(defn- acp-run [prompt on-permission]
  (let [seen   (atom [])
        server (serve/as-mcp-server (tn/build {:builtins false :tools [(add-tool seen)]}))
        agent  (acp/connect ["python3" (kit-file "fake_acp_agent.py")]
                            (cond-> {:mcp-servers [{:type "http" :name "toolnexus" :url (:url server)
                                                    :headers [{:name "Authorization"
                                                               :value (str "Bearer " (:token server))}]}]
                                     :permission-timeout 30000}
                              on-permission (assoc :on-permission on-permission)))
        deltas (atom [])]
    (try
      (let [c (client/create-in-process-client {:model "fake-acp" :generate-stream (acp/generate-stream agent)})
            r (client/run c prompt {:toolkit (tn/build {:builtins false})
                                    :on-event (fn [ev] (when (= "text" (:type ev)) (swap! deltas conj (:delta ev))))})]
        {:result r :seen @seen :streamed (apply str @deltas)})
      (finally (acp/close agent) ((:close server))))))

(deftest an-acp-agent-calls-the-served-toolkit
  (if (and @base (kit-file "fake_acp_agent.py"))
    (do
      (testing "permission approved (default: first allow option)"
        (let [{:keys [result seen streamed]} (acp-run "Use the add tool to add 17 and 25." nil)]
          (is (= [{:a 17 :b 25}] (mapv #(select-keys % [:a :b]) seen)) "add really ran")
          (is (= "The sum is 42." streamed))
          (is (= "The sum is 42." (:text result)))))
      (testing "permission rejected through :on-permission"
        (let [{:keys [result seen]} (acp-run "Use the add tool." (fn [_] "no"))]
          (is (empty? seen))
          (is (= "Permission denied." (:text result))))))
    (is true "skipped: no python3 / fake_acp_agent.py")))

;; Case k3 (cancel a "[wait]" prompt) is NOT covered: this port's ACP client has
;; no session/cancel (issue #170).
