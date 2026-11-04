#!/bin/sh
# Hermetic stand-in for a one-shot agent CLI (SPEC §8C conformance).
# The first argument selects the behaviour; every port's suite drives it.
mode=$1; shift
case "$mode" in
  argv)   cat >/dev/null; printf '{"content":"%s"}' "$*" ;;
  file)   cat "$1" ;;
  stdin)  cat ;;
  arg)    printf '%s' "$1" ;;
  out)    out=$1; shift; cat >/dev/null; printf '{"content":"%s"}' "$*" > "$out"; printf 'noise on stdout' ;;
  loop)
    if [ "$1" = "-f" ]; then p=$(cat "$2"); else p=$(cat); fi
    case "$p" in
      *'"role":"tool"'*) printf 'Sure.\n```json\n{"content":"finished"}\n```\n' ;;
      *) printf '{"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":"{\\"a\\":2,\\"b\\":3}"}}]}' ;;
    esac ;;
  fail)   cat >/dev/null; printf 'bad thing\n' >&2; exit 3 ;;
  sleep)  sleep 5 ;;
  empty)  cat >/dev/null ;;
  env)    cat >/dev/null; printf '{"content":"%s"}' "$TN_CLI_FAKE" ;;
  *)      printf 'unknown mode %s\n' "$mode" >&2; exit 64 ;;
esac
