#!/usr/bin/env bash
# env-chain-check — the API-key environment fallback (SPEC §8) proven from OUTSIDE.
#
# The suite cannot test this: neither host can set an environment variable
# in-process, so `resolve-key`'s fallback chain was the one §8 behaviour with no
# coverage — an audit showed the whole auth path could be emptied with the suite
# green. A RUNNER, though, controls its child's environment completely.
#
# FAKE keys only. This check never touches a real credential: the values below
# are obviously fabricated, and the probe prints the auth header's SHAPE
# (presence / scheme / length), never its content.
#
#   openai style:     :api-key > OPENAI_API_KEY   > OPENROUTER_API_KEY > ""
#   anthropic style:  :api-key > ANTHROPIC_API_KEY > OPENROUTER_API_KEY > ""
set -uo pipefail
cd "$(dirname "$0")"

PROBE=$(mktemp /tmp/tn-env-chain-XXXXXX.cljc)
cat > "$PROBE" <<'EOF'
(require '[koine.json :as json] '[koine.server :as server] '[toolnexus.client :as client])
(def got (atom nil))
(def srv (server/serve
          (fn [req]
            ;; Record BOTH headers so a style that sent the wrong one is visible,
            ;; not masked by the lookup order.
            (reset! got [(count (str (get (:headers req) "authorization")))
                         (count (str (get (:headers req) "x-api-key")))])
            {:status 200 :headers {"content-type" "application/json"}
             :body (json/write-str {:choices [{:message {:role "assistant" :content "ok"}}]
                                    :content [{:type "text" :text "ok"}]
                                    :usage {:prompt_tokens 1 :completion_tokens 1}})})
          {:port 0}))
(require '[koine.env :as env])
;; Style arrives via TN_STYLE, not *command-line-args* — `clojure -e` does not
;; populate the latter, and the first version of this probe silently ran every
;; case as openai because of it.
(def style (or (env/get-env "TN_STYLE") "openai"))
(def c (client/create-client {:base-url (str "http://127.0.0.1:" (server/port srv))
                              :style style :model "m"}))     ; NO :api-key => env chain
(client/run c "hi" {})
(println "shape" (first @got) (second @got))    ; lengths ONLY — never a value
(server/stop! srv)
EOF

fail=0
expect() { # $1 = label, $2 = expected header length, $3 = style, rest = env
  local label=$1 want=$2 style=$3; shift 3
  local got
  got=$(env -u OPENAI_API_KEY -u ANTHROPIC_API_KEY -u OPENROUTER_API_KEY "$@" \
        TN_STYLE="$style" clojure -M -e "(load-file \"$PROBE\")" 2>/dev/null | grep '^shape ' | awk '{print $2"/"$3}')
  if [ "$got" = "$want" ]; then
    printf '  ok    %-52s header-len %s\n' "$label" "$got"
  else
    printf '  FAIL  %-52s header-len %s, expected %s\n' "$label" "${got:-none}" "$want"
    fail=1
  fi
}

# Shape is "authorization-len/x-api-key-len". "Bearer " + key; the fakes are
# 18 ("fake-oai-key-01234"), 15 ("fake-or-key-123") and 16 ("fake-ant-key-abc")
# chars. An anthropic case with a non-zero authorization length means the wrong
# HEADER went out, which a single-header probe would have masked.
#
# "openai, no key" is 6/0 — the shipped client still emits the bare scheme
# ("Bearer" after value-trim) rather than omitting the header. Pinned as-is.
expect "openai: OPENAI_API_KEY used"                25/0 openai    OPENAI_API_KEY=fake-oai-key-01234
expect "openai: falls back to OPENROUTER_API_KEY"   22/0 openai    OPENROUTER_API_KEY=fake-or-key-123
expect "openai: OPENAI wins when both are set"      25/0 openai    OPENAI_API_KEY=fake-oai-key-01234 OPENROUTER_API_KEY=fake-or-key-123
expect "openai: no key => bare scheme, pinned"       6/0 openai
expect "anthropic: ANTHROPIC_API_KEY on x-api-key"  0/16 anthropic ANTHROPIC_API_KEY=fake-ant-key-abc
expect "anthropic: falls back to OPENROUTER"        0/15 anthropic OPENROUTER_API_KEY=fake-or-key-123
expect "anthropic: does NOT read OPENAI_API_KEY"    0/15 anthropic OPENAI_API_KEY=fake-oai-key-01234 OPENROUTER_API_KEY=fake-or-key-123

rm -f "$PROBE"
if [ $fail -eq 0 ]; then
  echo '{"check":"env-chain","gate":"OK"}'
else
  echo '{"check":"env-chain","gate":"FAILED"}'
  exit 1
fi
