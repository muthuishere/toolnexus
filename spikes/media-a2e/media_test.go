package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// Hermetic fake provider: start -> processing x2 -> completed | failed.
func fake(t *testing.T, final string) (*httptest.Server, *map[string]any) {
	var polls int32
	var got map[string]any
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer FAKE_KEY" {
			w.WriteHeader(401)
			return
		}
		if r.Method == "POST" {
			json.NewDecoder(r.Body).Decode(&got)
			w.Write([]byte(`{"code":0,"data":{"_id":"j1"}}`))
			return
		}
		st := "processing"
		if atomic.AddInt32(&polls, 1) >= 3 {
			st = final
		}
		w.Write([]byte(`{"code":0,"data":{"current_status":"` + st + `","failed_message":"nsfw","result_url":"https://x/v.mp4"}}`))
	})), &got
}

func style(base string) Style {
	return Style{BaseURL: base, Headers: map[string]string{"Authorization": "Bearer ${MEDIA_FAKE}"},
		Verbs: map[string]Verb{"speech": {Mode: "async", Kind: "audio", Start: Req{"POST", "/start"},
			Args: map[string]string{"text": "text_prompt", "voice": "references.0.speaker"}, IDPath: "data._id",
			Poll:       &Poll{Path: "/j/{id}", StatusPath: "data.current_status", Done: []string{"completed"}, Failed: []string{"failed"}, ErrorPath: "data.failed_message", IntervalMs: 5, TimeoutMs: 2000},
			ResultPath: "data.result_url"}}}
}

func TestAsyncCompletes(t *testing.T) {
	t.Setenv("MEDIA_FAKE", "FAKE_KEY")
	s, got := fake(t, "completed")
	defer s.Close()
	var seen []string
	m := New(style(s.URL), Options{OnProgress: func(p Progress) { seen = append(seen, p.Status) }})
	r, err := m.Speech(context.Background(), SpeechInput{Text: "hi", Voice: "alloy"})
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := json.Marshal(*got); string(b) != `{"references":[{"speaker":"alloy"}],"text_prompt":"hi"}` {
		t.Fatalf("normalised args mapped wrong: %s", b)
	}
	if r.Files[0].MimeType != "video/mp4" || len(seen) != 3 || seen[2] != "completed" {
		t.Fatalf("got %+v seen %v", r, seen)
	}
}

func TestAsyncFailsAsToolError(t *testing.T) {
	t.Setenv("MEDIA_FAKE", "FAKE_KEY")
	s, _ := fake(t, "failed")
	defer s.Close()
	m := New(style(s.URL), Options{})
	tr, _ := m.Tools()[0].Execute(map[string]any{"text": "hi"}, nil)
	if !tr.IsError || tr.Output != "job j1 failed: nsfw" {
		t.Fatalf("%+v", tr)
	}
}

func TestTimeoutAndCancel(t *testing.T) {
	t.Setenv("MEDIA_FAKE", "FAKE_KEY")
	s, _ := fake(t, "processing")
	defer s.Close()
	st := style(s.URL)
	v := st.Verbs["speech"]
	v.Poll.TimeoutMs = 30
	m := New(st, Options{})
	_, err := m.Speech(context.Background(), SpeechInput{Text: "hi"})
	if err == nil || !strings.Contains(err.Error(), "timed out after 30ms") {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := m.Speech(ctx, SpeechInput{Text: "hi"}); err == nil {
		t.Fatal("cancel ignored")
	}
}
