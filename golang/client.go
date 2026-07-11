// Unified LLM client — the host loop. Give it a base URL + a style
// ("openai" | "anthropic"), and it runs the tool-calling agent loop against a
// Toolkit. Mirrors js/src/client.ts and SPEC.md §8.
package toolnexus

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ClientStyle selects the wire format.
type ClientStyle string

const (
	StyleOpenAI    ClientStyle = "openai"
	StyleAnthropic ClientStyle = "anthropic"
)

// ClientOptions configures CreateClient.
type ClientOptions struct {
	BaseURL string
	Style   ClientStyle
	Model   string
	// APIKey; if empty, read from env (OPENROUTER_API_KEY / OPENAI_API_KEY /
	// ANTHROPIC_API_KEY). Never printed.
	APIKey string
	// Headers are extra request headers.
	Headers map[string]string
	// SystemPrompt is prepended to the toolkit's SkillsPrompt().
	SystemPrompt string
	// MaxTurns caps the tool-calling loop; 0 ⇒ 10.
	MaxTurns int
	// Hooks are optional lifecycle callbacks around the loop. A nil Hooks (or any
	// nil field) is skipped. Any hook returning an error aborts the run.
	Hooks *Hooks
	// Retries on transient LLM errors (429/5xx/network). 0 ⇒ 2.
	Retries int
	// RetryBaseMs is the base backoff in ms (exponential + jitter). 0 ⇒ 500.
	RetryBaseMs int
	// TimeoutMs is the whole-run deadline in ms; when > 0 the run (and its
	// in-flight request) is aborted once exceeded. 0 ⇒ no deadline.
	TimeoutMs int
	// Store is the conversation provider for Ask(prompt, id). Nil ⇒ in-memory
	// (per-client, process lifetime). Supply a file/db store to persist
	// conversations across processes. Mirrors js ClientOptions.store.
	Store ConversationStore
	// OnMetric is an observability sink — it receives a semantic MetricEvent at
	// each significant point (one "llm" per LLM call, one "tool" per tool call,
	// one terminal "run" per run/ask) as the loop runs (§8 Observability).
	// Forward to statsd / logs / OTel / anything. No cost when nil. The same
	// events also feed the built-in Prometheus registry (Client.Metrics()).
	// Mirrors js ClientOptions.onMetric.
	OnMetric func(MetricEvent)
	// WaitFor is the §10 suspension resolver. When a tool returns a suspension
	// (metadata.pending = Request), the client calls WaitFor(request), and on a
	// successful (Ok) Answer re-executes the same tool once with
	// ToolContext.Answer set, then feeds that result back to the model. Its
	// interior is unconstrained (open a browser, message a channel, watch a file,
	// forward over A2A). When nil, a suspension does NOT hang the run — Run halts
	// and returns a RunResult with Status "pending" and Pending set, so a durable
	// host can deliver it out-of-band and resume later. Named WaitFor, never
	// "await" (a reserved word). Mirrors js ClientOptions.waitFor.
	WaitFor func(Request) (Answer, error)
}

// MetricEvent is a semantic observability record fired as the loop runs (§8).
// It is NOT a counter/histogram primitive — it is a readable event the host can
// forward anywhere. Event discriminates which fields are meaningful:
//
//	"llm"  → Model, Status ("ok"|"error"), Ms, PromptTokens, CompletionTokens
//	"tool" → Tool, Source, IsError, Ms, Pending
//	"run"  → Model, Turns, ToolCalls, TotalTokens, Ms, Error (set only on failure)
//
// Field names follow Go's idiomatic PascalCase casing (like RunResult), so this
// struct is NOT byte-identical to the js MetricEvent union — only the Prometheus
// text from Client.Metrics() is byte-identical across ports. Mirrors js
// MetricEvent and SPEC.md §8.
type MetricEvent struct {
	// Event discriminates the record: "llm" | "tool" | "run".
	Event string
	// Model is the model used ("llm", "run").
	Model string
	// Status is "ok" | "error" ("llm").
	Status string
	// Ms is the elapsed wall-clock time in milliseconds (all events).
	Ms int64
	// PromptTokens / CompletionTokens are the per-call token counts ("llm").
	PromptTokens     int
	CompletionTokens int
	// Tool is the tool name ("tool").
	Tool string
	// Source is where the tool came from ("mcp"/"skill"/"native"/… else "custom") ("tool").
	Source string
	// IsError flags a tool error ("tool").
	IsError bool
	// Pending marks a §10 suspension ("tool"): the tool returned a Request awaiting
	// out-of-band resolution rather than a real result. A suspension is classified
	// Pending (IsError:false), never a tool error. Mirrors js MetricEvent.pending.
	Pending bool
	// Turns is the number of LLM round trips ("run").
	Turns int
	// ToolCalls is the total number of tool calls ("run").
	ToolCalls int
	// TotalTokens is the aggregated token usage across the run ("run").
	TotalTokens int
	// Error is the failure message, set only on a failed run ("run").
	Error string
}

// retryableStatus is the set of HTTP statuses worth retrying. Mirrors js RETRYABLE.
var retryableStatus = map[int]bool{429: true, 500: true, 502: true, 503: true, 504: true}

// Hooks are lifecycle callbacks around the agent loop. Each may observe; the
// noted ones may mutate or short-circuit. A nil field is skipped, and any hook
// returning an error aborts the run with that error. Mirrors js Hooks
// (src/client.ts) and SPEC.md §8.
type Hooks struct {
	// BeforeLLM runs before each model call. Return a non-nil LLMOverride to
	// replace messages and/or tools (trim/inject history, swap tools).
	BeforeLLM func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error)
	// AfterLLM runs after each model call (observe: logging, cost, tracing).
	AfterLLM func(ctx context.Context, ev AfterLLMEvent) error
	// BeforeTool runs before a tool executes. Return an override with Result set
	// to SHORT-CIRCUIT (deny / cache hit / dry-run — the real tool never runs),
	// or with Args set to rewrite the call.
	BeforeTool func(ctx context.Context, ev BeforeToolEvent) (*ToolOverride, error)
	// AfterTool runs after a tool executes. Return an override with Result set to
	// replace (redact / annotate) the output.
	AfterTool func(ctx context.Context, ev AfterToolEvent) (*ToolOverride, error)
}

// BeforeLLMEvent is passed to Hooks.BeforeLLM.
type BeforeLLMEvent struct {
	Messages []any
	Tools    []any
	Model    string
	Turn     int
}

// AfterLLMEvent is passed to Hooks.AfterLLM. Response is the raw, decoded
// provider payload (carries usage).
type AfterLLMEvent struct {
	Response map[string]any
	Model    string
	Turn     int
}

// BeforeToolEvent is passed to Hooks.BeforeTool.
type BeforeToolEvent struct {
	Name string
	Args map[string]any
	ID   string
	Turn int
}

// AfterToolEvent is passed to Hooks.AfterTool.
type AfterToolEvent struct {
	Name   string
	Args   map[string]any
	Result ToolResult
	ID     string
	Turn   int
}

// LLMOverride is returned by Hooks.BeforeLLM. A nil field leaves that value
// unchanged; a non-nil (even empty) slice replaces it.
type LLMOverride struct {
	Messages []any
	Tools    []any
}

// ToolOverride is returned by Hooks.BeforeTool / Hooks.AfterTool. In BeforeTool,
// a non-nil Result short-circuits the tool; otherwise a non-nil Args rewrites
// the call. In AfterTool, a non-nil Result replaces the result.
type ToolOverride struct {
	Args   map[string]any
	Result *ToolResult
}

// ToolCall records a tool the model asked for, plus its result + metadata.
type ToolCall struct {
	Name     string         `json:"name"`
	Args     map[string]any `json:"args"`
	Output   string         `json:"output"`
	IsError  bool           `json:"isError"`
	Metadata map[string]any `json:"metadata,omitempty"`
}

