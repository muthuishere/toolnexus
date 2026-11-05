;; Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
;;
;; Run: ./run.sh example 03-tool-input
;;
;; In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
;; RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
;; The client answers them and re-sends the same call with inputResponses (+ the opaque
;; requestState). call-tool runs those rounds for you: you only supply a responder fn.
(ns example.tool-input
  (:require [mcp2026.core :as mcp]))

(defn main []
  (let [srv (mcp/serve! (mcp/fixture-server)) ; `greet` asks for the user's name
        c (mcp/client (str (:url srv) "/mcp"))]

    ;; The responder is called once per input request. key identifies the request inside the
    ;; round; req is the request itself ({"method" "elicitation/create", "params" {...}}).
    ;; In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
    (let [ask-user (fn [key req]
                     (println (str "server asks (" (get req "method") "): " (get-in req ["params" "message"])))
                     ;; Answer like a person who filled the form. "decline" / "cancel" are the other actions.
                     {"action" "accept" "content" {"name" "Ada"}})

          res (mcp/call-tool c "greet" {} ask-user)]
      (println "result:" (mcp/text res)))

    ((:stop srv))))

(main)
(shutdown-agents)
(System/exit 0)
