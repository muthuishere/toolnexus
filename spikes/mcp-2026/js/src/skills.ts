// Skill URI safety (python-sdk #3685): a skill:// URI is percent-decoded ONCE, then rejected if it
// still contains '%' (double encoding), a backslash, NUL, an absolute/empty path, or a "."/".."
// segment. Both the server (before lookup) and the client (before writing files) use it.

/** Splits skill://<name>/<path> into a validated name and slash-separated relative path. Throws on anything unsafe. */
export function parseSkillURI(uri: string): { name: string; rel: string } {
  if (typeof uri !== "string" || !uri.startsWith("skill://")) throw new Error("not a skill:// URI")
  let dec: string
  try {
    dec = decodeURIComponent(uri.slice("skill://".length))
  } catch {
    throw new Error("bad percent-encoding")
  }
  if (/[%\\\x00]/.test(dec)) throw new Error("skill URI has encoded, backslash or NUL characters")
  const segs = dec.split("/")
  if (segs.length < 2) throw new Error("skill URI needs skill://<name>/<path>")
  for (const s of segs) {
    if (s === "" || s === "." || s === ".." || s.includes(":"))
      throw new Error("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
  }
  return { name: segs[0], rel: segs.slice(1).join("/") }
}