// Usage is token usage, summed across every LLM round trip in the run.
type Usage struct {
	PromptTokens     int `json:"promptTokens"`
	CompletionTokens int `json:"completionTokens"`
	TotalTokens      int `json:"totalTokens"`
}

// RunResult is the outcome of a Run.
type RunResult struct {
	Text      string     `json:"text"`
	Messages  []any      `json:"messages"`
	ToolCalls []ToolCall `json:"toolCalls"`
	// ToolCallCount is the total number of tool calls (= len(ToolCalls)).
	ToolCallCount int `json:"toolCallCount"`
	// Turns is the number of LLM round trips.
	Turns int `json:"turns"`
	// Usage is the aggregated token usage across all turns.
	Usage Usage `json:"usage"`
	// Model is the model used.
	Model string `json:"model"`
	// Status is "done" normally; "pending" iff a tool suspended and no WaitFor
	// was configured (§10).
	Status string `json:"status"`
	// Pending is the unresolved suspension — present iff Status == "pending" (§10).
	Pending *Request `json:"pending,omitempty"`
}

// StreamEvent is one event emitted on the channel returned by Client.Stream.
// Type is one of: "text", "tool_call", "tool_result", "usage", "done", "error".
// Only the fields relevant to Type are populated. Mirrors the js StreamEvent
// union; the "error" variant (with Err set) is the Go-idiomatic way errors
// surface on a channel — see Stream.
type StreamEvent struct {
	// Type discriminates the event: "text" | "tool_call" | "tool_result" |
	// "usage" | "done" | "error".
	Type string
	// Delta is the incremental assistant text (Type == "text").
	Delta string
	// Name is the tool name (Type == "tool_call" | "tool_result").
	Name string
	// ID is the tool-call id (Type == "tool_call" | "tool_result").
	ID string
	// Args are the parsed tool arguments (Type == "tool_call").
	Args map[string]any
	// Output is the tool output (Type == "tool_result").
	Output string
	// IsError flags a tool error (Type == "tool_result").
	IsError bool
	// Usage is the aggregated token usage (Type == "usage" | "done").
	Usage *Usage
	// Result is the final RunResult (Type == "done").
	Result *RunResult
	// Request is the suspension (Type == "pending"); emitted before WaitFor runs
	// so a channel handler can push the link in real time (§10).
	Request *Request
	// Err is the terminal error (Type == "error"); the channel closes right after.
	Err error
}

// addUsage sums one response's usage object into acc. Numbers arrive from JSON
// decode as float64. openai style reads prompt_tokens/completion_tokens/
// total_tokens; anthropic reads input_tokens→prompt, output_tokens→completion,
// total = input + output.
func addUsage(acc *Usage, raw map[string]any, style string) {
	if raw == nil {
		return
	}
	num := func(k string) int {
		if v, ok := raw[k].(float64); ok {
			return int(v)
		}
		return 0
	}
	if style == string(StyleAnthropic) {
		in, out := num("input_tokens"), num("output_tokens")
		acc.PromptTokens += in
		acc.CompletionTokens += out
		acc.TotalTokens += in + out
		return
	}
	prompt, completion := num("prompt_tokens"), num("completion_tokens")
	acc.PromptTokens += prompt
	acc.CompletionTokens += completion
	if total := num("total_tokens"); total != 0 {
		acc.TotalTokens += total
	} else {
		acc.TotalTokens += prompt + completion
	}
}

// Client runs the agent loop against a Toolkit.
type Client struct {
	opts ClientOptions
	http *http.Client
	// store is the conversation provider for Ask — from opts.Store, else a fresh
	// in-memory store.
	store ConversationStore
	// registry is the cumulative Prometheus registry fed by the same events the
	// OnMetric sink sees; rendered by Metrics().
	registry *metricsRegistry
}

// CreateClient builds a Client.
func CreateClient(opts ClientOptions) *Client {
	store := opts.Store
	if store == nil {
		store = NewInMemoryConversationStore()
	}
	return &Client{opts: opts, http: http.DefaultClient, store: store, registry: newMetricsRegistry()}
}

// Metrics renders the cumulative metrics as Prometheus text exposition format
// (§8). Always valid — empty-but-valid (only # HELP/# TYPE lines) before any
// activity. Byte-identical across ports. Mirrors js Client.metrics().
func (c *Client) Metrics() string {
	return c.registry.render()
}

// emit feeds a semantic metric event to the built-in registry and the optional
// OnMetric sink. Mirrors js Client.emit.
func (c *Client) emit(ev MetricEvent) {
	c.registry.record(ev)
	if c.opts.OnMetric != nil {
		c.opts.OnMetric(ev)
	}
}

// emitLLM emits an "llm" metric event for one model call.
func (c *Client) emitLLM(status string, start time.Time, prompt, completion int) {
	c.emit(MetricEvent{Event: "llm", Model: c.opts.Model, Status: status, Ms: msSince(start), PromptTokens: prompt, CompletionTokens: completion})
}

// endRun emits the terminal (success) "run" metric event and builds the
// RunResult. Mirrors js Client.endRun.
func (c *Client) endRun(start time.Time, text string, messages []any, toolCalls []ToolCall, turns int, usage Usage) RunResult {
	c.emit(MetricEvent{Event: "run", Model: c.opts.Model, Turns: turns, ToolCalls: len(toolCalls), TotalTokens: usage.TotalTokens, Ms: msSince(start)})
	return c.result(text, messages, toolCalls, turns, usage)
}

// emitRunError emits the terminal "run" metric event for a failed run (once).
// Mirrors js Client.emitRunError.
func (c *Client) emitRunError(start time.Time, toolCalls []ToolCall, turns int, usage Usage, err error) {
	msg := ""
	if err != nil {
		msg = err.Error()
	}
	c.emit(MetricEvent{Event: "run", Model: c.opts.Model, Turns: turns, ToolCalls: len(toolCalls), TotalTokens: usage.TotalTokens, Ms: msSince(start), Error: msg})
}

// msSince returns milliseconds elapsed since start (integer, like js Date.now()).
func msSince(start time.Time) int64 {
	return time.Since(start).Milliseconds()
}

// perCall extracts the per-call prompt/completion token counts from one raw
// usage payload (not summed). Mirrors js perCall.
func perCall(raw map[string]any, style string) (prompt, completion int) {
	if raw == nil {
		return 0, 0
	}
	num := func(k string) int {
		if v, ok := raw[k].(float64); ok {
			return int(v)
		}
		return 0
	}
	if style == string(StyleAnthropic) {
		return num("input_tokens"), num("output_tokens")
	}
	return num("prompt_tokens"), num("completion_tokens")
}

func (c *Client) maxTurns() int {
	if c.opts.MaxTurns > 0 {
		return c.opts.MaxTurns
	}
	return 10
}

func (c *Client) retries() int {
	if c.opts.Retries > 0 {
		return c.opts.Retries
	}
	return 2
}

func (c *Client) retryBaseMs() int {
	if c.opts.RetryBaseMs > 0 {
		return c.opts.RetryBaseMs
	}
	return 500
}

// withDeadline derives a run-scoped context honoring TimeoutMs. The caller MUST
// invoke the returned cancel. When TimeoutMs is 0 the context is returned
// unchanged with a no-op cancel. Mirrors js makeSignal (timeout + external
// cancel are unified through context).
func (c *Client) withDeadline(ctx context.Context) (context.Context, context.CancelFunc) {
	if c.opts.TimeoutMs > 0 {
		return context.WithTimeout(ctx, time.Duration(c.opts.TimeoutMs)*time.Millisecond)
	}
	return ctx, func() {}
}

