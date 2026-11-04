;; 10. EMBEDDABLE MCP SERVER (SPEC §7C.1, ADR 0039)
;;
;; `mcp-handler` returns a Ring handler you mount on YOUR stack, behind your own
;; TLS, CORS and OAuth. Every policy is a hook; toolnexus has no notion of a
;; tenant or a role. Here: bearer auth, a per-identity tool list (bob never sees
;; — and can never call — `wipe`), a confirm gate on the destructive tool, and an
;; audit hook. The requests are Ring maps handed straight to the handler, so this
;; runs hermetically on both hosts; mount `(:handler h)` on any Ring server for
;; real HTTP.
(ns examples.mcp-handler
  (:require [clojure.string :as str]
            [koine.json :as json]
            [toolnexus.mcp-handler :as mh]
            [toolnexus.tool :as tool]))

(def tools
  [(tool/tool {:name "search" :description "Search records" :context? true
               :annotations {:title "Search" :readOnlyHint true}
               :execute (fn [args ctx] (tool/success (str "results for " (:q args) " as " (mh/mcp-identity ctx))))})
   (tool/tool {:name "wipe" :description "Delete everything (admins only)"
               :annotations {:destructiveHint true}
               :execute (fn [_] (tool/success "wiped"))})])

(def h
  (mh/mcp-handler
    (tool/toolkit [])
    {:name "records" :title "Records API" :stateless true
     :authenticate (fn [req] (case (get-in req [:headers "authorization"])
                               "Bearer alice" "alice"
                               "Bearer bob"   "bob"
                               (throw (ex-info "bad token" {}))))
     :www-authenticate (fn [_ _] "Bearer resource_metadata=\"https://records.example/.well-known/oauth-protected-resource\"")
     :list-tools (fn [ctx] (if (= "alice" (mh/mcp-identity ctx)) tools [(first tools)]))
     :before-call (fn [_ t _] (when (get-in t [:annotations :destructiveHint])
                                {:kind :confirm :message "Wipe all records?"
                                 :result (tool/with-structured {:output "re-call with nonce n-42"} {:nonce "n-42"})}))
     :on-call (fn [ev] (println (str "  audit: " (:identity ev) " " (:name ev) " " (name (:decision ev))
                                     " isError=" (:is-error ev) " " (:result-bytes ev) "B")))}))

(defn- post [token m]
  (let [res ((:handler h) {:request-method :post
                           :headers (cond-> {"content-type" "application/json"
                                             "accept" "application/json, text/event-stream"}
                                      token (assoc "authorization" (str "Bearer " token)))
                           :body (json/write-str (assoc m :jsonrpc "2.0" :id 1))})]
    [(:status res) (:body res)]))

(defn -main [& _]
  (let [[s _] (post "mallory" {:method "tools/list"})]
    (println "mallory tools/list -> HTTP" s))
  (doseq [who ["alice" "bob"]]
    (let [[_ b] (post who {:method "tools/list"})]
      (println who "sees" (str/join ", " (map :name (get-in (json/read-str b) [:result :tools]))))))
  (println "bob calls wipe   ->" (get-in (json/read-str (second (post "bob" {:method "tools/call" :params {:name "wipe"}}))) [:error :message]))
  (println "alice calls wipe ->" (get-in (json/read-str (second (post "alice" {:method "tools/call" :params {:name "wipe"}}))) [:result :structuredContent]))
  (println "alice search     ->" (get-in (json/read-str (second (post "alice" {:method "tools/call" :params {:name "search" :arguments {:q "invoices"}}}))) [:result :content 0 :text]))
  (println "OK"))
