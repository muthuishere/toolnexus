// The loop. One decision per tick, one full second per tick.
//
//   node run.mjs --arm jev --games 3
//   node run.mjs --arm code --games 3           # baseline, no API calls
//   node run.mjs --arm shuffle --games 3        # control: Jev's numbers, permuted
//   node run.mjs --arm jev --ui                 # watch it in a browser
//   node run.mjs --arm jev --ui --shot x.png --out scratch   # illustrate, don't clobber
//   node run.mjs --arm jev --style raw --out style-raw       # the encoding ablation
//
// Every arm plays the SAME seeds, so the boards and apple placements are
// identical across arms and the only variable is the ranker.
import { writeFileSync } from "node:fs"
import { codeArm, codeRanker, jevArm } from "./arms.mjs"
import { deck, describeBoard, questions } from "./encode.mjs"
import { bodyLength, legalMoves, newGame, room, step } from "./game.mjs"

const argv = process.argv.slice(2)
const flag = (n, d) => { const i = argv.indexOf(`--${n}`); return i < 0 ? d : argv[i + 1] }
const has = (n) => argv.includes(`--${n}`)

const ARM = flag("arm", "jev")
const GAMES = Number(flag("games", 3))
const TICK_MS = Number(flag("tick", 1000))   // the point of this game: a full second
const MAX_MOVES = Number(flag("max-moves", 300))
const SEED0 = Number(flag("seed", 1))
// The encoding under test. Everything else — seeds, board, deck membership, the
// baseline, the tick — is held fixed, so a difference between styles is a
// difference the WORDS made.
const STYLE = flag("style", "prose")
const KEY = process.env.OPENROUTER_API_KEY

const arm = ARM === "code" ? codeArm
  : ARM === "shuffle" ? jevArm({ apiKey: KEY, control: "shuffle", style: STYLE })
  : ARM === "jev" ? jevArm({ apiKey: KEY, model: flag("model", "typesafe/jev-1.13"), style: STYLE })
  : (() => { throw new Error(`unknown arm ${ARM}`) })()

// ------------------------------------------------------------------ optional UI
let ui = null
if (has("ui")) {
  const { chromium } = await import("/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs")
  const { PAGE } = await import("./view.mjs")
  const CHROME = "/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  const browser = await chromium.launch({ headless: has("headless"), executablePath: CHROME,
    args: ["--window-position=-1728,0", "--window-size=1500,1000"] })
  const page = await (await browser.newContext({ viewport: { width: 1480, height: 920 } })).newPage()
  await page.setContent(PAGE)
  ui = { browser, page, call: (fn, arg) => page.evaluate(([f, a]) => window.__v[f](a), [fn, arg]).catch(() => {}) }
  await ui.call("arm", arm.name)
}

const pct = (xs, q) => (xs.length ? xs.slice().sort((a, b) => a - b)[Math.min(xs.length - 1, Math.floor(xs.length * q))] : null)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function playOne(seed) {
  const g = newGame(seed)
  const ticks = []
  while (!g.dead && g.moves < MAX_MOVES) {
    const ids = legalMoves(g)
    if (!ids.length) { g.dead = "no legal move left"; break }

    const state = describeBoard(g, STYLE)
    const { criteria } = deck(g, STYLE)
    const tickStart = Date.now()
    if (ui) await ui.call("board", { snake: g.snake, food: g.food, apples: g.apples, moves: g.moves, dead: g.dead })
    if (ui) await ui.call("begin", { state, criteria, budget: TICK_MS })

    const d = ids.length === 1
      // One legal move is not a decision. No call is made, and the record says so —
      // otherwise a forced move inflates the "it agreed with the baseline" number.
      ? { arm: arm.name, chosen: ids[0], outcome: "forced" }
      : await arm.decide(g, { deadlineMs: TICK_MS })

    if (ui) await ui.call("answer", {
      chosen: d.chosen, probabilities: d.probabilities ?? null, runnerUp: d.runnerUp ?? null,
      confidence: d.confidence ?? null, latency: d.latency ?? null, nouls: d.nouls ?? null,
      outcome: d.outcome,
    })

    ticks.push({
      seed, move: g.moves, length: bodyLength(g), legal: ids.length,
      outcome: d.outcome, chosen: d.chosen, codePick: codeRanker(g),
      roomAfter: room(g, d.chosen),
      latencyMs: d.latency != null ? Math.round(d.latency) : null,
      confidence: d.confidence ?? null, nouls: d.nouls ?? null,
      cost: d.cost ?? 0, error: d.error ?? null,
    })

    step(g, d.chosen)
    if (has("verbose")) console.error(`  seed${seed} m${g.moves} len${bodyLength(g)} ${ids.length} legal -> ${d.chosen} (${d.outcome}) ${Math.round(d.latency ?? 0)}ms`)

    // Hold the full tick. A decision that came back in 365 ms waits out the rest;
    // the budget is the budget whether or not it was used.
    const left = TICK_MS - (Date.now() - tickStart)
    if (left > 0 && (ui || has("realtime"))) await sleep(left)
  }
  // Only re-render on an actual death. The board is otherwise drawn at the START
  // of a tick so that the grid on screen is the exact position the sentences on
  // the right describe; a final post-loop redraw would leave the panel one move
  // behind the board in every screenshot.
  if (ui && g.dead) await ui.call("board", { snake: g.snake, food: g.food, apples: g.apples, moves: g.moves, dead: g.dead })
  return { seed, apples: g.apples, moves: g.moves, dead: g.dead ?? "hit the move cap", ticks }
}