// resolveKey picks the API key from opts or the environment. The value is never
// logged.
func (c *Client) resolveKey() (string, error) {
	if c.opts.APIKey != "" {
		return c.opts.APIKey, nil
	}
	candidates := []string{os.Getenv("OPENROUTER_API_KEY")}
	if c.opts.Style == StyleAnthropic {
		candidates = append(candidates, os.Getenv("ANTHROPIC_API_KEY"), os.Getenv("OPENAI_API_KEY"))
	} else {
		candidates = append(candidates, os.Getenv("OPENAI_API_KEY"), os.Getenv("ANTHROPIC_API_KEY"))
	}
	for _, k := range candidates {
		if k != "" {
			return k, nil
		}
	}
	return "", fmt.Errorf("no API key: set APIKey or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY")
}

func (c *Client) system(tk *Toolkit) string {
	parts := []string{}
	if c.opts.SystemPrompt != "" {
		parts = append(parts, c.opts.SystemPrompt)
	}
	if sp := tk.SkillsPrompt(); sp != "" {
		parts = append(parts, sp)
	}
	return strings.Join(parts, "\n\n")
}

// runTool runs one tool through the BeforeTool/AfterTool hooks: BeforeTool may
// short-circuit (Result) or rewrite (Args), then tk.Execute runs, then AfterTool
// may replace the result. It returns the args actually used and the final
// result. Mirrors js Client.runTool. Hook errors abort the run.
func (c *Client) runTool(ctx context.Context, tk *Toolkit, name string, args map[string]any, id string, turn int) (map[string]any, ToolResult, error) {
	h := c.opts.Hooks
	source := "custom"
	if t, ok := tk.Get(name); ok {
		source = string(t.Source)
	}
	start := time.Now()
	a := args
	if h != nil && h.BeforeTool != nil {
		ov, err := h.BeforeTool(ctx, BeforeToolEvent{Name: name, Args: a, ID: id, Turn: turn})
		if err != nil {
			return a, ToolResult{}, err
		}
		if ov != nil {
			if ov.Result != nil {
				// short-circuit (deny / cache / dry-run, or a guard-raised suspension —
				// path B) — still a tool call for metrics, but a suspension is classified
				// pending, never an error.
				c.emitTool(name, source, *ov.Result, start)
				return a, *ov.Result, nil
			}
			if ov.Args != nil {
				a = ov.Args
			}
		}
	}
	result, err := tk.Execute(ctx, name, a)
	if err != nil {
		return a, result, err
	}
	// A suspension (§10) is not a real result: skip AfterTool on it — the resolved
	// result (post-WaitFor) flows through AfterTool in resolvePending — and never
	// count it as a tool error.
	if h != nil && h.AfterTool != nil && PendingOf(result) == nil {
		ov, err := h.AfterTool(ctx, AfterToolEvent{Name: name, Args: a, Result: result, ID: id, Turn: turn})
		if err != nil {
			return a, result, err
		}
		if ov != nil && ov.Result != nil {
			result = *ov.Result
		}
	}
	c.emitTool(name, source, result, start)
	return a, result, nil
}

// emitTool emits the `tool` metric, classifying a §10 suspension as Pending
// (IsError:false), not a failure. Mirrors js Client.emitTool.
func (c *Client) emitTool(name, source string, result ToolResult, start time.Time) {
	if PendingOf(result) != nil {
		c.emit(MetricEvent{Event: "tool", Tool: name, Source: source, IsError: false, Pending: true, Ms: msSince(start)})
		return
	}
	c.emit(MetricEvent{Event: "tool", Tool: name, Source: source, IsError: result.IsError, Ms: msSince(start)})
}

// Run executes the agent loop for one prompt against the toolkit. It is
// RunWithHistory with no prior history.
func (c *Client) Run(ctx context.Context, prompt string, tk *Toolkit) (RunResult, error) {
	return c.RunWithHistory(ctx, prompt, tk, nil)
}

// RunWithHistory executes the agent loop, optionally continuing a prior
// transcript. When history is non-empty it is used verbatim (the system prompt
// is NOT re-added) and the new prompt is appended as the next user turn; the
// returned RunResult.Messages is the full updated transcript. Mirrors js
// Client.run(prompt, { history }). A run-level TimeoutMs (if set) and ctx
// cancellation both abort in-flight requests.
func (c *Client) RunWithHistory(ctx context.Context, prompt string, tk *Toolkit, history []any) (RunResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	ctx, cancel := c.withDeadline(ctx)
	defer cancel()
	if c.opts.Style == StyleAnthropic {
		return c.runAnthropic(ctx, prompt, tk, history)
	}
	return c.runOpenAI(ctx, prompt, tk, history)
}

// Ask is the stateful counterpart to Run. With a non-empty id, the client's
// conversation store remembers the exchange: it loads that id's transcript, runs
// the loop with it as history, saves the updated RunResult.Messages back under
// id, and returns the result — so the next Ask with the same id continues the
// conversation. With an empty id it is a stateless one-shot (identical to Run,
// the store untouched). Works identically for openai and anthropic. Mirrors js
// Client.ask and SPEC.md §8.
func (c *Client) Ask(ctx context.Context, prompt string, tk *Toolkit, id string) (RunResult, error) {
	return c.AskStream(ctx, prompt, tk, id, nil)
}

// AskStream is Ask with an optional block-style text callback. When onText is
// non-nil it runs the streaming path, invokes onText for each assistant text
// delta as it arrives, and STILL returns the final RunResult (the return type
// never changes) — memory (id load/save) is handled by the streaming path just
// as for Ask. When onText is nil it is exactly Ask (non-streaming). Mirrors js
// Client.ask({ on_text }) and SPEC.md §8.
func (c *Client) AskStream(ctx context.Context, prompt string, tk *Toolkit, id string, onText func(delta string)) (RunResult, error) {
	if onText != nil {
		ch, err := c.StreamWithID(ctx, prompt, tk, id)
		if err != nil {
			return RunResult{}, err
		}
		var result RunResult
		var streamErr error
		for ev := range ch {
			switch ev.Type {
			case "text":
				onText(ev.Delta)
			case "done":
				if ev.Result != nil {
					result = *ev.Result
				}
			case "error":
				streamErr = ev.Err
			}
		}
		if streamErr != nil {
			return RunResult{}, streamErr
		}
		return result, nil
	}
	if id == "" {
		return c.Run(ctx, prompt, tk)
	}
	history, err := c.store.Get(id)
	if err != nil {
		return RunResult{}, err
	}
	res, err := c.RunWithHistory(ctx, prompt, tk, history)
	if err != nil {
		return res, err
	}
	if err := c.store.Save(id, res.Messages); err != nil {
		return res, err
	}
	return res, nil
}

// sleep waits d, but returns ctx.Err() early if ctx is cancelled/timed out.
func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// backoff computes the exponential-backoff-with-jitter delay for an attempt, or
// honors a Retry-After header (in seconds) when present.
func (c *Client) backoff(attempt int, retryAfter string) time.Duration {
	if retryAfter != "" {
		if secs, err := strconv.Atoi(strings.TrimSpace(retryAfter)); err == nil && secs >= 0 {
			return time.Duration(secs) * time.Second
		}
	}
	base := c.retryBaseMs()
	ms := base*(1<<attempt) + rand.Intn(100)
	return time.Duration(ms) * time.Millisecond
}

// llmFetch issues the POST with retry + exponential backoff on 429/5xx/network,
// honoring Retry-After. ctx cancellation/timeout aborts the in-flight request
// and is NOT retried. The caller owns resp.Body. Mirrors js Client.llmFetch.
func (c *Client) llmFetch(ctx context.Context, endpoint string, headers map[string]string, raw []byte) (*http.Response, error) {
	retries := c.retries()
	var lastErr error
	for attempt := 0; attempt <= retries; attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		for k, v := range c.opts.Headers {
			req.Header.Set(k, v)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			// ctx cancelled/timed out: abort, never retry.
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			lastErr = err
			if attempt == retries {
				return nil, err
			}
			if werr := sleep(ctx, c.backoff(attempt, "")); werr != nil {
				return nil, werr
			}
			continue
		}
		if resp.StatusCode < 300 || !retryableStatus[resp.StatusCode] || attempt == retries {
			return resp, nil
		}
		ra := resp.Header.Get("Retry-After")
		resp.Body.Close()
		if werr := sleep(ctx, c.backoff(attempt, ra)); werr != nil {
			return nil, werr
		}
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("LLM request failed after %d retries", retries)
}

