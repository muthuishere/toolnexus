package toolnexus

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// go-agent-cli ask #8: a key is only ever sent to the host it belongs to.
func TestResolveKeyByHost(t *testing.T) {
	t.Setenv("OPENROUTER_API_KEY", "fake-or")
	t.Setenv("OPENAI_API_KEY", "fake-oa")
	t.Setenv("ANTHROPIC_API_KEY", "fake-an")
	t.Setenv("TN_TEST_KEY", "fake-custom")
	cases := []struct {
		base, envName, explicit, want string
		style                         ClientStyle
	}{
		{base: "https://api.openai.com/v1", want: "fake-oa"},
		{base: "https://api.anthropic.com", style: StyleAnthropic, want: "fake-an"},
		{base: "https://openrouter.ai/api/v1", style: StyleAnthropic, want: "fake-or"},
		{base: "https://API.OpenAI.com/v1", want: "fake-oa"},
		{base: "https://example.com/v1", explicit: "fake-explicit", want: "fake-explicit"},
		{base: "https://api.openai.com/v1", explicit: "fake-explicit", want: "fake-explicit"},
		{base: "https://example.com/v1", envName: "TN_TEST_KEY", want: "fake-custom"},
	}
	for _, tc := range cases {
		c := CreateClient(ClientOptions{BaseURL: tc.base, Style: tc.style, APIKey: tc.explicit, APIKeyEnv: tc.envName})
		got, err := c.resolveKey()
		if err != nil || got != tc.want {
			t.Fatalf("%s: got %q err %v, want %q", tc.base, got, err, tc.want)
		}
	}
	// never a cross-provider fallback
	for _, base := range []string{
		"https://example.com/v1",            // unknown host
		"http://api.openai.com/v1",          // plain http
		"https://api.openai.com.evil.test/", // lookalike
		"https://evil-openrouter.ai/v1",     // not a subdomain
		"http://localhost:11434/v1",
	} {
		c := CreateClient(ClientOptions{BaseURL: base})
		got, err := c.resolveKey()
		if err == nil || got != "" {
			t.Fatalf("%s: must not resolve an env key, got %q", base, got)
		}
		if strings.Contains(err.Error(), "fake-") {
			t.Fatalf("error leaks a key value: %v", err)
		}
	}
	// the right env var unset ⇒ error naming it, not another provider's key
	t.Setenv("OPENAI_API_KEY", "")
	_, err := CreateClient(ClientOptions{BaseURL: "https://api.openai.com/v1"}).resolveKey()
	if err == nil || !strings.Contains(err.Error(), "OPENAI_API_KEY") {
		t.Fatalf("err = %v", err)
	}
}

func TestListModels(t *testing.T) {
	var gotAuth, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path + "?" + r.URL.RawQuery
		if r.Header.Get("x-api-key") != "" {
			gotAuth = "x-api-key"
			if r.URL.Query().Get("after_id") == "" {
				fmt.Fprint(w, `{"data":[{"id":"claude-b","display_name":"B"}],"has_more":true,"last_id":"claude-b"}`)
			} else {
				fmt.Fprint(w, `{"data":[{"id":"claude-a","display_name":"A"}],"has_more":false}`)
			}
			return
		}
		gotAuth = r.Header.Get("Authorization")
		fmt.Fprint(w, `{"data":[{"id":"z/model","name":"Z","context_length":200000},{"id":"a/model"}]}`)
	}))
	defer srv.Close()
	ms, err := ListModels(context.Background(), ClientOptions{BaseURL: srv.URL + "/api/v1", APIKey: "fake"})
	if err != nil || len(ms) != 2 || ms[0].ID != "a/model" || ms[1].ContextLength != 200000 || gotAuth != "Bearer fake" || gotPath != "/api/v1/models?" {
		t.Fatalf("openai: %+v %v auth=%q path=%q", ms, err, gotAuth, gotPath)
	}
	ms, err = ListModels(context.Background(), ClientOptions{BaseURL: srv.URL, Style: StyleAnthropic, APIKey: "fake"})
	if err != nil || len(ms) != 2 || ms[0].ID != "claude-a" || ms[1].Name != "B" || gotAuth != "x-api-key" {
		t.Fatalf("anthropic: %+v %v", ms, err)
	}
	if _, err := ListModels(context.Background(), ClientOptions{BaseURL: srv.URL}); err == nil {
		t.Fatal("unknown host without APIKey must not resolve an env key")
	}
}
