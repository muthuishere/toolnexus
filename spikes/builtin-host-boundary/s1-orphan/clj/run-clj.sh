#!/usr/bin/env bash
cd "$(dirname "$0")"
exec clojure -M -m probe "$@"
