;; SPEC §7G — Media generation (source: "media"). ADRs 0036 + 0037.
;;
;; One interface, two faces:
;;
;;   function face  (image m {:prompt "a cat"})        — the host decides
;;   tool face      (tools m) -> generate_image, …      — the LLM decides
;;
;; Providers are MEDIA CLIENTS (§7G.2). In Clojure a client is a plain map of
;; functions:
;;
;;   {:name     "acme"
;;    :supports (fn [verb] bool)                       ; verb is the canonical string
;;    :args     (fn [verb] [{:name "prompt" :required true} …])   ; optional
;;    :image    (fn [args ctx] MediaResult)            ; every verb fn optional
;;    :video … :speech … :music … :transcribe … :clone-voice … :talk …}
;;
;; args reach a verb fn keyword-keyed, nils removed. ctx is
;; {:cancel (atom|0-arg fn) :deadline <epoch ms> :progress (fn [p]) :provider-options {…}}.
;; A failed verb throws `ex-info` whose data carries `:status`
;; (:failed | :timeout | :cancelled | :unavailable) — `unavailable?` tests it.
;;
;; Built in: `style-media-client` (the JSON style engine of ADR 0036, still the
;; way API clients are DEFINED), `a2e-media-client` (a style over the embedded
;; examples/media-styles/a2e.json), `elevenlabs-media-client`,
;; `openai-media-client`, `openrouter-media-client`, `fal-media-client`,
;; `cli-media-client` and its presets `grok-media-client` /
;; `codex-media-client` (agy is internal). The preferred spelling for CLIs is
;; the preset-as-style: `(load-media-style media/xai-grok-cli cfg)` — preset vars
;; `xai-grok-cli` `openai-codex-cli` `google-agy-cli` and `-mock` twins
;; (strings); `available` is the cheap pre-flight check (§7G.10b).
;; `create-media` routes each verb to the first client that supports it,
;; falling through ONLY on `unavailable` (§7G.3). A result carries `:style`
;; (who served it) and `:model` (the configured model, when set).
;;
;; KNOWN GAP (both hosts): koine.http carries request and response bodies as
;; TEXT (UTF-8), so a provider's binary body (mp3 bytes, a downloaded image)
;; and an uploaded binary input are only byte-exact when they are valid UTF-8.
;; Base64 / data-URL results and CLI files are byte-exact (koine.fs bytes). The
;; hermetic conformance bodies are ASCII; closing this needs a bytes mode in
;; koine.http.
;;
;; Error messages, tool names, schemas and tool results are byte-fixed by
;; conformance/media/ — change a byte there and here together.
(ns toolnexus.media
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.http :as khttp]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.text :as text]
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
    :description "Generate a short video from a prompt, optionally starting from an image. Can take minutes."
    :args [{:name "prompt" :type "string" :description "What should happen in the video." :required true}
           {:name "image" :type "string" :description "URL or local path of the starting image."}
           {:name "seconds" :type "integer" :description "Video length in seconds."}
           {:name "aspectRatio" :type "string" :description "Aspect ratio, e.g. 9:16."}]}
   {:verb "speech" :tool "generate_speech" :description "Speak text aloud and return the audio."
    :args [{:name "text" :type "string" :description "The text to speak." :required true}
           {:name "voice" :type "string" :description "Voice name or id, including a cloned voice."}]}
   {:verb "music" :tool "generate_music" :description "Generate music from a text prompt and return the audio."
    :args [{:name "prompt" :type "string" :description "Style, mood and instruments of the music." :required true}
           {:name "seconds" :type "integer" :description "Length in seconds."}
           {:name "instrumental" :type "boolean" :description "True for no vocals."}
           {:name "lyrics" :type "string" :description "Lyrics to sing."}]}
   {:verb "transcribe" :tool "transcribe_audio" :description "Transcribe speech in an audio file to text."
    :args [{:name "audio" :type "string" :description "URL or local path of the audio." :required true}
           {:name "language" :type "string" :description "Language of the speech as an ISO 639-1 code, e.g. en."}]}
   {:verb "cloneVoice" :tool "clone_voice"
    :description "Create a cloned voice from audio samples and return its voice id."
    :args [{:name "name" :type "string" :description "Name for the new voice." :required true}
           {:name "samples" :type "array" :items "string" :description "URLs or local paths of audio samples of the voice." :required true}]}
   {:verb "talk" :tool "talking_video"
    :description "Make the person in an image speak, from speech audio or from text (lip-synced video)."
    :args [{:name "image" :type "string" :description "URL or local path of the face image." :required true}
           {:name "audio" :type "string" :description "URL or local path of the speech audio."}
           {:name "text" :type "string" :description "Text to speak when no audio is given."}
           {:name "voice" :type "string" :description "Voice for the text."}]}])

(def ^:private verb-entry (into {} (map (fn [e] [(:verb e) e]) verbs)))

(def ^:private verb-fn-key
  "Canonical verb -> the client map key holding its fn."
  {"image" :image "video" :video "speech" :speech "music" :music
   "transcribe" :transcribe "cloneVoice" :clone-voice "talk" :talk})

(def ^:private text-verbs #{"transcribe" "cloneVoice"})

;; ---------------------------------------------------------------------------
;; the a2e preset — examples/media-styles/a2e.json, embedded verbatim
;; ---------------------------------------------------------------------------

(def a2e-style-json
  "examples/media-styles/a2e.json, byte for byte (a test asserts the copy). A
  string literal rather than a resource: it is the one shipping form that both
  hosts (JVM classpath, cljgo AOT) read identically."
  "{
  \"name\": \"a2e\",
  \"baseUrl\": \"https://api.a2e.ai/api\",
  \"headers\": {
    \"Authorization\": \"Bearer ${A2E_API_KEY}\"
  },
  \"verbs\": {
    \"image\": {
      \"mode\": \"async\",
      \"kind\": \"image\",
      \"start\": {
        \"method\": \"POST\",
        \"path\": \"/v1/userText2Image/start\"
      },
      \"defaults\": {
        \"name\": \"toolnexus\",
        \"model_type\": \"a2e\"
      },
      \"args\": {
        \"prompt\": \"prompt\",
        \"width\": \"width\",
        \"height\": \"height\",
        \"seed\": \"seed\"
      },
      \"idPath\": \"data.0._id\",
      \"poll\": {
        \"path\": \"/v1/userText2Image/{id}\",
        \"statusPath\": \"data.current_status\",
        \"done\": [
          \"completed\"
        ],
        \"failed\": [
          \"failed\",
          \"blocked\",
          \"canceled\"
        ],
        \"errorPath\": \"data.failed_message\",
        \"intervalMs\": 3000,
        \"timeoutMs\": 300000
      },
      \"resultPath\": \"data.image_urls\"
    },
    \"video\": {
      \"mode\": \"async\",
      \"kind\": \"video\",
      \"start\": {
        \"method\": \"POST\",
        \"path\": \"/v1/userImage2Video/start\"
      },
      \"defaults\": {
        \"name\": \"toolnexus\",
        \"generation_mode\": \"image-to-video\",
        \"negative_prompt\": \"blurry, distorted, low quality\"
      },
      \"args\": {
        \"image\": \"image_url\",
        \"prompt\": \"prompt\",
        \"seconds\": \"video_time\"
      },
      \"idPath\": \"data._id\",
      \"poll\": {
        \"path\": \"/v1/userImage2Video/{id}\",
        \"statusPath\": \"data.current_status\",
        \"done\": [
          \"completed\"
        ],
        \"failed\": [
          \"failed\",
          \"blocked\",
          \"canceled\"
        ],
        \"errorPath\": \"data.failed_message\",
        \"intervalMs\": 5000,
        \"timeoutMs\": 900000
      },
      \"resultPath\": \"data.result_url\"
    },
    \"speech\": {
      \"mode\": \"async\",
      \"kind\": \"audio\",
      \"start\": {
        \"method\": \"POST\",
        \"path\": \"/v1/seedAudio/start\"
      },
      \"args\": {
        \"text\": \"text_prompt\",
        \"voice\": \"references.0.speaker\"
      },
      \"idPath\": \"data._id\",
      \"poll\": {
        \"path\": \"/v1/seedAudio/{id}\",
        \"statusPath\": \"data.current_status\",
        \"done\": [
          \"completed\"
        ],
        \"failed\": [
          \"failed\"
        ],
        \"errorPath\": \"data.failed_message\",
        \"intervalMs\": 3000,
        \"timeoutMs\": 300000
      },
      \"resultPath\": \"data.result_url\"
    },
    \"talk\": {
      \"mode\": \"async\",
      \"kind\": \"video\",
      \"start\": {
        \"method\": \"POST\",
        \"path\": \"/v1/talkingPhoto/start\"
      },
      \"defaults\": {
        \"name\": \"toolnexus\"
      },
      \"args\": {
        \"image\": \"image_url\",
        \"audio\": \"audio_url\"
      },
      \"required\": [
        \"audio\"
      ],
      \"idPath\": \"data._id\",
      \"poll\": {
        \"path\": \"/v1/talkingPhoto/{id}\",
        \"statusPath\": \"data.current_status\",
        \"done\": [
          \"completed\"
        ],
        \"failed\": [
          \"failed\",
          \"blocked\",
          \"canceled\"
        ],
        \"errorPath\": \"data.failed_message\",
        \"intervalMs\": 5000,
        \"timeoutMs\": 900000
      },
      \"resultPath\": \"data.result_url\"
    }
  },
  \"errorEnvelope\": {
    \"codePath\": \"code\",
    \"okValues\": [
      0
    ],
    \"messagePath\": \"message\"
  },
  \"_doc\": \"a2e.ai preset (ADR 0036/0037). Embedded verbatim by every port as A2EMediaClient (SPEC §7G.9). Live-verified 2026-11-02: image, video, speech. talk is implemented per a2e's docs, UNVERIFIED. Set A2E_API_KEY. Result URLs expire (3-30 days); the built-in client downloads them by default.\"
}
")

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

(defn- mono [] (ktime/mono-ms))

;; ---------------------------------------------------------------------------
;; errors (§7G.2) — typed by :status, never by message
;; ---------------------------------------------------------------------------

(defn media-error
  "The media error: ex-info carrying `:status` (:failed | :timeout | :cancelled
  | :unavailable) and `:jobId` when known."
  ([msg status] (media-error msg status nil))
  ([msg status job-id]
   (ex-info (str msg) (cond-> {:toolnexus/error :media :status status}
                        job-id (assoc :jobId job-id)))))

(defn unavailable?
  "True for a media error whose status is :unavailable (§7G.2) — the client
  cannot serve the call now (no key, no credits, quota, CLI missing, ZDR)."
  [e]
  (= :unavailable (:status (ex-data e))))

(defn- fail! [msg] (throw (media-error msg :failed)))
(defn- unavailable! [msg] (throw (media-error msg :unavailable)))

