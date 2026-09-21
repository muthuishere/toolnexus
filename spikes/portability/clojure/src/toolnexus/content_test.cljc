;; toolnexus.content — the §1B / §8A suite.
;;
;; The regression pins come first and are the point of the file: the STRING path,
;; a TEXT-ONLY tool result and a TEXT-ONLY parts array must all be byte-identical
;; to what this port produced before content parts existed. Everything else here
;; is new behaviour; those three are the promise that nothing old moved.
;;
;; The shared fixture is read with `koine.fs/read-bytes` and asserted against the
;; COMMITTED golden, never against this port's own re-encoding. `read-file` is
;; `slurp`, which is lossy for non-UTF-8 bytes IDENTICALLY ON BOTH HOSTS — so a
;; test built on it would produce a plausible, self-consistent, WRONG base64 that
;; agrees with itself and disagrees with the other six ports. That is the one
;; failure mode a Clojure-side test of this change can have.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.content-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.env :as env]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.builtin :as builtin]
            [toolnexus.client :as client]
            [toolnexus.content :as content]
            [toolnexus.mcp :as mcp]
            [toolnexus.serve :as serve]
            [toolnexus.agents.compaction :as compaction]
            [toolnexus.tool :as tool]
            [toolnexus.translate :as translate]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(defn- examples-dir [] (env/get-env "TN_EXAMPLES"))

(defn- fixture-path [] (str (examples-dir) "/media/fixture.png"))

(defn- golden-base64
  "The COMMITTED golden. Trimmed because a text golden ends in a newline and the
  base64 does not."
  []
  (str/trim (str (fs/read-file (str (examples-dir) "/media/fixture.png.base64")))))

(def ^:private tiny-png
  "A 1-byte payload standing in wherever the real fixture's bytes are irrelevant."
  "AQ==")

(defn- img [] {:type "image" :mimeType "image/png" :data tiny-png})

;; ---------------------------------------------------------------------------
;; the model
;; ---------------------------------------------------------------------------

(deftest a-part-with-both-data-and-url-is-rejected
  (let [p (content/validate-part {:type "image" :mimeType "image/png"
                                  :data tiny-png :url "https://x/y.png"})]
    (is (content/error-part? p))
    (is (= "source-conflict" (:code p)))
    (is (str/includes? (:error p) "both data and url")))
  (testing "neither is equally a construction error"
    (let [p (content/validate-part {:type "image" :mimeType "image/png"})]
      (is (content/error-part? p))
      (is (= "source-missing" (:code p))))))

(deftest base64-matches-the-committed-golden
  (is (some? (examples-dir)) "TN_EXAMPLES must point at the repo's shared examples/ directory")
  (when (examples-dir)
    (let [part (content/from-file (fixture-path))]
      (is (not (content/error-part? part)))
      (is (= "image" (:type part)))
      (is (= "image/png" (:mimeType part)))
      (is (= (golden-base64) (:data part))
          "the port's base64 must equal the committed golden, not its own re-encoding")
      (is (= 82 (content/part-bytes part))))))

(deftest a-path-is-read-at-the-edge-and-never-stored
  (when (examples-dir)
    (let [part (content/from-file (fixture-path))]
      (is (nil? (:path part)) "a part NEVER carries a filesystem path")
      (is (nil? (:url part)))
      (is (string? (:data part)))
      ;; the whole part, re-read from JSON, still replays with no file present
      (let [round (json/read-str (json/write-str part))]
        (is (= (:data part) (:data round)))))))

(deftest a-data-url-is-normalised-at-construction
  (let [p (content/from-data-url (str "data:image/png;base64," tiny-png))]
    (is (= {:type "image" :mimeType "image/png" :data tiny-png} p))
    (is (nil? (:url p)) "a data: URL is parsed, never stored as a url"))
  (testing "attach dispatches on the shape of what it was given"
    (is (= "image" (:type (content/attach (str "data:image/png;base64," tiny-png)))))
    (is (= {:type "image" :mimeType "image/png" :url "https://example.com/a/b.png"}
           (content/attach "https://example.com/a/b.png")))))

