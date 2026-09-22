;; Stress harness for the SHIPPED clojure builtins (ADR 0034 / SPIKE §1.1).
;;
;; .cljc-clean by construction: no java.* interop, no reader conditionals, no
;; Go interop. Everything host-ish goes through koine — koine.process/run-async!
;; for concurrency, koine.process/sh for the ps/lsof shell-outs, koine.fs for
;; the filesystem. The builtins are the released ones: this file never
;; reimplements a builtin, it only calls toolnexus.tool/execute on the toolkit
;; toolnexus.builtin/builtin-toolkit hands back.
(ns stress
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.host :as khost]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.builtin :as builtin]
            [toolnexus.tool :as tool]))

;; --------------------------------------------------------------- primitives

(defn- par
  "Run every thunk concurrently on koine's async primitive and return their
  values in order. The port's own idiom — the same run-async! t-bash uses."
  [fs]
  (let [ps (mapv (fn [_] (promise)) fs)]
    (doseq [[p f] (map vector ps fs)]
      (proc/run-async! (fn [] (deliver p (try (f) (catch Throwable e {:isError true :output (str e)}))))))
    (mapv deref ps)))

(def ^:private digit {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})

(defn- to-int [s]
  (let [cs (seq (str/trim (str s)))]
    (if (and (seq cs) (every? digit cs))
      (reduce (fn [a c] (+ (* 10 a) (digit c))) 0 cs)
      -1)))

(defn- shell-int [cmd]
  (let [r (proc/sh ["sh" "-c" cmd] {:timeout-ms 15000})]
    (if (:timed-out? r) -1 (to-int (:out r)))))

(defn- own-pid []
  ;; $PPID inside the shell koine spawned IS this runtime's pid. No host API is
  ;; reachable from a .cljc, so the process table is the only route.
  (shell-int "echo $PPID"))

(defn- snap [pid]
  {:threads  (shell-int (str "ps -M " pid " 2>/dev/null | tail -n +2 | wc -l"))
   :children (shell-int (str "ps -eo pid=,ppid= | awk -v p=" pid " '$2==p' | wc -l"))
   :fds      (shell-int (str "lsof -p " pid " 2>/dev/null | tail -n +2 | wc -l"))})

(defn- secs [t0] (str (/ (double (long (- (ktime/mono-ms) t0))) 1000.0) "s"))

(defn- bash! [tk cmd ms]
  (tool/execute tk "bash" {:command cmd :timeout ms}))

(defn- md [r k] (get (:metadata r) k))

;; ------------------------------------------------------------------ S1 / S2 / S3

(defn s1 [tk dir round]
  (let [t0   (ktime/mono-ms)
        ms   (mapv (fn [i] (str dir "/s1-" round "-m" i)) (range 30))
        cs   (mapv (fn [i] (str dir "/s1-" round "-c" i)) (range 3))
        cmd  (fn [m] (str "sleep 0.2; sh -c 'sleep 5; touch " m "'"))
        rs   (par (concat (map (fn [m] (fn [] [:t (bash! tk (cmd m) 300)])) ms)
                          (map (fn [m] (fn [] [:c (bash! tk (cmd m) 20000)])) cs)))
        kill (filterv (fn [[k _]] (= k :t)) rs)
        ctl  (filterv (fn [[k _]] (= k :c)) rs)
        _    (ktime/sleep! 7000)
        leak (count (filter fs/exists? ms))
        cmk  (count (filter fs/exists? cs))
        to   (count (filter (fn [[_ r]] (true? (md r :timedOut))) kill))
        kt   (count (filter (fn [[_ r]] (true? (md r :killedTree))) kill))
        cok  (count (filter (fn [[_ r]] (false? (:isError r))) ctl))
        ok   (and (= 0 leak) (= 3 cmk) (= 3 cok))]
    (println (str "S1 verdict=" (if (= 3 cmk) (if ok "PASS" "FAIL") "INVALID")
                  " markers=" leak " timedOut=" to "/30 killedTree=" kt "/30"
                  " control_markers=" cmk "/3 control_ok=" cok "/3 wall=" (secs t0)))
    ok))

