;; A real YAML reader — SPEC §3's "standard YAML parser", owned by this port.
;;
;; WHY IT LIVES HERE. koine declined to own YAML (ADR 0009: YAML touches no
;; host, so it is not a seam), and the port cannot reach for SnakeYAML: the
;; DEFAULT classpath is gated to clojure + koine (`deps-purity-check.sh`), and
;; a JVM library is unusable on the cljgo half of the `.cljc` dual host anyway.
;; So the parser is written once, in portable Clojure, and BOTH hosts run the
;; same bytes. That is stronger than two host YAML libraries behind one seam,
;; which is what ADR 0028 assumed was the only route.
;;
;; WHAT CHANGED. The namespace this replaces was `frontmatter`'s hand-rolled
;; "documented subset, deliberately not YAML". Measured against ~/.claude/skills
;; (87 SKILL.md) it loaded 33 where the other six ports load 69, because block
;; scalars, sequences, nested maps and anchors were all rejected by design. A
;; documented divergence is still a divergence, and in the loader most exposed
;; to user files it was the largest parity break in the project.
;;
;; WHAT IT COVERS — everything frontmatter in the wild actually uses:
;;   block mappings (nested), block sequences, block scalars (`|`, `>` with
;;   chomping + explicit indent), single/double quoted scalars, multi-line plain
;;   scalars, flow collections (multi-line, balanced), anchors + aliases, tags
;;   (parsed and discarded), `#` comments, `---`/`...` document markers.
;; And it REFUSES what a real YAML parser refuses, which is the half that makes
;; the lenient fallback in `frontmatter` safe: an unquoted `": "` inside a plain
;; scalar, tab indentation, an unterminated flow collection, an undefined alias.
;;
;; SCALARS ARE STRINGS. There is no implicit typing (`2` stays "2", `true` stays
;; "true"); only the null forms (empty, `~`, `null`) become nil. The sole
;; consumer is frontmatter, which stringifies everything anyway, and NOT typing
;; is the one way two hosts cannot disagree about a float's printed form.
;;
;; ZERO reader conditionals. ZERO `java.*`. clojure.core + clojure.string only.
(ns toolnexus.yaml
  (:require [clojure.string :as str]))

(defn- fail
  "Every refusal is an ex-info carrying `:yaml true` and a 1-based `:line`, so
  `frontmatter` can tell a YAML refusal from a genuine bug and put the message
  in a skip record's `detail` (ADR 0028 decision 2)."
  [line msg]
  (throw (ex-info (str msg " at line " line) {:yaml true :line line})))