(defn- cancel-reason [token]
  (cond (nil? token) nil (fn? token) (token) :else @token))

(defn- check-cancel! [token]
  (when-let [r (cancel-reason token)]
    ;; Same shape as toolnexus.client/cancelled-error, plus the media status.
    (throw (ex-info (str "cancelled" (when (string? r) (str ": " r)))
                    {:toolnexus/error :cancelled :status :cancelled :reason r}))))

(defn- cap-cp
  "The first `n` code points of `s`, plus `…` when cut. Walks UTF-16 units on
  the JVM (a surrogate pair is one code point) and runes on cljgo."
  [s n]
  (let [s (str s) len (count s)]
    (loop [i 0 cps 0]
      (cond
        (>= i len) s
        (= cps n) (str (subs s 0 i) "…")
        :else (let [c (int (nth s i))
                    w (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) len)) 2 1)]
                (recur (+ i w) (inc cps)))))))

(defn- cap-body [s] (cap-cp s 1000))

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
;; MIME + file mapping (§7G.5, §7G.7 step 8)
;; ---------------------------------------------------------------------------

(def ^:private ext-mime
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp"
   "gif" "image/gif" "mp4" "video/mp4" "webm" "video/webm" "mov" "video/quicktime"
   "mp3" "audio/mpeg" "wav" "audio/wav" "ogg" "audio/ogg" "m4a" "audio/mp4"
   "flac" "audio/flac"})

(def ^:private mime-ext
  {"image/png" ".png" "image/jpeg" ".jpg" "image/webp" ".webp" "image/gif" ".gif"
   "video/mp4" ".mp4" "video/webm" ".webm" "video/quicktime" ".mov"
   "audio/mpeg" ".mp3" "audio/wav" ".wav" "audio/ogg" ".ogg" "audio/mp4" ".m4a"
   "audio/flac" ".flac" "audio/x-wav" ".wav" "audio/wave" ".wav" "audio/aac" ".aac"
   "text/plain" ".txt"})

(def version
  "The package version (kept equal to build.clj's `version`; a test pins it)."
  "0.29.1")

(def user-agent
  "§7G.12 D23: the default User-Agent of every media HTTP request."
  (str "toolnexus/" version " (clojure)"))

(def ^:private kind-default {"image" "image/png" "video" "video/mp4" "audio" "audio/mpeg"})
(def ^:private kind-type    {"image" "image" "audio" "audio" "video" "file"})

(defn- cut-at
  "`s` up to (excluding) the first char in `chars`."
  [s chars]
  (let [i (first (keep-indexed (fn [i c] (when (contains? chars c) i)) s))]
    (if i (subs s 0 i) s)))

