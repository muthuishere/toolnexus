/**
 * Unified LLM client — the host loop. Give it a plain base URL + a style
 * ("openai" | "anthropic"), and it runs the tool-calling agent loop against a
 * Toolkit. See ../../SPEC.md §8.
 */
import { AsyncLocalStorage } from "node:async_hooks"
import { mediaPartPath } from "./media.js"
import type { Toolkit } from "./toolkit.js"
import type { Tool, ToolHints, ToolResult, Request, Answer } from "./types.js"
import { pendingOf } from "./types.js"
import { toOpenAI, toAnthropic } from "./adapters.js"
import { applyPromptCache, type PromptCacheOptions } from "./promptcache.js"
import { buildToolPlan, toolSearch, unwrapCallTool, type ToolPlan, type ToolTier, type ToolTierOptions } from "./tooltiers.js"
import { isRetryableStatus, retryAfterMs } from "./retry.js"
import { isConcurrencySafe, resolveMaxToolConcurrency, scheduleCalls } from "./toolconcurrency.js"
import type { ContentPart, PromptInput, UnsupportedPartMode } from "./content.js"
import { toOpenAIWire, toAnthropicWire, checkPromptParts, modelParts, type WireOptions } from "./wire.js"
import type { TranslateRequest, TranslateResult, TranslatedToolCall } from "./translate.js"
import {
  finishReasonFor,
  contentText,
  argsString,
  openAIMessagesToAnthropic,
  openAIToolsToAnthropic,
  openAIToolChoiceToAnthropic,
  hasSystemMessage,
} from "./translate.js"

export type ClientStyle = "openai" | "anthropic"

export interface ClientOptions {
  baseUrl: string
  style: ClientStyle
  model: string
  /** The key, used as-is. Absent => `apiKeyEnv`, else ONLY the env var that belongs to the
   * `baseUrl` host over https: api.openai.com -> OPENAI_API_KEY, api.anthropic.com ->
   * ANTHROPIC_API_KEY, openrouter.ai (and subdomains) -> OPENROUTER_API_KEY. Any other host (or
   * plain http) needs `apiKey` or `apiKeyEnv` - a key is never sent to a host it does not belong
   * to (SPEC 4C). Never logged. */
  apiKey?: string
  /** The NAME of the env var to read the key from when `apiKey` is absent - for a host not in
   * the table above. Read at call time; the value is never logged. */
  apiKeyEnv?: string
  headers?: Record<string, string>
  systemPrompt?: string
  maxTurns?: number
  /** SPEC §8 "Stop-reason recovery". Default false. On, an empty answer is nudged once, a
   * `length`/`max_tokens` cut is continued (up to 3 times, text carried) and malformed tool-call
   * arguments get one corrective turn — each an ordinary turn, only while a turn remains. */
  stopRecovery?: boolean
  /** Cap on concurrency-safe tool calls of one response running at once (SPEC §8 "Tool
   * scheduling"). Absent ⇒ env `TOOLNEXUS_MAX_TOOL_CONCURRENCY` when a positive integer, else 10.
   * Unsafe calls (anything not read-only) always run alone. */
  maxToolConcurrency?: number
  hooks?: Hooks
  /** SPEC §7F.1 reactive compaction. Called (with the `beforeLLM` signature) when the provider
   * rejects a call as a context overflow (`isContextOverflow`); strictly fewer `messages` replace
   * the transcript and the same turn is re-sent. Breaker: 3 rapid compactions ⇒ `ContextOverflowError`.
   * Absent ⇒ the overflow surfaces as `LlmHttpError`, unchanged. */
  compactOnOverflow?: Hooks["beforeLLM"]
  /** Retries on transient LLM errors (`429`/`500`/`502`/`503`/`504`/`529` + network). Default 2.
   * Widen the status set with `retryableStatuses`. */
  retries?: number
  /** Base backoff in ms (exponential + jitter). Default 500. */
  retryBaseMs?: number
  /**
   * Extra HTTP statuses to treat as retryable, ADDED to the default set
   * (`429`/`500`/`502`/`503`/`504`/`529`). It can only widen: a host cannot remove `429` and
   * lose `Retry-After` handling with it. This sets the DEFAULT classification; `onError` still
   * runs per attempt and has the final say, so `onError` returning `"fail"` overrides a status
   * listed here. Example: a Cloudflare-fronted origin that answers `520`–`527`.
   */
  retryableStatuses?: readonly number[]

  /** Whole-run deadline in ms; aborts the run (and its in-flight request) when exceeded. */
  timeoutMs?: number
  /** Conversation provider for `ask(prompt, { id })`. Default: in-memory (process lifetime).
   * Supply a file/db store to persist conversations across processes. */
  store?: ConversationStore
  /** Observability sink — receives semantic metric events ({event:"llm"|"tool"|"run"…}) as the
   * loop runs (§8 Observability). Forward to statsd/logs/OTel/anything. No cost when unset. */
  onMetric?: (ev: MetricEvent) => void
  /** §10 Suspension resolver. When a tool returns Pending (metadata.pending), the client calls
   * this, then retries the tool with Context.answer = the resolution. Its interior is unconstrained
   * (open a browser, message a channel, watch a file, forward to another agent). Omit for a durable
   * host: run() does not hang — it returns { status:"pending", pending:request } to resume later.
   *
   * IDEMPOTENCY CONTRACT (SPEC §7D rewind-to-checkpoint). A durable resume REPLAYS the suspended
   * turn from its PRE-TURN checkpoint: "continues from its checkpoint" means the transcript is
   * REWOUND, not carried. EVERY tool that ran in that turn RUNS AGAIN, and the suspended tool
   * itself is always re-executed (with `ctx.answer`) — that is §10's resolution mechanism, not a
   * bug. Reattachment by task key makes a re-run `task` call idempotent; a leaf agent's own
   * side-effecting tools (`git push`, a charge, a delete) have NO equivalent and are the host's
   * responsibility. Any tool reachable in a suspendable turn must be idempotent.
   * Replaying the leaf's stored transcript instead is DEFERRED to its own change; it would not
   * remove this requirement anyway.
   */
  /** The second argument carries the turn's abort signal (SPEC 4C), so a pending ask can end
   * when the turn is cancelled: settle once `ctx.signal` aborts. */
  waitFor?: (request: Request, ctx: { signal: AbortSignal }) => Promise<Answer>
  /** Prompt caching (SPEC 8): anthropic `cache_control` breakpoints on the last tool, the system
   * prompt and the latest message; openai `prompt_cache_key`. Applied before `requestParams`
   * and `bodyTransform`, so both can still override it. Absent => body byte-identical. */
  promptCache?: PromptCacheOptions
  /** Tool tiers (SPEC 8): send only core tool schemas plus a `tool_search` tool that loads the
   * rest on demand (and `call_tool`). The plan is frozen per toolkit until `resetToolTiers`.
   * Absent => every tool is sent, as before. */
  toolTiers?: ToolTierOptions
  /** §8 Gap 1. Extra top-level keys shallow-merged into EVERY LLM body after the client builds
   * its own — a requestParams key WINS on collision (e.g. max_tokens overrides the anthropic
   * default). `messages`/`tools`/`stream` are forbidden here (stripped + warned) — rewrite
   * messages via bodyTransform. Applied on all four paths. Omit ⇒ body byte-identical to today. */
  requestParams?: Record<string, unknown>
  /** §8 Gap 1. Receives the fully assembled body (after requestParams merge) just before marshal
   * and returns the body to send — the escape hatch for provider-specific rewriting (e.g. tool-role
   * message preprocessing) without a proxy. Returning a falsy value ⇒ unchanged. Runs on every call. */
  bodyTransform?: (body: Record<string, any>) => Record<string, any> | undefined | void
  /** §8 Gap 2. HTTP transport for LLM requests (retries included). Omit ⇒ global fetch. Scope is the
   * LLM path only; MCP transports use the SDK's own client. */
  fetch?: typeof fetch
  /** §8 Resilience. Classify each LLM-call failure into "retry" or "fail". Omit ⇒ the default
   * classifier (retryable status/network within budget ⇒ retry, else fail) — byte-identical to
   * today. A "retry" is always bounded by `retries`; the classifier cannot loop unbounded.
   * There is no "suspend" tier — suspension (§10) stays a user-action pause, not an error path. */
  onError?: (info: ErrorInfo) => ErrorTier
  /** §8 Resilience. Ordered fallback model ids tried, with a fresh retry budget each, when the
   * current model's retry budget ends on an overload (`503`/`529`). Chain = [turnModel, ...these];
   * empty/duplicate entries skipped. `TOOLNEXUS_DISABLE_MODEL_FALLBACK=1|true` disables it.
   * `stream` emits `{ type: "fallback", from, to }` before switching. Omit ⇒ byte-identical. */
  fallbackModels?: readonly string[]
  /** §1B. Reject a content part carrying more than this many **decoded** bytes (never the
   * +33% base64 string). Unset ⇒ no limit, as today. */
  maxPartBytes?: number
  /** §8A. Overrides the provenance rule uniformly: `"error"` fails on any part the style
   * cannot represent (attached or tool-derived), `"text"` degrades every one of them to a
   * text placeholder. Unset ⇒ attached parts error, derived parts degrade. */
  onUnsupportedPart?: UnsupportedPartMode
}

/**
 * The §8 CLIENT status vocabulary, as a value. It is a DIFFERENT, smaller closed set than the
 * §7D AGENT vocabulary (`agents.TASK_STATUSES`), which also contains `"timeout"`, `"closed"`,
 * `"interrupted"` and `"error"`. Two fields are called `status`; they are not interchangeable.
 * A run that fails at a deadline THROWS here — it never comes back as a `RunResult`.
 */
export const RUN_STATUSES = ["done", "pending", "incomplete"] as const
export type RunStatus = (typeof RUN_STATUSES)[number]

/** Account-identifier keys blanked out of a provider error body before it reaches a message,
 *  a log or a metric. The shape survives; the value does not (ADR 0027 D3.2). */
export const REDACTED_BODY_KEYS = ["user_id", "account_id", "org_id", "organization"] as const
/** What a redacted value is replaced with — identical in all seven ports. */
export const REDACTION_PLACEHOLDER = "\u00abredacted\u00bb"
/** A redacted body is capped at this many characters IN THE MESSAGE ONLY. The typed `body`
 *  field carries the full redacted body — a host that opted into a typed error asked for the
 *  whole thing (ADR 0027 D3 / addendum A5). */
export const ERROR_BODY_CAP = 200

/**
 * Redact known account identifiers, then cap. A CAP IS NOT REDACTION: the body that leaked
 * `user_2…` in issue #92 was 96 bytes, well inside the cap, so both steps are needed and
 * redaction runs FIRST (capping first could split a key/value pair and hide it from the regex).
 */
export function redactErrorBody(body: string): string {
  let out = body
  for (const key of REDACTED_BODY_KEYS) {
    // JSON ("key": "value" / "key": 123) and form/query (key=value) spellings alike.
    out = out.replace(new RegExp(`("${key}"\\s*:\\s*)("(?:[^"\\\\]|\\\\.)*"|-?\\d+(?:\\.\\d+)?|true|false|null)`, "g"), `$1"${REDACTION_PLACEHOLDER}"`)
    out = out.replace(new RegExp(`\\b${key}=([^&\\s]+)`, "g"), `${key}=${REDACTION_PLACEHOLDER}`)
  }
  return out.trim()
}

/** Cap a already-redacted body for interpolation into a MESSAGE. Redaction is not a cap and a
 *  cap is not redaction: they are independent and both are needed. */
export function capErrorBody(body: string): string {
  return body.length > ERROR_BODY_CAP ? body.slice(0, ERROR_BODY_CAP) + "\u2026" : body
}

/**
 * A provider failure carried as a VALUE, not a sentence (ADR 0027 D3.1). A host that must
 * decide what to log can read `status` / `retryAfter` instead of parsing a message.
 *
 * `body` is ALREADY redacted and capped, and is `""` on 401/403 — an auth body routinely
 * reflects the credential or the header that was sent. `rawBody` is never populated by the
 * library; the SPEC credentials guarantee now covers error messages too.
 */
export class LlmHttpError extends Error {
  readonly name = "LlmHttpError"
  constructor(
    /** The HTTP status the provider answered with. */
    readonly status: number,
    /** The provider's FULL redacted body; `""` on 401/403 and when empty. Uncapped — the cap
     *  applies to `message` only. */
    readonly body: string,
    /** The `Retry-After` header verbatim, when the provider sent one. */
    readonly retryAfter?: string,
  ) {
    super(`LLM ${status}${body ? ": " + capErrorBody(body) : ""}`)
  }
}

/** Build the typed error from a non-2xx provider response (reads the body once). */
export async function llmHttpError(res: Response): Promise<LlmHttpError> {
  const raw = await res.text().catch(() => "")
  // 401/403: never echo the body — see `Classifier.cause`, now one policy, not two.
  const body = res.status === 401 || res.status === 403 ? "" : redactErrorBody(raw)
  return new LlmHttpError(res.status, body, res.headers.get("retry-after") ?? undefined)
}

/** SPEC §7F.1 markers of a context-overflow rejection (matched lowercased). */
const OVERFLOW_MARKERS = ["context_length_exceeded", "maximum context length", "prompt is too long", "prompt_too_long", "request_too_large", "context window"]

/** SPEC §7F.1: true iff `status` is 400/413 AND the (redacted) body names a context overflow. */
export function isContextOverflow(status: number, body: string): boolean {
  if (status !== 400 && status !== 413) return false
  const b = (body ?? "").toLowerCase()
  return OVERFLOW_MARKERS.some((m) => b.includes(m))
}