(defn s2 [tk dir round]
  (let [t0 (ktime/mono-ms)
        n  40
        js (mapv (fn [i]
                   (if (even? i)
                     (fn [] [:to i (bash! tk (str "sleep 0.2; sh -c 'sleep 5; touch " dir "/s2-" round "-" i "'") 300)])
                     (fn [] [:ok i (bash! tk (str "echo ok-" i) 20000)])))
                 (range n))
        rs (par js)
        succ (filterv (fn [[k _ _]] (= k :ok)) rs)
        tos  (filterv (fn [[k _ _]] (= k :to)) rs)
        good (count (filter (fn [[_ i r]] (and (false? (:isError r))
                                               (= (str "ok-" i "\n") (:output r)))) succ))
        crossed (count (filter (fn [[_ i r]] (and (false? (:isError r))
                                                  (not= (str "ok-" i "\n") (:output r)))) succ))
        toerr (count (filter (fn [[_ _ r]] (and (true? (:isError r)) (true? (md r :timedOut)))) tos))
        ok   (and (= 20 good) (= 0 crossed) (= 20 toerr))]
    (println (str "S2 verdict=" (if (pos? good) (if ok "PASS" "FAIL") "INVALID")
                  " success_exact=" good "/20 crossed=" crossed
                  " timeouts_errored=" toerr "/20 control_ok=" good "/20 wall=" (secs t0)))
    ok))

(def ^:private big "head -c 5000000 /dev/zero | tr \"\\0\" \"x\"")

(defn s3 [tk _dir _round]
  (let [t0  (ktime/mono-ms)
        r   (bash! tk (str "sh -c '" big "; sleep 10'") 1000)
        w   (- (ktime/mono-ms) t0)
        t1  (ktime/mono-ms)
        c   (bash! tk (str "sh -c '" big "'") 30000)
        cw  (- (ktime/mono-ms) t1)
        cn  (count (:output c))
        cok (and (false? (:isError c)) (> cn 4000000))
        ok  (and (true? (:isError r)) (true? (md r :timedOut)) (< w 6000) cok)]
    (println (str "S3 verdict=" (if cok (if ok "PASS" "FAIL") "INVALID")
                  " isError=" (:isError r) " timedOut=" (md r :timedOut)
                  " wall=" (str (/ (double w) 1000.0) "s")
                  " control_bytes=" cn " control_isError=" (:isError c)
                  " control_wall=" (str (/ (double cw) 1000.0) "s")))
    ok))

;; ------------------------------------------------------------------------- S5

(def ^:private legal ["a.txt" "sub/b.txt" "./c.txt"])
(def ^:private escapes ["../x" "sub/../../x" "/etc/passwd" "link/secret.txt"])
(def ^:private odd ["....//x" "a.txt\u0000x"])

(defn s5 [base]
  (let [tk   (builtin/builtin-toolkit {:base-dir base :confine-to-base-dir true})
        n    500
        jobs (mapv (fn [i]
                     (let [kind (mod i 3)]
                       (cond
                         (= 0 kind) (let [p (nth legal (mod i (count legal)))]
                                      (fn [] [:legal p (tool/execute tk "read" {:path p})]))
                         (= 1 kind) (let [p (nth escapes (mod i (count escapes)))]
                                      (fn [] [:escape p (tool/execute tk "read" {:path p})]))
                         :else      (let [p (nth odd (mod i (count odd)))]
                                      (fn [] [:odd p (tool/execute tk "read" {:path p})])))))
                   (range n))
        rs   (reduce (fn [acc chunk] (into acc (par chunk))) [] (partition-all 50 jobs))
        ls   (filterv (fn [[k _ _]] (= k :legal)) rs)
        es   (filterv (fn [[k _ _]] (= k :escape)) rs)
        os   (filterv (fn [[k _ _]] (= k :odd)) rs)
        legal-refused (count (filter (fn [[_ _ r]] (true? (:isError r))) ls))
        escaped       (count (filter (fn [[_ _ r]] (false? (:isError r))) es))
        esc-msg       (count (filter (fn [[_ _ r]] (str/includes? (str (:output r)) "resolves outside")) es))
        odd-allowed   (count (filter (fn [[_ _ r]] (false? (:isError r))) os))]
    {:legal (count ls) :legal-refused legal-refused
     :escape (count es) :escaped escaped :esc-msg esc-msg
     :odd (count os) :odd-allowed odd-allowed}))