func (c *Client) postJSON(ctx context.Context, endpoint string, headers map[string]string, body any) ([]byte, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	resp, err := c.llmFetch(ctx, endpoint, headers, raw)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("LLM %d: %s", resp.StatusCode, string(out))
	}
	return out, nil
}

// ---- OpenAI-style: POST {baseURL}/chat/completions ----

func (c *Client) runOpenAI(ctx context.Context, prompt string, tk *Toolkit, history []any) (res RunResult, err error) {
	key, err := c.resolveKey()
	if err != nil {
		return RunResult{}, err
	}
	endpoint := strings.TrimRight(c.opts.BaseURL, "/") + "/chat/completions"

	var messages []any
	if len(history) > 0 {
		// Continue an existing transcript; the system prompt is already in it.
		messages = append(messages, history...)
	} else if sys := c.system(tk); sys != "" {
		messages = append(messages, map[string]any{"role": "system", "content": sys})
	}
	messages = append(messages, map[string]any{"role": "user", "content": prompt})
	tools := tk.ToOpenAI()
	var toolCalls []ToolCall
	var usage Usage
	turns := 0
	runStart := time.Now()
	// Any error path emits the terminal "run" error event exactly once; success
	// paths emit via endRun (so err is nil here and this is a no-op).
	defer func() {
		if err != nil {
			c.emitRunError(runStart, toolCalls, turns, usage, err)
		}
	}()

	for turn := 0; turn < c.maxTurns(); turn++ {
		turns++
		if c.opts.Hooks != nil && c.opts.Hooks.BeforeLLM != nil {
			ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: c.opts.Model, Turn: turn})
			if err != nil {
				return RunResult{}, err
			}
			if ov != nil {
				if ov.Messages != nil {
					messages = ov.Messages
				}
				if ov.Tools != nil {
					tools = ov.Tools
				}
			}
		}
		body := map[string]any{
			"model":       c.opts.Model,
			"messages":    messages,
			"tools":       tools,
			"tool_choice": "auto",
		}
		t0 := time.Now()
		raw, err := c.postJSON(ctx, endpoint, map[string]string{"Authorization": "Bearer " + key}, body)
		if err != nil {
			c.emitLLM("error", t0, 0, 0)
			return RunResult{}, err
		}
		var data struct {
			Choices []struct {
				Message struct {
					Role      string `json:"role"`
					Content   string `json:"content"`
					ToolCalls []struct {
						ID       string `json:"id"`
						Type     string `json:"type"`
						Function struct {
							Name      string `json:"name"`
							Arguments string `json:"arguments"`
						} `json:"function"`
					} `json:"tool_calls"`
				} `json:"message"`
			} `json:"choices"`
			Usage map[string]any `json:"usage"`
		}
		if err := json.Unmarshal(raw, &data); err != nil {
			c.emitLLM("error", t0, 0, 0)
			return RunResult{}, err
		}
		p, cp := perCall(data.Usage, string(StyleOpenAI))
		c.emitLLM("ok", t0, p, cp)
		addUsage(&usage, data.Usage, string(StyleOpenAI))
		if c.opts.Hooks != nil && c.opts.Hooks.AfterLLM != nil {
			if err := c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: decodeResponse(raw), Model: c.opts.Model, Turn: turn}); err != nil {
				return RunResult{}, err
			}
		}
		if len(data.Choices) == 0 {
			return c.endRun(runStart, "", messages, toolCalls, turns, usage), nil
		}
		msg := data.Choices[0].Message

		// Append the assistant message (preserving tool_calls if any).
		asst := map[string]any{"role": "assistant", "content": msg.Content}
		if len(msg.ToolCalls) > 0 {
			var rawCalls []any
			for _, tc := range msg.ToolCalls {
				rawCalls = append(rawCalls, map[string]any{
					"id":   tc.ID,
					"type": "function",
					"function": map[string]any{
						"name":      tc.Function.Name,
						"arguments": tc.Function.Arguments,
					},
				})
			}
			asst["tool_calls"] = rawCalls
		}
		messages = append(messages, asst)

		if len(msg.ToolCalls) == 0 {
			return c.endRun(runStart, msg.Content, messages, toolCalls, turns, usage), nil
		}

		// Execute all tool calls in this turn concurrently (true parallel tool
		// calling). Each call writes its outcome to its own slot, so the transcript
		// is assembled in the order the model emitted the calls (deterministic,
		// independent of completion order).
		results := make([]any, len(msg.ToolCalls))
		records := make([]ToolCall, len(msg.ToolCalls))
		haltedAt := make([]*Request, len(msg.ToolCalls))
		var wg sync.WaitGroup
		var mu sync.Mutex
		var hookErr error
		for i, tc := range msg.ToolCalls {
			wg.Add(1)
			go func(i int, tc struct {
				ID       string `json:"id"`
				Type     string `json:"type"`
				Function struct {
					Name      string `json:"name"`
					Arguments string `json:"arguments"`
				} `json:"function"`
			}) {
				defer wg.Done()
				args, result, err := c.runTool(ctx, tk, tc.Function.Name, safeJSONArgs(tc.Function.Arguments), tc.ID, turn)
				if err == nil {
					if req := PendingOf(result); req != nil {
						r, h, rerr := c.resolvePending(ctx, tk, tc.Function.Name, args, *req, tc.ID, turn)
						err, result = rerr, r
						haltedAt[i] = h
					}
				}
				if err != nil {
					mu.Lock()
					if hookErr == nil {
						hookErr = err
					}
					mu.Unlock()
					return
				}
				records[i] = ToolCall{
					Name:     tc.Function.Name,
					Args:     args,
					Output:   result.Output,
					IsError:  result.IsError,
					Metadata: result.Metadata,
				}
				results[i] = map[string]any{
					"role":         "tool",
					"tool_call_id": tc.ID,
					"content":      result.Output,
				}
			}(i, tc)
		}
		wg.Wait()
		if hookErr != nil {
			return RunResult{}, hookErr
		}
		// Record in tool-call order; on the FIRST durable halt, surface it and stop —
		// deterministic, and the later suspensions' placeholder results never enter the
		// transcript (they re-suspend on resume). Mirrors the streaming path (G3).
		for i := range msg.ToolCalls {
			toolCalls = append(toolCalls, records[i])
			messages = append(messages, results[i])
			if haltedAt[i] != nil {
				return c.pendingRun(runStart, *haltedAt[i], messages, toolCalls, turns, usage), nil
			}
		}
	}
	return c.endRun(runStart, "", messages, toolCalls, turns, usage), nil
}

// result assembles a RunResult, filling in the derived telemetry fields.
func (c *Client) result(text string, messages []any, toolCalls []ToolCall, turns int, usage Usage) RunResult {
	return RunResult{
		Text:          text,
		Messages:      messages,
		ToolCalls:     toolCalls,
		ToolCallCount: len(toolCalls),
		Turns:         turns,
		Usage:         usage,
		Model:         c.opts.Model,
		Status:        "done",
	}
}

// pendingRun builds the terminal RunResult for a run that halted because a tool
// suspended and no WaitFor was configured (§10). Mirrors js Client.pendingRun.
func (c *Client) pendingRun(runStart time.Time, request Request, messages []any, toolCalls []ToolCall, turns int, usage Usage) RunResult {
	c.emit(MetricEvent{Event: "run", Model: c.opts.Model, Turns: turns, ToolCalls: len(toolCalls), TotalTokens: usage.TotalTokens, Ms: msSince(runStart)})
	req := request
	return RunResult{
		Text:          request.Prompt,
		Messages:      messages,
		ToolCalls:     toolCalls,
		ToolCallCount: len(toolCalls),
		Turns:         turns,
		Usage:         usage,
		Model:         c.opts.Model,
		Status:        "pending",
		Pending:       &req,
	}
}

