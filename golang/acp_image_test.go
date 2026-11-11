package toolnexus

import (
	"context"
	"encoding/base64"
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
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, scenario, outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	b64 := base64.StdEncoding.EncodeToString(acpTestPNG)
	req := InProcessRequest{Messages: []any{map[string]any{"role": "user", "content": []any{
		map[string]any{"type": "text", "text": "what is in this picture?"},
		map[string]any{"type": "image_url", "image_url": map[string]any{"url": "data:image/png;base64," + b64}},
	}}}}
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
