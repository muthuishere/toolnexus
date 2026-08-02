;; toolnexus, in portable Clojure.
;;
;; THIS FILE RUNS UNMODIFIED ON TWO RUNTIMES: Clojure on the JVM, and cljgo
;; (Clojure compiled to a Go binary). There is no reader conditional in it, no
;; `java.*`, no Go interop. Every host-shaped thing — files, subprocesses,
;; JSON — goes through koine, which is the seam that makes that possible.
;;
;; What it does, in four steps:
;;
;;   1. reads examples/mcp.json and starts the MCP server it names, as a real
;;      child process, then lists its tools                       (SPEC §2)
;;   2. globs examples/skills/**/SKILL.md and exposes ONE `skill` tool over
;;      whatever it found                                      (SPEC §0.5/0.6)
;;   3. adds a plain Clojure function as a tool                    (SPEC §0.8)
;;   4. merges all three into one toolkit and calls one tool from each
;;
;; The point of step 4 is the whole library: to a model, an MCP tool, a skill
;; and your own function are the same thing — a named, described, schema'd
;; callable.

(ns toolnexus.demo
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.host :as host]
            [koine.json :as json]
            [koine.process :as proc]))

;; ---------------------------------------------------------------------------
;; 1. MCP — talk JSON-RPC to a server over its stdin/stdout
;; ---------------------------------------------------------------------------

(def ^:private ids (atom 0))

(defn- rpc!
  "One JSON-RPC round trip with the child. koine.process gives us line-oriented
  stdio on both hosts; nothing here knows what a Process or an os/exec.Cmd is."
  [child method params]
  (let [id (swap! ids inc)]
    (proc/send-line! child (json/write-str {:jsonrpc "2.0" :id id
                                            :method method :params params}))
    (loop [seen 0]
      (when (> seen 500)
        (throw (ex-info "mcp: no response" {:method method})))
      (let [line (proc/read-line! child)]
        (cond
          (nil? line)       (throw (ex-info "mcp: server exited" {:method method}))
          (str/blank? line) (recur (inc seen))
          :else             (let [msg (json/read-str line)]
                              (if (= id (:id msg)) msg (recur (inc seen)))))))))

(defn connect-mcp!
  "Spawn the server and complete the MCP handshake. Returns the live child."
  [command]
  (let [child (proc/spawn command)]
    (rpc! child "initialize"
          {:protocolVersion "2024-11-05"
           :capabilities    {}
           :clientInfo      {:name "toolnexus-clj" :version "0.1.0"}})
    (proc/send-line! child (json/write-str {:jsonrpc "2.0"
                                            :method  "notifications/initialized"
                                            :params  {}}))
    child))

