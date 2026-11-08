// Integration suite against the shared mock model server (tools/mock-llm).
// Starts the mock as a subprocess on a free port unless TN_MOCK_LLM_URL is set;
// skips (does not fail) when python3 is unavailable. No network, no real key.

package toolnexus

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// mockLLM returns the mock base URL (ending in /v1). It starts a private mock
// for this test (stopped on cleanup) unless TN_MOCK_LLM_URL is set.
func mockLLM(t *testing.T) string {
	t.Helper()
	if u := os.Getenv("TN_MOCK_LLM_URL"); u != "" {
		return strings.TrimRight(u, "/")
	}
	py, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 not found on PATH; the mock-llm integration suite needs it")
	}
	script, _ := filepath.Abs(filepath.Join("..", "tools", "mock-llm", "mock_llm.py"))
	if _, err := os.Stat(script); err != nil {
		t.Skip("mock server not found at " + script)
	}
	cmd := exec.Command(py, script, "--port", "0", "--quiet")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("mock stdout: %v", err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatalf("start mock: %v", err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	})
	lines := make(chan string, 1)
	go func() {
		sc := bufio.NewScanner(out)
		if sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
		_, _ = io.Copy(io.Discard, out)
	}()
	const prefix = "toolnexus mock model at "
	select {
	case line := <-lines:
		if !strings.HasPrefix(line, prefix) {
			t.Fatalf("unexpected mock banner: %q", line)
		}
		return strings.TrimSpace(strings.TrimPrefix(line, prefix))
	case <-time.After(10 * time.Second):
		t.Fatal("mock did not print its URL within 10s")
	}
	return ""
}

func mockLLMPost(t *testing.T, url, body string) []byte {
	t.Helper()
	resp, err := http.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatalf("POST %s: %v", url, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		t.Fatalf("POST %s: %d %s", url, resp.StatusCode, b)
	}
	return b
}

func mockLLMGet(t *testing.T, url string) []byte {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		t.Fatalf("GET %s: %d %s", url, resp.StatusCode, b)
	}
	return b
}

// addToolkit builds a toolkit with one native add(a,b) tool and records calls.
func mockLLMAddToolkit(t *testing.T, seen *[]map[string]any) *Toolkit {
	t.Helper()
	schema := JSONSchema{
		"type": "object",
		"properties": map[string]any{
			"a": map[string]any{"type": "integer"},
			"b": map[string]any{"type": "integer"},
		},
		"required": []any{"a", "b"},
	}
	var mu sync.Mutex
	add := NativeTool("add", "Add two integers.", schema, func(_ context.Context, args map[string]any) (string, error) {
		mu.Lock()
		*seen = append(*seen, args)
		mu.Unlock()
		a, _ := args["a"].(float64)
		b, _ := args["b"].(float64)
		return fmt.Sprintf("%d", int(a+b)), nil
	})
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{add}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	return tk
}

func mockLLMClient(base string, style ClientStyle) *Client {
	return CreateClient(ClientOptions{BaseURL: base, Style: style, Model: "mock-model", APIKey: "mock-key", RetryBaseMs: 1})
}

func TestMockLLMToolLoop(t *testing.T) {
	base := mockLLM(t)
	for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
		t.Run(string(style), func(t *testing.T) {
			var seen []map[string]any
			tk := mockLLMAddToolkit(t, &seen)
			res, err := mockLLMClient(base, style).Run(context.Background(), "What is 1 + 1? Use the add tool.", tk)
			if err != nil {
				t.Fatalf("Run: %v", err)
			}
			if len(seen) != 1 {
				t.Fatalf("add executed %d times, want 1", len(seen))
			}
			if seen[0]["a"] != float64(1) || seen[0]["b"] != float64(1) {
				t.Errorf("add args = %v, want a=1 b=1 (from the schema)", seen[0])
			}
			if res.Text != "Tool result: 2" {
				t.Errorf("final text = %q, want %q", res.Text, "Tool result: 2")
			}
		})
	}
}

func TestMockLLMStreaming(t *testing.T) {
	base := mockLLM(t)
	for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
		t.Run(string(style), func(t *testing.T) {
			ch, err := mockLLMClient(base, style).Stream(context.Background(), "Say hello.", nil)
			if err != nil {
				t.Fatalf("Stream: %v", err)
			}
			var sb strings.Builder
			done := false
			for ev := range ch {
				switch ev.Type {
				case "text":
					sb.WriteString(ev.Delta)
				case "error":
					t.Fatalf("stream error: %v", ev.Err)
				case "done":
					done = true
				}
			}
			if !done {
				t.Error("stream ended without a done event")
			}
			if got := sb.String(); got != "This is a reply from the toolnexus mock model." {
				t.Errorf("streamed text = %q", got)
			}
		})
	}
}

