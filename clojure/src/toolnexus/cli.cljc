;; CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has
;; installed — and pays for — becomes the model behind an ordinary in-process
;; client, selected from a CLOSED keyword set, never by an arbitrary string.
;; Ports golang/cli.go to this dual-host `.cljc` — no reader conditionals.
;;
;; Two transports, one constructor:
;;
;;   - ACP (primary, toolnexus.cli-acp): when the style declares an ACP launch
;;     and the binary speaks it, the toolkit is served as a local MCP endpoint
;;     and handed to the agent in session/new mcpServers.
;;   - one-shot CLI (fallback, this file): the CLI is run once per model turn
;;     with the conversation rendered under a fixed preamble; its reply is
;;     parsed as an OpenAI-shaped envelope and tool calls go back to the
;;     toolnexus loop.
;;
;; A style is data (toolnexus.cli-styles, byte-identical to
;; conformance/cli-styles/) and holds no binary: :command is always the caller's.
(ns toolnexus.cli
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.text :as ktext]
            [koine.time :as ktime]
            [toolnexus.builtin :as builtin]
            [toolnexus.cli-acp :as cli-acp]
            [toolnexus.cli-styles :as cli-styles]
            [toolnexus.client :as client]))

(def preamble
  "The one-shot preamble, byte-identical to conformance/cli-styles/preamble.txt."
  cli-styles/preamble)

(def default-call-timeout-ms
  "Bounds one CLI invocation / one ACP prompt (owner decision 2026-11-04)."
  300000)

(def styles
  "The closed set of CLI styles: each is a shape (argv, prompt mode, answer
  source, ACP launch), never a binary. `:custom` takes :args/:prompt/:answer."
  #{:claude :codex :copilot :opencode :devin :gemini :goose :custom})

(def transports
  ":auto (default) uses ACP when the style has an ACP launch and the binary
  answers initialize, else the one-shot CLI; :acp and :cli force one."
  #{:auto :acp :cli})

;; ---------------------------------------------------------------------------
;; styles
;; ---------------------------------------------------------------------------

(defn style-def
  "The built-in definition of a style keyword (nil for :custom and anything
  outside `styles`). A map with the JSON's own keys: :name :args :prompt
  :answer :timeoutMs :env :verified :notes :acp {:args :projectMcpFile
  :systemHint ...}."
  [style]
  (when (and (keyword? style) (contains? styles style) (not= :custom style))
    (when-let [text (get cli-styles/style-json (name style))]
      (json/read-str text))))

(defn load-cli-style
  "Read a user style from a file path, or from JSON text that starts with
  \"{\". Pass the result as :style-def."
  [path-or-json]
  (let [s (str path-or-json)]
    (try
      (json/read-str (if (str/starts-with? (str/trim s) "{") s (fs/read-file s)))
      (catch Throwable e
        (throw (ex-info (str "cli style: " (or (ex-message e) (str e))) {}))))))

;; ---------------------------------------------------------------------------
;; placeholders: {{name}} with no brace inside, leftmost first (the regex
;; \{\{([^{}]*)\}\} every port uses), scanned by hand — cljgo's str/replace has
;; no function-replacement arity.
;; ---------------------------------------------------------------------------

(defn- scan-placeholders
  "Split `s` into a vector of literal strings and [:ph name] tokens."
  [s]
  (let [n (count s)]
    (loop [i 0 lit-start 0 out []]
      (let [j (str/index-of s "{{" i)]
        (if (nil? j)
          (conj out (subs s lit-start n))
          (let [k (loop [k (+ j 2)]
                    (cond
                      (>= k n) nil
                      (= \{ (nth s k)) nil
                      (= \} (nth s k)) (when (and (< (inc k) n) (= \} (nth s (inc k)))) k)
                      :else (recur (inc k))))]
            (if (nil? k)
              (recur (inc j) lit-start out)
              (recur (+ k 2) (+ k 2)
                     (conj out (subs s lit-start j) [:ph (subs s (+ j 2) k)])))))))))

(defn- placeholders [s]
  (keep (fn [x] (when (vector? x) (second x))) (scan-placeholders s)))

(defn- substitute [s f]
  (apply str (map (fn [x] (if (vector? x) (f (second x)) x)) (scan-placeholders s))))

