(ns toolnexus.mcp2026-fixture-test
  "Test helper (no tests; the _test suffix keeps it out of the jar and the
  examples/src mirror). Hermetic MCP 2026-07-28 peers for the suite, ported from
  the spike (spikes/mcp-2026/clojure + golang) onto koine so they run on both
  hosts:

    * `start-server`   — the shared fixture server (examples/mcp-2026/fixture.json:
                         echo + greet with MRTR, skill hello-world), stateless
                         Streamable HTTP, optional OAuth protection (PRM, 401/403)
    * `start-as`       — a DEV authorization server (auto-approves as dev-user;
                         PKCE S256, resource, iss, refresh rotation). TEST ONLY.
    * `stdio-script`   — the same protocol as a `node -e` stdio server, in three
                         modes: 2026, legacy (-32601 on discover) and silent
                         (ignores discover, so the probe times out)

  koine.server never hands a handler the query string, so the dev AS takes the
  authorize parameters as a POST body: `approve!` is the headless browser that
  does that and returns the callback URL the AS redirected to."
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as ktime]
            [toolnexus.mcp-auth :as auth]
            [toolnexus.mcp2026 :as m26]
            [toolnexus.shared-examples-test :refer [examples-dir]]
            [toolnexus.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; the dev authorization server
;; ---------------------------------------------------------------------------

(defn- js [status v] {:status status :headers {"content-type" "application/json"} :body (json/write-str v)})

(defn- as-authorize [as body]
  (let [q (auth/parse-query body)
        g (fn [k] (get q k))
        cfg @(:cfg as)
        _ (swap! (:stats as) (fn [s] (-> s (update :authorizations inc) (assoc :last-authorize-query q))))
        cid (g "client_id") ru (g "redirect_uri")
        ;; 200 + the callback URL as the body, not a 302: cljgo's HTTP client
        ;; follows redirects and the JVM's does not, so a 302 is not portable
        redirect (fn [m] {:status 200 :headers {"content-type" "text/plain"} :body (str ru "?" (auth/form-encode m))})]
    (cond
      (not (or (contains? (:clients cfg) cid) (str/starts-with? (str cid) "https://")))
      {:status 400 :body "invalid_client"}
      (or (not= "code" (g "response_type")) (not= "S256" (g "code_challenge_method")) (str/blank? (g "code_challenge")))
      (redirect {"error" "invalid_request" "state" (g "state") "iss" (:issuer as)})
      :else
      (let [code (auth/rand-str 16)]
        (swap! (:codes as) assoc code {:client-id cid :redirect ru :challenge (g "code_challenge")
                                       :resource (g "resource") :scope (g "scope")})
        (redirect (cond-> {"code" code "state" (if (:bad-state cfg) "forged" (g "state"))}
                    (not (:omit-iss cfg)) (assoc "iss" (or (:iss-override cfg) (:issuer as)))))))))

(defn- issue [as c]
  (let [cfg @(:cfg as)
        ttl (or (:access-ttl-s cfg) 3600)
        at  (str "at-" (auth/rand-str 24))
        rt  (str "rt-" (auth/rand-str 24))]
    (swap! (:tokens as) assoc at {:aud (:resource c) :scope (:scope c) :sub "dev-user"
                                  :exp (+ (ktime/now-ms) (* 1000 ttl))})
    (swap! (:rts as) assoc rt c)
    (let [body {"access_token" at "token_type" "Bearer" "expires_in" ttl "refresh_token" rt "scope" (:scope c)}]
      (if (:form-token cfg)
        {:status 200 :headers {"content-type" "application/x-www-form-urlencoded"} :body (auth/form-encode body)}
        (js 200 body)))))

(defn- as-token [as body]
  (let [cfg  @(:cfg as)
        form (auth/parse-query body)
        g    (fn [k] (get form k))
        _    (swap! (:stats as) assoc :last-token-form (dissoc form "code" "code_verifier" "refresh_token"))
        c (case (g "grant_type")
            "authorization_code"
            (let [c (get @(:codes as) (g "code"))]
              (swap! (:codes as) dissoc (g "code"))
              (cond (nil? c) "invalid_grant"
                    (not= (auth/b64url (auth/sha256 (str (g "code_verifier")))) (:challenge c)) "invalid_grant"
                    (or (not= (g "client_id") (:client-id c)) (not= (g "redirect_uri") (:redirect c))
                        (not= (g "resource") (:resource c))) "invalid_grant"
                    :else c))
            "refresh_token"
            (let [c (get @(:rts as) (g "refresh_token"))]
              (cond (nil? c) "invalid_grant"
                    :else (do (swap! (:rts as) dissoc (g "refresh_token"))
                              (swap! (:stats as) update :refreshes inc)
                              (if (and (seq (g "resource")) (:reject-refresh-resource cfg))
                                "invalid_target"
                                c))))
            "unsupported_grant_type")]
    (if (string? c) (js 400 {"error" c}) (issue as c))))

(defn start-as
  "A DEV authorization server, issuer http://127.0.0.1:<port>/dev. :cfg knobs:
  :clients #{ids} :access-ttl-s :no-s256 :no-cimd :iss-override :omit-iss
  :bad-state :reject-refresh-resource :form-token."
  []
  (let [self (atom nil)
        srv  (ts/serve (fn [req] (let [as @self
                                       iss (:issuer as)
                                       cfg @(:cfg as)]
                                   (case (:path req)
                                     "/.well-known/oauth-authorization-server/dev"
                                     (js 200 {"issuer" iss "authorization_endpoint" (str iss "/authorize")
                                              "token_endpoint" (str iss "/token")
                                              "response_types_supported" ["code"]
                                              "code_challenge_methods_supported" (if (:no-s256 cfg) ["plain"] ["S256"])
                                              "authorization_response_iss_parameter_supported" true
                                              "client_id_metadata_document_supported" (not (:no-cimd cfg))})
                                     "/dev/authorize" (as-authorize as (:body req))
                                     "/dev/token" (as-token as (:body req))
                                     {:status 404 :body "not found"}))))
        base (str "http://127.0.0.1:" (server/port srv))
        as   {:issuer (str base "/dev") :server srv
              :cfg (atom {:clients #{"test-client"}})
              :stats (atom {:authorizations 0 :refreshes 0})
              :codes (atom {}) :rts (atom {}) :tokens (atom {})}]
    (reset! self as)
    as))

(defn approve!
  "The headless browser: visit an authorization URL (its query as a POST body,
  see the ns doc) and return the callback URL the AS redirected to."
  [as url]
  (let [{:keys [query]} (auth/parse-url url)
        r (http/request {:method :post :url (str (:issuer as) "/authorize") :body query :timeout-ms 10000
                         :headers {"content-type" "application/x-www-form-urlencoded"}})]
    (when-not (and (= 200 (:status r)) (str/starts-with? (str (:body r)) "http"))
      (throw (ex-info (str "authorize: HTTP " (:status r)) {})))
    (:body r)))

(defn open-url-hook
  "An `open-url` for auto mode: approve, then deliver the callback to the
  loopback the way the callback page's script does (POST of the query)."
  [as]
  (fn [url]
    (let [cb (approve! as url)
          {:keys [scheme host path query]} (auth/parse-url cb)]
      (http/request {:method :post :url (str scheme "://" host path) :body query :timeout-ms 10000})
      nil)))

;; ---------------------------------------------------------------------------
;; the fixture server
;; ---------------------------------------------------------------------------

(defn- load-skills [root]
  (vec (for [skill-md (sort (filter #(str/ends-with? % "/SKILL.md") (fs/list-tree root)))
             :let [dir  (subs skill-md 0 (- (count skill-md) 9))
                   nm   (subs dir (inc (str/last-index-of dir "/")))
                   files (sort (filter (fn [p] (not (fs/directory? p))) (fs/list-tree dir)))]]
         {:uri (str "skill://" nm "/SKILL.md") :name nm
          :frontmatter {"name" nm}
          :files (vec (for [f files]
                        (let [t (fs/read-file f)]
                          {:uri (str "skill://" nm "/" (subs f (inc (count dir)))) :text t})))})))

(defn fixture
  "The shared fixture, read from examples/mcp-2026/fixture.json."
  []
  (let [ex (examples-dir)
        fx (json/read-str (fs/read-file (str ex "/mcp-2026/fixture.json")) {:key-fn str})]
    {:info (get fx "server") :tools (get fx "tools")
     :skills (load-skills (str ex "/skills"))}))

(defn- skill-entry [sk]
  {"uri" (:uri sk) "frontmatter" (:frontmatter sk)
   "resources" (vec (for [f (:files sk)]
                      {"uri" (:uri f) "digest" (str "sha256:" (auth/hex (auth/sha256 (:text f))))
                       "size" (count (auth/utf8-vals (:text f)))}))})

(defn- err [code msg & [data]] [nil (cond-> {"code" code "message" msg} data (assoc "data" data))])

(defn- call-tool [fx state p]
  (let [nm   (get p "name")
        args (let [a (get p "arguments")] (if (map? a) a {}))
        si   {m26/meta-server-info (:info fx)}
        done (fn [t structured]
               (swap! state update :executions inc)
               [{"resultType" "complete" "content" [{"type" "text" "text" t}] "isError" false
                 "structuredContent" structured "_meta" si} nil])]
    (case nm
      "echo"  (let [t (str (get args "text" ""))]
                (when-let [ms (:echo-delay-ms @state)] (ktime/sleep! ms))
                (done t {"text" t}))
      "greet" (let [nr (get-in p ["inputResponses" "name"])
                    n  (when (and (map? nr) (= "accept" (get nr "action"))) (get-in nr ["content" "name"]))]
                (if (and (string? n) (seq n))
                  (done (str "Hello, " n "!") {"greeting" (str "Hello, " n "!")})
                  (do (swap! state update :asked inc)
                      [{"resultType" "input_required" "_meta" si
                        "requestState" (str "rs-" (get p "requestState" "0"))
                        "inputRequests" {"name" {"method" "elicitation/create"
                                                 "params" {"mode" "form" "message" "What is your name?"
                                                           "requestedSchema" {"type" "object"
                                                                              "properties" {"name" {"type" "string"}}
                                                                              "required" ["name"]}}}}} nil])))
      "whoami" [{"resultType" "complete" "content" [{"type" "text" "text" (str (:subject @state))}] "isError" false} nil]
      (err m26/code-invalid-params (str "Unknown tool: " nm)))))

(defn dispatch [fx state method p]
  (let [si {m26/meta-server-info (:info fx)}
        bad-uri (fn [uri what] (try (m26/parse-skill-uri uri) nil
                                    (catch Throwable e (err m26/code-invalid-params (str "Invalid " what " URI: " (ex-message e))))))]
    (case method
      "server/discover" [{"resultType" "complete" "supportedVersions" [m26/protocol-version]
                          "capabilities" {"tools" {} "resources" {} "extensions" {m26/skills-ext {}}}
                          "_meta" si "ttlMs" 3600000 "cacheScope" "public"} nil]
      "tools/list" [{"resultType" "complete" "tools" (:tools fx) "ttlMs" 60000 "cacheScope" "public" "_meta" si} nil]
      "tools/call" (call-tool fx state p)
      "skills/list" [{"resultType" "complete" "skills" (mapv (fn [s] {"uri" (:uri s) "frontmatter" (:frontmatter s)}) (:skills fx))
                      "ttlMs" 60000 "cacheScope" "public"} nil]
      "skills/get" (let [uri (str (get p "uri"))]
                     (or (bad-uri uri "skill")
                         (if-let [sk (first (filter #(= uri (:uri %)) (:skills fx)))]
                           [{"resultType" "complete" "skill" (skill-entry sk)} nil]
                           (err m26/code-invalid-params (str "Unknown skill: " uri)))))
      "resources/read" (let [uri (str (get p "uri"))]
                         (or (bad-uri uri "resource")
                             (if-let [f (first (filter #(= uri (:uri %)) (mapcat :files (:skills fx))))]
                               [{"resultType" "complete"
                                 "contents" [{"uri" uri "mimeType" (if (str/ends-with? uri ".md") "text/markdown" "text/plain")
                                              "text" (:text f)}]} nil]
                               (err m26/code-invalid-params (str "Resource not found: " uri)))))
      (err m26/code-method-not-found (str "Method not found: " method)))))

(defn- decode-header [v]
  (if (and (str/starts-with? v "=?base64?") (str/ends-with? v "?="))
    (try (auth/vals->utf8 (vec (koine.codec/b64-decode-vals (subs v 9 (- (count v) 2))))) (catch Throwable _ nil))
    v))

(defn- validate [req method p]
  (let [h  (:headers req)
        hv (get h "mcp-protocol-version" "")
        mv (str (get-in p ["_meta" m26/meta-version]))
        mismatch (fn [m] [400 {"code" m26/code-header-mismatch "message" (str "Header mismatch: " m)}])]
    (cond
      (= hv "") (mismatch "MCP-Protocol-Version header missing")
      (not= (get h "mcp-method") method) (mismatch "Mcp-Method")
      (and (#{"tools/call" "resources/read" "prompts/get"} method)
           (not= (decode-header (get h "mcp-name" ""))
                 (str (get p (if (= method "resources/read") "uri" "name")))))
      (mismatch "Mcp-Name")
      (not= mv hv) (mismatch "_meta")
      (not= mv m26/protocol-version)
      [400 {"code" m26/code-unsupported-protocol "message" "Unsupported protocol version"
            "data" {"supported" [m26/protocol-version] "requested" mv}}]
      :else nil)))

(defn- rpc-response [status id result e]
  {:status status :headers {"content-type" "application/json"}
   :body (json/write-str (cond-> {"jsonrpc" "2.0" "id" id} e (assoc "error" e) (not e) (assoc "result" result)))})

(defn- www [base & kvs]
  (str "Bearer resource_metadata=\"" base "/.well-known/oauth-protected-resource/mcp\""
       (apply str (map (fn [[k v]] (str ", " k "=\"" v "\"")) (partition 2 kvs)))))

(defn- check-auth
  "nil = allowed; else the 401/403 response."
  [as base state req method p]
  (let [authz (str (get-in req [:headers "authorization"]))
        tok   (when (str/starts-with? authz "Bearer ") (subs authz 7))
        info  (when tok (get @(:tokens as) tok))
        need  (if (and (= method "tools/call") (= "greet" (get p "name"))) ["mcp:read" "mcp:greet"] ["mcp:read"])
        have  (set (str/split (str (:scope info)) #"\s+"))]
    (cond
      (nil? tok) {:status 401 :headers {"www-authenticate" (www base "scope" "mcp:read")}
                  :body (json/write-str {"error" "unauthorized"})}
      (or (nil? info) (not= (:aud info) (str base "/mcp")) (> (ktime/now-ms) (:exp info)))
      {:status 401 :headers {"www-authenticate" (www base "error" "invalid_token")}}
      (not (every? have need))
      {:status 403 :headers {"www-authenticate" (www base "error" "insufficient_scope" "scope" (str/join " " need))}}
      :else (do (swap! state assoc :subject (:sub info)) nil))))

(defn start-server
  "The fixture server on 127.0.0.1. opts: :as (protect it with that dev AS).
  Returns {:url :state :stop}. `state` counts :executions, :asked, :requests
  ([method headers-of-interest]) and takes :fail-after-exec (an HTTP status to
  return AFTER the tool ran) and :echo-delay-ms."
  ([] (start-server {}))
  ([{:keys [as]}]
   (let [fx    (cond-> (fixture)
                 as (update :tools conj {"name" "whoami" "description" "Who is calling"
                                         "inputSchema" {"type" "object"}}))
         state (atom {:executions 0 :asked 0 :requests []})
         base  (atom nil)
         srv   (ts/serve
                (fn [req]
                  (cond
                    (and as (= "/.well-known/oauth-protected-resource/mcp" (:path req)))
                    (js 200 {"resource" (str @base "/mcp") "authorization_servers" [(:issuer as)]
                             "scopes_supported" ["mcp:read" "mcp:greet"] "bearer_methods_supported" ["header"]})
                    (not= "/mcp" (:path req)) {:status 404 :body "404 page not found"}
                    (not= :post (:method req)) {:status 405}
                    :else
                    (let [msg (try (json/read-str (:body req) {:key-fn str}) (catch Throwable _ nil))]
                      (if-not (map? msg)
                        (rpc-response 400 nil nil {"code" -32700 "message" "Parse error"})
                        (let [id (get msg "id") method (str (get msg "method"))
                              p  (let [p (get msg "params")] (if (map? p) p {}))]
                          (swap! state update :requests conj
                                 {:method method :headers (select-keys (:headers req) ["mcp-protocol-version" "mcp-method" "mcp-name"])
                                  :meta (get p "_meta") :params (dissoc p "_meta")
                                  :bearer? (some? (get-in req [:headers "authorization"]))})
                          (if-let [[status e] (validate req method p)]
                            (rpc-response status id nil e)
                            (or (when as (check-auth as @base state req method p))
                                (let [[res e] (dispatch fx state method p)]
                                  (if-let [s (and (= method "tools/call") (:fail-after-exec @state))]
                                    {:status s :body "upstream exploded"}
                                    (if (:sse @state)
                                      {:status 200 :headers {"content-type" "text/event-stream"}
                                       :body (str ": keepalive\n\ndata: "
                                                  (json/write-str (cond-> {"jsonrpc" "2.0" "id" id}
                                                                    e (assoc "error" e) (not e) (assoc "result" res)))
                                                  "\n\n")}
                                      (rpc-response (if (and e (= (get e "code") m26/code-method-not-found)) 404 200)
                                                    id res e))))))))))))]
     (reset! base (str "http://127.0.0.1:" (server/port srv)))
     {:url (str @base "/mcp") :state state :stop (fn [] (server/stop! srv))})))

(defn methods-seen [srv] (mapv :method (:requests @(:state srv))))

;; ---------------------------------------------------------------------------
;; an OLD-ERA (pre-2026) HTTP server — for the fallback
;; ---------------------------------------------------------------------------

(defn start-legacy-server
  "Answers server/discover with -32601 (as pre-2026 servers do before
  initialize), then speaks the frozen protocol. `discover-status` overrides the
  HTTP status of the discover reply (e.g. 404, 405)."
  ([] (start-legacy-server {}))
  ([{:keys [discover-status]}]
   (let [seen (atom [])
         srv  (ts/serve
               (fn [req]
                 (let [msg (try (json/read-str (:body req) {:key-fn str}) (catch Throwable _ {}))
                       method (get msg "method") id (get msg "id")]
                   (swap! seen conj {:method method :authorization (some? (get-in req [:headers "authorization"]))})
                   (cond
                     (nil? id) {:status 202}
                     (= method "server/discover")
                     (if discover-status
                       {:status discover-status :body "nope"}
                       (rpc-response 200 id nil {"code" -32601 "message" "Method not found"}))
                     (= method "initialize")
                     (rpc-response 200 id {"protocolVersion" "2024-11-05" "capabilities" {"tools" {}}
                                           "serverInfo" {"name" "old-era" "version" "1"}} nil)
                     (= method "tools/list")
                     (rpc-response 200 id {"tools" [{"name" "hello" "description" "Say hello"
                                                     "inputSchema" {"type" "object"}}]} nil)
                     (= method "tools/call")
                     (rpc-response 200 id {"content" [{"type" "text" "text" "hello from the old era"}]} nil)
                     :else (rpc-response 200 id nil {"code" -32601 "message" "Method not found"})))))]
     {:url (str "http://127.0.0.1:" (server/port srv) "/mcp") :seen seen
      :stop (fn [] (server/stop! srv))})))

;; ---------------------------------------------------------------------------
;; stdio — the same protocol from `node -e`
;; ---------------------------------------------------------------------------

(def stdio-script
  "argv[1]: \"2026\" | \"legacy\" (discover -> -32601) | \"silent\" (discover ignored)."
  "
const mode = process.argv[1] || '2026';
const rl = require('readline').createInterface({ input: process.stdin });
const send = (m) => process.stdout.write(JSON.stringify(m) + '\\n');
const tools = [{name:'echo',description:'Echo the given text back.',inputSchema:{type:'object',properties:{text:{type:'string'}},required:['text']}},
               {name:'greet',description:'Greet the user; asks for their name via input_required.',inputSchema:{type:'object',properties:{}}}];
process.stderr.write('fixture stdio server up (' + mode + ')\\n');
rl.on('line', (line) => {
  let m; try { m = JSON.parse(line); } catch (e) { return; }
  if (m.id === undefined || m.id === null) return;
  const p = m.params || {};
  const ok = (result) => send({jsonrpc:'2.0', id:m.id, result});
  const no = (code, message) => send({jsonrpc:'2.0', id:m.id, error:{code, message}});
  if (m.method === 'server/discover') {
    if (mode === 'silent') return;
    if (mode === 'legacy') return no(-32601, 'Method not found');
    if ((p._meta || {})['io.modelcontextprotocol/protocolVersion'] !== '2026-07-28') return no(-32022, 'Unsupported protocol version');
    return ok({resultType:'complete', supportedVersions:['2026-07-28'], capabilities:{tools:{}},
               _meta:{'io.modelcontextprotocol/serverInfo':{name:'stdio-fixture',version:'1'}}});
  }
  if (m.method === 'initialize') return ok({protocolVersion:'2024-11-05', capabilities:{tools:{}}, serverInfo:{name:'stdio-legacy',version:'1'}});
  if (m.method === 'tools/list') return ok({tools});
  if (m.method === 'tools/call') {
    const a = p.arguments || {};
    if (p.name === 'echo') return ok({content:[{type:'text',text:String(a.text)}], structuredContent:{text:String(a.text)}});
    if (p.name === 'greet') {
      const r = (p.inputResponses || {}).name;
      if (r && r.action === 'accept' && r.content && r.content.name)
        return ok({content:[{type:'text',text:'Hello, ' + r.content.name + '!'}]});
      return ok({resultType:'input_required', requestState:'s1', inputRequests:{name:{method:'elicitation/create',
        params:{mode:'form',message:'What is your name?',requestedSchema:{type:'object',properties:{name:{type:'string'}}}}}}});
    }
    return no(-32602, 'Unknown tool: ' + p.name);
  }
  no(-32601, 'Method not found');
});
")

(defn stdio-command [mode] ["node" "-e" stdio-script mode])
