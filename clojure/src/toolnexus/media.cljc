;; SPEC §7G — Media generation (source: "media"). ADR 0036.
;;
;; One engine, two faces:
;;
;;   function face  (image m {:prompt "a cat"})        — the host decides
;;   tool face      (tools m) -> generate_image, …      — the LLM decides
;;
;; Providers are STYLES: JSON data, never code. A style says, per verb, whether
;; the call is sync or async (start -> poll -> result), where each normalised
;; argument goes in the body (a dotPath), and where the result URL(s) live.
;; The library names no vendor; presets live in examples/media-styles/.
;;
;; Internally every style is held with STRING keys (a parsed style file and a
;; Clojure-literal style normalise to the same thing), and every response body
;; is parsed with string keys, so a dotPath segment is always a plain map key.
;;
;; Error messages, tool names, schemas and tool results are byte-fixed by
;; conformance/media/ — change a byte there and here together.
(ns toolnexus.media
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.http :as khttp]
            [koine.text :as text]
            [koine.json :as json]
            [koine.time :as ktime]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the canonical verb table — conformance/media/verbs.json, verbatim
;; ---------------------------------------------------------------------------

(def verbs
  "conformance/media/verbs.json `verbs`, embedded verbatim (order, names, types,
  descriptions, required). A test reads the fixture and asserts equality."
  [{:verb "image" :tool "generate_image" :description "Generate an image from a text prompt."
    :args [{:name "prompt" :type "string" :description "What to draw." :required true}
           {:name "width" :type "integer" :description "Width in pixels."}
           {:name "height" :type "integer" :description "Height in pixels."}
           {:name "aspectRatio" :type "string" :description "Aspect ratio, e.g. 16:9."}
           {:name "seed" :type "integer" :description "Seed for reproducible output."}]}
   {:verb "video" :tool "generate_video"
    :description "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes."
    :args [{:name "prompt" :type "string" :description "What should happen in the video." :required true}
           {:name "image" :type "string" :description "URL of the starting image."}
           {:name "seconds" :type "integer" :description "Video length in seconds."}
           {:name "aspectRatio" :type "string" :description "Aspect ratio, e.g. 9:16."}]}
   {:verb "speech" :tool "generate_speech" :description "Speak text aloud and return the audio."
    :args [{:name "text" :type "string" :description "The text to speak." :required true}
           {:name "voice" :type "string" :description "Voice or speaker id."}]}
   {:verb "transcribe" :tool "transcribe_audio" :description "Transcribe speech in an audio URL to text."
    :args [{:name "audio" :type "string" :description "URL of the audio." :required true}]}
   {:verb "cloneVoice" :tool "clone_voice"
    :description "Create a cloned voice from audio sample URLs and return its voice id."
    :args [{:name "name" :type "string" :description "Name for the new voice." :required true}
           {:name "samples" :type "array" :items "string" :description "URLs of audio samples of the voice." :required true}]}
   {:verb "talking" :tool "talking_video"
    :description "Make the person in an image speak the given audio (lip-synced video)."
    :args [{:name "image" :type "string" :description "URL of the face image." :required true}
           {:name "audio" :type "string" :description "URL of the speech audio." :required true}]}])

;; ---------------------------------------------------------------------------
;; small portable helpers
;; ---------------------------------------------------------------------------

(defn- kname [k] (if (keyword? k) (name k) (str k)))

(defn- stringify-keys
  "Deep: every map key becomes a string. Vectors/lists become vectors."
  [x]
  (cond
    (map? x)        (reduce (fn [acc e] (assoc acc (kname (key e)) (stringify-keys (val e)))) {} x)
    (sequential? x) (mapv stringify-keys x)
    :else           x))

(defn- digits?
  "Only ASCII digits, non-empty — by code point, no regex dialect involved."
  [s]
  (and (seq s) (every? (fn [c] (and (>= (int c) (int \0)) (<= (int c) (int \9)))) s)))

(defn- parse-index [s]
  (reduce (fn [n c] (+ (* n 10) (- (int c) (int \0)))) 0 s))