(defn- normalize-args [args]
  (mapv (fn [e]
          (cond
            (string? e) {:group false :parts [e]}
            (sequential? e) (if (every? string? e)
                              {:group true :parts (vec e)}
                              (throw (ex-info "cli: a group may only hold strings" {})))
            :else (throw (ex-info "cli: an arg must be a string or a group of strings" {}))))
        (or args [])))

(defn- fail! [msg] (throw (ex-info msg {})))

(defn- validate-style [d elems model]
  (let [prompt (str (or (:prompt d) "")) answer (str (or (:answer d) ""))]
    (when-not (contains? #{"stdin" "arg" "file"} prompt)
      (fail! (str "cli: prompt must be stdin, arg or file, not " (json/write-str prompt))))
    (when-not (contains? #{"stdout" "out"} answer)
      (fail! (str "cli: answer must be stdout or out, not " (json/write-str answer))))
    (let [seen (atom #{})]
      (doseq [e elems, p (:parts e), ph (placeholders p)]
        (case ph
          ("prompt" "file" "out") nil
          "model" (when (and (not (:group e)) (str/blank? model))
                    (fail! "cli: the style needs a model: {{model}} is outside an optional group"))
          (fail! (str "cli: unknown placeholder {{" ph "}}")))
        (swap! seen conj ph))
      (when (and (not (str/blank? model)) (not (contains? @seen "model")))
        (fail! (str "cli: style " (json/write-str (str (:name d))) " takes no model")))
      (when (and (= "arg" prompt) (not (contains? @seen "prompt")))
        (fail! "cli: prompt \"arg\" needs {{prompt}} in args"))
      (when (and (= "file" prompt) (not (contains? @seen "file")))
        (fail! "cli: prompt \"file\" needs {{file}} in args"))
      (when (and (= "out" answer) (not (contains? @seen "out")))
        (fail! "cli: answer \"out\" needs {{out}} in args")))))

;; ---------------------------------------------------------------------------
;; the envelope: render and tolerant parse
;; ---------------------------------------------------------------------------

(defn render-cli-prompt
  "PREAMBLE + \"\\nREQUEST:\\n\" + {\"messages\",\"tools\"}: the OpenAI-shaped
  request, unmodified, as compact JSON (sorted keys, no HTML escaping)."
  [messages tools]
  (str preamble "\nREQUEST:\n"
       (json/write-str {:messages (vec (or messages [])) :tools (vec (or tools []))})))

(defn- parse-object [s]
  (let [v (try (json/read-str s) (catch Throwable _ ::bad))]   ; trailing text throws
    (when (map? v) v)))

(defn parse-cli-reply
  "The tolerant six-step envelope parser: (1) trim; (2) strip one markdown
  fence; (3) parse the whole text as one JSON object, else (4) the span from
  the first \"{\" to the last \"}\"; (5) unwrap choices[0].message or message;
  (6) tool_calls (flat or {function:{}}, string or object arguments) win, else
  content. Anything that is not an envelope is returned verbatim as content.
  Returns the in-process answer shape: {:content s} or {:tool-calls [...]}."
  [text]
  (let [text (str text)
        s    (str/trim text)
        s    (if (str/starts-with? s "```")
               (let [i (str/index-of s "\n")
                     s (if i (subs s (inc i)) "")
                     s (str/trim s)
                     s (if (str/ends-with? s "```") (subs s 0 (- (count s) 3)) s)]
                 (str/trim s))
               s)
        obj  (or (parse-object s)
                 (let [i (str/index-of s "{") j (str/last-index-of s "}")]
                   (when (and i j (> j i)) (parse-object (subs s i (inc j))))))]
    (if-not obj
      {:content text}
      (let [ch  (:choices obj)
            obj (cond
                  (and (sequential? ch) (seq ch))
                  (let [m (when (map? (first ch)) (:message (first ch)))] (if (map? m) m obj))
                  (map? (:message obj)) (:message obj)
                  :else obj)
            calls (when (sequential? (:tool_calls obj))
                    (vec (keep (fn [e]
                                 (when (map? e)
                                   (let [f    (if (map? (:function e)) (:function e) e)
                                         nm   (:name f)]
                                     (when (and (string? nm) (not= "" nm))
                                       (cond-> {:name nm :arguments (if (contains? f :arguments)
                                                                      (if (nil? (:arguments f)) {} (:arguments f))
                                                                      {})}
                                         (and (string? (:id e)) (not= "" (:id e))) (assoc :id (:id e)))))))
                               (:tool_calls obj))))]
        (cond
          (seq calls) {:tool-calls calls}
          (contains? obj :content) (let [c (:content obj)]
                                     (cond (string? c) {:content c}
                                           (nil? c)    {:content ""}
                                           :else       {:content (json/write-str c)}))
          :else {:content text})))))

;; ---------------------------------------------------------------------------
;; one-shot invocation
;; ---------------------------------------------------------------------------

(defn cli-argv
  "The argv for one invocation. Returns {:argv :out :file :dir}; `:dir` is the
  temp dir holding {{file}}/{{out}}, which the caller removes on every path."
  [c prompt]
  (let [model (:model-arg c)
        dir   (atom nil)
        mk    (fn [] (or @dir (reset! dir (fs/temp-dir! "toolnexus-cli"))))
        file  (atom nil)
        out   (atom nil)
        sub   (fn [p]
                (substitute p (fn [ph]
                                (case ph
                                  "prompt" prompt
                                  "model"  model
                                  "file"   (or @file (let [f (str (mk) "/prompt")]
                                                       (fs/write-file f prompt)
                                                       (reset! file f)))
                                  "out"    (or @out (reset! out (str (mk) "/out")))
                                  (str "{{" ph "}}")))))
        argv  (into [(:command c)]
                    (mapcat (fn [e] (when-not (and (:group e) (str/blank? model))
                                      (map sub (:parts e))))
                            (:elems c)))]
    {:argv argv :out @out :file @file :dir @dir}))

(defn- truncate-code-points
  "`s` cut to `n` code points + \"…\" when longer."
  [s n]
  (if (<= (count (ktext/code-points s)) n)
    s
    ;; smallest prefix length holding n code points (binary search — the host's
    ;; own string index unit may be UTF-16 or runes)
    (let [k (loop [lo 0 hi (count s)]
              (if (>= lo hi) lo
                  (let [mid (quot (+ lo hi) 2)]
                    (if (>= (count (ktext/code-points (subs s 0 mid))) n)
                      (recur lo mid)
                      (recur (inc mid) hi)))))]
      (str (subs s 0 k) "…"))))

(defn- kill-tree! [pid]
  (let [pids (into [pid] (builtin/child-pids pid))]
    (doseq [p pids] (proc/sh ["kill" "-KILL" (str p)] {:timeout-ms 5000}))))

(defn- invoke
  "Run the one-shot CLI once. Returns the reply text; throws the pinned errors."
  [c prompt]
  (let [{:keys [argv out dir]} (try (cli-argv c prompt)
                                    (catch Throwable e (fail! (str "cli: " (ex-message e)))))]
    (try
      (let [bin (cli-acp/which (first argv))]
        (when-not bin (fail! (str "cli: command not found: " (first argv))))
        (let [;; the child runs under `sh` that records its pid then EXECs the
              ;; CLI (same pid), so a timeout can kill the whole tree — an
              ;; `npx …` launch is a tree, not one process.
              piddir  (fs/temp-dir! "toolnexus-cli-pid")
              pidfile (str piddir "/pid")
              script  "echo $$ > \"$0\"; exec \"$@\""
              res     (atom nil)
              opts    (cond-> {:in (if (= "stdin" (:prompt (:style c))) prompt "")}
                        (:cwd c) (assoc :dir (:cwd c))
                        (seq (:env c)) (assoc :env (cli-acp/expand-env (:env c))))
              _       (proc/run-async!
                       (fn [] (reset! res (try (proc/sh (into ["sh" "-c" script pidfile bin] (rest argv)) opts)
                                               (catch Throwable e {:exit -1 :err (or (ex-message e) (str e)) :out ""})))))
              deadline (+ (ktime/mono-ms) (:timeout c))
              r       (loop []
                        (cond
                          @res @res
                          (>= (ktime/mono-ms) deadline) nil
                          :else (do (ktime/sleep! 5) (recur))))]
          (try
            (when (nil? r)
              (let [pid (try (let [t (str/trim (fs/read-file pidfile))]
                               (when (re-matches #"[0-9]+" t) t))
                             (catch Throwable _ nil))]
                (when pid (kill-tree! (json/read-str pid))))
              (fail! (str "cli: timed out after " (:timeout c) "ms")))
            (let [code (:exit r)]
              (when (and (number? code) (not (zero? code)))
                (let [msg (str/trim (str (:err r)))]
                  (fail! (if (= "" msg)
                           (str "cli: exit " code)
                           (str "cli: exit " code ": " (truncate-code-points msg 1000))))))
              (let [text (if (= "out" (:answer (:style c)))
                           (if (and out (fs/exists? out)) (fs/read-file out) (fail! "cli: no output file"))
                           (str (:out r)))]
                (when (str/blank? text) (fail! "cli: empty reply"))
                text))
            (finally (try (fs/delete-tree! piddir) (catch Throwable _ nil))))))
      (finally
        (when dir (try (fs/delete-tree! dir) (catch Throwable _ nil)))))))

;; ---------------------------------------------------------------------------
;; the client
;; ---------------------------------------------------------------------------

(def ^:private cli-keys
  [:style :style-def :command :args :prompt :answer :env :call-timeout-ms :cwd
   :transport :toolkit :acp-command :bridge-command :on-acp-update])

(defn- resolve-style [{:keys [style command args prompt answer] sd :style-def}]
  (when (str/blank? (str (or command "")))
    (fail! "cli: Command is required (path or name of the CLI binary)"))
  (when (and (some? style) (not (keyword? style)))
    (fail! (str "cli: Style must be a keyword from toolnexus.cli/styles (:claude, :codex, ...), not "
                (pr-str style))))
  (cond
    sd               sd
    (= :custom style) {:name "custom" :args args :prompt prompt :answer answer}
    :else (or (style-def style)
              (fail! "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"))))

(defn- with-lock! [lock f]
  (loop []
    (if (compare-and-set! lock false true)
      (try (f) (finally (reset! lock false)))
      (do (ktime/sleep! 1) (recur)))))

(defn- session!
  "The live ACP session, opened on first use; nil means the one-shot transport."
  [c]
  (let [st (:cli-state c)]
    (with-lock! (:lock c)
      (fn []
        (cond
          (:closed? @st) (fail! "cli: client is closed")
          (= :cli (:chosen @st)) nil
          (:acp @st) (:acp @st)
          :else
          (let [r (try {:ok (cli-acp/open-session (:acp-cfg c))}
                       (catch Throwable e {:err e}))]
            (if-let [e (:err r)]
              (if (and (= :auto (:chosen @st)) (cli-acp/launch-error? e) (:has-cli? c))
                (do (swap! st assoc :chosen :cli :fallback-reason (ex-message e)) nil)
                (throw e))
              (do (swap! st assoc :acp (:ok r) :chosen :acp) (:ok r)))))))))

(defn- generate [c req]
  (if-let [s (session! c)]
    (cli-acp/generate s req)
    (do
      (when (true? (get-in req [:body :stream]))
        (fail! "cli: streaming is not supported by the one-shot CLI transport; use Run"))
      (parse-cli-reply (invoke c (render-cli-prompt (:messages req) (:tools req)))))))

(defn create-cli-client
  "A client whose model is an installed agent CLI — an ordinary in-process
  client map (use toolnexus.client/run on it), plus `transport`,
  `fallback-reason`, `acp-session` and `close!` below. Validates everything it
  can at construction; the transport is decided on the first model call.

  opts (plus every create-in-process-client option; :model is passed to the
  CLI unchanged, nil/\"\" => the CLI's own default):
    :command         REQUIRED — the CLI binary, a path or a name on PATH
    :style           one of `styles` (a keyword; strings are refused)
    :style-def       a user style (load-cli-style); wins over :style
    :args :prompt :answer   the CLI shape for :style :custom
    :env             added to the child's environment; values expand ${VAR}
                     from this process's environment at call time, never logged
    :call-timeout-ms bounds one CLI invocation / one ACP prompt (default 300000)
    :cwd             the child's working directory
    :transport       :auto (default) | :acp | :cli
    :toolkit         served to an ACP agent as a local MCP server; pass the
                     same toolkit (or none) to run
    :acp-command     full argv starting the agent in ACP mode (an adapter);
                     replaces :command + the style's acp.args for ACP only
    :bridge-command  argv of a stdio<->HTTP MCP bridge (URL appended, token in
                     env TOOLNEXUS_MCP_TOKEN); default `toolnexus mcp-bridge`
                     when the toolnexus CLI is on PATH
    :on-acp-update   (fn [params]) sees every raw ACP session/update"
  [opts]
  (let [d         (resolve-style opts)
        transport (or (:transport opts) :auto)
        _         (when-not (contains? transports transport)
                    (fail! (str "cli: Transport must be one of :auto, :acp, :cli, not " (pr-str transport))))
        model     (let [m (:model opts)] (if (str/blank? (str (or m ""))) "" (str m)))
        acp-argv  (cond (seq (:acp-command opts)) (vec (:acp-command opts))
                        (:acp d) (into [(:command opts)] (:args (:acp d))))
        _         (when (and (= :acp transport) (nil? acp-argv))
                    (fail! (str "cli: style " (json/write-str (str (:name d)))
                                " has no ACP launch; set ACPCommand or use CliTransports.CLI")))
        acp-only? (and (nil? (:args d)) (str/blank? (str (or (:prompt d) "")))
                       (str/blank? (str (or (:answer d) ""))) acp-argv (= :auto transport))
        elems     (when (and (not= :acp transport) (not acp-only?))
                    (let [es (normalize-args (:args d))]
                      (validate-style d es model)
                      es))
        env       (merge (or (:env d) {}) (or (:env opts) {}))
        timeout   (or (some (fn [x] (when (and (number? x) (pos? x)) x))
                            [(:call-timeout-ms opts) (:timeoutMs d)])
                      default-call-timeout-ms)
        chosen    (cond (or (= :cli transport) (nil? acp-argv)) :cli
                        (= :acp transport) :acp
                        :else :auto)
        state     (atom {:chosen chosen :acp nil :closed? false :fallback-reason ""})
        c         {:style d :command (:command opts) :elems elems :has-cli? (some? elems)
                   :env env :timeout timeout :cwd (:cwd opts) :model-arg model
                   :lock (atom false) :cli-state state
                   :acp-cfg {:argv acp-argv :style d :toolkit (:toolkit opts) :hooks (:hooks opts)
                             :model model :cwd (:cwd opts) :env env :timeout timeout
                             :acp-command? (boolean (seq (:acp-command opts)))
                             :bridge-command (:bridge-command opts)
                             :on-acp-update (:on-acp-update opts)}}
        ip        (-> (apply dissoc opts cli-keys)
                      (assoc :model (if (= "" model) (str (:name d)) model)
                             :generate (fn [req] (generate c req))))]
    (assoc (client/create-in-process-client ip) ::cli c)))

(defn transport
  "\"acp\", \"cli\", or \"auto\" before the first model call has decided."
  [cli-client]
  (name (:chosen @(:cli-state (::cli cli-client)))))

(defn fallback-reason
  "Why :auto fell back from ACP to the one-shot CLI (\"\" when it did not)."
  [cli-client]
  (:fallback-reason @(:cli-state (::cli cli-client))))

(defn acp-session
  "The live ACP session's info — {:url :mcp-servers-sent :permissions
  :served-calls :session-cwd}, token redacted — or nil when none is open."
  [cli-client]
  (when-let [s (:acp @(:cli-state (::cli cli-client)))]
    (cli-acp/session-info s)))

(defn close!
  "End the ACP agent, stop the served toolkit and remove any temp session dir.
  Idempotent; a no-op for the one-shot CLI."
  [cli-client]
  (let [st (:cli-state (::cli cli-client))
        s  (:acp @st)]
    (swap! st assoc :acp nil :closed? true)
    (when s (cli-acp/close! s))
    nil))

(def run-mcp-bridge
  "See toolnexus.cli-acp/run-mcp-bridge."
  cli-acp/run-mcp-bridge)
