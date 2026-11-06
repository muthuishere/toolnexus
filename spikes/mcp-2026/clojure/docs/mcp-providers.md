# MCP authentication: provider notes

> **Clojure port of the spike (openspec `upgrade-mcp-2026-07-28`); the Go spike in `spikes/mcp-2026/golang` is
> the reference.** The API below is `spikes/mcp-2026/clojure` (namespaces `mcp2026.core`, `mcp2026.auth`,
> `mcp2026.devauth`). Every code block is copied verbatim from a program under `examples/` that
> `examples_test.cljc` runs and checks; `docs_test.cljc` fails if a block here stops matching its source.

| provider | client identity | tested | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client, or CIMD (experimental) | live, docker compose | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live | works |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: `../golang/keycloak/docker-compose.yml` + `realm-toolnexus.json` (realm `toolnexus`, client
`toolnexus-mcp`, throwaway user `alice`), example `examples/09-keycloak`.

```sh
cd ../golang/keycloak && docker compose up -d && cd -
KEYCLOAK=1 ./run.sh example 09-keycloak                                 # logs in through your browser
KEYCLOAK=1 clojure -M:test -m mcp2026.test-main mcp2026.keycloak-test   # the live suite, headless
```

<!-- from: examples/09-keycloak/main.cljc -->
```clojure
    ;; ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
    (let [srv (-> (mcp/fixture-server)
                  (mcp/tool "whoami" "Who is calling"
                            (fn [{p :principal} _]
                              ;; :subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
                              (str "client=" (get-in p [:claims "azp"]) " scopes=" (str/join " " (:scopes p))
                                   " subject-set=" (boolean (seq (:subject p))))))
                  (assoc :auth {:issuer issuer
                                :audience resource ; = the audience mapper's included.custom.audience
                                :scopes {"*" ["mcp:tools"] "greet" ["mcp:greet"]}}))
          ts (mcp/serve! srv {:port 7199})

          ;; ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
          open (if (= "1" (System/getenv "HEADLESS_LOGIN"))
                 {:open (example.keycloak.headless/headless-login "alice" "alice-test-pw")} ; throwaway local test user
                 {}) ; default :open = system browser
          c (mcp/client resource {:auth (auth/oauth {:client-ids {issuer "toolnexus-mcp"} ; client id bound to THIS issuer
                                                     :allow-http true ; dev Keycloak is plain HTTP; never in production
                                                     :authorizer (auth/loopback-authorizer open)})})]

      (println "whoami:" (mcp/text (mcp/call-tool c "whoami" nil)))
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

Setup checklist and Keycloak quirks (audience mapper, `basic` scope, CIMD client policy) are the same as for the
Go spike: see `../golang/keycloak/README.md`. One JVM-specific quirk for headless logins: Keycloak sets its
session cookies `Secure`, and `java.net.CookieManager` withholds them over `http://localhost`, so the test
harness (`examples/09-keycloak/headless.cljc`) keeps its own per-host jar. Real browsers are unaffected.

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**
with callback URL `http://127.0.0.1/callback` (GitHub ignores the port on loopback).

<!-- from: examples/10-github/main.cljc -->
```clojure
      (let [login (if (= "chrome-agent" (System/getenv "BROWSER"))
                    {:open (fn [u] (-> (ProcessBuilder. ["chrome-agent" "goto" u]) (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD) (.start) (.waitFor)))}
                    {}) ; system browser + http://127.0.0.1:<random>/callback
            c (mcp/client "https://api.githubcopilot.com/mcp/"
                          {:auth (auth/oauth {:client-id id
                                              :client-secret secret ; GitHub OAuth Apps are confidential (client_secret_basic)
                                              :authorizer (auth/loopback-authorizer login)})})]

        (println "tools listed:" (boolean (seq (mcp/tools c))))

        (let [res (mcp/call-tool c "get_me" {})] ; read-only: who am I
          (println "get_me:" (get res "resultType") "| isError:" (true? (get res "isError")))))))) ; content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ./run.sh example 10-github
```

Quirks the client handles without configuration: the token endpoint answers form-encoded without
`Accept: application/json` (the client sends it and parses both); `serverInfo` arrives in result `_meta`;
`isError` is omitted on success (missing = false).

## Microsoft Entra ID — UNTESTED

Not run against a real tenant. Entra rejects `resource` on the `refresh_token` grant (python-sdk #2578), though
the MCP spec says it MUST be sent. Set `:omit-refresh-resource true`; authorize and code exchange still send
`resource`. Only the simulation (`:reject-refresh-resource` on the dev AS, `refresh-resource-configurable`)
covers this.
