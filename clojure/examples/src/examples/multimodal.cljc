;; 6. MULTIMODAL CONTENT PARTS — LIVE (SPEC §1B / §8A)
;;
;; Two things are proved here, and only one of them is provable from what the
;; model says:
;;
;;   1. THE IMAGE ARRIVED. The proof is the PROMPT-TOKEN DELTA between the
;;      identical request without and with the image — never the answer. A model
;;      asked to name the colours in an image it never received will name four
;;      colours anyway, confidently and wrongly. That is precisely how a silently
;;      dropped image hides. A model can guess a colour; it cannot fake prompt
;;      tokens.
;;   2. THE §8A RELOCATION RULE. A tool returns an image in its result `:parts`;
;;      neither style can carry an image block inside a tool_result message, so
;;      the loop must relocate those parts onto the following user turn — and the
;;      run must still complete.
;;
;; The fixture is read with `koine.fs/read-bytes` + `koine.codec/encode`, never
;; `read-file`: the text route is `slurp`, which is lossy for non-UTF-8 bytes
;; IDENTICALLY on both hosts, so it would produce a plausible, self-consistent,
;; WRONG base64 that agrees with itself and disagrees with the other six ports.
;;
;; WITHOUT `OPENROUTER_API_KEY` this example still runs and still proves
;; something real — the part shapes and the two provider block shapes, offline.
;; With the key it makes six cheap calls (tiny models, max_tokens 40, an
;; 82-byte image). The key is read from the environment and never printed.
(ns examples.multimodal
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.env :as env]
            [koine.fs :as fs]
            [toolnexus.client :as client]
            [toolnexus.content :as content]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]
            [toolnexus.tool :as tool]))

(def question
  (str "This is an 8x8 image with four solid quadrants. Name the colour of each "
       "quadrant in the order top-left, top-right, bottom-left, bottom-right. "
       "Answer with four colour words only."))

(def colours ["red" "green" "blue" "white"])

(defn- named
  "How many of the four fixture colours a piece of prose actually names."
  [text]
  (let [t (str/lower-case (str text))]
    (count (filter #(str/includes? t %) colours))))

(defn- sign [n] (str (if (neg? n) "" "+") n))

(defn -main [& _]
  (let [root    (or (env/get-env "TN_EXAMPLES") "../../examples")
        fixture (str root "/media/fixture.png")
        ;; read-bytes + encode, never read-file — see the note at the top.
        bytes   (fs/read-bytes fixture)
        b64     (codec/encode bytes)
        image   (content/image-file fixture)]

    (println "fixture:  " (pr-str (content/describe-part image)))
    (println "base64 ok:" (= b64 (:data image)))
    (println "openai:   " (pr-str (:type (first (:blocks (content/encode-parts [image] {:style "openai"}))))))
    (println "anthropic:" (pr-str (:type (first (:blocks (content/encode-parts [image] {:style "anthropic"}))))))

    ;; A tool whose RESULT carries the image — the §8A relocation path.
    (let [show (native/native-tool
                {:name         "show_fixture"
                 :description  "Return the 8x8 four-quadrant fixture image so you can look at it."
                 :input-schema {:type "object" :properties {}}
                 :run (fn [_args]
                        (tool/with-parts
                          (tool/success "the fixture image (image/png, 82 bytes)")
                          [(content/from-bytes (fs/read-bytes fixture) "image/png")]))})
          tk    (toolnexus/build {:builtins false :tools [show]})
          empty (toolnexus/build {:builtins false})]

      (if-not (env/get-env "OPENROUTER_API_KEY")
        (println "\n(no OPENROUTER_API_KEY set — skipping the live runs)")

        (doseq [[style model]
                [["openai"    (or (env/get-env "OPENROUTER_MODEL_OPENAI") "openai/gpt-4o-mini")]
                 ["anthropic" (or (env/get-env "OPENROUTER_MODEL_ANTHROPIC") "anthropic/claude-haiku-4.5")]]]
          (let [c (client/create-client
                   {:base-url       "https://openrouter.ai/api/v1"
                    :style          style
                    :model          model
                    ;; explicit: the env fallback would prefer OPENAI_API_KEY /
                    ;; ANTHROPIC_API_KEY, and either one sent to OpenRouter is a 401.
                    :api-key        (env/get-env "OPENROUTER_API_KEY")
                    :max-turns      4
                    :request-params {:max_tokens 40}})
                ;; 1. the identical request, without and with the image.
                text-only  (client/run c [(content/text-part question)] {:toolkit empty})
                with-image (client/run c [(content/text-part question) (content/image-file fixture)]
                                       {:toolkit empty})
                ptok-text  (get-in text-only  [:usage :prompt-tokens])
                ptok-image (get-in with-image [:usage :prompt-tokens])
                delta      (- ptok-image ptok-text)
                ;; 2. the relocation path: the model calls the tool, the image
                ;;    comes back in its result parts, the loop relocates it.
                reloc      (client/run c (str "Call show_fixture, then " question) {:toolkit tk})
                ok?        (and (= "done" (:status reloc)) (pos? (:tool-call-count reloc)))]
            (println)
            (println (str "[" style "] " model))
            (println (str "  text only  (" ptok-text " ptok): " (str/replace (str (:text text-only)) "\n" " ")))
            (println (str "  with image (" ptok-image " ptok): " (str/replace (str (:text with-image)) "\n" " ")))
            (println (str "  via tool   (" (:tool-call-count reloc) " tool calls): "
                          (str/replace (str (:text reloc)) "\n" " ")))
            (println (str "RESULT clojure style=" style
                          " ptok_text=" ptok-text
                          " ptok_image=" ptok-image
                          " delta=" (sign delta)
                          " colours=" (named (:text with-image)) "/4"
                          " relocation=" (if ok? "ok" "FAILED")
                          " reloc_colours=" (named (:text reloc)) "/4"))))))
    (println "OK")))