// resolvePending resolves a suspended tool result (§10). It calls WaitFor(request),
// and on an Ok Answer re-executes the same tool once with ToolContext.Answer set,
// feeding that result back. When WaitFor is not configured it returns halted set to
// the request so the run stops and surfaces it. On !Ok (declined/expired) or a
// second suspension (unresolved) it returns an error ToolResult — never looping
// forever on the same request. Mirrors js Client.resolvePending.
func (c *Client) resolvePending(ctx context.Context, tk *Toolkit, name string, args map[string]any, request Request, id string, turn int) (result ToolResult, halted *Request, err error) {
	if c.opts.WaitFor == nil {
		req := request
		return ToolResult{Output: request.Prompt, IsError: true}, &req, nil
	}
	answer, err := c.opts.WaitFor(request)
	if err != nil {
		return ToolResult{}, nil, err
	}
	if !answer.Ok {
		return ToolResult{Output: "declined/expired: " + request.Prompt, IsError: true}, nil, nil
	}
	start := time.Now()
	result, err = tk.executeWithAnswer(ctx, name, args, &answer)
	if err != nil {
		return ToolResult{}, nil, err
	}
	if c.opts.Hooks != nil && c.opts.Hooks.AfterTool != nil {
		ov, herr := c.opts.Hooks.AfterTool(ctx, AfterToolEvent{Name: name, Args: args, Result: result, ID: id, Turn: turn})
		if herr != nil {
			return ToolResult{}, nil, herr
		}
		if ov != nil && ov.Result != nil {
			result = *ov.Result
		}
	}
	source := "custom"
	if t, ok := tk.Get(name); ok {
		source = string(t.Source)
	}
	c.emitTool(name, source, result, start)
	if PendingOf(result) != nil {
		result = ToolResult{Output: "unresolved: " + request.Prompt, IsError: true} // never loop forever
	}
	return result, nil, nil
}

// ---- Anthropic-style: POST {baseURL}/messages ----

func (c *Client) runAnthropic(ctx context.Context, prompt string, tk *Toolkit, history []any) (res RunResult, err error) {
	key, err := c.resolveKey()
	if err != nil {
		return RunResult{}, err
	}
	base := strings.TrimRight(c.opts.BaseURL, "/")
	endpoint := base + "/v1/messages"
	if strings.HasSuffix(base, "/v1") {
		endpoint = base + "/messages"
	}
	system := c.system(tk)
	var messages []any
	if len(history) > 0 {
		messages = append(messages, history...)
	}
	messages = append(messages, map[string]any{"role": "user", "content": prompt})
	tools := tk.ToAnthropic()
	var toolCalls []ToolCall
	var usage Usage
	turns := 0
	runStart := time.Now()
	defer func() {
		if err != nil {
			c.emitRunError(runStart, toolCalls, turns, usage, err)
		}
	}()

	headers := map[string]string{
		"x-api-key":         key,
		"anthropic-version": "2023-06-01",
	}

	for turn := 0; turn < c.maxTurns(); turn++ {
		turns++
		if c.opts.Hooks != nil && c.opts.Hooks.BeforeLLM != nil {
			ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: c.opts.Model, Turn: turn})
			if err != nil {
				return RunResult{}, err
			}
			if ov != nil {
				if ov.Messages != nil {
					messages = ov.Messages
				}
				if ov.Tools != nil {
					tools = ov.Tools
				}
			}
		}
		body := map[string]any{
			"model":      c.opts.Model,
			"max_tokens": 4096,
			"messages":   messages,
			"tools":      tools,
		}
		if system != "" {
			body["system"] = system
		}
		t0 := time.Now()
		raw, err := c.postJSON(ctx, endpoint, headers, body)
		if err != nil {
			c.emitLLM("error", t0, 0, 0)
			return RunResult{}, err
		}
		var data struct {
			Content []struct {
				Type  string         `json:"type"`
				Text  string         `json:"text"`
				ID    string         `json:"id"`
				Name  string         `json:"name"`
				Input map[string]any `json:"input"`
			} `json:"content"`
			Usage map[string]any `json:"usage"`
		}
		if err := json.Unmarshal(raw, &data); err != nil {
			c.emitLLM("error", t0, 0, 0)
			return RunResult{}, err
		}
		p, cp := perCall(data.Usage, string(StyleAnthropic))
		c.emitLLM("ok", t0, p, cp)
		addUsage(&usage, data.Usage, string(StyleAnthropic))
		if c.opts.Hooks != nil && c.opts.Hooks.AfterLLM != nil {
			if err := c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: decodeResponse(raw), Model: c.opts.Model, Turn: turn}); err != nil {
				return RunResult{}, err
			}
		}

		// Re-marshal the content blocks as the assistant message.
		var content []any
		var uses []struct {
			ID    string
			Name  string
			Input map[string]any
		}
		var textParts []string
		for _, b := range data.Content {
			switch b.Type {
			case "text":
				content = append(content, map[string]any{"type": "text", "text": b.Text})
				textParts = append(textParts, b.Text)
			case "tool_use":
				content = append(content, map[string]any{
					"type":  "tool_use",
					"id":    b.ID,
					"name":  b.Name,
					"input": b.Input,
				})
				uses = append(uses, struct {
					ID    string
					Name  string
					Input map[string]any
				}{b.ID, b.Name, b.Input})
			}
		}
		messages = append(messages, map[string]any{"role": "assistant", "content": content})

		if len(uses) == 0 {
			return c.endRun(runStart, strings.Join(textParts, ""), messages, toolCalls, turns, usage), nil
		}

		// Execute all tool_use blocks in this turn concurrently. Each block writes its
		// outcome to its own slot so the tool_result blocks map deterministically to
		// their tool_use ids (independent of completion order).
		results := make([]any, len(uses))
		records := make([]ToolCall, len(uses))
		haltedAt := make([]*Request, len(uses))
		var wg sync.WaitGroup
		var mu sync.Mutex
		var hookErr error
		for i, u := range uses {
			wg.Add(1)
			go func(i int, u struct {
				ID    string
				Name  string
				Input map[string]any
			}) {
				defer wg.Done()
				in := u.Input
				if in == nil {
					in = map[string]any{}
				}
				args, result, err := c.runTool(ctx, tk, u.Name, in, u.ID, turn)
				if err == nil {
					if req := PendingOf(result); req != nil {
						r, h, rerr := c.resolvePending(ctx, tk, u.Name, args, *req, u.ID, turn)
						err, result = rerr, r
						haltedAt[i] = h
					}
				}
				if err != nil {
					mu.Lock()
					if hookErr == nil {
						hookErr = err
					}
					mu.Unlock()
					return
				}
				records[i] = ToolCall{
					Name:     u.Name,
					Args:     args,
					Output:   result.Output,
					IsError:  result.IsError,
					Metadata: result.Metadata,
				}
				results[i] = map[string]any{
					"type":        "tool_result",
					"tool_use_id": u.ID,
					"content":     result.Output,
					"is_error":    result.IsError,
				}
			}(i, u)
		}
		wg.Wait()
		if hookErr != nil {
			return RunResult{}, hookErr
		}
		// Assemble the tool_result blocks in call order; on the FIRST durable halt,
		// include up to and including that block, surface it and stop — the later
		// suspensions' placeholder results never enter the transcript (they re-suspend
		// on resume). Mirrors the streaming path (G3).
		blocks := make([]any, 0, len(uses))
		var haltReq *Request
		for i := range uses {
			toolCalls = append(toolCalls, records[i])
			blocks = append(blocks, results[i])
			if haltedAt[i] != nil {
				haltReq = haltedAt[i]
				break
			}
		}
		messages = append(messages, map[string]any{"role": "user", "content": blocks})
		if haltReq != nil {
			return c.pendingRun(runStart, *haltReq, messages, toolCalls, turns, usage), nil
		}
	}
	return c.endRun(runStart, "", messages, toolCalls, turns, usage), nil
}

