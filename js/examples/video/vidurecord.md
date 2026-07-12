# vidurecord — how the demo video is made (everything)

The full record of producing the toolnexus launch video: the pipeline, every
gotcha we hit, the exact commands, and the knobs. If you re-record months from now,
read this first.

## What the video is

One ~67-second JavaScript video. A real **LazyVim** session types the toolnexus
agent (`agent.ts`) and runs it, with continuous female narration (ElevenLabs
**Sarah**). Climax = the `question` **suspend → ask → resume** moment in the terminal.

The editor is the **authenticity surface, not the subject**: brisk typing, the voice
carries the story, the terminal delivers the payoff.

## The parts

| File | Role | Committed? |
|------|------|-----------|
| `agent.ts` | **The hero** shown on camera (~48 lines). Every tool source → one toolkit + client, ending in a `question` suspension answered by `waitFor`. Reads `from "toolnexus"`, runs with **no API key**. | ✅ source |
| `model.ts` | Deterministic plumbing `agent.ts` imports — scripted local model + a real served A2A reviewer. Same spine as `../combined.ts`. Never on camera. | ✅ source |
| `maketape.mjs` | Generates `demo.tape` **from `agent.ts`** so they never drift. | ✅ source |
| `vo.txt` | The exact voice-over text fed to TTS. | ✅ source |
| `narration.md` | Storyboard: voice beat → screen. | ✅ source |
| `build.sh` | One command: record → narrate → mux → `demo-final.mp4`. | ✅ source |
| `demo.tape` | Generated VHS script. | 🚫 gitignored (derived) |
| `demo.ts` | The file typed live on camera (a copy of `agent.ts`). | 🚫 gitignored (derived) |
| `demo.mp4` | Silent screen capture. | 🚫 gitignored |
| `vo.mp3` | ElevenLabs narration. | 🚫 gitignored |
| `demo-final.mp4` | **The finished video** (video + voice). | 🚫 gitignored |

## Produce it

```bash
cd js && npm run build            # dist/ must exist — agent.ts imports the built package
cd examples/video
ELEVENLABS_API_KEY=… ./build.sh   # → demo-final.mp4   (VOICE_ID=… to override the voice)
```

Video only (no voice, no spend):

```bash
rm -f demo.ts && node maketape.mjs && vhs demo.tape   # → demo.mp4
node agent.ts                                         # sanity-check the run
```

Re-mux without re-synthesizing the voice (reuse `vo.mp3`):

```bash
VDUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 demo.mp4)
ADUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 vo.mp3)
PAD=$(python3 -c "print(max(0.5, $ADUR - $VDUR + 0.5))")
ffmpeg -y -i demo.mp4 -i vo.mp3 \
  -filter_complex "[0:v]tpad=stop_mode=clone:stop_duration=$PAD[v]" \
  -map "[v]" -map "1:a" -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest \
  demo-final.mp4
```

## Gotchas we hit (each cost a take — don't relearn them)

1. **Font: wide, ugly letter-spacing.** VHS couldn't find `JetBrains Mono` (not
   installed) and fell back to a fat-tracking font. Fix: use the **exact installed
   family** — here `JetBrainsMono Nerd Font Mono` (check with
   `fc-list : family | grep -i jetbrains`). Set it in `maketape.mjs`'s `Set FontFamily`.

2. **The file typed twice (duplication).** The tape does `nvim demo.ts`; if `demo.ts`
   survived a previous take, nvim opened it **with content** and typed a second copy on
   top. Fix (belt **and** suspenders): the tape clears the buffer first (`:%d` before
   `:set paste`), and `build.sh` does `rm -f demo.ts` before recording.

3. **VHS has no string escaping.** You can't `\"` inside a `Type "…"`. Fix: `maketape.mjs`
   wraps each line in a delimiter the line does **not** contain (`` ` `` → `"` → `'`), and
   `agent.ts` is written so **no single line needs all three**.

4. **VHS silently drops `→` (U+2192).** The arrow just vanished from comments and output.
   Fix: use ASCII `->`. (`•`, `⏸`, `…` all type fine — only that glyph fails.)

5. **`\n` inside a `Type "…"` becomes a real Enter.** A literal `\n` in the typed source
   would split the line mid-statement. Fix: no literal `\n` in `agent.ts`'s on-camera lines.

6. **LazyVim autopairs / autoindent fight literal typing.** Typing `{` yields `{}`, indent
   compounds. Fix: enter Vim **paste mode** (`:set paste`) before typing — it disables
   insert-mode mappings and autoindent, so keystrokes land verbatim.

7. **The process wouldn't exit** (mock servers keep the event loop alive). Fix: `model.ts`
   `.unref()`s every server; `agent.ts` ends with `tk.close()` + `reviewer.stop()`.

## Timing

`Set TypingSpeed 28ms` + a `Sleep 900ms` beat at each blank line → typing spans ~40 s;
the run holds ~9 s → **~67 s total**, matched to the ~66–68 s voice-over. If you change the
VO length, re-time the typing so the **voice always leads**. The mux freezes the last frame
(the "Done." payoff) to cover any remainder.

## Voice

ElevenLabs, `eleven_multilingual_v2`. Default **Sarah** `EXAVITQu4vr4xnSDxMaL` (override with
`VOICE_ID=…`). Candidates were sampled to the session scratchpad: Sarah / Bella / Alice / Lily.
The key is read from `$ELEVENLABS_API_KEY` at curl time and never printed or written to a file.

## Why a scripted model (not a live LLM)

No API key, no cost, no network to a model, and **identical output on every take** — so
re-recording never changes what's on screen. `model.ts` walks a fixed five-step plan
(function → MCP → skill → A2A → suspension); the MCP `everything` server and the A2A reviewer
are **real**.
