/**
 * listModels (SPEC §4C): the models a provider offers, for model switching. One GET per page,
 * no retries. Same key rules as the client (apiKey, apiKeyEnv, or the host's own env var).
 */
import { resolveKey, type ClientOptions } from "./client.js"

/** One model a provider lists. */
export interface ModelInfo {
  id: string
  /** The provider's display name, when it gives one. */
  name?: string
  /** Context window in tokens when the provider reports it (OpenRouter does). */
  contextLength?: number
}

async function getJson(f: typeof fetch, url: string, headers: Record<string, string>, signal?: AbortSignal): Promise<any> {
  const res = await f(url, { method: "GET", headers, signal })
  const text = await res.text()
  if (!res.ok) throw new Error(`list models: HTTP ${res.status}: ${text.slice(0, 300)}`)
  return JSON.parse(text)
}

/**
 * List the models at `opts.baseUrl` in `opts.style`: openai `GET {base}/models`; anthropic
 * `GET {base}/v1/models`, following `has_more`/`last_id`. Sorted by id.
 */
export async function listModels(
  opts: Pick<ClientOptions, "baseUrl" | "style" | "apiKey" | "apiKeyEnv" | "headers" | "fetch">,
  ctx?: { signal?: AbortSignal },
): Promise<ModelInfo[]> {
  const key = resolveKey(opts)
  const f = opts.fetch ?? fetch
  const base = opts.baseUrl.replace(/\/+$/, "")
  const out: ModelInfo[] = []
  if (opts.style === "anthropic") {
    const endpoint = base.endsWith("/v1") ? `${base}/models` : `${base}/v1/models`
    let after = ""
    for (let page = 0; page < 100; page++) {
      const url = `${endpoint}?limit=1000${after ? `&after_id=${encodeURIComponent(after)}` : ""}`
      const body = await getJson(f, url, { "x-api-key": key, "anthropic-version": "2023-06-01", ...opts.headers }, ctx?.signal)
      for (const m of body?.data ?? []) out.push({ id: m.id, ...(m.display_name ? { name: m.display_name } : {}) })
      if (!body?.has_more || !body?.last_id) break
      after = body.last_id
    }
  } else {
    const body = await getJson(f, `${base}/models`, { Authorization: `Bearer ${key}`, ...opts.headers }, ctx?.signal)
    for (const m of body?.data ?? []) {
      out.push({
        id: m.id,
        ...(m.name ? { name: m.name } : {}),
        ...(typeof m.context_length === "number" && m.context_length > 0 ? { contextLength: m.context_length } : {}),
      })
    }
  }
  return out.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
}
