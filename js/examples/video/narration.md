# Demo video — narration & storyboard

One ~70-second JavaScript video. A real LazyVim session types the toolnexus agent
(`agent.ts`) and runs it. Typing is sped up (native fast keystrokes, ~12 ms/key);
the **voice leads the typing** and never goes silent. The climax is the
suspend → ask → resume moment in the terminal.

The editor is the **authenticity surface, not the subject** — the viewer should feel
"a developer built this in a minute," not read every character.

## The voice-over (one continuous take — feed this to TTS verbatim)

> Every LLM gets smarter with tools. But every tool source speaks its own dialect —
> MCP servers, agent skills, a remote agent, your own functions. toolnexus makes them
> one thing.
>
> Point it at your MCP config, a folder of skills, a remote agent's card — and each
> one becomes a tool in a single toolkit. Then register a plain function of your own;
> it joins the same set, indistinguishable to the model.
>
> Wrap any model in a client, and its loop does the work — calling tools, feeding the
> results back, calling the next, until the answer is ready.
>
> And when a tool needs a human — an approval, a login, a decision — the loop doesn't
> fail. It suspends. It asks you. You answer... and it resumes, exactly where it left
> off.
>
> Five tool sources. One toolkit. One loop. And the same bytes in JavaScript, Python,
> Go, Java, and C#.
>
> toolnexus. Your LLM, with everything built in.

(~150 words ≈ 68 s at a calm 135 wpm. `eleven_multilingual_v2`.)

## Storyboard — voice beat → what's on screen

| # | Voice beat | Screen |
|---|-----------|--------|
| 1 | "Every LLM… one thing." | Empty LazyVim buffer; the imports type in. |
| 2 | "Point it at your MCP config… single toolkit." | `createToolkit({ mcpConfig, skillsDir, agents })` types in, block by block. |
| 3 | "Then register a plain function… indistinguishable." | `tk.register(defineTool({ name: "add", … }))` types in. |
| 4 | "Wrap any model in a client… until the answer is ready." | `createClient({ … })` + `client.run(...)` type in. |
| 5 | "And when a tool needs a human…" | `:wq`, then `node demo.ts`; tool calls stream in the terminal. |
| 6 | "It suspends. It asks you. You answer… and it resumes." | **Hold on** `⏸  agent asks: Ship it?` then the five-line tool-call summary lands. |
| 7 | "Five tool sources… everything built in." | Final line `…you chose to ship. Done.`; title card fades in. |

Voice starts ~0.5 s before the first keystroke and stays ahead of the typing the
whole way, so there is never dead air. Give beat 6 a real half-second breath around
"You answer…" — it's the only pause in the track, and it's the whole point.

## Voice pick (owner decision)

Four candidate female premade voices were sampled to
`scratchpad/voice-sample-{sarah,bella,alice,lily}.mp3`. Default: **Sarah**
(`EXAVITQu4vr4xnSDxMaL`) — calm, credible, product-narration timbre. Set the chosen
id in `build.sh` (`VOICE_ID`).
