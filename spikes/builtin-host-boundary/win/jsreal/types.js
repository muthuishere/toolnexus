/**
 * Shared contract types. See ../../SPEC.md.
 */
/**
 * Typed constructor for the common resume payload: "here is the tool's output" (ADR 0026 D2).
 * Hand-building `{ id, ok: true, data: { output } }` is where hosts get the key wrong; this
 * removes the key from the host's hands entirely.
 *
 * `output` MUST be a string. A non-string is an ERROR, never a silent degrade to `""` — a
 * fabricated empty result is indistinguishable from an answer the human never gave.
 */
export function answerOutput(id, output) {
    if (typeof output !== "string") {
        throw new TypeError(`answerOutput: output must be a string, got ${output === null ? "null" : typeof output}`);
    }
    if (!id)
        throw new TypeError("answerOutput: id is required (it must match the Request's id)");
    return { id, ok: true, data: { output } };
}
/** The matching refusal: `ok:false` with an advisory reason the loop does not branch on. */
export function answerDeclined(id, reason = "declined") {
    if (!id)
        throw new TypeError("answerDeclined: id is required (it must match the Request's id)");
    return { id, ok: false, reason };
}
let _pendingSeq = 0;
/** Producer helper: return a suspension. A ToolResult with `metadata.pending` = a Request. */
export function pending(request) {
    const id = request.id ?? `pnd-${Date.now().toString(36)}-${++_pendingSeq}`;
    const req = { ...request, id };
    return {
        output: req.prompt + (req.url ? `\n${req.url}` : ""),
        isError: true,
        metadata: { pending: req },
    };
}
/** Sugar for the common case: `kind:"authorization"` at a login URL. */
export function authRequired(url, prompt = "Authorization required to continue") {
    return pending({ kind: "authorization", prompt, url });
}
/** Read the suspension off a result, if any. */
export function pendingOf(result) {
    return result.metadata?.pending;
}
/** Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode). */
export const sanitize = (value) => value.replace(/[^a-zA-Z0-9_-]/g, "_");