(deftest an-unknown-extension-is-refused-by-name
  (let [p (content/from-file "/tmp/notes.xyz")]
    (is (content/error-part? p))
    (is (= "unknown-extension" (:code p)))
    (is (str/includes? (:error p) "xyz") "the error names the extension"))
  (testing "an explicit mime type is the documented escape hatch, and mime is never sniffed"
    (is (= "file" (:type (content/from-bytes (str "x") "application/x-thing"))))))

(deftest every-host-neutral-byte-source-produces-the-golden
  ;; SPEC §1B: "a port accepts the file and byte objects its users already hold".
  ;; This port's honest list is a path, a native byte array, and any sequence of
  ;; byte values — every one of them must land on the SAME committed golden, or
  ;; "accept broadly, store narrowly" is only half true.
  ;;
  ;; The fixture is read with `read-bytes`, NEVER `read-file`: slurp is lossy for
  ;; non-UTF-8 bytes identically on both hosts, so a test built on it would agree
  ;; with itself and disagree with the other six ports.
  (when (examples-dir)
    (let [raw    (fs/read-bytes (fixture-path))
          golden (golden-base64)]
      (is (= golden (codec/encode raw))
          "sanity: koine's own encoding of the fixture IS the committed golden")
      (testing "a native byte array — JVM byte[] / Go []byte, what read-bytes returns"
        (let [p (content/from-bytes raw "image/png")]
          (is (not (content/error-part? p)))
          (is (= "image" (:type p)))
          (is (= golden (:data p)))))
      (testing "a seq over that same array"
        (is (= golden (:data (content/from-bytes (seq raw) "image/png")))))
      (testing "a vector of byte values, however the caller's numbers are signed"
        (let [signed   (vec (map (fn [b] (let [v (bit-and (long b) 255)]
                                           (if (> v 127) (- v 256) v)))
                                 (seq raw)))
              unsigned (vec (map (fn [b] (bit-and (long b) 255)) (seq raw)))]
          (is (= golden (:data (content/from-bytes signed "image/png"))))
          (is (= golden (:data (content/from-bytes unsigned "image/png")))
              "signed and unsigned spellings of the same bytes must not diverge")))
      (testing "a lazy seq — `codec/encode` refuses this shape on BOTH hosts"
        (is (= golden (:data (content/from-bytes (map identity (seq raw)) "image/png")))))
      (testing "a list"
        (is (= golden (:data (content/from-bytes (apply list (seq raw)) "image/png")))))
      (testing "the path and the data: URL spellings agree with the bytes"
        (is (= golden (:data (content/from-file (fixture-path)))))
        (is (= golden (:data (content/attach raw {:mimeType "image/png"}))))
        (is (= golden (:data (content/from-data-url
                              (str "data:image/png;base64," golden))))))
      (testing "and none of them stores the source"
        (doseq [p [(content/from-bytes raw "image/png")
                   (content/from-bytes (vec (seq raw)) "image/png")
                   (content/from-file (fixture-path))]]
          (is (nil? (:path p)))
          (is (nil? (:url p))))))))

(deftest a-string-source-is-its-utf8-bytes
  ;; The one string that is NOT a path: `from-bytes` never guesses.
  (is (= (codec/encode "hi") (:data (content/from-bytes "hi" "text/plain"))))
  (is (= "file" (:type (content/from-bytes "hi" "text/plain")))))