(defn- integral? [x]
  (and (number? x) (or (integer? x) (== x (long x)))))

(defn- render-number
  "A number as text: an integral number renders as an integer (400.0 -> \"400\")."
  [x]
  (if (integral? x) (str (long x)) (str x)))

(defn- json-equal? [a b]
  (if (and (number? a) (number? b)) (== a b) (= a b)))

;; ---------------------------------------------------------------------------
;; dotPath
;; ---------------------------------------------------------------------------

(defn- segments [path] (str/split (str path) #"\."))

(defn read-path
  "§7G dotPath read: missing ⇒ nil. A segment of only digits indexes an array.
  The empty path \"\" is the whole value."
  [v path]
  (if (= "" (str path))
    v
    (reduce (fn [cur seg]
            (cond
              (and (sequential? cur) (digits? seg))
              (let [i (parse-index seg)] (if (< i (count cur)) (nth cur i) (reduced nil)))
              (map? cur) (if (contains? cur seg) (get cur seg) (reduced nil))
              :else (reduced nil)))
          v (segments path))))

(defn- pad-to [v i] (if (< i (count v)) v (into v (repeat (inc (- i (count v))) nil))))

(defn- write-segs [cur segs value]
  (if (empty? segs)
    value
    (let [seg  (first segs)
          more (rest segs)
          fresh (fn [] (if (and (seq more) (digits? (first more))) [] {}))]
      (if (and (digits? seg) (not (map? cur)))
        (let [i   (parse-index seg)
              v   (pad-to (if (sequential? cur) (vec cur) []) i)
              old (nth v i)]
          (assoc v i (write-segs (if (some? old) old (fresh)) more value)))
        (let [m   (if (map? cur) cur {})
              old (get m seg)]
          (assoc m seg (write-segs (if (some? old) old (fresh)) more value)))))))

(defn write-path
  "§7G dotPath write: creates objects, and arrays when the next segment is
  numeric (padding with nil). A scalar — or an array met by a non-numeric
  segment — on the path is replaced; an object met by a numeric segment is
  kept and the segment is written as a string key."
  [v path value]
  (write-segs v (segments path) value))

;; ---------------------------------------------------------------------------
;; MIME + file mapping (§7G step 9)
;; ---------------------------------------------------------------------------

(def ^:private ext-mime
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp"
   "gif" "image/gif" "mp4" "video/mp4" "webm" "video/webm" "mov" "video/quicktime"
   "mp3" "audio/mpeg" "wav" "audio/wav" "ogg" "audio/ogg" "m4a" "audio/mp4"
   "flac" "audio/flac"})

(def ^:private kind-default {"image" "image/png" "video" "video/mp4" "audio" "audio/mpeg"})
(def ^:private kind-type    {"image" "image" "audio" "audio" "video" "file"})

(defn- cut-at
  "`s` up to (excluding) the first char in `chars`."
  [s chars]
  (let [i (first (keep-indexed (fn [i c] (when (contains? chars c) i)) s))]
    (if i (subs s 0 i) s)))

(defn- url-extension
  "Lower-cased extension of the URL's PATH (query + fragment stripped), or nil."
  [url]
  (let [u    (cut-at (str url) #{\? \#})
        sch  (str/index-of u "://")
        path (if sch
               (let [rest* (subs u (+ sch 3))
                     slash (first (keep-indexed (fn [i c] (when (= c \/) i)) rest*))]
                 (if slash (subs rest* slash) ""))
               u)
        seg  (let [i (tool/last-index-of-char path \/)] (if i (subs path (inc i)) path))
        dot  (tool/last-index-of-char seg \.)]
    (when dot (str/lower-case (subs seg (inc dot))))))

(defn- ->file [kind url]
  {:type     (get kind-type kind "file")
   :mimeType (or (get ext-mime (url-extension url)) (get kind-default kind "image/png"))
   :url      url})

;; ---------------------------------------------------------------------------
;; styles
;; ---------------------------------------------------------------------------

(defn load-style
  "Read one style JSON file (§7G `loadMediaStyle`)."
  [path]
  (json/read-str (fs/read-file path) {:key-fn str}))

(defn create-media
  "§7G `createMedia`. `styles` is one style or a seq of styles (maps, string- or
  keyword-keyed); a verb resolves to the FIRST style that declares it.

  opts:
    :on-progress  (fn [{:verb :jobId :status}]) — after every poll
    :http-client  (fn [{:method :url :headers :body}]) -> {:status :body} — the
                  transport seam (default koine.http/request). Unlike the LLM
                  client's POST-only (url headers body) seam this one carries a
                  method, because the poll is a GET.
    :env          (fn [name]) -> value — `${VAR}` lookup for header values
                  (default koine.env/get-env)."
  ([styles] (create-media styles {}))
  ([styles opts]
   (let [ss (mapv stringify-keys (if (map? styles) [styles] (vec styles)))]
     (doseq [s ss
             v (vals (get s "verbs"))
             [k p] (sort-by first tool/compare-strings (get v "args"))]
       (when (= "" (str p))
         (throw (ex-info (str "style: empty path for argument \"" k "\"")
                         {:toolnexus/error :media}))))
     {::media true
      :styles ss
      :on-progress (:on-progress opts)
      :http-client (:http-client opts)
      :env (:env opts)})))

(defn- resolve-style [media verb]
  (first (filter (fn [s] (get-in s ["verbs" verb])) (:styles media))))

;; ---------------------------------------------------------------------------
;; engine
;; ---------------------------------------------------------------------------

(defn- fail! [msg] (throw (ex-info msg {:toolnexus/error :media})))

(defn- expand-env
  "`${VAR}` expansion with an injectable lookup; unset ⇒ \"\". A `${` without a
  closing `}` is left verbatim."
  [s lookup]
  (let [s (str s) n (count s)]
    (loop [i 0 out ""]
      (if (>= i n)
        out
        (if (and (= \$ (nth s i)) (< (inc i) n) (= \{ (nth s (inc i))))
          (let [close (loop [k (+ i 2)] (cond (>= k n) nil (= \} (nth s k)) k :else (recur (inc k))))]
            (if close
              (recur (inc close) (str out (or (lookup (subs s (+ i 2) close)) "")))
              (str out (subs s i))))
          (recur (inc i) (str out (nth s i))))))))

(defn- trim-slash [s]
  (let [s (str s)] (if (str/ends-with? s "/") (recur (subs s 0 (dec (count s)))) s)))

(defn- cancel-reason [token]
  (cond (nil? token) nil (fn? token) (token) :else @token))

(defn- check-cancel! [token]
  (when-let [r (cancel-reason token)]
    ;; Same shape as toolnexus.client/cancelled-error.
    (throw (ex-info (str "cancelled" (when (string? r) (str ": " r)))
                    {:toolnexus/error :cancelled :reason r}))))

(def ^:private max-body-code-points 1000)

(defn- cap-body
  "The first 1000 code points of `s`, plus `…` when cut. Walks UTF-16 units on
  the JVM (a surrogate pair is one code point) and runes on cljgo."
  [s]
  (let [n (count s)]
    (loop [i 0 cps 0]
      (cond
        (>= i n) s
        (= cps max-body-code-points) (str (subs s 0 i) "\u2026")
        :else (let [c (int (nth s i))
                    w (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) n)) 2 1)]
                (recur (+ i w) (inc cps)))))))

