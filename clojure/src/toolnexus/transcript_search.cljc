(ns toolnexus.transcript-search
  "`transcript-search-tool` — read-only search over a ConversationStore (SPEC.md
  §7E \"Memory hierarchy\" → transcript search). The ground-truth tier of agent
  memory: a dreaming persona checks its notes against what actually happened,
  not against its own notes.

  Positions and lengths are in CODE POINTS. Neither host's `count` answers that
  (UTF-16 units on the JVM, runes on cljgo), so text is handled here as a vector
  of one-code-point STRINGS — `code-point-strings` — which compares, slices and
  re-joins identically everywhere without a code-point -> string encoder.

  No java.*, no reader conditionals."
  (:require [clojure.string :as str]
            [koine.json :as json]
            [toolnexus.native :as native]
            [toolnexus.tool :as tool]))

(def description
  "The tool description (§7E, exact)."
  (str "Search past conversation transcripts — the ground truth of what actually happened. "
       "Case-insensitive substring match over message text. Returns one line per matching "
       "message: <conversationId>#<index> <role>: <snippet>. Read-only."))

(defn code-point-strings
  "`s` as a vector of strings, one code point each. A surrogate pair (JVM) stays
  together; on a rune-indexed host every character already is one code point."
  [s]
  (let [s (str s)
        n (count s)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (int (nth s i))]
          (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) n)
                   (let [lo (int (nth s (inc i)))] (and (>= lo 0xDC00) (<= lo 0xDFFF))))
            (recur (+ i 2) (conj acc (subs s i (+ i 2))))
            (recur (inc i) (conj acc (subs s i (inc i))))))))))

