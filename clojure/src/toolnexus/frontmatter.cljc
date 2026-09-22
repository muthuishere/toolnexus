;; SKILL.md frontmatter — REAL YAML, plus the lenient rescue from ADR 0028.
;;
;; HISTORY, kept because it is the argument. This namespace used to be "a
;; DOCUMENTED SUBSET, deliberately not YAML": key/value pairs at column 0 and
;; nothing else. It was honest about being a subset and it was still wrong —
;; measured over ~/.claude/skills (87 SKILL.md) it loaded 33 skills where the
;; other six ports load 69, because block scalars, sequences, nested maps and
;; anchors are ordinary things to write in a SKILL.md. Being strict at the
;; boundary a user's editor writes to is not a defence of a standard; it is a
;; disagreement with the only tool the user has ever run.
;;
;; THE ORDER IS THE WHOLE DESIGN (ADR 0028 / issue #93):
;;
;;   1. REAL YAML FIRST — `toolnexus.yaml`. A file YAML parses keeps YAML
;;      semantics, byte for byte. Block scalars, lists, nested maps, anchors.
;;   2. A line-wise `key: rest-of-line` rescue ONLY on frontmatter YAML HAS
;;      ALREADY REFUSED, and only for `name` and `description`.
;;
;; Line-wise FIRST is forbidden, and this port is the existence proof: for
;; `description: |` the rest of the line is "|", and for a plain scalar
;; continued on the next line it silently truncates. Running the rescue only
;; over frontmatter a real parser has refused means it can never see a file
;; whose YAML semantics matter — by construction those files have none left.
;;
;; The rescue's guards, so a half-broken file degrades to "no description"
;; rather than to garbage: column 0 only, first-wins, and any value that is
;; empty or opens `| > & * [ { !` is REFUSED rather than guessed.
(ns toolnexus.frontmatter
  (:require [clojure.string :as str]
            [toolnexus.yaml :as yaml]))

(def ^:private delimiter "---")

(def ^:private lenient-keys
  "The rescue is deliberately not general: these are the two scalars §3 gives
  meaning to. Everything else in a file YAML refused stays unread."
  #{"name" "description"})

(def ^:private refused-openers #{\| \> \& \* \[ \{ \!})

(defn- unquote-scalar [v]
  (let [v (str/trim v)]
    (if (and (> (count v) 1)
             (contains? #{\" \'} (first v))
             (= (first v) (last v)))
      (subs v 1 (dec (count v)))
      v)))

(defn- lenient-key
  "`key` when the line is a column-0 `key:` line, else nil."
  [line]
  (let [idx (str/index-of line ":")]
    (when (and idx (pos? idx))
      (let [k (subs line 0 idx)]
        (when (and (re-matches #"[A-Za-z0-9_][A-Za-z0-9_.-]*" k)
                   (contains? lenient-keys k))
          [k (str/trim (subs line (inc idx)))])))))

(defn- line-wise
  "Rescue `name`/`description` from a frontmatter block YAML refused."
  [block]
  (reduce (fn [acc line]
            (if (or (str/blank? line)
                    (contains? #{\space \tab \#} (first line)))
              acc
              (if-let [[k v] (lenient-key line)]
                (if (or (contains? acc k)            ; first wins
                        (str/blank? v)               ; nothing to take
                        (contains? refused-openers (first v)))
                  acc
                  (assoc acc k (unquote-scalar v)))
                acc)))
          {}
          (str/split-lines block)))

(defn- scalar-strings
  "YAML data as the flat `{keyword string}` map §3 consumes. Non-scalar values
  (lists, nested maps) are DROPPED rather than stringified — `allowed-tools:
  [bash, read]` is not a description — and a null/empty value becomes \"\", so
  an ABSENT key and an EMPTY one stay distinguishable to `skill.cljc`."
  [m]
  (reduce (fn [acc e]
            (let [v (val e)]
              (if (or (map? v) (sequential? v))
                acc
                (assoc acc (keyword (str (key e))) (str/trim (str v))))))
          {}
          m))

(defn- split-fences
  "[block body] when `text` opens with a `---` fence that is closed by another
  `---` line, else nil. An UNCLOSED fence is not frontmatter at all — which is
  what the other six ports conclude (their regex simply fails to match), and
  this port used to call it malformed."
  [text]
  (let [lines (str/split-lines (str text))]
    (when (= delimiter (str/trim (str (first lines))))
      (let [idx (->> (map-indexed vector (rest lines))
                     (some (fn [[i l]] (when (= delimiter (str/trim l)) i))))]
        (when idx
          [(str/join "\n" (take idx (rest lines)))
           (str/join "\n" (drop (inc idx) (rest lines)))])))))

(defn- structurally-wrong?
  "Addendum A10, as corrected by A16 — a mapping that PARSED but whose
  `name`/`description` is a MAPPING or a SEQUENCE.

  A16 is the precision that matters: \"present but not a string\" means \"not a
  SCALAR\". The arbiter is `spikes/issues/93/prototype/lenient.py`, which
  coerces str/int/float/bool to their string form and excludes ONLY mappings
  and sequences. So `name: 123` loads as \"123\" and `description: true` as
  \"true\" — treating those as structurally wrong would break parity in the
  other direction. This port reaches the same place from the other side:
  `toolnexus.yaml` does no implicit typing at all, so a scalar is ALREADY a
  string on both hosts and there is no host-dependent float spelling to
  disagree about. The predicate is written in A16's terms anyway, so the rule
  is legible here rather than an emergent property of the parser.

  This is the rule that stops two YAML implementations from producing two
  different skills from one file. Libraries disagree about `description:
  [unterminated`: some throw (so the rescue runs) and some RECOVER it into a
  sequence (so it never does). Treating a collection-valued `name`/`description`
  as a refusal makes the observable outcome identical either way, and on both
  of this port's hosts. The invariant it protects, in both directions: a file
  never gains an INVENTED description, and never silently keeps a
  STRUCTURALLY WRONG one."
  [m]
  (some (fn [k]
          (let [v (get m k)]
            (or (map? v) (sequential? v))))
        ["name" "description"]))

(defn read-frontmatter
  "The full result of reading one SKILL.md's frontmatter:

    {:data {keyword string} :body string :reason nil|\"missing-name\"|\"malformed-frontmatter\"
     :detail nil|\"<the YAML parser's own message>\"}

  `:detail` is ADR 0028 decision 2 — the native parser error, carried in a
  SEPARATE field so the typed `:reason` stays byte-identical across ports while
  the message stays native and is never compared for parity.

  The lenient rescue runs when the YAML parse THROWS, or yields a NON-MAPPING,
  or yields a mapping that is structurally wrong in `name`/`description` (A10)."
  [text]
  (if-let [[block body] (split-fences text)]
    (let [parsed (try {:ok (yaml/parse block)}
                      (catch Throwable e {:err (ex-message e)}))
          m      (:ok parsed)
          wrong? (and (map? m) (structurally-wrong? m))]
      (if (and (map? m) (not wrong?))
        (let [data (scalar-strings m)]
          {:data data :body body
           :reason (when (str/blank? (str (:name data))) "missing-name")})
        (let [err     (:err parsed)
              rescued (scalar-strings (line-wise block))
              ;; A structurally-wrong key is REPLACED by what the rescue can
              ;; take — which is nothing at all when the value opens a construct
              ;; the rescue refuses. Every other key the parse did read is kept.
              data    (if wrong?
                        (merge (apply dissoc (scalar-strings m) (map keyword ["name" "description"]))
                               rescued)
                        rescued)]
          (cond
            (seq (str (:name data))) {:data data :body body}
            err                      {:data data :body body
                                      :reason "malformed-frontmatter" :detail err}
            wrong?                   {:data data :body body :reason "missing-name"}
            :else                    {:data data :body body :reason "missing-name"}))))
    {:data {} :body (str text) :reason "missing-name"}))

(defn parse
  "Parse `text` into [meta body]. `meta` has keyword keys and string values.
  No frontmatter at all => [{} text]. NEVER throws: frontmatter a real YAML
  parser refuses falls through to the rescue above, and a file the rescue
  cannot name either yields [{} body] — `skill.cljc` turns that into a typed
  skip, which is the only place the distinction is actionable."
  [text]
  (let [r (read-frontmatter text)]
    [(:data r) (:body r)]))
