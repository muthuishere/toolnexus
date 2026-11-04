;; CLI client (SPEC §8C) — hermetic: no network, no real agent CLI. Mirrors
;; golang/cli_test.go.
;;
;; Three fakes:
;;   - the one-shot CLI is a POSIX `sh` script written per test (fast; no JVM);
;;   - the ACP agent is THIS PORT re-invoked through toolnexus.test-main with
;;     TN_FAKE_ACP=1 (the same re-invoke trick toolnexus.acp-test uses) — the
;;     script `exec`s into it when its first arg is `acp`, so ONE command serves
;;     both transports, exactly as a real CLI does;
;;   - the stdio MCP bridge is the port re-invoked with TN_MCP_BRIDGE_URL set.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.host :as host]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.text :as ktext]
            [koine.time :as ktime]
            [toolnexus.cli :as cli]
            [toolnexus.cli-acp :as cli-acp]
            [toolnexus.cli-styles :as cli-styles]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]
            [toolnexus.shared-examples-test :as te]))

;; ===========================================================================
;; the fake ACP agent — reached from toolnexus.test-main/-main (TN_FAKE_ACP=1)
;; ===========================================================================

(defn- out! [m] (println (json/write-str m)) (flush))

(defn- rec! [line]
  (when-let [p (env/get-env "TN_FAKE_REC")]
    (let [old (if (fs/exists? p) (fs/read-file p) "")]
      (fs/write-file p (str old line "\n")))))

(def ^:private served-requests
  ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fake\",\"version\":\"0\"}}}"
   "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
   "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_badge_number\",\"arguments\":{}}}"
   "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"load_skill\",\"arguments\":{\"name\":\"hello-world\"}}}"])

(defn- call-served
  "Call get_badge_number and load_skill on the handed MCP server (http with the
  given headers, or by spawning the stdio entry), and probe the http endpoint
  without a token."
  [entry]
  (cond
    (= "http" (:type entry))
    (let [hdr     (into {} (map (fn [h] [(:name h) (str (:value h))]) (:headers entry)))
          post    (fn [body headers]
                    (http/request {:method :post :url (:url entry) :body body
                                   :headers (merge {"Content-Type" "application/json"
                                                    "Accept" "application/json, text/event-stream"}
                                                   headers)}))
          replies (mapv (fn [r] (str (:body (post r hdr)))) served-requests)
          noauth  (:status (post (first served-requests) {}))
          get-st  (:status (http/request {:method :get :url (:url entry) :headers hdr :timeout-ms 5000}))]
      (str "entry=http noauth=" noauth " get=" get-st " replies=" (str/join " | " replies)))

    (some? entry)
    (let [envm (into {} (map (fn [e] [(:name e) (str (:value e))]) (:env entry)))
          r    (proc/sh (into [(:command entry)] (:args entry))
                        {:in (str (str/join "\n" served-requests) "\n") :env envm :timeout-ms 120000})]
      (if (not= 0 (:exit r))
        (str "bridge error " (:err r))
        (str "entry=<nil> replies=" (str/join " | " (str/split-lines (str/trim (str (:out r))))))))

    :else "entry=none"))

