#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest
set -euo pipefail
cd "$(dirname "$0")"
cargo build -q --bin spike
exec ./target/debug/spike "$@"
