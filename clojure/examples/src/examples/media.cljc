;; 9. MEDIA GENERATION (SPEC §7G)
;;
;; One JSON style — the shared a2e.ai preset — turns into both faces of the media
;; engine: the typed verb (`media/image`) and the tool face (`media/tools`), here
;; invoked directly the way the client loop would.
;;
;; Opt-in by environment: with no `A2E_API_KEY` it prints a one-line hint and `OK`
;; and makes no network call, so `run.sh` stays hermetic. The key is read by the
;; preset's `${A2E_API_KEY}` header at call time and is never printed.
(ns examples.media
  (:require [koine.env :as env]
            [toolnexus.media :as media]))

(defn -main [& _]
  (if-not (env/get-env "A2E_API_KEY")
    (println "set A2E_API_KEY to run the media example (skipping: no network call made)")
    (let [root  (or (env/get-env "TN_EXAMPLES") "../../examples")
          m     (media/create-media
                 (media/load-style (str root "/media-styles/a2e.json"))
                 {:on-progress (fn [p] (println (str "  .." (:verb p) " " (:status p))))})
          ;; 1. Typed verb.
          img   (media/image m {:prompt "a red fox in fresh snow, watercolor"
                                :width 768 :height 768})
          _     (println "image status:" (:status img))
          _     (doseq [f (:files img)]
                  (println "  file:" (:type f) (:mimeType f) (:url f)))
          ;; 2. Tool face — the same engine as plain Tools an LLM would call.
          tools (media/tools m)
          _     (doseq [t tools] (println "tool:" (:name t)))
          sp    (first (filter #(= "generate_speech" (:name %)) tools))
          res   ((:execute sp) {"text" "Hello from toolnexus."})]
      (println "speech output:" (:output res) "isError:" (:isError res))
      (doseq [p (:parts res)]
        (println "  part:" (:type p) (:mimeType p) (:url p)))))
  (println "OK"))