func TestMockLLMAttachment(t *testing.T) {
	base := mockLLM(t)
	sum, err := os.ReadFile(filepath.Join("..", "examples", "media", "fixture.png.sha256"))
	if err != nil {
		t.Fatalf("read fixture sha: %v", err)
	}
	want := "image/png 82 bytes sha256=" + strings.Fields(string(sum))[0][:12]
	for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
		t.Run(string(style), func(t *testing.T) {
			res, err := mockLLMClient(base, style).RunParts(context.Background(), []ContentPart{
				Text("Describe this image."),
				File(filepath.Join("..", "examples", "media", "fixture.png")),
			}, nil)
			if err != nil {
				t.Fatalf("RunParts: %v", err)
			}
			if !strings.Contains(res.Text, "Received 1 image(s): "+want) {
				t.Errorf("reply = %q, want it to contain %q", res.Text, want)
			}
		})
	}
}

func TestMockLLMRetryOn429(t *testing.T) {
	base := mockLLM(t)
	mockLLMPost(t, base+"/_mock/reset", "{}")
	mockLLMPost(t, base+"/_mock/faults", `{"path":"/chat/completions","status":429,"times":1,"retryAfter":"0"}`)
	res, err := mockLLMClient(base, StyleOpenAI).Run(context.Background(), "Say hello.", nil)
	if err != nil {
		t.Fatalf("Run after one 429: %v", err)
	}
	if res.Text != "This is a reply from the toolnexus mock model." {
		t.Errorf("text = %q", res.Text)
	}
	var counts map[string]any
	if err := json.Unmarshal(mockLLMGet(t, base+"/_mock/requests"), &counts); err != nil {
		t.Fatalf("decode counts: %v", err)
	}
	if n := mockLLMCount(counts, "/chat/completions"); n != 2 {
		t.Errorf("/chat/completions calls = %v, want 2 (429 then success); counts=%v", n, counts)
	}
}

// countFor finds a route's count whether the mock nests it or not.
func mockLLMCount(v map[string]any, route string) float64 {
	for k, x := range v {
		switch y := x.(type) {
		case float64:
			if k == route || k == "/v1"+route {
				return y
			}
		case map[string]any:
			if n := mockLLMCount(y, route); n != 0 {
				return n
			}
		}
	}
	return 0
}

func TestMockLLMClassifier(t *testing.T) {
	base := mockLLM(t)
	t.Setenv("TN_MOCK_CLASSIFIER_KEY", "mock-key")
	origin := strings.TrimSuffix(base, "/v1")
	cases := []struct {
		name string
		opts ClassifierOptions
	}{
		{"custom", ClassifierOptions{Backend: BackendCustom, BaseURL: base, Supports: &ClassifierSupports{}, APIKeyEnv: "TN_MOCK_CLASSIFIER_KEY"}},
		{"cloudflare", ClassifierOptions{Backend: BackendCloudflare, BaseURL: origin + "/v1/accounts/acc/ai/run", APIKeyEnv: "TN_MOCK_CLASSIFIER_KEY"}},
		{"openai", ClassifierOptions{Backend: BackendOpenAI, BaseURL: base, APIKeyEnv: "TN_MOCK_CLASSIFIER_KEY"}},
	}
	options := map[string]string{
		"billing": "the ticket is about an invoice or a charge",
		"bug":     "the ticket reports broken behaviour",
		"sales":   "the ticket asks about buying",
	}
	rubric := []string{"not urgent", "somewhat urgent", "urgent", "on fire"}
	questions := map[string]Question{
		"isQuestion": NoulQuestion{Instructions: "Is the ticket a question?"},
		"team":       ChoiceQuestion{Instructions: "Which team owns this ticket?", Criteria: options},
		"urgency":    ScoreQuestion{Instructions: "How urgent is it?", Criteria: rubric},
	}
	state := "My invoice was charged twice, please help."
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cl, err := CreateClassifier(c.opts)
			if err != nil {
				t.Fatalf("CreateClassifier: %v", err)
			}
			d, err := cl.Evaluate(context.Background(), state, questions)
			if err != nil {
				t.Fatalf("Evaluate: %v", err)
			}
			n, ok := d.Answers["isQuestion"].(NoulAnswer)
			if !ok || n.Noul != 0.95 {
				t.Errorf("noul = %#v, want 0.95", d.Answers["isQuestion"])
			}
			ch, ok := d.Answers["team"].(ChoiceAnswer)
			if !ok {
				t.Fatalf("team answer = %#v", d.Answers["team"])
			}
			if _, in := options[ch.Choice]; !in {
				t.Errorf("choice %q not among options", ch.Choice)
			}
			var sum float64
			for k, p := range ch.Probabilities {
				if _, in := options[k]; !in {
					t.Errorf("probability for unknown option %q", k)
				}
				sum += p
			}
			if math.Abs(sum-1) > 1e-9 || len(ch.Probabilities) != len(options) {
				t.Errorf("choice probabilities %v sum %v", ch.Probabilities, sum)
			}
			if ch.Probabilities[ch.Choice] != 0.7 || ch.Confidence != 0.8 {
				t.Errorf("choice prob/conf = %v/%v, want 0.7/0.8", ch.Probabilities[ch.Choice], ch.Confidence)
			}
			sc, ok := d.Answers["urgency"].(ScoreAnswer)
			if !ok {
				t.Fatalf("urgency answer = %#v", d.Answers["urgency"])
			}
			if sc.Score < 0 || sc.Score > float64(len(rubric)-1) || sc.Confidence != 0.6 {
				t.Errorf("score = %v conf %v, want within [0,%d] and 0.6", sc.Score, sc.Confidence, len(rubric)-1)
			}
			d2, err := cl.Evaluate(context.Background(), state, questions)
			if err != nil {
				t.Fatalf("second Evaluate: %v", err)
			}
			if d2.Answers["team"].(ChoiceAnswer).Choice != ch.Choice || d2.Answers["urgency"].(ScoreAnswer).Score != sc.Score {
				t.Error("same call twice gave a different answer")
			}
		})
	}
}

