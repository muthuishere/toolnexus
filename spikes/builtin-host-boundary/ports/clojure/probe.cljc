;; Port-boundary probes for the CLOJURE port, one .cljc, NO reader conditionals,
;; NO java.* interop — so the same file runs on the JVM and on cljgo.
;;
;;   JVM    clojure -M -m probe <section> [args...]
;;   cljgo  cljgo run probe.cljc <section> [args...]
;;
;; Sections: shell | basedir | confine | kill
(ns probe
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.host :as host]
            [koine.process :as proc]))

(defn p [& xs] (println (apply str xs)))

;; ---------------------------------------------------------------------------
;; O1 SHELL — does koine proc/sh resolve a BARE program name, and what comes
;; back when the program does not exist? "construction fails naming the
;; candidates" has to be built out of whatever that is.
;; ---------------------------------------------------------------------------
(defn sh-safe
  "proc/sh, but a throw is a value. koine's sh never throws on a non-zero exit;
  whether it throws on a MISSING BINARY is the whole question here, and the two
  hosts need not agree."
  [argv]
  (try {:ok (proc/sh argv {:timeout-ms 4000})}
       (catch Throwable e
         {:threw (str (ex-message e))})))

(defn section-shell []
  (p "HOST=" host/id)
  ;; control: an absolute path that certainly exists
  (p "CTRL_ABS=" (pr-str (sh-safe ["/bin/sh" "-c" "echo hello"])))
  ;; the measurement: BARE names
  (doseq [n ["sh" "bash" "zsh" "pwsh" "powershell" "cmd" "nosuchprog-xyz"]]
    (p "BARE_" n "=" (pr-str (sh-safe [n "-c" "echo hello"]))))
  ;; a host-set argv PREFIX used verbatim, incl. a multi-flag one
  (doseq [prefix [["sh" "-c"] ["bash" "-lc"] ["env" "-i" "sh" "-c"]]]
    (p "PREFIX_" (str/join "_" prefix) "="
       (pr-str (sh-safe (conj (vec prefix) "echo prefix-ok")))))
  ;; is there ANY resolver? koine has no `which`; the only portable probe is to
  ;; run the candidate and read the failure.
  (p "PROBE_RESOLVE_sh=" (pr-str (sh-safe ["sh" "-c" "exit 0"])))
  (p "PROBE_RESOLVE_missing=" (pr-str (sh-safe ["nosuchprog-xyz" "-c" "exit 0"]))))

;; ---------------------------------------------------------------------------
;; O2 BASEDIR — koine.fs offers NO join/absolutise. Measure the pure-string
;; alternative, and that an empty base reproduces today's cwd behaviour.
;; ---------------------------------------------------------------------------
(defn- clean-segments
  "Lexical `.`/`..` collapse over `/`-separated segments. Pure string work —
  there is no path type on either host."
  [segs]
  (reduce (fn [acc s]
            (cond (or (= s ".") (= s "")) acc
                  (= s "..") (if (seq acc) (pop acc) acc)
                  :else (conj acc s)))
          [] segs))

(defn abs? [s] (str/starts-with? (str s) "/"))

