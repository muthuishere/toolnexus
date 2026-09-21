// The loop: see a wave, plan it once, then execute the presses frame by frame.
//
//   node run.mjs --arm jev --runs 3
//   node run.mjs --arm shuffle      # control: Jev's numbers, permuted plans
//   node run.mjs --arm code         # the published baseline / fail-open path
//
// Serial by default. Parallelism, if it ever appears, lives INSIDE one request.
import { chromium } from "/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs"
import { writeFileSync } from "node:fs"
import { codeArm, codeRanker, deadlineFor, jevArm } from "./arms.mjs"
import { deck, describeWave, questions, timeToCommit, wave } from "./encode.mjs"
import { HUD_SOURCE } from "./hud.mjs"

const CHROME = "/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"

const argv = process.argv.slice(2)
const flag = (n, d) => { const i = argv.indexOf(`--${n}`); return i < 0 ? d : argv[i + 1] }
const has = (n) => argv.includes(`--${n}`)

const ARM = flag("arm", "jev")
const RUNS = Number(flag("runs", 3))
const MAX_MS = Number(flag("max-ms", 90_000))
// DIFFICULTY, and it is disclosed rather than quietly baked in. At the stock
// settings the game takes ~7 minutes to reach the speed where pterodactyls spawn
// (measured: 45 s of play only reaches speed 7.2; birds need 8.5). Left alone,
// every wave is one lone cactus and the deck has two options, so the judge picks
// `leap` 100% of the time and is never actually asked anything. `--speed` starts
// the run faster so birds and bunched waves appear within a demo-length run.
const START_SPEED = Number(flag("speed", 9))
const KEY = process.env.OPENROUTER_API_KEY

function buildArm() {
  switch (ARM) {
    case "code": return codeArm
    case "shuffle": return jevArm({ apiKey: KEY, control: "shuffle" })
    case "jev": return jevArm({ apiKey: KEY, model: flag("model", "typesafe/jev-1.13") })
    default: throw new Error(`unknown arm ${ARM}`)
  }
}

const READ = () => {
  const R = window.Runner?.instance_
  if (!R) return null
  return {
    crashed: !!R.crashed, playing: !!R.playing, speed: R.currentSpeed,
    distance: Math.round(R.distanceRan * 0.025),
    jumping: !!R.tRex?.jumping, ducking: !!R.tRex?.ducking,
    // Tag each obstacle object ONCE, in the page, so identity is the object
    // itself. Deriving an id from position is how you re-plan the same cactus.
    obstacles: (R.horizon?.obstacles || []).slice(0, 3).map((o) => ({
      id: (o.__jevId ??= (window.__jevOid = (window.__jevOid || 0) + 1)),
      xPos: Math.round(o.xPos), yPos: Math.round(o.yPos), width: o.width,
      type: o.typeConfig?.type || "?",
    })),
  }
}

const pct = (xs, q) => (xs.length ? xs.slice().sort((a, b) => a - b)[Math.min(xs.length - 1, Math.floor(xs.length * q))] : null)

