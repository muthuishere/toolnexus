// Prose encoding for snake.
//
// Same bet as the dino build: the judge never sees a number. Not a coordinate,
// not a distance, not a square count, not a length. Code runs the flood fill,
// code measures the distance to the apple, code decides which moves are even
// legal — and hands over the CONCLUSION as a sentence.
//
// What is different here, and why this game was worth building: the tick is a
// full second. In the dino the round trip ate a third of the budget and the loop
// blocked, so what got measured was latency. Here latency is comfortably inside
// the budget by construction, so what gets measured is the judgment.

import { DIRS, OPPOSITE, W, H, bodyLength, foodDistance, freeSquares, legalMoves, room } from "./game.mjs"

// ---------------------------------------------------------------- styles
//
// The ablation. Two results so far point at the encoding rather than the model —
// the dino build gained 4x from rewriting ONE clause, and the shuffle control here
// dropped 17 apples to 1. Neither says anything about how much of the judgment
// lives in the words. These four styles are how you find out, on identical seeds
// with everything else held fixed:
//
//   prose   the encoding this repo argues for: no digits anywhere, options
//           described by consequence in one shared template
//   raw     the same facts as NUMBERS — coordinates, distances, square counts.
//           `pong-jev` ships this as a negative control and calls it "the
//           encoding the docs warn against"; nobody has published the result
//   labels  prose state, but the options are bare ids with NO description at all.
//           This is literally what `jev-plays-wordle` ships. It isolates one
//           question: do the option sentences carry the judgment, or the state?
//   stale   prose, except every option opens with the SAME room clause regardless
//           of the board — a clause that no longer tracks the state. A deliberate
//           reproduction of the dino bug, to see whether it costs what it cost there
export const STYLES = ["prose", "raw", "labels", "stale"]

/** Which way a move turns, relative to where you are already facing. Absolute
 *  compass directions would make the judge do geometry; "your left" does not. */
const TURN = {
  up:    { up: "carrying straight on", left: "turning to your left", right: "turning to your right" },
  down:  { down: "carrying straight on", right: "turning to your left", left: "turning to your right" },
  left:  { left: "carrying straight on", down: "turning to your left", up: "turning to your right" },
  right: { right: "carrying straight on", up: "turning to your left", down: "turning to your right" },
}
export const turnName = (heading, dir) => TURN[heading][dir] ?? "doubling back"

/** Where the apple lies, in words, relative to the way you are facing. */
function applyBearing(g) {
  if (!g.food) return "There is no apple on the board."
  const h = g.snake[0]
  const dx = g.food.x - h.x, dy = g.food.y - h.y
  const axis = { right: [dx, -dy], left: [-dx, dy], up: [-dy, -dx], down: [dy, dx] }[g.heading]
  const [fwd, side] = axis
  const far = Math.abs(fwd) + Math.abs(side)
  const near = far <= 3 ? "just there" : far <= 7 ? "a short way off" : "right across the board"
  const along = fwd > 1 ? "ahead of you" : fwd < -1 ? "behind you" : "level with you"
  const across = side > 1 ? "and off to your left" : side < -1 ? "and off to your right" : "and straight in line"
  return `The apple is ${near}, ${along} ${across}.`
}

/** How much of the board is still yours, said without counting anything. */
function openness(g) {
  const best = Math.max(0, ...legalMoves(g).map((d) => room(g, d)))
  const free = freeSquares(g), len = bodyLength(g)
  return best >= free * 0.85 ? "Almost the whole board is still open to you."
    : best >= len * 3 ? "You have plenty of open ground around you."
    : best >= len ? "The space around you is getting tight."
    : "You are nearly boxed in."
}

function stature(g) {
  const len = bodyLength(g)
  return len < 8 ? "You are still short, so your own body is barely in the way."
    : len < 18 ? "You have grown long enough that your own body is now the thing to watch."
    : "You are very long, and almost every hazard on the board is your own tail."
}

export function describeBoard(g, style = "prose") {
  if (style === "raw") {
    // Every number code was careful never to say. Same facts, no prose.
    const h = g.snake[0]
    return {
      head: `x=${h.x},y=${h.y}`,
      food: g.food ? `x=${g.food.x},y=${g.food.y}` : "none",
      heading: g.heading,
      length: String(bodyLength(g)),
      free_squares: String(freeSquares(g)),
      board: `${W}x${H}`,
    }
  }
  return { apple: applyBearing(g), space: openness(g), size: stature(g) }
}

// --------------------------------------------------------------- the deck
//
// Every option uses the IDENTICAL sentence template. Consequence first (what the
// move leaves you), the apple second, the turn named last — because which way you
// turn is the least important fact about a move. The dino build proved what
// happens when one clause in this template contradicts the state: the judge
// answered coherently with what it was told, and what it was told was wrong.

const roomPhrase = (g, dir) => {
  const r = room(g, dir), free = freeSquares(g), len = bodyLength(g)
  return r === 0 ? "Leaves you nowhere at all to go"
    : r >= free * 0.85 ? "Keeps the whole open board in front of you"
    : r >= len * 3 ? "Leaves you plenty of room to keep moving"
    : r >= len ? "Leaves you just about enough room to turn around in"
    : "Shuts you into a pocket smaller than your own body"
}

const applePhrase = (g, dir) => {
  const now = g.food ? Math.abs(g.snake[0].x - g.food.x) + Math.abs(g.snake[0].y - g.food.y) : null
  const then = foodDistance(g, dir)
  if (now == null || then == null) return "and there is no apple to chase"
  return then < now ? "and it carries you toward the apple"
    : then > now ? "and it carries you away from the apple"
    : "and it leaves you no nearer the apple"
}

export function deck(g, style = "prose") {
  const ids = legalMoves(g)
  const criteria = {}
  for (const d of ids) {
    criteria[d] =
      style === "raw"
        ? `room=${room(g, d)} food_distance=${foodDistance(g, d) ?? "na"} direction=${d}`
      : style === "labels"
        ? d // no description at all — the jev-plays-wordle shape
      : style === "stale"
        // The clause is fixed. It is true often enough to look fine and wrong
        // exactly when it matters, which is what made the dino version so costly.
        ? `Leaves you plenty of room to keep moving, ${applePhrase(g, d)}. That is ${turnName(g.heading, d)}.`
      : `${roomPhrase(g, d)}, ${applePhrase(g, d)}. That is ${turnName(g.heading, d)}.`
  }
  return { ids, criteria }
}

export function questions(g, style = "prose") {
  const { criteria } = deck(g, style)
  return {
    move: {
      type: "choice",
      instructions:
        "You are a snake winding around a walled board, and you get longer every time " +
        "you eat an apple. Pick the move that keeps you alive and fed.",
      criteria,
    },
    // Speculative nouls — same round trip, no extra call. They feed the HUD and
    // the calibration question: does the judge KNOW when it is in trouble?
    is_cornered: {
      type: "noul",
      instructions: "You are running out of room and are about to have nowhere left to go.",
      criteria: { true: "You are nearly trapped.", false: "You still have room to work with." },
    },
    should_chase: {
      type: "noul",
      instructions: "Going after the apple right now is worth the risk it costs you.",
      criteria: { true: "Chase it.", false: "Play safe and wait for a better line." },
    },
  }
}
