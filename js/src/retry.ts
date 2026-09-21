/**
 * The ONE §8 retry policy — the retryable-status set and the `Retry-After` rule.
 *
 * INTERNAL to the package. This module is deliberately NOT re-exported from
 * `index.ts`: `client.ts` and `classifier.ts` (§8B) both need the policy, and a
 * second copy is exactly the drift this file exists to prevent — but sharing it
 * is an implementation detail, not npm API. Nothing here is in the parity-checked
 * option surface, and nothing here may be added to `index.ts`.
 */

/** The default retryable set (429 + the 5xx worth another try). */
const RETRYABLE = new Set([429, 500, 502, 503, 504])

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

/** Whether a status is in the default retryable set (429/5xx). Shared with §8B's classifier,
 * which reuses this policy rather than inventing a second one. */
export function isRetryableStatus(status: number): boolean {
  return RETRYABLE.has(status)
}