(defn- utf8-bytes [cp]
  (cond
    (< cp 0x80)    [cp]
    (< cp 0x800)   [(bit-or 0xC0 (bit-shift-right cp 6)) (bit-or 0x80 (bit-and cp 0x3F))]
    (< cp 0x10000) [(bit-or 0xE0 (bit-shift-right cp 12))
                    (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                    (bit-or 0x80 (bit-and cp 0x3F))]
    :else          [(bit-or 0xF0 (bit-shift-right cp 18))
                    (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F))
                    (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                    (bit-or 0x80 (bit-and cp 0x3F))]))

(def ^:private hex "0123456789ABCDEF")

(defn- unreserved? [b]
  (or (and (>= b 65) (<= b 90)) (and (>= b 97) (<= b 122)) (and (>= b 48) (<= b 57))
      (= b 45) (= b 46) (= b 95) (= b 126)))

(defn percent-encode
  "RFC 3986: keep A-Z a-z 0-9 - . _ ~, every other UTF-8 byte as %XX."
  [s]
  (apply str
         (for [cp (text/code-points (str s))
               b  (utf8-bytes (long cp))]
           (if (unreserved? b)
             (str (char b))
             (str "%" (nth hex (bit-shift-right b 4)) (nth hex (bit-and b 15)))))))

(defn- origin
  "scheme://host:port (lower-cased, default port filled in) of an absolute URL."
  [url]
  (let [u   (str url)
        sch (str/index-of u "://")]
    (when sch
      (let [scheme (str/lower-case (subs u 0 sch))
            auth   (cut-at (subs u (+ sch 3)) #{\/ \? \#})
            auth   (let [at (tool/last-index-of-char auth \@)] (if at (subs auth (inc at)) auth))
            colon  (tool/last-index-of-char auth \:)
            [h p]  (if (and colon (not (str/ends-with? auth "]")))
                     [(subs auth 0 colon) (subs auth (inc colon))]
                     [auth (if (= "https" scheme) "443" "80")])]
        (str scheme "://" (str/lower-case h) ":" p)))))

(defn- resolve-location [base loc]
  (cond
    (str/index-of loc "://") loc
    (str/starts-with? loc "//") (str (subs base 0 (str/index-of base "//")) loc)
    :else
    (let [sch  (str/index-of base "://")
          rest* (subs base (+ sch 3))
          slash (first (keep-indexed (fn [i c] (when (contains? #{\/ \? \#} c) i)) rest*))
          root  (if slash (subs base 0 (+ sch 3 slash)) base)]
      (if (str/starts-with? loc "/")
        (str root loc)
        (let [path (if slash (cut-at (subs rest* slash) #{\? \#}) "/")
              dir  (subs path 0 (inc (or (tool/last-index-of-char path \/) 0)))]
          (str root dir loc))))))

(def ^:private max-redirects 10)

(defn- timed-out! [verb job-id timeout]
  (fail! (if job-id
           (str "job " job-id " timed out after " (render-number timeout) "ms")
           (str verb ": timed out after " (render-number timeout) "ms"))))

(defn- send!
  "One logical request: follows redirects itself (style headers never leave the
  baseUrl origin), bounded by `deadline` (a ktime/mono-ms instant)."
  [media style verb method path body {:keys [deadline timeout job-id]}]
  (let [lookup  (or (:env media) env/get-env)
        sheads  (reduce (fn [acc e] (assoc acc (key e) (expand-env (val e) lookup)))
                        {} (get style "headers"))
        url0    (str (trim-slash (get style "baseUrl")) path)
        home    (origin url0)
        call    (or (:http-client media) khttp/request)
        res     (loop [method (keyword (str/lower-case method)) url url0 body body hops 0]
                  (let [remaining (- deadline (ktime/mono-ms))
                        _   (when (<= remaining 0) (timed-out! verb job-id timeout))
                        hs  (if (= home (origin url)) sheads {})
                        hs  (cond-> hs body (assoc "Content-Type" "application/json"))
                        req (cond-> {:method method :url url :headers hs :timeout-ms remaining}
                              body (assoc :body (json/write-str body)))
                        res (call req)
                        st  (:status res)
                        loc (khttp/header res "location")]
                    (cond
                      (= :timeout (:error res)) (timed-out! verb job-id timeout)
                      (and st (contains? #{301 302 303 307 308} (long st)) (string? loc)
                           (< hops max-redirects))
                      (let [keep? (or (contains? #{307 308} (long st)) (= :get method) (= :head method))]
                        (recur (if keep? method :get) (resolve-location url loc)
                               (when keep? body) (inc hops)))
                      :else res)))]
    (when (khttp/failed? res)
      (fail! (str verb ": HTTP transport failure: " (kname (:error res)))))
    (let [st (long (:status res)) raw (str (:body res))]
      (when (or (< st 200) (> st 299)) (fail! (str "HTTP " st ": " (cap-body raw))))
      (let [parsed (try (json/read-str raw {:key-fn str}) (catch Throwable _ ::bad))]
        (when (= ::bad parsed) (fail! (str verb ": response is not JSON")))
        (when-let [env* (get style "errorEnvelope")]
          (let [code (read-path parsed (get env* "codePath"))]
            (when (and (some? code)
                       (not (some (fn [ok] (json-equal? ok code)) (get env* "okValues"))))
              (let [m (some-> (get env* "messagePath") (->> (read-path parsed)))]
                (fail! (str "API error " (cond (string? code) code
                                         (integral? code) (render-number code)
                                         :else (json/write-str code))
                            ": " (if (string? m) m "")))))))
        parsed))))

(defn- positive-or [x dflt] (if (and (number? x) (pos? x)) x dflt))

(defn- render-status [s]
  (cond (string? s) s
        (number? s) (render-number s)
        :else ""))

(defn- poll! [media style verb v job-id cancel t0]
  (let [p        (get v "poll")
        interval (positive-or (get p "intervalMs") 5000)
        timeout  (positive-or (get p "timeoutMs") 600000)
        deadline (+ t0 timeout)
        path     (str/replace (str (get p "path")) "{id}" (percent-encode job-id))
        opts     {:deadline deadline :timeout timeout :job-id job-id}]
    (loop []
      (check-cancel! cancel)
      (ktime/sleep! interval)
      (check-cancel! cancel)
      (let [resp   (send! media style verb (or (get p "method") "GET") path nil opts)
            status (render-status (read-path resp (get p "statusPath")))]
        (when-let [f (:on-progress media)] (f {:verb verb :jobId job-id :status status}))
        (cond
          (some #{status} (get p "done")) [resp status]
          (some #{status} (get p "failed"))
          (let [m (some->> (get p "errorPath") (read-path resp))]
            (fail! (if (and (string? m) (seq m))
                     (str "job " job-id " " status ": " m)
                     (str "job " job-id " " status))))
          (>= (ktime/mono-ms) deadline) (timed-out! verb job-id timeout)
          :else (recur))))))

(defn- extract [verb v resp]
  (let [kind (get v "kind")
        rp   (get v "resultPath")
        val* (read-path resp rp)
        none #(fail! (str verb ": no result at " rp))]
    (if (= "text" kind)
      (cond (string? val*) {:files [] :text val*}
            (number? val*) {:files [] :text (render-number val*)}
            :else (none))
      (let [urls (cond (string? val*)     [val*]
                       (sequential? val*) (filter string? (flatten val*))
                       :else              [])
            urls (vec (remove empty? urls))]
        (if (empty? urls) (none) {:files (mapv (fn [u] (->file kind u)) urls)})))))

(defn generate
  "§7G engine — the generic function face. `verb` is the canonical verb name
  (\"image\" … \"cloneVoice\", string or keyword). `args` maps normalised arg
  names (keyword or string) to values. opts: `:provider-options` (map; its
  top-level keys overwrite the body last), `:cancel` (an atom or 0-arg fn —
  truthy ⇒ cancelled). Returns MediaResult `{:files [..] :text? :jobId? :status}`;
  throws ex-info with the §7G message on any failure."
  ([media verb args] (generate media verb args {}))
  ([media verb args {:keys [provider-options cancel]}]
   (let [verb  (kname verb)
         style (resolve-style media verb)
         _     (when-not style (fail! (str "media: no style supports \"" verb "\"")))
         v     (get-in style ["verbs" verb])
         amap  (get v "args")
         args  (->> (or args {})
                    (keep (fn [e] (when (some? (val e)) [(kname (key e)) (val e)])))
                    (sort-by first tool/compare-strings))
         _     (doseq [[k _] args]
                 (when-not (contains? amap k)
                   (fail! (str verb ": argument \"" k "\" not supported by this style"))))
         body  (reduce (fn [b [k a]] (write-path b (get amap k) (stringify-keys a)))
                       (stringify-keys (or (get v "defaults") {})) args)
         body  (reduce (fn [b e] (assoc b (kname (key e)) (stringify-keys (val e))))
                       body provider-options)
         start (get v "start")
         _     (check-cancel! cancel)
         t0      (ktime/mono-ms)
         timeout (positive-or (get-in v ["poll" "timeoutMs"]) 600000)
         resp  (send! media style verb (or (get start "method") "POST") (get start "path") body
                      {:deadline (+ t0 timeout) :timeout timeout})]
     (if (= "async" (get v "mode"))
       (let [id-path (get v "idPath")
             raw     (read-path resp id-path)
             job-id  (cond (and (string? raw) (not= "" raw)) raw
                           (integral? raw) (render-number raw)
                           :else (fail! (str verb ": no job id at " id-path)))
             [final status] (poll! media style verb v job-id cancel t0)]
         (assoc (extract verb v final) :jobId job-id :status status))
       (assoc (extract verb v resp) :status "completed")))))

(defn image       ([m args] (generate m "image" args))      ([m args opts] (generate m "image" args opts)))
(defn video       ([m args] (generate m "video" args))      ([m args opts] (generate m "video" args opts)))
(defn speech      ([m args] (generate m "speech" args))     ([m args opts] (generate m "speech" args opts)))
(defn transcribe  ([m args] (generate m "transcribe" args)) ([m args opts] (generate m "transcribe" args opts)))
(defn clone-voice ([m args] (generate m "cloneVoice" args)) ([m args opts] (generate m "cloneVoice" args opts)))
(defn talking     ([m args] (generate m "talking" args))    ([m args opts] (generate m "talking" args opts)))

;; ---------------------------------------------------------------------------
;; tool face
;; ---------------------------------------------------------------------------

(def ^:private kind-word {"image" "image" "video" "video" "audio" "audio"})

(defn- input-schema [entry mapped]
  (let [args (filter (fn [a] (contains? mapped (:name a))) (:args entry))]
    {:type "object"
     :properties (apply array-map
                        (mapcat (fn [a]
                                  [(keyword (:name a))
                                   (if (= "array" (:type a))
                                     {:type "array" :items {:type (:items a)} :description (:description a)}
                                     {:type (:type a) :description (:description a)})])
                                args))
     :required (vec (keep (fn [a] (when (:required a) (:name a))) args))}))

(defn- tool-result [verb kind r]
  (let [meta* (cond-> {:source "media" :verb verb}
                (:jobId r) (assoc :jobId (:jobId r))
                true       (assoc :status (:status r)))]
    (if (= "text" kind)
      (tool/success (:text r) meta*)
      (tool/with-parts
        (tool/success (str (get kind-word kind) " ready: "
                           (str/join "\n" (map :url (:files r))))
                      meta*)
        (:files r)))))

(defn tools
  "§7G — one Tool (source \"media\") per verb the media supports, in verbs.json
  order. Execute never throws: an engine error is an isError ToolResult."
  [media]
  (vec
   (keep (fn [entry]
           (when-let [style (resolve-style media (:verb entry))]
             (let [verb (:verb entry)
                   v    (get-in style ["verbs" verb])]
               (tool/tool
                {:name         (:tool entry)
                 :description  (:description entry)
                 :input-schema (input-schema entry (set (keys (get v "args"))))
                 :source       "media"
                 ;; Each call is an independent remote job: always concurrency-safe
                 ;; (§7G, §8 Tool scheduling).
                 :concurrency-safe (constantly true)
                 :context?     true
                 :execute
                 (fn exec
                   ([args] (exec args nil))
                   ([args ctx]
                    (try
                      (tool-result verb (get v "kind")
                                   (generate media verb args {:cancel (:cancel ctx)}))
                      (catch Throwable e
                        (tool/failure (or (ex-message e) (str e)))))))}))))
         verbs)))

(defn media?
  "True for a value built by `create-media`."
  [x]
  (and (map? x) (true? (::media x))))
