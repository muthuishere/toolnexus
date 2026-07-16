// HTTP / REST tools — declare a remote endpoint as a uniform Tool.
// Mirrors js/src/http.ts and SPEC.md §7.

package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

const httpDefaultTimeout = 30 * time.Second

// HTTPToolOptions configures an HTTP-backed tool.
type HTTPToolOptions struct {
	Name        string
	Description string
	Method      string // "GET" | "POST" | ...
	// URL may contain {placeholders} filled (and consumed) from args.
	URL string
	// Headers are static; ${ENV_VAR} in values expands from os.Getenv. Never logged.
	Headers map[string]string
	// Query arg names are sent as querystring instead of in the body.
	Query []string
	// Body encoding for non-GET requests: "json" (default) | "form" | "raw".
	Body string
	// InputSchema describes the args the model supplies. Nil ⇒ empty object.
	InputSchema JSONSchema
	// Timeout in milliseconds; 0 ⇒ 30000.
	Timeout int
	// ResultMode: "text" (default) | "json" | "status+text".
	ResultMode string
}

var placeholderRe = regexp.MustCompile(`\{(\w+)\}`)
var envVarRe = regexp.MustCompile(`\$\{([A-Za-z0-9_]+)\}`)

// expandHeaders substitutes ${ENV_VAR} in header values from the environment.
// Values are never logged.
func expandHeaders(headers map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range headers {
		out[k] = envVarRe.ReplaceAllStringFunc(v, func(m string) string {
			name := envVarRe.FindStringSubmatch(m)[1]
			return os.Getenv(name)
		})
	}
	return out
}

// HTTPTool wraps an HTTP endpoint as a uniform Tool (source: "http").
func HTTPTool(opts HTTPToolOptions) Tool {
	method := strings.ToUpper(opts.Method)
	if method == "" {
		method = http.MethodGet
	}
	querySet := map[string]bool{}
	for _, q := range opts.Query {
		querySet[q] = true
	}
	schema := opts.InputSchema
	if schema == nil {
		schema = emptySchema()
	}

	return Tool{
		Name:        opts.Name,
		Description: opts.Description,
		InputSchema: schema,
		Source:      SourceHTTP,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			// Copy args so we can consume keys as we route them.
			a := map[string]any{}
			for k, v := range args {
				a[k] = v
			}

			// 1. {placeholder} substitution in the URL (consumes the arg).
			endpoint := placeholderRe.ReplaceAllStringFunc(opts.URL, func(m string) string {
				key := placeholderRe.FindStringSubmatch(m)[1]
				val, ok := a[key]
				if ok {
					delete(a, key)
				}
				return url.PathEscape(toStr(val))
			})

			// 2. querystring args (all args for GET; named args otherwise).
			params := url.Values{}
			for key := range a {
				if querySet[key] || method == http.MethodGet {
					params.Add(key, toStr(a[key]))
				}
			}
			for key := range a {
				if querySet[key] || method == http.MethodGet {
					delete(a, key)
				}
			}
			if qs := params.Encode(); qs != "" {
				if strings.Contains(endpoint, "?") {
					endpoint += "&" + qs
				} else {
					endpoint += "?" + qs
				}
			}

			// 3. body.
			headers := expandHeaders(opts.Headers)
			var bodyReader io.Reader
			if method != http.MethodGet && method != http.MethodHead && len(a) > 0 {
				mode := opts.Body
				if mode == "" {
					mode = "json"
				}
				switch mode {
				case "json":
					if _, ok := headerKey(headers, "Content-Type"); !ok {
						headers["Content-Type"] = "application/json"
					}
					raw, _ := json.Marshal(a)
					bodyReader = strings.NewReader(string(raw))
				case "form":
					if _, ok := headerKey(headers, "Content-Type"); !ok {
						headers["Content-Type"] = "application/x-www-form-urlencoded"
					}
					form := url.Values{}
					for k, v := range a {
						form.Set(k, toStr(v))
					}
					bodyReader = strings.NewReader(form.Encode())
				case "raw":
					bodyReader = strings.NewReader(toStr(a["body"]))
				}
			}

			// Timeout: ctx override > opts > default.
			timeout := httpDefaultTimeout
			if opts.Timeout > 0 {
				timeout = time.Duration(opts.Timeout) * time.Millisecond
			}
			if tctx != nil && tctx.Timeout > 0 {
				timeout = time.Duration(tctx.Timeout) * time.Millisecond
			}

			parent := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				parent = tctx.Ctx
			}
			reqCtx, cancel := context.WithTimeout(parent, timeout)
			defer cancel()

			req, err := http.NewRequestWithContext(reqCtx, method, endpoint, bodyReader)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true}, nil
			}
			for k, v := range headers {
				req.Header.Set(k, v)
			}

			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true}, nil
			}
			defer resp.Body.Close()
			raw, _ := io.ReadAll(resp.Body)
			text := string(raw)

			if resp.StatusCode < 200 || resp.StatusCode >= 300 {
				return ToolResult{
					Output:   fmt.Sprintf("HTTP %d: %s", resp.StatusCode, text),
					IsError:  true,
					Metadata: map[string]any{"status": resp.StatusCode},
				}, nil
			}

			var output string
			switch opts.ResultMode {
			case "status+text":
				output = fmt.Sprintf("%d\n%s", resp.StatusCode, text)
			case "json":
				output = string(reencodeJSON(text))
			default:
				output = text
			}
			return ToolResult{
				Output:   output,
				IsError:  false,
				Metadata: map[string]any{"status": resp.StatusCode},
			}, nil
		},
	}
}

// toStr renders an arg value as a string for URL/form use.
func toStr(v any) string {
	if v == nil {
		return ""
	}
	switch x := v.(type) {
	case string:
		return x
	case fmt.Stringer:
		return x.String()
	default:
		return fmt.Sprintf("%v", x)
	}
}

// headerKey returns the canonical match (case-insensitive) if present.
func headerKey(headers map[string]string, key string) (string, bool) {
	for k := range headers {
		if strings.EqualFold(k, key) {
			return k, true
		}
	}
	return "", false
}

// reencodeJSON parses text and re-marshals it; on parse failure it JSON-encodes
// the raw string. Mirrors the JS safeParse + JSON.stringify behavior.
func reencodeJSON(text string) []byte {
	var v any
	if err := json.Unmarshal([]byte(text), &v); err != nil {
		b, _ := json.Marshal(text)
		return b
	}
	b, _ := json.Marshal(v)
	return b
}
