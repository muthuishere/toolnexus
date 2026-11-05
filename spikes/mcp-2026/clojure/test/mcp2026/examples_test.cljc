(ns mcp2026.examples-test
  "Runs every program under examples/ end to end and checks its exact output, so the docs (which
  copy code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only); 09 needs
  KEYCLOAK=1 + the Keycloak compose, 10 needs GITHUB_OAUTH=1 + sec-injected creds."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util.concurrent TimeUnit)))

(def hermetic
  {"01-connect-http" "supported versions: [2026-07-28]\ntools: echo, greet\necho: hello over HTTP"
   "02-connect-stdio" "tool count: 2\necho: hello over stdio"
   "03-tool-input" "server asks (elicitation/create): What is your name?\nresult: Hello, Ada!"
   "04-skills" (str "skills extension: true\nskill skill://hello-world/SKILL.md: hello-world\n"
                    "first heading: # Hello World Skill\ndownloaded: hello-world/SKILL.md\ndownloaded: hello-world/scripts/greet.sh")
   "05-tool-search" (str "eager: 100 tools, 9721 bytes of schema\nlazy:  2 tools, 519 bytes of schema\n"
                         "top hit: slack_create\nresult: slack_create ran with hi team")
   "06-oauth-zero-config" "echo: hello after login\ngreet: Hello, Ada!\nbrowser logins: 2\ntoken stored for this issuer: true"
   "07-oauth-web-pause-resume" (str "(a) web-handler: 200 resumed after consent\n"
                                    "(b) paused: authorization - true - has URL: true\n(b) resumed: resumed by hand")
   "08-serve-protected" (str "no token: 401 | WWW-Authenticate has resource_metadata: true\n"
                             "metadata: issuer ok: true | scopes: [mcp:read]\nwhoami: hello dev-user (mcp:read)\n"
                             "delete_all after step-up: deleted\nwhoami: hello dev-user (mcp:read mcp:admin)\n"
                             "verify hook: hello billing-service\nwrong key rejected: true")})

(defn run-example
  "Run examples/<name>/main.cljc in a fresh JVM with extra env; trimmed stdout."
  [name timeout-s env]
  (let [pb (ProcessBuilder. ^java.util.List ["java" "-cp" (System/getProperty "java.class.path") "clojure.main"
                                             (str "examples/" name "/main.cljc")])
        _ (doseq [[k v] env] (.put (.environment pb) k v))
        p (.start pb)
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))]
    (when-not (.waitFor p timeout-s TimeUnit/SECONDS)
      (.destroyForcibly p)
      (throw (ex-info (str name ": timed out") {})))
    (when-not (zero? (.exitValue p))
      (throw (ex-info (str name ": exit " (.exitValue p) "\nstdout:\n" @out "\nstderr:\n" @err) {})))
    (str/trim @out)))

(deftest examples-hermetic
  (let [runs (into {} (for [[n _] hermetic] [n (future (try (run-example n 120 {}) (catch Exception e (.getMessage e))))]))]
    (doseq [[n want] (sort hermetic)]
      (testing n (is (= want @(runs n)) (str n ": output drifted"))))))

(deftest example-keycloak
  (if (not= "1" (System/getenv "KEYCLOAK"))
    (println "SKIP example-keycloak: set KEYCLOAK=1 with keycloak/docker-compose.yml running")
    (let [got (run-example "09-keycloak" 90 {"HEADLESS_LOGIN" "1"})]
      (println got)
      (is (str/includes? got "whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true"))
      (is (str/includes? got "greet: Hello, Alice!")))))

(deftest example-github
  (if (not= "1" (System/getenv "GITHUB_OAUTH"))
    (println "SKIP example-github: set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)")
    (let [got (run-example "10-github" 180 {"BROWSER" "chrome-agent"})]
      (println got)
      (is (= "tools listed: true\nget_me: complete | isError: false" got)))))