// decodeResponse decodes a raw provider response body into a map for AfterLLM.
// Returns nil on failure (the hook still observes the model/turn).
func decodeResponse(raw []byte) map[string]any {
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil
	}
	return out
}

// safeJSONArgs parses a tool-call arguments JSON string into a map, defaulting to
// an empty map on failure.
func safeJSONArgs(s string) map[string]any {
	if s == "" {
		return map[string]any{}
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(s), &out); err != nil || out == nil {
		return map[string]any{}
	}
	return out
}

// ---- Streaming ----

// Stream runs the agent loop and returns a channel of StreamEvent. The loop runs
// in a goroutine that sends events (text deltas, tool_call, tool_result, usage)
// and closes the channel when done. The final event is always either a
// {Type:"done", Result, Usage} (success) or a {Type:"error", Err} (failure) —
// errors surface as a terminal "error" event rather than a separate return so a
// single range-over-channel sees the whole stream. A run-level TimeoutMs and ctx
// cancellation both abort the in-flight request and end the stream with an
// "error" event carrying ctx.Err(). Mirrors js Client.stream.
func (c *Client) Stream(ctx context.Context, prompt string, tk *Toolkit) (<-chan StreamEvent, error) {
	return c.StreamWithID(ctx, prompt, tk, "")
}

// StreamWithID is the stateful counterpart to Stream. With a non-empty id it is
// stateful (like Ask): the thread's transcript is loaded as history before
// streaming, and the updated RunResult.Messages is saved back to the
// ConversationStore on the terminal "done" event — so the next call with the
// same id continues the conversation. An empty id ⇒ stateless (identical to
// Stream, the store untouched). Mirrors js Client.stream(prompt, { id }).
func (c *Client) StreamWithID(ctx context.Context, prompt string, tk *Toolkit, id string) (<-chan StreamEvent, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	var history []any
	if id != "" {
		h, err := c.store.Get(id)
		if err != nil {
			return nil, err
		}
		history = h
	}
	ch := make(chan StreamEvent, 16)
	stream := func(dctx context.Context, out chan<- StreamEvent) error {
		if c.opts.Style == StyleAnthropic {
			return c.streamAnthropic(dctx, prompt, tk, history, out)
		}
		return c.streamOpenAI(dctx, prompt, tk, history, out)
	}
	go func() {
		dctx, cancel := c.withDeadline(ctx)
		defer cancel()
		defer close(ch)
		// Stateless: stream straight through.
		if id == "" {
			if err := stream(dctx, ch); err != nil {
				ch <- StreamEvent{Type: "error", Err: err}
			}
			return
		}
		// Stateful: intercept the terminal "done" event to persist the transcript
		// (mirrors js stream() saving on done when an id is set).
		inner := make(chan StreamEvent, 16)
		go func() {
			defer close(inner)
			if err := stream(dctx, inner); err != nil {
				inner <- StreamEvent{Type: "error", Err: err}
			}
		}()
		for ev := range inner {
			if ev.Type == "done" && ev.Result != nil {
				if err := c.store.Save(id, ev.Result.Messages); err != nil {
					ch <- StreamEvent{Type: "error", Err: err}
					return
				}
			}
			ch <- ev
		}
	}()
	return ch, nil
}

// ---- Streaming: OpenAI-style ----

func (c *Client) streamOpenAI(ctx context.Context, prompt string, tk *Toolkit, history []any, ch chan<- StreamEvent) (err error) {
	key, err := c.resolveKey()
	if err != nil {
		return err
	}
	endpoint := strings.TrimRight(c.opts.BaseURL, "/") + "/chat/completions"

	var messages []any
	if len(history) > 0 {
		// Continue an existing transcript; the system prompt is already in it.
		messages = append(messages, history...)
	} else if sys := c.system(tk); sys != "" {
		messages = append(messages, map[string]any{"role": "system", "content": sys})
	}
	messages = append(messages, map[string]any{"role": "user", "content": prompt})
	tools := tk.ToOpenAI()
	var toolCalls []ToolCall
	var usage Usage
	turns := 0
	runStart := time.Now()
	defer func() {
		if err != nil {
			c.emitRunError(runStart, toolCalls, turns, usage, err)
		}
	}()

	for turn := 0; turn < c.maxTurns(); turn++ {
		turns++
		if c.opts.Hooks != nil && c.opts.Hooks.BeforeLLM != nil {
			ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: c.opts.Model, Turn: turn})
			if err != nil {
				return err
			}
			if ov != nil {
				if ov.Messages != nil {
					messages = ov.Messages
				}
				if ov.Tools != nil {
					tools = ov.Tools
				}
			}
		}
		body, err := json.Marshal(map[string]any{
			"model":          c.opts.Model,
			"messages":       messages,
			"tools":          tools,
			"tool_choice":    "auto",
			"stream":         true,
			"stream_options": map[string]any{"include_usage": true},
		})
		if err != nil {
			return err
		}
		t0 := time.Now()
		beforeP, beforeC := usage.PromptTokens, usage.CompletionTokens
		resp, err := c.llmFetch(ctx, endpoint, map[string]string{"Authorization": "Bearer " + key}, body)
		if err != nil {
			c.emitLLM("error", t0, 0, 0)
			return err
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			out, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			c.emitLLM("error", t0, 0, 0)
			return fmt.Errorf("LLM %d: %s", resp.StatusCode, string(out))
		}

		var content strings.Builder
		// acc maps tool_call index -> assembled call; order preserves first-seen index.
		acc := map[int]*streamCall{}
		var order []int
		sErr := scanSSE(ctx, resp.Body, func(line string) (bool, error) {
			if !strings.HasPrefix(line, "data:") {
				return true, nil
			}
			payload := strings.TrimSpace(line[5:])
			if payload == "[DONE]" {
				return false, nil
			}
			j := safeParseMap(payload)
			if j == nil {
				return true, nil
			}
			if u, ok := j["usage"].(map[string]any); ok {
				addUsage(&usage, u, string(StyleOpenAI))
			}
			choice := firstChoice(j)
			d, _ := choice["delta"].(map[string]any)
			if d == nil {
				return true, nil
			}
			if txt, ok := d["content"].(string); ok && txt != "" {
				content.WriteString(txt)
				ch <- StreamEvent{Type: "text", Delta: txt}
			}
			tcs, _ := d["tool_calls"].([]any)
			for _, raw := range tcs {
				tc, ok := raw.(map[string]any)
				if !ok {
					continue
				}
				idx := 0
				if f, ok := tc["index"].(float64); ok {
					idx = int(f)
				}
				slot := acc[idx]
				if slot == nil {
					slot = &streamCall{}
					acc[idx] = slot
					order = append(order, idx)
				}
				if id, ok := tc["id"].(string); ok && id != "" {
					slot.id = id
				}
				if fn, ok := tc["function"].(map[string]any); ok {
					if n, ok := fn["name"].(string); ok {
						slot.name += n
					}
					if a, ok := fn["arguments"].(string); ok {
						slot.args += a
					}
				}
			}
			return true, nil
		})
		resp.Body.Close()
		if sErr != nil {
			c.emitLLM("error", t0, 0, 0)
			return sErr
		}
		c.emitLLM("ok", t0, usage.PromptTokens-beforeP, usage.CompletionTokens-beforeC)
		if c.opts.Hooks != nil && c.opts.Hooks.AfterLLM != nil {
			if err := c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: map[string]any{"streamed": true}, Model: c.opts.Model, Turn: turn}); err != nil {
				return err
			}
		}

		calls := make([]*streamCall, 0, len(order))
		for _, idx := range order {
			calls = append(calls, acc[idx])
		}
		if len(calls) == 0 {
			text := content.String()
			messages = append(messages, map[string]any{"role": "assistant", "content": text})
			u := usage
			ch <- StreamEvent{Type: "usage", Usage: &u}
			res := c.endRun(runStart, text, messages, toolCalls, turns, usage)
			ch <- StreamEvent{Type: "done", Result: &res, Usage: &u}
			return nil
		}

		// Append the assistant message with assembled tool_calls.
		var rawCalls []any
		for _, cc := range calls {
			rawCalls = append(rawCalls, map[string]any{
				"id":       cc.id,
				"type":     "function",
				"function": map[string]any{"name": cc.name, "arguments": cc.args},
			})
		}
		asst := map[string]any{"role": "assistant", "tool_calls": rawCalls}
		if content.Len() > 0 {
			asst["content"] = content.String()
		} else {
			asst["content"] = nil
		}
		messages = append(messages, asst)

		for _, cc := range calls {
			ch <- StreamEvent{Type: "tool_call", ID: cc.id, Name: cc.name, Args: safeJSONArgs(cc.args)}
		}
		// Execute tool calls concurrently; results[] keeps call order.
		results := make([]any, len(calls))
		records := make([]ToolCall, len(calls))
		events := make([]StreamEvent, len(calls))
		var wg sync.WaitGroup
		var hookErr error
		var mu sync.Mutex
		var halted *Request
		for i, cc := range calls {
			wg.Add(1)
			go func(i int, cc *streamCall) {
				defer wg.Done()
				args, result, err := c.runTool(ctx, tk, cc.name, safeJSONArgs(cc.args), cc.id, turn)
				if err == nil {
					if req := PendingOf(result); req != nil {
						ch <- StreamEvent{Type: "pending", Request: req} // surface BEFORE WaitFor so a channel can push the link
						r, h, rerr := c.resolvePending(ctx, tk, cc.name, args, *req, cc.id, turn)
						err, result = rerr, r
						if h != nil {
							mu.Lock()
							halted = h
							mu.Unlock()
						}
					}
				}
				if err != nil {
					mu.Lock()
					if hookErr == nil {
						hookErr = err
					}
					mu.Unlock()
					return
				}
				records[i] = ToolCall{Name: cc.name, Args: args, Output: result.Output, IsError: result.IsError, Metadata: result.Metadata}
				results[i] = map[string]any{"role": "tool", "tool_call_id": cc.id, "content": result.Output}
				events[i] = StreamEvent{Type: "tool_result", ID: cc.id, Name: cc.name, Output: result.Output, IsError: result.IsError}
			}(i, cc)
		}
		wg.Wait()
		if hookErr != nil {
			return hookErr
		}
		for i := range calls {
			toolCalls = append(toolCalls, records[i])
			messages = append(messages, results[i])
			ch <- events[i]
		}
		if halted != nil {
			res := c.pendingRun(runStart, *halted, messages, toolCalls, turns, usage)
			u := usage
			ch <- StreamEvent{Type: "done", Result: &res, Usage: &u}
			return nil
		}
	}
	res := c.endRun(runStart, lastText(messages), messages, toolCalls, turns, usage)
	u := usage
	ch <- StreamEvent{Type: "done", Result: &res, Usage: &u}
	return nil
}

