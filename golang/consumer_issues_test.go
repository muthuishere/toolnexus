// Regression tests for the consumer issues #86–#93, in the shipped ports'
// behaviour rather than in prose. Hermetic throughout: an injected
// http.RoundTripper stands in for the provider, so there is no network, no API
// key and no cost. The harnesses are the ones from spikes/issues/*.
package toolnexus

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

// ---------------------------------------------------------------- helpers

// recordingTransport captures every request body and replies from a script.
type recordingTransport struct {
	bodies  []map[string]any
	reply   func(n int) *http.Response
	calls   int
	rawSeen []string
}

func (r *recordingTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	r.calls++
	if req.Body != nil {
		raw, _ := io.ReadAll(req.Body)
		r.rawSeen = append(r.rawSeen, string(raw))
		var m map[string]any
		_ = json.Unmarshal(raw, &m)
		r.bodies = append(r.bodies, m)
	}
	return r.reply(r.calls), nil
}

func jsonResp(status int, v any, header http.Header) *http.Response {
	b, _ := json.Marshal(v)
	if header == nil {
		header = http.Header{}
	}
	header.Set("Content-Type", "application/json")
	return &http.Response{StatusCode: status, Header: header, Body: io.NopCloser(strings.NewReader(string(b)))}
}

// sseText is finalText for the streaming path.
func sseText(text string) *http.Response {
	body := strings.Join([]string{
		`data: {"choices":[{"delta":{"content":"` + text + `"}}]}`,
		`data: {"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}`,
		`data: [DONE]`,
		``,
	}, "\n\n")
	return &http.Response{StatusCode: 200,
		Header: http.Header{"Content-Type": []string{"text/event-stream"}},
		Body:   io.NopCloser(strings.NewReader(body))}
}

func finalText(text string) *http.Response {
	return jsonResp(200, map[string]any{
		"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": text}}},
		"usage":   map[string]any{"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7},
	}, nil)
}

func mockClient(t *testing.T, rt http.RoundTripper, opts ...func(*ClientOptions)) *Client {
	t.Helper()
	o := ClientOptions{BaseURL: "http://mock.local", Style: StyleOpenAI, Model: "m", APIKey: "test",
		HTTPClient: &http.Client{Transport: rt}}
	for _, f := range opts {
		f(&o)
	}
	return CreateClient(o)
}

// ------------------------------------------------------- D1 / issue #86

// A toolkit-less completion is a supported posture, not a crash: `builtins:false`
// already expresses "no tools", so a nil Toolkit must run the plain §0.10 loop.
// Go already accepted nil; this PINS it, in all three entry points.
func TestD1_NilToolkitCompletes(t *testing.T) {
	for _, entry := range []string{"Run", "Ask", "Stream"} {
		t.Run(entry, func(t *testing.T) {
			reply := func(int) *http.Response { return finalText("hi") }
			if entry == "Stream" {
				reply = func(int) *http.Response { return sseText("hi") }
			}
			rt := &recordingTransport{reply: reply}
			c := mockClient(t, rt)
			var text string
			switch entry {
			case "Run":
				r, err := c.Run(nil, "hello", nil)
				if err != nil {
					t.Fatal(err)
				}
				text = r.Text
			case "Ask":
				r, err := c.Ask(nil, "hello", nil, "conv-1")
				if err != nil {
					t.Fatal(err)
				}
				text = r.Text
			case "Stream":
				ch, err := c.Stream(nil, "hello", nil)
				if err != nil {
					t.Fatal(err)
				}
				for ev := range ch {
					if ev.Type == "error" {
						t.Fatal(ev.Err)
					}
					if ev.Type == "done" {
						text = ev.Result.Text
					}
				}
			}
			if text != "hi" {
				t.Fatalf("%s with a nil toolkit = %q, want %q", entry, text, "hi")
			}
			// The wire assertion: NO `tools`, NO `tool_choice`. Not an empty
			// array — a provider that rejects `tools: []` must still be usable.
			if len(rt.bodies) == 0 {
				t.Fatal("no request captured")
			}
			body := rt.bodies[0]
			if _, ok := body["tools"]; ok {
				t.Errorf("toolkit-less body carries a %q key: %s", "tools", rt.rawSeen[0])
			}
			if _, ok := body["tool_choice"]; ok {
				t.Errorf("toolkit-less body carries a %q key: %s", "tool_choice", rt.rawSeen[0])
			}
		})
	}
}

// ------------------------------------------------------- D4 / issue #89

func haltedRelayFixture(t *testing.T) (history []any, pending Request) {
	t.Helper()
	history = []any{
		map[string]any{"role": "user", "content": "which environment?"},
		map[string]any{"role": "assistant", "tool_calls": []any{map[string]any{
			"id": "call_1", "type": "function",
			"function": map[string]any{"name": "ask_human", "arguments": "{}"},
		}}},
		map[string]any{"role": "tool", "tool_call_id": "call_1", "content": "which environment?"},
	}
	pending = Request{ID: "req-1", Kind: RelayKind, Prompt: "which environment?", Data: map[string]any{
		"calls": []any{map[string]any{"id": "call_1", "name": "ask_human", "input": map[string]any{}, "arguments": "{}"}},
	}}
	return history, pending
}

