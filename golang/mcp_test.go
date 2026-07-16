// §10 MCP elicitation bridge — pure mapping parity test. Mirrors the JS unit test
// "mcp elicitation ⇄ §10 mapping" (js/test/unit.test.ts): a server can ask US for
// input mid-`tools/call`; we map that request onto the one §10 Request (form→input
// with data.schema, url→authorization with url) and map the resolved Answer back onto
// an MCP ElicitResult (ok→accept, reason:declined→decline, else→cancel).
//
// The generated `elc-...` Id is deliberately NOT asserted (matches JS).

package toolnexus

import (
	"reflect"
	"testing"

	"github.com/mark3labs/mcp-go/mcp"
)

func TestElicitationToRequest(t *testing.T) {
	schema := map[string]any{
		"type":       "object",
		"properties": map[string]any{"name": map[string]any{"type": "string"}},
		"required":   []any{"name"},
	}

	// form mode → kind:"input" carrying the schema in data.schema
	req := ElicitationToRequest(mcp.ElicitationParams{Message: "Your name?", RequestedSchema: schema})
	if req.Kind != "input" {
		t.Fatalf("form: kind = %q, want input", req.Kind)
	}
	if req.Prompt != "Your name?" {
		t.Fatalf("form: prompt = %q, want %q", req.Prompt, "Your name?")
	}
	if !reflect.DeepEqual(req.Data["schema"], schema) {
		t.Fatalf("form: data.schema = %#v, want %#v", req.Data["schema"], schema)
	}
	if req.URL != "" {
		t.Fatalf("form: url = %q, want empty", req.URL)
	}

	// URL mode → kind:"authorization" carrying the url, no schema
	ureq := ElicitationToRequest(mcp.ElicitationParams{Mode: "url", Message: "Log in", URL: "https://x/auth"})
	if ureq.Kind != "authorization" {
		t.Fatalf("url: kind = %q, want authorization", ureq.Kind)
	}
	if ureq.URL != "https://x/auth" {
		t.Fatalf("url: url = %q, want %q", ureq.URL, "https://x/auth")
	}
	if ureq.Data != nil {
		t.Fatalf("url: data = %#v, want nil", ureq.Data)
	}
}

func TestAnswerToElicitResult(t *testing.T) {
	cases := []struct {
		name   string
		answer Answer
		action mcp.ElicitationResponseAction
		// content is only meaningful for accept; nil means "not checked"
		content any
	}{
		{"accept with data", Answer{ID: "1", Ok: true, Data: map[string]any{"name": "Ada"}}, mcp.ElicitationResponseActionAccept, map[string]any{"name": "Ada"}},
		{"accept empty → empty map", Answer{ID: "1", Ok: true}, mcp.ElicitationResponseActionAccept, map[string]any{}},
		{"declined → decline", Answer{ID: "1", Ok: false, Reason: "declined"}, mcp.ElicitationResponseActionDecline, nil},
		{"bare false → cancel", Answer{ID: "1", Ok: false}, mcp.ElicitationResponseActionCancel, nil},
		{"expired → cancel", Answer{ID: "1", Ok: false, Reason: "expired"}, mcp.ElicitationResponseActionCancel, nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			res := AnswerToElicitResult(tc.answer)
			if res.Action != tc.action {
				t.Fatalf("action = %q, want %q", res.Action, tc.action)
			}
			if tc.action == mcp.ElicitationResponseActionAccept {
				if !reflect.DeepEqual(res.Content, tc.content) {
					t.Fatalf("content = %#v, want %#v", res.Content, tc.content)
				}
			}
		})
	}
}
