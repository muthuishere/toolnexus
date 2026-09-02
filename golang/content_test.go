// Multimodal content tests (§1B parts, §8A emission + relocation, §11 translate,
// §6 read, MCP in/out). Hermetic: mock LLMs via httptest, no network, no live
// model, the committed examples/media fixture as the base64 golden.
//
// Run: go test -race .

package toolnexus

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/mark3labs/mcp-go/mcp"
)

const fixturePNG = "../examples/media/fixture.png"

func goldenBase64(t *testing.T) string {
	t.Helper()
	raw, err := os.ReadFile(fixturePNG + ".base64")
	if err != nil {
		t.Fatalf("read golden: %v", err)
	}
	return strings.TrimSpace(string(raw))
}

// captureLLM answers /chat/completions or /v1/messages with a plain final
// answer, recording every request body it saw.
func capturePartsLLM(style ClientStyle, bodies *[]map[string]any) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		*bodies = append(*bodies, body)
		w.Header().Set("content-type", "application/json")
		if style == StyleAnthropic {
			_, _ = fmt.Fprint(w, mustJSON(map[string]any{
				"content": []any{map[string]any{"type": "text", "text": "ok"}},
				"usage":   map[string]any{"input_tokens": 1, "output_tokens": 1},
			}))
			return
		}
		_, _ = fmt.Fprint(w, mustJSON(map[string]any{
			"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": "ok"}}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		}))
	}))
}

func msgs(t *testing.T, body map[string]any) []any {
	t.Helper()
	m, ok := body["messages"].([]any)
	if !ok {
		t.Fatalf("no messages in body: %v", body)
	}
	return m
}

func objOf(t *testing.T, v any) map[string]any {
	t.Helper()
	m, ok := v.(map[string]any)
	if !ok {
		t.Fatalf("not an object: %#v", v)
	}
	return m
}

// ---------------------------------------------------------------------------
// §1B — the model and its edge constructors
// ---------------------------------------------------------------------------

// The committed golden is the authority: the port must encode the shared fixture
// to exactly those bytes, never to its own re-encoding.
func TestFileEncodesToCommittedGolden(t *testing.T) {
	p := File(fixturePNG)
	if err := p.Err(); err != nil {
		t.Fatalf("File: %v", err)
	}
	if p.Type != PartImage || p.MimeType != "image/png" {
		t.Fatalf("got %s/%s, want image/image/png", p.Type, p.MimeType)
	}
	if p.URL != "" {
		t.Fatalf("a path must never survive as a url: %q", p.URL)
	}
	if got, want := p.Data, goldenBase64(t); got != want {
		t.Fatalf("base64 mismatch\n got %q\nwant %q", got, want)
	}
	if p.Bytes() != 82 {
		t.Fatalf("decoded bytes = %d, want 82", p.Bytes())
	}
}

func TestPartWithBothDataAndURLIsRejected(t *testing.T) {
	both := ContentPart{Type: PartImage, MimeType: "image/png", Data: "aGk=", URL: "https://x/y.png"}
	err := both.Validate(0)
	pe, ok := err.(*PartError)
	if !ok || pe.Kind != "both" {
		t.Fatalf("want a typed 'both' PartError, got %v", err)
	}
	neither := ContentPart{Type: PartImage, MimeType: "image/png"}
	if pe, ok := neither.Validate(0).(*PartError); !ok || pe.Kind != "neither" {
		t.Fatalf("want a typed 'neither' PartError, got %v", neither.Validate(0))
	}
}

func TestDataURLIsNormalisedAtConstruction(t *testing.T) {
	b64 := goldenBase64(t)
	p := URLPart("data:image/png;base64,"+b64, "")
	if err := p.Err(); err != nil {
		t.Fatalf("URLPart: %v", err)
	}
	if p.URL != "" || p.Data != b64 || p.MimeType != "image/png" {
		t.Fatalf("data: URL not normalised: %+v", p)
	}
	https := URLPart("https://example.com/a.png", "image/png")
	if https.URL == "" || https.Data != "" {
		t.Fatalf("https URL must be retained as url: %+v", https)
	}
}

func TestUnknownExtensionIsRefusedByName(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "thing.heic")
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	p := File(path)
	pe, ok := p.Err().(*PartError)
	if !ok || pe.Kind != "extension" {
		t.Fatalf("want a typed 'extension' error, got %v", p.Err())
	}
	if !strings.Contains(pe.Error(), "heic") {
		t.Fatalf("the error must name the extension: %v", pe)
	}
}

func TestOversizedPartIsRejectedAtTheEdge(t *testing.T) {
	old := DefaultMaxPartBytes
	DefaultMaxPartBytes = 8
	defer func() { DefaultMaxPartBytes = old }()
	p := Bytes(make([]byte, 32), "image/png")
	pe, ok := p.Err().(*PartError)
	if !ok || pe.Kind != "size" {
		t.Fatalf("want a typed 'size' error, got %v", p.Err())
	}
	if !strings.Contains(pe.Error(), "32") || !strings.Contains(pe.Error(), "8") {
		t.Fatalf("the error must name the limit and the actual size: %v", pe)
	}
}

