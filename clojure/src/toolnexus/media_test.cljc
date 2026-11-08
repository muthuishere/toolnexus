;; SPEC §7G — media clients. Replays ALL of conformance/media/ (read in place,
;; never copied): verbs.json, schemas.json, cases.json (style engine),
;; clients.json (built-in API clients), cli.json (CLI clients over the fake CLI)
;; and routing.json — hermetically: an in-process fake HTTP server, the POSIX
;; fake CLI under /bin/sh, no network, no keys. Plus RunResult.files (§8) and
;; model visibility (§7G.6).
;;
;; No java.*, no Go interop, zero reader conditionals. Every assertion count is
;; data-driven and identical on both hosts (the all-modes gate compares them).
(ns toolnexus.media-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.env]
            [koine.host :as host]
            [koine.http :as khttp]
            [koine.time :as ktime]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.media :as media]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.test-support :as ts]
            [toolnexus.tool :as tool]))

(defn- conformance-dir [] (str (te/examples-dir) "/../conformance/media"))

(defn- fixture [file]
  (json/read-str (fs/read-file (str (conformance-dir) "/" file))))

;; ---------------------------------------------------------------------------
;; harness helpers
;; ---------------------------------------------------------------------------

(defn- subst
  "Deep-replace the harness placeholders in every string, except under the
  keys in `skip` (fixture lines; regex patterns are handled separately)."
  [x reps skip]
  (cond
    (string? x) (reduce (fn [s [k v]] (str/replace s k v)) x reps)
    (map? x)    (reduce (fn [m e] (assoc m (key e) (if (contains? skip (key e)) (val e) (subst (val e) reps skip))))
                        {} x)
    (sequential? x) (mapv #(subst % reps skip) x)
    :else x))

(defn- re-escape [s]
  (apply str (map (fn [c] (if (contains? (set "\\.^$|?*+()[]{}") c) (str "\\" c) (str c))) s)))

(defn- lower-keys [m] (reduce (fn [acc e] (assoc acc (str/lower-case (name (key e))) (val e))) {} m))

(defn- kebab-config
  "Fixture config (camelCase JSON) -> the port's config map."
  [cfg]
  (cond-> (dissoc (or cfg {}) :loop :gather)
    (:loop cfg)   (assoc :loop (cond-> {}
                                 (contains? (:loop cfg) :intervalMs) (assoc :interval-ms (get-in cfg [:loop :intervalMs]))
                                 (contains? (:loop cfg) :timeoutMs) (assoc :timeout-ms (get-in cfg [:loop :timeoutMs]))))
    (:gather cfg) (assoc :gather (cond-> (dissoc (:gather cfg) :maxBytes)
                                   (contains? (:gather cfg) :maxBytes) (assoc :max-bytes (get-in cfg [:gather :maxBytes]))))))

(defn- env-lookup [env] (fn [k] (get env (keyword k))))

(defn- parse-multipart [content-type body]
  (let [b     (subs content-type (+ (str/index-of content-type "boundary=") 9))
        delim (str "--" b)
        chunks (rest (str/split body (re-pattern (re-escape delim))))]
    (vec (for [ch chunks
               :when (str/starts-with? ch "\r\n")
               :let [ch   (subs ch 2)
                     sep  (str/index-of ch "\r\n\r\n")
                     head (subs ch 0 sep)
                     cont (subs ch (+ sep 4) (- (count ch) 2))
                     disp (first (filter #(str/starts-with? (str/lower-case %) "content-disposition") (str/split head #"\r\n")))
                     ct   (first (filter #(str/starts-with? (str/lower-case %) "content-type") (str/split head #"\r\n")))
                     nm   (second (re-find #"name=\"([^\"]*)\"" disp))
                     fnm  (second (re-find #"filename=\"([^\"]*)\"" disp))]]
           (if fnm
             {:name nm :filename fnm :contentType (str/trim (subs ct (inc (str/index-of ct ":"))))
              :bytesBase64 (codec/encode cont)}
             {:name nm :value cont})))))

(defn- with-fake-server
  "Serve `script` in order (the LAST entry repeats once exhausted; `{server}` is
  substituted at serve time), record every request as RECEIVED, and call
  (f base-url received-atom)."
  [script f]
  (let [n     (atom 0)
        base* (atom nil)
        reqs  (atom [])
        srv   (ts/serve
               (fn [req]
                 (swap! reqs conj {:method  (str/upper-case (name (:method req)))
                                   :path    (:path req)
                                   :headers (lower-keys (:headers req))
                                   :body    (:body req)})
                 (let [i     (min (dec (count script)) (dec (swap! n inc)))
                       entry (subst (nth script i) [["{server}" @base*]] #{})
                       hs    (lower-keys (:headers entry))]
                   {:status  (long (:status entry))
                    :headers (cond-> hs
                               (and (contains? entry :body) (not (contains? hs "content-type")))
                               (assoc "content-type" "application/json")
                               (and (not (contains? entry :body)) (not (contains? hs "content-type")))
                               (assoc "content-type" "application/octet-stream"))
                    :body    (cond (contains? entry :rawBody) (:rawBody entry)
                                   (contains? entry :bodyBase64) (codec/decode (:bodyBase64 entry))
                                   :else (json/write-str (:body entry)))})))]
    (reset! base* (str "http://127.0.0.1:" (server/port srv)))
    (try
      (f @base* reqs)
      (finally (server/stop! srv)))))

(defn- seam
  "The http-client seam: records the URL as SENT (koine.server decodes paths)."
  [base sent]
  (fn [req]
    (swap! sent conj (let [u (:url req)] (if (str/starts-with? u base) (subs u (count base)) u)))
    (khttp/request req)))

(defn- merged-requests
  "Received requests, with the path as SENT where the seam saw the hop. On cljgo
  Go follows redirects below the seam, so later hops keep the server's record."
  [reqs sent]
  (vec (map-indexed (fn [i r] (if (< i (count sent)) (assoc r :path (nth sent i)) r)) reqs)))

(defn- check-requests [c reqs]
  (when (contains? c :expectRequests)
    (let [exp (:expectRequests c)]
      (is (= (count exp) (count reqs)) "request count")
      (doseq [[e r] (map vector exp reqs)]
        (is (= (:method e) (:method r)))
        (is (= (:path e) (:path r)))
        (when (contains? e :body)
          (is (= (:body e) (json/read-str (:body r))))
          (is (= "application/json" (get-in r [:headers "content-type"]))))
        (when (contains? e :multipart)
          (is (= (:multipart e) (parse-multipart (get-in r [:headers "content-type"]) (:body r)))))
        (doseq [[h v] (:headers e)]
          (is (= v (get-in r [:headers (str/lower-case (name h))])) (str "header " (name h))))
        (doseq [h (:absentHeaders e)]
          (is (nil? (get-in r [:headers (str/lower-case h)])) (str "absent header " h))))))
  (doseq [[h v] (:expectHeaders c)
          r reqs]
    (is (= v (get-in r [:headers (str/lower-case (name h))])) (str "header " (name h))))
  ;; D23: header value matches the pattern on EVERY request
  (doseq [[h pat] (:expectHeaderPatterns c)
          r reqs]
    (is (re-find (re-pattern pat) (str (get-in r [:headers (str/lower-case (name h))])))
        (str "header pattern " (name h)))))

(defn- basename [p] (let [i (tool/last-index-of-char p \/)] (if i (subs p (inc i)) p)))
(defn- dirname [p] (let [i (tool/last-index-of-char p \/)] (if i (subs p 0 i) "")))

(defn- check-files [exp got dir client verb]
  (is (= (count exp) (count got)) "file count")
  (doseq [[e g] (map vector exp got)]
    (if-let [sv (:saved e)]
      (do (is (= (dissoc e :saved) (select-keys g [:type :mimeType])))
          (is (= dir (dirname (str (:path g)))) "saved directly in {dir}")
          (is (re-matches (re-pattern (str "^" client "-" verb "-[0-9]+-[0-9]+" (re-escape (:ext sv)) "$"))
                          (basename (str (:path g)))))
          (is (= (str "file://" (:path g)) (:url g)))
          (is (= (:bytesBase64 sv) (codec/encode (fs/read-bytes (:path g))))))
      (is (= e g)))))

(defn- check-outcome
  "Function-face outcome {:ok r} | {:error e} against result/error/status."
  [c out dir client]
  (if (or (contains? c :error) (contains? c :errorPrefix))
    (let [e (:error out)]
      (if (contains? c :error)
        (is (= (:error c) (some-> e ex-message)))
        (is (str/starts-with? (str (some-> e ex-message)) (:errorPrefix c))))
      (when (contains? c :status)
        (is (= (:status c) (some-> e ex-data :status name)) "error status")))
    (let [r (:ok out) exp (:result c)]
      (is (some? r) (str "no error expected: " (some-> (:error out) ex-message)))
      ;; style/model are asserted only when the case names them (§7G.10b)
      (is (= (dissoc exp :files)
             (apply dissoc r :files (remove #(contains? exp %) [:style :model]))))
      (check-files (:files exp) (:files r) dir client (:verb c)))))

(defn- check-tool [t res dir]
  (is (= (:isError t) (:isError res)))
  (when (contains? t :output) (is (= (:output t) (:output res))))
  (when (contains? t :outputPrefix) (is (str/starts-with? (str (:output res)) (:outputPrefix t))))
  (when (contains? t :outputPattern)
    (is (re-matches (re-pattern (str/replace (:outputPattern t) "{dir}" (re-escape dir))) (str (:output res)))
        (str "output " (:output res))))
  (when (contains? t :parts) (is (= (:parts t) (:parts res))))
  (when (contains? t :metadata) (is (= (:metadata t) (:metadata res))))
  (when (:noParts t) (is (not (contains? res :parts))))
  (when (seq (:parts res))
    (is (not-any? #(contains? % :path) (:parts res)) "parts never hold a path (D20)"))
  (when (contains? t :modelParts) (is (= (:modelParts t) (media/model-parts res)))))

(defn- tool-name [verb] (:tool (first (filter #(= verb (:verb %)) media/verbs))))

(defn- run-tool [m verb args]
  (let [t (first (filter #(= (tool-name verb) (:name %)) (media/tools m)))]
    (when t ((:execute t) args))))

(defn- attempt [f] (try {:ok (f)} (catch Throwable e {:error e})))

;; ---------------------------------------------------------------------------
;; HTTP faces (cases.json + clients.json)
;; ---------------------------------------------------------------------------

(def ^:private builders
  {"a2e" media/a2e-media-client "elevenlabs" media/elevenlabs-media-client
   "openai" media/openai-media-client "openrouter" media/openrouter-media-client
   "fal" media/fal-media-client})

(defn- ctx-for
  "Function-face ctx: progress, plus ctx.deadline = now + ctxTimeoutMs (D12)."
  [c progress]
  (cond-> {:progress (fn [p] (swap! progress conj p))}
    (contains? c :ctxTimeoutMs) (assoc :deadline (+ (ktime/now-ms) (:ctxTimeoutMs c)))))

(defn- http-face
  "One face of one HTTP case on a fresh server, {dir} and {inputs}. `build`
  makes the client from (case config)."
  [c0 build face check]
  (let [dir    (fs/temp-dir! "tn-media-dir")
        inputs (fs/temp-dir! "tn-media-in")]
    (try
      (doseq [[n b64] (:inputFiles c0)]
        (fs/write-bytes (str inputs "/" (name n)) (codec/decode-bytes b64)))
      (with-fake-server (:script c0)
        (fn [base reqs]
          (let [c        (subst c0 [["{server}" base] ["{dir}" dir] ["{inputs}" inputs]] #{:outputPattern :script})
                sent     (atom [])
                progress (atom [])
                cfg      (assoc (kebab-config (:config c))
                                :http-client (seam base sent)
                                :env (env-lookup (:env c)))
                t0       (ktime/mono-ms)
                out      (attempt (fn []
                                    (let [m (media/create-media (build c cfg))]
                                      (if (= face :fn)
                                        (media/generate m (:verb c) (:args c)
                                                        (ctx-for c progress))
                                        (run-tool m (:verb c) (:args c))))))
                elapsed  (- (ktime/mono-ms) t0)]
            (when (and (= face :fn) (contains? c :maxElapsedMs))
              (is (< elapsed (:maxElapsedMs c)) "returned in time"))
            (check {:c c :dir dir :out out :progress @progress
                    :reqs (merged-requests @reqs @sent)}))))
      (finally (fs/delete-tree! dir) (fs/delete-tree! inputs)))))

(defn- check-run-files
  "D20 runFilePath: RunResult.files built (by the port's run-files) from a
  toolCalls record of this tool result. true => each entry's path is the
  MediaFile's absolute path — the tool output names that same path per file;
  false => no entry has a path."
  [c res]
  (when (contains? c :runFilePath)
    (let [fs* (client/run-files [{:name "t" :parts (:parts res)}])]
      (if (:runFilePath c)
        (let [lines (str/split-lines (subs (str (:output res)) (+ 2 (str/index-of (str (:output res)) ": "))))]
          (is (= (count lines) (count fs*)) "run file count")
          (is (= lines (mapv :path fs*)) "RunResult.files path = MediaFile.path")
          (is (every? #(str/starts-with? (str (:path %)) "/") fs*) "absolute"))
        (is (not-any? #(contains? % :path) fs*) "no run file path")))))

(defn- check-no-usage [c out res]
  (when (:noUsage c)
    (when out (is (not (contains? (:ok out) :usage)) "no usage on the result"))
    (when res (is (not (contains? (:metadata res) :usage)) "no usage in tool metadata"))))

(defn- replay-http-case [c0 build client-name]
  ;; every check runs inside the face, before its {dir} is removed
  (http-face c0 build :fn
             (fn [{:keys [c dir out progress reqs]}]
               (check-requests c reqs)
               (when (contains? c :expectProgress)
                 (is (= (:expectProgress c) (mapv :status progress)))
                 (is (every? #(= (:verb c) (:verb %)) progress)))
               (check-outcome c out dir (client-name c))
               (check-no-usage c out nil)))
  (when (and (:tool c0) (not (:styleError c0)) (not (contains? c0 :ctxTimeoutMs)))
    (http-face c0 build :tool
               (fn [{:keys [c dir out]}]
                 (check-tool (:tool c) (:ok out) dir)
                 (check-run-files c (:ok out))
                 (check-no-usage c nil (:ok out))))))

(def ^:private cljgo-redirect-loop-gap
  "KNOWN GAP (cljgo only): Go's net/http follows redirects below koine.http, which
  offers no no-redirect switch. Its limit errors when the 10th redirect arrives,
  so a redirect loop makes 10 requests, not 11 (the error text is still mapped to
  §7G's `<verb>: too many redirects`). Asserted as-is, never skipped."
  "redirect_loop_too_many")

(deftest conformance-cases-replay
  (let [cases (:cases (fixture "cases.json"))]
    (is (= 49 (count cases)) "every fixture case is replayed")
    (doseq [c cases]
      (testing (:name c)
        (let [gap? (and (not= :jvm host/id) (= cljgo-redirect-loop-gap (:name c)))
              c    (if gap?
                     (do
                       ;; the dropped 11th hop has no request to check on cljgo;
                       ;; its two (method, path) assertions are replaced by two
                       ;; that pin the gap itself, so both hosts count the same
                       (is (= 11 (count (:expectRequests c))) "cljgo known gap: 11 expected")
                       (is (= {:method "GET" :path "/loop"} (last (:expectRequests c)))
                           "cljgo known gap: the 11th hop is the one Go does not make")
                       (update c :expectRequests (comp vec butlast)))
                     c)]
          (replay-http-case c
                            (fn [c cfg] (media/style-media-client (:style c) cfg))
                            (fn [c] (or (get-in c [:style :name]) "style"))))))))

(deftest conformance-clients-replay
  (let [cases (:cases (fixture "clients.json"))]
    (is (= 76 (count cases)) "every fixture case is replayed")
    (doseq [c cases]
      (testing (:name c)
        (replay-http-case c
                          (fn [c cfg] ((get builders (:client c)) cfg))
                          :client)))))

;; ---------------------------------------------------------------------------
;; CLI clients (cli.json) over conformance/media/fake-cli/fake-cli.sh
;; ---------------------------------------------------------------------------

(defn- cli-style
  "client grok|codex is the same as style xai-grok-cli|openai-codex-cli."
  [c]
  (or (:style c) ({"grok" media/xai-grok-cli "codex" media/openai-codex-cli} (:client c))))

(defn- style-label [style]
  (get {"xai-grok" "grok" "openai-codex" "codex" "google-agy" "agy"} (str/replace style #"-cli(-mock)?$" "")))

(defn- read-lines [p] (let [s (fs/read-file p)] (if (= "" s) [] (str/split-lines s))))

(defn- cli-face [c0 face]
  (let [out  (fs/temp-dir! "tn-cli-out")
        dir  (fs/temp-dir! "tn-cli-dir")
        fdir (fs/temp-dir! "tn-cli-fx")
        fx   (str fdir "/fixture.jsonl")]
    (try
      (fs/write-file fx (str (str/join "\n" (:fixture c0)) (when (seq (:fixture c0)) "\n")))
      (let [c        (subst c0 [["{out}" out] ["{dir}" dir]] #{:outputPattern :fixture})
            progress (atom [])
            command  (or (:command c)
                         ["/bin/sh" (fs/real-path (str (conformance-dir) "/fake-cli/fake-cli.sh")) fx out])
            cfg      (assoc (kebab-config (:config c)) :command command :env (env-lookup (:env c)))
            t0       (ktime/mono-ms)
            res      (attempt (fn []
                                (let [m (media/create-media (media/load-media-style (cli-style c) cfg))]
                                  (if (= face :fn)
                                    (media/generate m (:verb c) (:args c) (ctx-for c progress))
                                    (run-tool m (:verb c) (:args c))))))
            elapsed  (- (ktime/mono-ms) t0)]
        (when (= face :fn)
          (when (contains? c :maxElapsedMs)
            (is (< elapsed (:maxElapsedMs c)) "killed in time"))
          (when (contains? c :expectArgv)
            (let [cwd  (str/trim (fs/read-file (str out "/cwd.txt")))
                  argv (read-lines (str out "/argv.txt"))]
              (is (= (count (:expectArgv c)) (count argv)) "argv length")
              (doseq [[e a] (map vector (:expectArgv c) argv)]
                (if (= "{cwd}" e)
                  (is (= cwd a) "--cwd is the realpath of the run's working dir (D17)")
                  (is (= e a))))))
          (when-let [g (get-in c [:expectEnv :GROK_CONFIG])]
            (is (= g (fs/read-file (str out "/env-GROK_CONFIG.txt")))))
          (doseq [p (:expectExists c)] (is (fs/exists? p) (str "exists " p)))
          (doseq [p (:expectMissing c)] (is (not (fs/exists? p)) (str "missing " p)))
          (when (:expectNoSpawn c) (is (not (fs/exists? (str out "/argv.txt"))) "no spawn"))
          (when (contains? c :expectProgress)
            (is (= (:expectProgress c) (mapv :status @progress))))
          (check-outcome c res dir (style-label (cli-style c)))
          (check-no-usage c res nil)
          (when (:oneShot c)
            (let [one (attempt #(media/generate-media (cli-style c) (:verb c) (:args c) cfg))]
              (check-outcome c one dir (style-label (cli-style c))))))
        (when (= face :tool)
          (check-tool (:tool c) (:ok res) dir)
          (check-run-files c (:ok res))
          (check-no-usage c nil (:ok res))))
      (finally (fs/delete-tree! out) (fs/delete-tree! dir) (fs/delete-tree! fdir)))))

(deftest conformance-cli-replay
  (let [cases (:cases (fixture "cli.json"))]
    (is (= 35 (count cases)) "every fixture case is replayed")
    (doseq [c cases]
      (testing (:name c)
        (cli-face c :fn)
        (when (and (:tool c) (not (contains? c :ctxTimeoutMs))) (cli-face c :tool))))))

(deftest conformance-cli-availability
  (let [cases (:availabilityCases (fixture "cli.json"))]
    (is (= 7 (count cases)) "every availability case is replayed")
    (doseq [c0 cases]
      (testing (:name c0)
        (let [dir (fs/real-path (fs/temp-dir! "tn-avail"))]
          (try
            (let [c      (subst c0 [["{dir}" dir]] #{})
                  _      (doseq [p (:createFiles c)] (fs/write-file p ""))
                  envm   (:env c)
                  unset  (set (:unsetEnv c))
                  lookup (fn [k] (cond (contains? unset k) nil
                                       (contains? envm (keyword k)) (get envm (keyword k))
                                       :else (koine.env/get-env k)))
                  cfg    (assoc (kebab-config (:config c)) :env lookup)
                  s      (media/load-media-style (:style c) cfg)
                  t0     (ktime/mono-ms)
                  got    (media/available s)]
              (is (< (- (ktime/mono-ms) t0) 1000) "fast")
              (is (= (:expect c) got)))
            (finally (fs/delete-tree! dir))))))))

(deftest conformance-cli-verb-cases
  (let [cases (:verbCases (fixture "cli.json"))]
    (is (= 8 (count cases)) "every verb case is replayed")
    (doseq [c cases]
      (testing (:name c)
        (let [m (media/create-media
                 (mapv (fn [s] (if (string? s)
                                 (media/load-media-style s {:command ["/nonexistent/toolnexus-must-not-run"]})
                                 (media/load-media-style s)))
                       (:styles c)))]
          (is (= (:expectVerbs c) (media/supported-verbs m)))
          (is (= (:expectTools c) (mapv :name (media/tools m))))
          (is (= (:error c) (some-> (:error (attempt #(media/generate m (:unsupported c) {}))) ex-message))))))))

;; ---------------------------------------------------------------------------
;; routing (routing.json)
;; ---------------------------------------------------------------------------

(defn- custom-client [spec calls]
  (let [vs (into {} (map (fn [e] [(name (key e)) (val e)]) (:verbs spec)))]
    (merge
     (cond-> {:name     (:name spec)
              :supports (fn [verb] (contains? vs verb))}
       ;; args absent on every verb => the client declares no args() (D7)
       (some #(contains? % :args) (vals vs))
       (assoc :args (fn [verb]
                      (if (contains? (get vs verb) :args)
                        (:args (get vs verb))
                        (:args (first (filter #(= verb (:verb %)) media/verbs)))))))
     (into {} (map (fn [[verb k]]
                     [k (fn [args _]
                          (swap! calls conj {:client (:name spec) :verb verb :args args})
                          (let [o (:outcome (get vs verb))]
                            (if (contains? o :result)
                              (:result o)
                              (throw (media/media-error (:error o) (keyword (:status o)))))))])
                   {"image" :image "video" :video "speech" :speech "music" :music
                    "transcribe" :transcribe "cloneVoice" :clone-voice "talk" :talk})))))

(deftest conformance-routing-replay
  (let [fx    (fixture "routing.json")
        cases (:cases fx)]
    (is (= 19 (count cases)) "every fixture case is replayed")
    (doseq [c cases]
      (testing (:name c)
        (let [calls (atom [])
              out   (attempt #(media/generate (media/create-media (mapv (fn [s] (custom-client s calls)) (:clients c)))
                                              (:verb c) (:args c)))]
          (when (contains? c :expectCalls)
            (is (= (:expectCalls c) @calls)))
          (check-outcome c out nil "custom"))
        (when (:tool c)
          (let [calls (atom [])
                res   (run-tool (media/create-media (mapv (fn [s] (custom-client s calls)) (:clients c)))
                                (:verb c) (:args c))]
            (check-tool (:tool c) res nil)))))
    (doseq [sc (:schemaCases fx)]
      (testing (:name sc)
        (let [ts (media/tools (media/create-media (mapv (fn [s] (custom-client s (atom []))) (:clients sc))))]
          (is (= (mapv :name (:tools sc)) (mapv :name ts)) "order")
          (is (= (:tools sc)
                 (mapv (fn [t] {:name (:name t) :description (:description t) :source (:source t)
                                :inputSchema (:input-schema t)})
                       ts))))))
    (is (= (get-in fx [:emptyClients :error])
           (some-> (:error (attempt #(media/create-media []))) ex-message)))))

;; ---------------------------------------------------------------------------
;; embedded copies, schemas, toolkit, RunResult.files, model visibility
;; ---------------------------------------------------------------------------

(deftest tool-schemas-match-fixture
  (let [fx (fixture "schemas.json")
        ts (media/tools (media/create-media (media/style-media-client (:style fx))))]
    (is (= (mapv :name (:tools fx)) (mapv :name ts)) "order")
    (is (= (:tools fx)
           ;; fixture spells it inputSchema; the port keys it :input-schema
           (mapv (fn [t] {:name (:name t) :description (:description t) :source (:source t)
                          :inputSchema (:input-schema t)})
                 ts)))))

(deftest media-version-matches-build
  ;; D23 UA carries the package version: pinned to build.clj's (def version …)
  (let [b (fs/read-file (str (te/examples-dir) "/../clojure/build.clj"))]
    (is (str/includes? b (str "(def version \"" media/version "\")")))
    (is (= (str "toolnexus/" media/version " (clojure)") media/user-agent))))

(deftest verb-table-is-verbs-json-verbatim
  (is (= (:verbs (fixture "verbs.json")) media/verbs)))

(deftest a2e-preset-is-embedded-verbatim
  (is (= (fs/read-file (str (te/examples-dir) "/media-styles/a2e.json")) media/a2e-style-json)))

(defn- sync-style [base verbs]
  {:baseUrl base
   :verbs (into {} (map (fn [[v kind]] [v {:mode "sync" :kind kind :start {:path (str "/" (name v))}
                                           :args (if (= "text" kind) {:audio "a"} {:prompt "p"})
                                           :resultPath "u"}])
                        verbs))})

(deftest provider-options-overwrite-last-and-dotpath-arrays
  (with-fake-server [{:status 200 :body {:u "https://x/a.png"}}]
    (fn [base reqs]
      (let [m (media/create-media
               (media/style-media-client {:baseUrl base
                                          :verbs {:image {:mode "sync" :kind "image" :start {:path "/s"}
                                                          :defaults {:model "a" :keep 1}
                                                          :args {:prompt "input.0.text"} :resultPath "u"}}}))
            r (media/image m {:prompt "cat"} {:provider-options {:model "b"}})]
        (is (= "completed" (:status r)))
        (is (= {:model "b" :keep 1 :input [{:text "cat"}]}
               (json/read-str (:body (first @reqs)))))))))

(deftest cancellation-stops-the-poll
  (with-fake-server [{:status 200 :body {:id "j"}} {:status 200 :body {:st "processing"}}]
    (fn [base _]
      (let [n (atom 0)
            m (media/create-media
               (media/style-media-client
                {:baseUrl base
                 :verbs {:video {:mode "async" :kind "video" :start {:path "/s"} :args {:prompt "p"}
                                 :idPath "id" :resultPath "u"
                                 :poll {:path "/p/{id}" :statusPath "st" :done ["ok"] :failed ["x"]
                                        :intervalMs 1 :timeoutMs 60000}}}}))
            e (try (media/video m {:prompt "x"} {:progress (fn [_] (swap! n inc))
                                                 :cancel (fn [] (when (>= @n 2) "stop"))})
                   nil (catch Throwable e e))]
        (is (= :cancelled (:toolnexus/error (ex-data e))))
        (is (= :cancelled (:status (ex-data e))))))))

(deftest cli-cancellation-kills-the-process
  (let [out  (fs/temp-dir! "tn-cli-out")
        fdir (fs/temp-dir! "tn-cli-fx")
        fx   (str fdir "/fixture.jsonl")]
    (try
      (fs/write-file fx "{\"type\":\"text\",\"data\":\"thinking\"}\n#hang\n")
      (let [m  (media/create-media
                (media/grok-media-client
                 {:command ["/bin/sh" (fs/real-path (str (conformance-dir) "/fake-cli/fake-cli.sh")) fx out]}))
            t0 (ktime/mono-ms)
            e  (try (media/image m {:prompt "x"} {:cancel (fn [] (when (> (- (ktime/mono-ms) t0) 200) "stop"))})
                    nil (catch Throwable e e))]
        (is (= :cancelled (:status (ex-data e))))
        (is (< (- (ktime/mono-ms) t0) 5000)))
      (finally (fs/delete-tree! out) (fs/delete-tree! fdir)))))

(deftest toolkit-option-registers-media-tools
  (let [m  (media/create-media (media/style-media-client (:style (fixture "schemas.json"))))
        tk (toolnexus/build {:builtins false :media m})]
    (is (= ["clone_voice" "generate_image" "generate_speech" "talking_video"] (tool/tool-names tk)))
    (is (every? #(= "media" (:source %)) (tool/all-tools tk)))))

(deftest run-result-collects-files-and-only-images-reach-the-model
  (with-fake-server [{:status 200 :body {:u ["https://x/a.png" "https://x/b.webp"]}}]
    (fn [base _]
      (let [m  (media/create-media
                [(media/style-media-client (sync-style base {:image "image"}))
                 {:name "vid"
                  :supports #(= "video" %)
                  :video (fn [_ _] {:files [{:type "file" :mimeType "video/mp4" :url "https://x/v.mp4"}]
                                    :status "completed"})}])
            tk (toolnexus/build {:builtins false :media m})
            n  (atom 0)
            seen (atom nil)
            c  (client/create-in-process-client
                {:model "m"
                 :generate (fn [req]
                             (case (swap! n inc)
                               1 {:tool-calls [{:id "call_img" :name "generate_image" :arguments {:prompt "cat"}}
                                               {:id "call_vid" :name "generate_video" :arguments {:prompt "waves"}}]}
                               (do (reset! seen (:messages req)) {:content "done"})))})
            r  (client/run c "draw" {:toolkit tk})
            img [{:type "image" :mimeType "image/png" :url "https://x/a.png"}
                 {:type "image" :mimeType "image/webp" :url "https://x/b.webp"}]
            vid [{:type "file" :mimeType "video/mp4" :url "https://x/v.mp4"}]]
        (is (= "done" (:text r)))
        (is (= img (:parts (first (:tool-calls r)))))
        (is (= vid (:parts (second (:tool-calls r)))) "video parts stay on toolCalls[].parts")
        (is (= (into (mapv #(assoc % :from "generate_image" :callId "call_img") img)
                     (mapv #(assoc % :from "generate_video" :callId "call_vid") vid))
               (:files r)))
        (let [tool-msgs (filter #(= "tool" (:role %)) @seen)
              parts     (mapcat :parts tool-msgs)]
          (is (= 2 (count tool-msgs)))
          (is (not-any? #(= "file" (:type %)) parts) "video parts never reach the model"))
        (testing "a run without media is unchanged: files []"
          (let [r2 (client/run (client/create-in-process-client
                                {:model "m" :generate (fn [_] {:content "hi"})})
                               "x" {:toolkit (toolnexus/build {:builtins false})})]
            (is (= [] (:files r2)))))))))

;; Media tools are concurrency-safe (SPEC §7G.6, §8 Tool scheduling).
(deftest media-tools-are-concurrency-safe
  (let [ts (media/tools (media/create-media
                         (media/style-media-client
                          (sync-style "http://unused" {:image "image" :video "video" :speech "audio"
                                                       :music "audio" :transcribe "text" :talk "video"}))))]
    (is (= 6 (count ts)))
    (doseq [t ts]
      (is (true? (tool/concurrency-safe? t {})) (:name t)))))

;; SPEC §7G.8 Redirects: style headers never reach a different origin. The JVM
;; port follows redirects itself (java.net.http never does), so a cross-origin
;; hop is re-issued without them.
;;
;; KNOWN GAP (cljgo only): Go's net/http follows redirects below koine.http and
;; koine offers no no-redirect switch, so the engine never sees the hop. Go keeps
;; custom headers on every hop and treats a different PORT on the same host as
;; the same host, so here BOTH style headers reach origin b. The gap is asserted
;; as it is (same number of assertions on both hosts), never skipped — if koine
;; grows a switch and the gap closes, these assertions fail and say so.
(deftest cross-origin-redirect-drops-style-headers
  (let [seen-b (atom [])
        seen-a (atom [])
        b      (ts/serve (fn [req]
                           (swap! seen-b conj (lower-keys (:headers req)))
                           {:status 200 :headers {"content-type" "application/json"}
                            :body "{\"u\":\"https://x/a.png\"}"}))
        b-url  (str "http://127.0.0.1:" (server/port b))
        a      (ts/serve (fn [req]
                           (swap! seen-a conj (:path req))
                           (if (= "/s" (:path req))
                             {:status 302 :headers {"location" "/same"} :body ""}
                             {:status 307 :headers {"location" (str b-url "/other")} :body ""})))]
    (try
      (let [m (media/create-media
               (media/style-media-client {:baseUrl (str "http://127.0.0.1:" (server/port a))
                                          :headers {:Authorization "Bearer k" :X-Key "secret"}
                                          :verbs {:image {:mode "sync" :kind "image" :start {:path "/s"}
                                                          :args {:prompt "p"} :resultPath "u"}}}))
            r (media/image m {:prompt "cat"})]
        (is (= "https://x/a.png" (:url (first (:files r)))))
        (is (= ["/s" "/same"] @seen-a) "same-origin redirect followed")
        (is (= 1 (count @seen-b)))
        (if (= :jvm host/id)
          (do (is (nil? (get (first @seen-b) "authorization")))
              (is (nil? (get (first @seen-b) "x-key"))))
          (do (is (= "Bearer k" (get (first @seen-b) "authorization")) "cljgo known gap: forwarded cross-origin")
              (is (= "secret" (get (first @seen-b) "x-key")) "cljgo known gap: forwarded cross-origin"))))
      (finally (server/stop! a) (server/stop! b)))))

;; SPEC §7G.8 Per-request timeout: a hung server is bounded by the time remaining.
(deftest hung-server-bounded-by-remaining-deadline
  (let [srv (ts/serve (fn [_] (ktime/sleep! 3000)
                        {:status 200 :headers {"content-type" "application/json"} :body "{}"}))]
    (try
      (let [base (str "http://127.0.0.1:" (server/port srv))
            m    (media/create-media
                  (media/style-media-client {:baseUrl base
                                             :verbs {:image {:mode "sync" :kind "image" :start {:path "/s"}
                                                             :args {:prompt "p"} :resultPath "u"
                                                             :poll {:timeoutMs 1000}}}}))
            t0   (ktime/mono-ms)
            e    (try (media/image m {:prompt "x"}) nil (catch Throwable e e))]
        (is (= "image: timed out after 1000ms" (ex-message e)))
        (is (= :timeout (:status (ex-data e))))
        (is (< (- (ktime/mono-ms) t0) 2800)))
      (finally (server/stop! srv)))))

;; SPEC §7G.12 D9: a media tool result with no image part (modelParts []) sends
;; the plain output string as the tool-result content in EVERY message style
;; this port has (openai, anthropic; no Gemini client style in the Clojure port)
;; — never an empty content array, never a placeholder. Asserted on the wire.
(deftest d9-no-image-parts-sends-plain-output-in-every-style
  (doseq [style ["openai" "anthropic"]]
    (testing style
      (let [n    (atom 0)
            reqs (atom [])
            srv  (ts/serve
                  (fn [req]
                    (swap! reqs conj (json/read-str (str (:body req))))
                    {:status 200 :headers {"content-type" "application/json"}
                     :body (json/write-str
                            (if (= 1 (swap! n inc))
                              (if (= "openai" style)
                                {:choices [{:message {:role "assistant" :content nil
                                                      :tool_calls [{:id "cv" :type "function"
                                                                    :function {:name "generate_video"
                                                                               :arguments "{\"prompt\":\"waves\"}"}}]}}]}
                                {:content [{:type "tool_use" :id "cv" :name "generate_video" :input {:prompt "waves"}}]})
                              (if (= "openai" style)
                                {:choices [{:message {:role "assistant" :content "done"}}]}
                                {:content [{:type "text" :text "done"}]})))}))]
        (try
          (let [m   (media/create-media
                     [{:name "vid" :supports #(= "video" %)
                       :video (fn [_ _] {:files [{:type "file" :mimeType "video/mp4" :url "https://x/v.mp4"}]
                                         :status "completed"})}])
                c   (client/create-client {:base-url (str "http://127.0.0.1:" (server/port srv))
                                           :style style :model "m" :api-key "k"})
                r   (client/run c "go" {:toolkit (toolnexus/build {:builtins false :media m})})
                out "video ready: https://x/v.mp4"
                sent (:messages (second @reqs))]
            (is (= "done" (:text r)))
            (is (= out (:output (first (:tool-calls r)))))
            (if (= "openai" style)
              (let [tms (filter #(= "tool" (:role %)) sent)]
                (is (= [{:role "tool" :tool_call_id "cv" :content out}] (vec tms)))
                (is (= 1 (count (filter #(= "user" (:role %)) sent))) "no synthetic user message"))
              (let [trs (->> sent (filter #(= "user" (:role %))) (mapcat (fn [m] (if (sequential? (:content m)) (:content m) [])))
                             (filter #(= "tool_result" (:type %))))]
                (is (= 1 (count trs)))
                (is (= out (:content (first trs))) "plain string, not an empty array"))))
          (finally (server/stop! srv)))))))