// ---- Streaming: Anthropic-style ----

func (c *Client) streamAnthropic(ctx context.Context, prompt string, tk *Toolkit, history []any, ch chan<- StreamEvent) (err error) {
	key, err := c.resolveKey()
	if err != nil {
		return err
	}
	base := strings.TrimRight(c.opts.BaseURL, "/")
	endpoint := base + "/v1/messages"
	if strings.HasSuffix(base, "/v1") {
		endpoint = base + "/messages"
	}
	system := c.system(tk)
	var messages []any
	if len(history) > 0 {
		messages = append(messages, history...)
	}
	messages = append(messages, map[string]any{"role": "user", "content": prompt})
	tools := tk.ToAnthropic()
	var toolCalls []ToolCall
	var usage Usage
	turns := 0
	runStart := time.Now()
	defer func() {
		if err != nil {
			c.emitRunError(runStart, toolCalls, turns, usage, err)
		}
	}()

	headers := map[string]string{"x-api-key": key, "anthropic-version": "2023-06-01"}

	for turn := 0; turn < c.maxTurns(); turn++ {
		turns++
		if c.opts.Hooks != nil && c.opts.Hooks.BeforeLLM != nil {
			ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: c.opts.Model, Turn: turn})
			if err != nil {
				return err
			}
			if ov != nil {
				if ov.Messages != nil {
					messages = ov.Messages
				}
				if ov.Tools != nil {
					tools = ov.Tools
				}
			}
		}
		reqBody := map[string]any{
			"model":      c.opts.Model,
			"max_tokens": 4096,
			"messages":   messages,
			"tools":      tools,
			"stream":     true,
		}
		if system != "" {
			reqBody["system"] = system
		}
		body, err := json.Marshal(reqBody)
		if err != nil {
			return err
		}
		t0 := time.Now()
		beforeP, beforeC := usage.PromptTokens, usage.CompletionTokens
		resp, err := c.llmFetch(ctx, endpoint, headers, body)
		if err != nil {
			c.emitLLM("error", t0, 0, 0)
			return err
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			out, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			c.emitLLM("error", t0, 0, 0)
			return fmt.Errorf("LLM %d: %s", resp.StatusCode, string(out))
		}

		blocks := map[int]*streamBlock{}
		var order []int
		stopReason := ""
		sErr := scanSSE(ctx, resp.Body, func(line string) (bool, error) {
			if !strings.HasPrefix(line, "data:") {
				return true, nil
			}
			j := safeParseMap(strings.TrimSpace(line[5:]))
			if j == nil {
				return true, nil
			}
			switch j["type"] {
			case "message_start":
				if m, ok := j["message"].(map[string]any); ok {
					if u, ok := m["usage"].(map[string]any); ok {
						addUsage(&usage, u, string(StyleAnthropic))
					}
				}
			case "content_block_start":
				idx := intField(j, "index")
				cb, _ := j["content_block"].(map[string]any)
				b := &streamBlock{}
				if cb != nil {
					b.typ, _ = cb["type"].(string)
					b.id, _ = cb["id"].(string)
					b.name, _ = cb["name"].(string)
				}
				blocks[idx] = b
				order = append(order, idx)
			case "content_block_delta":
				idx := intField(j, "index")
				b := blocks[idx]
				if b == nil {
					return true, nil
				}
				d, _ := j["delta"].(map[string]any)
				switch d["type"] {
				case "text_delta":
					if t, ok := d["text"].(string); ok {
						b.text += t
						ch <- StreamEvent{Type: "text", Delta: t}
					}
				case "input_json_delta":
					if p, ok := d["partial_json"].(string); ok {
						b.json += p
					}
				}
			case "message_delta":
				if d, ok := j["delta"].(map[string]any); ok {
					if sr, ok := d["stop_reason"].(string); ok && sr != "" {
						stopReason = sr
					}
				}
				if u, ok := j["usage"].(map[string]any); ok {
					addUsage(&usage, u, string(StyleAnthropic))
				}
			}
			return true, nil
		})
		resp.Body.Close()
		if sErr != nil {
			c.emitLLM("error", t0, 0, 0)
			return sErr
		}
		c.emitLLM("ok", t0, usage.PromptTokens-beforeP, usage.CompletionTokens-beforeC)
		if c.opts.Hooks != nil && c.opts.Hooks.AfterLLM != nil {
			if err := c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: map[string]any{"streamed": true}, Model: c.opts.Model, Turn: turn}); err != nil {
				return err
			}
		}

		var content []any
		type useBlock struct {
			id, name string
			input    map[string]any
		}
		var uses []useBlock
		var textParts []string
		for _, idx := range order {
			b := blocks[idx]
			if b.typ == "tool_use" {
				in := safeJSONArgs(b.json)
				content = append(content, map[string]any{"type": "tool_use", "id": b.id, "name": b.name, "input": in})
				uses = append(uses, useBlock{b.id, b.name, in})
			} else {
				content = append(content, map[string]any{"type": "text", "text": b.text})
				textParts = append(textParts, b.text)
			}
		}
		messages = append(messages, map[string]any{"role": "assistant", "content": content})

		if stopReason != "tool_use" || len(uses) == 0 {
			text := strings.Join(textParts, "")
			u := usage
			ch <- StreamEvent{Type: "usage", Usage: &u}
			res := c.endRun(runStart, text, messages, toolCalls, turns, usage)
			ch <- StreamEvent{Type: "done", Result: &res, Usage: &u}
			return nil
		}

		for _, u := range uses {
			ch <- StreamEvent{Type: "tool_call", ID: u.id, Name: u.name, Args: u.input}
		}
		results := make([]any, len(uses))
		records := make([]ToolCall, len(uses))
		events := make([]StreamEvent, len(uses))
		var wg sync.WaitGroup
		var hookErr error
		var mu sync.Mutex
		var halted *Request
		for i, u := range uses {
			wg.Add(1)
			go func(i int, u useBlock) {
				defer wg.Done()
				in := u.input
				if in == nil {
					in = map[string]any{}
				}
				args, result, err := c.runTool(ctx, tk, u.name, in, u.id, turn)
				if err == nil {
					if req := PendingOf(result); req != nil {
						ch <- StreamEvent{Type: "pending", Request: req} // surface BEFORE WaitFor so a channel can push the link
						r, h, rerr := c.resolvePending(ctx, tk, u.name, args, *req, u.id, turn)
						err, result = rerr, r
						if h != nil {
							mu.Lock()
							halted = h
							mu.Unlock()
						}
					}
				}
				if err != nil {
					mu.Lock()
					if hookErr == nil {
						hookErr = err
					}
					mu.Unlock()
					return
				}
				records[i] = ToolCall{Name: u.name, Args: args, Output: result.Output, IsError: result.IsError, Metadata: result.Metadata}
				results[i] = map[string]any{"type": "tool_result", "tool_use_id": u.id, "content": result.Output, "is_error": result.IsError}
				events[i] = StreamEvent{Type: "tool_result", ID: u.id, Name: u.name, Output: result.Output, IsError: result.IsError}
			}(i, u)
		}
		wg.Wait()
		if hookErr != nil {
			return hookErr
		}
		for i := range uses {
			toolCalls = append(toolCalls, records[i])
			ch <- events[i]
		}
		messages = append(messages, map[string]any{"role": "user", "content": results})
		if halted != nil {
			res := c.pendingRun(runStart, *halted, messages, toolCalls, turns, usage)
			u := usage
			ch <- StreamEvent{Type: "done", Result: &res, Usage: &u}
			return nil
		}
	}
	res := c.endRun(runStart, "", messages, toolCalls, turns, usage)
	u := usage
	ch <- StreamEvent{Type: "done", Result: &res, Usage: &u}
	return nil
}

