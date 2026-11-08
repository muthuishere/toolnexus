;; 9. MEDIA GENERATION (SPEC §7G) — the E1 example contract
;;
;; Named media clients behind one interface: `create-media` routes each verb to
;; the first client that supports it and can take the args, and falls through to
;; the next one ONLY when a client is `unavailable` (no key, no CLI, no credits,
;; an arg it cannot take). Two faces over the same router: typed verbs
;; (`media/image`, `media/speech` …) and tools (`media/tools` — generate_image …).
;;
;; Clients, in order, each added only when its CLI is on PATH or its key is set:
;;   grok (CLI), codex (CLI), elevenlabs (ELEVENLABS_API_KEY),
;;   then the paid fallbacks openai (OPENAI_API_KEY), fal (FAL_API_KEY),
;;   a2e (A2E_API_KEY).
;; Steps: (1) image  (2) ElevenLabs speech, voice from MEDIA_VOICE else the
;; default  (3) tool face: list the tools, call generate_image  (4) Grok video
;; only with MEDIA_VIDEO=1  (5) paid video + talk only with MEDIA_PAID=1.
;; Prints client / verb / path-or-url / mime / size / usage per file. Any
;; attempted step that errors => non-zero exit. No client at all => a hint and
;; `OK` (exit 0), so `run.sh` stays hermetic. Keys are read at call time and are
;; never printed.
(ns examples.media
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.env :as env]
            [koine.fs :as fs]
            [toolnexus.media :as media]))

(def ^:private prompt "a red apple on a white table")

(defn- has-env? [v] (seq (env/get-env v)))

(defn- on-path? [cmd]
  (some (fn [d] (and (seq d) (fs/exists? (str d "/" cmd)) (not (fs/directory? (str d "/" cmd)))))
        (str/split (or (env/get-env "PATH") "") #":")))

(defn- size-of [f]
  (cond (:path f) (str (count (fs/read-bytes (:path f))))
        (:data f) (str (count (codec/decode-bytes (:data f))))
        :else     "-"))

(defn- print-file [client verb f usage]
  (println (str "  " client " " verb " " (or (:path f) (:url f) "inline") " " (:mimeType f)
                " " (size-of f) " bytes" (when usage (str " usage=" (pr-str usage))))))

(defn- step!
  "Run one step; true on success, prints the error and returns false on failure."
  [label f]
  (println (str "[" label "]"))
  (try (f) true
       (catch Throwable e
         (println (str "  ERROR " (ex-message e)))
         false)))

(def ^:private served (atom nil))

(defn- tracked
  "Wrap a client's verb fns to record which client was last called — after a
  call, the one that served it (a fall-through overwrites it)."
  [c]
  (reduce (fn [c k] (if-let [f (get c k)]
                      (assoc c k (fn [a ctx] (reset! served (:name c)) (f a ctx)))
                      c))
          c [:image :video :speech :music :transcribe :clone-voice :talk]))

(defn- typed! [m verb args]
  (let [r (media/generate m verb args)]
    (doseq [f (:files r)] (print-file @served verb f (:usage r)))
    (when (seq (:text r)) (println (str "  text: " (:text r))))
    r))

(defn -main [& _]
  (let [clients (mapv tracked (cond-> []
                  (on-path? "grok")             (conj (media/grok-media-client))
                  (on-path? "codex")            (conj (media/codex-media-client))
                  (has-env? "ELEVENLABS_API_KEY")   (conj (media/elevenlabs-media-client))
                  ;; paid fallbacks: smallest size, lowest quality by default
                  (has-env? "OPENAI_API_KEY")       (conj (media/openai-media-client {:model "gpt-image-1-mini"}))
                  (has-env? "FAL_API_KEY")          (conj (media/fal-media-client))
                  (has-env? "A2E_API_KEY")          (conj (media/a2e-media-client))))]
    (if (empty? clients)
      (do (println "no media client available: install/log in the grok or codex CLI, or set"
                   "ELEVENLABS_API_KEY / OPENAI_API_KEY / FAL_API_KEY / A2E_API_KEY (no network call made)")
          (println "OK"))
      (let [m       (media/create-media clients)
            _       (println "clients:" (str/join ", " (map :name clients)))
            results (atom [])
            step    (fn [label f] (swap! results conj (step! label f)))
            image   (atom nil)
            audio   (atom nil)]
        ;; 1. image
        (when (media/supports? m "image")
          (step "image" #(reset! image (first (:files (typed! m "image" {:prompt prompt}))))))
        ;; 2. speech through ElevenLabs only
        (when (has-env? "ELEVENLABS_API_KEY")
          (let [eleven (media/create-media (tracked (media/elevenlabs-media-client)))
                voice  (env/get-env "MEDIA_VOICE")]
            (step "speech" #(reset! audio (first (:files (typed! eleven "speech"
                                                                 (cond-> {:text "Hello from toolnexus."}
                                                                   (seq voice) (assoc :voice voice)))))))))
        ;; 3. tool face
        (when (media/supports? m "image")
          (step "tool generate_image"
                (fn []
                  (let [ts (media/tools m)
                        _  (println "  tools:" (str/join " " (map :name ts)))
                        t  (first (filter #(= "generate_image" (:name %)) ts))
                        r  ((:execute t) {:prompt prompt})
                        md (:metadata r)]
                    (when (:isError r) (throw (ex-info (str (:output r)) {})))
                    (println (str "  " (:client md) " image " (:output r)
                                  (when (:usage md) (str " usage=" (pr-str (:usage md))))))))))
        ;; 4. Grok video, opt-in
        (when (and (= "1" (env/get-env "MEDIA_VIDEO")) (on-path? "grok"))
          (step "grok video" #(typed! (media/create-media (tracked (media/grok-media-client))) "video"
                                      {:prompt "a red apple slowly rotating"})))
        ;; 5. paid video + talk, opt-in
        (when (= "1" (env/get-env "MEDIA_PAID"))
          (let [paid (filterv #(contains? #{"openai" "fal" "a2e"} (:name %)) clients)]
            (when (seq paid)
              (let [pm (media/create-media paid)]
                (when (media/supports? pm "video")
                  (step "paid video" #(typed! pm "video" {:prompt "a red apple slowly rotating"})))
                (when (and (media/supports? pm "talk") @image @audio)
                  (let [loc (fn [f] (or (:path f) (:url f)))]
                    (step "paid talk" #(typed! pm "talk" {:image (loc @image) :audio (loc @audio)}))))))))
        (if (every? true? @results)
          (println "OK")
          (throw (ex-info (str "media example: " (count (remove true? @results)) " step(s) failed") {})))))))