(def ^:private null-forms #{"" "~" "null" "Null" "NULL"})

;; ---------------------------------------------------------------------------
;; Line helpers
;; ---------------------------------------------------------------------------

(defn- strip-cr [s]
  (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s))

(defn- spaces-at [s]
  (loop [i 0]
    (if (and (< i (count s)) (= \space (nth s i))) (recur (inc i)) i)))

(defn- check-tabs! [s n]
  (let [i (spaces-at s)]
    (when (and (< i (count s)) (= \tab (nth s i)))
      (fail n "tabs are not allowed as indentation"))))

(defn- content-line? [s]
  (and (not (str/blank? s))
       (not (str/starts-with? (str/triml s) "#"))))

(defn- next-content
  "Index of the next line that is neither blank nor a comment. Tab indentation
  is refused here, where structure is read — never inside a block scalar, whose
  body may legitimately contain tabs past its indent."
  [lines i]
  (loop [i i]
    (if (>= i (count lines))
      i
      (let [s (nth lines i)]
        (if (content-line? s)
          (do (check-tabs! s (inc i)) i)
          (recur (inc i)))))))

(defn- doc-marker? [s]
  (let [t (str/trimr s)]
    (or (= t "---") (= t "...")
        (str/starts-with? t "--- ") (str/starts-with? t "... "))))

;; ---------------------------------------------------------------------------
;; Scalars
;; ---------------------------------------------------------------------------

(defn- read-double [s p n]
  (loop [i (inc p) out []]
    (cond
      (>= i (count s)) (fail n "unterminated double-quoted scalar")
      (= \\ (nth s i))
      (let [c (when (< (inc i) (count s)) (nth s (inc i)))]
        (recur (+ i 2)
               (conj out (case c
                           \n \newline \t \tab \r \return \0 \o000
                           \\ \\ \" \" \' \' \/ \/
                           (or c \\)))))
      (= \" (nth s i)) [(apply str out) (inc i)]
      :else (recur (inc i) (conj out (nth s i))))))

(defn- read-single [s p n]
  (loop [i (inc p) out []]
    (cond
      (>= i (count s)) (fail n "unterminated single-quoted scalar")
      (= \' (nth s i))
      (if (and (< (inc i) (count s)) (= \' (nth s (inc i))))
        (recur (+ i 2) (conj out \'))
        [(apply str out) (inc i)])
      :else (recur (inc i) (conj out (nth s i))))))

(defn- comment-start?
  "`#` opens a comment only when it starts the value or follows whitespace —
  the YAML rule, and the reason `… with #stockloop and …` truncates rather than
  erroring (measured: the six YAML ports truncate it too)."
  [s i p]
  (and (= \# (nth s i))
       (or (= i p) (contains? #{\space \tab} (nth s (dec i))))))

(defn- read-plain
  "A plain scalar from `p` to wherever YAML says it ends: a `:` that is followed
  by a space or ends the line, or a comment. The scanner STOPS at that colon and
  leaves it in place; the caller turning the leftover into an error is what
  makes `description: … Trigger on: do it` a refusal here and a rescue in
  `frontmatter`."
  [s p]
  (loop [i p out []]
    (cond
      (>= i (count s)) [(str/trimr (apply str out)) i]

      (and (= \: (nth s i))
           (or (= (inc i) (count s)) (contains? #{\space \tab} (nth s (inc i)))))
      [(str/trimr (apply str out)) i]

      (comment-start? s i p) [(str/trimr (apply str out)) (count s)]

      :else (recur (inc i) (conj out (nth s i))))))

(defn- plain-value [raw]
  (if (contains? null-forms raw) nil raw))

;; ---------------------------------------------------------------------------
;; Flow collections — gathered across lines, then parsed
;; ---------------------------------------------------------------------------

(defn- scan-flow
  "Collect a balanced flow collection starting at `[lines li p]`, joining
  continuation lines with a space. Returns [text pos-after li-after].
  Running off the end is `unterminated flow collection`, not a silent take."
  [lines li p]
  (loop [l li, pos p, depth 0, buf []]
    (if (>= l (count lines))
      (fail (inc li) "unterminated flow collection")
      (let [s (nth lines l)]
        (if (>= pos (count s))
          (recur (inc l) 0 depth (conj buf \space))
          (let [c (nth s pos)]
            (cond
              (or (= c \") (= c \'))
              (let [[_ np] (if (= c \") (read-double s pos (inc l)) (read-single s pos (inc l)))]
                (recur l np depth (into buf (seq (subs s pos np)))))

              (or (= c \[) (= c \{)) (recur l (inc pos) (inc depth) (conj buf c))

              (or (= c \]) (= c \}))
              (let [d (dec depth)]
                (if (zero? d)
                  [(apply str (conj buf c)) (inc pos) l]
                  (recur l (inc pos) d (conj buf c))))

              :else (recur l (inc pos) depth (conj buf c)))))))))

(declare ^:private flow-node)

(defn- flow-skip [s i]
  (loop [i i] (if (and (< i (count s)) (contains? #{\space \tab} (nth s i))) (recur (inc i)) i)))

(defn- flow-scalar [s p n]
  (loop [i p out []]
    (if (or (>= i (count s)) (contains? #{\, \] \}} (nth s i)))
      [(str/trim (apply str out)) i]
      (recur (inc i) (conj out (nth s i))))))

(defn- flow-seq [s p n]
  (loop [i (inc p) acc []]
    (let [i (flow-skip s i)]
      (cond
        (>= i (count s)) (fail n "unterminated flow sequence")
        (= \] (nth s i)) [acc (inc i)]
        (= \, (nth s i)) (recur (inc i) acc)
        :else (let [[v ni] (flow-node s i n)] (recur ni (conj acc v)))))))

(defn- flow-map [s p n]
  (loop [i (inc p) acc {}]
    (let [i (flow-skip s i)]
      (cond
        (>= i (count s)) (fail n "unterminated flow mapping")
        (= \} (nth s i)) [acc (inc i)]
        (= \, (nth s i)) (recur (inc i) acc)
        :else
        (let [[k ki] (flow-node s i n)
              ki     (flow-skip s ki)]
          (if (and (< ki (count s)) (= \: (nth s ki)))
            (let [vi     (flow-skip s (inc ki))
                  [v ni] (if (or (>= vi (count s)) (contains? #{\, \}} (nth s vi)))
                           [nil vi]
                           (flow-node s vi n))]
              (recur ni (assoc acc (str k) v)))
            (recur ki (assoc acc (str k) nil))))))))

(defn- flow-node [s p n]
  (let [c (nth s p)]
    (cond
      (= c \[) (flow-seq s p n)
      (= c \{) (flow-map s p n)
      (= c \") (read-double s p n)
      (= c \') (read-single s p n)
      :else    (let [[raw i] (flow-scalar s p n)] [(plain-value raw) i]))))

;; ---------------------------------------------------------------------------
;; Block scalars (`|` and `>`)
;; ---------------------------------------------------------------------------

(defn- block-scalar
  "`|`/`>` with optional chomping (`-`/`+`) and an explicit indent digit.
  Returns [string next-i]."
  [lines li header parent-ind]
  (let [folded?  (str/starts-with? header ">")
        rest-h   (subs header 1)
        chomp    (cond (str/includes? rest-h "-") :strip
                       (str/includes? rest-h "+") :keep
                       :else :clip)
        explicit (some (fn [c] (when (and (>= (int c) (int \1)) (<= (int c) (int \9)))
                                 (- (int c) (int \0))))
                       (seq rest-h))
        ;; Everything more indented than the key belongs to the block.
        end      (loop [i (inc li)]
                   (if (>= i (count lines))
                     i
                     (let [s (nth lines i)]
                       (if (or (str/blank? s) (> (spaces-at s) parent-ind)) (recur (inc i)) i))))
        raw      (subvec lines (inc li) end)
        ind      (or (and explicit (+ parent-ind explicit))
                     (some (fn [s] (when-not (str/blank? s) (spaces-at s))) raw)
                     (inc parent-ind))
        body     (mapv (fn [s] (if (str/blank? s) "" (if (>= (count s) ind) (subs s ind) (str/triml s)))) raw)
        joined   (if folded?
                   (str/join (loop [ls body, out [], prev-blank? false]
                               (if (empty? ls)
                                 out
                                 (let [l (first ls)]
                                   (cond
                                     (str/blank? l) (recur (rest ls) (conj out "\n") true)
                                     (empty? out)   (recur (rest ls) (conj out l) false)
                                     prev-blank?    (recur (rest ls) (conj out l) false)
                                     :else          (recur (rest ls) (conj out " " l) false))))))
                   (str/join "\n" body))
        trimmed  (str/replace joined #"\n+$" "")
        trailing (count (take-while str/blank? (reverse body)))]
    [(case chomp
       :strip trimmed
       :keep  (str trimmed (apply str (repeat (if (str/blank? trimmed) 0 (inc trailing)) "\n")))
       (if (str/blank? trimmed) trimmed (str trimmed "\n")))
     end]))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(declare ^:private parse-block)

(defn- inline-node
  "A node written on the same line as its key (or `-`). Returns a map
  {:value :pos :li :plain?}. Anchors, aliases and tags are handled here."
  [lines li p st]
  (let [s (nth lines li)
        n (inc li)
        c (nth s p)]
    (cond
      (= c \&)
      (let [e    (loop [i p] (if (and (< i (count s)) (not (contains? #{\space \tab} (nth s i)))) (recur (inc i)) i))
            nm   (subs s (inc p) e)
            rest (flow-skip s e)]
        (if (>= rest (count s))
          (do (swap! st assoc nm nil) {:value nil :pos (count s) :li li :plain? false :anchor nm})
          (let [node (inline-node lines li rest st)]
            (swap! st assoc nm (:value node))
            (assoc node :anchor nm))))

      (= c \*)
      (let [e  (loop [i p] (if (and (< i (count s)) (not (contains? #{\space \tab \, \] \}} (nth s i)))) (recur (inc i)) i))
            nm (subs s (inc p) e)]
        (when-not (contains? @st nm) (fail n (str "found undefined alias '" nm "'")))
        {:value (get @st nm) :pos e :li li :plain? false})

      (= c \!)
      (let [e    (loop [i p] (if (and (< i (count s)) (not (contains? #{\space \tab} (nth s i)))) (recur (inc i)) i))
            rest (flow-skip s e)]
        (if (>= rest (count s))
          {:value nil :pos (count s) :li li :plain? false}
          (inline-node lines li rest st)))

      (= c \") (let [[v np] (read-double s p n)] {:value v :pos np :li li :plain? false})
      (= c \') (let [[v np] (read-single s p n)] {:value v :pos np :li li :plain? false})

      (or (= c \[) (= c \{))
      (let [[text np nl] (scan-flow lines li p)
            [v _]        (flow-node text 0 n)]
        {:value v :pos np :li nl :plain? false})

      :else
      (let [[raw np] (read-plain s p)]
        {:value (plain-value raw) :pos np :li li :plain? true :raw raw}))))

(defn- read-key
  "The `key:` at `p`, or nil when this line is not a mapping entry.
  Returns [key pos-after-colon]."
  [s p n]
  (let [c (nth s p)]
    (if (or (= c \") (= c \'))
      (let [[k np] (if (= c \") (read-double s p n) (read-single s p n))
            np2    (flow-skip s np)]
        (when (and (< np2 (count s)) (= \: (nth s np2))) [k (inc np2)]))
      (loop [i p depth 0]
        (cond
          (>= i (count s)) nil
          (and (zero? depth) (= \: (nth s i))
               (or (= (inc i) (count s)) (contains? #{\space \tab} (nth s (inc i)))))
          [(str/trimr (subs s p i)) (inc i)]
          (contains? #{\[ \{} (nth s i)) (recur (inc i) (inc depth))
          (contains? #{\] \}} (nth s i)) (recur (inc i) (dec depth))
          (comment-start? s i p) nil
          :else (recur (inc i) depth))))))

(defn- nested-or-nil
  "The value of a key whose line ended at the colon: a deeper block, or nil."
  [lines li ind st]
  (let [j (next-content lines (inc li))]
    (if (and (< j (count lines))
             (not (doc-marker? (nth lines j)))
             (> (spaces-at (nth lines j)) ind))
      (parse-block lines j (spaces-at (nth lines j)) st)
      [nil (inc li)])))

(defn- plain-continuation
  "Fold the more-indented lines that continue a plain scalar. A continuation
  carrying its own `key: value` is the classic `mapping values are not allowed
  in this context` — refused, exactly as the six YAML ports refuse it."
  [lines li ind first-raw]
  (loop [k (inc li), parts (if (str/blank? first-raw) [] [first-raw])]
    (if (>= k (count lines))
      [(str/join " " parts) k]
      (let [s (nth lines k)]
        (if (or (not (content-line? s))
                (<= (spaces-at s) ind)
                (doc-marker? s))
          [(str/join " " parts) k]
          (do
            (check-tabs! s (inc k))
            (let [[raw np] (read-plain s (spaces-at s))]
              (when (< np (count s))
                (fail (inc k) "mapping values are not allowed in this context"))
              (recur (inc k) (conj parts raw)))))))))

(defn- entry-value
  "The value for a mapping key (or a `- ` item) whose content starts at `p`.
  Returns [value next-i]."
  [lines li p ind st]
  (let [s    (nth lines li)
        rest (flow-skip s p)]
    (cond
      (or (>= rest (count s)) (comment-start? s rest rest))
      (nested-or-nil lines li ind st)

      (contains? #{\| \>} (nth s rest))
      (block-scalar lines li (str/trim (subs s rest)) ind)

      :else
      (let [node (inline-node lines li rest st)
            s2   (nth lines (:li node))
            left (str/trim (subs s2 (min (count s2) (:pos node))))]
        (cond
          (and (seq left) (not (str/starts-with? left "#")))
          (fail (inc (:li node))
                (if (str/starts-with? left ":")
                  "mapping values are not allowed in this context"
                  (str "unexpected trailing content '" left "'")))

          (:plain? node)
          (let [[v k] (plain-continuation lines (:li node) ind (:raw node))]
            [(plain-value v) k])

          :else [(:value node) (inc (:li node))])))))

(defn- parse-block
  "One block node whose lines are indented exactly `ind`. Returns [value next-i]."
  [lines i ind st]
  (let [j (next-content lines i)]
    (if (>= j (count lines))
      [nil j]
      (let [s (nth lines j)]
        (cond
          (doc-marker? s) [nil j]

          ;; block sequence
          (and (= \- (nth s ind))
               (or (= (inc ind) (count s)) (contains? #{\space \tab} (nth s (inc ind)))))
          (loop [k j acc []]
            (let [m (next-content lines k)]
              (if (or (>= m (count lines))
                      (doc-marker? (nth lines m))
                      (not= ind (spaces-at (nth lines m)))
                      (not= \- (nth (nth lines m) ind)))
                [acc m]
                (let [line (nth lines m)
                      p    (flow-skip line (inc ind))]
                  (if (>= p (count line))
                    (let [[v ni] (nested-or-nil lines m ind st)] (recur ni (conj acc v)))
                    ;; `- key: value` — a mapping whose first line starts here
                    (if (read-key line p (inc m))
                      (let [patched (assoc lines m (str (apply str (repeat p \space)) (subs line p)))
                            [v ni]  (parse-block patched m p st)]
                        (recur ni (conj acc v)))
                      (let [[v ni] (entry-value lines m p ind st)] (recur ni (conj acc v)))))))))

          ;; block mapping
          (read-key s ind (inc j))
          (loop [k j acc {}]
            (let [m (next-content lines k)]
              (if (or (>= m (count lines))
                      (doc-marker? (nth lines m))
                      (< (spaces-at (nth lines m)) ind))
                [acc m]
                (let [line (nth lines m)
                      cur  (spaces-at line)]
                  (when (> cur ind) (fail (inc m) "bad indentation of a mapping entry"))
                  (let [kv (read-key line ind (inc m))]
                    (when-not kv (fail (inc m) "could not find expected ':'"))
                    (let [[key kp] kv]
                      (when (str/blank? key) (fail (inc m) "empty mapping key"))
                      (let [[v ni] (entry-value lines m kp ind st)]
                        (recur ni (assoc acc key v)))))))))

          ;; a bare scalar document
          :else
          (let [[v ni] (entry-value lines j ind ind st)] [v ni]))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn parse
  "Parse `text` as one YAML document. Mappings become maps with STRING keys,
  sequences vectors, scalars strings (nil for the null forms). Anything YAML
  would refuse THROWS an ex-info carrying `:yaml true` and `:line`."
  [text]
  (let [lines (mapv strip-cr (str/split-lines (str text)))
        st    (atom {})
        start (let [j (next-content lines 0)]
                (if (and (< j (count lines)) (= "---" (str/trimr (nth lines j)))) (inc j) j))
        j     (next-content lines start)]
    (if (>= j (count lines))
      nil
      (let [[v i] (parse-block lines j (spaces-at (nth lines j)) st)
            k     (next-content lines i)]
        (when (and (< k (count lines)) (not (doc-marker? (nth lines k))))
          (fail (inc k) "expected a single document"))
        v))))