// streamCall accumulates an OpenAI streamed tool call across delta chunks.
type streamCall struct {
	id, name, args string
}

// streamBlock accumulates an Anthropic streamed content block across deltas.
type streamBlock struct {
	typ, text, id, name, json string
}

// scanSSE reads an SSE body line-by-line with bufio.Scanner, invoking fn per
// line. fn returns (keepGoing, err); a false keepGoing stops cleanly (e.g.
// [DONE]). ctx cancellation aborts and returns ctx.Err(). Mirrors js sseLines.
func scanSSE(ctx context.Context, body io.Reader, fn func(line string) (bool, error)) error {
	sc := bufio.NewScanner(body)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		line := strings.TrimRight(sc.Text(), "\r")
		cont, err := fn(line)
		if err != nil {
			return err
		}
		if !cont {
			return nil
		}
	}
	if err := sc.Err(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return err
	}
	return nil
}

// safeParseMap JSON-parses s into a map, returning nil on failure.
func safeParseMap(s string) map[string]any {
	var out map[string]any
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		return nil
	}
	return out
}

// firstChoice returns choices[0] as a map, or an empty map.
func firstChoice(j map[string]any) map[string]any {
	cs, _ := j["choices"].([]any)
	if len(cs) == 0 {
		return map[string]any{}
	}
	c0, _ := cs[0].(map[string]any)
	if c0 == nil {
		return map[string]any{}
	}
	return c0
}

// intField reads an integer JSON field (decoded as float64), defaulting to 0.
func intField(j map[string]any, k string) int {
	if f, ok := j[k].(float64); ok {
		return int(f)
	}
	return 0
}

// lastText returns the last assistant message's string content, or "".
func lastText(messages []any) string {
	for i := len(messages) - 1; i >= 0; i-- {
		m, ok := messages[i].(map[string]any)
		if !ok || m["role"] != "assistant" {
			continue
		}
		if s, ok := m["content"].(string); ok {
			return s
		}
	}
	return ""
}

// ---- Conversation memory ----

// ConversationStore is where Ask(prompt, id) conversations are remembered — two
// methods. Ship InMemoryConversationStore; implement this for a file/db/redis
// provider to persist across processes. Mirrors js ConversationStore. Get
// returns a nil slice (like the JS `undefined`) when id has no stored
// transcript, and an error only on an unexpected failure a custom store wants to
// surface.
type ConversationStore interface {
	// Get returns the stored transcript for id, or nil when none exists.
	Get(id string) ([]any, error)
	// Save persists the (updated) transcript for id.
	Save(id string, messages []any) error
}

// InMemoryConversationStore is the default conversation provider — it keeps
// transcripts in memory for the client's lifetime. Safe for concurrent use;
// copies on get/save so callers can't mutate stored slices. Mirrors js
// InMemoryConversationStore.
type InMemoryConversationStore struct {
	mu   sync.Mutex
	msgs map[string][]any
}

// NewInMemoryConversationStore builds an empty in-memory conversation store.
func NewInMemoryConversationStore() *InMemoryConversationStore {
	return &InMemoryConversationStore{msgs: map[string][]any{}}
}

// Get returns a copy of the stored transcript for id, or (nil, nil) when absent.
func (s *InMemoryConversationStore) Get(id string) ([]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.msgs[id]
	if !ok {
		return nil, nil
	}
	clone := make([]any, len(m))
	copy(clone, m)
	return clone, nil
}

// Save stores a copy of the transcript under id.
func (s *InMemoryConversationStore) Save(id string, messages []any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	clone := make([]any, len(messages))
	copy(clone, messages)
	s.msgs[id] = clone
	return nil
}

// Conversation is a stateful multi-turn conversation: each Send continues the
// same transcript (memory). Mirrors js Conversation. Not safe for concurrent
// Send calls.
type Conversation struct {
	client *Client
	tk     *Toolkit
	// Messages is the full running transcript (system + user + assistant + tool).
	Messages []any
}

// Conversation builds a Conversation bound to this client and toolkit.
func (c *Client) Conversation(tk *Toolkit) *Conversation {
	return &Conversation{client: c, tk: tk}
}

// Send sends the next user turn; prior history is retained automatically and the
// returned RunResult.Messages becomes the new transcript.
func (conv *Conversation) Send(ctx context.Context, prompt string) (RunResult, error) {
	res, err := conv.client.RunWithHistory(ctx, prompt, conv.tk, conv.Messages)
	if err != nil {
		return res, err
	}
	conv.Messages = res.Messages
	return res, nil
}

// Reset clears the conversation memory.
func (conv *Conversation) Reset() {
	conv.Messages = nil
}
