/**
 * Deterministic ordering for everything the library lists (addenda A1a, A15, A22, A24).
 *
 * INTERNAL — deliberately not re-exported from `index.ts`. These are not API; they are the one
 * place the ordering rules live so no caller has to remember them.
 *
 * The rules, and why each exists:
 *
 *  - **Never `localeCompare`.** It orders by the machine's ICU data, so the same build emits a
 *    different order on a different machine. A cross-port byte-identity claim cannot survive it.
 *  - **Never a bare `Array.sort()`.** JS's default compares UTF-16 CODE UNITS, which disagrees
 *    with code point above U+FFFF — the same divergence C#'s `StringComparer.Ordinal` has.
 *  - **Never an ABSENT sort over a directory read.** `readdirSync` is sorted on most platforms,
 *    but Node guarantees nothing, and a walk that breaks at a cap turns that accident into a
 *    CONTENT difference: which files appear, not merely their order.
 */

/**
 * Unicode CODE-POINT comparison — the one string ordering rule, identical in all seven ports.
 * Iterates code points rather than code units, so a surrogate pair sorts after U+E000–U+FFFF
 * (where `<`, `Array.sort()` and `StringComparer.Ordinal` all disagree with it).
 */
export function compareCodePoints(a: string, b: string): number {
  if (a === b) return 0
  const ai = a[Symbol.iterator]()
  const bi = b[Symbol.iterator]()
  for (;;) {
    const x = ai.next()
    const y = bi.next()
    if (x.done) return y.done ? 0 : -1
    if (y.done) return 1
    const cx = x.value.codePointAt(0)!
    const cy = y.value.codePointAt(0)!
    if (cx !== cy) return cx < cy ? -1 : 1
  }
}

/**
 * The SKILL DISCOVERY order (addendum A15): DEPTH (path segments) ascending, then code point,
 * over a path relative to the root. First-wins then keeps the shallowest copy of a duplicated
 * name, uniformly — code point alone makes the winner depend on the skill's own first letter
 * (`docx` beats `synced/…`, but `xlsx` loses to it).
 */
export function compareDiscovery(a: string, b: string, sep: string): number {
  const da = a.split(sep).length
  const db = b.split(sep).length
  if (da !== db) return da < db ? -1 : 1
  return compareCodePoints(a, b)
}

/**
 * Sort a directory's entries BY NAME, in code-point order, in place.
 *
 * By NAME at each level, never by full path: that is what makes ports walking the same tree with
 * the same stack produce the same sequence, which matters because a capped walk's cap decides
 * WHICH entries are reached.
 */
export function sortEntriesByName<T extends { name: string }>(entries: T[]): T[] {
  return entries.sort((x, y) => compareCodePoints(x.name, y.name))
}

/**
 * The listing path form (addendum A28): a relative path with `/` as the separator ON EVERY
 * PLATFORM. Every capped or ordered listing EMITS this string and SORTS ON THE SAME STRING —
 * ordering by one thing and showing another is how a listing ends up looking unsorted to its
 * reader while passing its own test. Takes an already-relative path (`path.relative(...)`).
 */
export function toPosixPath(rel: string, sep: string): string {
  return sep === "/" ? rel : rel.split(sep).join("/")
}