func TestMaxPartBytesOnTheClient(t *testing.T) {
	var bodies []map[string]any
	llm := capturePartsLLM(StyleOpenAI, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", MaxPartBytes: 8})
	_, err := c.RunParts(context.Background(), []ContentPart{Bytes(make([]byte, 64), "image/png")}, tk)
	if pe, ok := err.(*PartError); !ok || pe.Kind != "size" {
		t.Fatalf("want a typed 'size' error, got %v", err)
	}
	if len(bodies) != 0 {
		t.Fatalf("an oversized part must fail before any HTTP call")
	}
}

// A part's bytes are never logged: String() renders {type, mimeType, bytes}, and
// a part is not free to a compactor.
func TestPartRendersWithoutItsBytes(t *testing.T) {
	p := File(fixturePNG)
	s := fmt.Sprintf("%v", p)
	if strings.Contains(s, p.Data) {
		t.Fatalf("rendered part leaked its base64: %s", s)
	}
	if !strings.Contains(s, "image/png") || !strings.Contains(s, "bytes:82") {
		t.Fatalf("rendered part must name type, mimeType and bytes: %s", s)
	}
	big := ContentPart{Type: PartImage, MimeType: "image/png", Data: base64.StdEncoding.EncodeToString(make([]byte, 2<<20))}
	if got := big.EstimatedTokens(); got < 2000 {
		t.Fatalf("a 2 MB image estimated at %d tokens — the compactor could never evict it", got)
	}
}

// §1B pin: EstimatedTokens is EXACTLY max(85, floor(decodedBytes/750)), not
// floored at 1. A port that regresses the floor back to 1 must fail this.
func TestEstimatedTokensFloorIs85(t *testing.T) {
	tiny := File(fixturePNG) // 82 decoded bytes
	if got := tiny.EstimatedTokens(); got != 85 {
		t.Fatalf("an 82-byte part estimated at %d tokens, want the 85 floor", got)
	}
	large := ContentPart{Type: PartImage, MimeType: "image/png", Data: base64.StdEncoding.EncodeToString(make([]byte, 750000))}
	if got := large.EstimatedTokens(); got != 1000 {
		t.Fatalf("a 750000-byte part estimated at %d tokens, want 1000 (750000/750)", got)
	}
}

// §8A pin: the three user-visible strings are byte-identical across all seven
// ports. Assert the FULL literal form, not a substring — a substring check is
// exactly how the old "part (mimeType)" / floor-of-1 wording drifted unnoticed.
func TestDescribePartIsByteIdentical(t *testing.T) {
	p := File(fixturePNG) // image/png, 82 decoded bytes
	if got := describePart(p); got != "image (image/png, 82 bytes)" {
		t.Fatalf("describePart = %q, want %q", got, "image (image/png, 82 bytes)")
	}
}

func TestUnsupportedPlaceholderIsByteIdentical(t *testing.T) {
	p := ContentPart{Type: PartAudio, MimeType: "audio/wav", Data: base64.StdEncoding.EncodeToString(make([]byte, 41984))}
	block := unsupportedPlaceholder(p)
	want := "[unsupported audio part (audio/wav, 41984 bytes)]"
	if got, _ := block["text"].(string); got != want {
		t.Fatalf("unsupportedPlaceholder text = %q, want %q", got, want)
	}
}

// A part carrying a url instead of data renders <bytes> as 0 in both strings —
// not empty, not a panic.
func TestDescribePartAndPlaceholderRenderZeroBytesForAUrlPart(t *testing.T) {
	p := ContentPart{Type: PartImage, MimeType: "image/png", URL: "https://example.com/x.png"}
	if got := describePart(p); got != "image (image/png, 0 bytes)" {
		t.Fatalf("describePart of a url part = %q, want %q", got, "image (image/png, 0 bytes)")
	}
	block := unsupportedPlaceholder(p)
	want := "[unsupported image part (image/png, 0 bytes)]"
	if got, _ := block["text"].(string); got != want {
		t.Fatalf("unsupportedPlaceholder of a url part = %q, want %q", got, want)
	}
}

// ---------------------------------------------------------------------------
// §8 — the loop accepts parts, and the string path does not move
// ---------------------------------------------------------------------------

// Regression pin: a string prompt still assembles {role:"user", content:<string>}.
func TestStringPromptIsByteIdentical(t *testing.T) {
	var bodies []map[string]any
	llm := capturePartsLLM(StyleOpenAI, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	if _, err := c.Run(context.Background(), "hello", tk); err != nil {
		t.Fatal(err)
	}
	got := mustJSON(msgs(t, bodies[0]))
	if want := `[{"content":"hello","role":"user"}]`; got != want {
		t.Fatalf("string path moved:\n got %s\nwant %s", got, want)
	}
}

func TestRunPartsPreservesOrdering(t *testing.T) {
	var bodies []map[string]any
	llm := capturePartsLLM(StyleOpenAI, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	_, err := c.RunParts(context.Background(), []ContentPart{Text("before"), File(fixturePNG), Text("after")}, tk)
	if err != nil {
		t.Fatal(err)
	}
	content, ok := objOf(t, msgs(t, bodies[0])[0])["content"].([]any)
	if !ok || len(content) != 3 {
		t.Fatalf("want 3 blocks, got %#v", objOf(t, msgs(t, bodies[0])[0])["content"])
	}
	if objOf(t, content[0])["text"] != "before" || objOf(t, content[2])["text"] != "after" {
		t.Fatalf("ordering lost: %s", mustJSON(content))
	}
	img := objOf(t, content[1])
	if img["type"] != "image_url" {
		t.Fatalf("want an image_url block, got %s", mustJSON(img))
	}
	url := objOf(t, img["image_url"])["url"].(string)
	if url != "data:image/png;base64,"+goldenBase64(t) {
		t.Fatalf("image_url must be a data: URL of the golden bytes, got %q", url)
	}
}

// A deferred construction error surfaces on the first bad part, before any HTTP.
func TestRunPartsSurfacesTheDeferredError(t *testing.T) {
	var bodies []map[string]any
	llm := capturePartsLLM(StyleOpenAI, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	_, err := c.RunParts(context.Background(), []ContentPart{Text("hi"), File("/no/such/file.png")}, tk)
	if _, ok := err.(*PartError); !ok {
		t.Fatalf("want a typed PartError, got %v", err)
	}
	if len(bodies) != 0 {
		t.Fatalf("a bad part must fail before any HTTP call")
	}
}

func TestAnthropicUserPartsBecomeNativeBlocks(t *testing.T) {
	var bodies []map[string]any
	llm := capturePartsLLM(StyleAnthropic, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleAnthropic, Model: "m", APIKey: "k"})
	if _, err := c.RunParts(context.Background(), []ContentPart{Text("look"), File(fixturePNG)}, tk); err != nil {
		t.Fatal(err)
	}
	content := objOf(t, msgs(t, bodies[0])[0])["content"].([]any)
	src := objOf(t, objOf(t, content[1])["source"])
	if objOf(t, content[1])["type"] != "image" || src["type"] != "base64" || src["media_type"] != "image/png" {
		t.Fatalf("want a native anthropic image block, got %s", mustJSON(content[1]))
	}
	if src["data"] != goldenBase64(t) {
		t.Fatalf("anthropic block carries the wrong bytes")
	}
}

// ---------------------------------------------------------------------------
// §8A — allowlist and the provenance rule
// ---------------------------------------------------------------------------

func TestAnthropicAudioIsANamedRefusal(t *testing.T) {
	if _, err := encodePart(StyleAnthropic, ContentPart{Type: PartAudio, MimeType: "audio/mpeg", Data: "aGk="}); err == nil {
		t.Fatal("anthropic must refuse audio")
	} else if ue, ok := err.(*UnsupportedPartError); !ok || ue.PartType != PartAudio || ue.Style != StyleAnthropic {
		t.Fatalf("want a typed UnsupportedPartError naming the part and the style, got %v", err)
	}
	// Every encoded block that IS produced must be in the style's allowlist.
	for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
		for _, p := range []ContentPart{
			Text("t"),
			{Type: PartImage, MimeType: "image/png", Data: "aGk="},
			{Type: PartImage, MimeType: "image/png", URL: "https://x/y.png"},
			{Type: PartFile, MimeType: "application/pdf", Data: "aGk="},
			{Type: PartAudio, MimeType: "audio/wav", Data: "aGk="},
		} {
			block, err := encodePart(style, p)
			if err != nil {
				continue // a named refusal
			}
			if !allowedBlocks[style][block["type"].(string)] {
				t.Fatalf("%s/%s produced a block outside the allowlist: %s", style, p.Type, mustJSON(block))
			}
		}
	}
}

// An ATTACHED unsupported part is a typed error before any HTTP call.
func TestAttachedUnsupportedPartErrors(t *testing.T) {
	var bodies []map[string]any
	llm := capturePartsLLM(StyleAnthropic, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleAnthropic, Model: "m", APIKey: "k"})
	_, err := c.RunParts(context.Background(), []ContentPart{{Type: PartAudio, MimeType: "audio/mpeg", Data: "aGk="}}, tk)
	if ue, ok := err.(*UnsupportedPartError); !ok || ue.Style != StyleAnthropic {
		t.Fatalf("want a typed UnsupportedPartError, got %v", err)
	}
	if len(bodies) != 0 {
		t.Fatalf("no HTTP call may be made for an unrepresentable attached part")
	}
}

// A DERIVED unsupported part degrades to a placeholder instead of failing the run.
func TestDerivedUnsupportedPartDegrades(t *testing.T) {
	e := partEmitter{style: StyleAnthropic, warned: new(bool)}
	blocks, err := e.blocks([]ContentPart{{Type: PartAudio, MimeType: "audio/mpeg", Data: "aGk="}}, false)
	if err != nil {
		t.Fatalf("a derived part must not fail the run: %v", err)
	}
	text := objOf(t, blocks[0])["text"].(string)
	if !strings.Contains(text, "audio") || !strings.Contains(text, "audio/mpeg") {
		t.Fatalf("the placeholder must name the type and mime type: %q", text)
	}
	if strings.Contains(text, "aGk=") {
		t.Fatalf("the placeholder leaked the part's bytes")
	}
	// The override forces uniform strictness.
	strict := partEmitter{style: StyleAnthropic, mode: UnsupportedPartAsError, warned: new(bool)}
	if _, err := strict.blocks([]ContentPart{{Type: PartAudio, MimeType: "audio/mpeg", Data: "aGk="}}, false); err == nil {
		t.Fatal("onUnsupportedPart:error must fail a derived part too")
	}
	// ...and the other way round.
	lenient := partEmitter{style: StyleAnthropic, mode: UnsupportedPartAsText, warned: new(bool)}
	if _, err := lenient.blocks([]ContentPart{{Type: PartAudio, MimeType: "audio/mpeg", Data: "aGk="}}, true); err != nil {
		t.Fatalf("onUnsupportedPart:text must degrade an attached part too: %v", err)
	}
}

// §1B pin: maxPartBytes is enforced at request ASSEMBLY, over every part
// regardless of provenance — not only in the edge constructors, so an
// MCP-derived oversize part cannot walk past the limit. An ATTACHED oversize
// part is a typed error, the same as TestMaxPartBytesOnTheClient's edge check,
// but exercised here at the emitter that a bypassed-constructor part reaches.
func TestOversizeAttachedPartErrorsAtAssembly(t *testing.T) {
	e := partEmitter{style: StyleOpenAI, maxBytes: 8, warned: new(bool)}
	_, err := e.blocks([]ContentPart{Bytes(make([]byte, 64), "image/png")}, true)
	pe, ok := err.(*PartError)
	if !ok || pe.Kind != "size" {
		t.Fatalf("want a typed 'size' PartError, got %v", err)
	}
	if !strings.Contains(pe.Error(), "64") || !strings.Contains(pe.Error(), "8") {
		t.Fatalf("the error must name the limit and the actual size: %v", pe)
	}
}

// A DERIVED (tool/MCP) oversize part degrades to the canonical placeholder
// instead of failing the run — same provenance rule as an unsupported part.
func TestOversizeDerivedPartDegrades(t *testing.T) {
	e := partEmitter{style: StyleOpenAI, maxBytes: 8, warned: new(bool)}
	blocks, err := e.blocks([]ContentPart{Bytes(make([]byte, 64), "image/png")}, false)
	if err != nil {
		t.Fatalf("an oversize derived part must not fail the run: %v", err)
	}
	text, _ := objOf(t, blocks[0])["text"].(string)
	want := "[unsupported image part (image/png, 64 bytes)]"
	if text != want {
		t.Fatalf("degraded block text = %q, want %q", text, want)
	}
}

// ---------------------------------------------------------------------------
// §8A — the tool-result relocation rule
// ---------------------------------------------------------------------------

// imageTool returns describing text plus one image part.
func imageTool(name string, t *testing.T) Tool {
	return Tool{
		Name: name, Description: "shot", Source: SourceCustom,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			return ToolResult{Output: "screenshot, 8x8 png", Parts: []ContentPart{File(fixturePNG)}}, nil
		},
	}
}