(defn run-fake-acp!
  "The fake ACP agent. FAKE_HTTP=1 advertises mcpCapabilities.http.
  FAKE_NO_CONFIG=1 rejects session/set_config_option. FAKE_PROJECT=1 reads the
  MCP server from <cwd>/.mcp.json instead of session/new. TN_FAKE_REC records
  every method + params."
  []
  (let [entry (atom nil) cwd (atom nil) id (atom 1000)
        ask   (fn [call-id title]
                (let [my (swap! id inc)]
                  (out! {:jsonrpc "2.0" :id my :method "session/request_permission"
                         :params {:sessionId "s1" :toolCall {:toolCallId call-id :title title}
                                  :options [{:optionId "yes" :kind "allow_once"}
                                            {:optionId "no" :kind "reject_once"}]}})
                  (loop []
                    (if-let [line (read-line)]
                      (let [m (try (json/read-str line) (catch Throwable _ nil))]
                        (if (and (map? m) (= my (:id m)) (nil? (:method m)))
                          (str (get-in m [:result :outcome :optionId]))
                          (recur)))
                      ""))))
        update (fn [u] (out! {:jsonrpc "2.0" :method "session/update" :params {:sessionId "s1" :update u}}))]
    (loop []
      (when-let [line (read-line)]
        (let [m (try (json/read-str line) (catch Throwable _ nil))]
          (when (map? m)
            (rec! (str (:method m) " " (json/write-str (:params m))))
            (case (str (:method m))
              "initialize"
              (out! {:jsonrpc "2.0" :id (:id m)
                     :result {:protocolVersion 1
                              :agentCapabilities {:mcpCapabilities {:http (= "1" (env/get-env "FAKE_HTTP"))}}}})
              "session/new"
              (let [p (:params m)]
                (reset! cwd (:cwd p))
                (when (= 1 (count (:mcpServers p))) (reset! entry (first (:mcpServers p))))
                (out! {:jsonrpc "2.0" :id (:id m) :result {:sessionId "s1"}}))
              "session/set_config_option"
              (if (= "1" (env/get-env "FAKE_NO_CONFIG"))
                (out! {:jsonrpc "2.0" :id (:id m) :error {:code -32601 :message "nope"}})
                (out! {:jsonrpc "2.0" :id (:id m) :result {}}))
              "session/set_model"
              (out! {:jsonrpc "2.0" :id (:id m) :result {}})
              "session/prompt"
              (do
                (when (= "1" (env/get-env "FAKE_PROJECT"))
                  (let [pf (json/read-str (fs/read-file (str @cwd "/.mcp.json")) {:key-fn str})
                        e  (get-in pf ["mcpServers" "toolnexus"])]
                    (reset! entry {:type "http" :url (get e "url")
                                   :headers (mapv (fn [[k v]] {:name k :value v}) (get e "headers"))})))
                ;; devin-like: the name is only on the tool_call update; the
                ;; permission request has an empty title.
                (update {:sessionUpdate "tool_call" :toolCallId "tc1" :title "Calling get_badge_number from toolnexus"})
                (let [ours  (ask "tc1" "")
                      _     (update {:sessionUpdate "tool_call" :toolCallId "tc2" :title "Bash"})
                      own   (ask "tc2" "Bash")
                      ;; a lookalike title on a new id: must be rejected
                      fake  (ask "tc3" "my_get_badge_number_wrapper")
                      ;; the same id again with a different title: decided per id
                      again (ask "tc1" "Bash")
                      ;; an fs/* request we declared unsupported must get -32601
                      text  (str "perm ours=" ours " own=" own " fake=" fake " again=" again "; "
                                 "prompt=" (pr-str (str/join "" (map :text (get-in m [:params :prompt])))) "; "
                                 (call-served @entry))]
                  (update {:sessionUpdate "agent_message_chunk" :content {:type "text" :text text}})
                  (out! {:jsonrpc "2.0" :id (:id m) :result {:stopReason "end_turn"}})))
              nil)))
        (recur)))))

;; ===========================================================================
;; helpers
;; ===========================================================================

(defn- self-command
  "This port's test entry point, on whichever host runs the suite (never cljgo
  from the JVM — jvm-only-check.sh poisons it)."
  []
  (if (= :jvm host/id)
    "clojure -M -m toolnexus.test-main"
    "cljgo run src/run_tests.cljc"))

(defn- sh-quote [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

(def ^:private fake-script
  "#!/bin/sh
all=\"$*\"
if [ \"$1\" = acp ]; then
  if [ \"$FAKE_FAIL_INIT\" = 1 ]; then printf 'unknown flag: acp' >&2; exit 2; fi
  cd __DIR__ && TN_FAKE_ACP=1 exec __SELF__
fi
mode=\"$TN_FAKE_CLI\"; prompt=''; pset=0; out=''
while [ $# -gt 0 ]; do
  case \"$1\" in
    --p) shift; prompt=\"$1\"; pset=1;;
    --f) shift; prompt=$(cat \"$1\"); pset=1;;
    --o) shift; out=\"$1\";;
    --argv) printf '{\"content\":\"%s\"}' \"$all\"; exit 0;;
    # style-agnostic: every built-in style's real argv works — the prompt as an
    # arg, the {{file}} temp file, the {{out}} temp path (not created yet)
    \"__PRE__\"*) prompt=\"$1\"; pset=1;;
    *toolnexus-cli*) if [ -f \"$1\" ]; then prompt=$(cat \"$1\"); pset=1; else out=\"$1\"; fi;;
  esac
  shift
done
[ $pset = 0 ] && prompt=$(cat)
if [ -n \"$TN_FAKE_REC\" ]; then printf '%s\\n=====\\n' \"$prompt\" >> \"$TN_FAKE_REC\"; fi
case \"$mode\" in
  exit) printf 'boom: bad login' >&2; exit 3;;
  long) i=0; while [ $i -lt 1200 ]; do printf 'é' >&2; i=$((i+1)); done; exit 1;;
  empty) exit 0;;
  sleep) sleep 10; exit 0;;
  nooutfile) printf 'progress only'; exit 0;;
  env) printf '{\"content\":\"%s\"}' \"$TN_ENV_PROBE\"; exit 0;;
  tool)
    case \"$prompt\" in
      *BADGE-77Q*) reply='{\"content\": \"Your badge is BADGE-77Q.\"}';;
      *) reply='```json
{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"get_badge_number\",\"arguments\":\"{}\"}}]}
```
';;
    esac;;
  *) reply='{\"content\":\"hello\"}';;
esac
if [ -n \"$out\" ]; then printf 'progress on stdout\\n'; printf '%s' \"$reply\" > \"$out\"; exit 0; fi
printf '%s' \"$reply\"
")

(defn- fake-cli!
  "Write the fake CLI script into a fresh dir; returns its path."
  []
  (let [dir  (fs/real-path (fs/temp-dir! "tn-fake-cli"))
        path (str dir "/fakecli")]
    (fs/write-file path (-> fake-script
                            (str/replace "__DIR__" (sh-quote (fs/real-path ".")))
                            (str/replace "__SELF__" (self-command))
                            (str/replace "__PRE__" (subs cli/preamble 0 20))))
    (proc/sh ["chmod" "755" path] {:timeout-ms 5000})
    path))

(defn- badge-toolkit []
  (toolnexus/build
   {:builtins false
    :skills   (str (te/examples-dir) "/skills")
    :tools    [(native/native-tool {:name "get_badge_number" :description "Returns the badge number."
                                    :input-schema {:type "object" :properties {}}
                                    :run (fn [_] "BADGE-77Q")})]}))

(defn- fake-cli-opts [mode & extra]
  {:style :custom :command (fake-cli!) :args (vec extra) :prompt "stdin" :answer "stdout"
   :env {"TN_FAKE_CLI" mode} :transport :cli})

(defn- run-cli [opts tk]
  (let [c (cli/create-cli-client opts)]
    (try (client/run c "What is my badge number?" (if tk {:toolkit tk} {}))
         (finally (cli/close! c)))))

(defn- err-of [f]
  (try (f) nil (catch Throwable e (ex-message e))))

(defn- conformance-dir []
  (str (te/examples-dir) "/../conformance"))

(defn- temp-file [name]
  (str (fs/real-path (fs/temp-dir! "tn-cli-test")) "/" name))

;; ===========================================================================
;; shared data
;; ===========================================================================

(deftest styles-match-conformance-byte-for-byte
  (let [dir   (str (conformance-dir) "/cli-styles")
        files (sort (map (fn [p] (subs p (inc (str/last-index-of p "/"))))
                         (filter (fn [p] (or (str/ends-with? p ".json") (str/ends-with? p ".txt")))
                                 (fs/list-tree dir))))]
    (is (= 8 (count files)) "7 styles + preamble")
    (doseq [f files]
      (let [want (fs/read-file (str dir "/" f))
            got  (if (= f "preamble.txt")
                   cli-styles/preamble
                   (get cli-styles/style-json (subs f 0 (- (count f) 5))))]
        (is (= want got) (str "clojure/src/toolnexus/cli_styles.cljc differs from conformance/cli-styles/" f
                              " (regenerate it)")))))
  (doseq [s [:claude :codex :copilot :opencode :devin :gemini :goose]]
    (let [d (cli/style-def s)]
      (is (= (name s) (:name d)))
      (is (not (str/blank? (str (:verified d)))))
      (is (nil? (err-of #(cli/create-cli-client {:style s :command "x" :transport :cli}))))))
  (is (nil? (:acp (cli/style-def :codex))) "codex must have no ACP launch")
  (is (= ".mcp.json" (get-in (cli/style-def :devin) [:acp :projectMcpFile])))
  (is (str/starts-with? (str (:verified (cli/style-def :goose))) "UNVERIFIED")))

(deftest style-and-transport-are-closed-sets
  (is (= #{:claude :codex :copilot :opencode :devin :gemini :goose :custom} cli/styles))
  (is (= #{:auto :acp :cli} cli/transports))
  (is (str/includes? (str (err-of #(cli/create-cli-client {:style "claude" :command "x"}))) "not \"claude\""))
  (is (= "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"
         (err-of #(cli/create-cli-client {:style :nope :command "x"}))))
  (is (str/includes? (str (err-of #(cli/create-cli-client {:style :claude :command "x" :transport "cli"})))
                     "Transport must be one of")))

(deftest load-cli-style-from-json-and-path
  (let [text "{\"name\":\"mine\",\"args\":[\"-q\",\"{{prompt}}\"],\"prompt\":\"arg\",\"answer\":\"stdout\"}"
        p    (temp-file "style.json")]
    (fs/write-file p text)
    (is (= "mine" (:name (cli/load-cli-style text))))
    (is (= "mine" (:name (cli/load-cli-style p))))
    (is (nil? (err-of #(cli/create-cli-client {:style-def (cli/load-cli-style p) :command "x"}))))
    (is (str/starts-with? (str (err-of #(cli/load-cli-style "/no/such/style.json"))) "cli style: "))))

(deftest construction-errors
  (doseq [[o want]
          [[{:style :claude} "cli: Command is required (path or name of the CLI binary)"]
           [{:command "x"} "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"]
           [{:style :goose :command "goose" :model "m" :transport :cli} "cli: style \"goose\" takes no model"]
           [{:style :codex :command "codex" :transport :acp} "cli: style \"codex\" has no ACP launch; set ACPCommand or use CliTransports.CLI"]
           [{:style :custom :command "x" :args ["{{model}}"] :prompt "stdin" :answer "stdout"} "cli: the style needs a model: {{model}} is outside an optional group"]
           [{:style :custom :command "x" :args ["{{nope}}"] :prompt "stdin" :answer "stdout"} "cli: unknown placeholder {{nope}}"]
           [{:style :custom :command "x" :prompt "arg" :answer "stdout"} "cli: prompt \"arg\" needs {{prompt}} in args"]
           [{:style :custom :command "x" :prompt "file" :answer "stdout"} "cli: prompt \"file\" needs {{file}} in args"]
           [{:style :custom :command "x" :prompt "stdin" :answer "out"} "cli: answer \"out\" needs {{out}} in args"]
           [{:style :custom :command "x" :prompt "pipe" :answer "stdout"} "cli: prompt must be stdin, arg or file, not \"pipe\""]
           [{:style :custom :command "x" :prompt "stdin" :answer "json"} "cli: answer must be stdout or out, not \"json\""]]]
    (is (= want (err-of #(cli/create-cli-client o))) (pr-str o))))

(deftest parse-cli-reply-table
  (doseq [[in content tool]
          [["{\"content\":\"hi\"}" "hi" nil]
           ["```json\n{\"content\":\"fenced\"}\n```\n\n" "fenced" nil]
           ["Sure! {\"content\":\"wrapped\"} done" "wrapped" nil]
           ["{\"choices\":[{\"message\":{\"content\":\"openai\"}}]}" "openai" nil]
           ["{\"message\":{\"content\":\"msg\"}}" "msg" nil]
           ["{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"x\\\":1}\"}}]}" nil "t"]
           ["{\"tool_calls\":[{\"name\":\"flat\",\"arguments\":{\"x\":1}}]}" nil "flat"]
           ["plain text answer" "plain text answer" nil]
           ["{\"content\":\"x\"} trailing {" "x" nil]
           ["{\"answer\":1}" "{\"answer\":1}" nil]
           ["{\"content\":\"x\"} {\"content\":\"y\"}" "{\"content\":\"x\"} {\"content\":\"y\"}" nil]]]
    (let [r (cli/parse-cli-reply in)]
      (if tool
        (is (= [tool] (mapv :name (:tool-calls r))) in)
        (is (and (= content (:content r)) (empty? (:tool-calls r))) in))))
  (is (= (str cli/preamble "\nREQUEST:\n{\"messages\":[],\"tools\":[]}") (cli/render-cli-prompt nil nil))))

;; ===========================================================================
;; one-shot transport
;; ===========================================================================

(deftest one-shot-tool-round-trip
  (let [rec (temp-file "rec")
        o   (-> (fake-cli-opts "tool")
                (assoc-in [:env "TN_FAKE_REC"] rec)
                (assoc :system-prompt "You are a badge clerk."))
        res (run-cli o (badge-toolkit))]
    (is (str/includes? (str (:text res)) "BADGE-77Q"))
    (is (= ["get_badge_number"] (mapv :name (:tool-calls res))))
    (let [first-prompt (first (str/split (fs/read-file rec) #"\n=====\n"))
          req          (json/read-str (second (str/split first-prompt #"\nREQUEST:\n" 2)))]
      (is (str/starts-with? first-prompt (str cli/preamble "\nREQUEST:\n")))
      (is (= "system" (get-in req [:messages 0 :role])))
      (is (str/includes? (str (get-in req [:messages 0 :content])) "badge clerk"))
      (is (seq (:tools req))))))

(deftest prompt-modes-and-out-file
  (let [tk (badge-toolkit)]
    (doseq [[args prompt answer] [[["--p" "{{prompt}}"] "arg" "stdout"]
                                  [["--f" "{{file}}"] "file" "stdout"]
                                  [["--o" "{{out}}"] "stdin" "out"]]]
      (let [res (run-cli (assoc (fake-cli-opts "tool") :args args :prompt prompt :answer answer) tk)]
        (is (str/includes? (str (:text res)) "BADGE-77Q") (str prompt "/" answer))))))

(deftest model-group
  (let [o (update (fake-cli-opts "x" "--argv" "-q") :args conj ["--model" "{{model}}"])]
    (is (not (str/includes? (str (:text (run-cli o nil))) "--model")) "no model => group dropped")
    (is (str/includes? (str (:text (run-cli (assoc o :model "prov/m-1") nil))) "--model prov/m-1"))))

(deftest temp-files-removed-on-every-path
  (let [o    (assoc (fake-cli-opts "x" "--argv" "--f" "{{file}}" "--o" "{{out}}") :prompt "file" :answer "out")
        ;; --argv answers on stdout, so answer "out" finds no file — an error path
        err  (err-of #(run-cli o nil))
        c    (cli/create-cli-client (assoc (fake-cli-opts "x") :args ["--f" "{{file}}"] :prompt "file"))
        r    (cli/cli-argv (::cli/cli c) "P")]
    (is (= "cli: no output file" err))
    (is (fs/exists? (:file r)) "cli-argv creates the {{file}} it hands out")
    (fs/delete-tree! (:dir r))
    ;; and the full invoke path cleans up after itself
    (let [res (run-cli (assoc (fake-cli-opts "x" "--argv" "--f" "{{file}}") :prompt "file") nil)
          f   (second (re-find #"--f (\S+)" (str (:text res))))]
      (is (some? f))
      (is (not (fs/exists? f)) "the {{file}} temp is removed after the call"))))

(deftest pinned-errors
  (doseq [[o want]
          [[(fake-cli-opts "exit") "cli: exit 3: boom: bad login"]
           [(fake-cli-opts "empty") "cli: empty reply"]
           [(assoc (fake-cli-opts "nooutfile" "--x" "{{out}}") :answer "out") "cli: no output file"]
           [(assoc (fake-cli-opts "x") :command "definitely-not-a-cli-xyz") "cli: command not found: definitely-not-a-cli-xyz"]]]
    (is (str/includes? (str (err-of #(run-cli o nil))) want) want)))

(deftest timeout-kills-the-tree
  (let [t0  (ktime/mono-ms)
        err (err-of #(run-cli (assoc (fake-cli-opts "sleep") :call-timeout-ms 300) nil))]
    (is (= "cli: timed out after 300ms" err))
    (is (< (- (ktime/mono-ms) t0) 5000) "the deadline bounds the call, grandchild included")))

(deftest exit-stderr-truncated-to-1000-code-points
  (let [err (err-of #(run-cli (fake-cli-opts "long") nil))]
    (is (str/starts-with? (str err) "cli: exit 1: é"))
    (is (str/ends-with? (str err) "é…"))
    (is (= (+ (count "cli: exit 1: ") 1001)
           (count (ktext/code-points (str err)))))))

(deftest env-expands-from-process-env
  (let [res (run-cli (assoc-in (fake-cli-opts "env") [:env "TN_ENV_PROBE"] "home=${HOME}") nil)]
    (is (= (str "home=" (env/get-env "HOME")) (:text res)))))

;; ===========================================================================
;; ACP transport
;; ===========================================================================

(defn- fake-acp-opts [env-extra]
  (let [rec (temp-file "rec")]
    [{:style-def {:name "fake" :args [] :prompt "stdin" :answer "stdout" :acp {:args ["acp"]}}
      :command (fake-cli!)
      :env (merge {"TN_FAKE_REC" rec} env-extra)
      :toolkit (badge-toolkit)
      :call-timeout-ms 120000
      :bridge-command ["sh" "-c" (str "cd " (sh-quote (fs/real-path ".")) " && TN_MCP_BRIDGE_URL=\"$0\" exec "
                                      (self-command))]}
     rec]))

(defn- assert-fake-acp-run [c res entry]
  (is (= "acp" (cli/transport c)) (cli/fallback-reason c))
  (doseq [want ["perm ours=yes own=no fake=no again=yes" (str "entry=" entry) "BADGE-77Q" "Hello"]]
    (is (str/includes? (str (:text res)) want) want))
  (is (str/includes? (str (:text res)) "\"name\":\"load_skill\""))
  (is (not (str/includes? (str (:text res)) "\"name\":\"skill\"")) "skill is served as load_skill, never skill")
  (is (= 2 (count (:served-calls (cli/acp-session c)))) "the server records the executed tools"))

(deftest acp-http-entry-with-bearer-token
  (let [[o rec] (fake-acp-opts {"FAKE_HTTP" "1"})
        c       (cli/create-cli-client o)]
    (try
      (let [res (client/run c "badge?" {})]
        (assert-fake-acp-run c res "http")
        (is (str/includes? (str (:text res)) "noauth=401") "no bearer => 401")
        (is (str/includes? (str (:text res)) "get=405") "non-POST => 405")
        (is (str/includes? (fs/read-file rec) "\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer "))
        (is (not (str/includes? (pr-str (cli/acp-session c)) "Bearer ")) "the token is redacted"))
      (finally (cli/close! c)))))

(deftest acp-stdio-bridge-when-agent-lacks-http
  (let [[o rec] (fake-acp-opts {"FAKE_HTTP" "0"})
        c       (cli/create-cli-client o)]
    (try
      (let [res (client/run c "badge?" {})
            r   (fs/read-file rec)]
        (assert-fake-acp-run c res "<nil>")
        (is (str/includes? r "\"env\":[{\"name\":\"TOOLNEXUS_MCP_TOKEN\""))
        (is (not (str/includes? r "\"type\":\"stdio\""))))
      (finally (cli/close! c)))))

(deftest acp-project-mcp-file
  (let [[o _] (fake-acp-opts {"FAKE_HTTP" "0" "FAKE_PROJECT" "1"})
        o     (assoc-in o [:style-def :acp :projectMcpFile] ".mcp.json")
        c     (cli/create-cli-client o)
        res   (client/run c "badge?" {})
        info  (cli/acp-session c)]
    (assert-fake-acp-run c res "http")
    (is (empty? (:mcp-servers-sent info)))
    (is (fs/exists? (str (:session-cwd info) "/.mcp.json")))
    (cli/close! c)
    (is (not (fs/exists? (:session-cwd info))) "the temp session dir is removed on close!")
    (is (http/failed? (http/request {:method :post :url (:url info) :body "{}" :timeout-ms 3000}))
        "the served endpoint stops with the session")))

(deftest acp-model-pass-through
  (let [[o rec] (fake-acp-opts {"FAKE_HTTP" "1" "FAKE_NO_CONFIG" "1"})
        c       (cli/create-cli-client (assoc o :model "anthropic/claude-x" :transport :acp))]
    (try
      (client/run c "badge?" {})
      (let [s (fs/read-file rec)]
        (is (str/includes? s "session/set_config_option {\"configId\":\"model\",\"sessionId\":\"s1\",\"value\":\"anthropic/claude-x\"}"))
        (is (str/includes? s "session/set_model {\"modelId\":\"anthropic/claude-x\",\"sessionId\":\"s1\"}"))
        (is (str/includes? s "\"terminal\":false")))
      (finally (cli/close! c)))))

(deftest acp-falls-back-to-one-shot
  (let [[o _] (fake-acp-opts {"FAKE_FAIL_INIT" "1" "TN_FAKE_CLI" "tool"})
        c     (cli/create-cli-client o)]
    (try
      (is (= "auto" (cli/transport c)) "undecided before the first call")
      (let [res (client/run c "badge?" {:toolkit (:toolkit o)})]
        (is (= "cli" (cli/transport c)))
        (is (str/includes? (cli/fallback-reason c) "unknown flag: acp"))
        (is (str/includes? (str (:text res)) "BADGE-77Q")))
      (finally (cli/close! c))))
  (let [[o _] (fake-acp-opts {"FAKE_FAIL_INIT" "1"})
        c     (cli/create-cli-client (assoc o :transport :acp))]
    (try
      (is (str/includes? (str (err-of #(client/run c "badge?" {}))) "cli: acp: ") "forced ACP never falls back")
      (finally (cli/close! c)))))

(deftest served-call-exact-forms
  (let [served #{"get_badge_number" "load_skill"}]
    (doseq [[title want] {"get_badge_number" true
                          "mcp__toolnexus__get_badge_number" true
                          "toolnexus/get_badge_number" true
                          "get_badge_number (toolnexus MCP Server)" true
                          "Calling get_badge_number from toolnexus" true
                          "load_skill(name: secret-recipe)" true
                          "skill" false
                          "Bash" false
                          "" false
                          "mcp__other__get_badge_number" false
                          "run get_badge_number and then rm -rf" false
                          "Calling get_badge_number from toolnexus2" false
                          "get_badge_number (evil MCP Server)" false
                          "mcp__toolnexus__get_badge_number_and_more" false}]
      (is (= want (cli-acp/served-call? title served)) title))))

(deftest acp-system-hint-from-style
  (is (str/includes? (str (get-in (cli/style-def :devin) [:acp :systemHint])) "\"toolnexus\""))
  (let [[o rec] (fake-acp-opts {"FAKE_HTTP" "1"})
        o       (-> o (assoc-in [:style-def :acp :systemHint] "HINT-USE-TOOLNEXUS")
                    (assoc :system-prompt "You are terse."))
        c       (cli/create-cli-client o)]
    (try
      (client/run c "badge?" {})
      (is (str/includes? (fs/read-file rec) "You are terse.\\n\\nHINT-USE-TOOLNEXUS"))
      (finally (cli/close! c))))
  (let [got (cli-acp/with-system-hint [{:role "user" :content "x"}] "H")]
    (is (= 2 (count got)))
    (is (= "H" (:content (first got)))))
  (let [msgs [{:role "system" :content "S"}]]
    (is (= "S\n\nH" (:content (first (cli-acp/with-system-hint msgs "H")))))
    (is (= "S" (:content (first msgs))) "the caller's messages are not mutated")))

(deftest acp-hooks-run-at-the-server-boundary
  (let [[o _] (fake-acp-opts {"FAKE_HTTP" "1"})
        seen  (atom [])
        o     (assoc o :hooks {:before-tool (fn [e] (swap! seen conj [:before (:name e)])
                                             (when (= "get_badge_number" (:name e))
                                               {:result {:output "HOOKED-1" :isError false}}))
                               :after-tool  (fn [e] (swap! seen conj [:after (:name e)]) nil)})
        c     (cli/create-cli-client o)]
    (try
      (let [res (client/run c "badge?" {})]
        (is (str/includes? (str (:text res)) "HOOKED-1") "before-tool short-circuits the served call")
        (is (some #{[:before "skill"]} @seen) "hooks see the toolkit's own name, not load_skill")
        (is (some #{[:after "skill"]} @seen))
        (is (not (some #{[:after "get_badge_number"]} @seen)) "a short-circuit skips the real tool"))
      (finally (cli/close! c)))))

;; ===========================================================================
;; the shared fixture every port replays
;; ===========================================================================

(deftest conformance-cases
  (let [fx (json/read-str (fs/read-file (str (conformance-dir) "/cli-client/cases.json")))]
    (doseq [cs (:argv fx)]
      (let [c (cli/create-cli-client {:style (keyword (:style cs)) :command (:command cs)
                                      :transport :cli :model (:model cs)})
            r (cli/cli-argv (::cli/cli c) (:prompt cs))
            argv (mapv (fn [a] (cond (and (:out r) (= a (:out r))) "<out>"
                                     (and (:file r) (= a (:file r))) "<file>"
                                     :else a))
                       (:argv r))]
        (when (:dir r) (fs/delete-tree! (:dir r)))
        (is (= (:argv cs) argv) (str (:style cs) " model=" (:model cs)))
        (is (= (:stdin cs) (= "stdin" (:prompt (cli/style-def (keyword (:style cs)))))))))
    (let [norm (fn [v] (json/write-str (if (string? v) (json/read-str v) v)))]
      (doseq [cs (:parse fx)]
        (let [r (cli/parse-cli-reply (:in cs))]
          (if-let [tc (:toolCall cs)]
            (let [got (first (:tool-calls r))]
              (is (= 1 (count (:tool-calls r))) (:in cs))
              (is (= (:name tc) (:name got)) (:in cs))
              (is (= (:id tc) (:id got)) (:in cs))
              (is (= (norm (:arguments tc)) (norm (:arguments got))) (:in cs)))
            (is (and (= (:content cs) (:content r)) (empty? (:tool-calls r))) (:in cs))))))
    (doseq [cs (:constructionErrors fx)]
      (let [o  (:options cs)
            o' (cond-> {}
                 (:style o) (assoc :style (keyword (:style o)))
                 (:command o) (assoc :command (:command o))
                 (:model o) (assoc :model (:model o))
                 (:prompt o) (assoc :prompt (:prompt o))
                 (:answer o) (assoc :answer (:answer o))
                 (:args o) (assoc :args (:args o))
                 (:transport o) (assoc :transport (keyword (:transport o))))]
        (is (= (:error cs) (err-of #(cli/create-cli-client o'))) (pr-str o))))
    (is (= (str/replace (:renderEmpty fx) "<PREAMBLE>" cli/preamble) (cli/render-cli-prompt nil nil)))))

;; ===========================================================================
;; streaming never errors (SPEC §8C, ADR 0037 decision 9)
;; ===========================================================================
;; This port has no streaming entry point: `:on-event` is a sink on the
;; non-streaming loop. What the contract asks of it is that a request carrying
;; {:stream true} is answered whole, never refused, on either transport.

(deftest generate-answers-a-stream-request-whole
  (let [c   (cli/create-cli-client (fake-cli-opts "x"))
        gen #'cli/generate]
    (try
      (is (= "hello" (:content (gen (::cli/cli c) {:messages [{:role "user" :content "hi"}]
                                                   :tools [] :body {:stream true}}))))
      (finally (cli/close! c)))))

(defn- run-with-events [opts tk]
  (let [evs (atom [])
        c   (cli/create-cli-client opts)]
    (try
      (let [res (client/run c "What is my badge number?" {:toolkit tk :on-event #(swap! evs conj %)})]
        [res (mapv :type @evs) (cli/transport c)])
      (finally (cli/close! c)))))

(deftest one-shot-stream-request-with-a-tool-call
  (let [[res types] (run-with-events (fake-cli-opts "tool") (badge-toolkit))]
    (is (str/includes? (str (:text res)) "BADGE-77Q"))
    (is (= ["tool_call" "tool_result"] (filterv #{"tool_call" "tool_result"} types)))
    (is (= "done" (last types)))
    (is (not-any? #{"error"} types))))

(deftest acp-stream-request-against-the-fake-agent
  (let [[o _]       (fake-acp-opts {"FAKE_HTTP" "1"})
        [res types] (run-with-events (assoc o :transport :acp) (:toolkit o))]
    (is (str/includes? (str (:text res)) "BADGE-77Q"))
    (is (= "done" (last types)))
    (is (not-any? #{"error"} types))))

(deftest stream-every-style-both-transports
  (doseq [st [:claude :codex :copilot :opencode :devin :gemini :goose :custom]
          tr [:cli :acp]]
    (testing (str (name st) "/" (name tr))
      (let [fake (fake-cli!)
            tk   (badge-toolkit)
            o    (cond-> {:style st :command fake :transport tr :toolkit tk
                          :env {"TN_FAKE_CLI" "tool"}}
                   (= :custom st) (assoc :args [] :prompt "stdin" :answer "stdout")
                   (= :acp tr) (merge (select-keys (first (fake-acp-opts {"FAKE_HTTP" "1"}))
                                                   [:env :bridge-command :call-timeout-ms])
                                      {:acp-command [fake "acp"]}))
            [res types t] (run-with-events o tk)]
        (is (not-any? #{"error"} types))
        (is (= "done" (last types)))
        (is (str/includes? (str (:text res)) "BADGE-77Q"))
        (when (= :cli tr) (is (some #{"tool_call"} types) "one-shot carries the loop's tool_call"))
        (is (= (name tr) t))))))