;; A tool is just a map with an :execute fn. That uniformity is the library.
(defn mcp-tools
  "tools/list -> one Tool per server tool. Names are prefixed with the server's
  name and sanitized, so two servers can both export `search` (SPEC §0.2)."
  [child server-name]
  (->> (get-in (rpc! child "tools/list" {}) [:result :tools])
       (map (fn [t]
              {:name        (str (str/replace server-name #"[^a-zA-Z0-9_-]" "_")
                                 "_"
                                 (str/replace (:name t) #"[^a-zA-Z0-9_-]" "_"))
               :description (or (:description t) "")
               :source      "mcp"
               :execute     (fn [args]
                              (let [r (get-in (rpc! child "tools/call"
                                                    {:name (:name t) :arguments args})
                                              [:result])]
                                (str/join "\n" (keep :text (:content r)))))}))
       (sort-by :name)
       vec))

;; ---------------------------------------------------------------------------
;; 2. Agent skills — a folder of SKILL.md files behind one tool
;; ---------------------------------------------------------------------------

(defn- frontmatter
  "SKILL.md is YAML frontmatter + markdown body. We only need name/description,
  so this is a deliberate five-line parser, not a YAML dependency."
  [text]
  (let [lines (str/split-lines text)]
    (if-not (= "---" (str/trim (first lines)))
      [{} text]
      (loop [remaining (rest lines) meta {}]
        (cond
          (empty? remaining)                     [meta ""]
          (= "---" (str/trim (first remaining))) [meta (str/join "\n" (rest remaining))]
          :else
          (let [line (first remaining)
                idx  (str/index-of line ":")]
            (recur (rest remaining)
                   (if idx
                     (assoc meta (keyword (str/trim (subs line 0 idx)))
                            (str/trim (subs line (inc idx))))
                     meta))))))))

(defn discover-skills
  "Every **/SKILL.md under root, keyed by the skill's declared name."
  [root]
  (reduce (fn [acc path]
            (let [[meta body] (frontmatter (fs/read-file path))
                  nm          (:name meta)
                  dir         (if-let [i (str/last-index-of path "/")] (subs path 0 i) ".")]
              (if (or (str/blank? (str nm)) (contains? acc nm))
                acc
                (assoc acc nm {:name        nm
                               :description (or (:description meta) "")
                               :body        (str/trim body)
                               :dir         dir
                               :files       (vec (sort (remove fs/directory?
                                                               (fs/list-tree dir))))}))))
          {}
          (fs/find-files root "SKILL.md")))

(defn skill-tool
  "ONE tool for ALL skills — progressive disclosure. The model sees the skill
  names up front and pays for a skill's instructions only when it asks."
  [skills]
  {:name        "skill"
   :description (str "Load a skill on demand. Available: "
                     (str/join ", " (sort (keys skills))))
   :source      "skill"
   :execute     (fn [args]
                  (if-let [s (get skills (str (:name args)))]
                    (str "<skill_content name=\"" (:name s) "\">\n"
                         "# Skill: " (:name s) "\n\n"
                         (:body s) "\n\n"
                         "Base directory for this skill: file://" (:dir s) "\n"
                         "<skill_files>\n"
                         (str/join "" (map #(str "<file>" % "</file>\n") (:files s)))
                         "</skill_files>\n"
                         "</skill_content>")
                    (str "unknown skill: " (:name args))))})

;; ---------------------------------------------------------------------------
;; 3. Native — your own function, same shape as everything above
;; ---------------------------------------------------------------------------

(defn native-tool [tool-name description f]
  {:name tool-name :description description :source "native"
   :execute (fn [args] (str (f args)))})

;; ---------------------------------------------------------------------------
;; 4. The toolkit — three sources, one flat namespace
;; ---------------------------------------------------------------------------

(defn toolkit [tools]
  (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools))

(defn call-tool
  "A failing tool is a result, never a crash: the loop that drives these has to
  survive a broken server (SPEC §0.3)."
  [tk tool-name args]
  (if-let [t (get tk tool-name)]
    (try ((:execute t) args)
         (catch Throwable e (str "tool error: " (ex-message e))))
    (str "unknown tool: " tool-name)))

;; ---------------------------------------------------------------------------
;; the demo
;; ---------------------------------------------------------------------------

(defn- line [] (println "---------------------------------------------------------------"))

(defn- show-call [tk label tool-name args render]
  (println (str "  [" label "] " tool-name " " (json/write-str args)))
  (println (str "      -> " (render (call-tool tk tool-name args)))))

(defn run-demo [examples-dir]
  (let [;; the shared cross-language fixture — the same mcp.json the JS,
        ;; Python, Go, Java, C# and Elixir ports run against
        cfg    (json/read-str (fs/read-file (str examples-dir "/mcp.json")))
        server (->> (:mcpServers cfg)
                    (map (fn [e] (assoc (val e) :name (name (key e)))))
                    (filter #(and (:command %) (not (false? (:enabled %)))))
                    first)
        child  (connect-mcp! (vec (:command server)))
        skills (discover-skills (str examples-dir "/skills"))
        tk     (toolkit (concat (mcp-tools child (:name server))
                                [(skill-tool skills)
                                 (native-tool "shout" "Uppercase some text."
                                              (fn [args] (str/upper-case (str (:text args)))))]))]
    (try
      (println (str "toolnexus demo   runtime: " (name host/id)))
      (line)
      (println (str "1. MCP server \"" (:name server) "\"  <- examples/mcp.json"))
      (println (str "   " (str/join " " (:command server))))
      (println (str "   " (count (filter #(= "mcp" (:source %)) (vals tk))) " tools discovered"))
      (println (str "2. Agent skills  <- examples/skills/**/SKILL.md"))
      (println (str "   " (count skills) " skill(s): " (str/join ", " (sort (keys skills)))))
      (println    "3. Native tool   <- a plain Clojure fn")
      (println    "   shout")
      (line)
      (println (str "unified toolkit — " (count tk) " tools, one namespace:"))
      (doseq [nm (sort (keys tk))]
        (println (str "   " nm "   (" (:source (get tk nm)) ")")))
      (line)
      (println "one call per source:")
      (show-call tk "mcp" (str (:name server) "_echo") {:message "hello from Clojure"}
                 identity)
      (show-call tk "skill" "skill" {:name "hello-world"}
                 (fn [out] (str (first (str/split-lines out))
                                "  … " (count out) " bytes of instructions injected")))
      (show-call tk "native" "shout" {:text "same file, both runtimes"}
                 identity)
      (line)
      (println "three sources, one interface. that is the whole idea.")
      (finally
        (proc/close! child)))))

(defn -main [& _]
  ;; Both projects sit one level under clojure/examples/, so the default
  ;; reaches the repo's shared fixtures from either of them.
  (run-demo (or (env/get-env "TN_EXAMPLES") "../../../examples"))
  ;; The success marker every example in clj/run.sh and cljgo/run.sh is judged
  ;; on. A demo that printed most of its report and then died would otherwise
  ;; exit 0 and look identical to one that finished.
  (println "OK"))