// toolThenAnswerLLM: turn 1 calls every named tool, turn 2 answers.
func toolThenAnswerLLM(style ClientStyle, names []string, bodies *[]map[string]any) *httptest.Server {
	turn := 0
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		*bodies = append(*bodies, body)
		turn++
		w.Header().Set("content-type", "application/json")
		if style == StyleAnthropic {
			if turn == 1 {
				var blocks []any
				for i, n := range names {
					blocks = append(blocks, map[string]any{"type": "tool_use", "id": fmt.Sprintf("c%d", i+1), "name": n, "input": map[string]any{}})
				}
				_, _ = fmt.Fprint(w, mustJSON(map[string]any{"content": blocks, "usage": map[string]any{}}))
				return
			}
			_, _ = fmt.Fprint(w, mustJSON(map[string]any{"content": []any{map[string]any{"type": "text", "text": "done"}}, "usage": map[string]any{}}))
			return
		}
		if turn == 1 {
			var calls []any
			for i, n := range names {
				calls = append(calls, map[string]any{"id": fmt.Sprintf("c%d", i+1), "type": "function",
					"function": map[string]any{"name": n, "arguments": "{}"}})
			}
			_, _ = fmt.Fprint(w, mustJSON(map[string]any{"choices": []any{map[string]any{
				"message": map[string]any{"role": "assistant", "content": "", "tool_calls": calls}}}}))
			return
		}
		_, _ = fmt.Fprint(w, mustJSON(map[string]any{"choices": []any{map[string]any{
			"message": map[string]any{"role": "assistant", "content": "done"}}}}))
	}))
}

