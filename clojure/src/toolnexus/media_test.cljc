;; SPEC §7G — media generation. Replays conformance/media/ (read in place, never
;; copied) against an in-process fake HTTP server, plus RunResult.files (§8).
;;
;; No java.*, no Go interop, zero reader conditionals.
(ns toolnexus.media-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.media :as media]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.test-support :as ts]
            [toolnexus.tool :as tool]))

(defn- fixture [file]
  (json/read-str (fs/read-file (str (te/examples-dir) "/../conformance/media/" file))))

(defn- with-fake-server
  "Serve `script` in order (the LAST entry repeats once exhausted), record every
  request, and call (f base-url requests-atom)."
  [script f]
  (let [n    (atom 0)
        reqs (atom [])
        srv  (ts/serve
              (fn [req]
                (swap! reqs conj {:method  (str/upper-case (name (:method req)))
                                  :path    (:path req)
                                  :headers (reduce (fn [m e] (assoc m (str/lower-case (str (key e))) (val e)))
                                                   {} (:headers req))
                                  :body    (:body req)})
                (let [i     (min (dec (count script)) (dec (swap! n inc)))
                      entry (nth script i)]
                  {:status  (long (:status entry))
                   :headers {"content-type" "application/json"}
                   :body    (if (contains? entry :rawBody)
                              (:rawBody entry)
                              (json/write-str (:body entry)))})))]
    (try
      (f (str "http://127.0.0.1:" (server/port srv)) reqs)
      (finally (server/stop! srv)))))