func TestMockLLMMedia(t *testing.T) {
	base := mockLLM(t)
	t.Setenv("MOCK_LLM_KEY", "mock-key")
	path := filepath.Join(t.TempDir(), "mock-media.json")
	if err := os.WriteFile(path, mockLLMGet(t, base+"/_mock/media-style.json"), 0o600); err != nil {
		t.Fatal(err)
	}
	style, err := LoadMediaStyle(path)
	if err != nil {
		t.Fatalf("LoadMediaStyle: %v", err)
	}
	m := MustCreateMedia(style)
	ctx := context.Background()

	file := func(name string, r MediaResult, err error, suffix, mime string) {
		t.Helper()
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if len(r.Files) != 1 || !strings.HasSuffix(r.Files[0].URL, suffix) || r.Files[0].MimeType != mime {
			t.Errorf("%s files = %+v, want one %s ending %s", name, r.Files, mime, suffix)
		}
		if r.Status != "completed" {
			t.Errorf("%s status = %q", name, r.Status)
		}
	}
	r, err := m.Image(ctx, ImageInput{Prompt: "a red square", Width: 8, Height: 8})
	file("image", r, err, "/files/image.png", "image/png")
	r, err = m.Video(ctx, VideoInput{Prompt: "a red square spinning", Seconds: 1})
	file("video", r, err, "/files/video.mp4", "video/mp4")
	r, err = m.Speech(ctx, SpeechInput{Text: "hello", Voice: "v1"})
	file("speech", r, err, "/files/speech.wav", "audio/wav")
	r, err = m.Talk(ctx, TalkInput{Image: base + "/files/image.png", Audio: base + "/files/speech.wav"})
	file("talking", r, err, ".mp4", "video/mp4")

	audio := base + "/files/speech.wav"
	r, err = m.Transcribe(ctx, TranscribeInput{Audio: audio})
	if err != nil || r.Text != "Mock transcript of "+audio+"." {
		t.Errorf("transcribe = %q, %v", r.Text, err)
	}
	r, err = m.CloneVoice(ctx, CloneVoiceInput{Name: "muthu", Samples: []string{audio}})
	if err != nil || r.Text != "voice-"+sha256Hex([]byte("muthu"))[:8] {
		t.Errorf("cloneVoice = %q, %v", r.Text, err)
	}

	_, err = m.Image(ctx, ImageInput{Prompt: "please [fail]"})
	if err == nil || !strings.Contains(err.Error(), "mock: job asked to fail") {
		t.Errorf("failing job err = %v, want it to contain the mock's message", err)
	}
}

// TestMockLLMMediaOffline is the fully offline end-to-end path (docs: "Test the
// whole agent offline"): the mock model calls generate_image, the tool runs the
// xai-grok-cli-mock preset (no CLI, no key, no network), and the produced file
// lands in RunResult.Files.
func TestMockLLMMediaOffline(t *testing.T) {
	base := mockLLM(t)
	style, err := LoadMediaStyle(MediaStyleXaiGrokCliMock, MediaConfig{Gather: MediaGather{Dir: t.TempDir()}})
	if err != nil {
		t.Fatal(err)
	}
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, Media: MustCreateMedia(style)})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	res, err := mockLLMClient(base, StyleOpenAI).Run(context.Background(), "Draw a red apple.", tk)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Name != "generate_image" || res.ToolCalls[0].IsError {
		t.Fatalf("tool calls = %+v", res.ToolCalls)
	}
	if len(res.Files) != 1 || res.Files[0].MimeType != "image/png" {
		t.Fatalf("files = %+v", res.Files)
	}
	b, err := os.ReadFile(res.Files[0].Path)
	if err != nil || string(b) != "toolnexus mock image" {
		t.Fatalf("file %q = %q, %v", res.Files[0].Path, b, err)
	}
}