func TestOpenAIRelocatesToolPartsIntoOneSyntheticUserMessage(t *testing.T) {
	var bodies []map[string]any
	llm := toolThenAnswerLLM(StyleOpenAI, []string{"shot_a", "shot_b"}, &bodies)
	defer llm.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false,
		ExtraTools: []Tool{imageTool("shot_a", t), imageTool("shot_b", t)}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatal(err)
	}
	sent := msgs(t, bodies[1]) // user, assistant, tool, tool, synthetic user
	if len(sent) != 5 {
		t.Fatalf("want 5 wire messages, got %d: %s", len(sent), mustJSON(sent))
	}
	for _, i := range []int{2, 3} {
		m := objOf(t, sent[i])
		if m["role"] != "tool" || m["content"] != "screenshot, 8x8 png" {
			t.Fatalf("tool message %d must carry only its output text: %s", i, mustJSON(m))
		}
		if _, leaked := m["parts"]; leaked {
			t.Fatalf("the canonical parts key must be stripped at emission: %s", mustJSON(m))
		}
		if _, leaked := m["name"]; leaked {
			t.Fatalf("the relocation label carrier must be stripped: %s", mustJSON(m))
		}
	}
	synth := objOf(t, sent[4])
	if synth["role"] != "user" {
		t.Fatalf("want one synthetic user message, got %s", mustJSON(synth))
	}
	blocks := synth["content"].([]any)
	if len(blocks) != 4 {
		t.Fatalf("want label+image per tool, got %s", mustJSON(blocks))
	}
	if got := objOf(t, blocks[0])["text"]; got != "Output of tool shot_a (c1):" {
		t.Fatalf("label 1 = %q", got)
	}
	if got := objOf(t, blocks[2])["text"]; got != "Output of tool shot_b (c2):" {
		t.Fatalf("label 2 = %q", got)
	}
	if objOf(t, blocks[1])["type"] != "image_url" || objOf(t, blocks[3])["type"] != "image_url" {
		t.Fatalf("both images must be relocated in tool-call order: %s", mustJSON(blocks))
	}
	// The synthetic message is an adapter artifact — never in the transcript.
	for _, m := range res.Messages {
		mm := objOf(t, m)
		if mm["role"] == "user" {
			if _, isArray := mm["content"].([]any); isArray {
				t.Fatalf("a synthetic user message leaked into the canonical transcript: %s", mustJSON(mm))
			}
		}
	}
	if got := mustJSON(res.Messages); strings.Contains(got, "Output of tool") {
		t.Fatalf("the relocation label leaked into the transcript: %s", got)
	}
}

