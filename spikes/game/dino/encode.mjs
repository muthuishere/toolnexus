// Prose encoding for the dino runner.
//
// THE BET, stated once: the judge is never shown a number. Not a pixel, not a
// millisecond, not a speed, not a count. It only ever sees words. Every piece of
// arithmetic — time-to-impact, the commit point, whether one leap can span two
// obstacles, when each key goes down — is computed HERE, in code.
//
// THE SECOND BET, which is what makes it work at speed: the judge is asked about
// a WAVE, not an obstacle. Obstacles arrive in clusters; a decision made at the
// moment one spawns is about a world that has moved on by the time you can act on
// it (the dino may still be airborne from the previous jump). So code groups the
// obstacles that arrive together, enumerates the legal PLANS for the whole group,
// and asks once. Code then executes the plan frame by frame. Latency is hidden in
// the gap between planning a wave and reaching it, not chased per press.

export const DINO_X = 44 // px: where the dino's hitbox sits. Code's business only.

// MEASURED, not assumed: obstacle pixels per second = currentSpeed x 54.7, over 11
// samples on this machine. The first spike used `speed x (1000/60)`, the wrong
// unit, which inflated every lead time by ~3.3x — see README, "the correction".
export const PX_PER_SPEED = 54.7

// MEASURED by sweep (../../results, README "the correction"): pressing at ~80-120 px
// of remaining travel survives 180-256 m; at 150 px the dino lands before the cactus
// arrives and dies at ~65 m. A jump reaches clearing height ~100 px before impact, and the dino is
// off the ground for ~600 ms all told. Both are code's constants; neither is ever
// shown.
export const COMMIT_PX = 100
export const AIRBORNE_MS = 600

export const pxPerMs = (speed) => (speed * PX_PER_SPEED) / 1000
export const timeToImpact = (o, speed) => (o.xPos - DINO_X) / pxPerMs(speed)
export const timeToCommit = (o, speed) => (o.xPos - DINO_X - COMMIT_PX) / pxPerMs(speed)

/** The wave: the leading obstacle plus anything arriving close enough behind it
 *  that the same plan has to cover both. Code decides what "close enough" means. */
export function wave(obstacles, speed, seen) {
  const fresh = obstacles.filter((o) => !seen.has(o.id))
  if (!fresh.length) return null
  const head = fresh[0]
  const group = [head]
  for (const o of fresh.slice(1)) {
    const gap = timeToImpact(o, speed) - timeToImpact(group.at(-1), speed)
    if (gap < AIRBORNE_MS * 1.6) group.push(o)
    else break
  }
  return group
}

// --------------------------------------------------------------- vocabulary

const nearness = (ms) =>
  ms < 250 ? "it is already on top of you"
  : ms < 600 ? "it is about to reach you"
  : ms < 1200 ? "it is closing fast"
  : "it is still some way off"

const pace = (speed) =>
  speed < 7 ? "the ground is rolling past at a walk"
  : speed < 9.5 ? "the ground is moving briskly"
  : speed < 12 ? "the ground is racing past"
  : "the ground is a blur"

/** What it is. Birds are described by the height they fly at, never a coordinate. */
export function shape(o) {
  if (o.type === "PTERODACTYL") {
    return o.yPos >= 95 ? "a bird skimming along at knee height"
      : o.yPos >= 70 ? "a bird flying straight at your chest"
      : "a bird gliding past well above your head"
  }
  const wide = o.width > 30
  return o.type === "CACTUS_LARGE"
    ? (wide ? "a row of tall cactus" : "a single tall cactus")
    : (wide ? "a thicket of low cactus" : "a single low cactus")
}

const ordinal = ["the first", "the second", "the third", "the fourth"]

/** The state. Sentences only — the wave is narrated in the order it arrives. */
export function describeWave(group, speed) {
  const lead = group[0]
  const rest = group.slice(1).map((o, i) => {
    const gap = timeToImpact(o, speed) - timeToImpact(group[i], speed)
    const how = gap < 350 ? "immediately behind it" : gap < 550 ? "close behind it" : "a little further back"
    return `Then ${shape(o)}, ${how}.`
  })
  return {
    ahead: `${shape(lead)}, and ${nearness(timeToImpact(lead, speed))}.`,
    wave: rest.length
      ? rest.join(" ")
      : "Behind it the track is clear for a while.",
    pace: `${pace(speed)}.`,
  }
}