async function playOne(page, arm, runIdx) {
  await page.goto("https://chromedino.com/", { waitUntil: "domcontentloaded", timeout: 30_000 })
  await page.waitForTimeout(2200)
  await page.evaluate(() => document.querySelectorAll('iframe,[id*="ad" i],[class*="ad-" i]').forEach((e) => e.remove()))
  await page.evaluate(HUD_SOURCE)
  await page.evaluate((n) => window.__hud.arm(n), arm.name)
  await page.keyboard.press("Space")
  await page.waitForTimeout(700)
  if (START_SPEED) await page.evaluate((v) => window.Runner.instance_.setSpeed(v), START_SPEED)

  const plans = []
  const seen = new Set()
  const t0 = Date.now()
  let last = null

  while (Date.now() - t0 < MAX_MS) {
    const s = await page.evaluate(READ)
    if (!s || s.crashed) break
    last = s
    await page.evaluate((m) => window.__hud.dist(m), s.distance)

    const group = wave(s.obstacles, s.speed, seen)
    if (!group) { await page.waitForTimeout(8); continue }
    for (const o of group) seen.add(o.id)

    const budget = deadlineFor(group, s.speed)
    if (budget <= 0) continue // already past the commit point; nothing to decide

    const { plans: legal, criteria } = deck(group, s.speed)
    const state = describeWave(group, s.speed)
    await page.evaluate(([st, c, b]) => window.__hud.begin(st, c, b), [state, criteria, budget])

    // PLAN ONCE, for the whole wave. The round trip overlaps the run-up.
    const d = await arm.decide(group, s.speed, { deadlineMs: budget })
    await page.evaluate((x) => window.__hud.answer(x), {
      chosen: d.chosen, probabilities: d.probabilities ?? null, runnerUp: d.runnerUp ?? null,
      confidence: d.confidence ?? null, latency: d.latency ?? null, nouls: d.nouls ?? null,
    })

    // EXECUTE. Code owns every press moment — one per obstacle, or one for the
    // whole wave when the plan spans it.
    const plan = legal[d.chosen]
    const steps = plan?.steps ?? []
    const targets = plan?.span ? [group[0]] : group
    let executed = 0, lateSteps = 0
    for (let i = 0; i < targets.length; i++) {
      const step = plan?.span ? "jump" : steps[i]
      const o = targets[i]
      let now = await page.evaluate(READ)
      const track = (w) => w?.obstacles?.find((x) => x.id === o.id) ?? null
      let live = track(now)
      if (!live || now.crashed) { lateSteps++; break }
      // Wait for the commit point AND for the feet to be on the ground. A Space
      // press while airborne is swallowed by the game, which is how a plan that
      // executed "successfully" still walks into the next cactus.
      while (live && !now.crashed && (timeToCommit(live, now.speed) > 16 || now.jumping)) {
        await page.waitForTimeout(8)
        now = await page.evaluate(READ)
        live = track(now)
      }
      if (now?.crashed) break
      if (!live) { lateSteps++; continue } // it went past while we waited
      if (step === "jump") { await page.keyboard.press("Space"); executed++ }
      else if (step === "duck") {
        await page.keyboard.down("ArrowDown"); await page.waitForTimeout(300)
        await page.keyboard.up("ArrowDown"); executed++
      }
    }

    const badge = d.outcome === "error" ? "ERROR" : d.outcome === "late" ? "LATE"
      : lateSteps ? "FORCED" : "APPLIED"
    await page.evaluate(([b, t]) => window.__hud.feed(b, t), [badge,
      `${d.chosen} · ${group.length} ahead · ${Math.round(d.latency ?? 0)} ms${d.error ? ` · ${d.error}` : ""}`])

    const done = plans.filter((x) => x.latencyMs != null).map((x) => x.latencyMs).sort((a, b) => a - b)
    await page.evaluate((t) => window.__hud.stats(t), `${plans.length + 1} waves · ` +
      (done.length ? `p50 ${done[Math.floor(done.length / 2)]} ms · ` : "") +
      `$${plans.reduce((a, x) => a + (x.cost ?? 0), 0).toFixed(4)}`)

    if (has("verbose")) console.error(`  wave#${group.map((g) => g.id).join("+")} ${group.map((g) => g.type + (g.type === "PTERODACTYL" ? `@y${g.yPos}` : "")).join(",")} budget=${Math.round(budget)} -> ${d.chosen} (${badge}) lat=${Math.round(d.latency ?? 0)} exec=${executed}/${targets.length}`)

    plans.push({
      run: runIdx, distance: s.distance, waveSize: group.length,
      obstacles: group.map((g) => g.type), budgetMs: Math.round(budget),
      outcome: badge.toLowerCase(), chosen: d.chosen, options: Object.keys(legal),
      // The code ranker's pick on the SAME wave. Not a ground truth — it is the
      // published baseline — but it is what "disagreed with the obvious move"
      // means, and it is the only number here the judge cannot influence.
      codePick: codeRanker(group, s.speed),
      latencyMs: d.latency != null ? Math.round(d.latency) : null,
      confidence: d.confidence ?? null, nouls: d.nouls ?? null,
      cost: d.cost ?? 0, error: d.error ?? null,
    })
  }

  const fin = await page.evaluate(READ)
  return { run: runIdx, distance: fin?.distance ?? last?.distance ?? 0, crashed: !!fin?.crashed, plans }
}