// §1B / §8A end-to-end: two tool-derived parts both over MaxPartBytes degrade
// to the canonical placeholder and the run completes — a remote server still
// cannot fail the caller's run — and the warn-once latch fires exactly once
// across both violations, not once per part.
func TestOversizeMcpDerivedPartsDegradeAndRunCompletes(t *testing.T) {
	var logBuf bytes.Buffer
	prevOut := log.Writer()
	log.SetOutput(&logBuf)
	defer log.SetOutput(prevOut)

	var bodies []map[string]any
	llm := toolThenAnswerLLM(StyleOpenAI, []string{"shot_a", "shot_b"}, &bodies)
	defer llm.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false,
		ExtraTools: []Tool{imageTool("shot_a", t), imageTool("shot_b", t)}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	// fixturePNG decodes to 82 bytes; a 8-byte cap makes both tool results oversize.
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", MaxPartBytes: 8})
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("an oversize tool-derived part must not fail the run: %v", err)
	}
	if res.Text != "done" {
		t.Fatalf("the run must complete: %+v", res)
	}
	synth := objOf(t, msgs(t, bodies[1])[4])
	blocks := synth["content"].([]any)
	want := "[unsupported image part (image/png, 82 bytes)]"
	for _, i := range []int{1, 3} {
		text, _ := objOf(t, blocks[i])["text"].(string)
		if text != want {
			t.Fatalf("relocated block %d = %q, want the degraded placeholder %q", i, text, want)
		}
	}
	if n := strings.Count(logBuf.String(), "[toolnexus]"); n != 1 {
		t.Fatalf("the warn-once latch must fire exactly once across both violations, fired %d time(s): %s", n, logBuf.String())
	}
}

func TestAnthropicEmitsToolPartsNatively(t *testing.T) {
	var bodies []map[string]any
	llm := toolThenAnswerLLM(StyleAnthropic, []string{"shot_a"}, &bodies)
	defer llm.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{imageTool("shot_a", t)}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleAnthropic, Model: "m", APIKey: "k"})
	if _, err := c.Run(context.Background(), "go", tk); err != nil {
		t.Fatal(err)
	}
	sent := msgs(t, bodies[1]) // user, assistant, user(tool_result)
	if len(sent) != 3 {
		t.Fatalf("anthropic must not add a synthetic message: %s", mustJSON(sent))
	}
	block := objOf(t, objOf(t, sent[2])["content"].([]any)[0])
	if block["type"] != "tool_result" || block["tool_use_id"] != "c1" {
		t.Fatalf("want a tool_result keyed to its tool_use_id: %s", mustJSON(block))
	}
	inner, ok := block["content"].([]any)
	if !ok || len(inner) != 2 {
		t.Fatalf("want text + image inside tool_result.content: %s", mustJSON(block))
	}
	if objOf(t, inner[0])["text"] != "screenshot, 8x8 png" || objOf(t, inner[1])["type"] != "image" {
		t.Fatalf("wrong tool_result.content: %s", mustJSON(inner))
	}
	if _, leaked := block["parts"]; leaked {
		t.Fatalf("the canonical parts key must be stripped: %s", mustJSON(block))
	}
}

// Regression pin: a text-only tool result still writes the exact same tool message.
func TestTextOnlyToolResultIsByteIdentical(t *testing.T) {
	var bodies []map[string]any
	llm := toolThenAnswerLLM(StyleOpenAI, []string{"echo"}, &bodies)
	defer llm.Close()
	echo := Tool{Name: "echo", Description: "e", Source: SourceCustom,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			return ToolResult{Output: "hi"}, nil
		}}
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{echo}})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	if _, err := c.Run(context.Background(), "go", tk); err != nil {
		t.Fatal(err)
	}
	sent := msgs(t, bodies[1])
	if got, want := mustJSON(sent[2]), `{"content":"hi","role":"tool","tool_call_id":"c1"}`; got != want {
		t.Fatalf("text-only tool message moved:\n got %s\nwant %s", got, want)
	}
}

// ---------------------------------------------------------------------------
// §2 / §0.4 — MCP result mapping, on every branch
// ---------------------------------------------------------------------------

func TestMcpContentPartsMapsEveryType(t *testing.T) {
	content := []mcp.Content{
		mcp.NewTextContent("hello"),
		mcp.NewImageContent("aW1n", "image/png"),
		mcp.NewAudioContent("YXVk", "audio/wav"),
		mcp.NewResourceLink("https://example.com/r.pdf", "r.pdf", "d", "application/pdf"),
		mcp.NewEmbeddedResource(mcp.BlobResourceContents{URI: "file:///b.bin", MIMEType: "application/octet-stream", Blob: "Ymxi"}),
		mcp.NewEmbeddedResource(mcp.TextResourceContents{URI: "file:///t.txt", MIMEType: "text/plain", Text: "trailing"}),
	}
	parts := mcpContentParts(content)
	if len(parts) != 4 {
		t.Fatalf("want 4 non-text parts, got %d: %v", len(parts), parts)
	}
	if parts[0].Type != PartImage || parts[0].Data != "aW1n" || parts[0].MimeType != "image/png" {
		t.Fatalf("image part wrong: %+v", parts[0])
	}
	if parts[1].Type != PartAudio || parts[1].Data != "YXVk" {
		t.Fatalf("audio part wrong: %+v", parts[1])
	}
	if parts[2].Type != PartFile || parts[2].URL != "https://example.com/r.pdf" || parts[2].Data != "" {
		t.Fatalf("resource_link must become file{url}: %+v", parts[2])
	}
	if parts[3].Type != PartFile || parts[3].Data != "Ymxi" || parts[3].URL != "" {
		t.Fatalf("blob resource must become file{data}: %+v", parts[3])
	}
	// A resource carrying TEXT joins output rather than becoming a part.
	if got, want := joinTextContent(content), "hello\ntrailing"; got != want {
		t.Fatalf("output = %q, want %q", got, want)
	}
}

