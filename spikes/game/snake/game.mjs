// Pure snake. No DOM, no browser, no timing physics — the board is a data
// structure and a move is a function. That is deliberate: the dino build lost an
// hour to jump physics, which taught nothing about judgment. Here every run is
// seeded, so two arms play the IDENTICAL sequence of boards and food placements
// and the only difference between them is who chose the moves.

export const W = 12, H = 12

/** mulberry32 — small, seeded, and the reason arms are comparable at all. */
export function rng(seed) {
  let a = seed >>> 0
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] }
export const OPPOSITE = { up: "down", down: "up", left: "right", right: "left" }

export function newGame(seed) {
  const rand = rng(seed)
  const g = {
    rand, seed,
    snake: [{ x: 5, y: 6 }, { x: 4, y: 6 }, { x: 3, y: 6 }], // head first
    heading: "right",
    apples: 0, moves: 0, dead: null, food: null,
  }
  g.food = placeFood(g)
  return g
}

export function placeFood(g) {
  const free = []
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++)
    if (!g.snake.some((s) => s.x === x && s.y === y)) free.push({ x, y })
  return free.length ? free[Math.floor(g.rand() * free.length)] : null
}

const inside = (p) => p.x >= 0 && p.y >= 0 && p.x < W && p.y < H
const ahead = (head, dir) => ({ x: head.x + DIRS[dir][0], y: head.y + DIRS[dir][1] })

/** Moves that do not kill you THIS step. Reversing into your own neck is not a
 *  move the game allows, so it never reaches the deck. */
export function legalMoves(g) {
  const head = g.snake[0]
  // The tail tip vacates as you move into it, so it is not a collision — except
  // on the step you eat, when the tail does not move.
  const body = g.snake.slice(0, -1)
  return Object.keys(DIRS).filter((d) => {
    if (d === OPPOSITE[g.heading]) return false
    const n = ahead(head, d)
    return inside(n) && !body.some((s) => s.x === n.x && s.y === n.y)
  })
}

export function step(g, dir) {
  const head = ahead(g.snake[0], dir)
  const eats = g.food && head.x === g.food.x && head.y === g.food.y
  const body = eats ? g.snake : g.snake.slice(0, -1)
  if (!inside(head)) { g.dead = "ran into the wall"; return g }
  if (body.some((s) => s.x === head.x && s.y === head.y)) { g.dead = "ran into itself"; return g }
  g.snake = [head, ...body]
  g.heading = dir
  g.moves++
  if (eats) { g.apples++; g.food = placeFood(g) }
  return g
}

/** Flood fill from a square: how much room a move leaves. THE arithmetic of this
 *  game, and it is code's job — the judge is handed the conclusion as a phrase. */
export function room(g, dir) {
  const start = ahead(g.snake[0], dir)
  if (!inside(start)) return 0
  const blocked = new Set(g.snake.slice(0, -1).map((s) => `${s.x},${s.y}`))
  if (blocked.has(`${start.x},${start.y}`)) return 0
  const seen = new Set([`${start.x},${start.y}`])
  const q = [start]
  while (q.length) {
    const p = q.pop()
    for (const [dx, dy] of Object.values(DIRS)) {
      const n = { x: p.x + dx, y: p.y + dy }, k = `${n.x},${n.y}`
      if (!inside(n) || seen.has(k) || blocked.has(k)) continue
      seen.add(k); q.push(n)
    }
  }
  return seen.size
}

/** Manhattan distance to the food after a move. Also pure arithmetic, also never sent. */
export function foodDistance(g, dir) {
  if (!g.food) return null
  const n = ahead(g.snake[0], dir)
  return Math.abs(n.x - g.food.x) + Math.abs(n.y - g.food.y)
}

export const bodyLength = (g) => g.snake.length
export const freeSquares = (g) => W * H - g.snake.length
