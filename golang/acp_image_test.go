package toolnexus

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// A 1x1 transparent PNG.
var acpTestPNG = []byte{
	0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
	0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
	0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
	0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae,
	0x42, 0x60, 0x82,
}

// acpImagePrompt runs one turn whose user message carries a text part and an
// image part (OpenAI chat wire shape, as the in-process seam receives it) and
// returns the session/prompt blocks the fake agent recorded.
func acpImagePrompt(t *testing.T, scenario string) []any {
	t.Helper()
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	return acpPromptBlocks(t, scenario, []any{
		map[string]any{"type": "text", "text": "what is in this picture?"},
		map[string]any{"type": "image_url", "image_url": map[string]any{"url": "data:image/png;base64," + b64}},
	})
}

// acpPromptBlocks runs one turn whose user content is the given OpenAI chat
// parts array and returns the session/prompt blocks the fake agent recorded.
func acpPromptBlocks(t *testing.T, scenario string, content []any) []any {
	t.Helper()
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, scenario, outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	req := InProcessRequest{Messages: []any{map[string]any{"role": "user", "content": content}}}
	if _, err := c.Generate(req); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	c.Close()
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "prompt_blocks" {
			blocks, _ := ev.Data["prompt"].([]any)
			return blocks
		}
	}
	t.Fatal("fake agent recorded no session/prompt")
	return nil
}

func acpSplitBlocks(blocks []any) (text string, images []map[string]any) {
	var b strings.Builder
	for _, raw := range blocks {
		blk, _ := raw.(map[string]any)
		switch blk["type"] {
		case "text":
			s, _ := blk["text"].(string)
			b.WriteString(s)
		case "image":
			images = append(images, blk)
		}
	}
	return b.String(), images
}

func TestACP_ImagePartSentAsImageBlock(t *testing.T) {
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	text, images := acpSplitBlocks(acpImagePrompt(t, "image"))
	if len(images) != 1 {
		t.Fatalf("expected 1 ACP image block, got %d (text=%q)", len(images), text)
	}
	if images[0]["mimeType"] != "image/png" || images[0]["data"] != b64 {
		t.Fatalf("image block mismatch: %v", images[0])
	}
	if strings.Contains(text, b64) {
		t.Fatalf("base64 leaked into a text block: %q", text)
	}
	if !strings.Contains(text, "what is in this picture?") {
		t.Fatalf("text part lost: %q", text)
	}
}

func TestACP_ImagePartPlaceholderWithoutCapability(t *testing.T) {
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	text, images := acpSplitBlocks(acpImagePrompt(t, "warm"))
	if len(images) != 0 {
		t.Fatalf("agent did not advertise image, yet got %d image blocks", len(images))
	}
	if strings.Contains(text, b64) {
		t.Fatalf("base64 leaked into a text block: %q", text)
	}
	want := "image (image/png, " + strconv.Itoa(len(acpTestPNG)) + " bytes)"
	if !strings.Contains(text, want) {
		t.Fatalf("expected placeholder %q in text, got %q", want, text)
	}
}

