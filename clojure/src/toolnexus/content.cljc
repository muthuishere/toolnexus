;; SPEC §1B + §8A — multimodal content parts.
;;
;; ONE `ContentPart` union — `text | image | file | audio` — is how non-text data
;; enters the loop (a prompt), comes back from a tool (`ToolResult.parts`) and
;; survives MCP. A part is BYTES-OR-URL, NEVER A PATH: a path does not survive a
;; persisted and replayed transcript, nor the MCP / A2A process boundary, so the
;; edge constructors normalise a path / native bytes / a `data:` URL AT
;; CONSTRUCTION and the path never enters the part.
;;
;; Wire casing, deliberately: `:mimeType`, not `:mime-type`. `koine.json/write-str`
;; emits a keyword's name VERBATIM, so the keyword IS the wire key — the same rule
;; that puts `:isError` in `toolnexus.tool` and `:input_schema` in
;; `toolnexus.adapter`. Kebab-casing here would silently change the JSON and be
;; exactly the cross-port drift the ports exist to prevent.
;;
;; EMISSION LIVES HERE, not in `toolnexus.adapter` — that namespace is tool-SCHEMA
;; only and says so ("nothing here touches a network"). Each `(style, part type)`
;; pair has either a defined block shape or an EXPLICIT refusal, and every encoded
;; block is checked against a POSITIVE ALLOWLIST before it can reach the wire,
;; because an unknown block type upstream returns HTTP 200 with the content
;; silently discarded — the exact bug this namespace exists to remove.
;;
;; NOTHING HERE THROWS. `toolnexus.tool/execute` and `toolnexus.mcp` both establish
;; that a failure crossing a source boundary is DATA with a stable name, never an
;; exception; a construction failure is an ERROR PART and an emission failure is an
;; `{:error … :code …}` map that the loop turns into a stopped RunResult.
;;
;; One `.cljc`, zero reader conditionals, zero `java.*`, zero Go interop.
(ns toolnexus.content
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.text :as text]))

;; ---------------------------------------------------------------------------
;; the media extension table (§6 `read`)
;; ---------------------------------------------------------------------------

(def media-types
  "SPEC §6 — FIXED, shared with the edge constructors, identical in every port.

  No magic-byte sniffing and no platform mime database: `/etc/mime.types` varies
  per machine, so the same `.webp` would resolve differently on two boxes and
  break the byte-identical fixture this repo is built around."
  {"png"  {:mimeType "image/png"        :type "image"}
   "jpg"  {:mimeType "image/jpeg"       :type "image"}
   "jpeg" {:mimeType "image/jpeg"       :type "image"}
   "gif"  {:mimeType "image/gif"        :type "image"}
   "webp" {:mimeType "image/webp"       :type "image"}
   "pdf"  {:mimeType "application/pdf"  :type "file"}
   "mp3"  {:mimeType "audio/mpeg"       :type "audio"}
   "wav"  {:mimeType "audio/wav"        :type "audio"}})

(defn base-name
  "The last path segment of `p`. Both separators are folded, so a Windows-shaped
  path does not come back whole on a POSIX host."
  [p]
  (let [s (str p)
        i (max (or (str/last-index-of s "/") -1)
               (or (str/last-index-of s "\\") -1))]
    (if (neg? i) s (subs s (inc i)))))

(defn extension-of
  "The lower-cased extension of `p` WITHOUT the dot, or \"\" when there is none.
  Read off the base name so a dot in a parent directory cannot be mistaken for
  one."
  [p]
  (let [b (base-name p)
        i (str/last-index-of b ".")]
    (if (or (nil? i) (zero? i) (= i (dec (count b))))
      ""
      (str/lower-case (subs b (inc i))))))

(defn media-type-for
  "The table entry for a path or URL path, or nil when the extension is not media."
  [p]
  (get media-types (extension-of p)))

(defn part-type-for-mime
  "Part kind for a mime type: `image/*` ⇒ image, `audio/*` ⇒ audio, else file."
  [mime]
  (let [m (str mime)]
    (cond
      (str/starts-with? m "image/") "image"
      (str/starts-with? m "audio/") "audio"
      :else                         "file")))

;; ---------------------------------------------------------------------------
;; sizes — decoded, never the +33% base64 string
;; ---------------------------------------------------------------------------