(deftest an-unsupported-byte-source-is-refused-by-name
  ;; The honest half of "accept broadly". A host file/stream object cannot be
  ;; named in a two-host .cljc, so it is refused with an error part that says so
  ;; and says what to pass instead — never a cast error, and never a throw.
  ;; `{:a 1}` stands in for any such object: it is a source shape this port
  ;; cannot read, on both hosts, without naming a host type to construct one.
  (doseq [bad [{:a 1} nil #{1 2} 42]]
    (let [p (content/from-bytes bad "image/png")]
      (is (content/error-part? p) (str "refused as data: " (pr-str bad)))
      (is (= "unsupported-source" (:code p)))
      (is (str/includes? (:error p) "java.io.File")
          "the message names the JVM types a caller is most likely holding")
      (is (str/includes? (:error p) "koine.fs/read-bytes")
          "and names the way out")))
  (testing "the SOURCE is reported before the missing mime — a mime would not fix it"
    (let [p (content/from-bytes {:a 1} "")]
      (is (= "unsupported-source" (:code p)))))
  (testing "a valid source with no mime still reports the mime"
    (let [p (content/from-bytes [1 2 3] "")]
      (is (= "unknown-extension" (:code p)))))
  (testing "attach refuses it the same way rather than throwing"
    (let [p (content/attach {:a 1} {:mimeType "image/png"})]
      (is (content/error-part? p))
      (is (= "unsupported-source" (:code p))))))

(deftest an-oversized-part-is-rejected-at-the-edge
  ;; 1 MB limit, ~1.5 MB of decoded bytes: the check is on DECODED bytes, never
  ;; the base64 string, which is 33% larger and would be a different limit.
  (let [big  (apply str (repeat 2097152 "A"))         ; 2 MiB of base64
        part {:type "image" :mimeType "image/png" :data big}
        p    (content/validate-part part 1048576)]
    (is (content/error-part? p))
    (is (= "too-large" (:code p)))
    (is (str/includes? (:error p) "1048576") "the error names the limit")
    (is (str/includes? (:error p) (str (content/base64-bytes big))) "and the actual size")))

(deftest the-media-table-is-fixed-and-shared
  (is (= "image/png"       (:mimeType (content/media-type-for "a/b/C.PNG"))))
  (is (= "image/jpeg"      (:mimeType (content/media-type-for "x.jpeg"))))
  (is (= "application/pdf" (:mimeType (content/media-type-for "x.pdf"))))
  (is (= "file"            (:type (content/media-type-for "x.pdf"))))
  (is (= "audio/mpeg"      (:mimeType (content/media-type-for "x.mp3"))))
  (is (nil? (content/media-type-for "x.txt")))
  (is (nil? (content/media-type-for "no-extension"))))

;; ---------------------------------------------------------------------------
;; logging, events, token charge
;; ---------------------------------------------------------------------------

(deftest a-part-is-described-without-its-bytes
  (let [part {:type "image" :mimeType "image/png" :data (apply str (repeat 2796204 "A"))}
        d    (content/describe-part part)]
    (is (= #{:type :mimeType :bytes} (set (keys d))))
    (is (= "image/png" (:mimeType d)))
    (is (= 2097153 (:bytes d)))
    (is (not (str/includes? (json/write-str d) "AAAA"))
        "a part's `data` NEVER reaches an event payload or a log line")))

(deftest the-estimate-charges-for-a-part-by-its-bytes
  (let [two-mb {:type "image" :mimeType "image/png"
                :data (apply str (repeat 2796204 "A"))}
        plain  [{:role "user" :content "hi"}]
        withp  [{:role "user" :content [(content/text-part "hi") two-mb]}]]
    (is (= 8 (compaction/estimate-tokens plain)) "a text-only transcript is unchanged")
    (is (> (compaction/estimate-tokens withp) 2700)
        "a 2 MB image is charged by BYTE LENGTH, so the compactor can evict it")
    (testing "and the base64 never reaches the estimator's JSON twice"
      (is (< (compaction/estimate-tokens withp) 10000)))))

(deftest a-part-is-not-free-to-the-compactor
  ;; the estimate is BYTE-derived; scoring by mimeType would put a 2 MB image at
  ;; ~3 tokens and make it uncompactable, which is the bug this pins.
  (let [two-mb {:type "image" :mimeType "image/png"
                :data (apply str (repeat 2796204 "A"))}
        tiny   (img)]
    (is (> (content/part-bytes two-mb) 2000000))
    (is (> (quot (content/part-bytes two-mb) 750) 2000))
    (is (< (content/part-bytes tiny) 8))))

;; ---------------------------------------------------------------------------
;; §8A emission — allowlist and provenance
;; ---------------------------------------------------------------------------

(deftest every-emitted-block-is-on-the-styles-allowlist
  (doseq [[style parts] [["openai"    [(img)
                                       {:type "file" :mimeType "application/pdf" :data tiny-png}
                                       {:type "audio" :mimeType "audio/mpeg" :data tiny-png}]]
                         ["anthropic" [(img)
                                       {:type "file" :mimeType "application/pdf" :data tiny-png}]]]]
    (let [r (content/encode-parts parts {:style style :provenance "attached"})]
      (is (nil? (:error r)))
      (doseq [b (:blocks r)]
        (is (contains? (get content/allowlist style) (:type b))
            (str style " emitted an off-allowlist block: " (:type b)))))))

(deftest the-block-shapes-are-the-measured-ones
  (is (= {:type "image_url" :image_url {:url (str "data:image/png;base64," tiny-png)}}
         (content/encode-part (img) "openai")))
  (is (= {:type "image" :source {:type "base64" :media_type "image/png" :data tiny-png}}
         (content/encode-part (img) "anthropic")))
  (testing "file_data REQUIRES the data: prefix — a bare base64 string is a 400"
    (is (str/starts-with?
         (get-in (content/encode-part {:type "file" :mimeType "application/pdf"
                                       :data tiny-png :name "r.pdf"} "openai")
                 [:file :file_data])
         "data:application/pdf;base64,")))
  (testing "the named refusals"
    (is (nil? (content/encode-part {:type "audio" :mimeType "audio/mpeg" :data tiny-png} "anthropic")))
    (is (nil? (content/encode-part {:type "file" :mimeType "application/pdf"
                                    :url "https://x/y.pdf"} "openai")))))

(deftest an-attached-unsupported-part-errors-before-any-http
  (let [r (content/encode-parts [{:type "audio" :mimeType "audio/mpeg" :data tiny-png}]
                                {:style "anthropic" :provenance "attached"})]
    (is (= "unsupported" (:code r)))
    (is (str/includes? (:error r) "anthropic"))
    (is (str/includes? (:error r) "audio"))
    (is (str/includes? (:error r) "audio/mpeg"))))

(deftest a-derived-unsupported-part-degrades-and-warns-once
  (content/reset-unsupported-warnings!)
  (let [part {:type "audio" :mimeType "audio/mpeg" :data tiny-png}
        r    (content/encode-parts [part] {:style "anthropic" :provenance "derived"})]
    (is (nil? (:error r)) "a server volunteering audio must not fail the caller's run")
    (is (= [{:type "text" :text "[unsupported audio part (audio/mpeg, 1 bytes)]"}] (:blocks r)))
    (testing "never dropped silently — the placeholder is the record"
      (is (str/includes? (:text (first (:blocks r))) "audio/mpeg")))))

(deftest the-override-forces-uniform-strictness-and-uniform-leniency
  (let [part {:type "audio" :mimeType "audio/mpeg" :data tiny-png}]
    (is (= "unsupported"
           (:code (content/encode-parts [part] {:style "anthropic" :provenance "derived"
                                                :on-unsupported-part "error"}))))
    (is (nil? (:error (content/encode-parts [part] {:style "anthropic" :provenance "attached"
                                                    :on-unsupported-part "text"}))))))

;; ---------------------------------------------------------------------------
;; ToolResult.parts
;; ---------------------------------------------------------------------------

(deftest a-text-only-tool-result-is-byte-identical
  (is (= {:output "hi" :isError false} (tool/with-parts (tool/success "hi") nil)))
  (is (= {:output "hi" :isError false} (tool/with-parts (tool/success "hi") [])))
  (is (= (json/write-str (tool/success "hi"))
         (json/write-str (tool/with-parts (tool/success "hi") [])))))

(deftest a-tool-returning-an-image-also-returns-describing-text
  (let [r (tool/with-parts (tool/success "screenshot, 1280x720 png") [(img)])]
    (is (= "screenshot, 1280x720 png" (:output r)) "output stays required and stays the text")
    (is (= [(img)] (:parts r)))))

(deftest parts-do-not-collide-with-suspension
  (let [req (client/make-request "approval" "may i")
        r   (assoc (client/suspend req) :parts [(img)])]
    (is (= req (client/pending-of r)) "§10 reads metadata.pending, which parts never touches")
    (is (= [(img)] (:parts r)))))

;; ---------------------------------------------------------------------------
;; §0.4 MCP result mapping
;; ---------------------------------------------------------------------------

(deftest a-text-only-mcp-result-is-unchanged
  (let [r (mcp/shape-result {:content [{:type "text" :text "a"} {:type "text" :text "b"}]})]
    (is (= {:output "a\nb" :isError false} r) "no :parts key at all")))

(deftest a-screenshot-tools-image-survives
  (let [r (mcp/shape-result {:content [{:type "text" :text "here"}
                                       {:type "image" :data tiny-png :mimeType "image/png"}]})]
    (is (= "here" (:output r)))
    (is (= [{:type "image" :mimeType "image/png" :data tiny-png}] (:parts r)))))

(deftest a-resource-link-becomes-a-file-part
  (let [r (mcp/shape-result {:content [{:type "resource_link" :uri "https://x/y.pdf"
                                        :name "y.pdf" :mimeType "application/pdf"}]})]
    (is (= [{:type "file" :mimeType "application/pdf" :url "https://x/y.pdf" :name "y.pdf"}]
           (:parts r)))))

(deftest an-embedded-resource-splits-by-blob-or-text
  (testing "a blob is a file part"
    (let [r (mcp/shape-result {:content [{:type "resource"
                                          :resource {:uri "file://a.pdf" :mimeType "application/pdf"
                                                     :blob tiny-png}}]})]
      (is (= [{:type "file" :mimeType "application/pdf" :data tiny-png :name "file://a.pdf"}]
             (:parts r)))))
  (testing "text is appended to output, not made a part"
    (let [r (mcp/shape-result {:content [{:type "text" :text "head"}
                                         {:type "resource" :resource {:uri "file://a.txt" :text "body"}}]})]
      (is (= "head\nbody" (:output r)))
      (is (nil? (:parts r))))))

(deftest an-image-only-result-is-not-an-empty-string
  (let [r (mcp/shape-result {:content [{:type "image" :data tiny-png :mimeType "image/png"}]})]
    (is (= "image (image/png, 1 bytes)" (:output r)))
    (is (= 1 (count (:parts r))))))

(deftest structured-content-does-not-swallow-an-image
  (let [r (mcp/shape-result {:structuredContent {:ok true}
                             :content [{:type "image" :data tiny-png :mimeType "image/png"}]})]
    (is (= (json/write-str {:ok true}) (:output r)) "the structured branch is unchanged")
    (is (= 1 (count (:parts r))) "and the short-circuit no longer drops the image")))

(deftest an-error-result-keeps-its-image
  (let [r (mcp/shape-result {:isError true
                             :content [{:type "text" :text "boom"}
                                       {:type "image" :data tiny-png :mimeType "image/png"}]})]
    (is (true? (:isError r)))
    (is (= "boom" (:output r)))
    (is (= 1 (count (:parts r))))))

;; ---------------------------------------------------------------------------
;; §4A read
;; ---------------------------------------------------------------------------

(defn- read-tool []
  (get (:tools (builtin/builtin-toolkit)) "read"))

(deftest reading-a-png-yields-an-image-part
  (when (examples-dir)
    (let [r ((:execute (read-tool)) {:path (fixture-path)})]
      (is (false? (:isError r)))
      (is (= (str (fixture-path) " (image/png, 82 bytes)") (:output r))
          "§1B pins this string byte-identically across the seven ports")
      (is (= 1 (count (:parts r))))
      (is (= "image" (:type (first (:parts r)))))
      (is (= (golden-base64) (:data (first (:parts r))))))))

(deftest reading-a-text-file-is-unchanged
  (let [p (str (fs/temp-dir!) "/tn-content-read.md")]
    (fs/write-file p "one\ntwo\nthree\nfour")
    (let [all ((:execute (read-tool)) {:path p})
          win ((:execute (read-tool)) {:path p :offset 2 :limit 2})]
      (is (= {:output "one\ntwo\nthree\nfour" :isError false} all) "no :parts key")
      (is (= {:output "two\nthree" :isError false} win)))
    (fs/delete! p)))

(deftest an-unrecognised-binary-yields-an-error-result
  (let [p (str (fs/temp-dir!) "/tn-content-read.bin")]
    ;; 0xFF 0xFE is not a legal UTF-8 lead byte pair
    ;; 0xFF 0xFE 0x00 0x41 — the bytes come from base64 rather than `byte-array`
    ;; so the test builds host bytes the same way on both hosts.
    (fs/write-bytes p (codec/decode-bytes "//4AQQ=="))
    (let [r ((:execute (read-tool)) {:path p})]
      (is (true? (:isError r)))
      (is (str/includes? (:output r) p) "the error names the file"))
    (fs/delete! p)))

;; ---------------------------------------------------------------------------
;; §11 translate
;; ---------------------------------------------------------------------------

(deftest content-supplied-as-text-parts-is-concatenated
  (let [{:keys [messages]} (translate/openai-messages-to-anthropic
                            [{:role "user" :content [{:type "text" :text "a"}
                                                     {:type "text" :text "b"}]}])]
    (is (= [{:role "user" :content "ab"}] messages) "the text-only array is unchanged")))

(deftest an-image-part-in-content-survives-translation
  (testing "a ContentPart written literally"
    (let [{:keys [messages]} (translate/openai-messages-to-anthropic
                              [{:role "user" :content [{:type "text" :text "what is this?"}
                                                       (img)]}])]
      (is (= [{:role "user"
               :content [{:type "text" :text "what is this?"}
                         {:type "image" :source {:type "base64" :media_type "image/png"
                                                 :data tiny-png}}]}]
             messages))))
  (testing "and the OpenAI-native block the same part encodes to"
    (let [{:keys [messages]} (translate/openai-messages-to-anthropic
                              [{:role "user"
                                :content [{:type "text" :text "hi"}
                                          {:type "image_url"
                                           :image_url {:url (str "data:image/png;base64," tiny-png)}}]}])]
      (is (= "image" (:type (second (:content (first messages)))))))))

;; ---------------------------------------------------------------------------
;; §7C serve
;; ---------------------------------------------------------------------------

(deftest a-tools-image-part-becomes-an-mcp-image-block
  (is (= {:type "image" :data tiny-png :mimeType "image/png"}
         (serve/part->content-block (img))))
  (is (= {:type "audio" :data tiny-png :mimeType "audio/mpeg"}
         (serve/part->content-block {:type "audio" :mimeType "audio/mpeg" :data tiny-png})))
  (is (= {:type "resource" :resource {:uri "r.pdf" :mimeType "application/pdf" :blob tiny-png}}
         (serve/part->content-block {:type "file" :mimeType "application/pdf"
                                     :data tiny-png :name "r.pdf"})))
  (is (= "resource_link"
         (:type (serve/part->content-block {:type "file" :mimeType "application/pdf"
                                            :url "https://x/y.pdf"})))))

;; ---------------------------------------------------------------------------
;; §8A through the loop — the wire build, end to end
;; ---------------------------------------------------------------------------

(defn- with-llm
  "A scripted LLM that records every request body. One tool-calling turn, then a
  terminal text turn."
  [style script f]
  (let [n        (atom 0)
        requests (atom [])
        srv      (server/serve
                  (fn [req]
                    (swap! requests conj (json/read-str (str (:body req))))
                    {:status 200
                     :headers {"content-type" "application/json"}
                     :body (json/write-str (script (swap! n inc)))})
                  {:port 0})]
    (try
      (f {:base (str "http://127.0.0.1:" (server/port srv)) :requests requests})
      (finally (server/stop! srv)))))

(defn- openai-script [image-tools]
  (fn [n]
    (if (= 1 n)
      {:choices [{:message {:role "assistant" :content nil
                            :tool_calls (mapv (fn [t] {:id (str "c" t) :type "function"
                                                       :function {:name t :arguments "{}"}})
                                              image-tools)}}]}
      {:choices [{:message {:role "assistant" :content "done"}}]})))

(defn- anthropic-script [image-tools]
  (fn [n]
    (if (= 1 n)
      {:content (mapv (fn [t] {:type "tool_use" :id (str "c" t) :name t :input {}}) image-tools)}
      {:content [{:type "text" :text "done"}]})))

(def ^:private shot-toolkit
  (tool/toolkit
   [(tool/tool {:name "shot1" :description "a"
                :execute (fn [_] (tool/with-parts (tool/success "screenshot 1") [(img)]))})
    (tool/tool {:name "shot2" :description "b"
                :execute (fn [_] (tool/with-parts (tool/success "screenshot 2") [(img)]))})
    (tool/tool {:name "noisy" :description "c"
                :execute (fn [_] (tool/with-parts (tool/success "a clip")
                                                  [{:type "audio" :mimeType "audio/mpeg"
                                                    :data tiny-png}]))})]))

(deftest the-string-path-is-unchanged
  (is (= {:role "user" :content "hello"} (client/user-message "hello"))
      "a string prompt produces exactly the pre-0.17 message")
  (with-llm "openai" (fn [_] {:choices [{:message {:role "assistant" :content "done"}}]})
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "openai" :model "m" :api-key "k"})
            r (client/run c "hello" {:toolkit (tool/toolkit [])})]
        (is (= "done" (:text r)))
        (is (= [{:role "user" :content "hello"}]
               (remove #(= "system" (:role %)) (:messages (first (deref requests))))))))))

(deftest ordering-is-preserved
  (with-llm "openai" (fn [_] {:choices [{:message {:role "assistant" :content "ok"}}]})
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "openai" :model "m" :api-key "k"})
            r (client/run c [(content/text-part "before") (img) (content/text-part "after")]
                          {:toolkit (tool/toolkit [])})
            sent (->> (deref requests) first :messages
                      (filter #(= "user" (:role %))) first :content)]
        (is (= "ok" (:text r)))
        (is (= 3 (count sent)))
        (is (= ["text" "image_url" "text"] (mapv :type sent)))
        (is (= "before" (:text (first sent))))
        (is (= "after" (:text (nth sent 2))))))))

(deftest anthropic-receives-the-image-inside-the-tool-result
  (with-llm "anthropic" (anthropic-script ["shot1"])
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "anthropic" :model "m" :api-key "k"})
            r (client/run c "go" {:toolkit shot-toolkit})
            sent (:messages (second (deref requests)))
            users (filter #(= "user" (:role %)) sent)
            tr    (->> users (mapcat :content) (filter #(= "tool_result" (:type %))) first)]
        (is (= "done" (:text r)))
        (is (= "cshot1" (:tool_use_id tr)))
        (is (= ["text" "image"] (mapv :type (:content tr)))
            "the image block is INSIDE tool_result.content, keyed to its tool_use_id")
        (is (= 2 (count users)) "no synthetic user message is emitted")
        (is (nil? (some :parts (mapcat :content users)))
            "the canonical `:parts` key never reaches the wire")))))

(deftest openai-receives-one-synthetic-user-message
  (with-llm "openai" (openai-script ["shot1" "shot2"])
    (fn [{:keys [base requests]}]
      (let [c    (client/create-client {:base-url base :style "openai" :model "m" :api-key "k"})
            r    (client/run c "go" {:toolkit shot-toolkit})
            sent (:messages (second (deref requests)))
            tools (filter #(= "tool" (:role %)) sent)
            users (filter #(= "user" (:role %)) sent)
            synth (last users)]
        (is (= "done" (:text r)))
        (is (= 2 (count tools)))
        (is (= ["screenshot 1" "screenshot 2"] (mapv :content tools))
            "each tool message carries only its output text")
        (is (nil? (some :parts tools)))
        (is (= 2 (count users)) "exactly ONE synthetic user message follows the tool messages")
        (is (= ["text" "image_url" "text" "image_url"] (mapv :type (:content synth))))
        (is (= "Output of tool shot1 (cshot1):" (:text (first (:content synth)))))
        (is (= "Output of tool shot2 (cshot2):" (:text (nth (:content synth) 2)))
            "relocated in tool-call order")
        (testing "and it is emitted immediately after the last tool message"
          (is (= "tool" (:role (nth sent (- (count sent) 2))))))))))

(deftest the-synthetic-message-never-persists
  (with-llm "openai" (openai-script ["shot1"])
    (fn [{:keys [base]}]
      (let [c (client/create-client {:base-url base :style "openai" :model "m" :api-key "k"})
            r (client/run c "go" {:toolkit shot-toolkit})
            users (filter #(= "user" (:role %)) (:messages r))]
        (is (= 1 (count users)) "RunResult.messages holds no OpenAI-shaped residue")
        (is (= "go" (:content (first users))))
        (is (= [(img)] (:parts (first (filter #(= "tool" (:role %)) (:messages r)))))
            "the canonical transcript keeps the PART, not the block")))))

(deftest mcp-derived-audio-degrades-instead-of-failing-the-run
  (content/reset-unsupported-warnings!)
  (with-llm "anthropic" (anthropic-script ["noisy"])
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "anthropic" :model "m" :api-key "k"})
            r (client/run c "go" {:toolkit shot-toolkit})
            tr (->> (deref requests) second :messages (mapcat :content)
                    (filter #(= "tool_result" (:type %))) first)]
        (is (= "done" (:text r)) "a volunteered audio clip must not fail the run")
        (is (str/includes? (json/write-str (:content tr)) "[unsupported audio part (audio/mpeg, 1 bytes)]"))))))

(deftest the-override-makes-a-derived-part-stop-the-run
  (with-llm "anthropic" (anthropic-script ["noisy"])
    (fn [{:keys [base]}]
      (let [c (client/create-client {:base-url base :style "anthropic" :model "m" :api-key "k"
                                     :on-unsupported-part "error"})
            r (client/run c "go" {:toolkit shot-toolkit})]
        (is (= "incomplete" (:status r)))
        (is (= "contentPart" (:limit r)))
        (is (= "unsupported" (get-in r [:error :code])))
        (is (str/includes? (:text r) "audio"))))))

(deftest an-attached-part-the-style-cannot-send-stops-the-run-before-any-http
  (with-llm "anthropic" (anthropic-script [])
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "anthropic" :model "m" :api-key "k"})
            r (client/run c [{:type "audio" :mimeType "audio/mpeg" :data tiny-png}]
                          {:toolkit (tool/toolkit [])})]
        (is (= "incomplete" (:status r)))
        (is (= "contentPart" (:limit r)))
        (is (empty? (deref requests)) "no HTTP request is made")))))

(deftest an-oversized-attached-part-stops-the-run
  (with-llm "openai" (fn [_] {:choices [{:message {:role "assistant" :content "ok"}}]})
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "openai" :model "m" :api-key "k"
                                     :max-part-bytes 4})
            r (client/run c [{:type "image" :mimeType "image/png"
                              :data (apply str (repeat 400 "A"))}]
                          {:toolkit (tool/toolkit [])})]
        (is (= "contentPart" (:limit r)))
        (is (= "too-large" (get-in r [:error :code])))
        (is (empty? (deref requests)))))))


