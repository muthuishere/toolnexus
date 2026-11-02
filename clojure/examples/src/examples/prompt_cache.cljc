;; 8. PROMPT CACHE + TOOL TIERS (0.22.0)
;;
;; Many tools and a long system prompt make every request expensive. Two client
;; options fix it together:
;;   :prompt-cache  marks the stable prefix (tools, system, latest message) for
;;                  the provider's cache — here Anthropic `cache_control`
;;   :tool-tiers    sends only the core tool schemas plus `tool_search` and
;;                  `call_tool`; the model loads the rest as a TOOL RESULT, so the
;;                  tools array — and with it the cached prefix — never changes.
;;
;; Hermetic: an in-process :http-client plays the model, so no key and no network.
;; The key below is a placeholder; a local or custom host needs :api-key or
;; :api-key-env since 0.22.0 (there is no env fallback any more).
(ns examples.prompt-cache
  (:require [toolnexus.client :as client]
            [toolnexus.core :as core]
            [toolnexus.native :as native]
            [koine.json :as json]))

(defn- body-of [b] (if (string? b) (json/read-str b) b))

(defn- use-tool [id nm input]
  {:content [{:type "tool_use" :id id :name nm :input input}]
   :stop_reason "tool_use"
   :usage {:input_tokens 12 :output_tokens 4
           :cache_read_input_tokens (if (= id "t1") 0 1800)
           :cache_creation_input_tokens (if (= id "t1") 1800 0)}})

(def script
  "What the fake model does, one reply per request."
  [(use-tool "t1" "tool_search" {:query "select:deploy"})
   (use-tool "t2" "call_tool" {:name "deploy" :arguments {:env "staging"}})
   {:content [{:type "text" :text "Deployed to staging."}] :stop_reason "end_turn"
    :usage {:input_tokens 12 :output_tokens 4 :cache_read_input_tokens 1900 :cache_creation_input_tokens 0}}])

(defn -main [& _]
  (let [bodies (atom [])
        queue  (atom script)
        gated  (atom [])
        tk     (core/build
                {:tools [(native/native-tool {:name "read_notes" :description "Read the release notes."
                                              :run (fn [_] "v1.4: faster builds")})
                         (native/native-tool {:name "lint" :description "Lint the code."
                                              :run (fn [_] "clean")})
                         (native/native-tool {:name "deploy" :description "Deploy the service."
                                              :run (fn [a] (str "deployed to " (:env a)))})]})
        llm    (client/create-client
                {:base-url "https://api.anthropic.com" :style "anthropic" :model "claude-sonnet-4-5"
                 :api-key "YOUR_KEY_HERE"
                 :system-prompt "You are a release engineer."
                 :prompt-cache {:long-ttl true}
                 :tool-tiers {:core ["read_notes"] :deferred ["deploy"]}
                 :hooks {:before-tool (fn [ev] (swap! gated conj (:name ev)) nil)}
                 :http-client (fn [_url _headers body]
                                (swap! bodies conj (body-of body))
                                (let [r (first @queue)]
                                  (swap! queue #(vec (rest %)))
                                  {:status 200 :headers {} :body (json/write-str r)}))})]

    (println "tier plan:" (pr-str (into (sorted-map) (client/tool-tier-plan llm tk))))

    (let [r     (client/run llm "Ship it to staging" {:toolkit tk})
          tools (fn [b] (mapv :name (:tools b)))]
      (println "tools sent:" (pr-str (tools (first @bodies))))
      (println "requests:  " (count @bodies))
      (println "gate saw:  " (pr-str @gated))
      (println "usage:     " (pr-str (select-keys (:usage r) [:prompt-tokens :cache-read-tokens :cache-write-tokens])))
      (println "answer:    " (:text r))

      (assert (= ["read_notes" "tool_search" "call_tool"] (tools (first @bodies))))
      (assert (apply = (map #(json/write-str (:tools %)) @bodies)) "the tools array never changed")
      (assert (= {:type "ephemeral" :ttl "1h"} (:cache_control (last (:tools (first @bodies))))))
      (assert (= ["deploy"] @gated) "call_tool was unwrapped before the permission hook")
      (assert (pos? (get-in r [:usage :cache-read-tokens] 0)))
      (assert (= "Deployed to staging." (:text r)))))
  (println "OK"))