// Regression pin: text-only MCP content joins exactly as before.
func TestTextOnlyMcpContentIsByteIdentical(t *testing.T) {
	content := []mcp.Content{mcp.NewTextContent("a"), mcp.NewTextContent("b")}
	if got := joinTextContent(content); got != "a\nb" {
		t.Fatalf("joinTextContent = %q, want %q", got, "a\nb")
	}
	if parts := mcpContentParts(content); parts != nil {
		t.Fatalf("a text-only result must carry no parts, got %v", parts)
	}
}

func TestImageOnlyMcpResultIsNotAnEmptyString(t *testing.T) {
	parts := mcpContentParts([]mcp.Content{mcp.NewImageContent("aW1n", "image/png")})
	if got := describeParts(parts); got == "" || !strings.Contains(got, "image/png") {
		t.Fatalf("an image-only result must name its part, got %q", got)
	}
}

// §8A pin: no-text-content MCP output is the describePart form, one line per
// part, joined with "\n" — byte-identical, not a substring check.
func TestMultiPartMcpResultJoinsOneLinePerPart(t *testing.T) {
	parts := mcpContentParts([]mcp.Content{
		mcp.NewImageContent("aW1n", "image/png"),     // decodes to 3 bytes
		mcp.NewAudioContent("aGVsbG8=", "audio/wav"), // decodes to 5 bytes
	})
	want := "image (image/png, 3 bytes)\naudio (audio/wav, 5 bytes)"
	if got := describeParts(parts); got != want {
		t.Fatalf("describeParts = %q, want %q", got, want)
	}
}

// ---------------------------------------------------------------------------
// §11 — inbound translation
// ---------------------------------------------------------------------------

func TestTranslateConcatenatesTextParts(t *testing.T) {
	got := translateContentArray([]any{
		map[string]any{"type": "text", "text": "a"},
		map[string]any{"type": "text", "text": "b"},
	})
	if got != "ab" {
		t.Fatalf("an all-text array must concatenate, got %#v", got)
	}
}

func TestTranslateKeepsAnImagePart(t *testing.T) {
	b64 := goldenBase64(t)
	out, system := openAIMessagesToAnthropic([]any{map[string]any{
		"role": "user",
		"content": []any{
			map[string]any{"type": "text", "text": "what is this"},
			map[string]any{"type": "image_url", "image_url": map[string]any{"url": "data:image/png;base64," + b64}},
		},
	}})
	if system != "" || len(out) != 1 {
		t.Fatalf("unexpected translation: %s", mustJSON(out))
	}
	blocks := objOf(t, out[0])["content"].([]any)
	if len(blocks) != 2 || objOf(t, blocks[0])["text"] != "what is this" {
		t.Fatalf("text must survive first, got %s", mustJSON(blocks))
	}
	img := objOf(t, blocks[1])
	src := objOf(t, img["source"])
	if img["type"] != "image" || src["media_type"] != "image/png" || src["data"] != b64 {
		t.Fatalf("the image must become a native anthropic block, got %s", mustJSON(img))
	}
}

// ---------------------------------------------------------------------------
// §6 — the read builtin's media table
// ---------------------------------------------------------------------------

func readBuiltin(t *testing.T) Tool {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tk.Close)
	for _, tool := range tk.Tools() {
		if tool.Name == "read" {
			return tool
		}
	}
	t.Fatal("no read builtin")
	return Tool{}
}

func TestReadReturnsAnImagePart(t *testing.T) {
	res, err := readBuiltin(t).Execute(map[string]any{"path": fixturePNG}, nil)
	if err != nil || res.IsError {
		t.Fatalf("read: %v / %s", err, res.Output)
	}
	if len(res.Parts) != 1 || res.Parts[0].Type != PartImage || res.Parts[0].MimeType != "image/png" {
		t.Fatalf("want one image part, got %+v", res.Parts)
	}
	if res.Parts[0].Data != goldenBase64(t) {
		t.Fatalf("read encoded the fixture differently from the committed golden")
	}
	if !strings.Contains(res.Output, "image/png") || !strings.Contains(res.Output, fixturePNG) {
		t.Fatalf("output must name the file and its mime type: %q", res.Output)
	}
	if strings.Contains(res.Output, res.Parts[0].Data) {
		t.Fatalf("output leaked the base64")
	}
}

// §8A pin: the read tool's media output is byte-identical to
// "<path> (<mimeType>, <bytes> bytes)" — not a substring check.
func TestReadMediaOutputIsByteIdentical(t *testing.T) {
	res, err := readBuiltin(t).Execute(map[string]any{"path": fixturePNG}, nil)
	if err != nil || res.IsError {
		t.Fatalf("read: %v / %s", err, res.Output)
	}
	want := fixturePNG + " (image/png, 82 bytes)"
	if res.Output != want {
		t.Fatalf("read output = %q, want %q", res.Output, want)
	}
}

func TestReadTextFileIsUnchanged(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "a.md")
	if err := os.WriteFile(path, []byte("l1\nl2\nl3\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	res, err := readBuiltin(t).Execute(map[string]any{"path": path, "offset": 2.0, "limit": 1.0}, nil)
	if err != nil || res.IsError {
		t.Fatalf("read: %v / %s", err, res.Output)
	}
	if res.Output != "l2" || res.Parts != nil {
		t.Fatalf("text windowing moved: %q / %v", res.Output, res.Parts)
	}
}

func TestReadUndecodableBinaryIsAnErrorResult(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "x.bin")
	if err := os.WriteFile(path, []byte{0xff, 0xfe, 0x00, 0x80}, 0o600); err != nil {
		t.Fatal(err)
	}
	res, err := readBuiltin(t).Execute(map[string]any{"path": path}, nil)
	if err != nil {
		t.Fatalf("no error may escape execute into the loop: %v", err)
	}
	if !res.IsError || !strings.Contains(res.Output, path) {
		t.Fatalf("want an isError result naming the file, got %+v", res)
	}
}