// acpHandleImageOutForTest: a tool_call, its completed tool_call_update whose
// content is one image ContentBlock, then a text chunk and an image chunk.
func acpHandleImageOutForTest(notify func(string, any), reply func(json.RawMessage, any), id json.RawMessage, sid string) {
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	img := map[string]any{"type": "image", "mimeType": "image/png", "data": b64}
	up := func(u map[string]any) { notify("session/update", map[string]any{"sessionId": sid, "update": u}) }
	up(map[string]any{"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "screenshot", "kind": "other", "status": "pending"})
	up(map[string]any{"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed",
		"content": []any{map[string]any{"type": "content", "content": img}}})
	up(map[string]any{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": "here"}})
	up(map[string]any{"sessionUpdate": "agent_message_chunk", "content": img})
	reply(id, map[string]any{"stopReason": "end_turn"})
}

func TestACP_InboundImageBlocksBecomeParts(t *testing.T) {
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	desc := "image (image/png, " + strconv.Itoa(len(acpTestPNG)) + " bytes)"
	c, err := LoadACP(context.Background(), acpTestOptions(t, "image_out", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	var results []StreamEvent
	resp, err := c.GenerateStream(context.Background(), InProcessRequest{Messages: []any{
		map[string]any{"role": "user", "content": "shoot"}}}, func(ev StreamEvent) {
		if ev.Type == "tool_result" {
			results = append(results, ev)
		}
	})
	if err != nil {
		t.Fatalf("GenerateStream: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 tool_result, got %d", len(results))
	}
	r := results[0]
	if r.Output != desc {
		t.Fatalf("tool_result Output = %q, want %q", r.Output, desc)
	}
	if len(r.Parts) != 1 || r.Parts[0].Type != PartImage || r.Parts[0].MimeType != "image/png" || r.Parts[0].Data != b64 {
		t.Fatalf("tool_result Parts = %+v", r.Parts)
	}
	if resp.Content != "here" {
		t.Fatalf("content = %q, want %q (base64 must never enter text)", resp.Content, "here")
	}
	if len(resp.Files) != 2 {
		t.Fatalf("expected 2 response files, got %+v", resp.Files)
	}
	if f := resp.Files[0]; f.From != "screenshot" || f.CallID != "t1" || f.Data != b64 || f.Type != PartImage {
		t.Fatalf("tool file = %+v", f)
	}
	if f := resp.Files[1]; f.From != "acp" || f.CallID != "" || f.Data != b64 || f.MimeType != "image/png" {
		t.Fatalf("message file = %+v", f)
	}
}

func TestACP_InboundImageReachesRunResultFiles(t *testing.T) {
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	c, err := LoadACP(context.Background(), acpTestOptions(t, "image_out", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	client := CreateInProcessClient(InProcessOptions{Model: "acp", GenerateStream: c.GenerateStream})
	res, err := client.Run(context.Background(), "shoot", bareToolkit(t))
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if res.Text != "here" {
		t.Fatalf("text = %q", res.Text)
	}
	if len(res.Files) != 2 || res.Files[0].From != "screenshot" || res.Files[0].Data != b64 || res.Files[1].From != "acp" {
		t.Fatalf("RunResult.Files = %+v", res.Files)
	}

	ch, err := client.Stream(context.Background(), "shoot", bareToolkit(t))
	if err != nil {
		t.Fatalf("Stream: %v", err)
	}
	var done *RunResult
	for ev := range ch {
		if ev.Type == "done" {
			done = ev.Result
		}
		if ev.Type == "error" {
			t.Fatalf("stream error: %v", ev.Err)
		}
	}
	if done == nil || len(done.Files) != 2 || done.Files[0].CallID != "t1" {
		t.Fatalf("stream done Files = %+v", done)
	}
}

func TestACP_AudioAndFilePartsSentAsAudioAndResourceBlocks(t *testing.T) {
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	blocks := acpPromptBlocks(t, "media", []any{
		map[string]any{"type": "input_audio", "input_audio": map[string]any{"data": b64, "format": "mp3"}},
		map[string]any{"type": "file", "file": map[string]any{"filename": "a.pdf", "file_data": "data:application/pdf;base64," + b64}},
		map[string]any{"type": "file", "file": map[string]any{"file_data": "data:text/plain;base64," + b64}},
		map[string]any{"type": "image_url", "image_url": map[string]any{"url": "https://example.com/cat.png"}},
	})
	if len(blocks) != 4 {
		t.Fatalf("want text + audio + 2 resource blocks (URL-only image gets none), got %v", blocks)
	}
	text, _ := blocks[0].(map[string]any)["text"].(string)
	if strings.Contains(text, b64) || !strings.Contains(text, "audio (audio/mpeg, 67 bytes)") ||
		!strings.Contains(text, "image (, 0 bytes)") {
		t.Fatalf("text block: %q", text)
	}
	a := blocks[1].(map[string]any)
	if a["type"] != "audio" || a["mimeType"] != "audio/mpeg" || a["data"] != b64 {
		t.Fatalf("audio block: %v", a)
	}
	for i, wantURI := range []string{"attachment:///a.pdf", "attachment:///attachment"} {
		r := blocks[2+i].(map[string]any)
		res, _ := r["resource"].(map[string]any)
		if r["type"] != "resource" || res["uri"] != wantURI || res["blob"] != b64 {
			t.Fatalf("resource block %d: %v", i, r)
		}
	}
}
