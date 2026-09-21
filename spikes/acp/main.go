// Command acp-spike drives the fake ACP agent (fakeagent/) through a minimal
// ACP client (client/) to settle the ADR-0025 gate with running code, printed
// verbatim so SPIKE.md can quote it.
//
//	go run . stale        - gate item 1: stale answer + supersedes fix
//	go run . hang          - gate item 2: unanswered permission hangs
//	go run . permission    - gate item 2: answered permission completes
//	go run . noisy         - gate item 3: thought/tool chunks corrupt output
//	go run . warm N        - gate item 4: warm-session per-call overhead
//	go run . all           - run everything in sequence
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"time"

	"toolnexus/spikes/acp/client"
)

const fakeagentBin = "./fakeagent.bin"

func buildFakeagent() error {
	cmd := exec.Command("go", "build", "-o", fakeagentBin, "./fakeagent")
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func main() {
	if err := buildFakeagent(); err != nil {
		fmt.Println("build fakeagent failed:", err)
		os.Exit(1)
	}
	defer os.Remove(fakeagentBin)

	which := "all"
	if len(os.Args) > 1 {
		which = os.Args[1]
	}

	run := map[string]func(){
		"stale":      gateStale,
		"hang":       gateHang,
		"permission": gatePermission,
		"noisy":      gateNoisy,
		"warm":       gateWarm,
		"live":       gateLive,
	}

	if which == "all" {
		for _, name := range []string{"stale", "hang", "permission", "noisy", "warm"} {
			fmt.Printf("\n========== GATE: %s ==========\n", name)
			run[name]()
		}
		return
	}
	fn, ok := run[which]
	if !ok {
		fmt.Println("unknown scenario:", which)
		os.Exit(1)
	}
	fn()
}

// ---------------------------------------------------------------------------
// Gate 1: stale answer, then the supersedes fix.
// ---------------------------------------------------------------------------

func gateStale() {
	c, err := client.Start(fakeagentBin, "-scenario=stale")
	must(err)
	defer c.Close()

	ctx := context.Background()

	q1 := "What is the capital of France?"
	a1, err := c.Prompt(ctx, q1)
	must(err)
	fmt.Printf("turn 1 prompt: %q\n", q1)
	fmt.Printf("turn 1 answer: %q\n", a1)

	// Full-request-every-turn: the second turn sends the ENTIRE assembled
	// conversation again (both questions), the way toolnexus's client
	// assembles `Messages []any` fresh each call. No supersedes marker.
	q2 := q1 + "\nWhat is the capital of Japan?"
	a2, err := c.Prompt(ctx, q2)
	must(err)
	fmt.Printf("turn 2 full-request (no marker): %q\n", q2)
	fmt.Printf("turn 2 answer (EXPECT stale/wrong): %q\n", a2)
	if a2 == "STALE-ANSWER-TO:"+q1 {
		fmt.Println("=> REPRODUCED: agent answered the FIRST (stale) question, not the new one.")
	} else {
		fmt.Println("=> did not reproduce as expected:", a2)
	}

	// Now the same shape, but with the ADR's proposed mitigation: an
	// explicit supersedes marker naming what's fresh.
	q3 := q1 + "\nWhat is the capital of Japan?\nSUPERSEDES-ALL-PRIOR: What is the capital of Japan?"
	a3, err := c.Prompt(ctx, q3)
	must(err)
	fmt.Printf("turn 3 full-request WITH supersedes marker: %q\n", q3)
	fmt.Printf("turn 3 answer: %q\n", a3)
	if a3 == "FRESH-ANSWER-TO:What is the capital of Japan?" {
		fmt.Println("=> FIX HOLDS: supersedes marker made the agent answer the fresh question.")
	} else {
		fmt.Println("=> fix did not hold:", a3)
	}
}

// ---------------------------------------------------------------------------
// Gate 2: hang vs answered permission.
// ---------------------------------------------------------------------------

func gateHang() {
	c, err := client.Start(fakeagentBin, "-scenario=hang")
	must(err)
	defer c.Close()
	c.AutoAnswerPermission = false
	c.PermissionTimeout = 2 * time.Second // bounded so the SPIKE terminates

	start := time.Now()
	_, err = c.Prompt(context.Background(), "delete the database")
	elapsed := time.Since(start)
	fmt.Printf("prompt returned after %s, err=%v\n", elapsed, err)
	if _, ok := err.(*client.PermissionTimeoutErr); ok {
		fmt.Println("=> REPRODUCED: turn hung until the client's own timeout fired (would hang forever without one).")
	} else {
		fmt.Println("=> did not reproduce the hang as expected")
	}
}

func gatePermission() {
	c, err := client.Start(fakeagentBin, "-scenario=permission")
	must(err)
	defer c.Close()
	c.AutoAnswerPermission = true // answers with the FIRST allow-kind option
	c.PermissionTimeout = 5 * time.Second

	start := time.Now()
	answer, err := c.Prompt(context.Background(), "delete the database")
	elapsed := time.Since(start)
	must(err)
	fmt.Printf("prompt completed in %s\n", elapsed)
	fmt.Printf("answer: %q\n", answer)
	if answer == "PERMITTED:delete the database" {
		fmt.Println("=> HOLDS: answering session/request_permission with first allow-kind option unblocked the turn.")
	}
}

// ---------------------------------------------------------------------------
// Gate 3: thought/tool narration corrupting structured output.
// ---------------------------------------------------------------------------

func gateNoisy() {
	c, err := client.Start(fakeagentBin, "-scenario=noisy")
	must(err)
	defer c.Close()

	clean, err := c.Prompt(context.Background(), "42")
	must(err)
	dirty := c.LastDirty()

	fmt.Printf("clean (agent_message_chunk only): %s\n", clean)
	fmt.Printf("dirty (every chunk kind accumulated): %s\n", dirty)

	if isValidJSON(clean) {
		fmt.Println("=> clean output parses as JSON: HOLDS")
	} else {
		fmt.Println("=> clean output did NOT parse as JSON (unexpected)")
	}
	if !isValidJSON(dirty) {
		fmt.Println("=> dirty output does NOT parse as JSON: REPRODUCED corruption")
	} else {
		fmt.Println("=> dirty output unexpectedly still parsed")
	}
}

func isValidJSON(s string) bool {
	var v any
	return json.Unmarshal([]byte(s), &v) == nil
}

// ---------------------------------------------------------------------------
// Gate 4: warm-session overhead against a fast fake server.
// ---------------------------------------------------------------------------

func gateWarm() {
	n := 6
	if len(os.Args) > 2 {
		if v, err := strconv.Atoi(os.Args[2]); err == nil {
			n = v
		}
	}

	// (a) cold: spawn+init+one prompt+close, N times — models a one-shot CLI
	// call per turn (what `devin -p` pays every time).
	var coldTotal time.Duration
	for i := 0; i < n; i++ {
		start := time.Now()
		c, err := client.Start(fakeagentBin, "-scenario=warm")
		must(err)
		_, err = c.Prompt(context.Background(), fmt.Sprintf("turn %d", i))
		must(err)
		c.Close()
		coldTotal += time.Since(start)
	}

	// (b) warm: spawn+init ONCE, N prompts, close once — models an ACP
	// session.
	start := time.Now()
	c, err := client.Start(fakeagentBin, "-scenario=warm")
	must(err)
	initElapsed := time.Since(start)
	var warmPromptTotal time.Duration
	for i := 0; i < n; i++ {
		ps := time.Now()
		_, err = c.Prompt(context.Background(), fmt.Sprintf("turn %d", i))
		must(err)
		warmPromptTotal += time.Since(ps)
	}
	c.Close()
	warmTotal := initElapsed + warmPromptTotal

	fmt.Printf("N=%d turns\n", n)
	fmt.Printf("cold (spawn per turn):  total=%s  avg/turn=%s\n", coldTotal, coldTotal/time.Duration(n))
	fmt.Printf("warm (one process):     total=%s  avg/turn=%s  (init=%s once, then avg prompt=%s)\n",
		warmTotal, warmTotal/time.Duration(n), initElapsed, warmPromptTotal/time.Duration(n))
	fmt.Println("=> Against a FAKE server with no real startup cost, the fixed cost being amortised")
	fmt.Println("   is process-spawn + exec.Cmd bookkeeping only (microseconds-to-low-ms), NOT the")
	fmt.Println("   15s devin binary startup the ADR's real numbers show. Report this honestly below.")
}

// ---------------------------------------------------------------------------
// Live sanity check against the REAL `devin acp` binary (only run when the
// caller explicitly asks for "live" — costs real Devin usage).
// ---------------------------------------------------------------------------

func gateLive() {
	devinPath, err := exec.LookPath("devin")
	if err != nil {
		fmt.Println("devin not on PATH, skipping live check:", err)
		return
	}
	fmt.Println("devin found at:", devinPath)

	c, err := client.Start(devinPath, "acp")
	if err != nil {
		fmt.Println("FATAL starting devin acp:", err)
		os.Exit(1)
	}
	defer c.Close()
	c.AutoAnswerPermission = true
	c.PermissionTimeout = 45 * time.Second

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	start := time.Now()
	answer, err := c.Prompt(ctx, "Reply with exactly the single word: pong")
	elapsed := time.Since(start)
	if err != nil {
		fmt.Println("FATAL live prompt:", err, "after", elapsed)
		os.Exit(1)
	}
	fmt.Printf("live devin acp answered in %s: %q\n", elapsed, answer)
}

func must(err error) {
	if err != nil {
		fmt.Println("FATAL:", err)
		os.Exit(1)
	}
}