// ---------------------------------------------------------------------------
// §7B/§2 inbound — a served tool's parts become MCP content blocks
// ---------------------------------------------------------------------------

func TestServedPartsBecomeMcpContent(t *testing.T) {
	content := partsToMcpContent([]ContentPart{
		{Type: PartImage, MimeType: "image/png", Data: "aW1n"},
		{Type: PartAudio, MimeType: "audio/wav", Data: "YXVk"},
		{Type: PartFile, MimeType: "application/pdf", Data: "cGRm", Name: "r.pdf"},
		{Type: PartFile, MimeType: "application/pdf", URL: "https://example.com/r.pdf", Name: "r.pdf"},
	})
	if len(content) != 4 {
		t.Fatalf("want 4 blocks, got %d", len(content))
	}
	if ic, ok := mcp.AsImageContent(content[0]); !ok || ic.Data != "aW1n" {
		t.Fatalf("want an image block, got %#v", content[0])
	}
	if ac, ok := mcp.AsAudioContent(content[1]); !ok || ac.Data != "YXVk" {
		t.Fatalf("want an audio block, got %#v", content[1])
	}
	er, ok := mcp.AsEmbeddedResource(content[2])
	if !ok {
		t.Fatalf("want an embedded resource, got %#v", content[2])
	}
	if br, ok := mcp.AsBlobResourceContents(er.Resource); !ok || br.Blob != "cGRm" {
		t.Fatalf("blob resource wrong: %#v", er.Resource)
	}
	if rl, ok := content[3].(mcp.ResourceLink); !ok || rl.URI != "https://example.com/r.pdf" {
		t.Fatalf("a url-backed file must become a resource link, got %#v", content[3])
	}
}

// A ToolResult carrying both parts and metadata.pending still suspends per §10.
func TestPartsDoNotCollideWithSuspension(t *testing.T) {
	req := Request{ID: "r1", Kind: "input", Prompt: "who?"}
	res := ToolResult{Output: "pending", Parts: []ContentPart{Text("x")},
		Metadata: map[string]any{"pending": req}}
	if PendingOf(res) == nil {
		t.Fatal("parts must not shadow metadata.pending")
	}
}

// The streaming paths carry parts too — the emission seam is shared, and this
// pins that the streaming tool-result site attaches them.
func TestStreamingOpenAIRelocatesToolParts(t *testing.T) {
	var bodies []map[string]any
	turn := 0
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		bodies = append(bodies, body)
		turn++
		w.Header().Set("content-type", "text/event-stream")
		if turn == 1 {
			_, _ = fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{"choices": []any{map[string]any{
				"delta": map[string]any{"tool_calls": []any{map[string]any{"index": 0, "id": "c1",
					"function": map[string]any{"name": "shot_a", "arguments": "{}"}}}}}}}))
		} else {
			_, _ = fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{"choices": []any{map[string]any{
				"delta": map[string]any{"content": "done"}}}}))
		}
		_, _ = fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	defer llm.Close()

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{imageTool("shot_a", t)}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	ch, err := c.StreamWithID(context.Background(), "go", tk, "")
	if err != nil {
		t.Fatal(err)
	}
	drainStream(t, ch)
	if len(bodies) < 2 {
		t.Fatalf("want two turns, got %d", len(bodies))
	}
	sent := msgs(t, bodies[1])
	last := objOf(t, sent[len(sent)-1])
	if last["role"] != "user" {
		t.Fatalf("streaming must relocate into a synthetic user message: %s", mustJSON(sent))
	}
	blocks := last["content"].([]any)
	if objOf(t, blocks[0])["text"] != "Output of tool shot_a (c1):" || objOf(t, blocks[1])["type"] != "image_url" {
		t.Fatalf("wrong relocated blocks: %s", mustJSON(blocks))
	}
}

// ---------------------------------------------------------------------------
// §1B — "a port accepts the file and byte objects its users already hold":
// io.Reader, fs.File / *os.File, and an fs.FS + name. Every one of them must
// land on the SAME committed golden as File(path), and store bytes only.
// ---------------------------------------------------------------------------

// errReader fails partway through, to prove a read error rides the deferred
// error field rather than a second return value.
type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, fmt.Errorf("disk went away") }

func TestReaderEncodesToCommittedGolden(t *testing.T) {
	raw, err := os.ReadFile(fixturePNG)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	p := Reader(bytes.NewReader(raw), "image/png")
	if err := p.Err(); err != nil {
		t.Fatalf("Reader: %v", err)
	}
	if p.Type != PartImage || p.MimeType != "image/png" {
		t.Fatalf("got %s/%s, want image/image/png", p.Type, p.MimeType)
	}
	if p.Data != goldenBase64(t) {
		t.Fatalf("base64 mismatch\n got %q\nwant %q", p.Data, goldenBase64(t))
	}
}

func TestReaderNeedsAnExplicitMimeType(t *testing.T) {
	p := Reader(bytes.NewReader([]byte("hi")), "")
	pe, ok := p.Err().(*PartError)
	if !ok || pe.Kind != "mime" {
		t.Fatalf("want a typed 'mime' PartError, got %v", p.Err())
	}
}

