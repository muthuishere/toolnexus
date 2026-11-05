(ns mcp2026.checks
  "The 8 cross-port conformance checks, runnable against any port's server; and the CLI."
  (:require [mcp2026.core :as mcp]))

(defn run-checks
  "Run the 8 checks against url, printing PASS/FAIL/SKIP lines; true when none failed.
  mk (optional) builds each client: (fn [] client) — e.g. a stdio or OAuth-configured client."
  ([url] (run-checks url (fn [] (mcp/client url))))
  ([_url mk]
   (let [ok (atom true)
         step (fn [name f]
                (let [r (try (f) (catch Exception e e))]
                  (cond (= r ::skip) (println (str "SKIP " name " (not applicable over stdio)"))
                        (instance? Exception r) (do (reset! ok false) (println (str "FAIL " name ": " (.getMessage ^Exception r))))
                        :else (println (str "PASS " name)))))
         fail #(throw (ex-info (apply str %&) {}))
         expect-code (fn [f code]
                       (let [e (try (f) nil (catch Exception e e))]
                         (when-not (= code (some-> e mcp/rpc-code))
                           (fail "want error " code ", got " (if e (.getMessage ^Exception e) "success")))))
         c (mk)
         uri "skill://hello-world/SKILL.md"]
     (try
       (step "server/discover"
             #(let [r (mcp/call c "server/discover")]
                (when-not (contains? (get-in r ["capabilities" "extensions"]) mcp/skills-ext)
                  (fail "skills extension not advertised: " (get r "capabilities")))
                (when-not (some #{mcp/protocol-version} (get r "supportedVersions"))
                  (fail "supportedVersions lacks " mcp/protocol-version))))
       (step "unsupported version -> -32022"
             #(let [bad (mk)] (try (expect-code (fn [] (mcp/call (assoc bad :version "1900-01-01") "server/discover"))
                                                mcp/code-unsupported-protocol)
                                   (finally (mcp/close bad)))))
       (step "header mismatch -> -32020"
             #(let [bad (mk)]
                (try (if (:stdio bad)
                       ::skip ; stdio has no HTTP headers to mismatch
                       (expect-code (fn [] (mcp/call (assoc bad :header-override {"Mcp-Name" "greet"})
                                                     "tools/call" {"name" "echo" "arguments" {"text" "x"}}))
                                    mcp/code-header-mismatch))
                     (finally (mcp/close bad)))))
       (step "tools/list"
             #(let [r (mcp/call c "tools/list")
                    names (set (map (fn [t] (get t "name")) (get r "tools")))]
                (when-not (and (names "echo") (names "greet")) (fail "tools: " names))
                (when-not (and (get r "ttlMs") (get r "cacheScope") (= "complete" (get r "resultType")))
                  (fail "missing ttlMs/cacheScope/resultType"))))
       (step "tools/call echo"
             #(let [r (mcp/call-tool c "echo" {"text" "hi toolnexus"})]
                (when-not (= "hi toolnexus" (mcp/text r)) (fail "got " r))))
       (step "tools/call greet (MRTR input_required round trip)"
             #(let [asked (atom 0)
                    r (mcp/call-tool c "greet" {}
                                     (fn [_ req]
                                       (swap! asked inc)
                                       (when-not (= "elicitation/create" (get req "method"))
                                         (fail "unexpected input request " (get req "method")))
                                       {"action" "accept" "content" {"name" "toolnexus"}}))]
                (when-not (and (= 1 @asked) (= "Hello, toolnexus!" (mcp/text r)))
                  (fail "asked=" @asked " result=" r))))
       (step "skills/list"
             #(let [r (mcp/call c "skills/list")]
                (when-not (some (fn [s] (= uri (get s "uri"))) (get r "skills")) (fail "hello-world not listed: " r))))
       (step "skills/get hello-world"
             #(let [sk (get (mcp/call c "skills/get" {"uri" uri}) "skill")]
                (when-not (and (= "hello-world" (get-in sk ["frontmatter" "name"])) (seq (get sk "resources")))
                  (fail "got " sk))))
       (finally (mcp/close c)))
     @ok)))

(defn -main [& [cmd arg]]
  (case cmd
    "server" (let [port (Integer/parseInt (or (System/getenv "PORT") "7107"))]
               (mcp/serve! (mcp/fixture-server) {:port port})
               (println (str "listening on http://127.0.0.1:" port "/mcp"))
               @(promise))
    "client" (System/exit (if (run-checks (or arg "http://127.0.0.1:7107/mcp")) 0 1))
    "selftest" (let [s (mcp/serve! (mcp/fixture-server))]
                 (System/exit (if (run-checks (str (:url s) "/mcp")) 0 1)))
    (do (println "usage: run.sh server | client <url> | selftest") (System/exit 2))))