(defn base64-bytes
  "The DECODED byte length of standard, padded base64 — without decoding it.
  `maxPartBytes` is measured in these bytes, which is the whole point: the base64
  string is 33% larger and a limit against it would be a different limit."
  [b64]
  (let [s (str b64)
        n (count s)]
    (if (zero? n)
      0
      (let [pad (cond (str/ends-with? s "==") 2
                      (str/ends-with? s "=")  1
                      :else                   0)]
        (- (* (quot n 4) 3) pad)))))

(defn part-bytes
  "A part's payload size in decoded bytes. A url-only part carries no bytes here."
  [part]
  (if (= "text" (:type part))
    (text/utf8-length (str (:text part)))
    (if (:data part) (base64-bytes (:data part)) 0)))

;; ---------------------------------------------------------------------------
;; rendering — `data` NEVER appears in a log line, an event or an error message
;; ---------------------------------------------------------------------------

(defn describe-part
  "How a part appears in a log line or a §9 event: `{type, mimeType, bytes}`.
  NEVER `data`. Same rule as never-log-headers — a part's payload is user
  content, and a §9 event is a place it must not leak."
  [part]
  (if (= "text" (:type part))
    {:type "text" :bytes (part-bytes part)}
    {:type (:type part) :mimeType (:mimeType part) :bytes (part-bytes part)}))

(defn summarize-part
  "A one-line, payload-free rendering of a part — for `output` text.

  SPEC §1B pins this string BYTE-IDENTICALLY across all seven ports: it reaches a
  model's context, so a port inventing its own wording is exactly the drift §0
  exists to prevent. `<bytes>` is the DECODED count as a plain integer, and a part
  carrying a `url` instead of `data` renders it as 0."
  [part]
  (if (= "text" (:type part))
    (str (:text part))
    (str (:type part) " (" (:mimeType part) ", " (part-bytes part) " bytes)")))

;; ---------------------------------------------------------------------------
;; the union, and its one invariant
;; ---------------------------------------------------------------------------

(defn error-part
  "A construction failure, AS DATA. Clojure's source boundary does not throw
  (`toolnexus.tool/execute`, `toolnexus.mcp`), and neither does this one — the
  failure travels as a value until the loop can report it, which is the same
  deferred-error shape the Go port's `File(path)` uses."
  [code message]
  {:type "error" :code (str code) :error (str message)})

(defn error-part? [x]
  (and (map? x) (= "error" (:type x))))

