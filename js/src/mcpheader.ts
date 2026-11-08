/** Internal (not exported from index): MCP 2026-07-28 header helpers shared by the handler and McpServerAuth. */

/** Decodes the `=?base64?<std-base64>?=` sentinel (SPEC §2.3); an absent header is not ok. */
export function decodeHeaderValue(v: string): [string, boolean] {
  if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11) {
    const b = v.slice(9, -2)
    if (b.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(b)) return ["", false]
    return [Buffer.from(b, "base64").toString("utf8"), true]
  }
  return [v, v !== ""]
}

/** GET a JSON document (200 only). */
export async function getJson(f: typeof fetch, url: string): Promise<any> {
  const r = await f(url, { headers: { Accept: "application/json" } })
  if (r.status !== 200) throw new Error(`GET ${url}: HTTP ${r.status}`)
  return r.json()
}