// The issue's exact report: Answer.Data with an unrecognised key used to be
// discarded, the model fed a FABRICATED tool error, and the host handed
// status "done" with a nil error. It must now be an error TO THE HOST.
func TestD4_UnrecognisedAnswerPayloadErrorsToTheHost(t *testing.T) {
	history, pending := haltedRelayFixture(t)
	rt := &recordingTransport{reply: func(int) *http.Response { return finalText("never reached") }}
	c := mockClient(t, rt)

	for _, data := range []map[string]any{
		{"value": "staging"},     // the issue's report
		{"answers": []any{"st"}}, // the (broken) documented example
		{},                       // nothing at all
	} {
		res, err := c.RunWithAnswer(nil, nil, history, pending, Answer{ID: "req-1", Ok: true, Data: data})
		if err == nil {
			t.Fatalf("Ok=true with data %v returned status %q and a nil error — the answer was silently dropped", data, res.Status)
		}
		if !strings.Contains(err.Error(), "AnswerOutput") {
			t.Errorf("the error must point at the constructor, got: %v", err)
		}
	}
	if rt.calls != 0 {
		t.Errorf("the provider must not be called at all on a malformed answer (called %d×)", rt.calls)
	}
}

// AnswerOutput is the constructor the error points at, and it must work.
func TestD4_AnswerOutputResumes(t *testing.T) {
	history, pending := haltedRelayFixture(t)
	rt := &recordingTransport{reply: func(int) *http.Response { return finalText("ok: staging") }}
	c := mockClient(t, rt)

	ans := AnswerOutput("req-1", "staging")
	if ans.ID != "req-1" || !ans.Ok || ans.Data[RelayOutputKey] != "staging" {
		t.Fatalf("AnswerOutput built %+v", ans)
	}
	res, err := c.RunWithAnswer(nil, nil, history, pending, ans)
	if err != nil {
		t.Fatal(err)
	}
	if res.Text != "ok: staging" {
		t.Fatalf("resume text = %q", res.Text)
	}
	// The host's output reached the transcript as that call's tool_result.
	if !strings.Contains(rt.rawSeen[0], "staging") {
		t.Errorf("the answer never reached the wire: %s", rt.rawSeen[0])
	}
}

// A non-string `output` used to degrade to "" — a caller mistake laundered into
// a tool that "returned nothing".
func TestD4_NonStringOutputErrors(t *testing.T) {
	history, pending := haltedRelayFixture(t)
	c := mockClient(t, &recordingTransport{reply: func(int) *http.Response { return finalText("x") }})
	for _, bad := range []any{42, []any{"staging"}, map[string]any{"v": 1}, nil} {
		_, err := c.RunWithAnswer(nil, nil, history, pending,
			Answer{ID: "req-1", Ok: true, Data: map[string]any{RelayOutputKey: bad}})
		if err == nil {
			t.Fatalf("output %#v (%T) was accepted", bad, bad)
		}
		if !strings.Contains(err.Error(), "want a string") {
			t.Errorf("error should say what was wrong, got: %v", err)
		}
	}
}

// The fabricated filler survives for the ONE case it was written for: a
// multi-call relay turn the host deliberately answered in part (A3: at least one
// RECOGNISED key present).
func TestD4_PartialRelayAnswerKeepsTheFiller(t *testing.T) {
	history := []any{
		map[string]any{"role": "user", "content": "go"},
		map[string]any{"role": "assistant", "tool_calls": []any{
			map[string]any{"id": "call_1", "type": "function", "function": map[string]any{"name": "a", "arguments": "{}"}},
			map[string]any{"id": "call_2", "type": "function", "function": map[string]any{"name": "b", "arguments": "{}"}},
		}},
	}
	pending := Request{ID: "req-1", Kind: RelayKind, Data: map[string]any{"calls": []any{
		map[string]any{"id": "call_1", "name": "a"}, map[string]any{"id": "call_2", "name": "b"},
	}}}
	rt := &recordingTransport{reply: func(int) *http.Response { return finalText("done") }}
	c := mockClient(t, rt)
	_, err := c.RunWithAnswer(nil, nil, history, pending,
		RelayAnswer("req-1", []RelayResult{{ID: "call_1", Output: "first"}}))
	if err != nil {
		t.Fatalf("a partial relay answer must still resume: %v", err)
	}
	if !strings.Contains(rt.rawSeen[0], "no result supplied on resume for b") {
		t.Errorf("the unanswered call must keep its balancing placeholder: %s", rt.rawSeen[0])
	}
}

