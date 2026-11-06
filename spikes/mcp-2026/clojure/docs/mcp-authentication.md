# MCP authentication

> **Clojure port of the spike (openspec `upgrade-mcp-2026-07-28`); the Go spike in `spikes/mcp-2026/golang` is
> the reference.** The API below is `spikes/mcp-2026/clojure` (namespaces `mcp2026.core`, `mcp2026.auth`,
> `mcp2026.devauth`). Every code block is copied verbatim from a program under `examples/` that
> `examples_test.cljc` runs and checks; `docs_test.cljc` fails if a block here stops matching its source.

MCP 2026-07-28 `basic/authorization` is OAuth 2.1 with the MCP server as a protected resource. The client
discovers everything from the server's `401`:

1. `WWW-Authenticate: Bearer resource_metadata=…` → Protected Resource Metadata (RFC 9728); falls back to
   `/.well-known/oauth-protected-resource/<path>`, then the root.
2. `authorization_servers[0]` → AS metadata (RFC 8414 path-insert, then OIDC path-insert, then OIDC append),
   with an `issuer` identity check.
3. Refuse the AS if it does not list PKCE `S256`.
4. Authorization code + PKCE S256, with `resource` (RFC 8707) on the authorize **and** token requests.
5. Callback: `iss` (RFC 9207) and `state` are checked before `code` or `error` is acted on.
6. The token is stored per resource and bound to the issuer; it is sent only once that issuer is confirmed.
7. Expired → `refresh_token` grant (single-flight). `403 insufficient_scope` → step-up with the union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx is thrown and never retried, because the
tool may already have run.

## Who the client is: no DCR

The spec deprecates Dynamic Client Registration and toolnexus does not implement it.

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents | `:client-metadata-url "https://app.example/client.json"` | Keycloak 26.8 with `cimd` + client policy |
| no CIMD (most providers today) | **register an app** once; `:client-id` (+ `:client-secret`) | GitHub OAuth App, Entra, Keycloak client |
| several ASes | `:client-ids {issuer client-id}`; each id is used only with its issuer | |

If neither is set, the call fails with *register an app with <issuer> and set … :client-id*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`(auth/loopback-authorizer)` opens the system browser and listens on `http://127.0.0.1:<random>/callback`
(RFC 8252 §7.3). The call blocks until the user has logged in, then returns the tool's result. In the example the
in-process dev server stands in for the browser.

<!-- from: examples/06-oauth-zero-config/main.cljc -->
```clojure
    (let [store (auth/memory-store) ; implement auth/TokenStore to persist tokens (keyed by resource)

          ;; The user code. A real app leaves :authorizer out: (auth/loopback-authorizer) opens the system
          ;; browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
          c (mcp/client url {:auth (auth/oauth {:client-id "my-app"
                                                :store store
                                                :authorizer (auth/loopback-authorizer {:open #(devauth/approve as %)})})})]

      (println "echo:" (mcp/text (mcp/call-tool c "echo" {"text" "hello after login"})))

      ;; Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
      ;; re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
      (println "greet:" (mcp/text (mcp/call-tool c "greet" {}
                                                 (fn [_ _] {"action" "accept" "content" {"name" "Ada"}}))))
```

```text
echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true
```

`greet` needs `mcp:greet`, so the second call is a **step-up**: `403 insufficient_scope` → a second authorization
for `mcp:read mcp:greet` → the paused call is sent once.

### Token store

`:store` takes any `auth/TokenStore` (`load-token` / `save-token!` / `delete-token!` keyed by resource URL);
the default is in-memory. Share one to avoid logging in again:

<!-- from: examples/06-oauth-zero-config/main.cljc -->
```clojure
      ;; A second client sharing the token store needs no new login.
      (let [c2 (mcp/client url {:auth (auth/oauth {:client-id "my-app" :store store
                                                   :authorizer (auth/loopback-authorizer {:open #(devauth/approve as %)})})})]
        (mcp/call-tool c2 "echo" {"text" "again"}))
      (println "browser logins:" (:authorizations (devauth/stats as))) ; 1 login + 1 step-up

      (let [tok (auth/load-token store url)]
        (println "token stored for this issuer:" (= (:issuer tok) (:issuer as))))) ; (str tok) is redacted
```

A token is an `auth/Token` record that prints as `Token{issuer=…, redacted}` (`str`, `pr-str`, `println`).

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `(auth/pause-authorizer redirect)` makes the call throw an
`ex-info` whose pending (`mcp/pending-of`) is `{:id :kind "authorization" :prompt :url :expires-at}`, the shape
of toolnexus SPEC §10 `Request`. Send the user to `:url`; when the AS redirects back, `auth/resume` returns the
**original call's** result. A pending authorization expires after 10 minutes.

### As one HTTP handler

