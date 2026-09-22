// Spike for issues #91 and #92 — what the library hands back when it fails.
//
// Entirely OFFLINE: a local stub HTTP server plays an OpenAI-compatible provider
// and returns an OpenRouter-SHAPED 400 body carrying a FAKE account identifier
// (user_2FAKE). No credential is read, no network egress, nothing to scrub.
//
//	go run .
package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// The FAKE body. Shaped exactly like OpenRouter's 400, with an obviously fake id.
const fakeBody = `{"error":{"message":"not a valid model ID","code":400},"user_id":"user_2FAKEFAKEFAKEFAKEFAKEFAKE"}`

func rule(s string) { fmt.Printf("\n=== %s ===\n", s) }

func main() {
	// ---- stub provider -------------------------------------------------
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		if strings.Contains(r.URL.Path, "slow") {
			time.Sleep(2 * time.Second) // outlive any small TimeoutMs
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"hi"}}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(fakeBody))
	}))
	defer srv.Close()

	tk, _ := tn.CreateToolkit(context.Background(), tn.Options{})

	// ---- A. provider 400: fail-fast + verbatim body ---------------------
	rule("A. provider 400 with Retries: 4 (issue #92 part 2, and the praised fail-fast)")
	hits = 0
	c := tn.CreateClient(tn.ClientOptions{
		BaseURL: srv.URL, Model: "stub", APIKey: "not-a-real-key",
		Retries: 4,
	})
	t0 := time.Now()
	res, err := c.Run(context.Background(), "hello", tk)
	fmt.Printf("elapsed      : %v\n", time.Since(t0).Round(time.Millisecond))
	fmt.Printf("HTTP attempts: %d   (Retries: 4 -> fail-fast on 4xx is WORKING if this is 1)\n", hits)
	fmt.Printf("err          : %v\n", err)
	fmt.Printf("err carries account id? %v\n", strings.Contains(fmt.Sprint(err), "user_2FAKE"))
	fmt.Printf("Status=%q Turns=%d Usage=%+v\n", res.Status, res.Turns, res.Usage)

	// ---- B. TimeoutMs: 1 -----------------------------------------------
	rule("B. TimeoutMs: 1 against a slow provider (issue #92 part 1)")
	c2 := tn.CreateClient(tn.ClientOptions{
		BaseURL: srv.URL + "/slow", Model: "stub", APIKey: "not-a-real-key",
		TimeoutMs: 1,
	})
	res2, err2 := c2.Run(context.Background(), "hello", tk)
	fmt.Printf("err          : %v\n", err2)
	fmt.Printf("Status       : %q   <- documented vocabulary is done|pending|incomplete|...|timeout|error\n", res2.Status)
	fmt.Printf("Turns=%d ToolCallCount=%d Usage=%+v Model=%q Text=%q\n",
		res2.Turns, res2.ToolCallCount, res2.Usage, res2.Model, res2.Text)
	fmt.Printf("RunResult is the zero value? %v\n", res2.Status == "" && res2.Turns == 0 && res2.Model == "")

	// ---- C. the same deadline on the STREAM path ------------------------
	rule("C. TimeoutMs: 1 on Stream (does the streaming path differ?)")
	ch, _ := c2.Stream(context.Background(), "hello", tk)
	n := 0
	for ev := range ch {
		n++
		fmt.Printf("  event %d: type=%q err=%v\n", n, ev.Type, ev.Err)
	}
	if n == 0 {
		fmt.Println("  (no events)")
	}

	// ---- D. classifier default model (issue #91), OFFLINE ---------------
	rule("D. classifier zero-value defaults (issue #91), pointed at the stub")
	fmt.Printf("DefaultClassifierBaseURL  = %q\n", tn.DefaultClassifierBaseURL)
	fmt.Printf("DefaultClassifierModel    = %q\n", tn.DefaultClassifierModel)
	fmt.Printf("DefaultClassifierAPIKeyEnv= %q\n", tn.DefaultClassifierAPIKeyEnv)
	os.Setenv("SPIKE_FAKE_KEY", "not-a-real-key")
	cl, cerr := tn.CreateClassifier(tn.ClassifierOptions{
		BaseURL: srv.URL, APIKeyEnv: "SPIKE_FAKE_KEY", // stub stands in for the default base
	})
	if cerr != nil {
		fmt.Println("construct err:", cerr)
		return
	}
	q := map[string]tn.Question{"ok": tn.NoulQuestion{Instructions: "Is this fine?"}}
	_, derr := cl.Evaluate(context.Background(), "some state", q)
	fmt.Printf("err             : %v\n", derr)
	fmt.Printf("err carries account id? %v\n", strings.Contains(fmt.Sprint(derr), "user_2FAKE"))
}
