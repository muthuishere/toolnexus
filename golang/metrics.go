// Zero-dependency, mutex-guarded Prometheus registry (§8 Observability). It
// accumulates the same semantic MetricEvents the OnMetric sink sees into
// cumulative counters/histograms and renders the Prometheus text exposition
// format by hand (no third-party dependency — the format is plain text). The
// rendered text is BYTE-IDENTICAL across all ports; mirrors the js
// MetricsRegistry in src/client.ts.
package toolnexus

import (
	"sort"
	"strconv"
	"strings"
	"sync"
)

// durationBuckets are the FIXED histogram buckets (seconds) for the
// *_duration_seconds metrics — pinned across all ports for byte-parity.
var durationBuckets = []float64{0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60}

// bucketLabels are the rendered `le` values for durationBuckets — exactly the
// strings js emits via String(bucket) ("0.05", "1", "2.5", "60", …). Hardcoded
// so the label text is guaranteed identical to the js port.
var bucketLabels = []string{"0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "30", "60"}

// hist is one accumulated histogram: cumulative bucket counts, running sum
// (seconds), and total observation count. Mirrors js Hist.
type hist struct {
	counts []int64
	sum    float64
	count  int64
}

// metricsRegistry is an in-memory cumulative registry. All maps are keyed by the
// rendered label string (order of label pairs is load-bearing for parity).
type metricsRegistry struct {
	mu           sync.Mutex
	llmRequests  map[string]int64 // labels: model,status
	llmTokens    map[string]int64 // labels: type
	llmDuration  map[string]*hist // labels: model
	toolCalls    map[string]int64 // labels: tool,source,is_error
	toolDuration map[string]*hist // labels: tool
	runErrors    map[string]int64 // labels: model
}

// newMetricsRegistry builds an empty registry.
func newMetricsRegistry() *metricsRegistry {
	return &metricsRegistry{
		llmRequests:  map[string]int64{},
		llmTokens:    map[string]int64{},
		llmDuration:  map[string]*hist{},
		toolCalls:    map[string]int64{},
		toolDuration: map[string]*hist{},
		runErrors:    map[string]int64{},
	}
}

// record folds one semantic MetricEvent into the cumulative metrics. Mirrors js
// MetricsRegistry.record.
func (r *metricsRegistry) record(ev MetricEvent) {
	r.mu.Lock()
	defer r.mu.Unlock()
	switch ev.Event {
	case "llm":
		r.llmRequests[labelStr([2]string{"model", ev.Model}, [2]string{"status", ev.Status})]++
		if ev.Status == "ok" {
			r.llmTokens[labelStr([2]string{"type", "prompt"})] += int64(ev.PromptTokens)
			r.llmTokens[labelStr([2]string{"type", "completion"})] += int64(ev.CompletionTokens)
		}
		observe(r.llmDuration, labelStr([2]string{"model", ev.Model}), float64(ev.Ms)/1000)
	case "tool":
		r.toolCalls[labelStr([2]string{"tool", ev.Tool}, [2]string{"source", ev.Source}, [2]string{"is_error", strconv.FormatBool(ev.IsError)})]++
		observe(r.toolDuration, labelStr([2]string{"tool", ev.Tool}), float64(ev.Ms)/1000)
	case "run":
		if ev.Error != "" {
			r.runErrors[labelStr([2]string{"model", ev.Model})]++
		}
	}
}

// render produces the Prometheus text exposition of the cumulative metrics, in
// the fixed metric order, with series sorted lexicographically by label string,
// ending with a trailing newline. Byte-identical across ports. Mirrors js
// MetricsRegistry.render.
func (r *metricsRegistry) render() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	renderCounter(&out, "toolnexus_llm_requests_total", "Total LLM requests.", r.llmRequests)
	renderCounter(&out, "toolnexus_llm_tokens_total", "Total tokens, by type.", r.llmTokens)
	renderHistogram(&out, "toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", r.llmDuration)
	renderCounter(&out, "toolnexus_tool_calls_total", "Total tool calls.", r.toolCalls)
	renderHistogram(&out, "toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", r.toolDuration)
	renderCounter(&out, "toolnexus_run_errors_total", "Total run errors.", r.runErrors)
	return strings.Join(out, "\n") + "\n"
}