// A3: `results` wins over `output` when both are present.
func TestD4_ResultsTakePrecedenceOverOutput(t *testing.T) {
	history, pending := haltedRelayFixture(t)
	rt := &recordingTransport{reply: func(int) *http.Response { return finalText("done") }}
	c := mockClient(t, rt)
	_, err := c.RunWithAnswer(nil, nil, history, pending, Answer{ID: "req-1", Ok: true, Data: map[string]any{
		"results": []any{map[string]any{"id": "call_1", "output": "FROM_RESULTS"}},
		"output":  "FROM_OUTPUT",
	}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(rt.rawSeen[0], "FROM_RESULTS") || strings.Contains(rt.rawSeen[0], "FROM_OUTPUT") {
		t.Errorf("results must win over output: %s", rt.rawSeen[0])
	}
}

// ------------------------------------------------------- D5 / issues #91,#92

// The leaking body from the report is 96 bytes — well under any cap. A cap is
// not redaction, and this asserts both independently.
func TestD5_ProviderErrorRedactsAccountIdentifiers(t *testing.T) {
	body := `{"error":{"message":"not a valid model ID","code":400},"user_id":"user_2FAKEfakefakefake"}`
	rt := &recordingTransport{reply: func(int) *http.Response {
		return jsonResp(400, json.RawMessage(body), http.Header{"Retry-After": []string{"7"}})
	}}
	c := mockClient(t, rt, func(o *ClientOptions) { o.Retries = 4 })
	_, err := c.Run(nil, "hi", nil)
	if err == nil {
		t.Fatal("a 400 must be an error")
	}

	var pe *ProviderError
	if !errors.As(err, &pe) {
		t.Fatalf("the provider failure must travel as a VALUE, got %T: %v", err, err)
	}
	if pe.Status != 400 {
		t.Errorf("Status = %d", pe.Status)
	}
	if pe.RetryAfter != "7" {
		t.Errorf("RetryAfter = %q", pe.RetryAfter)
	}
	for _, s := range []string{pe.Body, pe.Error()} {
		if strings.Contains(s, "user_2FAKE") {
			t.Errorf("an account identifier survived redaction: %s", s)
		}
		if !strings.Contains(s, RedactedMarker) {
			t.Errorf("the SHAPE must survive — %q expected in: %s", RedactedMarker, s)
		}
	}
	if !strings.Contains(pe.Body, "not a valid model ID") {
		t.Errorf("the provider's own cause must survive: %s", pe.Body)
	}

	// MUST NOT REGRESS: fail-fast on 4xx despite Retries: 4 — the retryable set
	// is ENUMERATED {429,500,502,503,504,529}, never "any 4xx/5xx".
	if rt.calls != 1 {
		t.Errorf("a 400 with Retries:4 must cost exactly ONE attempt, got %d", rt.calls)
	}
}

// A5: the cap is a property of the MESSAGE; the typed field carries the whole
// redacted body, because a host that reached for the type asked for all of it.
func TestD5_CapIsMessageOnly(t *testing.T) {
	long := `{"error":"` + strings.Repeat("x", 500) + `"}`
	pe := newProviderError(500, []byte(long), "")
	if len(pe.Body) != len(long) {
		t.Errorf("the typed Body must be uncapped: %d vs %d", len(pe.Body), len(long))
	}
	if len(pe.Error()) > providerBodyCap+40 {
		t.Errorf("the message must be capped, got %d chars", len(pe.Error()))
	}
}

// 401/403 bodies routinely echo the credential that was sent, so they are
// dropped in full — the policy the classifier path has always had, now on §8.
func TestD5_AuthBodyIsDroppedEntirely(t *testing.T) {
	for _, status := range []int{401, 403} {
		pe := newProviderError(status, []byte(`{"error":"bad key sk-live-SECRETVALUE"}`), "")
		if pe.Body != "" {
			t.Errorf("a %d body must be dropped, got %q", status, pe.Body)
		}
		if strings.Contains(pe.Error(), "SECRET") {
			t.Errorf("a %d message leaked the body: %s", status, pe.Error())
		}
	}
}

// The retryable set stays ENUMERATED — 429 retries, 400 does not, 501 does not.
func TestD5_RetryableSetStaysEnumerated(t *testing.T) {
	for status, wantRetryable := range map[int]bool{
		429: true, 500: true, 502: true, 503: true, 504: true, 529: true,
		400: false, 401: false, 404: false, 418: false, 501: false, 505: false,
	} {
		if got := isRetryableStatus(status, nil); got != wantRetryable {
			t.Errorf("isRetryableStatus(%d) = %v, want %v", status, got, wantRetryable)
		}
	}
}

// Go used to return `(RunResult{}, err)` — the ZERO value — so a caller who
// branched on Status first fell through every case on an empty string.
func TestD5_TimeoutReturnsIncompleteNotAZeroValue(t *testing.T) {
	slow := &recordingTransport{reply: func(int) *http.Response { return finalText("too late") }}
	c := CreateClient(ClientOptions{
		BaseURL: "http://mock.local", Style: StyleOpenAI, Model: "m", APIKey: "test",
		TimeoutMs: 1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			<-req.Context().Done()
			return nil, req.Context().Err()
		})},
	})
	_ = slow
	res, err := c.Run(nil, "hi", nil)
	if err == nil {
		t.Fatal("the deadline must still be an error")
	}
	var te *RunTimeoutError
	if !errors.As(err, &te) {
		t.Fatalf("the timeout must be typed and NAME THE BUDGET, got %T: %v", err, err)
	}
	if !strings.Contains(err.Error(), "run timeout after 1ms") {
		t.Errorf("message must name the budget, got %q", err.Error())
	}
	if res.Status != RunStatusIncomplete {
		t.Errorf("Status = %q, want %q — never the zero value beside a non-nil error", res.Status, RunStatusIncomplete)
	}
	if res.Limit != RunLimitTimeout {
		t.Errorf("Limit = %q, want %q", res.Limit, RunLimitTimeout)
	}
	if res.Turns == 0 {
		t.Error("the turns accumulated before the deadline must be preserved")
	}
	if res.Model != "m" {
		t.Errorf("Model = %q — the partial result must be a real result", res.Model)
	}
}