<!-- from: examples/07-oauth-web-pause-resume/main.cljc -->
```clojure
    ;; (a) One handler: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
    ;; with ?code&state, web-handler resumes, and the tool's text is written to the response.
    (deliver app-handler
             (auth/web-handler o (fn [req]
                                   (mcp/call-tool c "echo" {"text" (w/q1 (w/parse-query (:query req)) "text")}))
                               mcp/text))
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/main.cljc -->
```clojure
    ;; (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
    (let [o2 (auth/oauth {:client-id "my-web-app" :authorizer (auth/pause-authorizer "http://127.0.0.1/callback")})
          c2 (mcp/client mcp-url {:auth o2})
          p (try (mcp/call-tool c2 "echo" {"text" "resumed by hand"}) nil
                 (catch Exception e (or (mcp/pending-of e) (throw e))))] ; nil when the call did not pause
      (println "(b) paused:" (:kind p) "-" (boolean (seq (:prompt p))) "- has URL:" (boolean (seq (:url p))))

      (let [callback-url (devauth/capture-callback (:url p)) ; the user logs in; you receive the callback URL
            res (auth/resume o2 (:id p) callback-url)]
        (println "(b) resumed:" (mcp/text res))))
```

```text
(a) web-handler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on resume (the `401` came before dispatch). A pause inside an MRTR
round resumes that same round without asking the user again (`retry_test.cljc`).

## Client options (`auth/oauth`)

| key | default | meaning |
|---|---|---|
| `:authorizer` | `(loopback-authorizer)` | automatic, or `(pause-authorizer redirect)` |
| `:client-id` / `:client-secret` | | pre-registered client; a secret makes it confidential |
| `:token-auth-method` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `:client-ids` | | issuer → client id |
| `:client-metadata-url` | | CIMD client id |
| `:store` | in-memory | `TokenStore` |
| `:omit-refresh-resource` | false | drop `resource` on refresh (Entra, see Providers) |
| `:http` | `wire/request` (30 s timeout) | request fn for metadata and token calls |
| `:allow-http` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

`auth/configure!` changes keys on a live engine.

## Protecting your own server

One key: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/main.cljc -->
```clojure
        ;; ---- (1) the server: :issuer one-liner + per-tool scope + principal ----
        srv (-> (mcp/server)
                (mcp/tool "whoami" "Who is calling"
                          (fn [{:keys [principal]} _] ; :subject :scopes :claims of the verified token
                            (str "hello " (:subject principal) " (" (str/join " " (:scopes principal)) ")")))
                (mcp/tool "delete_all" "Dangerous admin tool" (fn [_ _] "deleted"))
                (assoc :auth {:issuer (:issuer as) ; the whole required config
                              :scopes {"*" ["mcp:read"]            ; every request
                                       "delete_all" ["mcp:admin"]}})) ; additionally required for this tool
        ts (mcp/serve! srv {}) ; production: (mcp/serve! srv {:host "0.0.0.0" :port 8080})
```

`(get scopes "*")` is required on every request and advertised as `scopes_supported`; `(get scopes tool)` is
also required for that `tools/call`. Scopes match exactly. `:audience` defaults to `scheme://host/mcp` of the
request. The handler reads the caller from its first argument: `(:principal ctx)` = `{:subject :scopes :claims}`.

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
verify hook: hello billing-service
wrong key rejected: true
```

### Verify hook: opaque tokens, API keys, introspection

`:verify (fn [token] principal)` replaces JWT verification; return nil or throw for `401 invalid_token`.

<!-- from: examples/08-serve-protected/main.cljc -->
```clojure
    ;; ---- (2) custom :verify: opaque API keys / token introspection instead of JWT ----
    (let [keys {"svc-key-123" {:subject "billing-service" :scopes ["mcp:read"]}} ; e.g. a DB lookup or RFC 7662 call
          srv2 (-> (mcp/server)
                   (mcp/tool "whoami" "Who is calling" (fn [{:keys [principal]} _] (str "hello " (:subject principal))))
                   (assoc :auth {:issuer (:issuer as) ; still advertised in the resource metadata
                                 :scopes {"*" ["mcp:read"]}
                                 :verify (fn [token] (get keys token))})) ; nil (or a throw) -> 401 invalid_token
          ts2 (mcp/serve! srv2)
          ;; A service caller with a static key: wrap the request fn to add the header.
          bearer (fn [k] (fn [req] (w/request (assoc-in req [:headers "Authorization"] (str "Bearer " k)))))
          svc (mcp/client (str (:url ts2) "/mcp") {:http (bearer "svc-key-123")})]
```

An HTTP client is a plain function of a request map, so a static header is a one-line wrapper (above).

## DevAuthServer (dev and tests only)

`(devauth/start)` is a TLS authorization server on `127.0.0.1` that auto-approves everyone as `dev-user`:
RFC 8414 metadata, JWKS, PKCE, `resource`, `iss`, refresh rotation, pre-registered and CIMD clients. Its
self-signed cert (made once per JVM with the JDK's `keytool`) is trusted by every client in the process.
**Never use it in production.**

<!-- from: examples/06-oauth-zero-config/main.cljc -->
```clojure
  ;; ---- demo harness: an authorization server + a protected MCP server, both in-process ----
  (let [as (devauth/start) ; DEV/TEST ONLY: auto-approves everyone as "dev-user"
        _ (devauth/register-client! as "my-app" ["http://127.0.0.1/callback"]) ; the AS admin registered "my-app"
        srv (assoc (mcp/fixture-server)
                   :auth {:issuer (:issuer as)
                          :scopes {"*" ["mcp:read"] "greet" ["mcp:greet"]}}) ; greet needs more
        ts (mcp/serve! srv)
        url (str (:url ts) "/mcp")]
    ;; ---- end harness ----
```