;; §1B pins `:max-part-bytes` enforcement at request ASSEMBLY, not construction,
;; and routes going over through the SAME provenance rule as an unsupported part.
;; Construction-only enforcement was the original design and it leaked: a part that
;; arrives from an MCP server never passes through an edge constructor, so a remote
;; server could hand us any size it liked. The first fix then went too far the other
;; way — treating oversize as a construction failure made a server volunteering a big
;; image kill a run the caller never asked to risk.
(def ^:private fat-toolkit
  (tool/toolkit
   [(tool/tool {:name "shot1" :description "returns an image over any sane limit"
                :execute (fn [_]
                           (tool/with-parts (tool/success "screenshot 1")
                                            [{:type "image" :mimeType "image/png"
                                              :data (apply str (repeat 400 "A"))}]))})]))

(deftest an-oversized-derived-part-degrades-instead-of-failing-the-run
  (content/reset-unsupported-warnings!)
  (with-llm "anthropic" (anthropic-script ["shot1"])
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :style "anthropic" :model "m" :api-key "k"
                                     :max-part-bytes 4})
            r (client/run c "go" {:toolkit fat-toolkit})
            tr (->> (deref requests) second :messages (mapcat :content)
                    (filter #(= "tool_result" (:type %))) first)]
        (is (= "done" (:text r))
            "a tool volunteering an oversized image must not fail the caller's run")
        (is (str/includes? (json/write-str (:content tr)) "[unsupported image part ")
            "never dropped silently — the placeholder is the record")))))

(deftest the-override-makes-an-oversized-derived-part-stop-the-run
  (content/reset-unsupported-warnings!)
  (with-llm "anthropic" (anthropic-script ["shot1"])
    (fn [{:keys [base]}]
      (let [c (client/create-client {:base-url base :style "anthropic" :model "m" :api-key "k"
                                     :max-part-bytes 4 :on-unsupported-part "error"})
            r (client/run c "go" {:toolkit fat-toolkit})]
        (is (= "incomplete" (:status r)))
        (is (= "too-large" (get-in r [:error :code]))
            "an oversize failure reports too-large, not unsupported")))))