// The two vocabularies are DISTINCT, and "timeout" is never a §8 status.
func TestD5_TwoStatusVocabulariesAreNamed(t *testing.T) {
	if RunStatusDone != "done" || RunStatusPending != "pending" || RunStatusIncomplete != "incomplete" {
		t.Fatal("the §8 status values are pinned")
	}
	for _, v := range []string{RunStatusDone, RunStatusPending, RunStatusIncomplete} {
		if v == "timeout" {
			t.Fatal(`"timeout" must NOT be in the §8 RunResult vocabulary — it is the §7D wait deadline`)
		}
	}
	if RunLimitTimeout != "timeout" || RunLimitMaxTurns != "maxTurns" || RunLimitCompletion != "completion" {
		t.Fatal("the Limit vocabulary is pinned")
	}
}

// The three defaults are only jointly valid; keeping TypeSafe's model id while
// pointing BaseURL at the gateway must fail BEFORE the wire.
func TestD5_ClassifierBackendPairing(t *testing.T) {
	if _, err := CreateClassifier(ClassifierOptions{}); err != nil {
		t.Fatalf("the zero value must stay valid (jev-latest IS servable on TypeSafe): %v", err)
	}
	c, err := CreateClassifier(ClassifierOptions{Backend: BackendOpenRouter})
	if err != nil {
		t.Fatalf("the openrouter preset must set all three as a unit: %v", err)
	}
	if c.opts.BaseURL != OpenRouterClassifierBaseURL || c.opts.Model != OpenRouterClassifierModel ||
		c.opts.APIKeyEnv != OpenRouterClassifierAPIKeyEnv {
		t.Errorf("preset applied partially: %s / %s / %s", c.opts.BaseURL, c.opts.Model, c.opts.APIKeyEnv)
	}

	_, err = CreateClassifier(ClassifierOptions{BaseURL: "https://openrouter.ai/api/v1"})
	if err == nil {
		t.Fatal("the known cross-base mismatch must fail at construction")
	}
	want := `model "jev-latest" is TypeSafe's spelling; on openrouter.ai use "typesafe/jev-1.13"`
	if err.Error() != want {
		t.Errorf("message must be byte-identical across ports.\n got: %s\nwant: %s", err.Error(), want)
	}
	// A self-hosted origin, and the gateway's own spelling, both pass untouched.
	if _, err := CreateClassifier(ClassifierOptions{BaseURL: "https://jev.internal/v1", Model: "jev-latest"}); err != nil {
		t.Errorf("a self-hosted origin must not be second-guessed: %v", err)
	}
	if _, err := CreateClassifier(ClassifierOptions{BaseURL: "https://openrouter.ai/api/v1", Model: "typesafe/jev-1.13"}); err != nil {
		t.Errorf("the gateway's own spelling must pass: %v", err)
	}
}

// MUST NOT REGRESS: absent cost is NOT zero (ADR 0022). A plain float64 would
// make "this backend does not say" print as $0.00.
func TestD5_ClassifierCostStaysOptional(t *testing.T) {
	var u ClassifierUsage
	if u.Cost != nil {
		t.Fatal("Cost must default to absent, not 0")
	}
	v := 0.0
	u.Cost = &v
	if u.Cost == nil || *u.Cost != 0 {
		t.Fatal("a REPORTED zero must be distinguishable from an absent cost")
	}
}

// ------------------------------------------------------- D6 / issue #93

func writeSkillAt(t *testing.T, root, name, body string) {
	t.Helper()
	dir := filepath.Join(root, name)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

// The measured case: an unquoted `": "` inside a plain scalar. Every YAML parser
// refuses it, the writing tool accepts it, and the user has no signal. The
// rescue read recovers the FULL sentence, byte-identically.
func TestD6_LenientRescueOfAColonInAPlainScalar(t *testing.T) {
	root := t.TempDir()
	desc := "Work out billable hours from git commits. Trigger on: update the timesheet, do my timesheet."
	writeSkillAt(t, root, "colon-space", "---\nname: colon-space\ndescription: "+desc+"\n---\nbody\n")

	src := LoadSkills(root)
	info, ok := src.Skills["colon-space"]
	if !ok {
		t.Fatalf("the skill was skipped: %+v", src.Skipped)
	}
	if info.Description != desc {
		t.Errorf("description must be byte-identical.\n got: %q\nwant: %q", info.Description, desc)
	}
	if len(src.Skipped) != 0 {
		t.Errorf("nothing should be skipped: %+v", src.Skipped)
	}
}

// The inversion is the whole finding: a line-wise read running FIRST would
// misparse legitimate YAML. Block scalars must come back through the YAML path,
// untouched.
func TestD6_YamlRunsFirstSoBlockScalarsAreUntouched(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "block-literal", "---\nname: block-literal\ndescription: |\n  First line of the description.\n  Second line, with a colon: still fine inside a block scalar.\n---\nbody\n")
	writeSkillAt(t, root, "block-folded", "---\nname: block-folded\ndescription: >\n  A folded description that runs\n  across two source lines.\n---\nbody\n")

	src := LoadSkills(root)
	lit := src.Skills["block-literal"].Description
	if lit != "First line of the description.\nSecond line, with a colon: still fine inside a block scalar." {
		t.Errorf("block literal changed value: %q", lit)
	}
	if got := src.Skills["block-folded"].Description; got != "A folded description that runs across two source lines." {
		t.Errorf("folded scalar changed value: %q", got)
	}
}

