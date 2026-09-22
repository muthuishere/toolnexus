// Windows stress for the builtin host boundary (ADR 0034) — the shipped go
// builtins, cross-compiled and run on a native Windows box over agentbus.
//
// Reduced and time-boxed on purpose: the agentbus listener is a shared resource
// and a job that overruns wedges it. Three scenarios, each with a control.
//
//	W1 concurrent timeouts   20 at once, each detaching a grandchild
//	W2 mixed load            20 successes + 20 timeouts, not crossed
//	W3 confinement           escapes, device names, and backslash spellings
package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

func tool(cfg any, name string) tn.Tool {
	tools, err := tn.SelectBuiltinsChecked(cfg)
	if err != nil {
		panic(err)
	}
	for _, t := range tools {
		if t.Name == name {
			return t
		}
	}
	panic("no builtin " + name)
}

func run(t tn.Tool, args map[string]any) tn.ToolResult {
	res, _ := t.Execute(args, &tn.ToolContext{Ctx: context.Background()})
	return res
}

func markers(dir string) int {
	entries, _ := os.ReadDir(dir)
	n := 0
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".marker") {
			n++
		}
	}
	return n
}

func verdict(ok bool) string {
	if ok {
		return "PASS"
	}
	return "FAIL"
}

func main() {
	dir, _ := os.MkdirTemp("", "tn-winstress-*")
	defer os.RemoveAll(dir)
	all := true

	// W1 — 20 concurrent timeouts. child.cmd detaches a grandchild that writes
	// the marker ~7 s later, so a marker means the kill did not reach the tree.
	md := filepath.Join(dir, "w1")
	_ = os.MkdirAll(md, 0o755)
	bash := tool(nil, "bash")
	var wg sync.WaitGroup
	var mu sync.Mutex
	timedOut, killedTree := 0, 0
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			m := filepath.Join(md, fmt.Sprintf("t%d.marker", i))
			res := run(bash, map[string]any{"command": "child.cmd " + m, "timeout": 700})
			mu.Lock()
			defer mu.Unlock()
			if res.Metadata["timedOut"] == true {
				timedOut++
			}
			if res.Metadata["killedTree"] == true {
				killedTree++
			}
		}(i)
	}
	wg.Wait()
	time.Sleep(12 * time.Second)
	orphans := markers(md)

	// CONTROL: one uninterrupted run must produce its marker.
	cd := filepath.Join(dir, "w1c")
	_ = os.MkdirAll(cd, 0o755)
	run(bash, map[string]any{"command": "child.cmd " + filepath.Join(cd, "c.marker"), "timeout": 30000})
	time.Sleep(2 * time.Second)
	control := markers(cd)

	w1 := orphans == 0 && timedOut == 20 && killedTree == 20 && control == 1
	all = all && w1
	fmt.Printf("W1 concurrent_timeouts n=20 orphans=%d timedOut=%d killedTree=%d control=%d/1 %s\n",
		orphans, timedOut, killedTree, control, verdict(w1))

	// W2 — successes and timeouts interleaved must not be crossed.
	md2 := filepath.Join(dir, "w2")
	_ = os.MkdirAll(md2, 0o755)
	okCorrect, okWrong, toCorrect := 0, 0, 0
	var wg2 sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg2.Add(2)
		go func(i int) {
			defer wg2.Done()
			want := fmt.Sprintf("ok-%d", i)
			res := run(bash, map[string]any{"command": "echo " + want})
			mu.Lock()
			defer mu.Unlock()
			if !res.IsError && strings.TrimSpace(res.Output) == want {
				okCorrect++
			} else {
				okWrong++
			}
		}(i)
		go func(i int) {
			defer wg2.Done()
			m := filepath.Join(md2, fmt.Sprintf("m%d.marker", i))
			res := run(bash, map[string]any{"command": "child.cmd " + m, "timeout": 700})
			mu.Lock()
			defer mu.Unlock()
			if res.IsError && res.Metadata["timedOut"] == true {
				toCorrect++
			}
		}(i)
	}
	wg2.Wait()
	time.Sleep(12 * time.Second)
	w2 := okCorrect == 20 && okWrong == 0 && toCorrect == 20 && markers(md2) == 0
	all = all && w2
	fmt.Printf("W2 mixed_load ok_correct=%d/20 ok_wrong=%d timeout_correct=%d/20 orphans=%d %s\n",
		okCorrect, okWrong, toCorrect, markers(md2), verdict(w2))

	// W3 — confinement, in Windows spellings: backslash climbs, a drive-absolute
	// path, and the reserved device names that pass a path comparison and write
	// to a device rather than into the directory.
	base := filepath.Join(dir, "confine")
	_ = os.MkdirAll(filepath.Join(base, "sub"), 0o755)
	write := tool(tn.BuiltinsConfig{BaseDir: base, ConfineToBaseDir: true}, "write")
	legal := []string{"a.txt", `sub\b.txt`, "sub/c.txt", "....//x"}
	escapes := []string{`..\x`, `sub\..\..\x`, `C:\Windows\Temp\tn-stress-escaped.txt`,
		"CON", "NUL", "nul.txt", `sub\COM1`, "aux.log"}

	allowedEscapes, refusedLegals := 0, 0
	var wg3 sync.WaitGroup
	for round := 0; round < 25; round++ {
		for _, p := range legal {
			wg3.Add(1)
			go func(p string) {
				defer wg3.Done()
				if run(write, map[string]any{"path": p, "content": "x"}).IsError {
					mu.Lock()
					refusedLegals++
					mu.Unlock()
				}
			}(p)
		}
		for _, p := range escapes {
			wg3.Add(1)
			go func(p string) {
				defer wg3.Done()
				if !run(write, map[string]any{"path": p, "content": "pwned"}).IsError {
					mu.Lock()
					allowedEscapes++
					mu.Unlock()
				}
			}(p)
		}
	}
	wg3.Wait()
	w3 := allowedEscapes == 0 && refusedLegals == 0
	all = all && w3
	fmt.Printf("W3 confinement attempts=%d allowed_escapes=%d refused_legals=%d %s\n",
		25*(len(legal)+len(escapes)), allowedEscapes, refusedLegals, verdict(w3))

	fmt.Printf("== windows stress %s\n", verdict(all))
}
