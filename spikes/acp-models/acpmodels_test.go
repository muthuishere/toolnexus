package acpmodels

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
)

// Fake agent: re-exec of the test binary. FAKE_SHAPE = config | grouped | legacy | none | liar.
func TestHelperProcess(t *testing.T) {
	if os.Getenv("FAKE_ACP") != "1" {
		return
	}
	shape := os.Getenv("FAKE_SHAPE")
	cur := "vendor/default"
	models := []map[string]any{{"value": "vendor/default", "name": "Default"}, {"value": "vendor/cheap", "name": "Cheap", "_meta": map[string]any{"pricing": map[string]any{"input": 0}}}}
	cfg := func() []any {
		var opts any = models
		if shape == "grouped" {
			opts = []any{map[string]any{"group": "vendor", "name": "Vendor", "options": models}}
		}
		return []any{
			map[string]any{"id": "model", "name": "Model", "category": "model", "type": "select", "currentValue": cur, "options": opts},
			map[string]any{"id": "mode", "name": "Mode", "category": "mode", "type": "select", "currentValue": "build", "options": []any{map[string]any{"value": "build", "name": "Build"}, map[string]any{"value": "plan", "name": "Plan"}}},
			map[string]any{"id": "weird", "type": "future_type"},
		}
	}
	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		var m struct {
			ID     any             `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		_ = json.Unmarshal(sc.Bytes(), &m)
		var res any = map[string]any{}
		switch m.Method {
		case "initialize":
			res = map[string]any{"protocolVersion": 1, "agentInfo": map[string]any{"name": "fake"}, "agentCapabilities": map[string]any{"loadSession": true}}
		case "session/new":
			r := map[string]any{"sessionId": "s1"}
			switch shape {
			case "config", "grouped", "liar":
				r["configOptions"] = cfg()
			case "legacy":
				r["models"] = map[string]any{"currentModelId": cur, "availableModels": []any{map[string]any{"modelId": "vendor/default", "name": "Default"}, map[string]any{"modelId": "vendor/cheap", "name": "Cheap"}}}
			}
			res = r
		case "session/set_config_option":
			var p struct{ ConfigID, Value string }
			_ = json.Unmarshal(m.Params, &p)
			if p.ConfigID == "model" && shape != "liar" {
				cur = p.Value
			}
			res = map[string]any{"configOptions": cfg()}
		case "session/prompt":
			fmt.Printf(`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"model=%s"}}}}`+"\n", cur)
			res = map[string]any{"stopReason": "end_turn"}
		case "session/set_model":
			var p struct{ ModelID string }
			_ = json.Unmarshal(m.Params, &p)
			cur = p.ModelID
		}
		b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": res})
		fmt.Println(string(b))
	}
	os.Exit(0)
}

func fake(t *testing.T, shape string) *Client {
	t.Helper()
	c, err := Create(context.Background(), Options{Command: os.Args[0], Args: []string{"-test.run=TestHelperProcess"},
		Env: []string{"FAKE_ACP=1", "FAKE_SHAPE=" + shape}, Cwd: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func TestInfoKept(t *testing.T) {
	c := fake(t, "config")
	if !strings.Contains(string(c.Info().AgentInfo), "fake") || !strings.Contains(string(c.Info().AgentCapabilities), "loadSession") {
		t.Fatalf("initialize result discarded: %+v", c.Info())
	}
}

func TestShapes(t *testing.T) {
	for _, tc := range []struct {
		shape string
		src   Source
	}{{"config", SourceConfigOptions}, {"grouped", SourceConfigOptions}, {"legacy", SourceLegacyModels}} {
		t.Run(tc.shape, func(t *testing.T) {
			c := fake(t, tc.shape)
			m, src, err := c.Models(context.Background())
			if err != nil || src != tc.src || len(m.Values) != 2 || m.Current != "vendor/default" {
				t.Fatalf("got %+v %s %v", m, src, err)
			}
			s, err := c.NewSession(context.Background(), SessionOptions{Model: "vendor/cheap"})
			if err != nil {
				t.Fatal(err)
			}
			if s.Verified != (tc.src == SourceConfigOptions) {
				t.Fatalf("verified=%v for %s", s.Verified, tc.src)
			}
			out, _ := s.Prompt(context.Background(), "hi")
			if out != "model=vendor/cheap" {
				t.Fatalf("turn ran on %q", out)
			}
		})
	}
}

func TestGroupKept(t *testing.T) {
	m, _, _ := fake(t, "grouped").Models(context.Background())
	if m.Values[0].Group != "vendor" {
		t.Fatalf("group lost: %+v", m.Values[0])
	}
}

func TestNeither(t *testing.T) {
	c := fake(t, "none")
	if _, _, err := c.Models(context.Background()); !errors.Is(err, ErrNoModelSelector) {
		t.Fatalf("want ErrNoModelSelector, got %v", err)
	}
	if _, err := c.NewSession(context.Background(), SessionOptions{Model: "x"}); !errors.Is(err, ErrNoModelSelector) {
		t.Fatalf("requesting a model on a selector-less agent must fail loudly, got %v", err)
	}
	if _, err := c.NewSession(context.Background(), SessionOptions{}); err != nil {
		t.Fatalf("no model requested must still work: %v", err)
	}
}

func TestLiarRefused(t *testing.T) {
	_, err := fake(t, "liar").NewSession(context.Background(), SessionOptions{Model: "vendor/cheap"})
	if err == nil || !strings.Contains(err.Error(), "refusing") {
		t.Fatalf("agent that ignores the switch must be refused, got %v", err)
	}
}

func TestUnknownModelRejectedLocally(t *testing.T) {
	_, err := fake(t, "config").NewSession(context.Background(), SessionOptions{Model: "vendor/nope"})
	if err == nil || !strings.Contains(err.Error(), "not offered") {
		t.Fatalf("got %v", err)
	}
}

func TestModeSet(t *testing.T) {
	_, err := fake(t, "config").NewSession(context.Background(), SessionOptions{Mode: "plan"})
	if err == nil || !strings.Contains(err.Error(), "refusing") {
		t.Fatalf("fake never flips mode, so verify must refuse; got %v", err)
	}
}

// Host-side "free" predicate: the library ships none; a host picks its own.
func TestHostPredicate(t *testing.T) {
	m, _, _ := fake(t, "config").Models(context.Background())
	var free []string
	for _, v := range m.Values {
		if strings.Contains(string(v.Meta), `"input":0`) {
			free = append(free, v.Value)
		}
	}
	if len(free) != 1 || free[0] != "vendor/cheap" {
		t.Fatalf("%v", free)
	}
}

func TestLiveOpencode(t *testing.T) {
	if os.Getenv("ACP_LIVE") != "1" {
		t.Skip("set ACP_LIVE=1")
	}
	if _, err := exec.LookPath("opencode"); err != nil {
		t.Skip("no opencode")
	}
	c, err := Create(context.Background(), Options{Command: "opencode", Args: []string{"acp"}, Cwd: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	m, src, err := c.Models(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	var free []string
	for _, v := range m.Values {
		if strings.HasSuffix(v.Value, "-free") || strings.HasSuffix(v.Value, ":free") { // host policy, not library
			free = append(free, v.Value)
		}
	}
	t.Logf("source=%s models=%d current=%s free-by-suffix=%d", src, len(m.Values), m.Current, len(free))
	want := "opencode/mimo-v2.6-flash-free"
	s, err := c.NewSession(context.Background(), SessionOptions{Model: want})
	if err != nil {
		t.Fatal(err)
	}
	out, err := s.Prompt(context.Background(), "Reply with just: ok")
	t.Logf("verified=%v current=%s reply=%q err=%v", s.Verified, s.Model.Current, out, err)
	if !s.Verified || s.Model.Current != want || err != nil {
		t.Fatal("live selection failed")
	}
}
