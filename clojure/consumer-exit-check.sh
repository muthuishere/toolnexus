#!/usr/bin/env bash
# consumer-exit-check — does this LIBRARY let its consumer's process exit?
#
#   ./consumer-exit-check.sh     exit 0 = pass
#
# WHY THIS IS NOT A UNIT TEST, and why the unit tests could never have caught
# the bug it exists for.
#
# The suite owns its process and calls (shutdown-agents) at the end, because a
# test runner is an application and may decide when it exits. That single line
# hid a real defect for the whole life of this port: three library sites used
# `future`, whose JVM pool threads are non-daemon with a 60-second keep-alive,
# so a CONSUMER — which cannot call shutdown-agents from its own library code
# and should not have to — sat there for a full minute after printing its
# answer. 154 tests, 707 assertions, all green at 7.4s throughout.
#
# The property is "the process exits promptly", and it is not observable from
# inside the process. So it is measured from outside, on the only thing that
# proves it: the wall clock of a program that uses the library and returns.
#
# Measured 2026-08-01, same output both times, JVM:
#   with `future`                        1:01.62
#   with koine.process/run-async!        0:01.19
#
# The probe drives the real client loop with TWO parallel tool calls, so it
# exercises client/execute-calls specifically. It calls no shutdown-agents, by
# design — that is the whole point.
#
# PROVEN TO FAIL: run against the pre-fix tree (the three sites still on
# `future`) it reports 61s against a 20s budget and exits 1, with the diagnosis
# in the message. A check nobody has watched fail is decoration.
set -uo pipefail
cd "$(dirname "$0")"

BUDGET_S=${BUDGET_S:-20}   # generous: the honest run is ~1s, the bug is ~61s
PROBE=$(mktemp /tmp/tn-consumer-probe-XXXX.cljc)
trap 'rm -f "$PROBE"' EXIT

cat > "$PROBE" <<'CLJ'
;; A CONSUMER of the library: one turn, two parallel tool calls, print, exit.
;; It deliberately does NOT call shutdown-agents.
(require '[toolnexus.client :as client]
         '[toolnexus.tool :as tool]
         '[koine.json :as json]
         '[koine.server :as server])

(def n (atom 0))
(def srv
  (server/serve
   (fn [_req]
     {:status 200 :headers {"content-type" "application/json"}
      :body (json/write-str
             (if (= 1 (swap! n inc))
               {:choices [{:message {:role "assistant" :tool_calls
                                     [{:id "a" :type "function"
                                       :function {:name "upper" :arguments "{\"text\":\"x\"}"}}
                                      {:id "b" :type "function"
                                       :function {:name "upper" :arguments "{\"text\":\"y\"}"}}]}}]}
               {:choices [{:message {:role "assistant" :content "done"}}]}))})
   {:port 0}))

(def tools [(tool/tool {:name "upper" :description "up"
                        :execute (fn [args] (tool/success (str (:text args))))})])

(let [c (client/create-client {:base-url (str "http://127.0.0.1:" (server/port srv))
                               :model "m" :api-key "unused"})
      r (client/run c "hi" {:toolkit (tool/toolkit tools)})]
  (println "text:" (:text r) "tool-calls:" (:tool-call-count r)))
(server/stop! srv)
(println "consumer done")
CLJ

DEPS='{:paths ["src"] :deps {org.clojure/clojure {:mvn/version "1.12.5"} net.clojars.muthuishere/koine {:mvn/version "0.9.0"}}}'

start=$(date +%s)
out=$(clojure -Sdeps "$DEPS" -M "$PROBE" 2>&1)
elapsed=$(( $(date +%s) - start ))

fail=0
case "$out" in
  *"consumer done"*) ;;
  *) echo "FAIL: probe did not complete" >&2; echo "$out" >&2; fail=1 ;;
esac
case "$out" in
  *"tool-calls: 2"*) ;;
  *) echo "FAIL: both parallel tool calls did not run — the probe is not exercising the path" >&2; fail=1 ;;
esac
if [ "$elapsed" -gt "$BUDGET_S" ]; then
  echo "FAIL: consumer took ${elapsed}s (budget ${BUDGET_S}s)." >&2
  echo "      The library is holding the consumer's process open — look for \`future\`" >&2
  echo "      in library code and use koine.process/run-async! instead." >&2
  fail=1
fi

printf '{"check":"consumer-exit","seconds":%s,"budget":%s,"gate":"%s"}\n' \
  "$elapsed" "$BUDGET_S" "$([ $fail -eq 0 ] && echo OK || echo FAILED)"
exit $fail