/** SPEC §7F.1: reactive compaction could not make the prompt fit (breaker tripped, or the
 *  `compactOnOverflow` hook made no progress). A failure, never a §10 pending. */
export class ContextOverflowError extends Error {
  readonly name = "ContextOverflowError"
  constructor(
    /** Reactive compactions in the current rapid chain (3 when the breaker tripped). */
    readonly compactions: number,
    /** Status of the last provider error. */
    readonly status: number,
    /** Redacted body of the last provider error (same redaction as `LlmHttpError`). */
    readonly body: string,
  ) {
    super(`context overflow: ${compactions} reactive compaction(s) did not make the prompt fit`)
  }
}

/** Per-run breaker counters for §7F.1. */
interface OverflowState { okSince: number; streak: number }

/** §8 Resilience. What to do with a failed LLM call. */
export type ErrorTier = "retry" | "fail"

/** §8 Resilience. Context passed to `onError` for each failed LLM attempt. */
export interface ErrorInfo {
  /** The thrown network/transport error, when the failure was not an HTTP response. */
  error?: unknown
  /** The HTTP status, when the failure was a non-ok response. */
  status?: number
  /** Zero-based attempt index (0 = first try). */
  attempt: number
  /** Whether `status`/the error is in the default retryable set
   * (429/500/502/503/504/529/network, plus anything `retryableStatuses` added). */
  retryable: boolean
}

/**
 * Semantic observability events (§8). NOT counter/histogram primitives — readable records the
 * host can forward anywhere. The same events also feed the built-in Prometheus registry
 * (`client.metrics()`).
 */
export type MetricEvent =
  | { event: "llm"; model: string; status: "ok" | "error"; ms: number; promptTokens: number; completionTokens: number }
  | { event: "tool"; tool: string; source: string; isError: boolean; ms: number; pending?: boolean }
  | { event: "run"; model: string; turns: number; toolCalls: number; totalTokens: number; ms: number; error?: string }
  // §8B. The classifier emits into this SAME sink. Neither event is folded into the Prometheus
  // registry, so `client.metrics()` text stays byte-identical to a build with no classifier.
  | {
      event: "classifier.evaluate"
      model: string
      status: "ok" | "error"
      ms: number
      promptTokens: number
      completionTokens: number
      error?: string
      /** L1: how many questions were asked. */
      questions: number
      /** L1: sha256 hex of `canonicalRequest(model, questions)` ("" when unencodable). */
      questionsHash: string
      /** True when the decision came from the classifier cache and no request was sent. */
      cached: boolean
    }
  // `warning`, never `error`: a degenerate-criteria report is advisory, and a consumer that
  // counts "has an error" as a failure must not count this.
  | { event: "classifier.warning"; question: string; warning: string }

/**
 * Where `ask()` conversations are remembered — two methods. Ship the in-memory
 * default; implement this for a file/db/redis provider to persist across processes.
 */
export interface ConversationStore {
  /** Return the stored transcript for `id`, or undefined if none. */
  get(id: string): Promise<any[] | undefined>
  /** Persist the (updated) transcript for `id`. */
  save(id: string, messages: any[]): Promise<void>
}

/** Default conversation provider — keeps transcripts in memory for the client's lifetime. */
export class InMemoryConversationStore implements ConversationStore {
  private readonly map = new Map<string, any[]>()
  private readonly updated = new Map<string, Date>()
  async get(id: string): Promise<any[] | undefined> {
    const m = this.map.get(id)
    return m ? [...m] : undefined
  }
  async save(id: string, messages: any[]): Promise<void> {
    this.map.set(id, [...messages])
    this.updated.set(id, new Date())
  }
  /** Every stored conversation, newest first (ties by id ascending). */
  async list(): Promise<ConversationEntry[]> {
    return sortEntries([...this.updated].map(([id, updatedAt]) => ({ id, updatedAt })))
  }
  /** Remove a conversation; absent ids are not an error. */
  async delete(id: string): Promise<void> {
    this.map.delete(id)
    this.updated.delete(id)
  }
}

/** One `list()` row of a shipped conversation store. */
export interface ConversationEntry {
  id: string
  updatedAt: Date
}

/** @internal Newest first, ties by id ascending (SPEC.md "Conversation memory"). */
export function sortEntries(entries: ConversationEntry[]): ConversationEntry[] {
  return entries.sort((a, b) => b.updatedAt.getTime() - a.updatedAt.getTime() || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(resolve, ms)
    signal.addEventListener("abort", () => { clearTimeout(t); reject(signal.reason ?? new Error("aborted")) }, { once: true })
  })
}

/**
 * Lifecycle hooks around the agent loop. Each may observe, and (where noted)
 * mutate or short-circuit. All may be async.
 */
/** The `beforeLLM` hook event. */
export interface BeforeLLMEvent {
  messages: any[]
  tools: any[]
  model: string
  turn: number
}

/** What a `beforeLLM` hook may return. `model` (non-empty) applies to that turn only. */
export interface LLMOverride {
  messages?: any[]
  tools?: any[]
  model?: string | null
}

export interface Hooks {
  /** Before each model call. Return { messages?, tools? } to replace them, and { model } to send a
   * different model for THIS turn only (request body + afterLLM event); absent/null/empty keeps the
   * configured model verbatim (SPEC §8, e.g. a ModelRouterClassifier). */
  beforeLLM?(ev: BeforeLLMEvent): void | LLMOverride | Promise<void | LLMOverride>
  /** After each model call (observe: logging, cost, tracing). */
  afterLLM?(ev: { response: any; model: string; turn: number }): void | Promise<void>
  /** Before a tool runs. Return { result } to SHORT-CIRCUIT (deny/cache), { args } to rewrite. */
  beforeTool?(ev: { name: string; args: Record<string, unknown>; id?: string; turn: number; hints?: ToolHints }): void | { args?: Record<string, unknown>; result?: ToolResult } | Promise<void | { args?: Record<string, unknown>; result?: ToolResult }>
  /** After a tool runs. Return { result } to replace the result. */
  afterTool?(ev: { name: string; args: Record<string, unknown>; result: ToolResult; id?: string; turn: number }): void | { result?: ToolResult } | Promise<void | { result?: ToolResult }>
  /** At each round boundary (SPEC §8, #192): after a round's tool results (final=false), or when the
   * model stopped with a turn remaining (final=true, text = its answer). Each `inject` string is
   * appended as a user message; a non-empty inject on a final round CONTINUES the loop. */
  afterRound?(ev: AfterRoundEvent): void | RoundInjection | Promise<void | RoundInjection>
}

/** The `afterRound` hook event. */
export interface AfterRoundEvent {
  turn: number
  final: boolean
  text: string
  messages: any[]
}

/** What an `afterRound` hook may return. */
export interface RoundInjection {
  inject?: string[]
}

/** A tool call the model made, plus its result + metadata. */
export interface ToolCallRecord {
  name: string
  args: Record<string, unknown>
  output: string
  isError: boolean
  metadata?: Record<string, unknown>
  /** §8: the ToolResult's parts — present iff the result had parts. */
  parts?: ContentPart[]
}

/** §8 RunResult.files entry: a non-text part a tool call produced this run. */
export type RunFile = { type: "image" | "file" | "audio"; mimeType: string; url?: string; data?: string; name?: string; path?: string; from: string; callId: string }

/** Model tool-call id of each record, kept off the record itself (§8 shape has no id). */
const callIds = new WeakMap<ToolCallRecord, string>()

/** RunResult.files: every non-text part of every tool call, in call order (§8). */
export function runFiles(toolCalls: ToolCallRecord[]): RunFile[] {
  const out: RunFile[] = []
  for (const r of toolCalls) {
    for (const p of r.parts ?? []) {
      if (p.type === "text") continue
      // D20: a media file's local path rides on the run file, never on the part.
      const local = mediaPartPath(p)
      out.push({ ...p, ...(local !== undefined ? { path: local } : {}), from: r.name, callId: callIds.get(r) ?? "" })
    }
  }
  return out
}

/** Token usage, summed across every LLM round trip in the run. */
export interface Usage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  /** Prompt tokens served from the provider's prompt cache (anthropic cache_read_input_tokens;
   * openai prompt_tokens_details.cached_tokens). Present once a provider reports any.
   * Under anthropic `promptTokens` EXCLUDES cached tokens: the context sent is
   * promptTokens + cacheReadTokens + cacheWriteTokens. Under openai `promptTokens` already
   * includes cacheReadTokens. */
  cacheReadTokens?: number
  /** Tokens written to the prompt cache (anthropic cache_creation_input_tokens). */
  cacheWriteTokens?: number
}

export interface RunResult {
  text: string
  messages: any[]
  /** Every tool call made, with its output, error flag, and metadata. */
  toolCalls: ToolCallRecord[]
  /** Every non-text part produced by tool calls this run, in call order (§8). Always present. */
  files: RunFile[]
  /** Total number of tool calls (= toolCalls.length). */
  toolCallCount: number
  /** Number of LLM round trips. */
  turns: number
  /** Aggregated token usage across all turns. */
  usage: Usage
  /** The model used. */
  model: string
  /** "done" normally; "pending" iff a tool suspended and no waitFor was configured (§10);
   * "incomplete" iff a limit stopped the run loudly (§7D) — a maxTurns stop with no final
   * text is never a silent "done". */
  status: "done" | "pending" | "incomplete"
  /** The unresolved suspension — present iff status === "pending" (§10). */
  pending?: Request
  /** The limit that stopped the run (e.g. "maxTurns") — present iff status === "incomplete". */
  limit?: string
}

/** Events yielded by client.stream(). */
export type StreamEvent =
  | { type: "text"; delta: string }
  /** Model reasoning narration (e.g. an ACP agent's `agent_thought_chunk`). Never part of
   *  the answer text or the RunResult. */
  | { type: "thought"; delta: string; via?: "acp" }
  /** `via: "acp"` marks a tool the ACP agent ran itself: reported, never executed by the loop. */
  | { type: "tool_call"; id: string; name: string; args: Record<string, unknown>; via?: "call_tool" | "acp" }
  /** An ACP agent's `tool_call_update` carrying `rawInput`: the call's arguments, known after
   *  the initial `tool_call` (#188). Reported only; never executed by the loop. */
  | { type: "tool_call_args"; id: string; name: string; args: Record<string, unknown>; via: "acp" }
  /** `parts`: the result's non-text output (§1B), e.g. an image an ACP agent's own tool returned. */
  | { type: "tool_result"; id: string; name: string; output: string; isError: boolean; parts?: ContentPart[]; metadata?: Record<string, unknown>; via?: "call_tool" | "acp" }
  | { type: "usage"; usage: Usage }
  /** Only with `fallbackModels`: switching from model `from` to `to` after overload (§8). */
  | { type: "fallback"; from: string; to: string }
  | { type: "pending"; request: Request }
  /** Emitted just before each LLM retry wait (streaming only). `attempt` is 1-based (the
   *  failed zero-based attempt + 1), `max` the configured `retries`, `delayMs` the exact wait,
   *  `status` the HTTP status (absent on a network error), `reason` "HTTP <status>" | "network". */
  | { type: "retry"; attempt: number; max: number; delayMs: number; status?: number; reason: string }
  | { type: "done"; result: RunResult }

function addUsage(acc: Usage, raw: any, style: ClientStyle): void {
  if (!raw) return
  const cache = (key: "cacheReadTokens" | "cacheWriteTokens", n: unknown) => {
    if (typeof n === "number" && n > 0) acc[key] = (acc[key] ?? 0) + n
  }
  if (style === "anthropic") {
    acc.promptTokens += raw.input_tokens ?? 0
    acc.completionTokens += raw.output_tokens ?? 0
    acc.totalTokens += (raw.input_tokens ?? 0) + (raw.output_tokens ?? 0)
    cache("cacheReadTokens", raw.cache_read_input_tokens)
    cache("cacheWriteTokens", raw.cache_creation_input_tokens)
  } else {
    cache("cacheReadTokens", raw.prompt_tokens_details?.cached_tokens)
    acc.promptTokens += raw.prompt_tokens ?? 0
    acc.completionTokens += raw.completion_tokens ?? 0
    acc.totalTokens += raw.total_tokens ?? (raw.prompt_tokens ?? 0) + (raw.completion_tokens ?? 0)
  }
}

/** Per-call token counts from one raw usage payload (not summed). */
function perCall(raw: any, style: ClientStyle): { prompt: number; completion: number } {
  if (!raw) return { prompt: 0, completion: 0 }
  if (style === "anthropic") return { prompt: raw.input_tokens ?? 0, completion: raw.output_tokens ?? 0 }
  return { prompt: raw.prompt_tokens ?? 0, completion: raw.completion_tokens ?? 0 }
}

/**
 * Pick the API key (SPEC 4C, security). An explicit `apiKey` is used as-is; else `apiKeyEnv`;
 * else ONLY the env var that belongs to the `baseUrl` host. There is no cross-provider fallback:
 * any other host needs `apiKey` or `apiKeyEnv`. The value is never logged.
 */
export function resolveKey(opts: Pick<ClientOptions, "apiKey" | "apiKeyEnv" | "baseUrl">): string {
  if (opts.apiKey) return opts.apiKey
  const envName = opts.apiKeyEnv || keyEnvForBaseUrl(opts.baseUrl)
  if (!envName) {
    throw new Error(
      `No API key: set apiKey or apiKeyEnv (provider env vars are only used for https://api.openai.com, https://api.anthropic.com and https://openrouter.ai, never for ${JSON.stringify(opts.baseUrl)})`,
    )
  }
  const key = process.env[envName]
  if (!key) throw new Error(`No API key: set apiKey or ${envName}`)
  return key
}

