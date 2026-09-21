/** Canonical JSON: keys sorted recursively (ASCII), arrays NEVER reordered,
 *  compact separators. JSON.stringify does neither of the first two things for
 *  us — it preserves insertion order — so the sort is hand-rolled.
 *
 *  Numbers, strings and escaping are delegated to JSON.stringify, which emits
 *  the shortest round-tripping decimal (ECMA-262 Number::toString). That is what
 *  makes 1.21 come back as "1.21" and the integer-valued 0 as "0". */
export function canonical(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null"
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]"
  const obj = value as Record<string, unknown>
  const keys = Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort()
  return "{" + keys.map((k) => JSON.stringify(k) + ":" + canonical(obj[k])).join(",") + "}"
}