// observe folds one duration (seconds) into the histogram at key. Mirrors js observe.
func observe(m map[string]*hist, key string, seconds float64) {
	h := m[key]
	if h == nil {
		h = &hist{counts: make([]int64, len(durationBuckets))}
		m[key] = h
	}
	h.sum += seconds
	h.count++
	for i, b := range durationBuckets {
		if seconds <= b {
			h.counts[i]++
		}
	}
}

// renderCounter appends a counter's # HELP / # TYPE + one line per series
// (sorted by label string). Mirrors js MetricsRegistry.renderCounter.
func renderCounter(out *[]string, name, help string, m map[string]int64) {
	*out = append(*out, "# HELP "+name+" "+help)
	*out = append(*out, "# TYPE "+name+" counter")
	for _, key := range sortedKeys(m) {
		v := strconv.FormatInt(m[key], 10)
		if key != "" {
			*out = append(*out, name+"{"+key+"} "+v)
		} else {
			*out = append(*out, name+" "+v)
		}
	}
}

// renderHistogram appends a histogram's # HELP / # TYPE + cumulative
// _bucket{le=…} / _sum / _count lines per series (sorted by label string).
// Mirrors js MetricsRegistry.renderHistogram.
func renderHistogram(out *[]string, name, help string, m map[string]*hist) {
	*out = append(*out, "# HELP "+name+" "+help)
	*out = append(*out, "# TYPE "+name+" histogram")
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, key := range keys {
		h := m[key]
		for i := range durationBuckets {
			*out = append(*out, name+"_bucket{"+withLe(key, bucketLabels[i])+"} "+strconv.FormatInt(h.counts[i], 10))
		}
		*out = append(*out, name+"_bucket{"+withLe(key, "+Inf")+"} "+strconv.FormatInt(h.count, 10))
		count := strconv.FormatInt(h.count, 10)
		if key != "" {
			*out = append(*out, name+"_sum{"+key+"} "+jsNumber(h.sum))
			*out = append(*out, name+"_count{"+key+"} "+count)
		} else {
			*out = append(*out, name+"_sum "+jsNumber(h.sum))
			*out = append(*out, name+"_count "+count)
		}
	}
}

// sortedKeys returns the map keys sorted lexicographically.
func sortedKeys(m map[string]int64) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// labelStr renders ordered label pairs to `k="v",k="v"`; order is load-bearing
// for parity. Mirrors js labelStr.
func labelStr(pairs ...[2]string) string {
	parts := make([]string, len(pairs))
	for i, p := range pairs {
		parts[i] = p[0] + `="` + escapeLabel(p[1]) + `"`
	}
	return strings.Join(parts, ",")
}

// withLe appends the le label to an existing (possibly empty) label string.
// Mirrors js withLe.
func withLe(base, le string) string {
	if base != "" {
		return base + `,le="` + le + `"`
	}
	return `le="` + le + `"`
}

// escapeLabel escapes a Prometheus label value: backslash, double-quote,
// newline — in that order. Mirrors js escapeLabel.
func escapeLabel(v string) string {
	v = strings.ReplaceAll(v, `\`, `\\`)
	v = strings.ReplaceAll(v, `"`, `\"`)
	v = strings.ReplaceAll(v, "\n", `\n`)
	return v
}

// jsNumber formats a float the way ECMAScript String(number) does for the value
// range these metrics produce (shortest round-trip decimal, integers without a
// trailing ".0"). Go's 'g'/-1 formatting matches js String() across the duration
// sums seen here (e.g. 0.05, 2.5, 60, 0.019). Mirrors js `${sum}`.
func jsNumber(f float64) string {
	return strconv.FormatFloat(f, 'g', -1, 64)
}