const browser = await chromium.launch({
  headless: has("headless"), executablePath: CHROME,
  args: ["--window-position=-1728,0", "--window-size=1800,1040"],
})
const page = await (await browser.newContext({ viewport: { width: 1780, height: 950 } })).newPage()
const arm = buildArm()

// Warm the connection before the first timed decision. Cold TLS cost 384 ms vs
// 331 ms warm; a cold first call would be recorded as the judge being slow when it
// is the handshake. (FEASIBILITY.md)
if (arm.judge?.ask) {
  const fake = [{ id: 0, xPos: 500, yPos: 90, width: 17, type: "CACTUS_SMALL" }]
  try { await arm.judge.ask(describeWave(fake, 7), questions(fake, 7)); console.error("connection warmed") }
  catch (e) { console.error("warm-up failed:", String(e.message).slice(0, 120)) }
}

const runs = []
for (let i = 1; i <= RUNS; i++) {
  runs.push(await playOne(page, arm, i))
  console.error(`run ${i}: distance ${runs.at(-1).distance}, ${runs.at(-1).plans.length} waves planned`)
}

const all = runs.flatMap((r) => r.plans)
const lat = all.map((t) => t.latencyMs).filter((x) => x != null)
const count = (o) => all.filter((t) => t.outcome === o).length
const report = {
  arm: arm.name,
  startSpeed: START_SPEED,
  model: ARM === "jev" || ARM === "shuffle" ? flag("model", "typesafe/jev-1.13") : null,
  runs: runs.map((r) => ({ run: r.run, distance: r.distance, waves: r.plans.length })),
  distance: { median: pct(runs.map((r) => r.distance), 0.5), best: Math.max(...runs.map((r) => r.distance)) },
  waves: all.length,
  outcomes: { applied: count("applied"), forced: count("forced"), late: count("late"), error: count("error") },
  // p95, not just the median — nobody in the corpus reports it.
  latencyMs: { p50: pct(lat, 0.5), p90: pct(lat, 0.9), p95: pct(lat, 0.95), max: lat.length ? Math.max(...lat) : null },
  budgetMs: { p10: pct(all.map((t) => t.budgetMs), 0.1), p50: pct(all.map((t) => t.budgetMs), 0.5) },
  confidence: { p50: pct(all.map((t) => t.confidence).filter((x) => x != null), 0.5) },
  agreesWithCode: {
    all: +(all.filter((t) => t.chosen === t.codePick).length / Math.max(1, all.length)).toFixed(3),
    // Cacti are near-forced (a two-option deck); birds are where a judgment happens.
    birds: (() => {
      const b = all.filter((t) => t.obstacles.includes("PTERODACTYL"))
      return b.length ? +(b.filter((t) => t.chosen === t.codePick).length / b.length).toFixed(3) : null
    })(),
    birdWaves: all.filter((t) => t.obstacles.includes("PTERODACTYL")).length,
  },
  cost: { total: +all.reduce((a, t) => a + (t.cost ?? 0), 0).toFixed(6) },
  plans: all,
}
const out = new URL(`./results/${ARM}.json`, import.meta.url).pathname
writeFileSync(out, JSON.stringify(report, null, 2))
console.log(JSON.stringify({ ...report, plans: `${all.length} waves -> ${out}` }, null, 2))
if (!has("keep-open")) await browser.close()