/** The env var whose key may be sent to `baseUrl`'s host, or "" when none may. */
export function keyEnvForBaseUrl(baseUrl: string): string {
  const h = hostOf(baseUrl)
  if (h === "api.openai.com") return "OPENAI_API_KEY"
  if (h === "api.anthropic.com") return "ANTHROPIC_API_KEY"
  if (h === "openrouter.ai" || h.endsWith(".openrouter.ai")) return "OPENROUTER_API_KEY"
  return ""
}

function hostOf(baseUrl: string): string {
  try {
    const u = new URL(baseUrl)
    if (u.protocol !== "https:") return "" // a provider key is never sent over plain http
    return u.hostname.toLowerCase()
  } catch {
    return ""
  }
}

// ---- Prometheus metrics registry (zero-dependency; §8 Observability) ----

/** FIXED across all ports for byte-parity of `..._duration_seconds` histograms. Seconds. */
const DURATION_BUCKETS = [0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60]

interface Hist {
  counts: number[]
  sum: number
  count: number
}

/** Escape a Prometheus label value: backslash, double-quote, newline. */
function escapeLabel(v: string): string {
  return v.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n")
}

/** Render an ordered list of label pairs to `k="v",k="v"` (order is load-bearing for parity). */
function labelStr(pairs: [string, string][]): string {
  return pairs.map(([k, v]) => `${k}="${escapeLabel(v)}"`).join(",")
}

function inc(m: Map<string, number>, key: string, by = 1): void {
  m.set(key, (m.get(key) ?? 0) + by)
}

function observe(m: Map<string, Hist>, key: string, seconds: number): void {
  let h = m.get(key)
  if (!h) {
    h = { counts: new Array(DURATION_BUCKETS.length).fill(0), sum: 0, count: 0 }
    m.set(key, h)
  }
  h.sum += seconds
  h.count++
  for (let i = 0; i < DURATION_BUCKETS.length; i++) if (seconds <= DURATION_BUCKETS[i]) h.counts[i]++
}

/**
 * In-memory cumulative registry that turns the same semantic events `onMetric` sees into the
 * Prometheus counters/histograms of §8, and renders the text exposition format by hand (no dep).
 */
class MetricsRegistry {
  private readonly llmRequests = new Map<string, number>() // labels: model,status
  private readonly llmTokens = new Map<string, number>() // labels: type
  private readonly llmDuration = new Map<string, Hist>() // labels: model
  private readonly toolCalls = new Map<string, number>() // labels: tool,source,is_error
  private readonly toolDuration = new Map<string, Hist>() // labels: tool
  private readonly runErrors = new Map<string, number>() // labels: model

  record(ev: MetricEvent): void {
    if (ev.event === "llm") {
      inc(this.llmRequests, labelStr([["model", ev.model], ["status", ev.status]]))
      if (ev.status === "ok") {
        inc(this.llmTokens, labelStr([["type", "prompt"]]), ev.promptTokens)
        inc(this.llmTokens, labelStr([["type", "completion"]]), ev.completionTokens)
      }
      observe(this.llmDuration, labelStr([["model", ev.model]]), ev.ms / 1000)
    } else if (ev.event === "tool") {
      inc(this.toolCalls, labelStr([["tool", ev.tool], ["source", ev.source], ["is_error", String(ev.isError)], ["pending", String(ev.pending ?? false)]]))
      observe(this.toolDuration, labelStr([["tool", ev.tool]]), ev.ms / 1000)
    } else if (ev.event === "run") {
      if (ev.error) inc(this.runErrors, labelStr([["model", ev.model]]))
    }
  }

  render(): string {
    const out: string[] = []
    this.renderCounter(out, "toolnexus_llm_requests_total", "Total LLM requests.", this.llmRequests)
    this.renderCounter(out, "toolnexus_llm_tokens_total", "Total tokens, by type.", this.llmTokens)
    this.renderHistogram(out, "toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", this.llmDuration)
    this.renderCounter(out, "toolnexus_tool_calls_total", "Total tool calls.", this.toolCalls)
    this.renderHistogram(out, "toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", this.toolDuration)
    this.renderCounter(out, "toolnexus_run_errors_total", "Total run errors.", this.runErrors)
    return out.join("\n") + "\n"
  }

  private renderCounter(out: string[], name: string, help: string, m: Map<string, number>): void {
    out.push(`# HELP ${name} ${help}`)
    out.push(`# TYPE ${name} counter`)
    for (const key of [...m.keys()].sort()) {
      out.push(key ? `${name}{${key}} ${m.get(key)}` : `${name} ${m.get(key)}`)
    }
  }

  private renderHistogram(out: string[], name: string, help: string, m: Map<string, Hist>): void {
    out.push(`# HELP ${name} ${help}`)
    out.push(`# TYPE ${name} histogram`)
    for (const key of [...m.keys()].sort()) {
      const h = m.get(key)!
      for (let i = 0; i < DURATION_BUCKETS.length; i++) {
        out.push(`${name}_bucket{${withLe(key, String(DURATION_BUCKETS[i]))}} ${h.counts[i]}`)
      }
      out.push(`${name}_bucket{${withLe(key, "+Inf")}} ${h.count}`)
      out.push(key ? `${name}_sum{${key}} ${h.sum}` : `${name}_sum ${h.sum}`)
      out.push(key ? `${name}_count{${key}} ${h.count}` : `${name}_count ${h.count}`)
    }
  }
}

function withLe(base: string, le: string): string {
  return base ? `${base},le="${le}"` : `le="${le}"`
}

export class Client {
  /** Conversation provider for `ask()` — from opts.store, else in-memory. */
  private readonly store: ConversationStore
  /** Cumulative Prometheus registry fed by the same events `onMetric` sees. */
  private readonly registry = new MetricsRegistry()
  /** The frozen ToolTiers plan per toolkit (SPEC 8 Tool tiers). */
  private tierPlans = new WeakMap<Toolkit, ToolPlan>()
  constructor(private readonly opts: ClientOptions) {
    this.store = opts.store ?? new InMemoryConversationStore()
  }

  /**
   * §8 Gap 4. The client's conversation store — the exact instance passed in `opts.store`, else
   * the default in-memory store the client created. Read (`get`) and write (`save`) it directly to
   * share state with `ask`; no consumer needs a shadow copy.
   */
  conversationStore(): ConversationStore {
    return this.store
  }

  /**
   * §8 Gap 1. Apply the request-shaping contract to an assembled body: strip forbidden keys from
   * requestParams, shallow-merge requestParams (caller wins), then run bodyTransform last. Called at
   * every LLM body-build site so the ordering contract holds identically on all four paths.
   */
  private finalizeBody(body: Record<string, any>): Record<string, any> {
    if (this.opts.promptCache) applyPromptCache(body, this.opts.style, this.opts.promptCache)
    const rp = this.opts.requestParams
    if (rp) {
      for (const [k, v] of Object.entries(rp)) {
        if (k === "messages" || k === "tools" || k === "stream") {
          console.warn(`[toolnexus] requestParams key "${k}" is not allowed — ignored (use bodyTransform)`)
          continue
        }
        body[k] = v
      }
    }
    if (this.opts.bodyTransform) {
      const out = this.opts.bodyTransform(body)
      if (out) return out
    }
    return body
  }

  /** §8 Gap 5. OpenAI-style body; omits tools/tool_choice when the tool list is empty. Key order
   * matches the pre-change body so a no-options call is byte-identical. */
  private openaiBody(messages: any[], tools: any[], stream: boolean, model: string = this.opts.model): Record<string, any> {
    const b: Record<string, any> = { model, messages: toOpenAIWire(messages, this.wireOpts()) }
    if (tools.length) {
      b.tools = tools
      b.tool_choice = "auto"
    }
    if (stream) {
      b.stream = true
      b.stream_options = { include_usage: true }
    }
    return this.finalizeBody(b)
  }

  /** §8 Gap 5. Anthropic-style body; omits tools when the tool list is empty. */
  private anthropicBody(system: string, messages: any[], tools: any[], stream: boolean, model: string = this.opts.model): Record<string, any> {
    const b: Record<string, any> = { model, max_tokens: 4096, system, messages: toAnthropicWire(messages, this.wireOpts()) }
    if (tools.length) b.tools = tools
    if (stream) b.stream = true
    return this.finalizeBody(b)
  }

  /** The §8A/§1B shaping options every wire build shares. */
  private wireOpts(): WireOptions {
    return { onUnsupportedPart: this.opts.onUnsupportedPart, maxPartBytes: this.opts.maxPartBytes }
  }

  /** Prometheus text exposition of cumulative metrics (§8). Always valid, empty-but-valid before activity. */
  metrics(): string {
    return this.registry.render()
  }

  /** Feed a semantic metric event to the built-in registry and the optional onMetric sink. */
  private emit(ev: MetricEvent): void {
    this.registry.record(ev)
    this.opts.onMetric?.(ev)
  }

  /** Emit the terminal `run` metric event and build the RunResult. `exhausted` marks a
   * maxTurns loop exit — with no final text that is a LOUD "incomplete", never "done" (§7D). */
  private endRun(runStart: number, model: string, text: string, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage, exhausted = false): RunResult {
    this.emit({ event: "run", model, turns, toolCalls: toolCalls.length, totalTokens: usage.totalTokens, ms: Date.now() - runStart })
    return this.result(model, text, messages, toolCalls, turns, usage, exhausted)
  }

  /** Emit a `run` error metric event (once, on a thrown run). */
  private emitRunError(runStart: number, model: string, toolCalls: ToolCallRecord[], turns: number, usage: Usage, e: unknown): void {
    this.emit({ event: "run", model, turns, toolCalls: toolCalls.length, totalTokens: usage.totalTokens, ms: Date.now() - runStart, error: e instanceof Error ? e.message : String(e) })
  }

  /**
   * Run the loop. `ctx` — and `ctx.toolkit` inside it — are OPTIONAL: an absent toolkit is a
   * COMPLETION (SPEC §0.10). The system message is then the system prompt alone, and the
   * request body carries no `tools` and no `tool_choice` key (not an empty array). An empty
   * toolkit and no toolkit are observably identical on the wire.
   */
  async run(prompt: PromptInput, ctx?: { toolkit?: Toolkit; signal?: AbortSignal; history?: any[] }): Promise<RunResult> {
    checkPromptParts(prompt, this.wireOpts())
    const agentFiles: RunFile[] = []
    const r = await agentFilesSink.run(agentFiles, () => this.opts.style === "anthropic"
      ? this.runAnthropic(prompt, ctx?.toolkit, ctx?.signal, ctx?.history)
      : this.runOpenAI(prompt, ctx?.toolkit, ctx?.signal, ctx?.history))
    // Agent-side media (an in-process model's own tools) follow the loop's tool files.
    if (agentFiles.length) r.files = [...r.files, ...agentFiles]
    return r
  }

  /**
   * Single-turn translation (§11): exactly ONE provider call, returned in OpenAI shape.
   * No agent loop, no tool execution, no conversation state — every call is
   * self-contained, so this may be run statelessly and concurrently.
   *
   * The inbound half of the adapters: `toAnthropic`/`toGemini` send declarations out,
   * this reads the provider's tool calls back in. A `toolkit` passed in the request is
   * DECLARED and never executed.
   *
   * Retries/backoff, requestParams merging and the `llm` metric are shared with the loop.
   * `beforeLLM`/`afterLLM` fire once; tool hooks do not, because no tool runs.
   */
  async translate(req: TranslateRequest): Promise<TranslateResult> {
    return this.opts.style === "anthropic" ? this.translateAnthropic(req) : this.translateOpenAI(req)
  }