// A failing reader must not produce a half-built part: the error is deferred and
// RunParts surfaces it before any HTTP call, exactly like File's.
func TestReaderReadErrorIsDeferredAndSurfacedByRunParts(t *testing.T) {
	p := Reader(errReader{}, "image/png")
	pe, ok := p.Err().(*PartError)
	if !ok || pe.Kind != "read" {
		t.Fatalf("want a typed 'read' PartError, got %v", p.Err())
	}
	if p.Data != "" || p.MimeType != "" {
		t.Fatalf("a failed read must not build a part: %+v", p)
	}

	var bodies []map[string]any
	llm := capturePartsLLM(StyleOpenAI, &bodies)
	defer llm.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	_, err := c.RunParts(context.Background(), []ContentPart{Text("hi"), p}, tk)
	if _, ok := err.(*PartError); !ok {
		t.Fatalf("want a typed PartError from RunParts, got %v", err)
	}
	if len(bodies) != 0 {
		t.Fatalf("a bad part must fail before any HTTP call")
	}
}

// An *os.File is an fs.File: the mime comes from the file's own name, and the
// handle stays the caller's to close.
func TestFileHandleFromOSFileMatchesGoldenAndDoesNotClose(t *testing.T) {
	f, err := os.Open(fixturePNG)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer f.Close()

	p := FileHandle(f)
	if err := p.Err(); err != nil {
		t.Fatalf("FileHandle: %v", err)
	}
	if p.Type != PartImage || p.MimeType != "image/png" {
		t.Fatalf("got %s/%s, want image/image/png", p.Type, p.MimeType)
	}
	if p.Data != goldenBase64(t) {
		t.Fatalf("base64 mismatch\n got %q\nwant %q", p.Data, goldenBase64(t))
	}

	// The constructor must NOT have closed a file it did not open: a closed
	// *os.File fails Stat with os.ErrClosed, and rewinding still works.
	if _, err := f.Stat(); err != nil {
		t.Fatalf("the caller's file was closed by the constructor: %v", err)
	}
	if _, err := f.Seek(0, 0); err != nil {
		t.Fatalf("seek on the caller's file: %v", err)
	}
	again, err := io.ReadAll(f)
	if err != nil || len(again) != 82 {
		t.Fatalf("re-read of the caller's file: %d bytes, %v", len(again), err)
	}
}

func TestFileHandleRejectsAnUnknownExtension(t *testing.T) {
	fsys := fstest.MapFS{"notes.xyz": &fstest.MapFile{Data: []byte("hi")}}
	f, err := fsys.Open("notes.xyz")
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer f.Close()
	pe, ok := FileHandle(f).Err().(*PartError)
	if !ok || pe.Kind != "extension" {
		t.Fatalf("want a typed 'extension' PartError, got %v", FileHandle(f).Err())
	}
	if !strings.Contains(pe.Msg, "xyz") {
		t.Fatalf("the error must name the extension: %q", pe.Msg)
	}
}

// An fs.FS + name — the shape an embed.FS has, which is how a Go binary ships a
// fixture image inside itself. os.DirFS and fstest.MapFS stand in for embed.FS
// here (go:embed cannot reach outside the module directory).
func TestFSFileEncodesToCommittedGolden(t *testing.T) {
	raw, err := os.ReadFile(fixturePNG)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	cases := map[string]struct {
		fsys fs.FS
		name string
	}{
		"os.DirFS":       {os.DirFS(filepath.Dir(fixturePNG)), "fixture.png"},
		"fstest.MapFS":   {fstest.MapFS{"assets/fixture.png": &fstest.MapFile{Data: raw}}, "assets/fixture.png"},
		"embedded shape": {fstest.MapFS{"fixture.png": &fstest.MapFile{Data: raw}}, "fixture.png"},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			p := FSFile(tc.fsys, tc.name)
			if err := p.Err(); err != nil {
				t.Fatalf("FSFile: %v", err)
			}
			if p.Type != PartImage || p.MimeType != "image/png" {
				t.Fatalf("got %s/%s, want image/image/png", p.Type, p.MimeType)
			}
			if p.URL != "" {
				t.Fatalf("a name must never survive as a url: %q", p.URL)
			}
			if p.Data != goldenBase64(t) {
				t.Fatalf("base64 mismatch\n got %q\nwant %q", p.Data, goldenBase64(t))
			}
		})
	}
}

func TestFSFileMissingNameIsADeferredReadError(t *testing.T) {
	pe, ok := FSFile(fstest.MapFS{}, "gone.png").Err().(*PartError)
	if !ok || pe.Kind != "read" {
		t.Fatalf("want a typed 'read' PartError, got %v", FSFile(fstest.MapFS{}, "gone.png").Err())
	}
}

// A non-image source still gets its name, the same as File(path) does.
func TestFSFilePDFCarriesItsName(t *testing.T) {
	fsys := fstest.MapFS{"docs/report.pdf": &fstest.MapFile{Data: []byte("%PDF-1.4")}}
	p := FSFile(fsys, "docs/report.pdf")
	if err := p.Err(); err != nil {
		t.Fatalf("FSFile: %v", err)
	}
	if p.Type != PartFile || p.MimeType != "application/pdf" || p.Name != "report.pdf" {
		t.Fatalf("got %s/%s name=%q", p.Type, p.MimeType, p.Name)
	}
}

// MaxPartBytes stays a construction fast-fail on the new sources too.
func TestReaderFastFailsOverMaxPartBytes(t *testing.T) {
	old := DefaultMaxPartBytes
	DefaultMaxPartBytes = 8
	defer func() { DefaultMaxPartBytes = old }()

	pe, ok := Reader(bytes.NewReader(make([]byte, 64)), "image/png").Err().(*PartError)
	if !ok || pe.Kind != "size" {
		t.Fatalf("want a typed 'size' PartError, got %v", pe)
	}
	if p := Reader(bytes.NewReader(make([]byte, 4)), "image/png"); p.Err() != nil {
		t.Fatalf("an under-limit reader must build: %v", p.Err())
	}
}