// A11: `hash-inline` is decided by the YAML library's COMMENT handling, not by
// the rescue. ` #` opens a comment, so the description is exactly the text
// before it. A parser that keeps the tail still reports ok while carrying a
// DIFFERENT description — a silent mismatch — so assert the STRING, never the
// ok/skip verdict alone.
func TestD6_HashInlineIsDecidedByCommentHandling(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "hash-inline", "---\nname: hash-inline\ndescription: Tag things with #stockloop and Trigger on: nothing\n---\nbody\n")
	src := LoadSkills(root)
	info, ok := src.Skills["hash-inline"]
	if !ok {
		t.Fatalf("skipped: %+v", src.Skipped)
	}
	if info.Description != "Tag things with" {
		t.Errorf("description must stop at the YAML comment, got %q", info.Description)
	}
}

// The rescue read REFUSES a value opening `| > & * [ { !` — a half-broken block
// scalar degrades to "no description", never to an invented one.
func TestD6_RescueNeverInventsAValue(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "broken-flow", "---\nname: broken-flow\ndescription: [unterminated, flow, sequence\n---\nbody\n")

	src := LoadSkills(root)
	info, ok := src.Skills["broken-flow"]
	if !ok {
		t.Fatalf("the name is rescuable and must be rescued: %+v", src.Skipped)
	}
	if info.Name != "broken-flow" {
		t.Errorf("the NAME must be kept, got %q", info.Name)
	}
	if info.Description != "" {
		t.Errorf("a refused opener must yield NOTHING, got %q", info.Description)
	}
	// A12: this row is the proof A10 is implemented. A YAML library that THROWS
	// and one that error-RECOVERS `[unterminated` into a sequence only land here
	// together because a present-but-non-string name/description also triggers
	// the rescue. Simulate the recovering library's shape directly:
	root2 := t.TempDir()
	writeSkillAt(t, root2, "recovered", "---\nname: recovered\ndescription:\n  - a\n  - b\n---\nbody\n")
	src2 := LoadSkills(root2)
	got, ok2 := src2.Skills["recovered"]
	if !ok2 {
		t.Fatalf("a structurally-wrong description must not lose the name: %+v", src2.Skipped)
	}
	if got.Description != "" {
		t.Errorf("a sequence-valued description must be dropped, never kept or stringified: %q", got.Description)
	}
}

// Column 0, first-wins, and lenient-keys-only: the three guards, asserted.
func TestD6_RescueGuards(t *testing.T) {
	root := t.TempDir()
	// Tab indentation makes this invalid YAML; the indented `name:` line is a
	// continuation, not a top-level key, so it must not be read.
	writeSkillAt(t, root, "guards", "---\nname: first-wins\nname: second\n\tdescription: indented-not-a-key\ndescription: real description\n---\nbody\n")

	src := LoadSkills(root)
	info, ok := src.Skills["first-wins"]
	if !ok {
		t.Fatalf("skipped: %+v", src.Skipped)
	}
	if info.Description != "real description" {
		t.Errorf("column-0 guard failed, description = %q", info.Description)
	}
	if _, bad := src.Skills["second"]; bad {
		t.Error("first-wins guard failed")
	}
}

// A genuinely malformed file — nothing rescuable — is still REFUSED, and the
// skip now carries the native parser error alongside the byte-identical reason.
func TestD6_MalformedStillRefusedAndCarriesDetail(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "no-name", "---\ndescription: [unterminated\n---\nbody\n")

	inv := ListSkills(LoadSkillsOptions{Dirs: []string{root}})
	if len(inv.Skills) != 0 {
		t.Fatalf("a file with no rescuable name must be refused, got %+v", inv.Skills)
	}
	if len(inv.Skipped) != 1 || inv.Skipped[0].Reason != SkipMalformed {
		t.Fatalf("skips = %+v", inv.Skipped)
	}
	if inv.Skipped[0].Detail == "" {
		t.Error("the skip must carry the native parser error in Detail")
	}
	if string(inv.Skipped[0].Reason) != "malformed-frontmatter" {
		t.Errorf("the typed reason must stay byte-identical, got %q", inv.Skipped[0].Reason)
	}
}

// A2: the LOADING path reports its own losses. A host must not need a second
// ListSkills pass to learn that files vanished.
func TestD6_LoadSkillsExposesSkips(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "good", "---\nname: good\ndescription: fine\n---\nbody\n")
	writeSkillAt(t, root, "bad", "---\ndescription: no name here\n---\nbody\n")

	src := LoadSkills(root)
	if len(src.Skills) != 1 {
		t.Fatalf("skills = %v", src.Skills)
	}
	if len(src.Skipped) != 1 || src.Skipped[0].Reason != SkipMissingName {
		t.Fatalf("LoadSkills must return its skips, got %+v", src.Skipped)
	}
}