// --------------------------------------------------------------- the deck
//
// `jev-tetris`'s rule, in spirit: every option uses the IDENTICAL sentence
// template, because equivalent wordings are not guaranteed to produce equivalent
// judgements. Consequence first, the plan named last.

const TEMPLATE = (consequence, cost, label) => `${consequence}. ${cost}. That is ${label}.`

// The option sentences must use THE SAME vocabulary as the state sentences, or an
// option quietly reads as irrelevant. First version said a leap goes "over
// whatever is STANDING in your way" — nothing is standing when a bird is coming,
// so the judge crouched at every bird, including the knee-high one that is fatal
// to crouch under. Heights here ("knee height", "chest height", "above your head")
// are the exact words `shape()` uses. This is the `jev-tetris` lesson: the biggest
// measured win in that whole corpus was deleting one self-contradictory sentence.
const describePlan = (steps) => {
  const names = { jump: "a leap", duck: "a crouch", run: "holding your stride" }
  if (steps.length === 1) {
    const s = steps[0]
    return s === "jump"
      ? TEMPLATE("You spring up and pass over anything on the ground or down at knee height",
          "For a moment you are in the air and cannot change your mind", "one leap")
      : s === "duck"
      ? TEMPLATE("You flatten yourself and pass under anything coming at chest height or higher",
          "You stay low, so anything on the ground or at knee height would stop you dead", "one crouch")
      : TEMPLATE("You hold your stride and let it come, which is right when it will pass well above your head",
          "If it is actually in your path you run straight into it", "doing nothing")
  }
  const joined = steps.map((s) => names[s]).join(", then ")
  return TEMPLATE(
    `You take them one at a time: ${joined}`,
    "Each move has to land before the next thing arrives, so there is no room to change your mind partway",
    joined)
}

/** The provably-legal deck for a wave. Code decides legality; the judge only ranks.
 *  `hold` is ALWAYS present: there is always a do-nothing escape hatch. */
export function deck(group, speed) {
  const plans = {}
  const add = (id, steps) => { plans[id] = { steps, text: describePlan(steps) } }

  if (group.length === 1) {
    const o = group[0]
    if (o.type === "PTERODACTYL") { add("leap", ["jump"]); add("crouch", ["duck"]) }
    else add("leap", ["jump"])
    add("hold", ["run"])
  } else {
    // One leap spans both only if the second arrives while still airborne — pure
    // arithmetic, so code answers it and never asks.
    const gap = timeToImpact(group[1], speed) - timeToImpact(group[0], speed)
    const allGround = group.every((o) => o.type !== "PTERODACTYL")
    if (allGround && gap < AIRBORNE_MS * 0.55) {
      plans.one_leap = { steps: ["jump"], span: true,
        text: TEMPLATE("You time a single leap so that you are still in the air when the second one passes underneath",
          "If you mistime it you come down on top of one of them", "one leap for both") }
    }
    add("leap_leap", group.map(() => "jump"))
    if (group.some((o) => o.type === "PTERODACTYL")) {
      add("mixed", group.map((o) => (o.type === "PTERODACTYL" && o.yPos < 90 ? "duck" : "jump")))
    }
    add("hold", group.map(() => "run"))
  }
  return { ids: Object.keys(plans), plans, criteria: Object.fromEntries(Object.entries(plans).map(([k, v]) => [k, v.text])) }
}

/** The questions. One `choice` is the spine; the nouls ride free in the same
 *  round trip (fan-out: many questions, one request, one state ingest). */
export function questions(group, speed) {
  const { criteria } = deck(group, speed)
  return {
    plan: {
      type: "choice",
      instructions:
        "You are running along a flat track and there are things in the way ahead. " +
        "Pick the plan that gets you past all of them in one piece.",
      criteria,
    },
    is_urgent: {
      type: "noul",
      instructions: "The first thing ahead has to be dealt with this instant, not in a moment.",
      criteria: { true: "There is no time left to wait.", false: "There is still room to wait." },
    },
    is_crowded: {
      type: "noul",
      instructions: "The things ahead arrive so close together that one move cannot cover them all.",
      criteria: { true: "They are bunched up.", false: "They are well spread out." },
    },
  }
}
