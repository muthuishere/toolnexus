;; SPEC §4A + §0.11 — the built-in coding toolset (source: "builtin").
;;
;; The ten default tools — bash, read, write, edit, grep, glob, webfetch,
;; question, apply_patch, todowrite — with the §4A names and input schemas, plus
;; §0.11's toggle semantics.
;;
;; Ported from spike s22, which proved the whole set byte-identical on Clojure
;; (JVM) and cljgo. Two things s22 could NOT do, and koine 0.6.0 now can:
;;
;;   1. `bash`'s timeout is real. `koine.process/sh` takes `:timeout-ms`; a
;;      killed command comes back `{:timed-out? true :exit nil}` — nil, not a
;;      number, because the JVM would say 137 and Go -1 and koine refuses to
;;      invent agreement. `:timed-out?` is ALWAYS present, so it is tested
;;      unconditionally and BEFORE `:exit`.
;;   2. `mkdir`/`delete` no longer shell out. s22 ran `mkdir -p` / `rm -f`,
;;      which is portable across HOSTS but not across OPERATING SYSTEMS.
;;      `koine.fs/mkdirs!` and `koine.fs/delete!` are syscalls on both hosts.
;;
;; Every implementation is named `t-*`: `read` is `clojure.core/read`, and
;; cljgo's static Java-interop scan rejects a WHOLE namespace over one shadowed
;; core symbol. Zero reader conditionals, zero `java.*`, zero Go interop.
(ns toolnexus.builtin
  (:require [clojure.string :as str]
            [koine.env :as kenv]
            [koine.fs :as fs]
            [koine.http :as khttp]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [koine.text :as text]
            [toolnexus.content :as content]
            [toolnexus.tool :as tool]))

(defn- suspend
  "§10 — a ToolResult whose metadata.pending is a Request is a suspension.

  isError is TRUE, per §10's ToolResult block: the call \"did not produce a
  usable answer\". That does NOT contradict §10's later \"a suspension is never
  a tool error\" — that sentence is about the `tool` OBSERVABILITY EVENT, which
  carries isError:false plus a pending:true marker so error-rate metrics and
  circuit-breakers do not count it. Two different objects, two different flags.

  This namespace originally produced isError:false here while `client/suspend`
  produced true. Both worked, because the loop branches on metadata.pending
  alone — which is exactly why it would have drifted unnoticed."
  [request]
  (tool/failure "Waiting for a response." {:pending request}))

;; ---------------------------------------------------------------------------
;; small pure helpers
;; ---------------------------------------------------------------------------

(def utf8-count
  "UTF-8 byte length of `s` — `koine.text/utf8-length`.

  The hand-rolled version here was WRONG on both hosts (8 and 5 bytes for a
  6-byte answer — it folded code units, not code points) and was fixed locally
  before koine 0.11.0 lifted the correct fold into the seam. Delegating keeps
  one implementation; the regression test in builtin_test stays here, guarding
  the call site rather than trusting the seam blindly."
  text/utf8-length)

(defn- index-all
  "Every index at which `sub` occurs in `s`, left to right."
  [s sub]
  (if (str/blank? sub)
    []
    (loop [from 0 acc []]
      (let [j (str/index-of s sub from)]
        (if (nil? j) acc (recur (+ (long j) (count sub)) (conj acc j)))))))

(defn- replace-all-literal [s old new]
  (if (str/blank? old)
    s
    (loop [rest-s s acc ""]
      (let [j (str/index-of rest-s old)]
        (if (nil? j)
          (str acc rest-s)
          (recur (subs rest-s (+ (long j) (count old)))
                 (str acc (subs rest-s 0 (long j)) new)))))))

(defn- replace-first-literal [s old new]
  (let [j (str/index-of s old)]
    (if (nil? j) s (str (subs s 0 (long j)) new (subs s (+ (long j) (count old)))))))

;; -- glob, matched structurally rather than via a translated regex -----------
;; Top-level defn- rather than letfn: named self-recursion is the shape every
;; host agrees on, and the AOT pass sees an ordinary fn. Structural matching
;; also sidesteps the regex-dialect gap (java.util.regex vs Go RE2).

(defn- seg-match*
  "`*` (any run within a segment) and `?` (one char) against one path segment."
  [pat s pi si]
  (cond
    (and (= pi (count pat)) (= si (count s))) true
    (= pi (count pat))                        false
    (= \* (nth pat pi))                       (or (seg-match* pat s (inc pi) si)
                                                  (and (< si (count s))
                                                       (seg-match* pat s pi (inc si))))
    (= si (count s))                          false
    (or (= \? (nth pat pi))
        (= (nth pat pi) (nth s si)))          (seg-match* pat s (inc pi) (inc si))
    :else                                     false))

(defn- glob-match* [p q pi qi]
  (cond
    (= pi (count p))    (= qi (count q))
    (= "**" (nth p pi)) (or (glob-match* p q (inc pi) qi)
                            (and (< qi (count q)) (glob-match* p q pi (inc qi))))
    (= qi (count q))    false
    (seg-match* (nth p pi) (nth q qi) 0 0) (glob-match* p q (inc pi) (inc qi))
    :else               false))