// A1: discovery order is lexicographic by path relative to the root, so
// first-wins dedupe is deterministic rather than a property of the filesystem.
func TestD6_DiscoveryOrderIsDeterministic(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "zeta", "---\nname: dup\ndescription: from zeta\n---\nz\n")
	writeSkillAt(t, root, "alpha", "---\nname: dup\ndescription: from alpha\n---\na\n")

	for i := 0; i < 5; i++ {
		src := LoadSkills(root)
		if got := src.Skills["dup"].Description; got != "from alpha" {
			t.Fatalf("run %d: first-wins must resolve lexicographically, got %q", i, got)
		}
	}
}

// A15: the ordering is DEPTH ASCENDING, then code point WITHIN a depth — and
// `xlsx` is the fixture that tells the two rules apart.
//
// `docx` vs `synced/<uuid>/docx` passes under BOTH rules (`d` < `s`) and
// therefore proves nothing. `xlsx` vs `synced/<uuid>/xlsx` does not: under a
// pure code-point sort `s` < `x`, so the NESTED copy would win, and the winner
// of a top-level-vs-nested duplicate would depend on the skill's first letter
// relative to an unrelated sibling directory's name. Depth-first makes the
// shallower copy win in both cases, which is the rule everyone wrote down.
func TestD6_ShallowerPathWinsRegardlessOfFirstLetter(t *testing.T) {
	root := t.TempDir()
	nested := filepath.Join("synced", "6636f0a1-0000-4000-8000-000000000000")
	for _, name := range []string{"docx", "xlsx"} {
		writeSkillAt(t, root, name,
			"---\nname: "+name+"\ndescription: top-level copy\n---\ntop\n")
		writeSkillAt(t, root, filepath.Join(nested, name),
			"---\nname: "+name+"\ndescription: synced copy\n---\nsynced\n")
	}
	for i := 0; i < 5; i++ {
		src := LoadSkills(root)
		// `d` < `s`: wins under either rule.
		if got := src.Skills["docx"].Description; got != "top-level copy" {
			t.Fatalf("run %d: docx = %q", i, got)
		}
		// `x` > `s`: wins ONLY because depth is compared first.
		if got := src.Skills["xlsx"].Description; got != "top-level copy" {
			t.Fatalf("run %d: xlsx resolved to the NESTED copy (%q) — the sort is "+
				"code-point-first, so the winner depends on the skill's first letter", i, got)
		}
	}
}

// Within ONE depth, code point is the tie-break — no case folding, no locale
// collation, no segment-aware comparison.
func TestD6_CodePointIsTheTieBreakWithinADepth(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "Zebra", "---\nname: cased\ndescription: uppercase Z\n---\nz\n")
	writeSkillAt(t, root, "apple", "---\nname: cased\ndescription: lowercase a\n---\na\n")
	for i := 0; i < 5; i++ {
		if got := LoadSkills(root).Skills["cased"].Description; got != "uppercase Z" {
			t.Fatalf("run %d: code-point order puts %q before %q — no case folding; got %q",
				i, "Zebra", "apple", got)
		}
	}
}

// A22: the §0.10 SKILLS PROMPT has its OWN ordering, separate from discovery
// order, and SPEC pins that prompt byte-identical across ports — so the order is
// part of the contract. It is CODE POINT, and these names are chosen so that the
// two wrong rules produce visibly different output:
//
//   - a LOCALE COLLATOR would file "Äpfel" next to "apple" near the front;
//     code point puts it LAST, because its UTF-8 lead byte (0xC3) is above "z".
//   - a UTF-16 CODE-UNIT compare would put the astral "𝔞" (U+1D51E, surrogate
//     lead D835) BEFORE "�"; code point puts U+FFFD first.
//
// Nothing here is ASCII-only, which is the point: the property is defended
// rather than incidental.
func TestD6_SkillsPromptOrderIsCodePoint(t *testing.T) {
	root := t.TempDir()
	names := []string{"Zurich", "apple", "zebra", "Äpfel", "�-replacement", "𝔞lpha"}
	for i, n := range names {
		writeSkillAt(t, root, "s"+string(rune('a'+i)),
			"---\nname: "+n+"\ndescription: d"+string(rune('0'+i))+"\n---\nbody\n")
	}
	prompt := LoadSkills(root).Prompt()

	want := []string{"Zurich", "apple", "zebra", "Äpfel", "�-replacement", "𝔞lpha"}
	sortedCopy := append([]string(nil), want...)
	sort.Strings(sortedCopy)
	for i := range want {
		if want[i] != sortedCopy[i] {
			t.Fatalf("the expectation itself is not code-point order: %v vs %v", want, sortedCopy)
		}
	}
	var got []string
	for _, line := range strings.Split(prompt, "\n") {
		if !strings.HasPrefix(line, "- **") {
			continue
		}
		got = append(got, line[4:strings.Index(line, "**:")])
	}
	if len(got) != len(want) {
		t.Fatalf("prompt listed %d skills, want %d:\n%s", len(got), len(want), prompt)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("§0.10 prompt order is not code point.\n got: %q\nwant: %q", got, want)
		}
	}
}

