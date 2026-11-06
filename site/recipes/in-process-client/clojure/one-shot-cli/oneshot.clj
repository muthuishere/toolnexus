;; One CLI process per turn, as an in-process client. Loaded by main.clj.
;; JSON + subprocess come from koine, which toolnexus already depends on (no extra deps).
(ns oneshot
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.process :as proc]
            [toolnexus.client :as client]))

;; Render the whole request as one prompt: tools, transcript, and the reply contract.
(defn- render [{:keys [messages tools]}]
  (let [called (atom {}) ; tool_call_id -> tool name, so results read "[tool add] 5"
        lines  (transient ["You are the model inside a tool-calling loop. Tools you may call:"])]
    (doseq [{f :function} tools]
      (conj! lines (str "- " (:name f) ": " (:description f) " args=" (json/write-str (:parameters f)))))
    (conj! lines "") (conj! lines "Conversation:")
    (doseq [m messages]
      (cond
        (= "tool" (:role m))
        (conj! lines (str "[tool " (get @called (:tool_call_id m) (:tool_call_id m)) "] " (:content m)))
        (seq (:tool_calls m))
        (do (doseq [c (:tool_calls m)] (swap! called assoc (:id c) (get-in c [:function :name])))
            (conj! lines (str "[assistant called] " (json/write-str (mapv :function (:tool_calls m))))))
        :else
        (conj! lines (str "[" (:role m) "] " (if (string? (:content m)) (:content m) (json/write-str (:content m)))))))
    (conj! lines "")
    (conj! lines "Reply with ONLY one JSON object: {\"tool_calls\":[{\"name\":\"...\",\"arguments\":{...}}]} to call tools,")
    (conj! lines "or {\"content\":\"...\"} with the final answer. No prose, no code fences.")
    (str/join "\n" (persistent! lines))))

;; Pull the outermost {...} out of the reply, tolerating fences or chatter around it.
(defn- parse [out]
  (or (try
        (let [r (json/read-str (subs out (str/index-of out "{") (inc (str/last-index-of out "}"))))]
          (cond
            (and (sequential? (:tool_calls r)) (seq (:tool_calls r)))
            {:tool-calls (mapv (fn [c] {:name (:name c) :arguments (or (:arguments c) {})}) (:tool_calls r))}
            (string? (:content r)) {:content (:content r)}))
        (catch Throwable _ nil))
      {:content (str/trim out)})) ; not our contract: treat it as the answer

;; One process per turn. The prompt goes in on stdin; the reply comes back on stdout.
(defn one-shot-client [{:keys [command model timeout-ms] :or {timeout-ms 300000}}]
  (client/create-in-process-client
   {:model (or model (first command))
    :generate (fn [req]
                (let [r (proc/sh (vec command) {:in (render req) :timeout-ms timeout-ms})]
                  (when-not (= 0 (:exit r))
                    (throw (ex-info (str (first command) " exited " (:exit r) ": " (:err r)) {:result r})))
                  (parse (:out r))))}))