(defn- normalize
  "Every run of ASCII whitespace (space \\t \\n \\r \\f \\v) ⇒ one space, trimmed."
  [s]
  (-> (str/replace (str s) #"[ \t\n\r\f\x0B]+" " ")
      (str/replace #"^ | $" "")))

(defn- fold
  "ASCII-only case fold over a code-point-string vector — never Unicode
  lowercasing, which changes lengths and differs per host."
  [cps]
  (mapv (fn [c] (if (re-matches #"[A-Z]" c) (str/lower-case c) c)) cps))

;; Compact JSON, object keys sorted by CODE POINT at every depth (§7E); arrays
;; keep their order; non-ASCII emitted as-is. Own encoder so the key order and
;; escaping are the same on every host.
(defn- json-str [s]
  (str "\""
       (apply str (map (fn [c]
                         (case c
                           "\"" "\\\"" "\\" "\\\\" "\n" "\\n" "\r" "\\r" "\t" "\\t"
                           "\b" "\\b" "\f" "\\f"
                           (let [cp (int (nth c 0))]
                             (if (< cp 0x20)
                               (str "\\u00" (nth "0123456789abcdef" (quot cp 16))
                                    (nth "0123456789abcdef" (rem cp 16)))
                               c))))
                       (code-point-strings s)))
       "\""))

(defn- code-point [c]
  (let [hi (int (nth c 0))]
    (if (= 2 (count c))
      (+ 0x10000 (* (- hi 0xD800) 0x400) (- (int (nth c 1)) 0xDC00))
      hi)))

(defn- by-code-point
  "Lexicographic by code point (vector `compare` would order by length first)."
  [a b]
  (loop [x (map code-point (code-point-strings a))
         y (map code-point (code-point-strings b))]
    (cond
      (and (empty? x) (empty? y)) 0
      (empty? x) -1
      (empty? y) 1
      (not= (first x) (first y)) (compare (first x) (first y))
      :else (recur (rest x) (rest y)))))

(defn- key-str [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- compact-json [v]
  (cond
    (map? v)        (str "{" (str/join "," (map (fn [[k x]]
                                                  (str (json-str k) ":" (compact-json x)))
                                                (sort-by first by-code-point
                                                         (map (fn [[k x]] [(key-str k) x]) v)))) "}")
    (sequential? v) (str "[" (str/join "," (map compact-json v)) "]")
    (string? v)     (json-str v)
    (keyword? v)    (json-str (subs (str v) 1))
    :else           (json/write-str v)))

(defn- parts-text [content]
  (cond
    (string? content) [content]
    (sequential? content)
    (vec (mapcat (fn [p]
                   (when (map? p)
                     (case (str (:type p))
                       "text"        (when (string? (:text p)) [(:text p)])
                       "tool_use"    [(compact-json (or (:input p) {}))]
                       "tool_result" (parts-text (:content p))
                       nil)))
                 content))
    :else []))

(defn message-text
  "The searchable text of one stored message (§7E): string content, text parts,
  tool_use input as compact JSON, tool_result content (recursively), then each
  OpenAI tool call's `arguments` string — joined by \"\\n\", then normalized."
  [msg]
  (if-not (map? msg)
    ""
    (let [pieces (into (parts-text (:content msg))
                       (keep (fn [tc] (let [a (get-in tc [:function :arguments])]
                                        (when (string? a) a))))
                       (when (sequential? (:tool_calls msg)) (:tool_calls msg)))]
      (normalize (str/join "\n" pieces)))))

(defn- index-of-cps [hay needle]
  (let [n (count needle) h (count hay)]
    (loop [i 0]
      (cond
        (> (+ i n) h) -1
        (= needle (subvec hay i (+ i n))) i
        :else (recur (inc i))))))

(defn- snippet [cps at qlen size]
  (let [len (count cps)]
    (if (<= len size)
      (apply str cps)
      (let [start (max 0 (min (- (+ at (quot qlen 2)) (quot size 2)) (- len size)))
            end   (+ start size)]
        (str (when (pos? start) "…")
             (apply str (subvec cps start end))
             (when (< end len) "…"))))))

(defn- int-at-least-1? [x] (and (integer? x) (>= x 1)))

(defn transcript-search-tool
  "A read-only Tool over `store` (§7E). Construction throws unless the store has
  `:list`. Input `{query, conversationId?, limit?}`; output one line per hit,
  `<conversationId>#<index> <role>: <snippet>`, `no matches` when none, and a
  trailing `(+<k> more)` when hits exceed the limit. Never writes.

  Options: `:max-results` (default 20), `:snippet-chars` (default 200, code
  points), `:name` (default `transcript_search`)."
  ([store] (transcript-search-tool store {}))
  ([store opts]
   (when-not (and (map? store) (fn? (:list store)))
     (throw (ex-info "transcriptSearchTool: the store must implement list()" {})))
   (let [max-results   (if (contains? opts :max-results) (:max-results opts) 20)
         snippet-chars (if (contains? opts :snippet-chars) (:snippet-chars opts) 200)]
     (when-not (int-at-least-1? max-results)
       (throw (ex-info "transcriptSearchTool: maxResults must be an integer >= 1" {})))
     (when-not (int-at-least-1? snippet-chars)
       (throw (ex-info "transcriptSearchTool: snippetChars must be an integer >= 1" {})))
     (native/native-tool
      {:name         (or (:name opts) "transcript_search")
       :description  description
       :input-schema {:type       "object"
                      :properties {:query          {:type "string" :description "Text to find (case-insensitive substring)."}
                                   :conversationId {:type "string" :description "Search only this conversation."}
                                   :limit          {:type "integer"
                                                    :description (str "Most hits to return (default and max "
                                                                      max-results ").")}}
                      :required   ["query"]}
       :run
       (fn [args]
         (let [query (if (string? (:query args)) (normalize (:query args)) "")]
           (cond
             (= "" query) (tool/failure "query is required")

             (and (contains? args :limit) (not (int-at-least-1? (:limit args))))
             (tool/failure "limit must be an integer >= 1")

             :else
             (let [limit (if (some? (:limit args)) (min (:limit args) max-results) max-results)
                   ids   (if (string? (:conversationId args))
                           [(:conversationId args)]
                           (map :id ((:list store))))
                   q     (fold (code-point-strings query))
                   [hits total]
                   (reduce
                    (fn [[hits total] id]
                      (let [msgs ((:get store) id)]
                        (reduce
                         (fn [[hits total] [index msg]]
                           (let [cps (code-point-strings (message-text msg))
                                 at  (index-of-cps (fold cps) q)]
                             (if (neg? at)
                               [hits total]
                               [(if (< (count hits) limit)
                                  (conj hits (str id "#" index " "
                                                  (if (string? (:role msg)) (:role msg) "unknown")
                                                  ": " (snippet cps at (count q) snippet-chars)))
                                  hits)
                                (inc total)])))
                         [hits total]
                         (map-indexed vector (or msgs [])))))
                    [[] 0]
                    ids)]
               (cond
                 (zero? total) "no matches"
                 (> total (count hits)) (str/join "\n" (conj hits (str "(+" (- total (count hits)) " more)")))
                 :else (str/join "\n" hits))))))}))))