if (arm.judge?.ask) {
  // Warm the connection so the first tick is not measuring a TLS handshake.
  const g = newGame(999)
  try { await arm.judge.ask(describeBoard(g, STYLE), questions(g, STYLE)); console.error("connection warmed") }
  catch (e) { console.error("warm-up failed:", String(e.message).slice(0, 120)) }
}

const games = []
for (let i = 0; i < GAMES; i++) {
  games.push(await playOne(SEED0 + i))
  const g = games.at(-1)
  console.error(`seed ${g.seed}: ${g.apples} apples, ${g.moves} moves, ${g.dead}`)
}

const all = games.flatMap((g) => g.ticks)
const judged = all.filter((t) => t.outcome !== "forced")
const lat = all.map((t) => t.latencyMs).filter((x) => x != null)
const cnt = (o) => all.filter((t) => t.outcome === o).length
const report = {
  arm: arm.name, style: STYLE, tickMs: TICK_MS,
  model: ARM === "jev" || ARM === "shuffle" ? flag("model", "typesafe/jev-1.13") : null,
  games: games.map((g) => ({ seed: g.seed, apples: g.apples, moves: g.moves, died: g.dead })),
  apples: { median: pct(games.map((g) => g.apples), 0.5), best: Math.max(...games.map((g) => g.apples)) },
  moves: { median: pct(games.map((g) => g.moves), 0.5), best: Math.max(...games.map((g) => g.moves)) },
  decisions: { total: all.length, judged: judged.length, forced: cnt("forced") },
  outcomes: { applied: cnt("applied"), late: cnt("late"), error: cnt("error") },
  // The honest agreement number: forced moves are excluded, because a one-option
  // deck agrees with everything.
  agreesWithCode: judged.length
    ? +(judged.filter((t) => t.chosen === t.codePick).length / judged.length).toFixed(3) : null,
  // Did it ever pick a move that shuts it into a pocket smaller than its own body?
  selfTrappingMoves: judged.filter((t) => t.roomAfter < t.length).length,
  latencyMs: { p50: pct(lat, 0.5), p90: pct(lat, 0.9), p95: pct(lat, 0.95), max: lat.length ? Math.max(...lat) : null },
  budgetUsed: lat.length ? +(pct(lat, 0.95) / TICK_MS).toFixed(3) : null,
  confidence: { p50: pct(all.map((t) => t.confidence).filter((x) => x != null), 0.5) },
  cost: { total: +all.reduce((a, t) => a + (t.cost ?? 0), 0).toFixed(6) },
  ticks: all,
}
// `--out` exists because a short `--ui --shot` run once clobbered the 3-game
// measurement file it was illustrating. A screenshot run is not a measurement.
const out = new URL(`./results/${flag("out", ARM)}.json`, import.meta.url).pathname
writeFileSync(out, JSON.stringify(report, null, 2))
console.log(JSON.stringify({ ...report, ticks: `${all.length} ticks -> ${out}` }, null, 2))
if (ui && flag("shot", null)) await ui.page.screenshot({ path: flag("shot", null) })
if (ui && !has("keep-open")) await ui.browser.close()
