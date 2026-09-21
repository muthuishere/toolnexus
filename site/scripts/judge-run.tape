# A REAL Classifier run, recorded. Regenerate with:
#
#   OPENROUTER_API_KEY=… vhs site/scripts/judge-run.tape
#
# The credential is inherited from the environment and is deliberately NOT in
# this file. The library reads it at the point of use and it appears in no
# output — which is why this recording can be published at all.
#
# It runs js/examples/judge.ts unmodified: one POST to the systemone wire over
# OpenRouter, three question types in a single round trip. The numbers that
# scroll past are whatever the backend answered that run — the same reason no
# test asserts a live number.

Output site/public/judge-run.webm

Require node

Set Shell zsh
Set FontSize 14
Set Width 1320
Set Height 340
Set Padding 22
Set Theme { "name": "toolnexus", "background": "#0d1117", "foreground": "#e6edf3", "cursor": "#3ddc84", "black": "#0d1117", "red": "#ff7b72", "green": "#3ddc84", "yellow": "#d29922", "blue": "#58a6ff", "magenta": "#bc8cff", "cyan": "#39c5cf", "white": "#e6edf3", "brightBlack": "#6e7681", "brightRed": "#ffa198", "brightGreen": "#56d364", "brightYellow": "#e3b341", "brightBlue": "#79c0ff", "brightMagenta": "#d2a8ff", "brightCyan": "#56d4dd", "brightWhite": "#f0f6fc" }

Set TypingSpeed 45ms

Hide
Type "cd js && clear" Enter
Show

Sleep 900ms
Type "node --experimental-strip-types examples/judge.ts"
Sleep 700ms
Enter
Sleep 9s