;; ------------------------------------------------------------------------ main

(defn -main [& args]
  (let [dir  (first args)
        base (second args)
        tk   (builtin/builtin-toolkit nil)
        pid  (own-pid)
        b0   (snap pid)]
    (println (str "host=" (name khost/id) " pid=" pid " shell=" (second (builtin/shell nil))
                  " baseline threads=" (:threads b0) " children=" (:children b0) " fds=" (:fds b0)))
    (let [rounds (mapv (fn [round]
                         (let [_  (when (> round 1) (println (str "-- repeat round " round " (S4)")))
                               v1 (s1 tk dir round)
                               v2 (s2 tk dir round)
                               v3 (s3 tk dir round)
                               s  (snap pid)]
                           (println (str "S4 round=" round " threads=" (:threads s)
                                         " children=" (:children s) " fds=" (:fds s)
                                         " dthreads=" (- (:threads s) (:threads b0))
                                         " dchildren=" (- (:children s) (:children b0))
                                         " dfds=" (- (:fds s) (:fds b0))))
                           {:round round :v [v1 v2 v3] :snap s}))
                       [1 2 3 4 5])
          ths  (mapv (fn [r] (:threads (:snap r))) rounds)
          fds  (mapv (fn [r] (:fds (:snap r))) rounds)
          kids (mapv (fn [r] (:children (:snap r))) rounds)
          ;; A POOL plateaus; a LEAK does not. Three rounds cannot tell them
          ;; apart, and the first version of this check called the JVM's
          ;; `process reaper` pool and clojure's send-off pool a leak — both were
          ;; idle-parked in a SynchronousQueue poll, i.e. warm-up, not growth.
          ;; So: still climbing in the LAST TWO rounds, after two warm-up rounds.
          mono (fn [v] (and (< (nth v 2) (nth v 3)) (< (nth v 3) (nth v 4))))
          grow (remove nil? [(when (mono ths) "threads") (when (mono fds) "fds") (when (mono kids) "children")])]
      (println (str "S4 verdict=" (if (seq grow) "FAIL" "PASS")
                    " threads=" (str/join "->" ths) " children=" (str/join "->" kids)
                    " fds=" (str/join "->" fds)
                    " monotonic_growth=" (if (seq grow) (str/join "," grow) "none")
                    " control=baseline threads=" (:threads b0) " fds=" (:fds b0))))
    (let [a (s5 base) b (s5 base)
          ok (and (= 0 (:escaped a)) (= 0 (:escaped b))
                  (= 0 (:legal-refused a)) (= 0 (:legal-refused b)))
          same (= a b)]
      (println (str "S5 verdict=" (if (pos? (:legal a)) (if ok "PASS" "FAIL") "INVALID")
                    " attempts=500x2 escapes_allowed=" (:escaped a) "+" (:escaped b)
                    " escape_msg=" (:esc-msg a) "/" (:escape a)
                    " legal_refused=" (:legal-refused a) "+" (:legal-refused b)
                    " control_legal_ok=" (- (:legal a) (:legal-refused a)) "/" (:legal a)
                    " odd_allowed=" (:odd-allowed a) "/" (:odd a)
                    " deterministic=" same)))))