// The `skill` tool's not-found list is user-visible too (it reaches the model),
// and follows the same rule.
func TestD6_SkillNotFoundListIsCodePointOrdered(t *testing.T) {
	root := t.TempDir()
	for i, n := range []string{"zebra", "Äpfel", "apple"} {
		writeSkillAt(t, root, "s"+string(rune('a'+i)),
			"---\nname: "+n+"\ndescription: d\n---\nbody\n")
	}
	res, err := LoadSkills(root).Tool.Execute(map[string]any{"name": "nope"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !res.IsError {
		t.Fatal("an unknown skill must be an error result")
	}
	if !strings.Contains(res.Output, "apple, zebra, Äpfel") {
		t.Errorf("available-skills list is not code-point ordered: %s", res.Output)
	}
}

// A22, fourth site: the `<skill_files>` SAMPLE LIST in the skill tool's output.
// This one ships DIRECTLY as model-visible text, and because it is CAPPED the
// order also decides WHICH files appear at all.
//
// It was NOT correct by construction, unlike the prompt sort. os.ReadDir is
// sorted, but the walk pushed subdirectories onto a LIFO stack, so it emitted
// them in REVERSE order: alpha/ beta/ zeta/ came back zeta, beta, alpha.
func TestD6_SkillFilesSampleIsCodePointOrdered(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "s", "---\nname: s\ndescription: d\n---\nbody\n")
	dir := filepath.Join(root, "s")
	for _, sub := range []string{"alpha", "beta", "zeta", "Äpfel"} {
		if err := os.MkdirAll(filepath.Join(dir, sub), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, sub, "f.txt"), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for _, f := range []string{"a-root.txt", "z-root.txt"} {
		if err := os.WriteFile(filepath.Join(dir, f), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	res, err := LoadSkills(root).Tool.Execute(map[string]any{"name": "s"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, line := range strings.Split(res.Output, "\n") {
		if strings.HasPrefix(line, "<file>") {
			p := strings.TrimSuffix(strings.TrimPrefix(line, "<file>"), "</file>")
			r, _ := filepath.Rel(dir, p)
			got = append(got, filepath.ToSlash(r))
		}
	}
	// Code point over the path relative to the skill dir: "-" (0x2D) and "/"
	// (0x2F) both sort below letters, and "Äpfel" lands LAST because its UTF-8
	// lead byte (0xC3) is above "z" — a locale collator would file it near "a".
	want := []string{"a-root.txt", "alpha/f.txt", "beta/f.txt", "z-root.txt", "zeta/f.txt", "Äpfel/f.txt"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("<skill_files> is not code-point ordered.\n got: %v\nwant: %v", got, want)
		}
	}

	// A25: a GLOBAL sort over relative paths, plain code point — NOT a
	// per-directory sort during traversal. A directory `alpha/` and a sibling
	// file `alpha-b.txt` are exactly where the two rules disagree: the global
	// relative-path rule puts `alpha-b.txt` FIRST (`-` 0x2D < `/` 0x2F), while a
	// per-directory sort would descend `alpha` first (the bare name is a prefix
	// of `alpha-b.txt`, so it sorts before it). The global rule wins because it
	// is a function of the FILE SET alone; the per-directory rule is a function
	// of the traversal, and would make every port reproduce the same stack
	// discipline to emit the same bytes.
	if err := os.WriteFile(filepath.Join(dir, "alpha-b.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	res3, err := LoadSkills(root).Tool.Execute(map[string]any{"name": "s"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	ai := strings.Index(res3.Output, filepath.Join("alpha", "f.txt"))
	bi := strings.Index(res3.Output, "alpha-b.txt")
	if ai < 0 || bi < 0 {
		t.Fatalf("both entries must be listed:\n%s", res3.Output)
	}
	if bi > ai {
		t.Error("ordering is per-directory-level, not a global sort over relative paths")
	}

	// ADR-0004 K1: the sort runs BEFORE the cap, so the cap selects the
	// code-point-FIRST files deterministically. Order decides CONTENT here, not
	// just sequence — this is a content assertion, not a cosmetic one.
	capped := LoadSkillsWith(LoadSkillsOptions{Dirs: []string{root}, SampleLimit: 2})
	res2, err := capped.Tool.Execute(map[string]any{"name": "s"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(res2.Output, "<file>") != 2 {
		t.Fatalf("SampleLimit 2 emitted %d files", strings.Count(res2.Output, "<file>"))
	}
	// With alpha-b.txt now present the first two in code-point order are
	// a-root.txt and alpha-b.txt — and alpha/f.txt must NOT be sampled, which is
	// the content half of the assertion.
	if !strings.Contains(res2.Output, "a-root.txt") || !strings.Contains(res2.Output, "alpha-b.txt") {
		t.Errorf("the cap must take the code-point-first files, got:\n%s", res2.Output)
	}
	if strings.Contains(res2.Output, filepath.Join("alpha", "f.txt")) {
		t.Errorf("the cap changed WHICH files appear; alpha/f.txt sorts after the cap:\n%s", res2.Output)
	}
}

// A1a/A1b: the tie-break is a Unicode CODE-POINT comparison of the whole path
// relative to the root — no locale collation, no case folding, no segment-aware
// comparison — and a symlink sorts at its DISCOVERED path, not its target.
//
// This is a USER-VISIBLE FLIP in the Go port: with `docx/SKILL.md` and
// `synced/<hash>/docx/SKILL.md` both present (the real shape in ~/.claude/skills),
// Go used to keep the `synced/` copy and now keeps the top-level one. Same skill
// name, different file, different content.
func TestD6_DuplicateWinnerIsTheCodePointFirstPath(t *testing.T) {
	root := t.TempDir()
	writeSkillAt(t, root, "docx", "---\nname: docx\ndescription: top-level copy\n---\ntop\n")
	writeSkillAt(t, root, filepath.Join("synced", "6636abcd", "docx"), "---\nname: docx\ndescription: synced copy\n---\nsynced\n")

	for i := 0; i < 5; i++ {
		src := LoadSkills(root)
		if got := src.Skills["docx"].Description; got != "top-level copy" {
			t.Fatalf("run %d: docx must resolve to the top-level copy, got %q", i, got)
		}
	}
}

var _ = time.Second

// A26: the sort-before-cap rule is NOT about skills. Every capped listing of
// filesystem entries that reaches the model must COLLECT, SORT by path relative
// to the walk root in plain code point, THEN truncate — never break the walk at
// the cap.
//
// Both builtins had the defect. `glob` capped UPSTREAM of its sort, so the walk
// chose the content and the sort only ordered the survivors; `grep` capped
// mid-walk and had NO SORT AT ALL. filepath.WalkDir's lexical order did not
// rescue either: its order is per-directory-level while the sort is over full
// relative paths, so selection and presentation ran on two different rules.
func globGrepFixture(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	// `alpha/` beside `alpha-b.txt` is the discriminator: per-level order
	// descends `alpha` first, relative-path order puts `alpha-b.txt` first.
	for _, d := range []string{"alpha", "zeta"} {
		if err := os.MkdirAll(filepath.Join(root, d), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, d, "f.txt"), []byte("needle\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for _, f := range []string{"a-root.txt", "alpha-b.txt", "z-root.txt"} {
		if err := os.WriteFile(filepath.Join(root, f), []byte("needle\nneedle\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func runBuiltin(t *testing.T, name string, args map[string]any) string {
	t.Helper()
	for _, tool := range CreateBuiltinTools() {
		if tool.Name != name {
			continue
		}
		res, err := tool.Execute(args, nil)
		if err != nil {
			t.Fatal(err)
		}
		if res.IsError {
			t.Fatalf("%s: %s", name, res.Output)
		}
		return res.Output
	}
	t.Fatalf("builtin %q not found", name)
	return ""
}

func TestD6_GlobSortsBeforeItCaps(t *testing.T) {
	root := globGrepFixture(t)

	full := strings.Split(runBuiltin(t, "glob", map[string]any{"pattern": "**/*.txt", "path": root}), "\n")
	want := []string{"a-root.txt", "alpha-b.txt", "alpha/f.txt", "z-root.txt", "zeta/f.txt"}
	if len(full) != len(want) {
		t.Fatalf("got %v, want %v", full, want)
	}
	for i := range want {
		if full[i] != want[i] {
			t.Fatalf("glob order is not relative-path code point.\n got: %v\nwant: %v", full, want)
		}
	}

	// THE CONTENT ASSERTION: the cap must take the code-point-first two, not
	// whichever two the walk reached first. A per-level walk would have picked
	// `alpha/f.txt` over `alpha-b.txt`.
	capped := strings.Split(runBuiltin(t, "glob",
		map[string]any{"pattern": "**/*.txt", "path": root, "limit": 2}), "\n")
	if len(capped) != 2 || capped[0] != "a-root.txt" || capped[1] != "alpha-b.txt" {
		t.Errorf("the cap selected by traversal, not by sort order: %v", capped)
	}
	for _, line := range capped {
		if strings.Contains(line, "alpha/f.txt") {
			t.Errorf("alpha/f.txt sorts after the cap and must not appear: %v", capped)
		}
	}
}

func TestD6_GrepSortsBeforeItCaps(t *testing.T) {
	root := globGrepFixture(t)

	full := strings.Split(runBuiltin(t, "grep", map[string]any{"pattern": "needle", "path": root}), "\n")
	if len(full) != 8 { // 3 root files x2 lines + 2 nested x1
		t.Fatalf("expected 8 matches, got %d: %v", len(full), full)
	}
	// By relative path in code point, then by LINE NUMBER ascending.
	order := []string{"a-root.txt:1", "a-root.txt:2", "alpha-b.txt:1", "alpha-b.txt:2",
		"alpha/f.txt:1", "z-root.txt:1", "z-root.txt:2", "zeta/f.txt:1"}
	for i, suffix := range order {
		if !strings.Contains(filepath.ToSlash(full[i]), suffix+":") {
			t.Fatalf("grep match %d is %q, want one ending %q\nall: %v", i, full[i], suffix, full)
		}
	}

	// THE CONTENT ASSERTION: grep had no sort at all, so the cap returned
	// whatever the walk reached first.
	capped := strings.Split(runBuiltin(t, "grep",
		map[string]any{"pattern": "needle", "path": root, "limit": 3}), "\n")
	if len(capped) != 3 {
		t.Fatalf("limit 3 returned %d matches", len(capped))
	}
	if !strings.Contains(filepath.ToSlash(capped[2]), "alpha-b.txt:1:") {
		t.Errorf("the cap selected by traversal, not by sort order: %v", capped)
	}
	for _, line := range capped {
		if strings.Contains(filepath.ToSlash(line), "alpha/f.txt") {
			t.Errorf("alpha/f.txt sorts after the cap and must not appear: %v", capped)
		}
	}
}
