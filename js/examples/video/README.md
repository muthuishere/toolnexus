# Demo video kit

> **Note:** this is the *authentic live-capture* approach — a real editor typing real
> code. The **official animated launch video** (the one embedded on the docs site) is
> built with the reusable **`explainer-video`** skill (animated HTML scenes → Playwright
> → narration). This kit remains a valid, self-contained way to record a genuine session.

Everything needed to produce one ~70-second video: a real LazyVim session types the
toolnexus agent and runs it, with continuous narration, climaxing on the
suspend → ask → resume moment.

The editor is the **authenticity surface, not the subject** — brisk typing, the voice
tells the story, the terminal delivers the payoff.

## Files

| File | What |
|------|------|
| `agent.ts` | **The hero.** The ~45-line agent shown on camera — every tool source (MCP, skills, A2A, your own function, built-ins) into one toolkit + client, ending in a `question` suspension answered by `waitFor`. Reads as `from "toolnexus"`, runs with no API key. |
| `model.ts` | The deterministic plumbing `agent.ts` imports — a scripted local model + a served A2A "reviewer" agent — so every take is byte-identical. Never on camera. |
| `maketape.mjs` | Generates `demo.tape` from `agent.ts` (keeps them in sync; enters Vim paste mode so LazyVim autopairs/indent don't mangle the typing). |
| `demo.tape` | The VHS recording script (generated). |
| `vo.txt` | The exact voice-over text fed to TTS. |
| `narration.md` | The narration + storyboard (voice beat → screen). |
| `build.sh` | One command: record → narrate → mux → `demo-final.mp4`. |

## Produce it

```bash
cd js && npm run build          # dist/ must exist (agent.ts imports the built package)
cd examples/video
./build.sh                      # needs $ELEVENLABS_API_KEY; VOICE_ID=... to override the voice
```

Or step by step:

```bash
node maketape.mjs && vhs demo.tape        # -> demo.mp4 (silent, ~67s)
node agent.ts                             # sanity-check the run (prints the same output typed on screen)
```

## Knobs

- **Voice** — `VOICE_ID` env (default Sarah `EXAVITQu4vr4xnSDxMaL`). Candidates sampled in the
  session scratchpad: Sarah / Bella / Alice / Lily. Model `eleven_multilingual_v2`.
- **Pace** — `Set TypingSpeed` and the per-block `Sleep` in `maketape.mjs`. Typing currently
  spans ~40s and the run holds ~9s, totalling ~67s to match the ~68s voice-over. Change the
  voice-over length? Re-time the typing so they stay aligned (the voice should always lead).
- **Look** — theme / font / dimensions are `Set` lines at the top of `maketape.mjs`
  (`Catppuccin Mocha`, 1600×1000). Set a font that's installed for the tightest glyph spacing.
- **Music** — optional. Duck a bed under the voice with a second `ffmpeg` `sidechaincompress`
  pass on `demo-final.mp4`; left out of `build.sh` to keep the default clean.

## Why a scripted model

No API key, no cost, no network to a live LLM, and **identical output on every take** — so
re-recording never changes what's on screen. The model in `model.ts` walks a fixed five-step
plan (function → MCP → skill → A2A → suspension); the MCP `everything` server and the A2A
reviewer are real. This is the same spine as `../combined.ts`.
