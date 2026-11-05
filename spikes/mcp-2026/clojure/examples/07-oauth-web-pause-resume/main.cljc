;; Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
;;
;; Run: ./run.sh example 07-oauth-web-pause-resume   (hermetic: everything runs on 127.0.0.1)
;;
;; A server-side app must never block a request waiting for a login. With pause-authorizer the
;; call throws a pending instead ({:id :kind "authorization" :prompt :url :expires-at}, the
;; shape of toolnexus SPEC §10 Request). The app sends the browser to the pending :url; the
;; authorization server redirects back; the app resumes and gets the ORIGINAL call's result.
;;
;; auth/web-handler packages that whole dance as one HTTP handler. The manual version is shown too.
(ns example.oauth-web-pause-resume
  (:require [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]
            [mcp2026.devauth :as devauth]
            [mcp2026.wire :as w]))

(defn main []
  ;; ---- demo harness: dev authorization server + protected MCP server ----
  (let [as (devauth/start) ; DEV/TEST ONLY
        _ (devauth/register-client! as "my-web-app" ["http://127.0.0.1/tool" "http://127.0.0.1/callback"])
        mcp-ts (mcp/serve! (assoc (mcp/fixture-server) :auth {:issuer (:issuer as)}))
        mcp-url (str (:url mcp-ts) "/mcp")
        ;; ---- end harness ----

        ;; ---- the web app ----
        app-handler (promise)
        app (w/serve! (fn [req] (@app-handler req)))
        o (auth/oauth {:client-id "my-web-app"
                       :authorizer (auth/pause-authorizer (str (:url app) "/tool"))})
        c (mcp/client mcp-url {:auth o})]

    ;; (a) One handler: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
    ;; with ?code&state, web-handler resumes, and the tool's text is written to the response.
    (deliver app-handler
             (auth/web-handler o (fn [req]
                                   (mcp/call-tool c "echo" {"text" (w/q1 (w/parse-query (:query req)) "text")}))
                               mcp/text))
    ;; ---- end web app ----

    ;; A person's browser opens /tool?text=... (w/browser follows redirects and trusts the dev TLS).
    (let [resp (w/browser {:method "GET" :url (str (:url app) "/tool?text=resumed+after+consent")})]
      (println "(a) web-handler:" (:status resp) (:body resp)))

    ;; (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
    (let [o2 (auth/oauth {:client-id "my-web-app" :authorizer (auth/pause-authorizer "http://127.0.0.1/callback")})
          c2 (mcp/client mcp-url {:auth o2})
          p (try (mcp/call-tool c2 "echo" {"text" "resumed by hand"}) nil
                 (catch Exception e (or (mcp/pending-of e) (throw e))))] ; nil when the call did not pause
      (println "(b) paused:" (:kind p) "-" (boolean (seq (:prompt p))) "- has URL:" (boolean (seq (:url p))))

      (let [callback-url (devauth/capture-callback (:url p)) ; the user logs in; you receive the callback URL
            res (auth/resume o2 (:id p) callback-url)]
        (println "(b) resumed:" (mcp/text res))))

    ((:stop app))
    ((:stop mcp-ts))
    (devauth/close as)))

(main)
(shutdown-agents)
(System/exit 0)
