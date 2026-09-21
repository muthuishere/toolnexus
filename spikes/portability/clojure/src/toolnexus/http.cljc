;; SPEC §0.9 / §7 — HTTP / REST tools (source: "http").
;;
;;   {placeholder} substitution in the URL, filled from args
;;   ${ENV} expansion in header values — NEVER logged, never returned
;;   non-2xx  => ToolResult{isError:true, output:"HTTP <status>: <body>"}
;;   2xx      => ToolResult{output:<body>, isError:false, metadata:{status}}
;;
;; A transport failure (the connection never produced a status at all) is the
;; case §0.9 does not name, and it is the one that decides whether this port is
;; portable. koine.http/request NEVER throws on one: it returns
;; `{:status nil :error :timeout|:dns|:connect-failed|:transport}` as DATA,
;; because the host exception TYPES cannot be named portably — the JVM has
;; java.net.* classes, cljgo has Go errors, and a `catch` that names either is a
;; reader conditional in disguise. So this namespace branches on `http/failed?`
;; and reports `(:error res)` by name. It never catches a class.
(ns toolnexus.http
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.http :as khttp]
            [koine.json :as json]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; percent-encoding
;; ---------------------------------------------------------------------------
;;
;; Hand-rolled: `java.net.URLEncoder` is java.*, and cljgo's Go equivalent is
;; not reachable without interop. It is 20 lines of clojure.core, so both hosts
;; run the same code rather than two encoders that agree until they don't.

(def ^:private hex-digits "0123456789ABCDEF")

(defn- utf8-units
  "The UTF-8 bytes of a character, as ints in 0..255. A code point outside the
  BMP arrives as a surrogate PAIR and is encoded as two 3-byte sequences
  (CESU-8, not UTF-8) — identically on both hosts, so it is a documented limit
  rather than a divergence."
  [c]
  (let [v (int c)]
    (cond
      (< v 0x80)  [v]
      (< v 0x800) [(bit-or 0xC0 (bit-shift-right v 6))
                   (bit-or 0x80 (bit-and v 0x3F))]
      :else       [(bit-or 0xE0 (bit-shift-right v 12))
                   (bit-or 0x80 (bit-and (bit-shift-right v 6) 0x3F))
                   (bit-or 0x80 (bit-and v 0x3F))])))

(defn- unreserved?
  "RFC 3986 unreserved: A-Z a-z 0-9 - _ . ~ — tested by explicit code-point
  range, not a character class, because regex dialects differ per host."
  [c]
  (let [v (int c)]
    (or (and (>= v (int \A)) (<= v (int \Z)))
        (and (>= v (int \a)) (<= v (int \z)))
        (and (>= v (int \0)) (<= v (int \9)))
        (= c \-) (= c \_) (= c \.) (= c \~))))

(defn url-encode
  "Percent-encode `s` (RFC 3986 unreserved set kept, everything else %XX)."
  [s]
  (apply str
         (mapcat (fn [c]
                   (if (unreserved? c)
                     [c]
                     (mapcat (fn [b] ["%" (nth hex-digits (bit-shift-right b 4))
                                      (nth hex-digits (bit-and b 0xF))])
                             (utf8-units c))))
                 (str s))))

;; ---------------------------------------------------------------------------
;; URL assembly
;; ---------------------------------------------------------------------------

(defn placeholders
  "The `{name}` placeholders in `template`, in order of appearance. Scanned by
  hand rather than with `re-seq` + a capture group: capture-group semantics are
  the part of regex most likely to differ between java.util.regex and RE2, and
  this is plain clojure.core on every host."
  [template]
  (let [s (str template)
        n (count s)
        legal? (fn [c] (or (unreserved? c) (= c \_)))]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (if (= \{ (nth s i))
          (let [j (loop [k (inc i)]
                    (cond (>= k n)            nil
                          (= \} (nth s k))    k
                          (legal? (nth s k))  (recur (inc k))
                          :else               nil))]
            (if (and j (> j (inc i)))
              (recur (inc j) (conj acc (subs s (inc i) j)))
              (recur (inc i) acc)))
          (recur (inc i) acc))))))

(defn substitute
  "§0.9 — `{placeholder}` substitution from `args`.

  UNPINNED IN THE SPEC: §0.9 says 'substitution' and never says whether the
  value is percent-encoded. It is encoded here, because a value with a space
  otherwise produces a URL the JVM's `URI/create` rejects outright — i.e. the
  unencoded reading turns an ordinary argument into a transport failure. Report
  this so §0.9 pins it for all six ports rather than each port guessing.

  A placeholder with no matching arg is left VERBATIM rather than blanked: a
  half-built URL that 404s is debuggable, a silently mangled one is not."
  [template args]
  (reduce (fn [acc [k v]]
            (str/replace acc (str "{" (name k) "}") (url-encode v)))
          (str template)
          args))