(defn content-part?
  "True when `x` looks like a §1B ContentPart — as opposed to a provider-native
  block that happens to be a map."
  [x]
  (and (map? x)
       (let [t (:type x)]
         (cond
           (= "text" t)                            (string? (:text x))
           (contains? #{"image" "file" "audio"} t) (string? (:mimeType x))
           :else                                   false))))

(defn validate-part
  "SPEC §1B — exactly one of `data` / `url`, and (when `max-part-bytes` is set)
  a decoded payload within it. Returns the part, or an ERROR PART naming what is
  wrong. An error part passes straight through so the first failure is the one
  reported."
  ([part] (validate-part part nil))
  ([part max-part-bytes]
   (cond
     (error-part? part) part
     (= "text" (:type part)) part
     (not (content-part? part))
     (error-part "unsupported" (str "not a content part: " (pr-str (:type part))))
     :else
     (let [has-data? (some? (:data part))
           has-url?  (some? (:url part))]
       (cond
         (and has-data? has-url?)
         (error-part "source-conflict"
                     (str "content part \"" (:type part)
                          "\" carries both data and url — supply exactly one"))

         (not (or has-data? has-url?))
         (error-part "source-missing"
                     (str "content part \"" (:type part)
                          "\" carries neither data nor url — supply exactly one"))

         (and max-part-bytes has-data? (> (base64-bytes (:data part)) (long max-part-bytes)))
         (error-part "too-large"
                     (str "content part \"" (:type part) "\" is "
                          (base64-bytes (:data part))
                          " decoded bytes, over the max-part-bytes limit of "
                          max-part-bytes))

         :else part)))))

;; ---------------------------------------------------------------------------
;; edge constructors — the path / the raw bytes never enter the part
;; ---------------------------------------------------------------------------

(defn text-part
  "A `text` part. NOT named `text`: this namespace already requires `koine.text`
  as `text`, and a var shadowing an alias is the kind of one-host surprise the
  port avoids on principle."
  [s]
  {:type "text" :text (str s)})

(defn- finish
  [type mime source {:keys [name max-part-bytes]}]
  (validate-part (cond-> (merge {:type type :mimeType (str mime)} source)
                   (and (= "file" type) name) (assoc :name (str name)))
                 max-part-bytes))

;; ---------------------------------------------------------------------------
;; what counts as "bytes in hand" HERE
;;
;; SPEC §1B tells every port to accept the file and byte objects its users
;; already hold, and lists per port what that means: `File`/`Blob` in js, a
;; `BinaryIO` in python, an `io.Reader` in golang, `java.io.File`/`InputStream`
;; in java, `FileInfo`/`Stream` in csharp, `File.Stream` in elixir. THIS PORT'S
;; LIST IS SHORTER, and the reason is structural rather than an omission:
;;
;;   ONE `.cljc`, no reader conditionals, compiled for TWO hosts — the JVM and
;;   cljgo (Go). Naming `java.io.File` or `java.io.InputStream` here would make
;;   the namespace unloadable on the Go host, which is the one property the
;;   Clojure port exists to have. Host-specific behaviour reaches this file only
;;   through koine, and koine has no stream/reader abstraction at all: its byte
;;   surface is `fs/read-bytes` (whole file) and `codec/encode`. `koine.stream`
;;   is Server-Sent Events, not a byte stream. So there is no host-neutral
;;   handle-shaped source to accept, and inventing one would be a per-host
;;   branch wearing a portable name.
;;
;; What IS host-neutral is accepted, and it is more than a path:
;;
;;   * a filesystem path string      -> read now (`attach` / `from-file`)
;;   * a `data:` URL string          -> parsed now
;;   * an `http(s):` URL string      -> kept as `:url`
;;   * a native byte array           -> JVM `byte[]` AND Go `[]byte`, i.e. exactly
;;                                      what `koine.fs/read-bytes` returns on each
;;                                      host; `koine.codec/encode` takes both
;;   * ANY sequence of byte values   -> a vector, list, lazy seq, `(seq some-bytes)`,
;;                                      `(map …)` output, signed (-128..127) or
;;                                      unsigned (0..255) — the shapes ordinary
;;                                      Clojure code actually produces, which
;;                                      `codec/encode` itself REFUSES on both hosts
;;   * a string payload              -> its UTF-8 bytes, with an explicit mime
;;
;; Anything else is refused BY NAME rather than by cast error: passing a
;; `java.io.File` on the JVM used to reach `codec/encode` and throw
;; "class java.io.File cannot be cast to class [B", which is both a throw (this
;; namespace never throws) and a message that does not tell you what to do.
;; ---------------------------------------------------------------------------

(defn- byte-values
  "A seq of byte values as UNSIGNED 0-255. `bit-and 255` folds the JVM's signed
  bytes and already-unsigned values together, so `[137 80]` and `[-119 80]`
  encode identically — a caller who got their numbers out of a byte array and one
  who typed them out must not diverge."
  [xs]
  (map (fn [b] (bit-and (long b) 255)) xs))

(defn- unsupported-source
  "The refusal for a source shape this port cannot read — AS DATA, and naming the
  way out. `pr-str` of the type is used rather than any host class reference, so
  the message can name `java.io.File` without this file ever mentioning it in
  code."
  [x]
  (let [label (if (nil? x)
                "nil"
                (try (let [t (pr-str (type x))] (if (str/blank? t) "an unknown type" t))
                     (catch Throwable _ "an unknown type")))]
    (error-part
     "unsupported-source"
     (str "unsupported byte source: " label
          " — the Clojure port is one .cljc compiled for two hosts (JVM and cljgo),"
          " so a host-only object (java.io.File, java.io.InputStream, a Go *os.File)"
          " cannot be read here. Pass a filesystem path string, a byte array from"
          " koine.fs/read-bytes, or a sequence of byte values."))))

(defn- encode-source
  "`{:data <base64>}` for a supported byte source, or an ERROR PART. Never throws."
  [x]
  (cond
    (nil? x)
    (unsupported-source x)

    ;; A string is its UTF-8 bytes. `codec/encode` documents that route, and it
    ;; is how a text payload with an explicit mime (`application/x-thing`) is
    ;; attached without the caller encoding by hand.
    (string? x)
    {:data (codec/encode x)}

    ;; A vector / list / lazy seq / `(seq byte-array)` of byte values. This is the
    ;; shape ordinary Clojure code produces and the one `codec/encode` REFUSES on
    ;; BOTH hosts (JVM: "PersistentVector cannot be cast to [B"; cljgo:
    ;; "expected a byte-array or string"), so it is handled here through the pure
    ;; `b64-encode-vals`, which is host-identical arithmetic.
    (sequential? x)
    (try {:data (codec/b64-encode-vals (byte-values x))}
         (catch Throwable _ (unsupported-source x)))

    ;; A native byte array — JVM `byte[]`, Go `[]byte`. `sequential?` is false for
    ;; both, and `codec/encode` handles both. Anything else that lands here is
    ;; refused with the named message rather than a cast error.
    :else
    (try {:data (codec/encode x)}
         (catch Throwable _ (unsupported-source x)))))

(defn from-bytes
  "A part from bytes already in hand. `mime` is REQUIRED — bytes carry no
  extension to read — and the base64 happens here, so the caller never pays the
  encoding tax in their own program.

  ACCEPTS, on both hosts: a native byte array (JVM `byte[]` / Go `[]byte`, i.e.
  what `koine.fs/read-bytes` returns), ANY sequence of byte values (vector, list,
  lazy seq, `(seq some-byte-array)`; signed or unsigned), and a string, which is
  taken as its UTF-8 bytes.

  DOES NOT ACCEPT a host file/stream object — `java.io.File`, `java.io.InputStream`,
  a Go `*os.File`. Not an oversight: this port is one `.cljc` for two hosts, and
  naming a JVM type here would make the namespace unloadable on cljgo, while koine
  offers no host-neutral stream abstraction to stand in for one. Such a source
  gets an `unsupported-source` ERROR PART naming the type and telling the caller
  to pass a path or bytes — never a cast error, and never a throw.

  `koine.codec/encode` is the encoder because its docstring names this exact use:
  standard base64 with padding (RFC 4648 §4), NOT the URL-safe alphabet, which is
  what MCP image/blob blocks and every provider want."
  ([bs mime] (from-bytes bs mime {}))
  ([bs mime opts]
   ;; The SOURCE is checked before the mime: a caller holding a `java.io.File`
   ;; has a problem that supplying a mime type would not fix, and telling them
   ;; about the mime first sends them one step further from the answer.
   (let [source (encode-source bs)]
     (cond
       (error-part? source) source

       (str/blank? (str mime))
       (error-part "unknown-extension"
                   "from-bytes requires an explicit mimeType — bytes carry no extension to read")

       :else (finish (part-type-for-mime mime) mime source opts)))))

(defn from-file
  "Read `path` NOW and base64 it NOW, so the part never carries the path (§1B).
  The mime type comes from the FIXED extension table (or `:mimeType` in `opts`);
  an unknown extension with no explicit mime is an error part naming it.

  `koine.fs/read-bytes`, never `read-file`: the text route is `slurp`, which is
  lossy for non-UTF-8 bytes IDENTICALLY ON BOTH HOSTS — so it would produce a
  plausible, self-consistent, WRONG base64 that agrees with itself and disagrees
  with the other six ports."
  ([path] (from-file path {}))
  ([path opts]
   (let [p    (str path)
         mime (or (:mimeType opts) (:mimeType (media-type-for p)))]
     (cond
       (nil? mime)
       (error-part "unknown-extension"
                   (str "no mime type for extension \""
                        (let [e (extension-of p)] (if (= "" e) "(none)" e))
                        "\" — pass an explicit :mimeType (mime is never sniffed)"))

       (not (fs/exists? p))
       (error-part "source-missing" (str "no such file: " p))

       :else
       (finish (part-type-for-mime mime) mime
               {:data (codec/encode (fs/read-bytes p))}
               (merge {:name (base-name p)} opts))))))

(defn image-file
  "`from-file`, pinned to an `image` part — the spelling SPEC §1B's Clojure
  example uses. A path whose table entry is not an image is an error part rather
  than a quietly different kind."
  ([path] (image-file path {}))
  ([path opts]
   (let [p (from-file path opts)]
     (cond
       (error-part? p)          p
       (= "image" (:type p))    p
       :else (error-part "unknown-extension"
                         (str "image-file: " (base-name path) " is a \"" (:type p)
                              "\" part (" (:mimeType p) "), not an image"))))))

(defn from-data-url
  "Parse `data:<mime>;base64,<b64>` into `{:mimeType … :data …}`, so two spellings
  of the same bytes cannot diverge downstream. NEVER stored as a `:url`."
  ([u] (from-data-url u {}))
  ([u opts]
   (let [s (str u)
         m (re-matches #"(?s)data:([^;,]*)(;base64)?,(.*)" s)]
     (if-not m
       (error-part "source-missing" "malformed data: URL")
       (let [mime (or (:mimeType opts)
                      (let [d (nth m 1)] (if (str/blank? d) "application/octet-stream" d)))
             raw  (nth m 3)
             ;; A data: URL without `;base64` carries the payload as text. It is
             ;; encoded here rather than kept as text so every part reaching the
             ;; wire has ONE payload shape. Percent-escapes are NOT decoded — no
             ;; port decodes them, and inventing that here would be drift.
             b64  (if (nth m 2) raw (codec/encode raw))]
         (finish (part-type-for-mime mime) mime {:data b64} opts))))))

(defn from-url
  "Keep an `http(s):` URL as a `:url` part. The mime comes from the table or from
  `:mimeType`; an unknown extension with neither is an error part naming it."
  ([u] (from-url u {}))
  ([u opts]
   (let [s    (str u)
         ;; the path portion only — a query string must not supply the extension
         p    (first (str/split (str/replace s #"^[a-zA-Z]+://[^/]*" "") #"[?#]"))
         mime (or (:mimeType opts) (:mimeType (media-type-for (str p))))]
     (if (nil? mime)
       (error-part "unknown-extension"
                   (str "no mime type for extension \""
                        (let [e (extension-of (str p))] (if (= "" e) "(none)" e))
                        "\" — pass an explicit :mimeType (mime is never sniffed)"))
       (finish (part-type-for-mime mime) mime {:url s} opts)))))

(defn attach
  "The edge: hand it whatever you actually have and get a path-free part.

  A STRING is dispatched on its shape — a `data:` URL is parsed, an `http(s):`
  URL is kept as a `:url`, anything else is a filesystem path read now. ANYTHING
  ELSE is bytes, and goes to `from-bytes`: a native byte array (JVM `byte[]` /
  Go `[]byte`) or any sequence of byte values. To attach a string as its UTF-8
  BYTES rather than as a path, call `from-bytes` directly — `attach` cannot tell
  those two apart, and guessing is how a payload silently becomes a filename.

  A host file/stream object (`java.io.File`, `InputStream`, a Go `*os.File`) is
  refused by name — see `from-bytes` for why this port's list is shorter than the
  other six."
  ([source] (attach source {}))
  ([source opts]
   (if-not (string? source)
     (from-bytes source (:mimeType opts) opts)
     (cond
       (str/starts-with? source "data:")               (from-data-url source opts)
       (re-find #"(?i)^https?://" source)              (from-url source opts)
       :else                                           (from-file source opts)))))

;; ---------------------------------------------------------------------------
;; §8A emission — the positive allowlist and the unsupported-part rule
;; ---------------------------------------------------------------------------

(def allowlist
  "The ONLY block types that may reach each style's wire. A part encoding to
  anything else never leaves this namespace: map-and-hope is precisely how an
  unknown block reaches a provider that answers 200 and drops the content."
  {"openai"    #{"text" "image_url" "file" "input_audio"}
   "anthropic" #{"text" "image" "document"}})

(def ^:private audio-formats
  "OpenAI's `input_audio.format` is a bare format name, not a mime type."
  {"audio/mpeg" "mp3" "audio/mp3" "mp3" "audio/wav" "wav" "audio/x-wav" "wav"})

(defn- data-url [mime data] (str "data:" mime ";base64," data))

(defn encode-part
  "One part as its provider block, or nil when the style defines NO shape for it.
  The nils are EXPLICIT refusals, never a fall-through: `openai × file+url` (Chat
  Completions has no URL form for a file), `openai × audio+url`, and
  `anthropic × audio` (the provider defines no audio block at all)."
  [part style]
  (let [t    (:type part)
        mime (str (:mimeType part))
        data (:data part)]
    (if (= "text" t)
      {:type "text" :text (str (:text part))}
      (if (= "openai" style)
        (cond
          (= "image" t)
          {:type "image_url"
           :image_url {:url (if data (data-url mime data) (:url part))}}

          ;; `file_data` REQUIRES the `data:<mime>;base64,` prefix — a bare base64
          ;; string is a 400, measured on the live wire.
          (and (= "file" t) data)
          {:type "file" :file {:filename (or (:name part) "file")
                               :file_data (data-url mime data)}}

          (and (= "audio" t) data)
          {:type "input_audio"
           :input_audio {:data data
                         :format (or (get audio-formats mime)
                                     (str/replace mime #"^audio/" ""))}}

          :else nil)
        (cond
          (= "image" t)
          {:type "image" :source (if data
                                   {:type "base64" :media_type mime :data data}
                                   {:type "url" :url (:url part)})}

          (= "file" t)
          {:type "document" :source (if data
                                      {:type "base64" :media_type mime :data data}
                                      {:type "url" :url (:url part)})}

          ;; Anthropic defines no audio block. A named refusal, not an oversight,
          ;; and the case the provenance rule below exists for.
          :else nil)))))

(defn unsupported-placeholder
  "What a degraded (non-failing) unsupported part leaves behind — never silence.
  The third of §1B's three byte-identical user-visible strings."
  [part]
  (str "[unsupported " (:type part) " part (" (:mimeType part) ", "
       (part-bytes part) " bytes)]"))

(def ^:private warned
  "`(style, part type)` pairs already warned about. §8A says WARN ONCE; this is
  the port's existing convention for ignored-and-warned."
  (atom #{}))

(defn reset-unsupported-warnings!
  "Test seam — forget which unsupported pairs have been warned about."
  []
  (reset! warned #{})
  nil)

(defn encode-parts
  "Encode `parts` for one style. Returns `{:blocks [...]}` or `{:error msg :code c}`.

  §8A, by PROVENANCE: a part the caller ATTACHED that the style cannot represent
  is an error before any HTTP call — the caller asked for something specific and
  silently changing it is the betrayal. A part DERIVED from a tool / MCP result
  degrades to a text placeholder and warns once — failing a caller's run because
  a server volunteered an audio clip would be a regression on behaviour that
  succeeds today. `:on-unsupported-part` (\"error\" | \"text\") overrides both
  uniformly. A part is NEVER dropped silently.

  This returns data rather than throwing for the reason at the top of the file:
  the loop turns an `:error` into a stopped RunResult, which is how a failure
  crosses a boundary in this port."
  [parts {:keys [style provenance on-unsupported-part max-part-bytes]}]
  (let [style (str style)
        mode  (or (some-> on-unsupported-part str)
                  (if (= "attached" (str provenance)) "error" "text"))
        allow (get allowlist style #{})]
    (reduce
     (fn [acc raw]
       (if (:error acc)
         acc
         ;; Structural validity and size are DIFFERENT failures and must not share
         ;; a branch. A malformed part was never valid, so it is always fatal. Being
         ;; over `:max-part-bytes` is a policy limit, and §1B routes it through the
         ;; SAME provenance rule as an unsupported part — otherwise an MCP server
         ;; that volunteers a 50 MB image kills a run the caller never asked to risk.
         (let [part      (validate-part raw)
               oversize? (and max-part-bytes
                              (:data part)
                              (not (error-part? part))
                              (> (part-bytes part) (long max-part-bytes)))
               block     (when (and (not (error-part? part)) (not oversize?))
                           (encode-part part style))]
           (cond
             ;; a construction failure is ALWAYS fatal — it is not a provider
             ;; disagreement, it is a part that was never valid.
             (error-part? part)
             {:error (:error part) :code (:code part)}

             (and block (contains? allow (str (:type block))))
             (update acc :blocks conj block)

             (= "error" mode)
             {:error (if oversize?
                       (str "content part \"" (:type part) "\" is " (part-bytes part)
                            " decoded bytes, over the max-part-bytes limit of " max-part-bytes)
                       (str "provider style \"" style "\" defines no block for a \""
                            (:type part) "\" content part"
                            (when (not= "text" (:type part)) (str " (" (:mimeType part) ")"))))
              :code (if oversize? "too-large" "unsupported")}

             :else
             ;; The warn-once latch is keyed by REASON as well as style/type, so a
             ;; size warning and a no-block warning cannot suppress one another.
             (let [k (str style ":" (:type part) ":" (if oversize? "too-large" "unsupported"))]
               (when-not (contains? (deref warned) k)
                 (swap! warned conj k)
                 (println (if oversize?
                            (str "[toolnexus] a " (:type part) " part is " (part-bytes part)
                                 " decoded bytes, over the max-part-bytes limit of " max-part-bytes
                                 " — sending a text placeholder")
                            (str "[toolnexus] provider style \"" style "\" has no block for a \""
                                 (:type part) "\" part — sending a text placeholder"))))
               (update acc :blocks conj {:type "text" :text (unsupported-placeholder part)}))))))
     {:blocks []}
     parts)))

;; ---------------------------------------------------------------------------
;; §11 inbound — a provider-native block read BACK into a ContentPart
;; ---------------------------------------------------------------------------

(defn inbound-part
  "An inbound OpenAI-shaped content block as a §1B ContentPart, or nil when the
  block is neither. Accepts a ContentPart written literally AND the native block
  the same part encodes to, so a caller's own OpenAI messages translate as
  faithfully as ours do."
  [block]
  (cond
    (content-part? block) block
    (not (map? block))    nil

    (and (= "image_url" (:type block)) (get-in block [:image_url :url]))
    (let [u (str (get-in block [:image_url :url]))]
      (if (str/starts-with? u "data:")
        (let [p (from-data-url u)] (when-not (error-part? p) (assoc p :type "image")))
        {:type "image"
         :mimeType (or (:mimeType (media-type-for u)) "image/*")
         :url u}))

    (and (= "file" (:type block)) (string? (get-in block [:file :file_data])))
    (let [p (from-data-url (str (get-in block [:file :file_data])))]
      (when-not (error-part? p)
        (cond-> (assoc p :type "file")
          (get-in block [:file :filename]) (assoc :name (str (get-in block [:file :filename]))))))

    (and (= "input_audio" (:type block)) (string? (get-in block [:input_audio :data])))
    (let [fmt (str (or (get-in block [:input_audio :format]) ""))]
      {:type "audio"
       :mimeType (cond (= "mp3" fmt) "audio/mpeg"
                       (= "wav" fmt) "audio/wav"
                       :else         (str "audio/" (if (= "" fmt) "*" fmt)))
       :data (str (get-in block [:input_audio :data]))})

    :else nil))

;; ---------------------------------------------------------------------------
;; §8A  canonical transcript -> provider wire messages
;; ---------------------------------------------------------------------------
;;
;; The loop's `messages` vector is the CANONICAL TRANSCRIPT: a user turn carrying
;; parts holds ContentParts under `:content`, and a tool turn carrying parts holds
;; them under `:parts`. Neither is a wire shape. This is the one place that turns
;; the canonical transcript into what each style actually accepts, so the loop only
;; ever has to RECORD parts, never encode them.
;;
;; It is also where the RELOCATION RULE lives. Anthropic defines blocks inside
;; `tool_result.content`, so its parts are emitted NATIVELY, keyed to their
;; `tool_use_id`. OpenAI's `tool` message rejects an image outright (a hard 400,
;; "Image URLs are only allowed for messages with role 'user'"), so there every
;; non-text part from every tool result answering ONE assistant turn is relocated,
;; in tool-call order, into ONE synthetic `user` message emitted immediately after
;; the last tool message. That synthetic message is an ADAPTER ARTIFACT: it is
;; built here, on the way out, and never written back to the transcript, the
;; ConversationStore or `translate` output — which is what keeps a mid-conversation
;; provider switch free of OpenAI-shaped residue.

(defn relocation-header
  "The text preceding each relocated part, so the model can attribute it to its
  call. Byte-pinned across the ports."
  [name id]
  (str "Output of tool " name " (" id "):"))

(defn part-array?
  "True when a message's `:content` is a canonical ContentPart vector rather than
  an already-native block array."
  [content]
  (and (sequential? content)
       (seq content)
       (every? (fn [x] (or (content-part? x) (error-part? x))) content)))

(defn- split-parts [parts]
  {:texts  (vec (filter #(= "text" (:type %)) parts))
   :others (vec (remove #(= "text" (:type %)) parts))})

(defn- parts-of [m]
  (let [p (:parts m)]
    (when (and (sequential? p) (seq p)) (vec p))))

(defn- encode-into
  "Fold one `encode-parts` call into an accumulator that short-circuits on the
  first `:error`."
  [acc parts opts f]
  (if (:error acc)
    acc
    (let [r (encode-parts parts opts)]
      (if (:error r) (merge acc (select-keys r [:error :code])) (f acc (:blocks r))))))

(defn- openai-wire [messages opts]
  (let [base (select-keys opts [:on-unsupported-part :max-part-bytes])
        flush
        (fn [acc]
          (if (empty? (:relocated acc))
            acc
            (let [acc' (reduce
                        (fn [a r]
                          (encode-into a (:parts r)
                                       (merge base {:style "openai" :provenance "derived"})
                                       (fn [a blocks]
                                         (update a :blocks into
                                                 (into [{:type "text"
                                                         :text (relocation-header (:name r) (:id r))}]
                                                       blocks)))))
                        (assoc acc :blocks [])
                        (:relocated acc))]
              (if (:error acc')
                acc'
                (-> acc'
                    (update :out conj {:role "user" :content (:blocks acc')})
                    (assoc :relocated [] :blocks []))))))
        acc
        (reduce
         (fn [acc m]
           (if (:error acc)
             acc
             (if (= "tool" (:role m))
               (let [parts (parts-of m)
                     rest' (dissoc m :parts :name)]
                 (if-not parts
                   (update acc :out conj m)
                   (let [{:keys [texts others]} (split-parts parts)
                         acc (encode-into
                              acc texts (merge base {:style "openai" :provenance "derived"})
                              (fn [a blocks]
                                (update a :out conj
                                        (if (seq blocks)
                                          (assoc rest' :content
                                                 (into [{:type "text" :text (str (:content rest'))}]
                                                       blocks))
                                          rest'))))]
                     (if (or (:error acc) (empty? others))
                       acc
                       (update acc :relocated conj {:name (str (:name m))
                                                    :id   (str (:tool_call_id m))
                                                    :parts others})))))
               (let [acc (flush acc)]
                 (cond
                   (:error acc) acc
                   (part-array? (:content m))
                   (encode-into acc (:content m)
                                (merge base {:style "openai" :provenance "attached"})
                                (fn [a blocks] (update a :out conj (assoc m :content blocks))))
                   :else (update acc :out conj m))))))
         {:out [] :relocated [] :blocks []}
         messages)
        acc (flush acc)]
    (if (:error acc) (select-keys acc [:error :code]) {:messages (:out acc)})))

(defn- anthropic-wire [messages opts]
  (let [base (select-keys opts [:on-unsupported-part :max-part-bytes])
        acc
        (reduce
         (fn [acc m]
           (cond
             (:error acc) acc

             (part-array? (:content m))
             (encode-into acc (:content m)
                          (merge base {:style "anthropic" :provenance "attached"})
                          (fn [a blocks] (update a :out conj (assoc m :content blocks))))

             (not (sequential? (:content m)))
             (update acc :out conj m)

             :else
             ;; a `user` turn of tool_result blocks: parts ride INSIDE the block
             ;; they belong to, keyed to their own tool_use_id. Nothing moves.
             (let [acc' (reduce
                         (fn [a block]
                           (let [parts (when (= "tool_result" (:type block)) (parts-of block))]
                             (if-not parts
                               (update a :blocks conj block)
                               (encode-into
                                a parts (merge base {:style "anthropic" :provenance "derived"})
                                (fn [a2 encoded]
                                  (update a2 :blocks conj
                                          (assoc (dissoc block :parts) :content
                                                 (into [{:type "text" :text (str (:content block))}]
                                                       encoded))))))))
                         (assoc acc :blocks [])
                         (:content m))]
               (if (:error acc')
                 acc'
                 (-> acc'
                     (update :out conj (assoc m :content (:blocks acc')))
                     (assoc :blocks []))))))
         {:out [] :blocks []}
         messages)]
    (if (:error acc) (select-keys acc [:error :code]) {:messages (:out acc)})))

(defn build-wire
  "The canonical transcript as `opts`'s style wants it.
  Returns `{:messages [...]}` or `{:error msg :code c}` — never throws, and never
  mutates the transcript it was handed."
  [messages opts]
  (if (= "anthropic" (str (:style opts)))
    (anthropic-wire (vec messages) opts)
    (openai-wire (vec messages) opts)))
