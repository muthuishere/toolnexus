#!/usr/bin/env bash
# env-chain-check — the API-key-by-host rule (SPEC §4C, security) proven from
# OUTSIDE.
#
# The suite cannot test this: neither host can set an environment variable
# in-process, so `resolve-key`'s env lookup is the one behaviour with no in-suite
# coverage — an audit showed the whole auth path could be emptied with the suite
# green. A RUNNER, though, controls its child's environment completely.
#
# FAKE keys only. This check never touches a real credential: the values below
# are obviously fabricated, and the probe prints the auth header's SHAPE
# (presence / scheme / length), never its content. Nothing is dialled — the
# probe's :http-client records the headers and answers locally, so a real
# provider URL can be the base without any network.
#
#   :api-key > :api-key-env > ONLY the env var of the base-url HOST, over https:
#     api.openai.com -> OPENAI_API_KEY · api.anthropic.com -> ANTHROPIC_API_KEY ·
#     openrouter.ai (+ subdomains) -> OPENROUTER_API_KEY
#   any other host, or plain http => an error. No cross-provider fallback.
set -uo pipefail
cd "$(dirname "$0")"

PROBE=$(mktemp /tmp/tn-env-chain-XXXXXX.cljc)
cat > "$PROBE" <<'EOF'
(require '[koine.json :as json] '[koine.env :as env] '[clojure.string :as str] '[toolnexus.client :as client])
(def got (atom nil))
;; Base, style and the optional :api-key-env NAME arrive via TN_* env vars —
;; `clojure -e` does not populate *command-line-args*.
(def style (or (env/get-env "TN_STYLE") "openai"))
(def c (client/create-client
        (cond-> {:base-url (env/get-env "TN_BASE") :style style :model "m" :retries 0
                 :http-client (fn [_url headers _body]
                                ;; Record BOTH headers so a style that sent the wrong
                                ;; one is visible, not masked by the lookup order.
                                (reset! got [(count (str (get headers "authorization")))
                                             (count (str (get headers "x-api-key")))])
                                {:status 200
                                 :body (json/write-str {:choices [{:message {:role "assistant" :content "ok"}}]
                                                        :content [{:type "text" :text "ok"}]})})}
          (env/get-env "TN_KEY_ENV") (assoc :api-key-env (env/get-env "TN_KEY_ENV")))))
(try
  (client/run c "hi" {})
  (println "shape" (first @got) (second @got))    ; lengths ONLY — never a value
  (catch Throwable e
    (let [leak (str/includes? (str (ex-message e)) "fake-")]
      (println "shape error" (if leak "LEAK" "clean")))))
EOF

fail=0
expect() { # $1 = label, $2 = expected shape, $3 = style, $4 = base, rest = env
  local label=$1 want=$2 style=$3 base=$4; shift 4
  local got
  got=$(env -u OPENAI_API_KEY -u ANTHROPIC_API_KEY -u OPENROUTER_API_KEY "$@" \
        TN_STYLE="$style" TN_BASE="$base" clojure -M -e "(load-file \"$PROBE\")" 2>/dev/null | grep '^shape ' | awk '{print $2"/"$3}')
  if [ "$got" = "$want" ]; then
    printf '  ok    %-56s %s\n' "$label" "$got"
  else
    printf '  FAIL  %-56s %s, expected %s\n' "$label" "${got:-none}" "$want"
    fail=1
  fi
}

# Shape is "authorization-len/x-api-key-len", or "error/clean" for a refused key
# (clean = the error message carries no key value). "Bearer " + key; the fakes
# are 18 ("fake-oai-key-01234"), 15 ("fake-or-key-123"), 16 ("fake-ant-key-abc")
# and 15 ("fake-custom-123") chars.
OA=OPENAI_API_KEY=fake-oai-key-01234
AN=ANTHROPIC_API_KEY=fake-ant-key-abc
OR=OPENROUTER_API_KEY=fake-or-key-123
expect "openai host: OPENAI_API_KEY"                         25/0 openai https://api.openai.com/v1 $OA $AN $OR
expect "openai host: never falls back to OPENROUTER"         error/clean openai https://api.openai.com/v1 $OR
expect "openrouter host: OPENROUTER only, even with OPENAI"  22/0 openai https://openrouter.ai/api/v1 $OA $AN $OR
expect "anthropic host: ANTHROPIC_API_KEY on x-api-key"      0/16 anthropic https://api.anthropic.com $OA $AN $OR
expect "anthropic host: no ANTHROPIC key => error"           error/clean anthropic https://api.anthropic.com $OA $OR
expect "openrouter host, anthropic style: OPENROUTER"        0/15 anthropic https://openrouter.ai/api $OA $AN $OR
expect "plain http: no env key at all"                       error/clean openai http://127.0.0.1:1/v1 $OA $AN $OR
expect "lookalike host: no env key"                          error/clean openai https://api.openai.com.evil.test/v1 $OA $AN $OR
expect "unknown host: :api-key-env names the variable"       22/0 openai https://example.com/v1 $OA $AN $OR TN_KEY_ENV=TN_TEST_KEY TN_TEST_KEY=fake-custom-123

rm -f "$PROBE"
if [ $fail -eq 0 ]; then
  echo '{"check":"env-chain","gate":"OK"}'
else
  echo '{"check":"env-chain","gate":"FAILED"}'
  exit 1
fi