(defn glob-match?
  "`**` crosses separators, `*`/`?` do not — so a recursive sweep is `**/*.md`."
  [pattern path]
  (glob-match* (vec (str/split (str pattern) #"/"))
               (vec (str/split (str path) #"/"))
               0 0))

(defn- files-under
  "Every FILE under `root`, sorted. `koine.fs/list-tree` is unordered per host,
  so the sort is what makes any listing tool comparable across hosts."
  [root]
  (->> (fs/list-tree root)
       (map str)
       (remove fs/directory?)
       ;; tool/sort-strings, not `sort` — glob output must not depend on the host.
       tool/sort-strings))

(defn- rel-path
  "`p` relative to `root`, always `/`-separated (A28).

  The separator is normalised because this string is BOTH the sort key and the
  emitted output: a port that orders by one string and displays another is
  ordering by one thing and showing another, and cross-port byte-identity needs
  the separator to match on every platform. The JS host emits `/` natively while
  a JVM host on Windows would emit `\\`, so without this the two hosts of THIS
  PORT could disagree on shipped text. js does the same thing, explicitly
  (`toPosixPath(path.relative(root, file), path.sep)`)."
  [root p]
  (let [root (str/replace (str root) "\\" "/")
        p    (str/replace (str p) "\\" "/")
        pfx  (if (str/ends-with? root "/") root (str root "/"))]
    (if (str/starts-with? p pfx) (subs p (count pfx)) p)))

(defn- parent-of [p]
  ;; the text seam — `clojure.string/last-index-of` is a BYTE offset on cljgo,
  ;; so a non-ASCII directory path was cut mid-name there.
  (let [i (tool/last-index-of-char p \/)]
    (when i (subs (str p) 0 (long i)))))

;; ---------------------------------------------------------------------------
;; §4A — the ten input SCHEMAS. This is the contract.
;; ---------------------------------------------------------------------------

(def bash-schema
  {:type "object"
   :properties {:command     {:type "string"  :description "The shell command to run"}
                :workdir     {:type "string"  :description "Working directory (default: process cwd)"}
                :timeout     {:type "number"  :description "Timeout in milliseconds" :default 60000}
                :description {:type "string"  :description "What this command does"}}
   :required ["command"]})

(def read-schema
  {:type "object"
   :properties {:path   {:type "string" :description "Path to the file to read"}
                :offset {:type "number" :description "1-based line to start from"}
                :limit  {:type "number" :description "Number of lines to read"}}
   :required ["path"]})

(def write-schema
  {:type "object"
   :properties {:path    {:type "string" :description "Path to write to"}
                :content {:type "string" :description "Content to write"}}
   :required ["path" "content"]})

(def edit-schema
  {:type "object"
   :properties {:path       {:type "string"  :description "Path to the file to edit"}
                :oldString  {:type "string"  :description "Exact text to replace"}
                :newString  {:type "string"  :description "Replacement text"}
                :replaceAll {:type "boolean" :description "Replace every occurrence"}}
   :required ["path" "oldString" "newString"]})

(def grep-schema
  {:type "object"
   :properties {:pattern {:type "string" :description "Regular expression to search for"}
                :path    {:type "string" :description "Directory to search (default: cwd)"}
                :include {:type "string" :description "Glob filter on file paths"}
                :limit   {:type "number" :description "Maximum matches" :default 100}
                :context {:type "number" :description "Lines of context before and after each match"}
                :before  {:type "number" :description "Lines of context before each match (overrides context)"}
                :after   {:type "number" :description "Lines of context after each match (overrides context)"}}
   :required ["pattern"]})

(def glob-schema
  {:type "object"
   :properties {:pattern {:type "string" :description "Glob pattern to match"}
                :path    {:type "string" :description "Directory to search (default: cwd)"}
                :limit   {:type "number" :description "Maximum results" :default 100}}
   :required ["pattern"]})

(def webfetch-schema
  {:type "object"
   :properties {:url     {:type "string" :description "URL to fetch"}
                :format  {:type "string" :description "Output format"
                          :enum ["text" "markdown" "html"] :default "markdown"}
                :timeout {:type "number" :description "Timeout in seconds" :default 30}}
   :required ["url"]})

(def question-schema
  {:type "object"
   :properties {:questions
                {:type "array"
                 :description "Questions to ask the human"
                 :items {:type "object"
                         :properties {:question {:type "string"}
                                      :header   {:type "string"}
                                      :options  {:type "array" :items {:type "string"}}
                                      :multiple {:type "boolean"}}
                         :required ["question"]}}}
   :required ["questions"]})

(def apply-patch-schema
  {:type "object"
   :properties {:patchText {:type "string" :description "The patch to apply"}}
   :required ["patchText"]})

(def todowrite-schema
  {:type "object"
   :properties {:todos
                {:type "array"
                 :description "The full todo list, replacing the current one"
                 :items {:type "object"
                         :properties {:id        {:type "string"}
                                      :text      {:type "string"}
                                      :completed {:type "boolean"}}
                         :required ["id" "text" "completed"]}}}
   :required ["todos"]})

;; ---------------------------------------------------------------------------
;; §4A — behaviour
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; the host boundary — interpreter, base directory, confinement (SPEC §4A, ADR 0034)
;; ---------------------------------------------------------------------------

(def kill-grace-ms
  "The DEFAULT wait between \"please stop\" and \"stop\" (SPEC §4A `killGraceMs`),
  identical in every port. A host overrides it with `:kill-grace-ms` /
  `:killGraceMs` on the builtins config; absent or 0 means this value."
  2000)

(defn- grace-of [m]
  (let [v (or (:kill-grace-ms m) (:killGraceMs m))]
    (if (and (number? v) (pos? v)) (long v) kill-grace-ms)))

(def ^:private win-reserved
  #{"CON" "PRN" "AUX" "NUL"
    "COM1" "COM2" "COM3" "COM4" "COM5" "COM6" "COM7" "COM8" "COM9"
    "LPT1" "LPT2" "LPT3" "LPT4" "LPT5" "LPT6" "LPT7" "LPT8" "LPT9"})

(defn- windows?
  "Windows is detected from the environment, not from a host API: `os.name` is
  `java.*` and `runtime.GOOS` is Go interop, and this namespace may use neither.
  COMSPEC/SystemRoot are set on every Windows and on no POSIX system."
  []
  (boolean (or (kenv/get-env "COMSPEC") (kenv/get-env "SystemRoot"))))

(defn- path-sep [] (if (windows?) ";" ":"))

(defn- resolves-on-path?
  "Does `name` resolve as an executable, absolutely or on PATH? A PATH walk,
  because there is no portable `which` available from a .cljc."
  [name]
  (if (or (str/includes? name "/") (str/includes? name "\\"))
    (fs/exists? name)
    (let [exts (if (windows?)
                 (str/split (or (kenv/get-env "PATHEXT") ".COM;.EXE;.BAT;.CMD") #";")
                 [""])]
      (boolean
       (some (fn [dir]
               (some (fn [ext] (fs/exists? (str dir "/" name ext))) exts))
             (remove str/blank? (str/split (or (kenv/get-env "PATH") "") (re-pattern (path-sep)))))))))

(defn shell-candidates
  "The interpreters tried when the host names none. `%COMSPEC%` leads on Windows
  because PowerShell is routinely blocked by execution or application-control
  policy, while `%COMSPEC%` is always present."
  []
  (if (windows?)
    (let [comspec (kenv/get-env "COMSPEC")]
      (vec (concat (when comspec [[comspec "/d" "/s" "/c"]])
                   [["cmd.exe" "/d" "/s" "/c"]
                    ["pwsh" "-NoProfile" "-Command"]
                    ["powershell" "-NoProfile" "-Command"]
                    ["bash" "-lc"]])))
    [["/bin/sh" "-c"] ["sh" "-c"]]))

(defn builtin-env
  "The host boundary, resolved once: which interpreter to run, which directory
  relative paths mean, and whether to refuse the ones that leave it. A missing
  interpreter is a configuration fact, and turn fourteen of a paid run is the
  expensive place to learn it."
  [cfg]
  (let [m        (if (map? cfg) cfg {})
        base-dir (str (or (:base-dir m) (:baseDir m) ""))
        confine  (boolean (or (:confine-to-base-dir m) (:confineToBaseDir m)))
        given    (:shell m)
        grace    (grace-of m)
        ;; SPEC §4C opt-in tools — absent ⇒ none of them exists.
        extras   {:background   (:background m)
                  :web-search   (or (:web-search m) (:webSearch m))
                  :notebook-edit (boolean (or (:notebook-edit m) (:notebookEdit m)))}]
    (if (seq given)
      (merge extras
             {:shell (mapv str given) :shell-error nil :base-dir base-dir :confine confine
              :kill-grace-ms grace})
      (let [cands (shell-candidates)
            found (first (filter (fn [argv] (resolves-on-path? (first argv))) cands))]
        (merge extras
        {:shell (or found [])
         :shell-error (when-not found
                        (str "no shell interpreter found (tried: "
                             (str/join ", " (map first cands))
                             "); set :shell on the builtins config, or disable the bash "
                             "builtin with {:tools {\"bash\" false}}"))
         :base-dir base-dir
         :confine confine
         :kill-grace-ms grace})))))

(defn shell
  "The interpreter the builtins resolved for `bash` — what a host prints, and
  what `metadata.shell` carries on every bash result."
  ([] (shell nil))
  ([cfg] (let [e (builtin-env cfg)]
           (if (:shell-error e) [:error (:shell-error e)] [:ok (:shell e)]))))

(defn- env-dir [e]
  (if (str/blank? (:base-dir e)) "." (:base-dir e)))

(defn- absolute-path? [p]
  (or (str/starts-with? p "/")
      (boolean (re-find #"^[A-Za-z]:[\\/]" p))
      (str/starts-with? p "\\\\")))

(defn- reserved-device? [p]
  (let [base (last (str/split p #"[/\\]"))
        stem (str/upper-case (str/trim (first (str/split (str base) #"\\."))))]
    (contains? win-reserved stem)))

(defn- canonical
  "Resolve a path for comparison, component by component — a link in the MIDDLE
  of a path is the escape a leaf-only check misses. A component that does not
  exist yet is kept as written, because a file `write` is about to create has no
  real path and a check that only works on existing files is not a check for
  `write`.

  `koine.fs/real-path` throws on a missing path, which is exactly the signal the
  deepest-existing-ancestor walk needs. Its result is LOWER-CASED, because the
  two clojure hosts disagree on case (the JVM normalises, cljgo does not), and
  one path yielding two confinement verdicts is the drift this repo exists to
  prevent."
  [p]
  (let [segs (remove str/blank? (str/split (str p) #"[/\\]"))
        root (if (absolute-path? (str p)) "/" "")]
    (str/lower-case
     (reduce (fn [acc seg]
               (let [joined (if (str/blank? acc) (str root seg) (str acc "/" seg))]
                 (if (fs/exists? joined) (fs/real-path joined) joined)))
             root
             segs))))

(defn- resolve-path
  "Map a tool-supplied path onto the filesystem: relative to `:base-dir` (or,
  with none, exactly as before), and refused when confinement is on and the
  canonical target lies outside the base. Returns `[:ok full]` or
  `[:error message]` — never throws, because a builtin reports."
  [e p]
  (let [base (:base-dir e)]
    (cond
      (and (:confine e) (str/blank? base))
      [:error "confine-to-base-dir is set but base-dir is empty"]

      :else
      (let [full (if (and (not (str/blank? base)) (not (absolute-path? p)))
                   (str base "/" p)
                   p)]
        (cond
          (not (:confine e)) [:ok full]

          (and (windows?) (reserved-device? full))
          [:error (str p " names a reserved device, which is not a file inside " base)]

          :else
          (let [cb (canonical base)
                ct (canonical full)]
            (if (or (= ct cb) (str/starts-with? ct (str cb "/")))
              [:ok full]
              [:error (str p " resolves outside baseDir " base)])))))))

(def default-bash-timeout-ms
  "§4A: `timeout?:number(ms, default 60000)`."
  60000)

(def ^:private digit->int
  {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})

(defn- parse-pid
  "A pid from a string of digits, or nil. Hand-rolled: `parse-long` is
  clojure.core 1.11+ on the JVM and unverified on cljgo, and this namespace may
  not branch per host."
  [s]
  (let [cs (seq (str/trim (str s)))]
    (when (and (seq cs) (every? digit->int cs))
      (reduce (fn [acc c] (+ (* 10 acc) (digit->int c))) 0 cs))))

(defn- ps-pairs
  "Every (pid, ppid) on the machine. A `ps` shell-out because neither host
  exposes a process table to a .cljc, and because the alternative — trusting a
  process-group trick — was measured to fail under dash (see ADR 0034 D4)."
  []
  (let [r (proc/sh ["ps" "-eo" "pid=,ppid="] {:timeout-ms 5000})]
    (if (and (not (:timed-out? r)) (= 0 (long (or (:exit r) -1))))
      (->> (str/split-lines (str (:out r)))
           (keep (fn [line]
                   (let [parts (remove str/blank? (str/split (str/trim line) #"\s+"))]
                     (when (= 2 (count parts))
                       (let [a (parse-pid (first parts)) b (parse-pid (second parts))]
                         (when (and a b) [a b]))))))
           (remove (fn [[a b]] (or (nil? a) (nil? b))))
           vec)
      [])))

(defn child-pids
  "Every live descendant of `root`, captured WHILE THE PARENT IS ALIVE — after it
  dies its children are reparented and are unreachable from its pid (measured,
  spikes/builtin-host-boundary/SPIKE.md §1.1)."
  [root]
  (let [pairs (ps-pairs)]
    (loop [frontier [root] acc []]
      (let [kids (vec (for [[p pp] pairs :when (and (some #(= % pp) frontier)
                                                    (not (some #(= % p) acc))
                                                    (not= p root))] p))]
        (if (empty? kids) acc (recur kids (into acc kids)))))))

(defn- signal-job
  "Ask (`-TERM`) or insist (`-KILL`) on every pid of the job."
  [pids sig]
  (doseq [pid pids]
    (if (windows?)
      ;; Windows has NO graceful termination for a console process: `taskkill /T`
      ;; without `/F` refuses every console process in the tree while still being
      ;; able to take the PARENT down, which reparents the grandchild. Both steps
      ;; force there; the grace window is a POSIX effect.
      (proc/sh ["taskkill" "/T" "/F" "/PID" (str pid)] {:timeout-ms 5000})
      (proc/sh ["kill" sig (str pid)] {:timeout-ms 5000}))))

(defn- alive-pid?
  "Is `pid` still running? `kill -0` is the portable liveness test; it signals
  nothing. On Windows `tasklist` is the equivalent question."
  [pid]
  (if (windows?)
    (let [r (proc/sh ["tasklist" "/FI" (str "PID eq " pid)] {:timeout-ms 5000})]
      (str/includes? (str (:out r)) (str pid)))
    (= 0 (long (or (:exit (proc/sh ["kill" "-0" (str pid)] {:timeout-ms 5000})) -1)))))

(defn- await-exit
  "Poll until every pid is gone, or `budget-ms` passes. Returns true if the job
  ended within the budget."
  [pids budget-ms]
  (let [deadline (+ (ktime/mono-ms) budget-ms)]
    (loop []
      (cond
        (every? (fn [p] (not (alive-pid? p))) pids) true
        (>= (ktime/mono-ms) deadline) false
        :else (do (ktime/sleep! 20) (recur))))))

;; ---------------------------------------------------------------------------
;; SPEC §4C opt-in: background shells (bash run_in_background, bash_output,
;; kill_shell). The host owns the registry and calls `bg-close-all!` on shutdown
;; — a background command is NOT tied to the turn that started it.
;; ---------------------------------------------------------------------------

(defn background-shells
  "An empty background-shell registry. Pass it as the builtins config's
  `:background`; `bash` then takes `run_in_background` and `bash_output` /
  `kill_shell` exist. POSIX only (the job is found through its own pid)."
  []
  (atom {:next 0 :jobs {}}))

(defn- bg-running? [j] (not (realized? (:done j))))

;; The host reports a shell killed by signal N as 128 + N; Go reports -1 (killed
;; by a signal). Map 129..159 to -1 so every port prints the same code. Known
;; limit: a command that itself exits 129..159 also reads as -1.
(defn- signal-code [code]
  (if (and (> code 128) (< code 160)) -1 code))

(defn- bg-info [j]
  (let [running (bg-running? j)
        r       (when-not running @(:done j))]
    {:id (:id j) :command (:command j) :started (:started j)
     :running running
     :exit-code (when-not running (let [x (:exit r)] (if (number? x) (signal-code (long x)) -1)))
     :killed @(:killed j)}))

(defn- bg-status [i]
  (cond (:running i) "running"
        (:killed i)  "killed"
        :else        (str "exited with code " (:exit-code i))))

(defn- bg-start!
  "Start `command` detached from the turn. Output goes to a file so `bash_output`
  can return what arrived since the last read; the shell records its own pid
  first, exactly as the foreground `bash` does, so the whole job can be killed."
  [e command workdir]
  (let [reg     (:background e)
        dir     (fs/temp-dir! "tn-bg")
        pidfile (str dir "/pid")
        outfile (str dir "/out")
        _       (fs/write-file outfile "")
        script  (str "echo $$ > '" pidfile "'; exec > '" outfile "' 2>&1; " command)
        done    (promise)
        id      (str "bg" (:next (swap! reg update :next inc)))
        job     {:id id :command command :started (ktime/now-ms) :done done
                 :pidfile pidfile :outfile outfile :read (atom 0) :killed (atom false)
                 :grace (or (:kill-grace-ms e) kill-grace-ms)}]
    (swap! reg assoc-in [:jobs id] job)
    (proc/run-async!
     (fn []
       (deliver done (try (proc/sh (conj (vec (:shell e)) script)
                                   (cond-> {} workdir (assoc :dir workdir)))
                          (catch Throwable t {:exit -1 :err (or (ex-message t) (str t))})))))
    (tool/success (str "Started background shell " id
                       ". Read its output with bash_output, stop it with kill_shell.")
                  {:shell (str/join " " (:shell e)) :backgroundId id})))

(defn- bg-pid [j]
  (let [deadline (+ (ktime/mono-ms) 2000)]
    (loop []
      (let [pid (when (fs/exists? (:pidfile j)) (parse-pid (fs/read-file (:pidfile j))))]
        (cond pid pid
              (or (not (bg-running? j)) (>= (ktime/mono-ms) deadline)) nil
              :else (do (ktime/sleep! 10) (recur)))))))

(defn- bg-kill-job!
  "Stop the job's whole tree: SIGTERM, the grace window, SIGKILL. Returns whether
  it was still running."
  [j]
  (if-not (bg-running? j)
    false
    (let [_    (reset! (:killed j) true)
          pid  (bg-pid j)
          pids (when pid (into [pid] (child-pids pid)))]
      (when (seq pids)
        (signal-job pids "-TERM")
        (when-not (await-exit pids (:grace j))
          (signal-job pids "-KILL")))
      ;; the shell is gone; let the async sh deliver, bounded
      (let [deadline (+ (ktime/mono-ms) 5000)]
        (loop []
          (when (and (bg-running? j) (< (ktime/mono-ms) deadline))
            (ktime/sleep! 10) (recur))))
      true)))

(defn bg-list
  "Every background command, oldest first: `[{:id :command :started :running
  :exit-code :killed}]` (`:exit-code` is nil while running, -1 for a signal)."
  [reg]
  (->> (vals (:jobs @reg))
       (sort-by (juxt :started (fn [j] (parse-pid (subs (:id j) 2)))))
       (mapv bg-info)))

(defn bg-kill!
  "Stop one background command's tree. Unknown id ⇒ throws."
  [reg id]
  (if-let [j (get-in @reg [:jobs id])]
    (do (bg-kill-job! j) nil)
    (throw (ex-info (str "no background shell " (pr-str id)) {:id id}))))

(defn bg-close-all!
  "Stop every background command still running, concurrently."
  [reg]
  (let [ps (mapv (fn [j] (let [p (promise)]
                           (proc/run-async! (fn [] (deliver p (try (bg-kill-job! j) (catch Throwable _ nil)))))
                           p))
                 (vals (:jobs @reg)))]
    (doseq [p ps] (deref p))
    nil))

(defn- t-bash-output [e args _ctx]
  (let [id (str (:id args))
        j  (get-in @(:background e) [:jobs id])]
    (if-not j
      (tool/failure (str "bash_output: no background shell " (pr-str id)))
      ;; status FIRST: when the job has finished, its output file is complete, so
      ;; a reader that stops on "exited" never misses the last line.
      (let [info   (bg-info j)
            all    (str (fs/read-file (:outfile j)))
            from   (min @(:read j) (count all))
            fresh  (subs all from)
            _      (reset! (:read j) (count all))
            status (bg-status info)]
        (tool/success (str fresh "\n[status: " status "]")
                      (cond-> {:id id :status (first (str/split status #" " 2))}
                        (and (not (:running info)) (not (:killed info)))
                        (assoc :exitCode (:exit-code info))))))))

(defn- t-kill-shell [e args _ctx]
  (let [id (str (:id args))
        j  (get-in @(:background e) [:jobs id])]
    (cond
      (nil? j) (tool/failure (str "kill_shell: no background shell " (pr-str id)))
      (bg-kill-job! j) (tool/success (str "Killed background shell " id) {:id id :killed true})
      :else (tool/success (str "Background shell " id " had already " (bg-status (bg-info j)))
                          {:id id :killed false}))))

(defn- t-bash
  "Combined stdout+stderr. Non-zero exit ⇒ isError with the exit code appended.
  A timeout kills THE WHOLE JOB ⇒ isError.

  The deadline is enforced here rather than by `koine.process/sh`'s `:timeout-ms`,
  and that is the whole fix: koine performs its kill INSIDE the library, on the
  direct child only, and exposes no pid — so by the time it returns, the shell is
  gone, its children have been reparented, and there is nothing left to signal.
  Running the command on a background thread with our own deadline means the
  child is still alive when its tree is enumerated (ADR 0034 D4).

  `:timed-out?` is still checked FIRST and unconditionally on koine's result: on
  a kill `:exit` is nil, not a number, so `(zero? (:exit r))` would blow up (or,
  worse, a port that defaults nil to 0 would read a kill as a clean run)."
  [e args _ctx]
  (let [ms      (long (or (:timeout args) default-bash-timeout-ms))
        command (str (:command args))]
    (cond
      (str/blank? command)
      (tool/failure "bash: command is required")

      (:shell-error e)
      (tool/failure (str "bash: " (:shell-error e)))

      :else
      (let [[status workdir] (if (:workdir args)
                               (resolve-path e (str (:workdir args)))
                               [:ok (env-dir e)])]
        (if (= status :error)
          (tool/failure (str "bash: " workdir))
          (if (and (:background e) (true? (:run_in_background args)))
            (bg-start! e command workdir)
          (let [pidfile (str (fs/temp-dir! "tn-bash") "/pid")
                argv    (:shell e)
                ;; The shell records its OWN pid before doing anything else, so
                ;; the job can be found without a process group — which is not
                ;; portable here: `set -m` fails outright under dash.
                script  (if (windows?)
                          command
                          (str "echo $$ > " pidfile "; " command))
                result  (promise)
                meta    {:shell (str/join " " argv)}]
            (proc/run-async!
             (fn []
               (deliver result
                        (proc/sh (conj (vec argv) script)
                                 (cond-> {} workdir (assoc :dir workdir))))))
            (let [deadline (+ (ktime/mono-ms) ms)
                  r (loop []
                      (cond
                        (realized? result) @result
                        (>= (ktime/mono-ms) deadline) nil
                        :else (do (ktime/sleep! 20) (recur))))]
              (if (nil? r)
                ;; Timed out. Snapshot the tree while the shell is STILL ALIVE,
                ;; then ask, wait out the grace window, and insist.
                (let [pid  (when (fs/exists? pidfile) (parse-pid (fs/read-file pidfile)))
                      pids (when pid (into [pid] (child-pids pid)))]
                  (when (seq pids) (signal-job pids "-TERM"))
                  ;; WAIT for the job to go, up to the grace window — do not
                  ;; SLEEP through it. The window bounds how long the kill may
                  ;; take, not how long the caller waits: a command that dies on
                  ;; SIGTERM in 5 ms must not cost its caller two seconds.
                  ;; Measured parity break — five ports returned a 1 s timeout in
                  ;; ~1.0 s while this one took ~3.05 s.
                  (when (and (seq pids) (not (await-exit pids (or (:kill-grace-ms e) kill-grace-ms))))
                    (signal-job pids "-KILL"))
                  (tool/failure (str "command timed out after " ms "ms")
                                (assoc meta :timedOut true :killedTree (boolean (seq pids)))))
                (let [out (str (:out r) (:err r))]
                  (cond
                    (true? (:timed-out? r))
                    (tool/failure (str out "command timed out after " ms "ms")
                                  (assoc meta :timedOut true :killedTree false))

                    (zero? (long (:exit r)))
                    (tool/success out (assoc meta :exitCode 0))

                    :else
                    (tool/failure (str out "exit code " (:exit r))
                                  (assoc meta :exitCode (:exit r))))))))))))))

(defn- valid-utf8?
  "Is `bs` a well-formed UTF-8 byte sequence?

  Hand-rolled because there is nothing else: `koine.fs/read-file` is `slurp`,
  which REPLACES a malformed byte with U+FFFD — identically on both hosts — so it
  can never report the failure §4A requires `read` to report. Decoding through
  host interop is not available to a `.cljc` with zero `java.*`. The walk is over
  UNSIGNED values (`bit-and 255`), which is how koine folds the JVM's signed
  bytes and Go's unsigned ones together."
  [bs]
  (let [v (vec (map (fn [b] (bit-and (long b) 255)) (seq bs)))
        n (count v)
        cont? (fn [i] (and (< i n) (= 128 (bit-and (nth v i) 192))))]
    (loop [i 0]
      (if (>= i n)
        true
        (let [b (nth v i)
              [len lo hi]
              (cond
                (< b 128)                    [1 0 0]
                (= 192 (bit-and b 224))      [2 128 2047]
                (= 224 (bit-and b 240))      [3 2048 65535]
                (= 240 (bit-and b 248))      [4 65536 1114111]
                :else                        nil)]
          (cond
            (nil? len) false
            (= 1 len)  (recur (inc i))
            (not (every? cont? (range (inc i) (+ i len)))) false
            :else
            (let [cp (reduce (fn [acc j] (+ (* acc 64) (bit-and (nth v j) 63)))
                             (bit-and b (case len 2 31 3 15 4 7))
                             (range (inc i) (+ i len)))]
              ;; over-long encodings and the UTF-16 surrogate range are malformed
              (if (or (< cp lo) (> cp hi) (and (>= cp 55296) (<= cp 57343)))
                false
                (recur (+ i len))))))))))

(defn- t-read [e args _ctx]
  (let [raw   (str (:path args))
        [st p] (resolve-path e raw)
        media (content/media-type-for (str p))]
    (cond
      (= st :error)
      (tool/failure (str "read: " p))

      (not (fs/exists? p))
      (tool/failure (str "read: file not found: " raw))

      ;; §4A + §1B: a recognised media extension comes back as a CONTENT PART.
      ;; The mime type is the fixed table's, never sniffed and never resolved
      ;; through a platform mime database — `/etc/mime.types` varies per machine
      ;; and would break the byte-identical cross-port fixture.
      media
      (let [bs   (fs/read-bytes p)
            part (content/from-bytes bs (:mimeType media) {:name (content/base-name p)})]
        (if (content/error-part? part)
          (tool/failure (str "read: " (:error part)))
          (tool/with-parts
            ;; §1B pins this string byte-identically across the seven ports.
            (tool/success (str p " (" (:mimeType media) ", "
                               (content/part-bytes part) " bytes)"))
            [(assoc part :name (content/base-name p))])))

      ;; §4A: undecodable bytes are an ERROR RESULT naming the file — never a
      ;; raised exception escaping `execute` into the loop, and never the silent
      ;; U+FFFD replacement `slurp` would otherwise hand the model.
      (not (valid-utf8? (fs/read-bytes p)))
      (tool/failure (str "read: " p " is not valid UTF-8 text"))

      :else
      (let [text (str (fs/read-file p))
            off  (:offset args)
            lim  (:limit args)]
        (if (and (nil? off) (nil? lim))
          (tool/success text)
          (let [lines (str/split-lines text)
                start (max 0 (dec (long (or off 1))))
                win   (drop start lines)
                win   (if lim (take (long lim) win) win)
                end   (min (count lines) (+ start (count win)))
                total (count lines)]
            ;; SPEC §4C: a window that stops before the end says so in METADATA,
            ;; with the offset to continue from. The text stays the pinned
            ;; cross-port bytes — the caller asked for exactly this window.
            (if (< end total)
              (tool/success (str/join "\n" win)
                            {:truncated true :totalLines total :nextOffset (inc end)})
              (tool/success (str/join "\n" win)))))))))

;; ---------------------------------------------------------------------------
;; SPEC §4A edit diffs — metadata.files + metadata.diff
;; ---------------------------------------------------------------------------

(defn- lines-of
  "Text as diff lines. A trailing newline ends the last line rather than adding
  an empty one; nil (no file) is no lines."
  [t]
  (if (or (nil? t) (= "" t)) [] (vec (str/split-lines (str t)))))

(defn- lcs-ops
  "Line ops [[\\space l] [\\- l] [\\+ l]] for `a` -> `b`, by LCS. Called on the
  CHANGED middle only (common prefix/suffix trimmed first), so a one-line edit
  of a large file stays cheap."
  [a b]
  (let [n (count a) m (count b)
        ;; table[i][j] = LCS length of a[i..] and b[j..], built bottom-up
        table (reduce (fn [rows i]
                        (let [below (first rows)
                              row (reduce (fn [row j]
                                            (assoc row j
                                                   (if (= (nth a i) (nth b j))
                                                     (inc (nth below (inc j)))
                                                     (max (nth below j) (nth row (inc j))))))
                                          (vec (repeat (inc m) 0))
                                          (range (dec m) -1 -1))]
                          (cons row rows)))
                      (list (vec (repeat (inc m) 0)))
                      (range (dec n) -1 -1))
        table (vec table)]
    (loop [i 0 j 0 ops []]
      (cond
        (and (< i n) (< j m) (= (nth a i) (nth b j)))
        (recur (inc i) (inc j) (conj ops [\space (nth a i)]))

        (and (< i n) (or (>= j m)
                         (>= (nth (nth table (inc i)) j) (nth (nth table i) (inc j)))))
        (recur (inc i) j (conj ops [\- (nth a i)]))

        (< j m)
        (recur i (inc j) (conj ops [\+ (nth b j)]))

        :else ops))))

(def ^:private diff-context 3)

(defn- file-diff
  "{:file {:path :status :additions :deletions} :diff unified-text} for one file.
  `before`/`after` nil = absent (added / deleted). One hunk spanning the change,
  with up to three lines of context either side."
  [path before after]
  (let [a (lines-of before) b (lines-of after)
        na (count a) nb (count b)
        pre (loop [k 0] (if (and (< k na) (< k nb) (= (nth a k) (nth b k))) (recur (inc k)) k))
        suf (loop [k 0] (if (and (< k (- na pre)) (< k (- nb pre))
                                 (= (nth a (- na 1 k)) (nth b (- nb 1 k))))
                          (recur (inc k)) k))
        mid (lcs-ops (subvec a pre (- na suf)) (subvec b pre (- nb suf)))
        dels (count (filter #(= \- (first %)) mid))
        adds (count (filter #(= \+ (first %)) mid))
        status (cond (nil? before) "added" (nil? after) "deleted" :else "modified")
        lead (max 0 (- pre diff-context))
        tail (min suf diff-context)
        ops (concat (map (fn [l] [\space l]) (subvec a lead pre))
                    mid
                    (map (fn [l] [\space l]) (subvec a (- na suf) (+ (- na suf) tail))))
        old-n (count (remove #(= \+ (first %)) ops))
        new-n (count (remove #(= \- (first %)) ops))
        rng (fn [start cnt] (str (if (zero? cnt) (max 0 (dec start)) start) "," cnt))
        diff (if (and (zero? adds) (zero? dels))
               ""
               (str "--- " (if (nil? before) "/dev/null" (str "a/" path)) "\n"
                    "+++ " (if (nil? after) "/dev/null" (str "b/" path)) "\n"
                    "@@ -" (rng (inc lead) old-n) " +" (rng (inc lead) new-n) " @@\n"
                    (apply str (map (fn [[t l]] (str t l "\n")) ops))))]
    {:file {:path path :status status :additions adds :deletions dels}
     :diff diff}))

(defn- diff-path
  "The path a diff names: relative to `:base-dir` when one is set."
  [e p]
  (if (str/blank? (:base-dir e)) (str p) (rel-path (:base-dir e) p)))

(defn- diff-meta
  "metadata {:files [...] :diff \"...\"} for [[path before after] ...]."
  [e changes]
  (let [ds (mapv (fn [[p before after]] (file-diff (diff-path e p) before after)) changes)]
    {:files (mapv :file ds) :diff (apply str (map :diff ds))}))

(defn- read-if-exists [p] (when (fs/exists? p) (str (fs/read-file p))))

(defn- t-write [e args _ctx]
  (let [raw (str (:path args))
        [st p] (resolve-path e raw)
        c (str (:content args))]
    (if (= st :error)
      (tool/failure (str "write: " p))
      (let [before (read-if-exists p)]
        (when-let [d (parent-of p)] (fs/mkdirs! d))
        (fs/write-file p c)
        (tool/success (str "Wrote " (utf8-count c) " bytes to " raw)
                      (diff-meta e [[p before c]]))))))

(defn- t-edit [e args _ctx]
  (let [[st p] (resolve-path e (str (:path args)))]
    (if (= st :error)
      (tool/failure (str "edit: " p))
     (if-not (fs/exists? p)
      (tool/failure (str "edit: file not found: " p))
      (let [text (str (fs/read-file p))
            old  (str (:oldString args))
            new  (str (:newString args))
            hits (index-all text old)]
        (cond
          (empty? hits)
          (tool/failure (str "edit: oldString not found in " p))

          (and (> (count hits) 1) (not (true? (:replaceAll args))))
          (tool/failure (str "edit: oldString is not unique in " p
                         " (" (count hits) " occurrences)"))

          :else
          (let [out (if (true? (:replaceAll args))
                      (replace-all-literal text old new)
                      (replace-first-literal text old new))]
            (fs/write-file p out)
            (tool/success (str "Replaced " (if (true? (:replaceAll args)) (count hits) 1)
                          " occurrence(s) in " p)
                          (diff-meta e [[p text out]])))))))))

(defn- grep-file-hits
  "`rel:line:text` for every matching line in `f`, in ASCENDING LINE ORDER.

  The path emitted is the `/`-separated path RELATIVE to the walk root — the
  SAME string `t-grep` orders on. It used to emit the absolute path while
  ordering on the walked one: ordering by one thing and displaying another, and
  leaking a machine path into model-visible output. The same defect was found in
  golang, csharp and python, and js already emits `path.relative(root, file)`.

  Line order is NUMERIC because the hits are produced in line order and never
  re-sorted as strings — sorting the rendered `path:line:text` would put line 10
  before line 2."
  [pat root f]
  (let [rel   (rel-path root f)
        lines (vec (str/split-lines (str (fs/read-file f))))]
    (->> (map-indexed (fn [i line] [(inc i) line]) lines)
         (filter (fn [pair] (some? (re-find pat (nth pair 1)))))
         (map (fn [pair] {:rel rel :line (nth pair 0) :lines lines
                          :text (str rel ":" (nth pair 0) ":" (nth pair 1))}))
         vec)))

(defn- grep-context-lines
  "SPEC §4C grep context, ripgrep-style: match lines `path:N:text`, context lines
  `path-N-text`, and `--` between groups that do not touch."
  [hits before after]
  (let [match-at (reduce (fn [acc h] (update acc (:rel h) (fnil conj #{}) (:line h))) {} hits)]
    (loop [hs hits last-rel nil last-line 0 out []]
      (if-let [h (first hs)]
        (let [lines (:lines h)
              n     (count lines)
              from  (max 1 (- (:line h) before))
              to    (min n (+ (:line h) after))
              same? (= (:rel h) last-rel)
              [from out] (cond
                           (and same? (<= from (inc last-line))) [(inc last-line) out]
                           (some? last-rel)                       [from (conj out "--")]
                           :else                                  [from out])
              out   (reduce (fn [acc k]
                              (let [sep (if (contains? (get match-at (:rel h)) k) ":" "-")]
                                (conj acc (str (:rel h) sep k sep (nth lines (dec k))))))
                            out (range from (inc to)))
              last-line (if (or (> to last-line) (not same?)) to last-line)]
          (recur (rest hs) (:rel h) last-line out))
        out))))

(defn- t-grep
  "`pattern` is a REGEX, and the dialect differs per host (java.util.regex vs Go
  RE2). Only the common subset is portable; that is a §4A limit, not a bug here."
  [e args _ctx]
  (let [[rst root] (if (:path args) (resolve-path e (str (:path args))) [:ok (env-dir e)])
        limit (long (or (:limit args) 100))
        inc-g (:include args)
        pat   (re-pattern (str (:pattern args)))
        files (->> (files-under root)
                   (filter (fn [f] (or (nil? inc-g)
                                       (glob-match? inc-g (rel-path root f))))))
        ;; COLLECT every hit, THEN cap (A25/A26). `files-under` has already
        ;; ordered the walk portably, and the hits of each file come out in
        ;; ascending line order, so the concatenation is exactly "by relative
        ;; path, then line number ascending" — with the cap applied LAST, so the
        ;; filesystem never decides WHICH hits the model sees.
        hits  (when (= rst :ok) (vec (mapcat (fn [f] (grep-file-hits pat root f)) files)))]
    (if (= rst :error)
      (tool/failure (str "grep: " root))
      (let [n      (fn [k] (let [v (get args k)] (when (number? v) (long v))))
            ctx    (n :context)
            before (or (when (and (n :before) (>= (n :before) 0)) (n :before))
                       (when (and ctx (pos? ctx)) ctx) 0)
            after  (or (when (and (n :after) (>= (n :after) 0)) (n :after))
                       (when (and ctx (pos? ctx)) ctx) 0)
            shown  (vec (take limit hits))
            text   (if (and (zero? before) (zero? after))
                     (str/join "\n" (map :text shown))
                     (str/join "\n" (grep-context-lines shown before after)))]
        ;; SPEC §4C: a list cut by `limit` says so, and by how much, in METADATA.
        ;; The text stays the pinned cross-port output.
        (if (> (count hits) (count shown))
          (tool/success text {:count (count shown) :truncated true :total (count hits)})
          (tool/success text))))))

(defn- t-glob [e args _ctx]
  (let [[rst root] (if (:path args) (resolve-path e (str (:path args))) [:ok (env-dir e)])
        limit (long (or (:limit args) 100))
        rels  (->> (files-under root)
                   (map (fn [f] (rel-path root f)))
                   (filter (fn [r] (glob-match? (str (:pattern args)) r)))
                   ;; tool/sort-strings, not `sort`. `files-under` already
                   ;; ordered these portably, but by ABSOLUTE path — mapping to
                   ;; relative changes the keys, so this re-sort is what the
                   ;; output order actually is, and a bare `sort` handed it back
                   ;; to the host: the JVM orders by UTF-16 code unit, cljgo by
                   ;; UTF-8 byte, and above the BMP those are opposite answers.
                   tool/sort-strings)]
    (if (= rst :error)
      (tool/failure (str "glob: " root))
      (tool/success (str/join "\n" (take limit rels))))))

(defn- strip-tags [s]
  (-> (str s)
      (str/replace #"<[^>]*>" "")
      (str/replace #"[ \t]+" " ")
      str/trim))

(defn- html->markdown
  "Deliberately minimal: §4A pins the FORMAT NAMES but not the conversion, so
  these bytes are this port's choice and not the contract. Reported as a §4A
  gap that six ports cannot converge on by luck."
  [s]
  (-> (str s)
      (str/replace #"<h1[^>]*>" "# ")
      (str/replace #"</h1>" "\n")
      (str/replace #"<b>" "**")
      (str/replace #"</b>" "**")
      (str/replace #"<p[^>]*>" "")
      (str/replace #"</p>" "\n")
      strip-tags))

(defn- t-webfetch [args _ctx]
  (let [secs (long (or (:timeout args) 30))
        res  (khttp/request {:method :get :url (str (:url args))
                             :timeout-ms (* 1000 secs)})
        fmt  (str (or (:format args) "markdown"))]
    (if (khttp/failed? res)
      ;; DATA, never a caught class — koine classifies the transport failure.
      (tool/failure (str "webfetch: transport failure: " (name (:error res)))
                {:error (:error res)})
      (let [st   (long (:status res))
            body (str (:body res))]
        (cond
          (or (< st 200) (> st 299)) (tool/failure (str "HTTP " st))
          (= fmt "html")             (tool/success body)
          (= fmt "text")             (tool/success (strip-tags body))
          :else                      (tool/success (html->markdown body)))))))

(defn render-questions
  "§4A, byte-identical across ports: each question's text in order, with
  \" (options: a, b, c)\" appended when it has non-empty options, joined by
  \"\\n\", no trailing newline. `header` is NOT rendered — it stays in
  `data.questions`."
  [questions]
  (str/join "\n"
            (map (fn [q]
                   (str (:question q)
                        (if (seq (:options q))
                          (str " (options: " (str/join ", " (:options q)) ")")
                          "")))
                 questions)))

(defn- t-question
  "§4A + §10 — asking the human is a Request, not a special case. First call
  suspends; the loop re-executes with `ctx.answer`, and the resolution IS the
  answer. A `!ok` answer suspends again rather than inventing one."
  [args ctx]
  (let [answer (:answer ctx)]
    (if (and (map? answer) (true? (:ok answer)))
      (tool/success (json/write-str (:data answer)))
      (suspend {:id     (str (or (:request-id ctx) "req-1"))
                :kind   "question"
                :prompt (render-questions (:questions args))
                :data   {:questions (vec (:questions args))}}))))

;; -- apply_patch ------------------------------------------------------------

(def ^:private add-marker    "*** Add File: ")
(def ^:private update-marker "*** Update File: ")
(def ^:private delete-marker "*** Delete File: ")

(defn- parse-patch
  "opencode's grammar, ONE hunk per Update section. Returns {:ops [...]} or
  {:error \"...\"}."
  [text]
  (let [lines (vec (str/split-lines (str text)))]
    (if-not (= "*** Begin Patch" (str/trim (str (first lines))))
      {:error "apply_patch: patch must start with *** Begin Patch"}
      (loop [i 1 ops [] cur nil]
        (if (>= i (count lines))
          {:error "apply_patch: patch must end with *** End Patch"}
          (let [l (nth lines i)
                t (str/trim l)]
            (cond
              (= t "*** End Patch")
              {:ops (if cur (conj ops cur) ops)}

              (str/starts-with? t add-marker)
              (recur (inc i) (if cur (conj ops cur) ops)
                     {:op "add" :path (subs t (count add-marker)) :lines []})

              (str/starts-with? t update-marker)
              (recur (inc i) (if cur (conj ops cur) ops)
                     {:op "update" :path (subs t (count update-marker)) :lines []})

              (str/starts-with? t delete-marker)
              (recur (inc i) (if cur (conj ops cur) ops)
                     {:op "delete" :path (subs t (count delete-marker)) :lines []})

              (nil? cur) (recur (inc i) ops cur)
              :else      (recur (inc i) ops (assoc cur :lines (conj (:lines cur) l))))))))))

(defn- hunk-blocks
  "[old-lines new-lines] for an Update hunk: ' ' context, '-' removed, '+' added."
  [lines]
  (reduce (fn [acc l]
            (let [tag  (if (empty? l) \space (nth l 0))
                  body (if (empty? l) "" (subs l 1))]
              (cond
                (= tag \-) [(conj (nth acc 0) body) (nth acc 1)]
                (= tag \+) [(nth acc 0) (conj (nth acc 1) body)]
                :else      [(conj (nth acc 0) body) (conj (nth acc 1) body)])))
          [[] []]
          lines))

(defn- find-block
  "Index of the first contiguous occurrence of `block` in `lines`, or nil."
  [lines block]
  (if (empty? block)
    nil
    (loop [i 0]
      (cond
        (> (+ i (count block)) (count lines)) nil
        (= block (vec (take (count block) (drop i lines)))) i
        :else (recur (inc i))))))

(defn- plan-op
  "Resolve one op to {:kind :path :content?} or {:error ...}. READS ONLY — this
  is what makes §4A's 'no partial write' true by construction."
  [op]
  (let [p (:path op)]
    (cond
      (= "add" (:op op))
      (if (fs/exists? p)
        {:error (str "apply_patch: Add File target already exists: " p)}
        {:kind "add" :path p
         :content (str (str/join "\n" (map (fn [l] (if (empty? l) "" (subs l 1)))
                                           (:lines op)))
                       "\n")})

      (= "delete" (:op op))
      (if-not (fs/exists? p)
        {:error (str "apply_patch: Delete File target does not exist: " p)}
        {:kind "delete" :path p})

      (= "update" (:op op))
      (if-not (fs/exists? p)
        {:error (str "apply_patch: Update File target does not exist: " p)}
        (let [text   (str (fs/read-file p))
              trail? (str/ends-with? text "\n")
              lines  (vec (str/split-lines text))
              blocks (hunk-blocks (:lines op))
              at     (find-block lines (nth blocks 0))]
          (if (nil? at)
            {:error (str "apply_patch: hunk does not match " p)}
            (let [out (vec (concat (take at lines)
                                   (nth blocks 1)
                                   (drop (+ (long at) (count (nth blocks 0))) lines)))]
              {:kind "update" :path p
               :content (str (str/join "\n" out) (if trail? "\n" ""))}))))

      :else {:error (str "apply_patch: unknown op " (:op op))})))

(defn- t-apply-patch [e args _ctx]
  (let [parsed (parse-patch (:patchText args))]
    (if (:error parsed)
      (tool/failure (:error parsed))
      ;; The paths live INSIDE the patch text, not in the arguments, so a host
      ;; cannot rewrite them from a hook — the one case that genuinely needs the
      ;; base directory to be library-side (ADR 0034 D2).
      (let [resolved (mapv (fn [op]
                             (let [[st p] (resolve-path e (str (:path op)))]
                               (if (= st :error) {:error (str "apply_patch: " p)} (assoc op :path p))))
                           (:ops parsed))
            bad-path (first (filter :error resolved))
            plans (if bad-path [bad-path] (mapv plan-op resolved))
            bad   (first (filter :error plans))]
        (if bad
          ;; Atomic: everything was planned from READS, so nothing has been
          ;; written and there is no partial state to unwind.
          (tool/failure (:error bad))
          (let [befores (mapv (fn [pl] (when-not (= "add" (:kind pl)) (read-if-exists (:path pl)))) plans)]
              (doseq [pl plans]
                (if (= "delete" (:kind pl))
                  (fs/delete! (:path pl))
                  (do (when-let [d (parent-of (:path pl))] (fs/mkdirs! d))
                      (fs/write-file (:path pl) (:content pl)))))
              (tool/success (str "Applied " (count plans) " file change(s): "
                            (str/join ", " (map (fn [pl] (str (:kind pl) " " (:path pl)))
                                                plans)))
                            (diff-meta e (mapv (fn [pl before]
                                                 [(:path pl) before
                                                  (when-not (= "delete" (:kind pl)) (:content pl))])
                                               plans befores)))))))))

(defn- t-todowrite
  "Stateless in v1 — it echoes the list back. The RENDERING is not pinned by
  §4A; `- [x] text` / `- [ ] text` is this port's choice (reported).

  The `:todos` METADATA is not cosmetic: the other six ports all return it, and a
  host reading the plan back (the §7D completion gate does) has no other
  structured way to see which items are still open. This port was the only one
  omitting it."
  [args _ctx]
  (let [todos (vec (:todos args))]
    (tool/success (str/join "\n"
                            (map (fn [t] (str "- [" (if (true? (:completed t)) "x" " ") "] "
                                              (:text t)))
                                 todos))
                  {:todos todos})))

;; ---------------------------------------------------------------------------
;; JSON, indented the way the Go reference marshals it (sorted keys, HTML-safe
;; escapes) — so a notebook rewrite or a tool_search result is the same bytes
;; in every port.
;; ---------------------------------------------------------------------------

(defn- json-key [k]
  (cond (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k))
        :else (str k)))

(defn- go-escape [s]
  (-> (str s)
      (str/replace "<" "\\u003c")
      (str/replace ">" "\\u003e")
      (str/replace "&" "\\u0026")
      (str/replace "\u2028" "\\u2028")
      (str/replace "\u2029" "\\u2029")))

(defn ^:no-doc json-indent
  "`v` as indented JSON: object keys sorted by code point, `indent` per level,
  `<`/`>`/`&`/U+2028/U+2029 escaped as \\u003c/\\u003e/\\u0026/\\u2028/\\u2029 — the bytes of Go's
  `json.MarshalIndent(v, \"\", indent)`. INTERNAL: shared by `notebook_edit` and
  the client's `tool_search` so the two cannot drift."
  ([v indent] (json-indent v indent ""))
  ([v indent pad]
   (let [pad2 (str pad indent)]
     (cond
       (map? v)
       (if (empty? v)
         "{}"
         (let [ks (tool/sort-strings (map json-key (keys v)))
               by (into {} (map (fn [[k x]] [(json-key k) x]) v))]
           (str "{\n"
                (str/join ",\n" (map (fn [k] (str pad2 (go-escape (json/write-str k)) ": "
                                                  (json-indent (get by k) indent pad2)))
                                     ks))
                "\n" pad "}")))

       (or (sequential? v) (set? v))
       (if (empty? v)
         "[]"
         (str "[\n"
              (str/join ",\n" (map (fn [x] (str pad2 (json-indent x indent pad2))) v))
              "\n" pad "]"))

       (string? v) (go-escape (json/write-str v))
       (keyword? v) (go-escape (json/write-str (json-key v)))
       :else (json/write-str v)))))

;; ---------------------------------------------------------------------------
;; SPEC §4C opt-in: websearch over a host provider, notebook_edit
;; ---------------------------------------------------------------------------

(defn- t-websearch [e args _ctx]
  (let [q     (str (or (:query args) ""))
        limit (let [l (:limit args)] (if (and (number? l) (>= l 1)) (long l) 5))]
    (if (str/blank? q)
      (tool/failure "websearch: query is required")
      (let [res (try {:ok (vec ((:web-search e) q limit))}
                     (catch Throwable t {:error (or (ex-message t) (str t))}))]
        (if (:error res)
          (tool/failure (str "websearch: " (:error res)))
          (let [rs (vec (take limit (:ok res)))]
            (if (empty? rs)
              (tool/success "No results." {:count 0})
              (tool/success
               (str/join "\n\n"
                         (map-indexed (fn [i r]
                                        (str (inc i) ". " (:title r) "\n   " (:url r)
                                             (when-not (str/blank? (str (:snippet r)))
                                               (str "\n   " (:snippet r)))))
                                      rs))
               {:count (count rs) :results rs}))))))))

(defn- notebook-source
  "Text as nbformat's list of lines, each keeping its newline."
  [src]
  (if (= "" src)
    []
    (loop [s src acc []]
      (let [j (str/index-of s "\n")]
        (if (nil? j)
          (if (= "" s) acc (conj acc s))
          (recur (subs s (inc j)) (conj acc (subs s 0 (inc j)))))))))

(defn- notebook-index
  "The target cell index, -1 for an insert at the top, or [:error msg]."
  [cells mode cid cix]
  (cond
    (not (str/blank? (str (or cid ""))))
    (or (first (keep-indexed (fn [i c] (when (= (str cid) (:id c)) i)) cells))
        [:error (str "notebook_edit: no cell with id " (pr-str (str cid)))])

    (number? cix)
    (let [i (long cix)]
      (if (or (neg? i) (>= i (count cells)))
        [:error (str "notebook_edit: cell_index " i " out of range (0-" (dec (count cells)) ")")]
        i))

    (not= mode "insert") [:error "notebook_edit: cell_id or cell_index is required"]
    :else -1))

(defn- notebook-apply
  "{:cells :summary} or {:error}."
  [cells mode idx src ctype raw]
  (case mode
    "replace"
    (let [c (cond-> (assoc (get cells idx) :source (notebook-source src))
              ctype (assoc :cell_type ctype))
          c (if (= "code" (:cell_type c))
              (assoc c :outputs [] :execution_count nil)
              (dissoc c :outputs :execution_count))]
      {:cells (assoc cells idx c) :summary (str "Replaced cell " idx " in " raw)})

    "insert"
    (let [t    (or ctype "code")
          cell (cond-> {:cell_type t :metadata {} :source (notebook-source src)}
                 (= "code" t) (assoc :outputs [] :execution_count nil))
          at   (inc idx)]
      {:cells (vec (concat (subvec cells 0 at) [cell] (subvec cells at)))
       :summary (str "Inserted " t " cell at " at " in " raw)})

    "delete"
    {:cells (vec (concat (subvec cells 0 idx) (subvec cells (inc idx))))
     :summary (str "Deleted cell " idx " from " raw)}

    {:error (str "notebook_edit: unknown edit_mode " (pr-str mode))}))

(defn- t-notebook-edit [e args _ctx]
  (let [raw (str (or (:path args) ""))
        [st p] (if (str/blank? raw) [:error "path is required"] (resolve-path e raw))]
    (cond
      (= st :error) (tool/failure (str "notebook_edit: " p))
      (not (fs/exists? p)) (tool/failure (str "notebook_edit: file not found: " raw))
      :else
      (let [before (str (fs/read-file p))
            nb     (try (json/read-str before)
                        (catch Throwable t {::bad (or (ex-message t) (str t))}))]
        (if (or (not (map? nb)) (::bad nb))
          (tool/failure (str "notebook_edit: " raw " is not a notebook: " (when (map? nb) (::bad nb))))
          (let [cells (vec (:cells nb))
                mode  (if (str/blank? (str (or (:edit_mode args) ""))) "replace" (str (:edit_mode args)))
                idx   (notebook-index cells mode (:cell_id args) (:cell_index args))
                ctype (when-not (str/blank? (str (or (:cell_type args) ""))) (str (:cell_type args)))
                res   (if (vector? idx)
                        {:error (second idx)}
                        (notebook-apply cells mode idx (str (or (:new_source args) "")) ctype raw))]
            (if (:error res)
              (tool/failure (:error res))
              (let [out (str (json-indent (assoc nb :cells (:cells res)) " ") "\n")]
                (fs/write-file p out)
                (tool/success (:summary res)
                              (merge {:cells (count (:cells res))}
                                     (diff-meta e [[p before out]])))))))))))

;; ---------------------------------------------------------------------------
;; the builtin source
;; ---------------------------------------------------------------------------

(defn- builtin
  [tool-name description schema f]
  (tool/tool {:name tool-name
              :description description
              :input-schema schema
              :source "builtin"
              ;; two arities, because toolnexus.tool/execute calls (f args) when
              ;; it has no Context and (f args ctx) when it does.
              :execute (fn ([args] (f args nil))
                         ([args ctx] (f args ctx)))}))

(def builtin-hints
  "SPEC §4C tool hints for every builtin, for a host permission gate. `bash` can
  do anything, so it is destructive and open-world; `todowrite` and `question`
  only touch the agent's own state / ask the human, so they count as read-only."
  {"bash"          {:read-only false :destructive true  :open-world true}
   "read"          {:read-only true  :destructive false :open-world false}
   "write"         {:read-only false :destructive true  :open-world false}
   "edit"          {:read-only false :destructive true  :open-world false}
   "grep"          {:read-only true  :destructive false :open-world false}
   "glob"          {:read-only true  :destructive false :open-world false}
   "webfetch"      {:read-only true  :destructive false :open-world true}
   "question"      {:read-only true  :destructive false :open-world false}
   "apply_patch"   {:read-only false :destructive true  :open-world false}
   "todowrite"     {:read-only true  :destructive false :open-world false}
   "bash_output"   {:read-only true  :destructive false :open-world false}
   "kill_shell"    {:read-only false :destructive true  :open-world false}
   "websearch"     {:read-only true  :destructive false :open-world true}
   "notebook_edit" {:read-only false :destructive true  :open-world false}})

(def ^:private bg-id-schema
  {:type "object"
   :properties {:id {:type "string" :description "The background shell id bash returned"}}
   :required ["id"]})

(def ^:private websearch-schema
  {:type "object"
   :properties {:query {:type "string" :description "The search query"}
                :limit {:type "number" :description "Maximum number of results (default 5)"}}
   :required ["query"]})

(def ^:private notebook-schema
  {:type "object"
   :properties {:path       {:type "string" :description "Path to the .ipynb file"}
                :cell_id    {:type "string" :description "The cell's id (or use cell_index)"}
                :cell_index {:type "number" :description "0-based cell position (used when cell_id is absent)"}
                :new_source {:type "string" :description "The new cell source (replace/insert)"}
                :cell_type  {:type "string" :enum ["code" "markdown"]
                             :description "Cell type for insert (default code) or to change on replace"}
                :edit_mode  {:type "string" :enum ["replace" "insert" "delete"]
                             :description "Default replace. insert adds the new cell AFTER the given cell, or first when none is given"}}
   :required ["path"]})

(defn builtin-tools-for
  "The ten §4A builtins, built against a host boundary — the interpreter, base
  directory and confinement of a builtins config (ADR 0034). A nil config is the
  historical behaviour: the detected interpreter, paths relative to the process
  working directory, no confinement.

  SPEC §4C opt-ins, each absent unless configured: `:background` (a
  `background-shells` registry) adds `run_in_background` to bash plus
  `bash_output` / `kill_shell`; `:web-search` `(fn [query limit] [{:title :url
  :snippet}])` adds `websearch`; `:notebook-edit true` adds `notebook_edit`.
  Every builtin carries `:hints` (`builtin-hints`)."
  [cfg]
  (let [e (builtin-env cfg)
        with-env (fn [f] (fn [args ctx] (f e args ctx)))
        bash-sch (if (:background e)
                   (assoc-in bash-schema [:properties :run_in_background]
                             {:type "boolean"
                              :description (str "Start the command and return at once with a background "
                                                "shell id; read it with bash_output, stop it with "
                                                "kill_shell. No timeout applies.")})
                   bash-schema)]
    (mapv
     (fn [t] (assoc t :hints (get builtin-hints (:name t))))
     (cond->
    [(builtin "bash"        "Run a shell command."                bash-sch           (with-env t-bash))
     (builtin "read"        (str "Read a file. A known media file (png/jpg/jpeg/gif/webp/pdf/"
                                 "mp3/wav) comes back as a content part; otherwise it is read "
                                 "as UTF-8 text, and with offset/limit only that line window "
                                 "is returned.")
                                                                  read-schema        (with-env t-read))
     (builtin "write"       "Write a file, creating parent dirs." write-schema       (with-env t-write))
     (builtin "edit"        "Exact-string replace in a file."     edit-schema        (with-env t-edit))
     (builtin "grep"        "Search file contents by regex."      grep-schema        (with-env t-grep))
     (builtin "glob"        "List files matching a glob."         glob-schema        (with-env t-glob))
     (builtin "webfetch"    "HTTP GET a URL and return its body." webfetch-schema    t-webfetch)
     (builtin "question"    "Ask the human one or more questions." question-schema   t-question)
     (builtin "apply_patch" "Apply an add/update/delete patch."   apply-patch-schema (with-env t-apply-patch))
     (builtin "todowrite"   "Replace the session todo list."      todowrite-schema   t-todowrite)]
      (:background e)
      (conj (builtin "bash_output"
                     (str "Read the output a background bash command (started with run_in_background) "
                          "produced since the last read, and whether it is still running.")
                     bg-id-schema (with-env t-bash-output))
            (builtin "kill_shell" "Stop a background bash command and everything it started."
                     bg-id-schema (with-env t-kill-shell)))
      (:web-search e)
      (conj (builtin "websearch" "Search the web. Returns numbered results with title, URL and snippet."
                     websearch-schema (with-env t-websearch)))
      (:notebook-edit e)
      (conj (builtin "notebook_edit"
                     (str "Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell "
                          "after it, or delete it. Replacing a code cell clears its outputs.")
                     notebook-schema (with-env t-notebook-edit)))))))

(def builtin-tools
  "The ten §4A builtins with the default host boundary, in the order of the SPEC
  table. One definition, not two: the cfg-aware builder is the only place the set
  is listed, so the two cannot drift."
  (builtin-tools-for nil))

(def builtin-names (mapv :name builtin-tools))

;; ---------------------------------------------------------------------------
;; §0.11 / §4 — toggling
;; ---------------------------------------------------------------------------

(defn source-on?
  "Whole-source gate. `builtins:false` | `{disabled:true}` | `{enabled:false}`
  turn the source off, with MCP precedence: `disabled:true` wins, else
  `enabled:false` disables, else on. Absent ⇒ ON (the default)."
  [opt]
  (cond
    (nil? opt)   true
    (true? opt)  true
    (false? opt) false
    (map? opt)   (cond (true? (:disabled opt)) false
                       (false? (:enabled opt)) false
                       :else                   true)
    :else        true))

(defn enabled-builtins
  "The builtin tools this config exposes. A whole-source-off SHORT-CIRCUITS:
  the `tools` map is never consulted (§0.11).

  ===================================================================
  KNOWN SPEC DEFECT — `builtins.tools` with a `true` value. REPORTED.
  ===================================================================
  Two sections of SPEC.md specify DIFFERENT FUNCTIONS for the same input,
  while each claims to be the other:

    §4A + §4 'Assembly order & builtin toggle' (and §0.11 itself) —
      'a name→bool map applied on the ALL-ON BASELINE: a tool mapped to false
      is dropped, true (or absent) stays on.'
      ⇒ {:tools {:bash true}} = ALL TEN.

    §3 S2 (skills filter) and §2 Gap 7 (MCP per-server filter), both of which
      describe themselves as 'identical to ... builtins §4A' —
      'nil/empty ⇒ all; ≥1 true ⇒ ALLOWLIST (only true-mapped names)'
      ⇒ {:tools {:bash true}} = BASH ONLY.

  This implements the §4A wording — the section that OWNS builtins, states it
  twice, and which §0.11 restates. Spike s22 made the same call. Whichever way
  it is resolved, §3-S2 and §2-Gap-7 must stop claiming they are identical to
  §4A unless they are. Six ports cannot be byte-identical against a spec that
  specifies two behaviours for one input."
  [opt]
  (if-not (source-on? opt)
    []
    ;; Keys are normalised to plain NAMES before lookup. `(get m (keyword …))`
    ;; matched keyword keys only, so `{:tools {"bash" false}}` — and EVERY config
    ;; read from JSON without `:key-fn keyword` — left all ten builtins armed.
    ;; It failed OPEN: the one direction a disable toggle must never fail. The
    ;; same both-spellings rule already governs the §3 skills filter
    ;; (`skill/filter-name`); this is that rule, applied where it was missing.
    (let [m       (when (map? opt) (:tools opt))
          by-name (when (map? m)
                    (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))]
      (vec (filter (fn [t] (not (false? (get by-name (:name t)))))
                   ;; Built against THIS config, so the same object that carries
                   ;; the toggles also carries the host boundary (ADR 0034).
                   (builtin-tools-for opt))))))

(defn enabled-builtin-names [opt] (mapv :name (enabled-builtins opt)))

(defn builtin-toolkit
  "The enabled builtins as a `toolnexus.tool` toolkit."
  ([] (builtin-toolkit nil))
  ([opt] (tool/toolkit (enabled-builtins opt) {"builtin" (if (source-on? opt)
                                                           "connected" "disabled")})))