  /** OpenAI-style upstream: near-passthrough. */
  private async translateOpenAI(req: TranslateRequest): Promise<TranslateResult> {
    const key = resolveKey(this.opts)
    const declared = [...(req.toolkit ? req.toolkit.toOpenAI() : []), ...(req.tools ?? [])]
    let messages = (req.messages ?? []).slice()
    const sys = req.system ?? this.opts.systemPrompt ?? ""
    if (sys && !hasSystemMessage(messages)) messages = [{ role: "system", content: sys }, ...messages]

    let tools = declared
    let turnModel = this.opts.model
    if (this.opts.hooks?.beforeLLM) {
      const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn: 0 })
      if (ov?.messages) messages = ov.messages
      if (ov?.tools) tools = ov.tools
      if (ov?.model) turnModel = ov.model
    }

    const body: Record<string, any> = { model: turnModel, messages }
    if (tools.length) body.tools = tools
    if (req.toolChoice !== undefined) body.tool_choice = req.toolChoice
    if (req.maxTokens) body.max_tokens = req.maxTokens

    const signal = this.makeSignal(req.signal)
    try {
      const r = await this.llmCallJson(
        (m) => [`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${key}`, ...this.opts.headers },
          body: JSON.stringify(this.finalizeBody({ ...body, model: m })),
        }],
        signal,
        "openai",
        turnModel,
      )
      const data = r.data
      turnModel = r.model
      if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: turnModel, turn: 0 })
      const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
      addUsage(usage, data.usage, "openai")
      const choice = data.choices?.[0]
      const toolCalls: TranslatedToolCall[] = (choice?.message?.tool_calls ?? []).map((tc: any) => ({
        id: String(tc.id ?? ""),
        name: String(tc.function?.name ?? ""),
        arguments: argsString(tc.function?.arguments),
      }))
      return {
        text: choice?.message?.content ?? "",
        toolCalls,
        finishReason: choice?.finish_reason ?? finishReasonFor(toolCalls.length > 0),
        usage,
        model: turnModel,
        raw: data,
      }
    } finally {
      this.releaseSignal(signal)
    }
  }

  /** Anthropic-style upstream: the real translation. */
  private async translateAnthropic(req: TranslateRequest): Promise<TranslateResult> {
    const key = resolveKey(this.opts)
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`

    const converted = openAIMessagesToAnthropic(req.messages ?? [])
    let messages = converted.messages
    const system = req.system ?? this.opts.systemPrompt ?? converted.system
    let tools = [
      ...(req.toolkit ? req.toolkit.toAnthropic() : []),
      ...openAIToolsToAnthropic(req.tools),
    ]

    let turnModel = this.opts.model
    if (this.opts.hooks?.beforeLLM) {
      const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn: 0 })
      if (ov?.messages) messages = ov.messages
      if (ov?.tools) tools = ov.tools
      if (ov?.model) turnModel = ov.model
    }

    const body: Record<string, any> = {
      model: turnModel,
      max_tokens: req.maxTokens && req.maxTokens > 0 ? req.maxTokens : 4096,
      messages,
    }
    if (system) body.system = system
    if (tools.length) body.tools = tools
    const tc = openAIToolChoiceToAnthropic(req.toolChoice)
    if (tc) body.tool_choice = tc

    const signal = this.makeSignal(req.signal)
    try {
      const r = await this.llmCallJson(
        (m) => [endpoint, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-api-key": key,
            "anthropic-version": "2023-06-01",
            ...this.opts.headers,
          },
          body: JSON.stringify(this.finalizeBody({ ...body, model: m })),
        }],
        signal,
        "anthropic",
        turnModel,
      )
      const data = r.data
      turnModel = r.model
      if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: turnModel, turn: 0 })
      const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
      addUsage(usage, data.usage, "anthropic")
      const text: string[] = []
      const toolCalls: TranslatedToolCall[] = []
      for (const b of data.content ?? []) {
        if (b?.type === "text") text.push(b.text ?? "")
        else if (b?.type === "tool_use") {
          toolCalls.push({
            id: String(b.id ?? ""),
            name: String(b.name ?? ""),
            arguments: JSON.stringify(b.input ?? {}),
          })
        }
      }
      return {
        text: text.join(""),
        toolCalls,
        finishReason: finishReasonFor(toolCalls.length > 0, data.stop_reason),
        usage,
        model: turnModel,
        raw: data,
      }
    } finally {
      this.releaseSignal(signal)
    }
  }

  /**
   * Renders a translate result's tool calls as an OpenAI `tool_calls` array, ready to put
   * on an assistant message. Convenience for assembling a response envelope.
   */
  static toolCallsJSON(res: TranslateResult): any[] {
    return res.toolCalls.map((tc) => ({
      id: tc.id,
      type: "function",
      function: { name: tc.name, arguments: tc.arguments },
    }))
  }

  /**
   * Stateful ask. With an `id`, the client's `store` remembers the conversation:
   * it loads that id's transcript, runs, saves the updated transcript, and returns
   * the answer — so the next `ask` with the same `id` continues it. Without an
   * `id` it is a stateless one-shot (identical to `run`).
   */
  async ask(prompt: PromptInput, ctx?: { toolkit?: Toolkit; id?: string; on_text?: (delta: string) => void; signal?: AbortSignal }): Promise<RunResult> {
    // Block-style streaming: run the streaming loop, forward text deltas, still return the
    // final RunResult. Memory (id load/save) is handled by stream() itself, so no duplication.
    if (ctx?.on_text) {
      let result: RunResult | undefined
      for await (const ev of this.stream(prompt, { toolkit: ctx.toolkit, id: ctx.id, signal: ctx.signal })) {
        if (ev.type === "text") ctx.on_text(ev.delta)
        else if (ev.type === "done") result = ev.result
      }
      return result!
    }
    if (!ctx?.id) return this.run(prompt, { toolkit: ctx?.toolkit, signal: ctx?.signal })
    checkPromptParts(prompt, this.wireOpts())
    const id = ctx.id
    const history = (await this.store.get(id)) ?? []
    // §8: an interrupted turn is saved too — the loop hands back its transcript, repaired.
    let partial: any[] | undefined
    const onInterrupt: OnInterrupt = (m) => { partial = m }
    let result: RunResult
    try {
      result = this.opts.style === "anthropic"
        ? await this.runAnthropic(prompt, ctx.toolkit, ctx.signal, history, onInterrupt)
        : await this.runOpenAI(prompt, ctx.toolkit, ctx.signal, history, onInterrupt)
    } catch (e) {
      if (partial) await this.store.save(id, partial)
      throw e
    }
    await this.store.save(id, result.messages)
    return result
  }

  /** A stateful multi-turn conversation that retains history across sends. */
  conversation(ctx?: { toolkit?: Toolkit; signal?: AbortSignal }): Conversation {
    return new Conversation(this, ctx?.toolkit, ctx?.signal)
  }

  /**
   * Streaming variant: async-iterate live events (text deltas, tool calls/results, usage, done).
   * With an `id`, it is stateful (like `ask`): the thread's transcript is loaded as history before
   * streaming, and saved back to the ConversationStore on the terminal `done` event. No `id` ⇒ stateless.
   */
  async *stream(prompt: PromptInput, ctx?: { toolkit?: Toolkit; id?: string; signal?: AbortSignal }): AsyncGenerator<StreamEvent, void, unknown> {
    checkPromptParts(prompt, this.wireOpts())
    const history = ctx?.id ? (await this.store.get(ctx.id)) ?? [] : undefined
    // §8: with an id, an interrupted stream saves its repaired transcript before the error surfaces.
    let partial: any[] | undefined
    const onInterrupt: OnInterrupt | undefined = ctx?.id ? (m) => { partial = m } : undefined
    const gen = this.opts.style === "anthropic"
      ? this.streamAnthropic(prompt, ctx?.toolkit, ctx?.signal, history, onInterrupt)
      : this.streamOpenAI(prompt, ctx?.toolkit, ctx?.signal, history, onInterrupt)
    try {
      for await (const ev of gen) {
        if (ev.type === "done" && ctx?.id) await this.store.save(ctx.id, ev.result.messages)
        yield ev
      }
    } catch (e) {
      if (partial && ctx?.id) await this.store.save(ctx.id, partial)
      throw e
    }
  }

  /** §0.10 system message. The toolkit is OPTIONAL — with none there is no skills prompt and
   *  the system message is the system prompt alone. This deref was issue #86 (ADR 0023). */
  private system(toolkit?: Toolkit): string {
    return [this.opts.systemPrompt ?? "", toolkit?.skillsPrompt() ?? ""].filter(Boolean).join("\n\n")
  }

  /** Run-scoped timeout timers, cleared by `releaseSignal` when the run settles. */
  private readonly runTimers = new WeakMap<AbortSignal, ReturnType<typeof setTimeout>>()

  /** Drop a run's timeout timer. Idempotent, and safe on a signal that never had one. */
  private releaseSignal(signal: AbortSignal): void {
    const t = this.runTimers.get(signal)
    if (t !== undefined) {
      clearTimeout(t)
      this.runTimers.delete(signal)
    }
  }

  /** Build a run-scoped abort signal from the optional run-level timeout + an external signal.
   *
   * The timer is deliberately NOT `unref()`d. An unref'd timer cannot keep the event loop
   * alive, so a run whose only pending work is a request that never settles — an injected
   * `fetch`, an in-process transport, a stalled stream — drains the loop and the timeout never
   * fires at all, which is the one case `timeoutMs` exists for. It is RELEASED instead: the
   * caller clears it however the run leaves, so a finished run holds nothing open. */
  private makeSignal(external?: AbortSignal): AbortSignal {
    const ctrl = new AbortController()
    if (this.opts.timeoutMs) {
      const t = setTimeout(() => ctrl.abort(new Error(`run timeout after ${this.opts.timeoutMs}ms`)), this.opts.timeoutMs)
      this.runTimers.set(ctrl.signal, t)
    }
    if (external) {
      if (external.aborted) ctrl.abort(external.reason)
      else external.addEventListener("abort", () => ctrl.abort(external.reason), { once: true })
    }
    return ctrl.signal
  }

  /** fetch with retry + exponential backoff on the default retryable set (429/500/502/503/504/529)
   * plus network errors and anything `retryableStatuses` added, honoring Retry-After; aborts via signal.
   * The retry-vs-fail decision is the host's `onError` (default: retryable-within-budget ⇒ retry). */
  private async llmFetch(url: string, init: RequestInit, signal: AbortSignal, out?: { failed?: boolean }): Promise<Response> {
    // Same loop as the streaming path; its retry events are simply dropped here.
    const it = this.llmFetchEvents(url, init, signal, out)
    for (;;) {
      const r = await it.next()
      if (r.done) return r.value
    }
  }

  /** The retry loop behind `llmFetch`, as a generator: yields a `retry` StreamEvent just before
   * each retry wait (its `delayMs` is the exact wait slept) and returns the final Response.
   * The streaming generators consume it with `yield*`. */
  private async *llmFetchEvents(url: string, init: RequestInit, signal: AbortSignal, out?: { failed?: boolean }): AsyncGenerator<StreamEvent, Response, unknown> {
    const retries = this.opts.retries ?? 2
    const base = this.opts.retryBaseMs ?? 500
    // Default classifier = today's behavior, expressed as an onError. A "retry" is always
    // capped by the budget below (attempt === retries stops regardless of the classifier).
    const classify = this.opts.onError ?? ((info: ErrorInfo): ErrorTier => (info.retryable ? "retry" : "fail"))
    let lastErr: unknown
    for (let attempt = 0; attempt <= retries; attempt++) {
      let res: Response
      try {
        res = await (this.opts.fetch ?? (await defaultFetch()))(url, { ...init, signal })
      } catch (e) {
        if (signal.aborted) throw e // abort/timeout: don't retry
        lastErr = e
        const tier = classify({ error: e, attempt, retryable: true })
        if (tier === "fail" || attempt === retries) throw e
        const wait = base * 2 ** attempt + Math.random() * 100
        yield { type: "retry", attempt: attempt + 1, max: retries, delayMs: wait, reason: "network" }
        await delay(wait, signal)
        continue
      }
      if (res.ok) return res
      let retryable = isRetryableStatus(res.status, this.opts.retryableStatuses)
      // The provider's own verdict wins over the status set (SPEC 8 x-should-retry).
      const sr = res.headers.get("x-should-retry")?.trim().toLowerCase()
      if (sr === "true") retryable = true
      else if (sr === "false") retryable = false
      const tier = classify({ status: res.status, attempt, retryable })
      if (tier === "fail" || attempt === retries) {
        if (out) out.failed = tier === "fail"
        return res // caller surfaces the non-ok status
      }
      const ra = retryAfterMs(res.headers.get("retry-after"))
      // `?? `, not `|| `: Retry-After: 0 means "retry now", not "no opinion".
      const wait = ra ?? base * 2 ** attempt + Math.random() * 100
      yield { type: "retry", attempt: attempt + 1, max: retries, delayMs: wait, status: res.status, reason: `HTTP ${res.status}` }
      await delay(wait, signal)
    }
    throw lastErr
  }

  /** §8 fallbackModels: the per-call model chain — [turnModel, ...fallbackModels], empty and
   * duplicate entries skipped; just [turnModel] when the option is absent or the kill switch is set. */
  private modelChain(turnModel: string): string[] {
    const fb = this.opts.fallbackModels
    if (!fb || fb.length === 0) return [turnModel]
    const kill = (typeof process !== "undefined" ? process.env?.TOOLNEXUS_DISABLE_MODEL_FALLBACK : undefined)?.trim().toLowerCase()
    if (kill === "1" || kill === "true") return [turnModel]
    const chain: string[] = [turnModel]
    for (const m of fb) if (m && !chain.includes(m)) chain.push(m)
    return chain
  }

  /** llmFetch over the model chain: each model spends the full retry budget; only a budget that
   * ends on 503/529 (not an onError "fail") advances to the next model. Returns the response and
   * the model that produced it. `build` rebuilds the same request for a model. */
  private async llmFetchChain(model: string, build: (m: string) => [string, RequestInit], signal: AbortSignal): Promise<{ res: Response; model: string }> {
    const it = this.llmFetchChainEvents(model, build, signal)
    for (;;) {
      const r = await it.next()
      if (r.done) return r.value
    }
  }

  /** The fallback chain as a generator for the streaming loops: the #189 `retry` events of each
   * model's attempts and a `fallback` event at each switch reach the stream as they happen. */
  private async *llmFetchChainEvents(model: string, build: (m: string) => [string, RequestInit], signal: AbortSignal): AsyncGenerator<StreamEvent, { res: Response; model: string }, unknown> {
    const chain = this.modelChain(model)
    for (let i = 0; ; i++) {
      const m = chain[i]
      const [url, init] = build(m)
      const out: { failed?: boolean } = {}
      const res = yield* this.llmFetchEvents(url, init, signal, out)
      if (res.ok || out.failed || i + 1 >= chain.length || (res.status !== 503 && res.status !== 529)) return { res, model: m }
      try { await res.body?.cancel() } catch { /* ignore */ }
      yield { type: "fallback", from: m, to: chain[i + 1] }
    }
  }


  /** Run one tool through beforeTool/afterTool hooks (mutate args, short-circuit, transform result). */
  private async runTool(toolkit: Toolkit | undefined, name: string, args: Record<string, unknown>, id: string | undefined, turn: number, signal?: AbortSignal) {
    if (this.opts.toolTiers && name === "tool_search") {
      // Engine-internal and read-only: no hooks (SPEC 8 Tool tiers).
      const t0 = Date.now()
      const result = toolSearch(toolkit?.tools() ?? [], (n) => toolkit?.get(n), args ?? {})
      this.emitTool(name, "builtin", result, t0)
      return { args, result }
    }
    // call_tool is unwrapped HERE, so hooks, hints and permission gates see the real tool. The
    // returned args keep the wrapper shape (with any beforeTool rewrite inside), so a 10
    // re-run unwraps the same way.
    const inner = this.unwrapCall(name, args)
    if (inner) {
      const r = await this.runToolDirect(toolkit, inner.name, inner.args, id, turn, signal)
      return { args: { name: inner.name, arguments: r.args }, result: r.result }
    }
    return this.runToolDirect(toolkit, name, args, id, turn, signal)
  }

  private async runToolDirect(toolkit: Toolkit | undefined, name: string, args: Record<string, unknown>, id: string | undefined, turn: number, signal?: AbortSignal) {
    const h = this.opts.hooks
    const tool = toolkit?.get(name)
    const source = tool?.source ?? "custom"
    const t0 = Date.now()
    let a = args
    if (h?.beforeTool) {
      const ov = await h.beforeTool({ name, args: a, id, turn, ...(tool?.hints ? { hints: tool.hints } : {}) })
      if (ov?.result) {
        // short-circuit (deny/cache/dry-run, or a guard-raised suspension — path B); still a tool
        // call for metrics, but a suspension is classified pending, never an error.
        this.emitTool(name, source, ov.result, t0)
        return { args: a, result: ov.result }
      }
      if (ov?.args) a = ov.args
    }
    // No toolkit ⇒ nothing was declared, so a tool call is the provider inventing one.
    let result: ToolResult = toolkit
      ? await toolkit.execute(name, a, { ...(signal ? { signal } : {}), ...(id ? { callId: id } : {}) })
      : { output: `unknown tool: ${name}`, isError: true }
    // A suspension (§10) is not a real result: skip afterTool's failure path on it — the resolved
    // result (post-waitFor) still flows through afterTool in resolvePending — and never count it as
    // a tool error.
    if (h?.afterTool && !pendingOf(result)) {
      const ov = await h.afterTool({ name, args: a, result, id, turn })
      if (ov?.result) result = ov.result
    }
    this.emitTool(name, source, result, t0)
    return { args: a, result }
  }

  /** Runs one response's calls under the tool schedule (SPEC §8 "Tool scheduling"). */
  private schedule<T, R>(
    toolkit: Toolkit | undefined,
    items: T[],
    key: (item: T) => [string, Record<string, unknown>],
    fn: (item: T) => Promise<R>,
  ): Promise<R[]> {
    return scheduleCalls(items, (item) => {
      const [name, args] = key(item)
      return this.callSafe(toolkit, name, args)
    }, resolveMaxToolConcurrency(this.opts.maxToolConcurrency), fn)
  }

  /** One call's safety; looks through `call_tool` to the tool it names. Unknown ⇒ unsafe. */
  private callSafe(toolkit: Toolkit | undefined, name: string, args: Record<string, unknown>): boolean {
    if (this.opts.toolTiers && name === "tool_search") return true
    const inner = this.unwrapCall(name, args ?? {})
    const tool = toolkit?.get(inner ? inner.name : name)
    return tool ? isConcurrencySafe(tool, inner ? inner.args : (args ?? {})) : false
  }

  /** ToolTiers `call_tool {name, arguments}` -> the real call; undefined when tiers are off. */
  private unwrapCall(name: string, args: Record<string, unknown>): { name: string; args: Record<string, unknown> } | undefined {
    return this.opts.toolTiers ? unwrapCallTool(name, args) : undefined
  }

  /** The RunResult.toolCalls entry: a `call_tool` call is recorded as the real tool with its
   *  inner arguments, the name hooks and stream events see (the transcript keeps the wrapper). */
  private toolCallRecord(name: string, args: Record<string, unknown>, result: ToolResult, callId?: string): ToolCallRecord {
    const inner = this.unwrapCall(name, args)
    const rec: ToolCallRecord = { name: inner ? inner.name : name, args: inner ? inner.args : args, output: result.output, isError: result.isError, metadata: result.metadata }
    if (result.parts?.length) rec.parts = result.parts
    callIds.set(rec, callId ?? "")
    return rec
  }

  /** The stream's tool_call event, naming the real tool for a `call_tool` call. */
  private callEvent(id: string, name: string, args: Record<string, unknown>): StreamEvent {
    const inner = this.unwrapCall(name, args)
    return inner ? { type: "tool_call", id, name: inner.name, args: inner.args, via: "call_tool" } : { type: "tool_call", id, name, args }
  }

  private resultEvent(id: string, name: string, args: Record<string, unknown>, result: ToolResult): StreamEvent {
    const inner = this.unwrapCall(name, args)
    return {
      type: "tool_result", id, name: inner ? inner.name : name, output: result.output, isError: result.isError,
      ...(result.metadata ? { metadata: result.metadata } : {}),
      ...(inner ? { via: "call_tool" as const } : {}),
    }
  }

  private plan(toolkit: Toolkit | undefined): ToolPlan | undefined {
    if (!this.opts.toolTiers || !toolkit) return undefined
    let p = this.tierPlans.get(toolkit)
    if (!p) {
      p = buildToolPlan(this.opts.toolTiers, toolkit.tools())
      this.tierPlans.set(toolkit, p)
    }
    return p
  }

  /** The tools array for a request: core tools (sorted) then tool_search and call_tool. Without
   * tiers it is every tool, as before. */
  private requestTools(toolkit: Toolkit | undefined): Tool[] {
    if (!toolkit) return []
    const p = this.plan(toolkit)
    if (!p) return toolkit.tools()
    const out: Tool[] = []
    for (const n of p.core) {
      const t = toolkit.get(n)
      if (t) out.push(t)
    }
    return [...out, p.search, p.call]
  }

  /** The frozen tier plan for `toolkit` (built on first use): tool name -> tier. Undefined when
   * `toolTiers` is not set. For a host UI or log. */
  toolTierPlan(toolkit: Toolkit): Record<string, ToolTier> | undefined {
    const p = this.plan(toolkit)
    return p ? Object.fromEntries(p.tier) : undefined
  }

  /** Forget `toolkit`'s plan so the next request rebuilds it - call at a new session start.
   * Mid-session it breaks the cached prefix. */
  resetToolTiers(toolkit: Toolkit): void {
    this.tierPlans.delete(toolkit)
  }

  /** Emit the `tool` metric, classifying a §10 suspension as `pending` (isError:false), not a failure. */
  private emitTool(name: string, source: string, result: ToolResult, t0: number) {
    const req = pendingOf(result)
    this.emit({ event: "tool", tool: name, source, isError: req ? false : result.isError, ms: Date.now() - t0, ...(req ? { pending: true } : {}) })
  }

  /** One non-streaming LLM call, with an `llm` metric event (ok/error + per-call tokens + ms). */
  private async llmCallJson(build: (m: string) => [string, RequestInit], signal: AbortSignal, style: ClientStyle, turnModel: string): Promise<{ data: any; model: string }> {
    const t0 = Date.now()
    let model = turnModel
    try {
      const r = await this.llmFetchChain(turnModel, build, signal)
      model = r.model
      const res = r.res
      if (!res.ok) throw await llmHttpError(res)
      const data: any = await res.json()
      const tok = perCall(data.usage, style)
      this.emit({ event: "llm", model, status: "ok", ms: Date.now() - t0, promptTokens: tok.prompt, completionTokens: tok.completion })
      return { data, model }
    } catch (e) {
      this.emit({ event: "llm", model, status: "error", ms: Date.now() - t0, promptTokens: 0, completionTokens: 0 })
      throw e
    }
  }

  /**
   * SPEC §7F.1: one LLM call with reactive compaction. `call(messages)` makes the request and
   * throws on a non-2xx (as `LlmHttpError`). On a context overflow with `compactOnOverflow` set,
   * the hook compacts and the SAME turn is re-sent (no beforeLLM, no turn consumed), guarded by
   * the rapid-refill breaker. Returns the call's value and the (possibly compacted) transcript.
   */
  private async callWithOverflow<T>(st: OverflowState, ev: BeforeLLMEvent, call: (messages: any[]) => Promise<T>): Promise<{ value: T; messages: any[] }> {
    const it = this.callWithOverflowEvents(st, ev, async function* (m: any[]) { return await call(m) })
    for (;;) {
      const r = await it.next()
      if (r.done) return r.value
    }
  }

  /** `callWithOverflow` for the streaming loops: `call` is a generator so the #189 `retry` events
   * of every attempt (including one after a compaction) still reach the stream before each wait. */
  private async *callWithOverflowEvents<T>(st: OverflowState, ev: BeforeLLMEvent, call: (messages: any[]) => AsyncGenerator<StreamEvent, T, unknown>): AsyncGenerator<StreamEvent, { value: T; messages: any[] }, unknown> {
    let messages = ev.messages
    for (;;) {
      try {
        const value = yield* call(messages)
        st.okSince++
        return { value, messages }
      } catch (e) {
        const hook = this.opts.compactOnOverflow
        if (!hook || !(e instanceof LlmHttpError) || !isContextOverflow(e.status, e.body)) throw e
        if (!(st.streak > 0 && st.okSince < 2)) st.streak = 0
        if (st.streak === 3) throw new ContextOverflowError(3, e.status, e.body)
        const ov = await hook({ messages, tools: ev.tools, model: ev.model, turn: ev.turn })
        if (!ov?.messages || ov.messages.length >= messages.length) throw new ContextOverflowError(st.streak, e.status, e.body)
        messages = ov.messages
        st.streak++
        st.okSince = 0
      }
    }
  }

  // The run-scoped timeout timer is owned by these four wrappers, so it is cleared however the
  // run leaves: return, throw, or a consumer abandoning the stream half-way.
  private async runOpenAI(prompt: PromptInput, toolkit: Toolkit | undefined, external?: AbortSignal, history?: any[], onInterrupt?: OnInterrupt): Promise<RunResult> {
    const signal = this.makeSignal(external)
    try {
      return await this.runOpenAIWithSignal(signal, prompt, toolkit, history, onInterrupt)
    } finally {
      this.releaseSignal(signal)
    }
  }

  private async runAnthropic(prompt: PromptInput, toolkit: Toolkit | undefined, external?: AbortSignal, history?: any[], onInterrupt?: OnInterrupt): Promise<RunResult> {
    const signal = this.makeSignal(external)
    try {
      return await this.runAnthropicWithSignal(signal, prompt, toolkit, history, onInterrupt)
    } finally {
      this.releaseSignal(signal)
    }
  }

  private async *streamOpenAI(prompt: PromptInput, toolkit: Toolkit | undefined, external?: AbortSignal, history?: any[], onInterrupt?: OnInterrupt): AsyncGenerator<StreamEvent, void, unknown> {
    const signal = this.makeSignal(external)
    try {
      yield* this.streamOpenAIWithSignal(signal, prompt, toolkit, history, onInterrupt)
    } finally {
      this.releaseSignal(signal)
    }
  }

  private async *streamAnthropic(prompt: PromptInput, toolkit: Toolkit | undefined, external?: AbortSignal, history?: any[], onInterrupt?: OnInterrupt): AsyncGenerator<StreamEvent, void, unknown> {
    const signal = this.makeSignal(external)
    try {
      yield* this.streamAnthropicWithSignal(signal, prompt, toolkit, history, onInterrupt)
    } finally {
      this.releaseSignal(signal)
    }
  }

  /**
   * §8 stop-reason recovery: classify the response just appended to `messages` and, when a rule
   * fires, remove (or keep) that assistant message, append the pinned user message, and return
   * true — the caller then takes another turn. Never on the last permitted turn.
   */
  private recover(rs: Recovery | undefined, turn: number, messages: any[], hasCalls: boolean, malformed: boolean, truncated: boolean, text: string, appended = true): boolean {
    if (!rs || turn >= (this.opts.maxTurns ?? 10) - 1) return false
    let msg: string
    if (hasCalls) {
      if (!malformed || rs.corrected) return false
      rs.corrected = true
      if (appended) messages.pop()
      msg = CORRECTIVE
    } else if (truncated) {
      if (rs.continues >= 3) return false
      rs.continues++
      rs.carry += text
      msg = CONTINUE
    } else {
      if (text.trim() !== "" || rs.nudged) return false
      rs.nudged = true
      if (appended) messages.pop()
      msg = NUDGE
    }
    messages.push({ role: "user", content: msg })
    return true
  }

  private newRecovery(): Recovery | undefined {
    return this.opts.stopRecovery ? { nudged: false, continues: 0, corrected: false, carry: "" } : undefined
  }

  // ---- OpenAI-style: POST {baseUrl}/chat/completions ----
  private async runOpenAIWithSignal(signal: AbortSignal, prompt: PromptInput, toolkit: Toolkit | undefined, history?: any[], onInterrupt?: OnInterrupt): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const runStart = Date.now()
    let messages: any[] = history && history.length ? [...history] : []
    if (!messages.length) {
      const system = this.system(toolkit)
      if (system) messages.push({ role: "system", content: system })
    }
    messages.push({ role: "user", content: prompt })
    let tools = toOpenAI(this.requestTools(toolkit))
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0
    // The model of the last call this run made (a beforeLLM override when one applied) — §8.
    let lastModel = this.opts.model
    // Results finished in the current tool phase, by call id — what an interrupted save keeps.
    const done = new Map<string, ToolResult>()
    const rs = this.newRecovery()
    const overflow: OverflowState = { okSince: 0, streak: 0 }

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        let turnModel = this.opts.model
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
          if (ov?.model) turnModel = ov.model
        }
        lastModel = turnModel
        const sent = await this.callWithOverflow(overflow, { messages, tools, model: turnModel, turn }, (msgs) => this.llmCallJson((m) => [`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
          method: "POST",
          headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
          body: JSON.stringify(this.openaiBody(msgs, tools, false, m)),
        }], signal, "openai", turnModel))
        messages = sent.messages
        const data: any = sent.value.data
        turnModel = lastModel = sent.value.model
        addUsage(usage, data.usage, "openai")
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: turnModel, turn })
        const msg = data.choices[0].message
        messages.push(msg)
        const calls = msg.tool_calls ?? []
        if (this.recover(rs, turn, messages, calls.length > 0, calls.some((c: any) => malformedArgs(c.function?.arguments)),
          data.choices[0].finish_reason === "length", typeof msg.content === "string" ? msg.content : "")) continue
        if (calls.length === 0) {
          const text = (rs?.carry ?? "") + (msg.content ?? "")
          if (await this.roundEnd(messages, turn, true, text)) { if (rs) rs.carry = ""; continue }
          return this.endRun(runStart, lastModel, text, messages, toolCalls, turns, usage)
        }
        if (rs) rs.carry = ""
        // execute all tool calls in this turn concurrently (true parallel tool calling)
        done.clear()
        const settled = await untilAborted(signal, this.schedule(toolkit,
          calls, (call: any) => [call.function.name, safeJson(call.function.arguments)], async (call: any) => {
            let { args, result } = await this.runTool(toolkit, call.function.name, safeJson(call.function.arguments), call.id, turn, signal)
            let halted: Request | undefined
            const req = pendingOf(result)
            if (req) {
              const r = await this.resolvePending(toolkit, call.function.name, args, req, call.id, turn, signal)
              result = r.result
              halted = r.halted
            }
            if (!halted && call.id) done.set(call.id, result)
            return { call, args, result, halted }
          },
        ))
        // Record in tool-call order; on the FIRST durable halt, surface it and stop — deterministic,
        // and the later suspensions' placeholder results never enter the transcript (they re-suspend
        // on resume). Mirrors the streaming path.
        for (const s of settled) {
          toolCalls.push(this.toolCallRecord(s.call.function.name, s.args, s.result, s.call.id))
          messages.push(toolMessage(s.call.id, s.call.function.name, s.result))
          if (s.halted) return this.pendingRun(runStart, lastModel, s.halted, messages, toolCalls, turns, usage)
        }
        await this.roundEnd(messages, turn, false, "")
      }
      return this.endRun(runStart, lastModel, lastText(messages), messages, toolCalls, turns, usage, true)
    } catch (e) {
      this.emitRunError(runStart, lastModel, toolCalls, turns, usage, e)
      onInterrupt?.(interruptedTranscript(messages, this.opts.style === "anthropic", done))
      throw e
    }
  }

  /** §8 afterRound: fire the round-boundary hook and append its injections as user messages.
   * Returns true when something was appended. A final round fires only when a turn remains. */
  private async roundEnd(messages: any[], turn: number, final: boolean, text: string): Promise<boolean> {
    const h = this.opts.hooks?.afterRound
    if (!h) return false
    if (final && turn + 1 >= (this.opts.maxTurns ?? 10)) return false
    const out = await h({ turn, final, text, messages })
    let added = false
    for (const m of out?.inject ?? []) {
      if (typeof m === "string" && m !== "") { messages.push({ role: "user", content: m }); added = true }
    }
    return added
  }

  private result(model: string, text: string, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage, exhausted = false): RunResult {
    const incomplete = exhausted && text === ""
    const r: RunResult = { text, messages, toolCalls, files: runFiles(toolCalls), toolCallCount: toolCalls.length, turns, usage, model, status: incomplete ? "incomplete" : "done" }
    if (incomplete) r.limit = "maxTurns"
    return r
  }

  /** §10: a run halted because a tool suspended and no `waitFor` was configured. */
  private pendingRun(runStart: number, model: string, request: Request, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage): RunResult {
    this.emit({ event: "run", model, turns, toolCalls: toolCalls.length, totalTokens: usage.totalTokens, ms: Date.now() - runStart })
    return { text: request.prompt, messages, toolCalls, files: runFiles(toolCalls), toolCallCount: toolCalls.length, turns, usage, model, status: "pending", pending: request }
  }

  /**
   * §10: resolve a suspended tool result. Calls `waitFor(request)`, and on `ok` re-executes the
   * tool once with `Context.answer = answer`. Returns the resolved result, or `{ halted }` when
   * no `waitFor` is configured (the run should stop and surface the request).
   */
  private async resolvePending(
    toolkit: Toolkit | undefined, name: string, args: Record<string, unknown>, request: Request, id: string | undefined, turn: number, signal?: AbortSignal,
  ): Promise<{ result: ToolResult; halted?: Request }> {
    const inner = this.unwrapCall(name, args)
    if (inner) ({ name, args } = inner)
    if (!this.opts.waitFor) return { result: { output: request.prompt, isError: true }, halted: request }
    const answer = await this.opts.waitFor(request, { signal: signal ?? new AbortController().signal })
    if (!answer?.ok) return { result: { output: `declined/expired: ${request.prompt}`, isError: true } }
    const t0 = Date.now()
    let result: ToolResult = toolkit
      ? await toolkit.execute(name, args, { answer, ...(signal ? { signal } : {}), ...(id ? { callId: id } : {}) })
      : { output: `unknown tool: ${name}`, isError: true }
    if (this.opts.hooks?.afterTool) {
      const ov = await this.opts.hooks.afterTool({ name, args, result, id, turn })
      if (ov?.result) result = ov.result
    }
    this.emitTool(name, toolkit?.get(name)?.source ?? "custom", result, t0)
    if (pendingOf(result)) result = { output: `unresolved: ${request.prompt}`, isError: true } // never loop forever
    return { result }
  }

  // ---- Anthropic-style: POST {baseUrl}/messages ----
  private async runAnthropicWithSignal(signal: AbortSignal, prompt: PromptInput, toolkit: Toolkit | undefined, history?: any[], onInterrupt?: OnInterrupt): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    const runStart = Date.now()
    let messages: any[] = history && history.length ? [...history, { role: "user", content: prompt }] : [{ role: "user", content: prompt }]
    let tools = toAnthropic(this.requestTools(toolkit))
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0
    // The model of the last call this run made (a beforeLLM override when one applied) — §8.
    let lastModel = this.opts.model
    // Results finished in the current tool phase, by call id — what an interrupted save keeps.
    const done = new Map<string, ToolResult>()
    const rs = this.newRecovery()
    const overflow: OverflowState = { okSince: 0, streak: 0 }

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        let turnModel = this.opts.model
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
          if (ov?.model) turnModel = ov.model
        }
        lastModel = turnModel
        const sent = await this.callWithOverflow(overflow, { messages, tools, model: turnModel, turn }, (msgs) => this.llmCallJson((m) => [endpoint, {
          method: "POST",
          headers: {
            "x-api-key": key,
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            ...this.opts.headers,
          },
          body: JSON.stringify(this.anthropicBody(system, msgs, tools, false, m)),
        }], signal, "anthropic", turnModel))
        messages = sent.messages
        const data: any = sent.value.data
        turnModel = lastModel = sent.value.model
        addUsage(usage, data.usage, "anthropic")
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: turnModel, turn })
        // §8.x: Anthropic rejects empty text blocks in history; an all-empty reply is not stored
        const kept = (data.content ?? []).filter((b: any) => !(b.type === "text" && b.text === ""))
        const appended = kept.length > 0
        if (appended) messages.push({ role: "assistant", content: kept })
        const uses = (data.content ?? []).filter((b: any) => b.type === "tool_use")
        const text = (data.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
        if (this.recover(rs, turn, messages, uses.length > 0, uses.some((u: any) => u.input !== undefined && !isObj(u.input)),
          data.stop_reason === "max_tokens", text, appended)) continue
        if (uses.length === 0) {
          const full = (rs?.carry ?? "") + text
          if (await this.roundEnd(messages, turn, true, full)) { if (rs) rs.carry = ""; continue }
          return this.endRun(runStart, lastModel, full, messages, toolCalls, turns, usage)
        }
        if (rs) rs.carry = ""
        // execute all tool_use blocks in this turn concurrently (true parallel tool calling)
        done.clear()
        const settled = await untilAborted(signal, this.schedule(toolkit,
          uses, (use: any) => [use.name, objArgs(use.input)], async (use: any) => {
            let { args, result } = await this.runTool(toolkit, use.name, objArgs(use.input), use.id, turn, signal)
            let halted: Request | undefined
            const req = pendingOf(result)
            if (req) {
              const r = await this.resolvePending(toolkit, use.name, args, req, use.id, turn, signal)
              result = r.result
              halted = r.halted
            }
            if (!halted && use.id) done.set(use.id, result)
            return { use, args, result, halted }
          },
        ))
        // Record in tool-call order; on the FIRST durable halt, include that tool_result block, stop
        // building the content, and surface it — deterministic (G3), later suspensions re-suspend on
        // resume. Mirrors the OpenAI path and the streaming loops.
        const content: any[] = []
        let halted: Request | undefined
        for (const s of settled) {
          toolCalls.push(this.toolCallRecord(s.use.name, s.args, s.result, s.use.id))
          content.push(toolResultBlock(s.use.id, s.result))
          if (s.halted) { halted = s.halted; break }
        }
        messages.push({ role: "user", content })
        if (halted) return this.pendingRun(runStart, lastModel, halted, messages, toolCalls, turns, usage)
        await this.roundEnd(messages, turn, false, "")
      }
      return this.endRun(runStart, lastModel, "", messages, toolCalls, turns, usage, true)
    } catch (e) {
      this.emitRunError(runStart, lastModel, toolCalls, turns, usage, e)
      onInterrupt?.(interruptedTranscript(messages, this.opts.style === "anthropic", done))
      throw e
    }
  }

  // ---- Streaming: OpenAI-style ----
  private async *streamOpenAIWithSignal(signal: AbortSignal, prompt: PromptInput, toolkit: Toolkit | undefined, history?: any[], onInterrupt?: OnInterrupt): AsyncGenerator<StreamEvent, void, unknown> {
    const key = resolveKey(this.opts)
    const runStart = Date.now()
    let messages: any[] = history && history.length ? [...history] : []
    if (!messages.length) {
      const system = this.system(toolkit)
      if (system) messages.push({ role: "system", content: system })
    }
    messages.push({ role: "user", content: prompt })
    let tools = toOpenAI(this.requestTools(toolkit))
    const toolCalls: ToolCallRecord[] = []
    // Agent-side media from an in-process answer (§8C ACP content parts), after the loop's own.
    const agentFiles: RunFile[] = []
    const withAgentFiles = (r: RunResult): RunResult => (agentFiles.length ? { ...r, files: [...r.files, ...agentFiles] } : r)
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0
    // The model of the last call this run made (a beforeLLM override when one applied) — §8.
    let lastModel = this.opts.model
    // Results finished in the current tool phase, by call id — what an interrupted save keeps.
    const done = new Map<string, ToolResult>()
    const rs = this.newRecovery()
    const overflow: OverflowState = { okSince: 0, streak: 0 }

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        let turnModel = this.opts.model
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
          if (ov?.model) turnModel = ov.model
        }
        lastModel = turnModel
        const t0 = Date.now()
        const beforeP = usage.promptTokens, beforeC = usage.completionTokens
        let content = ""
        let finish = ""
        const acc = new Map<number, { id: string; name: string; args: string }>()
        try {
          const sent = yield* this.callWithOverflowEvents(overflow, { messages, tools, model: turnModel, turn }, (msgs) => (async function* (self: Client) {
            const r = yield* self.llmFetchChainEvents(turnModel, (m) => [`${self.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
              method: "POST",
              headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...self.opts.headers },
              body: JSON.stringify(self.openaiBody(msgs, tools, true, m)),
            }], signal)
            if (!r.res.ok || !r.res.body) throw await llmHttpError(r.res)
            return r
          })(this))
          messages = sent.messages
          const res = sent.value.res
          turnModel = lastModel = sent.value.model
          if (!res.body) throw await llmHttpError(res)
          for await (const line of sseLines(res.body)) {
            if (!line.startsWith("data:")) continue
            const payload = line.slice(5).trim()
            if (payload === "[DONE]") break
            const j = safeParse(payload)
            // A non-text event from an in-process streaming generate rides in-band so it keeps
            // its order and arrives as it happens; it goes straight to the consumer and is never
            // executed. Honoured only on the in-process sentinel URL, never from a real server.
            if (j && j[IN_PROCESS_SIDE_KEY] && this.opts.baseUrl === IN_PROCESS_BASE_URL) {
              yield j[IN_PROCESS_SIDE_KEY] as StreamEvent
              continue
            }
            if (j && j[IN_PROCESS_FILES_KEY] && this.opts.baseUrl === IN_PROCESS_BASE_URL) {
              agentFiles.push(...j[IN_PROCESS_FILES_KEY])
              continue
            }
            if (j?.usage) addUsage(usage, j.usage, "openai")
            const choice = j?.choices?.[0]
            const d = choice?.delta
            if (choice?.finish_reason) finish = choice.finish_reason
            if (d?.content) { content += d.content; yield { type: "text", delta: d.content } }
            for (const tc of d?.tool_calls ?? []) {
              const slot = acc.get(tc.index) ?? { id: "", name: "", args: "" }
              if (tc.id) slot.id = tc.id
              if (tc.function?.name) slot.name += tc.function.name
              if (tc.function?.arguments) slot.args += tc.function.arguments
              acc.set(tc.index, slot)
            }
          }
        } catch (e) {
          this.emit({ event: "llm", model: turnModel, status: "error", ms: Date.now() - t0, promptTokens: 0, completionTokens: 0 })
          throw e
        }
        this.emit({ event: "llm", model: turnModel, status: "ok", ms: Date.now() - t0, promptTokens: usage.promptTokens - beforeP, completionTokens: usage.completionTokens - beforeC })
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: { streamed: true, usage }, model: turnModel, turn })

        const calls = [...acc.values()]
        messages.push(calls.length === 0
          ? { role: "assistant", content }
          : { role: "assistant", content: content || null, tool_calls: calls.map((c) => ({ id: c.id, type: "function", function: { name: c.name, arguments: c.args } })) })
        if (this.recover(rs, turn, messages, calls.length > 0, calls.some((c) => malformedArgs(c.args)), finish === "length", content)) continue
        if (calls.length === 0) {
          if (await this.roundEnd(messages, turn, true, (rs?.carry ?? "") + content)) { if (rs) rs.carry = ""; continue }
          yield { type: "usage", usage }
          yield { type: "done", result: withAgentFiles(this.endRun(runStart, lastModel, (rs?.carry ?? "") + content, messages, toolCalls, turns, usage)) }
          return
        }
        if (rs) rs.carry = ""
        for (const c of calls) yield this.callEvent(c.id, c.name, safeJson(c.args))
        done.clear()
        const settled = await untilAborted(signal, this.schedule(toolkit, calls, (c) => [c.name, safeJson(c.args)], async (c) => {
          const r = await this.runTool(toolkit, c.name, safeJson(c.args), c.id, turn, signal)
                    if (!pendingOf(r.result) && c.id) done.set(c.id, r.result)
          return r
        }))
        for (let i = 0; i < calls.length; i++) {
          const c = calls[i]
          let { args, result } = settled[i]
          const req = pendingOf(result)
          if (req) {
            yield { type: "pending", request: req } // surface BEFORE waitFor so a channel can push the link
            const r = await this.resolvePending(toolkit, c.name, args, req, c.id, turn, signal)
            result = r.result
            if (r.halted) {
              toolCalls.push(this.toolCallRecord(c.name, args, result, c.id))
              messages.push(toolMessage(c.id, c.name, result))
              yield { type: "done", result: withAgentFiles(this.pendingRun(runStart, lastModel, r.halted, messages, toolCalls, turns, usage)) }
              return
            }
          }
          toolCalls.push(this.toolCallRecord(c.name, args, result, c.id))
          messages.push(toolMessage(c.id, c.name, result))
          yield this.resultEvent(c.id, c.name, safeJson(c.args), result)
        }
        await this.roundEnd(messages, turn, false, "")
      }
      yield { type: "done", result: withAgentFiles(this.endRun(runStart, lastModel, lastText(messages), messages, toolCalls, turns, usage, true)) }
    } catch (e) {
      this.emitRunError(runStart, lastModel, toolCalls, turns, usage, e)
      onInterrupt?.(interruptedTranscript(messages, this.opts.style === "anthropic", done))
      throw e
    }
  }

  // ---- Streaming: Anthropic-style ----
  private async *streamAnthropicWithSignal(signal: AbortSignal, prompt: PromptInput, toolkit: Toolkit | undefined, history?: any[], onInterrupt?: OnInterrupt): AsyncGenerator<StreamEvent, void, unknown> {
    const key = resolveKey(this.opts)
    const runStart = Date.now()
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    let messages: any[] = history && history.length ? [...history, { role: "user", content: prompt }] : [{ role: "user", content: prompt }]
    let tools = toAnthropic(this.requestTools(toolkit))
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0
    // The model of the last call this run made (a beforeLLM override when one applied) — §8.
    let lastModel = this.opts.model
    // Results finished in the current tool phase, by call id — what an interrupted save keeps.
    const done = new Map<string, ToolResult>()
    const rs = this.newRecovery()
    const overflow: OverflowState = { okSince: 0, streak: 0 }

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        let turnModel = this.opts.model
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
          if (ov?.model) turnModel = ov.model
        }
        lastModel = turnModel
        const t0 = Date.now()
        const beforeP = usage.promptTokens, beforeC = usage.completionTokens
        const blocks = new Map<number, { type: string; text?: string; id?: string; name?: string; json?: string }>()
        let stopReason = ""
        try {
          const sent = yield* this.callWithOverflowEvents(overflow, { messages, tools, model: turnModel, turn }, (msgs) => (async function* (self: Client) {
            const r = yield* self.llmFetchChainEvents(turnModel, (m) => [endpoint, {
              method: "POST",
              headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "Content-Type": "application/json", ...self.opts.headers },
              body: JSON.stringify(self.anthropicBody(system, msgs, tools, true, m)),
            }], signal)
            if (!r.res.ok || !r.res.body) throw await llmHttpError(r.res)
            return r
          })(this))
          messages = sent.messages
          const res = sent.value.res
          turnModel = lastModel = sent.value.model
          if (!res.body) throw await llmHttpError(res)
          for await (const line of sseLines(res.body)) {
            if (!line.startsWith("data:")) continue
            const j = safeParse(line.slice(5).trim())
            if (!j) continue
            if (j.type === "message_start") addUsage(usage, j.message?.usage, "anthropic")
            else if (j.type === "content_block_start") blocks.set(j.index, { type: j.content_block.type, id: j.content_block.id, name: j.content_block.name, text: "", json: "" })
            else if (j.type === "content_block_delta") {
              const b = blocks.get(j.index)!
              if (j.delta.type === "text_delta") { b.text += j.delta.text; yield { type: "text", delta: j.delta.text } }
              else if (j.delta.type === "input_json_delta") b.json += j.delta.partial_json
            } else if (j.type === "message_delta") { stopReason = j.delta?.stop_reason ?? stopReason; addUsage(usage, j.usage, "anthropic") }
          }
        } catch (e) {
          this.emit({ event: "llm", model: turnModel, status: "error", ms: Date.now() - t0, promptTokens: 0, completionTokens: 0 })
          throw e
        }
        this.emit({ event: "llm", model: turnModel, status: "ok", ms: Date.now() - t0, promptTokens: usage.promptTokens - beforeP, completionTokens: usage.completionTokens - beforeC })
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: { streamed: true, usage }, model: turnModel, turn })

        const content = [...blocks.values()]
          .filter((b) => b.type === "tool_use" || b.text !== "") // §8.x: Anthropic rejects empty text blocks in history
          .map((b) => b.type === "tool_use" ? { type: "tool_use", id: b.id, name: b.name, input: safeJson(b.json || "{}") } : { type: "text", text: b.text })
        const appended = content.length > 0 // §8.x: an all-empty reply is not stored
        if (appended) messages.push({ role: "assistant", content })
        const uses = content.filter((b: any) => b.type === "tool_use")
        const hasCalls = stopReason === "tool_use" && uses.length > 0
        const text = content.filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
        if (this.recover(rs, turn, messages, hasCalls, [...blocks.values()].some((b) => b.type === "tool_use" && malformedArgs(b.json)),
          stopReason === "max_tokens", text, appended)) continue
        if (!hasCalls) {
          if (await this.roundEnd(messages, turn, true, (rs?.carry ?? "") + text)) { if (rs) rs.carry = ""; continue }
          yield { type: "usage", usage }
          yield { type: "done", result: this.endRun(runStart, lastModel, (rs?.carry ?? "") + text, messages, toolCalls, turns, usage) }
          return
        }
        if (rs) rs.carry = ""
        for (const u of uses as any[]) yield this.callEvent(u.id, u.name, u.input)
        done.clear()
        const settled = await untilAborted(signal, this.schedule(toolkit, uses as any[], (u: any) => [u.name, u.input ?? {}], async (u: any) => {
          const r = await this.runTool(toolkit, u.name, u.input ?? {}, u.id, turn, signal)
          if (!pendingOf(r.result) && u.id) done.set(u.id, r.result)
          return r
        }))
        const results: any[] = []
        for (let i = 0; i < uses.length; i++) {
          const u = uses[i] as any
          let { args, result } = settled[i]
          const req = pendingOf(result)
          if (req) {
            yield { type: "pending", request: req }
            const r = await this.resolvePending(toolkit, u.name, args, req, u.id, turn, signal)
            result = r.result
            if (r.halted) {
              toolCalls.push(this.toolCallRecord(u.name, args, result, u.id))
              results.push(toolResultBlock(u.id, result))
              messages.push({ role: "user", content: results })
              yield { type: "done", result: this.pendingRun(runStart, lastModel, r.halted, messages, toolCalls, turns, usage) }
              return
            }
          }
          toolCalls.push(this.toolCallRecord(u.name, args, result, u.id))
          results.push(toolResultBlock(u.id, result))
          yield this.resultEvent(u.id, u.name, u.input, result)
        }
        messages.push({ role: "user", content: results })
        await this.roundEnd(messages, turn, false, "")
      }
      yield { type: "done", result: this.endRun(runStart, lastModel, "", messages, toolCalls, turns, usage, true) }
    } catch (e) {
      this.emitRunError(runStart, lastModel, toolCalls, turns, usage, e)
      onInterrupt?.(interruptedTranscript(messages, this.opts.style === "anthropic", done))
      throw e
    }
  }
}

export function createClient(opts: ClientOptions): Client {
  return new Client(opts)
}


// --------------------------------------------------------------------------- //
// In-process models (SPEC §8 Gap 2, semantic form).
// --------------------------------------------------------------------------- //

/** One tool call an in-process model asks for. Flat on purpose: the nested
 *  `function: {}` wrapper is a wire detail, not something a model author should type.
 *  `arguments` may be a structured value (encoded for you) or an encoded string. */
export interface InProcessToolCall {
  id?: string
  name: string
  arguments?: unknown
}

/** What an in-process model is handed: the assembled request for THIS call. */
export interface InProcessRequest {
  messages: any[]
  tools?: any[]
  model: string
  /** Every other key the client assembled, for a model that wants them. */
  body: Record<string, any>
}

/** What an in-process model returns: exactly one assistant message. Return `content`
 *  to finish, or `toolCalls` to ask for tools — never both. `usage` is optional; when
 *  omitted the run reports zero tokens rather than failing. */
export interface InProcessResponse {
  content?: string
  toolCalls?: InProcessToolCall[]
  usage?: { promptTokens?: number; completionTokens?: number; totalTokens?: number }
  /** Media the model's own agent-side tools or messages produced (e.g. an ACP agent's
   *  images). Appended to RunResult.files after the loop's own tool files. */
  files?: RunFile[]
}

/** A streaming in-process model: `emit` each event as it happens — `text` deltas become
 *  the streamed answer as they arrive; any other event (thought, an agent's own tool_call /
 *  tool_result) goes straight to the stream consumer and is never executed by the loop.
 *  Return the whole message when done (its `toolCalls`, if any, are what the loop runs). */
export type InProcessGenerateStream = (
  request: InProcessRequest,
  emit: (event: StreamEvent) => void,
) => InProcessResponse | Promise<InProcessResponse>

type InProcessGenerate = (request: InProcessRequest) => InProcessResponse | Promise<InProcessResponse>

/** Options for {@link createInProcessClient}: every client option EXCEPT the three
 *  that describe a wire — `baseUrl`, `apiKey` and `style` — plus `generate` and/or
 *  `generateStream` (at least one). */
export type InProcessOptions = Omit<ClientOptions, "baseUrl" | "apiKey" | "style" | "fetch"> & {
  /** Your model. Receives the assembled request, returns one assistant message. */
  generate?: InProcessGenerate
  /** Your model, streaming for real. With it, `stream()` is incremental; without it a
   *  `stream()` on a plain `generate` is refused. When both are set, `generate` serves
   *  non-streaming calls; alone, it serves them too (with a no-op `emit`). */
  generateStream?: InProcessGenerateStream
}

/** The sentinel base URL. Never dialled — the transport below answers every request
 *  before the network is reached — but the client builds a URL string internally, so
 *  it must be syntactically valid. `.invalid` is reserved by RFC 2606 precisely so a
 *  name can never resolve. */
const IN_PROCESS_BASE_URL = "http://in-process.invalid/v1"

/** The in-band SSE key that carries a non-text stream event from an in-process
 *  streaming generate to the consumer (honoured only on {@link IN_PROCESS_BASE_URL}). */
const IN_PROCESS_SIDE_KEY = "toolnexus_event"

/** The in-band SSE key carrying an in-process answer's `files` to `stream()` (same sentinel rule). */
const IN_PROCESS_FILES_KEY = "toolnexus_files"

/** Per-`run()` sink for an in-process answer's `files` — they are agent-side, not loop tool calls. */
const agentFilesSink = new AsyncLocalStorage<RunFile[]>()

/**
 * Build a `fetch`-shaped adapter over a semantic `generate` function (ADR 0030):
 * the reusable piece of {@link createInProcessClient} — assembles the wire request,
 * calls `generate`, and re-encodes its answer as an OpenAI-shaped `Response`. Every
 * caller that wants an in-process model behind a `fetch` slot (the top-level client,
 * or the agent runtime's `inProcess` option) goes through this ONE function, so there
 * is no second copy of the request/response assembly to drift.
 *
 * Streaming with only a plain `generate` is refused rather than faked — see the thrown
 * error. With `generateStream` it streams for real: each text event is written to the
 * SSE body the moment it is emitted.
 */
export function createInProcessFetch(generate?: InProcessGenerate, generateStream?: InProcessGenerateStream): typeof fetch {
  if (typeof generate !== "function" && typeof generateStream !== "function") {
    throw new Error("toolnexus: createInProcessFetch requires a `generate` (or `generateStream`) function")
  }

  const inProcessFetch: typeof fetch = async (_url, init) => {
    const body = JSON.parse(String((init as any)?.body ?? "{}"))
    const request: InProcessRequest = { messages: body.messages ?? [], tools: body.tools, model: body.model, body }

    if (body.stream && generateStream) return inProcessStream(generateStream, request)

    // A generate() returns a whole answer, so a stream would be one chunk pretending
    // to be many. Content and delta-count assertions cannot tell that from a real
    // stream, so refuse instead of degrading silently.
    if (body.stream) {
      throw new Error(
        "toolnexus: createInProcessClient does not support streaming — `generate` returns a " +
          "complete answer. Use the non-streaming run, or supply a `fetch`/transport that streams.",
      )
    }

    const answer = generate ? await generate(request) : await generateStream!(request, () => {})
    if (answer.files?.length) agentFilesSink.getStore()?.push(...answer.files)

    const message: Record<string, any> = { role: "assistant" }
    if (answer.toolCalls && answer.toolCalls.length > 0) {
      message.tool_calls = wireToolCalls(answer.toolCalls, false)
    } else {
      message.content = answer.content ?? ""
    }

    const u = answer.usage ?? {}
    const prompt = u.promptTokens ?? 0
    const completion = u.completionTokens ?? 0
    return new Response(
      JSON.stringify({
        choices: [{ index: 0, message, finish_reason: message.tool_calls ? "tool_calls" : "stop" }],
        usage: {
          prompt_tokens: prompt,
          completion_tokens: completion,
          total_tokens: u.totalTokens ?? prompt + completion,
        },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }

  return inProcessFetch
}

function wireToolCalls(calls: InProcessToolCall[], indexed: boolean): any[] {
  return calls.map((c, i) => ({
    ...(indexed ? { index: i } : {}),
    id: c.id ?? `call_${i}`,
    type: "function",
    function: {
      name: c.name,
      // A string is already encoded; anything else is encoded here so a model
      // author never has to think about the wire.
      arguments: typeof c.arguments === "string" ? c.arguments : JSON.stringify(c.arguments ?? {}),
    },
  }))
}

/** Runs a streaming generate and answers with an OpenAI SSE body written AS EVENTS ARRIVE:
 *  text → a content delta; any other event → an in-band side chunk for the consumer; then
 *  (tool calls as one delta | the content as one delta when no text was emitted), the
 *  finish_reason chunk, usage, and [DONE]. */
function inProcessStream(generateStream: InProcessGenerateStream, request: InProcessRequest): Response {
  const enc = new TextEncoder()
  let open = true
  const sse = new ReadableStream<Uint8Array>({
    start(controller) {
      const chunk = (v: unknown) => {
        if (open) controller.enqueue(enc.encode(`data: ${JSON.stringify(v)}\n\n`))
      }
      let sawText = false
      void (async () => {
        try {
          const answer = await generateStream(request, (ev) => {
            if (!ev || typeof ev !== "object") return
            if (ev.type === "text") {
              if (ev.delta) {
                sawText = true
                chunk({ choices: [{ index: 0, delta: { content: ev.delta } }] })
              }
              return
            }
            chunk({ [IN_PROCESS_SIDE_KEY]: ev })
          })
          if (answer.files?.length) chunk({ [IN_PROCESS_FILES_KEY]: answer.files })
          let finish = "stop"
          if (answer.toolCalls && answer.toolCalls.length > 0) {
            chunk({ choices: [{ index: 0, delta: { tool_calls: wireToolCalls(answer.toolCalls, true) } }] })
            finish = "tool_calls"
          } else if (!sawText && answer.content) {
            chunk({ choices: [{ index: 0, delta: { content: answer.content } }] })
          }
          chunk({ choices: [{ index: 0, delta: {}, finish_reason: finish }] })
          const u = answer.usage
          if (u) {
            const p = u.promptTokens ?? 0, c = u.completionTokens ?? 0
            chunk({ choices: [], usage: { prompt_tokens: p, completion_tokens: c, total_tokens: u.totalTokens || p + c } })
          }
          if (open) controller.enqueue(enc.encode("data: [DONE]\n\n"))
          open = false
          controller.close()
        } catch (e) {
          if (open) {
            open = false
            controller.error(e)
          }
        }
      })()
    },
    cancel() {
      open = false
    },
  })
  return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } })
}

/**
 * A client backed by a model running IN THIS PROCESS — no server, no socket, and no
 * HTTP types to construct.
 *
 * This is a second constructor, not a second seam: it builds on the same injectable
 * transport that `fetch`/`httpClient` uses, so the tool-calling loop, MCP servers,
 * skills, sub-agents, hooks, metrics and the completion gate all behave identically.
 * The `fetch`-shaped adapter itself is {@link createInProcessFetch} — this function
 * is just that adapter wired into a `Client`.
 *
 * Streaming is refused rather than faked for a plain `generate`; with `generateStream`
 * it streams for real — see {@link createInProcessFetch}.
 */
export function createInProcessClient(opts: InProcessOptions): Client {
  const { generate, generateStream, ...rest } = opts
  if (typeof generate !== "function" && typeof generateStream !== "function") {
    throw new Error("toolnexus: createInProcessClient requires a `generate` (or `generateStream`) function")
  }
  const inProcessFetch = createInProcessFetch(generate, generateStream)

  return new Client({
    // Retries default to ZERO here, unlike a network client. There is no wire, so
    // there is no transient failure to ride out: whatever `generate` throws will
    // throw again. Retrying it only buys backoff before the user sees their own bug —
    // measured at 3.7s for the streaming refusal before this default. A host whose
    // model really is flaky (a GPU that OOMs, say) can still set `retries`.
    retries: 0,
    ...(rest as ClientOptions),
    baseUrl: IN_PROCESS_BASE_URL,
    style: "openai",
    // An in-process model has no endpoint to authenticate to, so the host must never need a key — but the client resolves one from the environment and fails when it finds none. A sentinel keeps that resolution from ever running. Caught by CI, which has no OPENROUTER_API_KEY; every local run passed because a developer shell has one.
    apiKey: "in-process",
    fetch: inProcessFetch,
  })
}

/** Stateful multi-turn conversation: each send() continues the same transcript (memory). */
export class Conversation {
  /** Full running transcript (system + user + assistant + tool messages). */
  messages: any[] = []
  constructor(private readonly client: Client, private readonly toolkit?: Toolkit, private readonly signal?: AbortSignal) {}

  /** Send the next user turn; prior history is retained automatically. */
  async send(prompt: PromptInput): Promise<RunResult> {
    const result = await this.client.run(prompt, { toolkit: this.toolkit, signal: this.signal, history: this.messages })
    this.messages = result.messages
    return result
  }

  /** Reset the conversation memory. */
  reset(): void {
    this.messages = []
  }
}

/** Read an SSE stream line-by-line from a fetch body (web ReadableStream). */
async function* sseLines(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buf = ""
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf("\n")) >= 0) {
      yield buf.slice(0, idx).replace(/\r$/, "")
      buf = buf.slice(idx + 1)
    }
  }
  if (buf) yield buf
}

function safeParse(s: string): any {
  try {
    return JSON.parse(s)
  } catch {
    return undefined
  }
}

const NUDGE = "Your previous response contained no answer. Continue and give your answer."
const CONTINUE = "Your previous response was cut off at the output token limit. Continue exactly where it stopped, without repeating anything."
const CORRECTIVE = "Your previous tool call could not be used: its arguments were not a valid JSON object. Call the tool again with arguments that are a single valid JSON object matching its input schema."

/** Per-run budgets + carried text of §8 stop-reason recovery. */
interface Recovery { nudged: boolean; continues: number; corrected: boolean; carry: string }

function isObj(v: unknown): boolean {
  return typeof v === "object" && v !== null && !Array.isArray(v)
}

/** A raw argument string that is non-empty (trimmed) and not a JSON object (§8). */
function malformedArgs(s: unknown): boolean {
  return typeof s === "string" && s.trim() !== "" && !isObj(safeParse(s))
}

/** Tool-call arguments as an object: anything that is not a JSON object becomes `{}` (§8). */
function safeJson(s: string): Record<string, unknown> {
  return objArgs(safeParse(s || "{}"))
}

function objArgs(v: unknown): Record<string, unknown> {
  return isObj(v) ? (v as Record<string, unknown>) : {}
}

/**
 * A canonical `tool` turn. With no parts this is byte-identical to the pre-0.17 message;
 * with parts it also carries them (and the tool `name` the §8A relocation header needs) —
 * both consumed and stripped by the wire build, so neither reaches OpenAI.
 */
function toolMessage(id: string | undefined, name: string, result: ToolResult): any {
  const m: any = { role: "tool", tool_call_id: id, content: result.output }
  const parts = modelParts(result)
  if (parts.length) {
    m.name = name
    m.parts = parts
  }
  return m
}

/** A canonical Anthropic `tool_result` block; parts ride along and are emitted natively. */
function toolResultBlock(id: string | undefined, result: ToolResult): any {
  const b: any = { type: "tool_result", tool_use_id: id, content: result.output, is_error: result.isError }
  const parts = modelParts(result)
  if (parts.length) b.parts = parts
  return b
}

/** Receives the repaired transcript of a run that ended in an error or cancellation. */
type OnInterrupt = (messages: any[]) => void

const INTERRUPTED: ToolResult = { output: "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.", isError: true }

/** Settle with `p`, or reject with the abort reason as soon as `signal` aborts. */
function untilAborted<T>(signal: AbortSignal, p: Promise<T>): Promise<T> {
  if (signal.aborted) return Promise.reject(signal.reason ?? new Error("aborted"))
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(signal.reason ?? new Error("aborted"))
    signal.addEventListener("abort", onAbort, { once: true })
    p.then(
      (v) => { signal.removeEventListener("abort", onAbort); resolve(v) },
      (e) => { signal.removeEventListener("abort", onAbort); reject(e) },
    )
  })
}

/**
 * §8 "Interrupted turns are saved": a copy of `messages` in which every tool call of the LAST
 * assistant message has a result — the finished one from `done`, else `interrupted` (isError) —
 * in the dialect's own shape, so the saved transcript replays on either API.
 */
function interruptedTranscript(messages: any[], anthropic: boolean, done: Map<string, ToolResult>): any[] {
  const out = [...messages]
  let ai = -1
  for (let i = out.length - 1; i >= 0; i--) if (out[i]?.role === "assistant") { ai = i; break }
  if (ai < 0) return out
  const resultFor = (id: string) => done.get(id) ?? INTERRUPTED
  if (!anthropic) {
    const calls: any[] = out[ai].tool_calls ?? []
    const have = new Set(out.slice(ai + 1).filter((m) => m?.role === "tool").map((m) => m.tool_call_id))
    for (const c of calls) if (!have.has(c.id)) out.push(toolMessage(c.id, c.function?.name ?? "", resultFor(c.id)))
    return out
  }
  const uses = Array.isArray(out[ai].content) ? out[ai].content.filter((b: any) => b?.type === "tool_use") : []
  if (!uses.length) return out
  const next = out[ai + 1]
  const blocks: any[] = next?.role === "user" && Array.isArray(next.content) ? [...next.content] : []
  const have = new Set(blocks.filter((b) => b?.type === "tool_result").map((b) => b.tool_use_id))
  for (const u of uses) if (!have.has(u.id)) blocks.push(toolResultBlock(u.id, resultFor(u.id)))
  if (next?.role === "user" && Array.isArray(next.content)) out[ai + 1] = { ...next, content: blocks }
  else out.splice(ai + 1, 0, { role: "user", content: blocks })
  return out
}

function lastText(messages: any[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "assistant" && typeof messages[i].content === "string") return messages[i].content
  }
  return ""
}

/** The default LLM transport. On Node it is the keep-alive `node:http` transport in
 * `nodefetch.ts` (loaded lazily so this module stays free of `node:` imports for browser
 * bundles); on Bun, Deno, browsers, or if loading fails, it is the platform `fetch`. A
 * caller-supplied `fetch` option always wins and never reaches here. */
const platformFetch = globalThis.fetch
let defaultFetchP: Promise<typeof fetch> | undefined
function defaultFetch(): Promise<typeof fetch> {
  // A host that replaced the global fetch (a stub, a proxy wrapper) keeps it.
  if (globalThis.fetch !== platformFetch) return Promise.resolve(globalThis.fetch)
  const g = globalThis as { Bun?: unknown; Deno?: unknown; process?: { versions?: { node?: string } } }
  const onNode = !!g.process?.versions?.node && !g.Bun && !g.Deno
  if (!onNode) return Promise.resolve((input, init) => globalThis.fetch(input, init))
  return (defaultFetchP ??= import("./nodefetch.js").then(
    (m) => m.nodeFetch,
    () => (input: Parameters<typeof fetch>[0], init?: RequestInit) => globalThis.fetch(input, init),
  ))
}
