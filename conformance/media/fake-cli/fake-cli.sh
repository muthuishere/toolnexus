#!/bin/sh
# Portable fake media CLI for SPEC §7G.11 (cli.json). Run as:
#   /bin/sh fake-cli.sh <fixture.jsonl> <out-dir> <client argv...>
# It records argv, cwd and $GROK_CONFIG under <out-dir>, then replays the fixture line by line.
# Directives (a line starting with "#"):
#   #file <relpath> <text>   create <out-dir>/<relpath> holding <text> (no trailing newline)
#   #exit <n>                stop now with exit code <n>
#   #hang                    exec sleep 600 (one process, so killing it ends the run)
# Any other line is printed with every "{out}" replaced by <out-dir>.
fixture="$1"
out="$2"
shift 2
mkdir -p "$out"
: > "$out/argv.txt"
for a in "$@"; do printf '%s\n' "$a" >> "$out/argv.txt"; done
pwd -P > "$out/cwd.txt"
printf '%s' "${GROK_CONFIG-}" > "$out/env-GROK_CONFIG.txt"
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    '#file '*)
      rest=${line#'#file '}
      rel=${rest%% *}
      text=${rest#* }
      mkdir -p "$(dirname "$out/$rel")"
      printf '%s' "$text" > "$out/$rel"
      ;;
    '#exit '*)
      exit "${line#'#exit '}"
      ;;
    '#hang')
      exec sleep 600
      ;;
    *)
      printf '%s\n' "$line" | sed "s|{out}|$out|g"
      ;;
  esac
done < "$fixture"
exit 0