(defn path-join [& parts]
  (let [j (str/join "/" (remove str/blank? (map str parts)))]
    (str (when (abs? (first (remove str/blank? (map str parts)))) "/")
         (str/join "/" (clean-segments (str/split j #"/"))))))

(defn cwd
  "There is no koine/clojure.core call for the process cwd that works on both
  hosts, so it is asked of the shell — one subprocess, at construction."
  []
  (str/trim (:out (proc/sh ["sh" "-c" "pwd"] {:timeout-ms 4000}))))

(defn resolve-path [base pth]
  (let [pth (str pth)]
    (cond (abs? pth) (path-join pth)
          (str/blank? base) (path-join (cwd) pth)
          :else (path-join base pth))))

(defn section-basedir [base]
  (p "HOST=" host/id)
  (p "CWD=" (cwd))
  (p "BASE=" base)
  (doseq [x ["sub/file.txt" "./sub/file.txt" "../escape.txt" "file.txt" "." "" "/etc/hosts"]]
    (p "WITHBASE " (pr-str x) " -> " (pr-str (resolve-path base x))))
  ;; CONTROL: empty base must equal today's behaviour (relative to process cwd)
  (let [same (for [x ["sub/file.txt" "./x" "../y" "z"]]
               [x (resolve-path "" x) (path-join (cwd) x)
                (= (resolve-path "" x) (path-join (cwd) x))])]
    (p "EMPTY_BASE_IDENTICAL=" (every? #(nth % 3) same))
    (doseq [t same] (p "  " (pr-str t))))
  ;; a real relative write must land under the base, not under cwd
  (fs/mkdirs! (resolve-path base "sub"))
  (fs/write-file (resolve-path base "sub/w.txt") "x")
  (p "LANDED_IN_BASE=" (fs/exists? (path-join base "sub/w.txt")))
  (p "LANDED_IN_CWD=" (fs/exists? (path-join (cwd) "sub/w.txt")))
  ;; apply_patch: the paths are CONTENT
  (let [patch "*** Begin Patch\n*** Add File: sub/added.txt\n+hi\n*** End Patch\n"
        out (->> (str/split-lines patch)
                 (map (fn [l]
                        (if-let [m (re-matches #"^\*\*\* (Add File|Update File|Delete File): (.*)$" l)]
                          (str "*** " (nth m 1) ": " (resolve-path base (str/trim (nth m 2))))
                          l)))
                 (str/join "\n"))]
    (p "PATCH_REWRITE_OK=" (str/includes? out (path-join base "sub/added.txt"))))
  ;; bash workdir default — koine sh takes :dir, so this one is free
  (p "BASH_WORKDIR=" (str/trim (:out (proc/sh ["sh" "-c" "pwd"] {:dir base :timeout-ms 4000}))))
  ;; what koine.fs does NOT have
  (p "KOINE_FS_HAS_JOIN=" (some? (resolve 'koine.fs/join)))
  (p "KOINE_FS_HAS_ABSOLUTE=" (some? (resolve 'koine.fs/absolute)))
  (p "KOINE_FS_HAS_REAL_PATH=" (some? (resolve 'koine.fs/real-path)))
  (p "HOST_SUPPORTS_real_path=" (host/supports? :fs/real-path)))

;; ---------------------------------------------------------------------------
;; O3 CONFINEMENT — is there a symlink-resolving call reachable from a .cljc on
;; BOTH hosts? koine.fs/real-path claims to be one. Measure it, including the
;; (b) not-yet-existing case and (c) case normalisation.
;; ---------------------------------------------------------------------------
(defn real-safe [pth]
  (try {:ok (fs/real-path pth)}
       (catch Throwable e {:threw (ex-message e)})))

(defn canon
  "Resolve the deepest EXISTING ancestor and re-attach the tail."
  [pth]
  (let [pth (path-join pth)
        segs (vec (remove str/blank? (str/split pth #"/")))]
    (loop [n (count segs)]
      (let [head (str "/" (str/join "/" (subvec segs 0 n)))
            tail (subvec segs n)]
        (cond
          (fs/exists? head) (path-join (:ok (real-safe head)) (str/join "/" tail))
          (zero? n) pth
          :else (recur (dec n)))))))

(defn contained? [base pth]
  (let [cb (canon base) cp (canon pth)]
    (or (= cb cp) (str/starts-with? cp (str cb "/")))))

(defn section-confine [base out]
  (p "HOST=" host/id)
  (p "BASE_RAW=" base)
  (p "REAL_PATH_base=" (pr-str (real-safe base)))
  (p "REAL_PATH_missing=" (pr-str (real-safe (path-join base "nope/nope.txt"))))
  (p "CANON_base=" (canon base))
  (p "LEXICAL_DIFFERS_FROM_CANON=" (not= (canon base) (path-join base)))
  (doseq [[x want] [["sub/file.txt" true]
                    ["sub/deep/not/created/yet.txt" true]
                    ["../escape.txt" false]
                    ["sub/../../escape.txt" false]
                    [(path-join out "secret.txt") false]
                    ["link/secret.txt" false]
                    ["link/newfile.txt" false]
                    ["." true]
                    ["" true]]]
    (let [full (if (abs? x) x (path-join base x))
          got (contained? base full)]
      (p "CONFINE " (format "%-34s" (pr-str x)) " contained=" got
         " expected=" want " " (if (= got want) "OK" "MISMATCH"))))
  ;; CONTROL — a naive LEXICAL check must say the symlink case is contained.
  (p "CONTROL_naive_symlink_says_contained="
     (str/starts-with? (path-join base "link/secret.txt") (str (path-join base) "/")))
  ;; (c) case normalisation
  (let [up (str/upper-case base)]
    (p "CASE_upper_exists=" (fs/exists? (path-join up "sub")))
    (p "CASE_upper_canon=" (canon (path-join up "sub")))
    (p "CASE_contained_via_upper=" (contained? base (path-join up "sub"))))
  ;; the subprocess fallback, measured so the write-up can say whether it is needed
  (p "SUBPROC_pwd_-P=" (str/trim (:out (proc/sh ["sh" "-c" "pwd -P"] {:dir base :timeout-ms 4000})))))

;; ---------------------------------------------------------------------------
;; O4 KILL — does the `set -m` wrapper work THROUGH koine proc/sh, on this host?
;; Modes: control | naive | postwalk | setm | setm-quiet | spawn-pgid
;; ---------------------------------------------------------------------------
(defn section-kill [mode marker pidfile]
  (p "HOST=" host/id)
  (let [cmd (str "sleep 0.2; sh -c 'sleep 1; touch " marker "'")
        wrap (fn [tail] (str "set -m; { " cmd "\n} & echo $! > " pidfile "; wait $!" tail))]
    (case mode
      "control"  (proc/sh ["sh" "-c" cmd] {:timeout-ms 30000})
      "naive"    (proc/sh ["sh" "-c" cmd] {:timeout-ms 300})
      "postwalk" (do (proc/sh ["sh" "-c" (str "echo $$ > " pidfile "; " cmd)] {:timeout-ms 300})
                     (let [pid (str/trim (fs/read-file pidfile))
                           ps  (:out (proc/sh ["ps" "-eo" "pid=,ppid="] {:timeout-ms 5000}))
                           kids (keep (fn [l] (let [[a b] (str/split (str/trim l) #"\s+")]
                                                (when (= b pid) a)))
                                      (str/split-lines ps))]
                       (p "REACHABLE_AFTER_KILL=" (count kids))
                       (doseq [k kids] (proc/sh ["kill" "-KILL" k] {:timeout-ms 2000}))))
      ("setm" "setm-quiet" "setm-dash")
      (let [tail (if (= mode "setm") "" " 2>/dev/null")
            interp (if (= mode "setm-dash") "/bin/dash" "sh")
            r (proc/sh [interp "-c" (wrap tail)] {:timeout-ms 300})]
        (p "OUT=" (pr-str (str (:out r) (:err r))) " EXIT=" (pr-str (:exit r))
           " TIMED_OUT=" (:timed-out? r))
        (let [pg (str/trim (fs/read-file pidfile))]
          (p "PGID=" pg)
          (let [k (proc/sh ["kill" "-TERM" (str "-" pg)] {:timeout-ms 2000})]
            (p "KILLGROUP_exit=" (pr-str (:exit k)) " " (pr-str (str/trim (str (:out k) (:err k))))))
          (proc/sh ["sh" "-c" (str "sleep 0.2; kill -KILL -" pg " 2>/dev/null || true")]
                   {:timeout-ms 3000})))
      ;; the OTHER route koine already offers: `spawn` is a long-lived child whose
      ;; stdout we can READ, so the pgid can come back on a line instead of a file.
      "spawn-pgid"
      (let [c (proc/spawn ["sh" "-c" (str "set -m; { " cmd "\n} & echo PG=$!; wait $! 2>/dev/null")])
            line (proc/read-line! c)
            pg (when line (str/replace (str line) "PG=" ""))]
        (p "SPAWN_FIRST_LINE=" (pr-str line))
        (when pg
          (proc/sh ["kill" "-TERM" (str "-" pg)] {:timeout-ms 2000})
          (proc/sh ["sh" "-c" (str "sleep 0.2; kill -KILL -" pg " 2>/dev/null || true")] {:timeout-ms 3000}))
        (proc/kill! c)))))

;; --- byte-fidelity of the wrapper, the §0 question --------------------------
(defn section-fidelity [pidfile]
  (p "HOST=" host/id)
  (doseq [c ["echo hello" "echo oops 1>&2" "exit 7" "printf abc" "nosuchcmd-xyz"]]
    (let [plain (proc/sh ["sh" "-c" c] {:timeout-ms 4000})
          wrapA (proc/sh ["sh" "-c" (str "set -m; { " c "\n} & echo $! > " pidfile "; wait $!")] {:timeout-ms 4000})
          wrapB (proc/sh ["sh" "-c" (str "set -m; { " c "\n} & echo $! > " pidfile "; wait $! 2>/dev/null")] {:timeout-ms 4000})
          k (fn [r] [(:out r) (:err r) (:exit r)])]
      (p (format "%-16s" c) " plain=" (pr-str (k plain))
         " setm=" (pr-str (k wrapA)) (if (= (k plain) (k wrapA)) " SAME" " *DIVERGES*")
         " setm-quiet=" (pr-str (k wrapB)) (if (= (k plain) (k wrapB)) " SAME" " *DIVERGES*")))))

(defn -main [& [section a b c]]
  (case section
    "shell"    (section-shell)
    "basedir"  (section-basedir a)
    "confine"  (section-confine a b)
    "kill"     (section-kill a b c)
    "fidelity" (section-fidelity a)
    (p "usage: probe <shell|basedir|confine|kill|fidelity> [args]")))