(defn- url-path
  "The PATH of a URL (query + fragment stripped); a plain path is itself."
  [url]
  (let [u   (cut-at (str url) #{\? \#})
        sch (str/index-of u "://")]
    (if sch
      (let [rest* (subs u (+ sch 3))
            slash (first (keep-indexed (fn [i c] (when (= c \/) i)) rest*))]
        (if slash (subs rest* slash) ""))
      u)))

(defn- last-segment [url]
  (let [path (url-path url)
        i    (tool/last-index-of-char path \/)]
    (if i (subs path (inc i)) path)))

(defn- url-extension
  "Lower-cased extension of the URL's PATH (query + fragment stripped), or nil."
  [url]
  (let [seg (last-segment url)
        dot (tool/last-index-of-char seg \.)]
    (when dot (str/lower-case (subs seg (inc dot))))))

(defn- url-save-ext
  "§7G.12 D22: the URL path's own extension (`.` + 1-5 ASCII letters/digits,
  lower-cased), or nil."
  [url]
  (let [seg (last-segment url)
        dot (tool/last-index-of-char seg \.)]
    (when dot
      (let [e (subs seg (inc dot))]
        (when (re-matches #"[A-Za-z0-9]{1,5}" e) (str "." (str/lower-case e)))))))

(defn- ext-mime-of [url] (get ext-mime (url-extension url)))

(defn- http-url? [s]
  (let [s (str/lower-case (str s))]
    (or (str/starts-with? s "http://") (str/starts-with? s "https://"))))

;; ---------------------------------------------------------------------------
;; config (§7G.4)
;; ---------------------------------------------------------------------------

(defn- positive-or [x dflt] (if (and (number? x) (pos? x)) x dflt))

(def ^:private default-timeout 600000)
(def ^:private default-max-bytes 104857600)

(defn- loop-timeout [cfg] (positive-or (get-in cfg [:loop :timeout-ms]) default-timeout))

(defn- cfg-model
  "models[verb] wins; then `model` for the client's primary verb; then the default."
  [cfg verb primary dflt]
  (let [ms (:models cfg)
        m  (or (get ms (keyword verb)) (get ms verb))]
    (cond (and (string? m) (seq m)) m
          (and (= verb primary) (string? (:model cfg)) (seq (:model cfg))) (:model cfg)
          :else dflt)))

(defn- lookup-fn [cfg] (or (:env cfg) env/get-env))

(defn- trim-slash [s]
  (let [s (str s)] (if (str/ends-with? s "/") (recur (subs s 0 (dec (count s)))) s)))

(defn- temp-root []
  (trim-slash (or (env/get-env "TMPDIR") (env/get-env "TEMP") (env/get-env "TMP") "/tmp")))

;; ---------------------------------------------------------------------------
;; a job: one verb call on one client — deadline, progress, transport
;; ---------------------------------------------------------------------------

(defn- make-job
  "`timeout` is the job deadline from now (ms). ctx :deadline (epoch ms), when
  earlier, wins."
  [client-name verb cfg ctx timeout & [extra]]
  (let [t0       (mono)
        own      (+ t0 timeout)
        ctx-dl   (:deadline ctx)
        deadline (if (number? ctx-dl) (min own (+ t0 (- ctx-dl (ktime/now-ms)))) own)]
    (merge {:client   client-name
            :verb     verb
            :cfg      cfg
            :ctx      ctx
            :cancel   (:cancel ctx)
            :t0       t0
            :timeout  timeout
            :deadline deadline
            :call     (or (:http-client cfg) khttp/request)}
           extra)))

(defn- report!
  "ctx :progress, else loop.onProgress — never both (§7G.2)."
  [job status job-id]
  (when-let [f (or (get-in job [:ctx :progress]) (get-in job [:cfg :loop :on-progress]))]
    (f (cond-> {:verb (:verb job) :client (:client job) :status status
                :elapsedMs (- (mono) (:t0 job))}
         job-id (assoc :jobId job-id)))))

(defn- timed-out! [job job-id]
  (let [t (render-number (:timeout job))]
    (throw (media-error (if job-id
                          (str "job " job-id " timed out after " t "ms")
                          (str (:verb job) ": timed out after " t "ms"))
                        :timeout job-id))))

(defn- wait!
  "Sleep `interval` ms (in small slices, so cancellation is prompt)."
  [job interval]
  (check-cancel! (:cancel job))
  (let [end (+ (mono) interval)]
    (loop []
      (let [left (- end (mono))]
        (when (pos? left)
          (ktime/sleep! (min left 50))
          (check-cancel! (:cancel job))
          (recur))))))

;; --- RFC 3986 percent-encoding

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

(defn- http!
  "One logical request (§7G.8): follows redirects itself, sends `headers` only
  to the job's home origin, sets Content-Type only with a body, and bounds every
  hop by the job deadline. Returns the final response; never checks its status."
  [job {:keys [method url body content-type headers job-id]}]
  (let [home (:home job)
        call (:call job)
        verb (:verb job)
        res  (loop [method method url url body body hops 0]
               (let [remaining (- (:deadline job) (mono))
                     _   (when (<= remaining 0) (timed-out! job job-id))
                     hs  (if (and home (= home (origin url))) (or headers {}) {})
                     hs  (cond-> hs body (assoc "Content-Type" (or content-type "application/json")))
                     ;; D23: default UA unless the client/style headers set one
                     hs  (if (some (fn [k] (= "user-agent" (str/lower-case (kname k)))) (keys hs))
                           hs
                           (assoc hs "User-Agent" user-agent))
                     req (cond-> {:method method :url url :headers hs :timeout-ms remaining}
                           body (assoc :body body))
                     res (call req)
                     st  (:status res)
                     loc (khttp/header res "location")]
                 (cond
                   (= :timeout (:error res)) (timed-out! job job-id)
                   (and st (contains? #{301 302 303 307 308} (long st)) (string? loc))
                   (let [_     (when (>= hops max-redirects) (fail! (str verb ": too many redirects")))
                         keep? (or (contains? #{307 308} (long st)) (= :get method) (= :head method))]
                     (recur (if keep? method :get) (resolve-location url loc)
                            (when keep? body) (inc hops)))
                   :else res)))]
    ;; cljgo: Go's net/http follows redirects itself (koine has no switch) and
    ;; stops after 10 with this text — the same §7G limit, reported in §7G words.
    (when (and (khttp/failed? res)
               (str/includes? (str (:error-message res)) "stopped after 10 redirects"))
      (fail! (str verb ": too many redirects")))
    (when (khttp/failed? res)
      (fail! (str verb ": HTTP transport failure: " (kname (:error res)))))
    res))

(defn- unavailable-status?
  "§7G.2: 402, or 429 whose body names quota/credit/billing/balance."
  [st body]
  (or (= st 402)
      (and (= st 429)
           (let [b (str/lower-case (str body))]
             (some (fn [w] (str/includes? b w)) ["quota" "credit" "billing" "balance"])))))

(defn- check-status!
  "Every API response: unavailable mapping, then any other non-2xx."
  [job res]
  (let [st (long (:status res)) raw (str (:body res))]
    (when (unavailable-status? st raw)
      (unavailable! (str (:client job) ": HTTP " st ": " (cap-body raw))))
    (when (or (< st 200) (> st 299))
      (fail! (str "HTTP " st ": " (cap-body raw))))
    res))

(defn- parse-json! [job raw]
  (let [parsed (try (json/read-str (str raw) {:key-fn str}) (catch Throwable _ ::bad))]
    (when (= ::bad parsed) (fail! (str (:verb job) ": response is not JSON")))
    parsed))

(defn- api!
  "An API request with the client's headers: status checked; JSON parsed
  unless `:raw?`."
  [job {:keys [raw? json-body] :as req}]
  (let [res (check-status! job (http! job (cond-> (assoc req :headers (:headers job))
                                            (some? json-body) (assoc :body (json/write-str json-body)))))]
    (if raw? res (parse-json! job (:body res)))))

;; ---------------------------------------------------------------------------
;; gather (§7G.5)
;; ---------------------------------------------------------------------------

(def ^:private save-seq (atom 0))

(defn- gather-cfg [job default-mode]
  (let [g (get-in job [:cfg :gather])]
    {:mode      (let [m (:mode g)] (if (and m (seq (kname m))) (kname m) default-mode))
     :dir       (let [d (:dir g)] (if (and (string? d) (seq d)) d (str (temp-root) "/toolnexus-media")))
     :name      (:name g)
     :max-bytes (positive-or (:max-bytes g) default-max-bytes)
     :keep      (not (false? (:keep g)))}))

(defn- content-size [{:keys [text bytes]}]
  (if bytes (count bytes) (text/utf8-length text)))

(defn- check-size!
  ([job g c] (check-size! job g c ""))
  ([job g c prefix]
  (when (> (content-size c) (:max-bytes g))
    (fail! (str (:verb job) ": " prefix "file exceeds gather.maxBytes (" (render-number (:max-bytes g)) " bytes)")))))

(defn- content-b64 [{:keys [text bytes]}]
  (if bytes (codec/encode bytes) (codec/encode (str text))))

(defn- media-slug
  "§7G.10b gather.name: every byte outside [A-Za-z0-9._-] becomes \"-\" (per
  UTF-8 byte), then leading \".\" and \"-\" are dropped."
  [n]
  (let [ok? (fn [ch] (or (<= (int \a) (int ch) (int \z)) (<= (int \A) (int ch) (int \Z))
                         (<= (int \0) (int ch) (int \9)) (contains? #{\. \_ \-} ch)))
        s   (apply str (mapcat (fn [ch] (if (ok? ch) [ch] (repeat (text/utf8-length (str ch)) \-)))
                               (str n)))]
    (str/replace s #"^[.-]+" "")))

;; names handed out but not yet written, so concurrent gathers never collide
(def ^:private claimed-names (atom #{}))

(defn- free-name!
  "The first of <slug><ext>, <slug>-2<ext>, … not yet in `dir`; created empty."
  [dir slug ext]
  (loop [n 1]
    (let [p    (str dir "/" slug (if (> n 1) (str "-" n) "") ext)
          [old _] (swap-vals! claimed-names (fn [c] (if (or (contains? c p) (fs/exists? p)) c (conj c p))))]
      (if (or (contains? old p) (fs/exists? p))
        (recur (inc n))
        (do (fs/write-file p "")
            (swap! claimed-names disj p)
            p)))))

(defn- save! [job g mime c src-url]
  (let [dir  (:dir g)
        _    (fs/mkdirs! dir)
        ext  (or (when src-url (url-save-ext src-url)) (get mime-ext mime ".bin"))
        slug (media-slug (or (:name g) ""))
        path (if (seq slug)
               (free-name! (trim-slash dir) slug ext)
               (str dir "/" (:client job) "-" (:verb job) "-" (ktime/now-ms) "-"
                    (swap! save-seq inc) ext))]
    (if (:bytes c) (fs/write-bytes path (:bytes c)) (fs/write-file path (str (:text c))))
    path))

(defn- content-file [job g kind mime c & [src-url]]
  (check-size! job g c)
  (let [base {:type (get kind-type kind "file") :mimeType mime}]
    (if (= "inline" (:mode g))
      (assoc base :data (content-b64 c))
      (let [p (save! job g mime c src-url)] (assoc base :path p :url (str "file://" p))))))

(defn- strip-params [ct]
  (when (string? ct) (str/trim (cut-at ct #{\;}))))

(defn- fetch!
  "GET with NO client headers, bounded by the job deadline (§7G.5 download,
  §7G.1 input fetch). Returns the response; never checks its status."
  [job url]
  (http! job {:method :get :url url :headers {}}))

(defn- gather-one
  "One provider result -> a MediaFile. A result is one of
  {:text s :mime m} (bytes as text), {:bytes b :mime m}, {:b64 s :mime m},
  {:data-url s}, {:url s}, {:path s}."
  [job g kind r]
  (cond
    (:data-url r)
    (let [u     (:data-url r)
          comma (str/index-of u ",")
          head  (subs u 5 (or comma 5))
          mime  (cut-at head #{\;})
          mime  (if (seq mime) mime (get kind-default kind))]
      (content-file job g kind mime {:bytes (codec/decode-bytes (if comma (subs u (inc comma)) ""))}))

    (:b64 r)   (content-file job g kind (:mime r) {:bytes (codec/decode-bytes (:b64 r))})
    (:bytes r) (content-file job g kind (:mime r) {:bytes (:bytes r)})
    (contains? r :text) (content-file job g kind (:mime r) {:text (:text r)})

    (:url r)
    (let [u (:url r)]
      (if (= "url" (:mode g))
        {:type (get kind-type kind "file")
         :mimeType (or (ext-mime-of u) (get kind-default kind "image/png"))
         :url u}
        (let [res (fetch! job u)
              st  (long (:status res))]
          (when (or (< st 200) (> st 299))
            (fail! (str (:verb job) ": download failed: HTTP " st)))
          (let [ct   (strip-params (khttp/header res "content-type"))
                mime (if (and (seq ct) (not= "application/octet-stream" ct))
                       ct
                       (or (ext-mime-of u) (get kind-default kind "image/png")))]
            ;; D14: an oversize download is a download failure
            (let [c {:text (str (:body res))}]
              (check-size! job g c "download failed: ")
              (content-file job g kind mime c u))))))

    (:path r)
    (let [p    (:path r)
          mime (or (ext-mime-of p) (get kind-default kind "image/png"))
          base {:type (get kind-type kind "file") :mimeType mime}]
      (case (:mode g)
        "url"    (assoc base :url (str "file://" p) :path p)
        "inline" (let [c {:bytes (fs/read-bytes p)}]
                   (check-size! job g c)
                   (assoc base :data (content-b64 c)))
        (let [c    {:bytes (fs/read-bytes p)}
              file (content-file job g kind mime c)]
          (when-not (:keep g) (fs/delete! p))
          file)))))

(defn- gather!
  [job kind results default-mode]
  (let [g (gather-cfg job default-mode)]
    (mapv (fn [r] (gather-one job g kind r)) results)))

;; ---------------------------------------------------------------------------
;; inputs (§7G.1 input references) and multipart
;; ---------------------------------------------------------------------------

(defn- read-input!
  "Resolve an `image`/`audio`/sample reference to {:text :filename :mime}."
  [job value]
  (let [v     (str value)
        verb  (:verb job)
        cant  (fn [reason] (fail! (str verb ": cannot read \"" v "\"" (when reason (str ": " reason)))))
        fname (let [s (last-segment v)] (if (seq s) s "input"))
        mime  (or (ext-mime-of v) "application/octet-stream")
        g     (gather-cfg job "download")
        body  (if (http-url? v)
                (let [res (fetch! job v)
                      st  (long (:status res))]
                  (when (or (< st 200) (> st 299)) (cant (str "HTTP " st)))
                  (str (:body res)))
                (let [p (if (str/starts-with? v "file://") (subs v 7) v)]
                  (if (and (fs/exists? p) (not (fs/directory? p)))
                    (try (fs/read-file p) (catch Throwable e (cant (ex-message e))))
                    (cant "no such file"))))]
    (when (> (text/utf8-length body) (:max-bytes g))
      (cant (str "file exceeds gather.maxBytes (" (render-number (:max-bytes g)) " bytes)")))
    {:text body :filename fname :mime mime}))

(defn- multipart
  "fields: [{:name :value} | {:name :filename :mime :text}] -> [content-type body]."
  [fields]
  (let [b    (str "toolnexus-" (ktime/now-ms) "-" (rand-int 1000000000))
        part (fn [f]
               (if (:filename f)
                 (str "--" b "\r\nContent-Disposition: form-data; name=\"" (:name f)
                      "\"; filename=\"" (:filename f) "\"\r\nContent-Type: " (:mime f)
                      "\r\n\r\n" (:text f) "\r\n")
                 (str "--" b "\r\nContent-Disposition: form-data; name=\"" (:name f)
                      "\"\r\n\r\n" (:value f) "\r\n")))]
    [(str "multipart/form-data; boundary=" b)
     (str (apply str (map part fields)) "--" b "--\r\n")]))

(defn- apply-form-options
  "providerOptions on a multipart request: replace a field of that name, or add one."
  [fields opts]
  (reduce (fn [fs e]
            (let [k (kname (key e)) v (val e)
                  v (if (string? v) v (json/write-str v))]
              (if (some #(= k (:name %)) fs)
                (mapv (fn [f] (if (= k (:name f)) {:name k :value v} f)) fs)
                (conj fs {:name k :value v}))))
          (vec fields) opts))

(defn- with-options
  "providerOptions top-level keys overwrite a JSON body last (function face only)."
  [body opts]
  (reduce (fn [b e] (assoc b (kname (key e)) (stringify-keys (val e)))) body opts))

;; ---------------------------------------------------------------------------
;; styles (§7G.7)
;; ---------------------------------------------------------------------------

(defn load-style
  "Read one style JSON file (§7G `loadMediaStyle`)."
  [path]
  (json/read-str (fs/read-file path) {:key-fn str}))

(defn- validate-style! [s]
  (doseq [v (sort tool/compare-strings (keys (get s "verbs")))]
    (when-not (contains? verb-entry v)
      (throw (media-error (str "style: unknown verb \"" v "\"") :failed))))
  (doseq [v (vals (get s "verbs"))
          [k p] (sort-by first tool/compare-strings (get v "args"))]
    (when (= "" (str p))
      (throw (media-error (str "style: empty path for argument \"" k "\"") :failed))))
  s)

(defn- style-args
  "Mapped canonical args, canonical order; required = (canonical required ∩
  mapped) ∪ the verb's `required`."
  [s verb]
  (let [v      (get-in s ["verbs" verb])
        mapped (set (keys (get v "args")))
        extra  (set (map str (get v "required")))]
    (vec (keep (fn [a]
                 (when (contains? mapped (:name a))
                   {:name (:name a)
                    :required (boolean (or (and (:required a) true) (contains? extra (:name a))))}))
               (:args (verb-entry verb))))))

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

(defn- render-status [s]
  (cond (string? s) s
        (number? s) (render-number s)
        :else ""))

(defn- style-send! [job s method path body job-id]
  (let [res (check-status! job (http! job {:method (keyword (str/lower-case method))
                                           :url (str (:base job) path)
                                           :headers ((:headers-fn job))
                                           :body (when body (json/write-str body))
                                           :job-id job-id}))
        parsed (parse-json! job (:body res))]
    (when-let [env* (get s "errorEnvelope")]
      (let [code (read-path parsed (get env* "codePath"))]
        (when (and (some? code)
                   (not (some (fn [ok] (json-equal? ok code)) (get env* "okValues"))))
          (let [m (some-> (get env* "messagePath") (->> (read-path parsed)))]
            (fail! (str "API error " (cond (string? code) code
                                           (integral? code) (render-number code)
                                           :else (json/write-str code))
                        ": " (if (string? m) m "")))))))
    parsed))

(defn- style-extract [job v resp]
  (let [verb (:verb job)
        kind (get v "kind")
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
        (if (empty? urls)
          (none)
          {:files (gather! job kind (mapv (fn [u] {:url u}) urls) (:default-mode job))})))))

(defn- style-run
  [s cfg opts verb args ctx]
  (let [v        (get-in s ["verbs" verb])
        amap     (get v "args")
        cname    (or (get s "name") "style")
        lookup   (lookup-fn cfg)
        key*     (let [k (:key cfg)] (when (and (string? k) (seq k)) k))
        key*     (or key* (when-let [e (:key-env opts)] (lookup e)))
        _        (when (and (:key-env opts) (not (seq key*)))
                   (unavailable! (str cname ": no API key (set " (:key-env opts) ")")))
        hlookup  (if (seq key*) (fn [_] key*) lookup)
        p        (get v "poll")
        interval (positive-or (get-in cfg [:loop :interval-ms])
                              (positive-or (get p "intervalMs") 5000))
        timeout  (positive-or (get-in cfg [:loop :timeout-ms])
                              (positive-or (get p "timeoutMs") default-timeout))
        base     (trim-slash (if (and (string? (:url cfg)) (seq (:url cfg))) (:url cfg) (get s "baseUrl")))
        job      (make-job cname verb cfg ctx timeout
                           {:base base :home (origin base)
                            :default-mode (:default-mode opts "url")
                            :headers-fn (fn [] (reduce (fn [acc e] (assoc acc (key e) (expand-env (val e) hlookup)))
                                                       {} (get s "headers")))})
        args     (->> args
                      (keep (fn [e] (when (and (some? (val e)) (contains? amap (kname (key e))))
                                      [(kname (key e)) (val e)])))
                      (sort-by first tool/compare-strings))
        body     (reduce (fn [b [k a]] (write-path b (get amap k) (stringify-keys a)))
                         (stringify-keys (or (get v "defaults") {})) args)
        body     (with-options body (:provider-options ctx))
        start    (get v "start")
        _        (check-cancel! (:cancel job))
        resp     (style-send! job s (or (get start "method") "POST") (get start "path") body nil)]
    (if (= "async" (get v "mode"))
      (let [id-path (get v "idPath")
            raw     (read-path resp id-path)
            job-id  (cond (and (string? raw) (not= "" raw)) raw
                          (integral? raw) (render-number raw)
                          :else (fail! (str verb ": no job id at " id-path)))
            path    (str/replace (str (get p "path")) "{id}" (percent-encode job-id))
            [final status]
            (loop []
              (wait! job interval)
              (let [r      (style-send! job s (or (get p "method") "GET") path nil job-id)
                    status (render-status (read-path r (get p "statusPath")))]
                (report! job status job-id)
                (cond
                  (some #{status} (get p "done")) [r status]
                  (some #{status} (get p "failed"))
                  (let [m (some->> (get p "errorPath") (read-path r))]
                    (fail! (if (and (string? m) (seq m))
                             (str "job " job-id " " status ": " m)
                             (str "job " job-id " " status))))
                  (>= (mono) (:deadline job)) (timed-out! job job-id)
                  :else (recur))))]
        (assoc (style-extract job v final) :jobId job-id :status status))
      (assoc (style-extract job v resp) :status "completed"))))

(defn- style-client* [style cfg opts]
  (let [s   (validate-style! (stringify-keys style))
        cfg (or cfg {})
        run (fn [verb] (fn [args ctx] (style-run s cfg opts verb args ctx)))]
    (merge {:name     (or (get s "name") "style")
            ::style   true
            :supports (fn [verb] (contains? (get s "verbs") (kname verb)))
            :args     (fn [verb] (style-args s (kname verb)))}
           (into {} (map (fn [[verb k]] [k (run verb)]) verb-fn-key)))))

(defn style-media-client
  "§7G.7 — a MediaClient over a parsed style (map, string- or keyword-keyed),
  named `style.name` or \"style\". Uses config :url (replaces baseUrl), :loop,
  :gather (default mode \"url\"), :http-client, and :key — when set, what every
  `${…}` in the style's headers expands to. A style naming a verb outside the
  seven, or mapping an arg to \"\", throws here."
  ([style] (style-media-client style {}))
  ([style config] (style-client* style config {})))

;; ---------------------------------------------------------------------------
;; built-in API clients (§7G.9)
;; ---------------------------------------------------------------------------

(defn- arglist [& specs]
  (mapv (fn [s] (if (str/ends-with? s "*")
                  {:name (subs s 0 (dec (count s))) :required true}
                  {:name s :required false}))
        specs))

(defn- api-job
  "Key check (no key ⇒ unavailable, no request), then a job with the client's
  headers on its base URL's origin."
  [{:keys [name env-var header default-url]} cfg verb ctx]
  (let [k    (let [k (:key cfg)] (if (and (string? k) (seq k)) k ((lookup-fn cfg) env-var)))
        _    (when-not (seq k) (unavailable! (str name ": no API key (set " env-var ")")))
        base (trim-slash (if (and (string? (:url cfg)) (seq (:url cfg))) (:url cfg) default-url))]
    (make-job name verb cfg ctx (loop-timeout cfg)
              {:base base :home (origin base) :headers (header k)})))

(defn- api-client
  "A MediaClient from {:name … :args {verb [arg…]} :run {verb (fn [job args ctx])}}."
  [spec cfg]
  (let [cfg (or cfg {})]
    (merge {:name     (:name spec)
            :supports (fn [verb] (contains? (:args spec) (kname verb)))
            :args     (fn [verb] (get (:args spec) (kname verb) []))}
           (into {} (keep (fn [[verb k]]
                            (when-let [run (get (:run spec) verb)]
                              [k (fn [args ctx]
                                   (let [job (api-job spec cfg verb ctx)]
                                     (check-cancel! (:cancel job))
                                     (run job args ctx)))]))
                          verb-fn-key)))))

(defn- done [files & {:keys [text job-id]}]
  (cond-> {:files files}
    (and (string? text) (or (seq text) (empty? files))) (assoc :text text)
    job-id (assoc :jobId job-id)
    true (assoc :status "completed")))

(defn- usage-of
  "§7G.12 D21: MediaUsage from provider fields `{out-key in-key}` of `u`, only
  the numbers it reports; nil when none."
  [u pairs]
  (when (map? u)
    (not-empty (into {} (keep (fn [[out k]] (let [v (get u k)] (when (number? v) [out v]))) pairs)))))

(defn- interval-of [job dflt] (positive-or (get-in job [:cfg :loop :interval-ms]) dflt))

;; --- A2E: the embedded style with built-in defaults

(defn a2e-media-client
  "A2EMediaClient (`a2e`): the embedded a2e.json style (§7G.9). :key or
  A2E_API_KEY at call time; :url replaces baseUrl; :model is written to the
  image body's model_type; gather defaults to download."
  ([] (a2e-media-client {}))
  ([config]
   (let [s (json/read-str a2e-style-json {:key-fn str})
         s (if (and (string? (:model config)) (seq (:model config)))
             (assoc-in s ["verbs" "image" "defaults" "model_type"] (:model config))
             s)]
     (style-client* s config {:key-env "A2E_API_KEY" :default-mode "download"}))))

;; --- ElevenLabs

(def ^:private eleven-default-voice "JBFqnCBsd6RMkjVDRZzb")

(defn- ascii-lower [s] (str/lower-case (str s)))

(defn- eleven-voice-id [job voice]
  (let [resp   (api! job {:method :get
                          :url (str (:base job) "/v2/voices?page_size=100&search=" (percent-encode voice))})
        voices (let [vs (get resp "voices")] (if (sequential? vs) vs []))
        hit    (first (filter (fn [v] (or (= (ascii-lower (get v "name")) (ascii-lower voice))
                                          (= (get v "voice_id") voice)))
                              voices))]
    (cond
      hit (str (get hit "voice_id"))
      (and (= 20 (count voice)) (not (str/includes? voice " "))) voice
      :else (fail! (str "speech: voice \"" voice "\" not found")))))

(defn- form! [job url fields ctx]
  (let [[ct body] (multipart (apply-form-options fields (:provider-options ctx)))]
    (api! job {:method :post :url url :body body :content-type ct})))

(defn- text-result [job resp path]
  (let [t (read-path resp path)]
    (if (string? t)
      {:files [] :text t :status "completed"}
      (fail! (str (:verb job) ": no result at " path)))))

(defn elevenlabs-media-client
  "ElevenLabsMediaClient (`elevenlabs`): speech, music, transcribe, cloneVoice
  (§7G.9). Key ELEVENLABS_API_KEY, header xi-api-key."
  ([] (elevenlabs-media-client {}))
  ([config]
   (api-client
    {:name "elevenlabs" :env-var "ELEVENLABS_API_KEY" :default-url "https://api.elevenlabs.io"
     :header (fn [k] {"xi-api-key" k})
     :args {"speech" (arglist "text*" "voice")
            "music" (arglist "prompt*" "seconds" "instrumental")
            "transcribe" (arglist "audio*" "language")
            "cloneVoice" (arglist "name*" "samples*")}
     :run
     {"speech"
      (fn [job args ctx]
        (let [voice (:voice args)
              id    (if (some? voice) (eleven-voice-id job (str voice)) eleven-default-voice)
              model (cfg-model (:cfg job) "speech" "speech" "eleven_flash_v2_5")
              res   (api! job {:method :post :raw? true
                               :url (str (:base job) "/v1/text-to-speech/" (percent-encode id)
                                         "?output_format=mp3_22050_32")
                               :json-body (with-options {"text" (str (:text args)) "model_id" model}
                                            (:provider-options ctx))})]
          (done (gather! job "audio" [{:text (str (:body res)) :mime "audio/mpeg"}] "download")
                :job-id (khttp/header res "request-id"))))
      "music"
      (fn [job args ctx]
        (let [secs (:seconds args)
              body (cond-> {"prompt" (str (:prompt args))
                            "music_length_ms" (* 1000 (long (if (number? secs) secs 10)))}
                     (true? (:instrumental args)) (assoc "force_instrumental" true))
              res  (api! job {:method :post :raw? true
                              :url (str (:base job) "/v1/music?output_format=mp3_44100_128")
                              :json-body (with-options body (:provider-options ctx))})]
          (done (gather! job "audio" [{:text (str (:body res)) :mime "audio/mpeg"}] "download"))))
      "transcribe"
      (fn [job args ctx]
        (let [in (read-input! job (:audio args))
              fields (cond-> [{:name "model_id" :value (cfg-model (:cfg job) "transcribe" "speech" "scribe_v1")}]
                       (some? (:language args)) (conj {:name "language_code" :value (str (:language args))})
                       true (conj {:name "file" :filename (:filename in) :mime (:mime in) :text (:text in)}))]
          (text-result job (form! job (str (:base job) "/v1/speech-to-text") fields ctx) "text")))
      "cloneVoice"
      (fn [job args ctx]
        (let [ins    (mapv (fn [s] (read-input! job s)) (:samples args))
              fields (into [{:name "name" :value (str (:name args))}]
                           (map (fn [in] {:name "files" :filename (:filename in) :mime (:mime in) :text (:text in)}) ins))]
          (text-result job (form! job (str (:base job) "/v1/voices/add") fields ctx) "voice_id")))}}
    config)))

;; --- OpenAI

(def ^:private openai-format-mime {"png" "image/png" "jpeg" "image/jpeg" "webp" "image/webp"})

(defn openai-media-client
  "OpenAIMediaClient (`openai`): image, video, speech, transcribe (§7G.9). Key
  OPENAI_API_KEY; :url points it at any OpenAI-compatible server."
  ([] (openai-media-client {}))
  ([config]
   (api-client
    {:name "openai" :env-var "OPENAI_API_KEY" :default-url "https://api.openai.com/v1"
     :header (fn [k] {"Authorization" (str "Bearer " k)})
     :args {"image" (arglist "prompt*" "width" "height")
            "video" (arglist "prompt*" "seconds")
            "speech" (arglist "text*" "voice")
            "transcribe" (arglist "audio*" "language")}
     :run
     {"image"
      (fn [job args ctx]
        (let [model (cfg-model (:cfg job) "image" "image" "gpt-image-1-mini")
              w (:width args) h (:height args)
              size (if (or w h) (str (render-number (or w h)) "x" (render-number (or h w))) "1024x1024")
              body (cond-> {"model" model "prompt" (str (:prompt args)) "n" 1 "size" size}
                     (str/starts-with? model "gpt-image") (assoc "quality" "low"))
              resp (api! job {:method :post :url (str (:base job) "/images/generations")
                              :json-body (with-options body (:provider-options ctx))})
              mime (get openai-format-mime (get resp "output_format") "image/png")
              data (let [d (get resp "data")] (if (sequential? d) d []))
              rs   (vec (keep (fn [e]
                                (cond (and (string? (get e "b64_json")) (seq (get e "b64_json")))
                                      {:b64 (get e "b64_json") :mime mime}
                                      (and (string? (get e "url")) (seq (get e "url"))) {:url (get e "url")}))
                              data))]
          (when (empty? rs) (fail! "image: no result at data"))
          (let [u (usage-of (get resp "usage") [[:inputTokens "input_tokens"] [:outputTokens "output_tokens"]])]
            (cond-> (done (gather! job "image" rs "download"))
              u (assoc :usage u)))))
      "speech"
      (fn [job args ctx]
        (let [body {"model" (cfg-model (:cfg job) "speech" "image" "gpt-4o-mini-tts")
                    "input" (str (:text args))
                    "voice" (if (some? (:voice args)) (str (:voice args)) "alloy")}
              res  (api! job {:method :post :raw? true :url (str (:base job) "/audio/speech")
                              :json-body (with-options body (:provider-options ctx))})]
          (done (gather! job "audio" [{:text (str (:body res)) :mime "audio/mpeg"}] "download"))))
      "transcribe"
      (fn [job args ctx]
        (let [in (read-input! job (:audio args))
              fields (cond-> [{:name "model" :value (cfg-model (:cfg job) "transcribe" "image" "gpt-4o-mini-transcribe")}]
                       (some? (:language args)) (conj {:name "language" :value (str (:language args))})
                       true (conj {:name "file" :filename (:filename in) :mime (:mime in) :text (:text in)}))]
          (text-result job (form! job (str (:base job) "/audio/transcriptions") fields ctx) "text")))
      "video"
      (fn [job args ctx]
        (let [secs (:seconds args)
              body {"model" (cfg-model (:cfg job) "video" "image" "sora-2")
                    "prompt" (str (:prompt args))
                    "seconds" (render-number (if (number? secs) secs 4))}
              resp (api! job {:method :post :url (str (:base job) "/videos")
                              :json-body (with-options body (:provider-options ctx))})
              raw  (get resp "id")
              id   (cond (and (string? raw) (seq raw)) raw
                         (integral? raw) (render-number raw)
                         :else (fail! "video: no job id at id"))
              url  (str (:base job) "/videos/" (percent-encode id))
              interval (interval-of job 5000)]
          (loop []
            (wait! job interval)
            (let [r  (api! job {:method :get :url url :job-id id})
                  st (render-status (get r "status"))]
              (report! job st id)
              (cond
                (= "completed" st) nil
                (= "failed" st)
                (let [m (read-path r "error.message")]
                  (fail! (if (and (string? m) (seq m)) (str "job " id " failed: " m) (str "job " id " failed"))))
                (>= (mono) (:deadline job)) (timed-out! job id)
                :else (recur))))
          (let [res (api! job {:method :get :raw? true :url (str url "/content") :job-id id})]
            (done (gather! job "video" [{:text (str (:body res)) :mime "video/mp4"}] "download")
                  :job-id id))))}}
    config)))

;; --- OpenRouter

(defn openrouter-media-client
  "OpenRouterMediaClient (`openrouter`): image over chat completions (§7G.9).
  Key OPENROUTER_API_KEY."
  ([] (openrouter-media-client {}))
  ([config]
   (api-client
    {:name "openrouter" :env-var "OPENROUTER_API_KEY" :default-url "https://openrouter.ai/api/v1"
     :header (fn [k] {"Authorization" (str "Bearer " k)})
     :args {"image" (arglist "prompt*")}
     :run
     {"image"
      (fn [job args ctx]
        (let [body {"model" (cfg-model (:cfg job) "image" "image" "google/gemini-2.5-flash-image")
                    "modalities" ["image" "text"]
                    "messages" [{"role" "user" "content" (str (:prompt args))}]}
              resp (api! job {:method :post :url (str (:base job) "/chat/completions")
                              :json-body (with-options body (:provider-options ctx))})
              chs  (let [c (get resp "choices")] (if (sequential? c) c []))
              text (apply str (keep (fn [c] (let [t (get-in c ["message" "content"])] (when (string? t) t))) chs))
              rs   (vec (for [c chs
                              im (let [ims (get-in c ["message" "images"])] (if (sequential? ims) ims []))
                              :let [u (get-in im ["image_url" "url"])]
                              :when (and (string? u) (seq u))]
                          (if (str/starts-with? u "data:") {:data-url u} {:url u})))
              jid  (let [i (get resp "id")] (when (and (string? i) (seq i)) i))
              u    (usage-of (get resp "usage") [[:inputTokens "prompt_tokens"] [:outputTokens "completion_tokens"]
                                                 [:costUsd "cost"]])]
          (when (empty? rs)
            (fail! (str "image: no image in response" (when (seq text) (str ": " (cap-cp text 200))))))
          (cond-> {:files (gather! job "image" rs "download")}
            (seq text) (assoc :text text)
            jid (assoc :jobId jid)
            true (assoc :status "completed")
            u (assoc :usage u))))}}
    config)))

;; --- fal

(defn- fal-run [job model input ctx kind]
  (let [resp (api! job {:method :post :url (str (:base job) "/" model)
                        :json-body (with-options input (:provider-options ctx))})
        raw  (get resp "request_id")
        rid  (cond (and (string? raw) (seq raw)) raw
                   (integral? raw) (render-number raw)
                   :else (fail! (str (:verb job) ": no job id at request_id")))
        ;; D10: absent URLs are built from <url>/<model>/requests/<id>
        given (fn [k] (let [u (get resp k)] (when (and (string? u) (seq u)) u)))
        rbase (str (:base job) "/" model "/requests/" (percent-encode rid))
        surl (or (given "status_url") (str rbase "/status"))
        rurl (or (given "response_url") rbase)
        interval (interval-of job 500)]
    (loop []
      (wait! job interval)
      (let [r   (api! job {:method :get :url surl :job-id rid})
            st  (render-status (get r "status"))
            err (get r "error")]
        (report! job st rid)
        (cond
          (= "COMPLETED" st) nil
          (and (some? err) (not= "" err))
          (fail! (str "job " rid " failed: " (if (string? err) err (json/write-str err))))
          (>= (mono) (:deadline job)) (timed-out! job rid)
          :else (recur))))
    (let [r    (api! job {:method :get :url rurl :job-id rid})
          urls (vec (filter (fn [u] (and (string? u) (seq u)))
                            (concat (map (fn [i] (get i "url"))
                                         (let [is (get r "images")] (if (sequential? is) is [])))
                                    [(read-path r "image.url") (read-path r "video.url")
                                     (read-path r "audio.url") (read-path r "audio_file.url")])))]
      (when (empty? urls) (fail! (str (:verb job) ": no result in response")))
      (done (gather! job kind (mapv (fn [u] {:url u}) urls) "download") :job-id rid))))

(defn fal-media-client
  "FalMediaClient (`fal`): any hosted model id over fal's queue API (§7G.9).
  Key FAL_API_KEY, header `Authorization: Key <key>`."
  ([] (fal-media-client {}))
  ([config]
   (api-client
    {:name "fal" :env-var "FAL_API_KEY" :default-url "https://queue.fal.run"
     :header (fn [k] {"Authorization" (str "Key " k)})
     :args {"image" (arglist "prompt*" "width" "height" "seed")
            "video" (arglist "prompt*" "image" "seconds" "aspectRatio")}
     :run
     {"image"
      (fn [job args ctx]
        (let [model (cfg-model (:cfg job) "image" "image" "fal-ai/flux/schnell")
              w (:width args) h (:height args)
              input (cond-> {"prompt" (str (:prompt args)) "num_images" 1}
                      (str/includes? model "schnell") (assoc "image_size" "square" "num_inference_steps" 4)
                      (or w h) (assoc "image_size" {"width" (or w h) "height" (or h w)})
                      (some? (:seed args)) (assoc "seed" (:seed args)))]
          (fal-run job model input ctx "image")))
      "video"
      (fn [job args ctx]
        (let [img   (:image args)
              model (cfg-model (:cfg job) "video" "image"
                               (if (some? img)
                                 "fal-ai/kling-video/v2.1/standard/image-to-video"
                                 "fal-ai/kling-video/v2.1/standard/text-to-video"))
              secs  (:seconds args)
              input (cond-> {"prompt" (str (:prompt args))
                             "duration" (render-number (if (number? secs) secs 5))}
                      (some? img) (assoc "image_url" (str img))
                      (some? (:aspectRatio args)) (assoc "aspect_ratio" (str (:aspectRatio args))))]
          (fal-run job model input ctx "video")))}}
    config)))

;; ---------------------------------------------------------------------------
;; CLI clients (§7G.10)
;; ---------------------------------------------------------------------------

(def ^:private path-re #"(?i)/[^\s\"'()\[\]]+\.(png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)")

(defn- regular-file? [p] (and (fs/exists? p) (not (fs/directory? p))))

(defn- folder-entries
  "Direct children of `dir` (non-recursive), sorted by name."
  [dir]
  (if (fs/directory? dir)
    (let [d (trim-slash dir)]
      (->> (fs/list-tree d)
           (map str)
           (filter (fn [p] (and (str/starts-with? p (str d "/"))
                                (not (str/includes? (subs p (inc (count d))) "/")))))
           (sort tool/compare-strings)
           vec))
    []))

(defn- kind-ok? [verb p]
  (let [m (ext-mime-of p)]
    (and m (str/starts-with? m (str verb "/")))))

(defn- arg-text
  "An arg value as prompt text: a string as-is, an integral number as integer text."
  [v]
  (cond (string? v) v
        (number? v) (render-number v)
        (nil? v)    ""
        :else       (str v)))

(def ^:private cli-hints
  [["width" "width " " px"] ["height" "height " " px"]
   ["seconds" "about " " seconds long"] ["aspectRatio" "aspect ratio " ""]])

(defn fold-media-prompt
  "§7G CLI hint folding: the prompt a CLI style receives — `prompt`, then the
  present size hints in canonical verb order as \" (h1, h2)\"; nothing appended
  when none is present. `args` keys may be keywords or strings."
  [verb args]
  (let [get* (fn [k] (let [v (get args (keyword k))] (if (some? v) v (get args k))))
        hs   (for [a (:args (verb-entry (kname verb)))
                   [n pre post] cli-hints
                   :when (= n (:name a))
                   :let [v (get* n)]
                   :when (some? v)]
               (str pre (arg-text v) post))]
    (if (seq hs)
      (str (arg-text (get* "prompt")) " (" (str/join ", " hs) ")")
      (arg-text (get* "prompt")))))

(defn- absolute [p]
  (if (str/starts-with? p "/") p (str (fs/real-path ".") "/" p)))

(defn- cli-mock-run
  "§7G CLI mock presets: no process. Writes \"<gather.dir>/<client>-mock-<verb>.<png|mp4>\"
  holding \"toolnexus mock <verb>\", gathers it like a CLI file; text = the exact
  prompt the CLI would have received; jobId \"mock\"."
  [spec cfg job verb prompt]
  (let [ext  (if (= "video" verb) ".mp4" ".png")
        d    (get-in cfg [:gather :dir])
        dir  (if (and (string? d) (seq d)) d (str (temp-root) "/toolnexus-media"))
        _    (fs/mkdirs! dir)
        p    (absolute (str (trim-slash dir) "/" (:name spec) "-mock-" verb ext))]
    (fs/write-file p (str "toolnexus mock " verb))
    {:files  (gather! job verb [{:path p}] "download")
     :text   prompt
     :jobId  "mock"
     :status "completed"}))

(declare cli-spawn-run)

(defn- cli-run [spec cfg verb args ctx]
  (let [cname   (:name spec)
        timeout (loop-timeout cfg)
        job     (make-job cname verb cfg ctx timeout)
        prompt  (str/replace (get-in spec [:templates verb]) "{prompt}" (fold-media-prompt verb args))]
    (if (:mock spec)
      (cli-mock-run spec cfg job verb prompt)
      (cli-spawn-run spec cfg job verb prompt timeout))))

(defn- cli-spawn-run [spec cfg job verb prompt timeout]
  (let [cname   (:name spec)
        cmd     (let [c (:command cfg)]
                  (cond (string? c) [c]
                        (and (sequential? c) (seq c)) (mapv str c)
                        :else [(:default-command spec)]))
        model   (cfg-model cfg verb verb nil)
        cwd     (fs/real-path (fs/temp-dir! "toolnexus-cli")) ; D17: realpath (macOS /var => /private/var)
        argv    ((:argv spec) {:prompt prompt :cwd cwd :model model})]
    (try
      (check-cancel! (:cancel job))
      (let [child (try (proc/spawn (into cmd argv) (cond-> {:dir cwd}
                                                     (seq (:env spec)) (assoc :env (:env spec))))
                       (catch Throwable _ nil))
            _     (when-not child
                    (unavailable! (str cname ": cannot start \"" (first cmd) "\"")))
            lines (atom [])
            eof   (atom false)
            exit  (atom nil)
            _     (proc/run-async! (fn []
                                     (try
                                       (loop []
                                         (if-let [l (proc/read-line! child)]
                                           (do (swap! lines conj l) (recur))
                                           (reset! eof true)))
                                       (catch Throwable _ (reset! eof true)))))
            ;; stdin closed at once: the CLI reads no prompt from it.
            _     (proc/run-async! (fn [] (try (reset! exit (proc/close! child)) (catch Throwable _ nil))))
            state (atom {:text "" :paths [] :error nil :session nil})
            consume (fn [l]
                      (let [o (try (json/read-str l {:key-fn str}) (catch Throwable _ nil))]
                        (when (map? o)
                          (let [{:keys [session text paths error status usage]} ((:parse spec) o l)]
                            (swap! state (fn [s] (cond-> s
                                                   usage   (assoc :usage usage)
                                                   session (assoc :session session)
                                                   text    (update :text str text)
                                                   (seq paths) (update :paths into paths)
                                                   error   (assoc :error error))))
                            (when status (report! job status nil))))))
            kill! (fn [] (try (proc/kill! child) (catch Throwable _ nil)))]
        (loop [i 0]
          (let [ls @lines n (count ls)]
            (if (< i n)
              (do (consume (nth ls i)) (recur (inc i)))
              (do
                (when (cancel-reason (:cancel job)) (kill!) (check-cancel! (:cancel job)))
                (when (>= (mono) (:deadline job))
                  (kill!)
                  (throw (media-error (str cname ": timed out after " (render-number timeout) "ms") :timeout)))
                (if (and @eof (= n (count @lines)))
                  nil
                  (do (ktime/sleep! 5) (recur i)))))))
        (let [code    (or @exit (proc/await-exit! child 2000) (proc/exit-code child))
              {:keys [text paths error session usage]} @state
              scanned (map first (re-seq path-re text))
              folder  (when (and (:folder spec) session) (folder-entries ((:folder spec) cfg session)))
              cands   (reduce (fn [acc p] (if (some #{p} acc) acc (conj acc p)))
                              [] (concat paths scanned folder))
              kept    (filterv (fn [p] (and (regular-file? p) (kind-ok? verb p))) cands)]
          (if (seq kept)
            (cond-> {:files (gather! job verb (mapv (fn [p] {:path p}) kept) "download")}
              (seq text) (assoc :text text)
              session (assoc :jobId session)
              true (assoc :status "completed")
              usage (assoc :usage usage))
            (if error
              (if-let [msg ((:unavailable spec) verb error)]
                (unavailable! msg)
                (fail! (str cname ": no " verb ": " (cap-cp error 400))))
              (fail! (str cname ": no " verb " file in the run (exit code " (render-number (or code -1)) ")"
                          (when (seq text) (str ": " (cap-cp text 300)))))))))
      (finally
        (try (fs/delete-tree! cwd) (catch Throwable _ nil))))))

(defn- cli-args [verb]
  (into [{:name "prompt" :required true}]
        (map (fn [n] {:name n :required false})
             (if (= "video" verb) ["seconds" "aspectRatio"] ["width" "height" "aspectRatio"]))))

(defn cli-media-client
  "CliMediaClient (§7G.10): run a local CLI once, headless, read its JSON-lines
  stdout, collect files, kill on cancel/timeout. `spec`:
    :name             client name
    :verbs            #{\"image\" …}
    :default-command  executable when config :command is absent
    :argv             (fn [{:keys [prompt cwd model]}]) -> [arg …]
    :env              fixed child env {name value}
    :templates        {verb \"… {prompt} …\"}
    :parse            (fn [obj line]) -> {:session :text :paths :error :status}
    :folder           optional (fn [config session]) -> dir to scan
    :unavailable      (fn [verb error]) -> message when unavailable, else nil
    :mock             true => never spawn; return a fixed file (§7G CLI mock presets)
  CLI clients accept prompt plus size hints (image: width, height,
  aspectRatio; video: seconds, aspectRatio), folded into the prompt by
  `fold-media-prompt`."
  [spec config]
  (let [cfg  (or config {})
        spec (cond-> spec (::mock cfg) (assoc :mock true))
        vs   (set (:verbs spec))]
    (merge {:name     (:name spec)
            :supports (fn [verb] (contains? vs (kname verb)))
            :args     (fn [verb] (when (contains? vs (kname verb)) (cli-args (kname verb))))}
           (into {} (keep (fn [[verb k]]
                            (when (contains? vs verb)
                              [k (fn [args ctx] (cli-run spec cfg verb args ctx))]))
                          verb-fn-key)))))

(defn- lower-has? [s w] (str/includes? (str/lower-case (str s)) w))

(def ^:private grok-config "{\"features\":{\"image_gen\":true,\"video_gen\":true}}")

(defn grok-media-client
  "GrokMediaClient (`grok`): image and video through `grok -p … --output-format
  streaming-json` on the user's Grok subscription. Never edits Grok settings;
  under Zero Data Retention video is `unavailable` (§7G.10)."
  ([] (grok-media-client {}))
  ([config]
   (cli-media-client
    {:name "grok" :verbs #{"image" "video"} :default-command "grok"
     :env {"GROK_CONFIG" grok-config}
     :argv (fn [{:keys [prompt cwd model]}]
             (cond-> ["-p" prompt "--cwd" cwd "--output-format" "streaming-json" "--yolo" "--no-auto-update"]
               model (into ["-m" model])))
     :templates {"image" "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path."
                 "video" "Generate exactly one video with your video generation tool: {prompt}. Do not write code. Reply only with the file path."}
     :parse (fn [o _]
              (case (get o "type")
                "text" (let [d (get o "data")] (when (string? d) {:text d}))
                "tool_call" (let [n (get o "toolName")] (when (some? n) {:status (str "tool " n)}))
                "tool_call_update"
                (let [st (get o "status") raw (get o "rawOutput")]
                  (cond-> {}
                    (and (string? st) (seq st)) (assoc :status (str "tool " st))
                    (= "completed" st) (merge (let [p (when (map? raw) (get raw "path"))]
                                                (when (and (string? p) (seq p)) {:paths [p]})))
                    (= "failed" st) (assoc :error (let [m (when (map? raw) (get raw "message"))]
                                                    (if (string? m) m (json/write-str raw))))))
                "end" (cond-> {:status (str "end " (render-status (get o "stopReason")))}
                        (string? (get o "sessionId")) (assoc :session (get o "sessionId"))
                        true (merge (let [u (get o "usage")
                                          tk (get o "total_cost_usd_ticks")
                                          m (cond-> (or (usage-of (when (map? u) u) [[:inputTokens "input_tokens"] [:outputTokens "output_tokens"]]) {})
                                              (number? (get o "total_cost_usd")) (assoc :costUsd (get o "total_cost_usd"))
                                              ;; D21: ticks carried raw, never converted
                                              (number? tk) (assoc :raw {:total_cost_usd_ticks tk}))]
                                      (when (seq m) {:usage m}))))
                "error" {:error (str (get o "message"))}
                nil))
     :unavailable (fn [verb err]
                    (cond (lower-has? err "zero data retention")
                          (str "grok: " verb " blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)")
                          (lower-has? err "usage limit") (str "grok: " (cap-cp err 400))
                          :else nil))}
    config)))

(defn codex-media-client
  "CodexMediaClient (`codex`): image through `codex exec --json` on the user's
  ChatGPT plan. The file comes from the reply text or
  $CODEX_HOME/generated_images/<thread> (§7G.10)."
  ([] (codex-media-client {}))
  ([config]
   (cli-media-client
    {:name "codex" :verbs #{"image"} :default-command "codex"
     :argv (fn [{:keys [prompt model]}]
             (-> ["exec" "--json" "--skip-git-repo-check" "-s" "workspace-write"]
                 (cond-> model (into ["-m" model]))
                 (conj prompt)))
     :templates {"image" "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved."}
     :parse (fn [o line]
              (let [t (get o "type")]
                (cond-> {:status (render-status t)}
                  (= "thread.started" t) (assoc :session (let [s (get o "thread_id")] (when (string? s) s)))
                  (and (= "item.completed" t) (= "agent_message" (get-in o ["item" "type"]))
                       (string? (get-in o ["item" "text"])))
                  (assoc :text (get-in o ["item" "text"]))
                  (or (= "error" t) (= "turn.failed" t))
                  (assoc :error (let [m (get o "message") em (get-in o ["error" "message"])]
                                  (cond (string? m) m (string? em) em :else line))))))
     :folder (fn [cfg session]
               (let [lookup (lookup-fn cfg)
                     home   (or (lookup "CODEX_HOME") (str (or (lookup "HOME") (env/get-env "HOME") "") "/.codex"))]
                 (str (trim-slash home) "/generated_images/" session)))
     :unavailable (fn [_ err]
                    (when (or (lower-has? err "usage limit") (lower-has? err "quota"))
                      (str "codex: " (cap-cp err 400))))}
    config)))

;; ---- Google Antigravity (agy)

(defn- dig [o ks] (reduce (fn [m k] (when (map? m) (get m k))) o ks))

(defn- agy-media-client
  "`agy -p … --output-format stream-json --dangerously-skip-permissions`
  (image). The path is in the final result's response text, else the newest
  image in the conversation's brain folder."
  [config]
  (cli-media-client
   {:name "agy" :verbs #{"image"} :default-command "agy"
    :argv (fn [{:keys [prompt model]}]
            (cond-> ["-p" prompt "--output-format" "stream-json" "--dangerously-skip-permissions"]
              model (into ["--model" model])))
    :templates {"image" "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the absolute path where the image was saved."}
    :parse (fn [o line]
             (case (get o "event")
               "init" {:session (let [c (get o "conversation_id")] (when (string? c) c)) :status "init"}
               "step_update" (let [t (dig o ["step_update" "tool_name"]) st (dig o ["step_update" "state"])]
                               (when (and (string? t) (seq t) (string? st) (seq st))
                                 {:status (str "tool " t " " st)}))
               "result" (let [sess (dig o ["result" "conversation_id"])
                              txt  (dig o ["result" "response"])
                              txt  (when (string? txt) txt)
                              st   (dig o ["result" "status"])
                              st   (if (string? st) st "")
                              e    (dig o ["result" "error"])
                              em   (dig o ["result" "error" "message"])
                              u    (usage-of (dig o ["result" "usage"]) [[:inputTokens "input_tokens"] [:outputTokens "output_tokens"]])]
                          (cond-> {:status (str "result " st)}
                            (string? sess) (assoc :session sess)
                            (seq txt) (assoc :text txt)
                            ;; live: agy reports an exhausted image quota in a SUCCESS result's text
                            (and (= "SUCCESS" st) (or (lower-has? txt "quota") (lower-has? txt "resource_exhausted")))
                            (assoc :error txt)
                            (not= "SUCCESS" st)
                            (assoc :error (cond (and (string? e) (seq e)) e
                                                (and (string? em) (seq em)) em
                                                (seq txt) txt
                                                :else (json/write-str (get o "result"))))
                            u (assoc :usage u)))
               "error" (let [m (get o "message")] (when (string? m) {:error m}))
               nil))
    :folder (fn [cfg session]
              (let [home (or ((lookup-fn cfg) "HOME") (env/get-env "HOME") "")]
                (str (trim-slash home) "/.gemini/antigravity-cli/brain/" session)))
    :unavailable (fn [_ err]
                   (when (or (lower-has? err "quota") (lower-has? err "usage limit"))
                     (str "agy: " (cap-cp err 400))))}
   config))

;; ---------------------------------------------------------------------------
;; loadMediaStyle — CLI presets as styles (ADR 0042)
;; ---------------------------------------------------------------------------

(def xai-grok-cli "Preset: the `grok` CLI (image, video)." "xai-grok-cli")
(def openai-codex-cli "Preset: the `codex` CLI (image)." "openai-codex-cli")
(def google-agy-cli "Preset: Google's Antigravity CLI `agy` (image)." "google-agy-cli")
(def xai-grok-cli-mock "Mock twin of `xai-grok-cli`: never spawns, returns a fixed file." "xai-grok-cli-mock")
(def openai-codex-cli-mock "Mock twin of `openai-codex-cli`." "openai-codex-cli-mock")
(def google-agy-cli-mock "Mock twin of `google-agy-cli`." "google-agy-cli-mock")

(def ^:private cli-presets
  {"xai-grok-cli" grok-media-client "openai-codex-cli" codex-media-client "google-agy-cli" agy-media-client})

(defn load-media-style
  "§7G `loadMediaStyle(pathOrPreset, config?)` -> a loaded style, itself a
  MediaClient (pass it to `create-media`). A built-in preset name (`xai-grok-cli`,
  `openai-codex-cli`, `google-agy-cli`, or a `-mock` twin) is served by that CLI
  client; anything else is a style JSON file path (or an already-parsed style
  map). `config` (model, command, loop, gather, …) configures the client
  behind the style."
  ([path-or-preset] (load-media-style path-or-preset {}))
  ([path-or-preset config]
   (let [cfg  (or config {})
         p    (when (string? path-or-preset) path-or-preset)
         mock (and p (str/ends-with? p "-mock"))
         base (when p (if mock (subs p 0 (- (count p) 5)) p))
         preset (contains? cli-presets base)
         sty  (when-not preset (stringify-keys (if p (load-style p) path-or-preset)))
         c    (if preset
                ((get cli-presets base) (cond-> cfg mock (assoc ::mock true)))
                (style-client* sty cfg {}))]
     (assoc c ::loaded-style true
            ::source-id (if preset p (or (get sty "name") "style"))
            ::preset (when preset p)
            ::style-json sty
            ::cfg cfg))))

;; ---------------------------------------------------------------------------
;; available() — the cheap pre-flight check (§7G.10b)
;; ---------------------------------------------------------------------------

(def mock-available-env
  "Set to \"0\" to make every mock preset report unavailable."
  "TOOLNEXUS_MEDIA_MOCK_AVAILABLE")

(defn- on-path?
  "A name with a path separator is checked as a file; otherwise searched on PATH."
  [lookup name]
  (if (or (str/includes? name "/") (str/includes? name "\\"))
    (regular-file? name)
    (boolean (some (fn [d] (regular-file? (str (trim-slash d) "/" name)))
                   (remove str/blank? (str/split (or (lookup "PATH") (env/get-env "PATH") "") #":"))))))

(defn- cli-auth-file [lookup label]
  (let [home (trim-slash (or (lookup "HOME") (env/get-env "HOME") ""))]
    (case label
      "grok"  (str home "/.grok/auth.json")
      "codex" (let [h (lookup "CODEX_HOME")]
                (if (and (string? h) (seq h)) (str (trim-slash h) "/auth.json") (str home "/.codex/auth.json")))
      "agy"   (str home "/.gemini/antigravity-cli/antigravity-oauth-token")
      nil)))

(defn available
  "§7G.10b `style.available()` on a loaded style -> {:installed :loggedIn :reason},
  fast, generating nothing and reading no secret. CLI preset: the command
  (config :command first element, else the CLI name) is found, and the CLI's own
  login file EXISTS (never read). Mock preset: always available unless
  TOOLNEXUS_MEDIA_MOCK_AVAILABLE=0. HTTP style: config :key, or every ${VAR}
  in its headers set. Env is read through config :env when given."
  [style]
  (let [cfg    (::cfg style)
        lookup (lookup-fn cfg)
        set?   (fn [k] (let [v (lookup k)] (and (string? v) (seq v))))
        label  (:name style)]
    (if-let [preset (::preset style)]
      (if (str/ends-with? preset "-mock")
        (if (= "0" (lookup mock-available-env))
          {:installed false :loggedIn false :reason (str label ": mock set unavailable by " mock-available-env "=0")}
          {:installed true :loggedIn true :reason (str label ": mock")})
        (let [c   (:command cfg)
              bin (cond (string? c) c
                        (and (sequential? c) (seq c)) (str (first c))
                        :else label)]
          (if-not (on-path? lookup bin)
            {:installed false :loggedIn false :reason (str label ": \"" bin "\" not found on PATH")}
            (let [auth (cli-auth-file lookup label)]
              (if (regular-file? auth)
                {:installed true :loggedIn true :reason (str label ": installed, logged in (" auth " present)")}
                {:installed true :loggedIn false :reason (str label ": installed, not logged in (" auth " missing)")})))))
      (let [id (::source-id style)
            k  (:key cfg)]
        (if (and (string? k) (seq k))
          {:installed true :loggedIn true :reason (str id ": key from config")}
          (let [vars    (sort tool/compare-strings
                              (distinct (mapcat (fn [v] (map second (re-seq #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}" (str v))))
                                                (vals (get (::style-json style) "headers")))))
                missing (first (remove set? vars))]
            (if missing
              {:installed true :loggedIn false :reason (str id ": " missing " is not set")}
              {:installed true :loggedIn true :reason (str id ": env present")})))))))

;; ---------------------------------------------------------------------------
;; createMedia — routing (§7G.3)
;; ---------------------------------------------------------------------------

(defn- client-map? [x] (and (map? x) (contains? x :supports)))

(defn- noun
  "\"style\" when every source is a loaded style, else \"client\"."
  [media]
  (if (every? ::loaded-style (:clients media)) "style" "client"))

(defn create-media
  "§7G.3 `createMedia`: one MediaClient or a seq of them. Each verb goes to the
  first client that supports it; an `unavailable` error falls through to the
  next. `(create-media [])` throws `media: no clients`."
  [clients]
  (let [cs (cond (nil? clients) []
                 (client-map? clients) [clients]
                 :else (vec clients))]
    (when (empty? cs) (throw (media-error "media: no clients" :failed)))
    {::media true :clients cs}))

(defn media?
  "True for a value built by `create-media`."
  [x]
  (and (map? x) (true? (::media x))))

(def ^:private verb-aliases
  (merge (into {} (map (fn [v] [v v]) (keys verb-fn-key)))
         {"clone-voice" "cloneVoice" "clone_voice" "cloneVoice"}))

(defn- canonical-verb [verb] (get verb-aliases (kname verb) (kname verb)))

(defn- client-supports? [c verb]
  (boolean (try ((:supports c) verb) (catch Throwable _ false))))

(defn- client-args
  "The client's accepted args for `verb`: [{:name :required}], or every
  canonical arg (canonical required) when it declares no :args."
  [c verb]
  (if-let [f (:args c)]
    (mapv (fn [a] {:name (kname (or (:name a) (get a "name")))
                   :required (boolean (or (:required a) (get a "required")))})
          (f verb))
    (mapv (fn [a] {:name (:name a) :required (boolean (:required a))})
          (:args (verb-entry verb)))))

(defn- canonical-index [verb]
  (into {} (map-indexed (fn [i a] [(:name a) i]) (:args (verb-entry verb)))))

(defn- check-canonical!
  "§7G.3 step 3 (D7/D19): a key that is not a canonical arg of the verb is
  served by no client — a plain `failed` error before any client is tried."
  [verb args]
  (let [idx (canonical-index verb)]
    (doseq [k (sort tool/compare-strings (keys args))]
      (when-not (contains? idx k)
        (fail! (str verb ": argument \"" k "\" not supported"))))))

(defn- skip-reason
  "§7G.3 step 4 (D19): why candidate `c` cannot take `args`, or nil."
  [c verb args]
  (let [accepted (client-args c verb)
        names    (set (map :name accepted))
        idx      (canonical-index verb)]
    (or (some (fn [k] (when-not (contains? names k)
                        (str (:name c) ": argument \"" k "\" not supported")))
              (sort tool/compare-strings (keys args)))
        (some (fn [a] (when-not (contains? args (:name a))
                        (str (:name c) ": missing required argument \"" (:name a) "\"")))
              (sort-by (fn [a] (get idx (:name a) 1000)) (filter :required accepted))))))

(defn- clean-args [args]
  (into {} (keep (fn [e] (when (some? (val e)) [(kname (key e)) (val e)])) (or args {}))))

(defn- source-model
  "The configured model for `verb` of a loaded style, or nil (never guessed)."
  [c verb]
  (when (::loaded-style c)
    (let [cfg (::cfg c)
          ms  (:models cfg)
          m   (or (get ms (keyword verb)) (get ms verb))]
      (cond (and (string? m) (seq m)) m
            (and (string? (:model cfg)) (seq (:model cfg))) (:model cfg)
            :else nil))))

(defn- stamp
  "§7G.10b: MediaResult :style (who served it) and :model, unless the client set them."
  [r c verb]
  (if (map? r)
    (let [id (or (::source-id c) (:name c))
          m  (source-model c verb)]
      (cond-> r
        (and (not (seq (:style r))) (seq id)) (assoc :style id)
        (and (not (seq (:model r))) m) (assoc :model m)))
    r))

(defn- route
  "-> [MediaResult client-name]"
  [media verb args ctx]
  (let [verb  (canonical-verb verb)
        cands (when (contains? verb-entry verb)
                (filterv (fn [c] (client-supports? c verb)) (:clients media)))
        _     (when (empty? cands) (fail! (str "media: no " (noun media) " supports \"" verb "\"")))
        args  (clean-args args)
        _     (check-canonical! verb args)
        kargs (into {} (map (fn [e] [(keyword (key e)) (val e)]) args))
        ctx   (or ctx {})]
    (loop [cs cands msgs []]
      (if (empty? cs)
        (throw (media-error (str "media: no client available for \"" verb "\": " (str/join "; " msgs))
                            :unavailable))
        (let [c    (first cs)
              f    (get c (verb-fn-key verb))
              skip (skip-reason c verb args)
              out  (if skip
                     {:unavailable skip}
                     (try
                       (when-not f (fail! (str "media: no client supports \"" verb "\"")))
                       {:ok (f kargs ctx)}
                       (catch Throwable e
                         (if (unavailable? e) {:unavailable (ex-message e)} (throw e)))))]
          (if (contains? out :ok)
            [(stamp (:ok out) c verb) (:name c)]
            (recur (rest cs) (conj msgs (:unavailable out)))))))))

(defn generate
  "§7G.3 — the generic function face. `verb` is the canonical verb name
  (\"image\" … \"cloneVoice\" \"talk\", string or keyword; :clone-voice works
  too). `args` maps canonical arg names (keyword or string) to values. ctx:
  `:cancel` (atom or 0-arg fn — truthy ⇒ cancelled), `:deadline` (epoch ms),
  `:progress` (fn [{:verb :client :jobId :status :elapsedMs}]),
  `:provider-options` (map; overwrites the request body's top-level keys).
  Returns MediaResult `{:files [..] :text? :jobId? :status}`; throws the media
  error (`ex-data` `:status`) on any failure."
  ([media verb args] (generate media verb args {}))
  ([media verb args ctx] (first (route media verb args ctx))))

(defn image       ([m args] (generate m "image" args))      ([m args ctx] (generate m "image" args ctx)))
(defn video       ([m args] (generate m "video" args))      ([m args ctx] (generate m "video" args ctx)))
(defn speech      ([m args] (generate m "speech" args))     ([m args ctx] (generate m "speech" args ctx)))
(defn music       ([m args] (generate m "music" args))      ([m args ctx] (generate m "music" args ctx)))
(defn transcribe  ([m args] (generate m "transcribe" args)) ([m args ctx] (generate m "transcribe" args ctx)))
(defn clone-voice ([m args] (generate m "cloneVoice" args)) ([m args ctx] (generate m "cloneVoice" args ctx)))
(defn talk        ([m args] (generate m "talk" args))       ([m args ctx] (generate m "talk" args ctx)))

(defn supports?
  "True when any client of `media` supports `verb`."
  [media verb]
  (let [v (canonical-verb verb)]
    (boolean (and (contains? verb-entry v)
                  (some (fn [c] (client-supports? c v)) (:clients media))))))

(defn supported-verbs
  "§7G `media.verbs()`: every verb some source supports, in canonical order."
  [media]
  (vec (keep (fn [e] (when (supports? media (:verb e)) (:verb e))) verbs)))

(defn generate-media
  "One-shot sugar: `(generate (create-media (load-media-style style config)) verb args)`."
  ([style verb args] (generate-media style verb args {}))
  ([style verb args config]
   (generate (create-media (load-media-style style config)) verb args)))

;; ---------------------------------------------------------------------------
;; tool face (§7G.6)
;; ---------------------------------------------------------------------------

;; D2: the ready word comes from the verb's kind, never from the files
(def ^:private verb-word {"image" "image" "video" "video" "talk" "video" "speech" "audio" "music" "audio"})

(defn- b64-length [s]
  (let [n (count s) pad (count (take-while #(= \= %) (reverse s)))]
    (- (quot (* 3 n) 4) pad)))

(defn- file-line [f]
  (cond (:path f) (:path f)
        (:url f)  (:url f)
        :else     (str "inline " (:mimeType f) " " (b64-length (str (:data f))) " bytes")))

(defn- file-part
  "§1B part for one MediaFile — never a path. D20: the MediaFile's local path
  rides in the part's Clojure METADATA (::path), invisible to `=` and to every
  serialised shape, so `run-files` can put it on RunResult.files."
  [f]
  (let [base {:type (:type f) :mimeType (:mimeType f)}
        part (cond (:data f)           (assoc base :data (:data f))
                   (http-url? (:url f)) (assoc base :url (:url f))
                   :else (let [p (or (:path f) (let [u (str (:url f))] (if (str/starts-with? u "file://") (subs u 7) u)))]
                           (assoc base :data (codec/encode (fs/read-bytes p)))))]
    (if (:path f) (with-meta part {::path (:path f)}) part)))

(defn part-path
  "D20: the local path a media part came from, or nil."
  [part]
  (::path (meta part)))

(defn- tool-result [verb client r]
  (let [meta* (cond-> {:source "media" :verb verb :client client}
                (:jobId r) (assoc :jobId (:jobId r))
                true       (assoc :status (:status r))
                (:usage r) (assoc :usage (:usage r)))]
    (if (contains? text-verbs verb)
      (tool/success (str (:text r)) meta*)
      (let [files (:files r)]
        (tool/with-parts
          (tool/success (str (get verb-word verb "image") " ready: "
                             (str/join "\n" (map file-line files)))
                        meta*)
          (mapv file-part files))))))

(defn- input-schema [entry accepted]
  (let [acc  (into {} (map (fn [a] [(:name a) a]) accepted))
        args (filter (fn [a] (contains? acc (:name a))) (:args entry))]
    {:type "object"
     :properties (apply array-map
                        (mapcat (fn [a]
                                  [(keyword (:name a))
                                   (if (= "array" (:type a))
                                     {:type "array" :items {:type (:items a)} :description (:description a)}
                                     {:type (:type a) :description (:description a)})])
                                args))
     :required (vec (keep (fn [a] (when (:required (get acc (:name a))) (:name a))) args))}))

(defn tools
  "§7G.6 — one Tool (source \"media\") per verb any client supports, in
  verbs.json order; the schema is the first supporting client's args. Execute
  never throws: any error is an isError ToolResult."
  [media]
  (vec
   (keep (fn [entry]
           (let [verb (:verb entry)]
             (when-let [lead (first (filter (fn [c] (client-supports? c verb)) (:clients media)))]
               (tool/tool
                {:name         (:tool entry)
                 :description  (:description entry)
                 :input-schema (input-schema entry (client-args lead verb))
                 :source       "media"
                 ;; Each call is an independent job: always concurrency-safe
                 ;; (§7G.6, §8 Tool scheduling).
                 :concurrency-safe (constantly true)
                 :context?     true
                 :execute
                 (fn exec
                   ([args] (exec args nil))
                   ([args ctx]
                    (try
                      (let [[r client] (route media verb args {:cancel (:cancel ctx)})]
                        (tool-result verb client r))
                      (catch Throwable e
                        (tool/failure (or (ex-message e) (str e)))))))}))))
         verbs)))

(defn model-parts
  "§7G.6 model visibility: the parts of a ToolResult that reach the model. For a
  media tool result only `image` parts; any other result's parts unchanged."
  [result]
  (let [ps (vec (:parts result))]
    (if (= "media" (get-in result [:metadata :source]))
      (filterv (fn [p] (= "image" (:type p))) ps)
      ps)))
