package agents

// go-agent-cli follow-up to ask #7: AsToolWith threads the calling ctx, and the
// child's tool calls go through the host's gate (Hooks) and ask bridge (WaitFor).

import (
	"context"
	"net/http"
	"sync"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type blockingLLM struct{}

func (blockingLLM) RoundTrip(req *http.Request) (*http.Response, error) {
	<-req.Context().Done()
	return nil, req.Context().Err()
}

func TestAsToolCancelledWithCallingCtx(t *testing.T) {
	child := New("child", Spec{Does: "d", Soul: "s"})
	tool := child.AsTool(Options{Transport: blockingLLM{}})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(100 * time.Millisecond); cancel() }()
	start := time.Now()
	r, _ := tool.Execute(map[string]any{"prompt": "go"}, &tn.ToolContext{Ctx: ctx})
	if d := time.Since(start); d > 3*time.Second {
		t.Fatalf("child not cancelled: took %v", d)
	}
	if !r.IsError || len(r.Output) < 9 || r.Output[:9] != "cancelled" {
		t.Fatalf("result = %+v", r)
	}
}

// childScript: first call asks for the "danger" tool, then answers.
type childScript struct {
	mu sync.Mutex
	n  int
}

func (c *childScript) RoundTrip(req *http.Request) (*http.Response, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.n++
	if c.n == 1 {
		return callResp(toolCallPart("danger", map[string]any{}, "d1")), nil
	}
	return textResp("child done"), nil
}

func TestAsToolWithGateAndAskBridge(t *testing.T) {
	ran := false
	danger := tn.NativeTool("danger", "mutates", nil, func(context.Context, map[string]any) (string, error) {
		ran = true
		return "did it", nil
	})
	danger.Hints = &tn.ToolHints{Destructive: true}
	child := New("child", Spec{Does: "d", Soul: "s", Tools: []tn.Tool{danger}})
	var gated []string
	asked := 0
	tool := child.AsToolWith(func(*tn.ToolContext) Options {
		return Options{
			Transport: &childScript{},
			Hooks: &tn.Hooks{BeforeTool: func(_ context.Context, ev tn.BeforeToolEvent) (*tn.ToolOverride, error) {
				gated = append(gated, ev.Name)
				if ev.Hints == nil || !ev.Hints.ReadOnly {
					p := tn.Pending(tn.Request{Kind: "approval", Prompt: "allow " + ev.Name + "?"})
					return &tn.ToolOverride{Result: &p}, nil
				}
				return nil, nil
			}},
			WaitFor: func(req tn.Request) (tn.Answer, error) {
				asked++
				return tn.Answer{ID: req.ID, Ok: true}, nil
			},
		}
	})
	r, _ := tool.Execute(map[string]any{"prompt": "go"}, &tn.ToolContext{Ctx: context.Background()})
	if r.IsError || r.Output != "child done" {
		t.Fatalf("result = %+v", r)
	}
	if len(gated) != 1 || gated[0] != "danger" || asked != 1 || !ran {
		t.Fatalf("gated=%v asked=%d ran=%v", gated, asked, ran)
	}
}
