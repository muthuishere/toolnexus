/**
 * The ONE §8 retry policy — the retryable-status set and the `Retry-After` rule.
 *
 * INTERNAL to the package. This module is deliberately NOT re-exported from
 * `index.ts`: `client.ts` and `classifier.ts` (§8B) both need the policy, and a
 * second copy is exactly the drift this file exists to prevent — but sharing it
 * is an implementation detail, not npm API. Nothing here is in the parity-checked
 * option surface, and nothing here may be added to `index.ts`.
 */

/** ~68 years; the widest whole-second count all seven ports represent exactly. */
const RETRY_AFTER_MAX_SECONDS = 2147483647

/**
 * Honour `Retry-After` only in its delay-seconds form: a run of ASCII digits
 * (RFC 9110 §10.2.3) in 0…2147483647, returned in milliseconds.
 *
 * `Number()` is far too permissive for this — it accepts `"0.5"`, `"-5"`, `"1e3"`
 * and `" "` (as 0), which is how this port came to wait for fractional seconds
 * that five of the other six rejected outright. Fractional, signed, HTTP-date and
 * out-of-range values are not delays we can honour, so the caller falls back to
 * backoff rather than guessing. `0` is a real answer, so it returns `0`, not
 * `null`, and callers must use `??` rather than `||`.
 */
export function retryAfterMs(raw: string | null | undefined): number | null {
  if (raw == null) return null
  const s = raw.trim()
  if (!/^[0-9]+$/.test(s)) return null
  const secs = Number(s)
  return secs <= RETRY_AFTER_MAX_SECONDS ? secs * 1000 : null
}

/**
 * The default retryable set: `429` plus the 5xx worth another try — and `529 Overloaded`,
 * which TypeSafe documents as "retry with backoff" and which an enumeration made terminal.
 *
 * It is an ENUMERATION on purpose. "Any 5xx" would sweep in permanently-broken statuses
 * (`501 Not Implemented`, `505 HTTP Version Not Supported`) and change the retry behaviour of
 * every existing host without asking. A backend with its own transient status — a Cloudflare
 * origin answering `520`–`527`, say — opts in declaratively through `retryableStatuses`, which
 * ADDS to this set and cannot remove from it.
 *
 * Shared with §8B's classifier, which adds `408` to it rather than inventing a second policy.
 */
const RETRYABLE = new Set([429, 500, 502, 503, 504, 529])

/**
 * Whether a status is retryable by default, optionally widened by a host's `retryableStatuses`.
 *
 * `extra` is ADDITIVE: it can only make more statuses retryable, never fewer, so a host cannot
 * accidentally drop `429` and lose `Retry-After` handling with it. It decides the DEFAULT
 * classification only — `onError` still runs afterwards and has the final say on every attempt,
 * so `onError` returning `"fail"` overrides a status the host itself listed here.
 */
export function isRetryableStatus(status: number, extra?: readonly number[] | null): boolean {
  return RETRYABLE.has(status) || (extra != null && extra.includes(status))
}