(defn- case-media [c base progress]
  (media/create-media (update (:style c) :baseUrl #(str/replace % "{server}" base))
                      {:env         (fn [k] (get (:env c) (keyword k)))
                       :on-progress (fn [p] (swap! progress conj p))}))

(defn- function-face [c]
  (with-fake-server (:script c)
    (fn [base reqs]
      (let [progress (atom [])
            m        (case-media c base progress)
            out      (try {:ok (media/generate m (:verb c) (:args c))}
                          (catch Throwable e {:error (ex-message e)}))]
        {:out out :reqs @reqs :progress @progress}))))

(defn- tool-face [c]
  (with-fake-server (:script c)
    (fn [base _]
      (let [m  (case-media c base (atom []))
            t  (first (filter #(= (:name (:tool c)) (:name %)) (media/tools m)))]
        (when t
          (select-keys ((:execute t) (:args c)) [:isError :output :parts :metadata]))))))

(deftest conformance-cases-replay
  (let [cases (:cases (fixture "cases.json"))]
    (is (<= 19 (count cases)) "every fixture case is replayed")
    (doseq [c cases]
      (testing (:name c)
        (let [{:keys [out reqs progress]} (function-face c)]
          (when (contains? c :expectRequests)
            (is (= (mapv :method (:expectRequests c)) (mapv :method reqs)))
            (is (= (mapv :path (:expectRequests c)) (mapv :path reqs)))
            (doseq [[e r] (map vector (:expectRequests c) reqs)
                    :when (contains? e :body)]
              (is (= (:body e) (json/read-str (:body r))))
              (is (= "application/json" (get-in r [:headers "content-type"])))))
          (doseq [[h v] (:expectHeaders c)
                  r reqs]
            (is (= v (get-in r [:headers (str/lower-case (name h))])) (str "header " (name h))))
          (when (contains? c :expectProgress)
            (is (= (:expectProgress c) (mapv :status progress)))
            (is (every? #(= (:verb c) (:verb %)) progress)))
          (if (contains? c :error)
            (is (= (:error c) (:error out)))
            (is (= (:result c) (:ok out))))
          (when-let [expected (:tool c)]
            (is (= (dissoc expected :name) (tool-face c)))))))))

(deftest tool-schemas-match-fixture
  (let [fx (fixture "schemas.json")
        ts (media/tools (media/create-media (:style fx)))]
    (is (= (mapv :name (:tools fx)) (mapv :name ts)) "order")
    (is (= (:tools fx)
           ;; fixture spells it inputSchema; the port keys it :input-schema
           (mapv (fn [t] {:name (:name t) :description (:description t) :source (:source t)
                          :inputSchema (:input-schema t)})
                 ts)))))

(deftest verb-table-is-verbs-json-verbatim
  (is (= (:verbs (fixture "verbs.json")) media/verbs)))

(deftest provider-options-overwrite-last-and-dotpath-arrays
  (with-fake-server [{:status 200 :body {:u "https://x/a.png"}}]
    (fn [base reqs]
      (let [m (media/create-media {:baseUrl base
                                   :verbs {:image {:mode "sync" :kind "image" :start {:path "/s"}
                                                   :defaults {:model "a" :keep 1}
                                                   :args {:prompt "input.0.text"} :resultPath "u"}}})
            r (media/image m {:prompt "cat"} {:provider-options {:model "b"}})]
        (is (= "completed" (:status r)))
        (is (= {:model "b" :keep 1 :input [{:text "cat"}]}
               (json/read-str (:body (first @reqs)))))))))

(deftest cancellation-stops-the-poll
  (with-fake-server [{:status 200 :body {:id "j"}} {:status 200 :body {:st "processing"}}]
    (fn [base _]
      (let [n (atom 0)
            m (media/create-media
               {:baseUrl base
                :verbs {:video {:mode "async" :kind "video" :start {:path "/s"} :args {:prompt "p"}
                                :idPath "id" :resultPath "u"
                                :poll {:path "/p/{id}" :statusPath "st" :done ["ok"] :failed ["x"]
                                       :intervalMs 1 :timeoutMs 60000}}}}
               {:on-progress (fn [_] (swap! n inc))})
            e (try (media/video m {:prompt "x"} {:cancel (fn [] (when (>= @n 2) "stop"))})
                   nil (catch Throwable e e))]
        (is (= :cancelled (:toolnexus/error (ex-data e))))))))

(deftest toolkit-option-registers-media-tools
  (let [m  (media/create-media (:style (fixture "schemas.json")))
        tk (toolnexus/build {:builtins false :media m})]
    (is (= ["clone_voice" "generate_image" "generate_speech" "talking_video"] (tool/tool-names tk)))
    (is (every? #(= "media" (:source %)) (tool/all-tools tk)))))

(deftest run-result-collects-files
  (with-fake-server [{:status 200 :body {:u ["https://x/a.png" "https://x/b.webp"]}}]
    (fn [base _]
      (let [m  (media/create-media {:baseUrl base
                                    :verbs {:image {:mode "sync" :kind "image" :start {:path "/s"}
                                                    :args {:prompt "prompt"} :resultPath "u"}}})
            tk (toolnexus/build {:builtins false :media m})
            n  (atom 0)
            c  (client/create-in-process-client
                {:model "m"
                 :generate (fn [_]
                             (if (= 1 (swap! n inc))
                               {:tool-calls [{:id "call_img" :name "generate_image" :arguments {:prompt "cat"}}]}
                               {:content "done"}))})
            r  (client/run c "draw" {:toolkit tk})
            parts [{:type "image" :mimeType "image/png" :url "https://x/a.png"}
                   {:type "image" :mimeType "image/webp" :url "https://x/b.webp"}]]
        (is (= "done" (:text r)))
        (is (= parts (:parts (first (:tool-calls r)))))
        (is (= (mapv #(assoc % :from "generate_image" :callId "call_img") parts) (:files r)))
        (is (not (str/includes? (:text r) "https://")))
        (testing "a run without media is unchanged: files [] and no parts key"
          (let [r2 (client/run (client/create-in-process-client
                                {:model "m" :generate (fn [_] {:content "hi"})})
                               "x" {:toolkit (toolnexus/build {:builtins false})})]
            (is (= [] (:files r2)))))))))