(defn query-string
  "The `?`-less querystring for the `:query` names, in DECLARATION order, taking
  each value from `args`; nil when nothing matched. Names and values are
  percent-encoded, and an absent arg contributes no pair at all (rather than an
  empty one) so an optional parameter is genuinely optional.

  Public for the same reason `placeholders` / `substitute` / `url-encode` are:
  neither host's HTTP server exposes the query portably to the handler (the
  JVM's `URI.getPath` strips it, cljgo's `:uri` keeps it), so an end-to-end test
  cannot see it. Testing this directly is the only way a `:query` that is
  silently dropped fails a test instead of an API call."
  [args names]
  (let [pairs (->> names
                   (map (fn [q] [(name q) (get args (keyword (name q)))]))
                   (filter (fn [p] (some? (nth p 1)))))]
    (when (seq pairs)
      (str/join "&" (map (fn [p] (str (url-encode (nth p 0)) "="
                                      (url-encode (nth p 1))))
                         pairs)))))

(defn- expand-headers
  "§0.9 — `${ENV}` expansion in header VALUES. Those values routinely carry
  credentials: nothing in this namespace logs, prints, or copies a header value
  into a ToolResult."
  [headers]
  (reduce (fn [acc [k v]] (assoc acc (name k) (env/expand (str v)))) {} headers))

(defn- encode-body [mode m]
  (cond
    (empty? m)      nil
    (= "form" mode) (str/join "&" (map (fn [p] (str (url-encode (name (nth p 0))) "="
                                                    (url-encode (nth p 1))))
                                       (sort-by (fn [p] (name (nth p 0))) (vec m))))
    (= "raw" mode)  (str (nth (first (sort-by (fn [p] (name (nth p 0))) (vec m))) 1))
    :else           (json/write-str m)))

(defn- content-type-for [mode]
  (cond
    (= "form" mode) "application/x-www-form-urlencoded"
    (= "raw" mode)  "text/plain"
    :else           "application/json"))

(defn- render-result [mode res]
  (let [st   (long (:status res))
        body (str (:body res))]
    (cond
      (= "status+text" mode) (tool/success (str "HTTP " st "\n" body) {:status st})
      ;; "json" is a §7 mode NAME with no pinned bytes — SPEC never says whether
      ;; it re-serialises. Canonicalising through koine.json (whose write-str
      ;; sorts keys) at least makes six ports comparable; a body that is not
      ;; JSON falls through unchanged rather than erroring.
      (= "json" mode)        (tool/success (try (json/write-str (json/read-str body {:key-fn keyword}))
                                           (catch Throwable _ body))
                                      {:status st})
      :else                  (tool/success body {:status st}))))

;; ---------------------------------------------------------------------------
;; the tool
;; ---------------------------------------------------------------------------

(defn http-tool
  "Declare a remote endpoint as a Tool (§0.9 / §7).

      (http-tool {:name \"getUser\"
                  :description \"Fetch a user\"
                  :input-schema {:type \"object\"}
                  :method :get
                  :url \"https://api.example.com/users/{id}\"
                  :headers {\"authorization\" \"Bearer ${API_TOKEN}\"}
                  :query [:verbose]
                  :body \"json\"           ; \"json\" (default) | \"form\" | \"raw\"
                  :timeout-ms 30000
                  :result-mode \"text\"})  ; \"text\" | \"json\" | \"status+text\"

  Args are consumed in this order: URL placeholders first, then `:query` names,
  and whatever is left becomes the request body (non-GET only)."
  [opts]
  (let [{:keys [description input-schema method url headers query body
                timeout-ms result-mode]
         :or   {method :get body "json" timeout-ms 30000 result-mode "text"}} opts
        ;; NOT destructured as `name`: that would shadow clojure.core/name for
        ;; the whole body, and `name` is used on every keyword below.
        tool-name (:name opts)
        verb      (keyword (str/lower-case (name method)))]
    (tool/tool
      {:name         tool-name
       :description  description
       :input-schema input-schema
       :source       "http"
       :execute
       (fn execute-http
         ([args] (execute-http args nil))
         ([args _ctx]
          (let [args      (or args {})
                consumed  (mapv keyword (placeholders url))
                rest-args (apply dissoc args consumed)
                qnames    (mapv (fn [q] (keyword (name q))) query)
                qs        (query-string rest-args qnames)
                body-args (apply dissoc rest-args qnames)
                send-body (when-not (= :get verb) (encode-body body body-args))
                hdrs      (cond-> (expand-headers headers)
                            send-body (assoc "content-type" (content-type-for body)))
                full-url  (str (substitute url args) (when qs (str "?" qs)))
                res       (khttp/request (cond-> {:method verb :url full-url
                                                  :headers hdrs :timeout-ms timeout-ms}
                                           send-body (assoc :body send-body)))]
            (cond
              ;; DATA, not a caught class — see the ns docstring.
              (khttp/failed? res)
              (tool/failure (str "HTTP transport failure: " (name (:error res)))
                        {:error (:error res)})

              (or (< (long (:status res)) 200) (> (long (:status res)) 299))
              (tool/failure (str "HTTP " (:status res) ": " (:body res))
                        {:status (long (:status res))})

              :else (render-result result-mode res)))))})))
