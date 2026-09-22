// Stress harness for the builtin host boundary (ADR 0034) — go port.
//
// Not a unit test: a deliberate attempt to produce orphans, leaks, deadlocks
// and wrong verdicts under load, against the SHIPPED builtins.
//
//	S1 concurrent timeouts   30 at once, every one must kill its whole job
//	S2 mixed load            40 at once, successes and timeouts not crossed
//	S3 big output + timeout   5 MB on stdout then a sleep — the deadlock shape
//	S4 leaks                  threads / child processes / fds, over 3 rounds
//	S5 confinement under load 500 concurrent resolves, zero wrong verdicts
//
// Every scenario carries a CONTROL. An assertion that passes because the
// harness never ran measures nothing, which has already happened twice here.
package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
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
	res, err := t.Execute(args, &tn.ToolContext{Ctx: context.Background()})
	if err != nil {
		return tn.ToolResult{Output: err.Error(), IsError: true}
	}
	return res
}

// orphanCommand: the GRANDCHILD writes the marker, and `sleep 0.2` in front
// stops the shell exec-optimising the single command away.
func orphanCommand(marker string) string {
	return "sleep 0.2; sh -c 'sleep 5; touch " + marker + "'"
}

func countMarkers(dir string) int {
	entries, _ := os.ReadDir(dir)
	n := 0
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".marker") {
			n++
		}
	}
	return n
}

// ---------------------------------------------------------------------------

func s1(dir string) bool {
	bash := tool(nil, "bash")
	const n = 30
	markers := filepath.Join(dir, "s1")
	_ = os.MkdirAll(markers, 0o755)

	var wg sync.WaitGroup
	timedOut, killedTree := 0, 0
	var mu sync.Mutex
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			m := filepath.Join(markers, fmt.Sprintf("t%d.marker", i))
			res := run(bash, map[string]any{"command": orphanCommand(m), "timeout": 300})
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
	time.Sleep(7 * time.Second)
	orphans := countMarkers(markers)

	// CONTROL: the same command with a long timeout MUST write its marker.
	control := filepath.Join(dir, "s1c")
	_ = os.MkdirAll(control, 0o755)
	var cwg sync.WaitGroup
	for i := 0; i < 3; i++ {
		cwg.Add(1)
		go func(i int) {
			defer cwg.Done()
			m := filepath.Join(control, fmt.Sprintf("c%d.marker", i))
			run(bash, map[string]any{"command": orphanCommand(m), "timeout": 20000})
		}(i)
	}
	cwg.Wait()
	controlMarkers := countMarkers(control)

	pass := orphans == 0 && timedOut == n && killedTree == n && controlMarkers == 3
	fmt.Printf("S1 concurrent_timeouts n=%d orphans=%d timedOut=%d killedTree=%d control_markers=%d/3 %s\n",
		n, orphans, timedOut, killedTree, controlMarkers, verdict(pass))
	return pass
}

func s2(dir string) bool {
	bash := tool(nil, "bash")
	const pairs = 20
	markers := filepath.Join(dir, "s2")
	_ = os.MkdirAll(markers, 0o755)

	var wg sync.WaitGroup
	var mu sync.Mutex
	okCorrect, okWrong, timeoutCorrect, timeoutWrong := 0, 0, 0, 0
	for i := 0; i < pairs; i++ {
		wg.Add(2)
		go func(i int) {
			defer wg.Done()
			want := fmt.Sprintf("ok-%d", i)
			res := run(bash, map[string]any{"command": "echo " + want})
			mu.Lock()
			defer mu.Unlock()
			// The output must be THIS call's, not another concurrent call's.
			if !res.IsError && strings.TrimSpace(res.Output) == want {
				okCorrect++
			} else {
				okWrong++
			}
		}(i)
		go func(i int) {
			defer wg.Done()
			m := filepath.Join(markers, fmt.Sprintf("m%d.marker", i))
			res := run(bash, map[string]any{"command": orphanCommand(m), "timeout": 300})
			mu.Lock()
			defer mu.Unlock()
			if res.IsError && res.Metadata["timedOut"] == true {
				timeoutCorrect++
			} else {
				timeoutWrong++
			}
		}(i)
	}
	wg.Wait()
	time.Sleep(7 * time.Second)
	orphans := countMarkers(markers)

	pass := okCorrect == pairs && okWrong == 0 && timeoutCorrect == pairs && timeoutWrong == 0 && orphans == 0
	fmt.Printf("S2 mixed_load ok_correct=%d/%d ok_wrong=%d timeout_correct=%d/%d timeout_wrong=%d orphans=%d %s\n",
		okCorrect, pairs, okWrong, timeoutCorrect, pairs, timeoutWrong, orphans, verdict(pass))
	return pass
}

func s3() bool {
	bash := tool(nil, "bash")
	// 5 MB on stdout, then a sleep past the timeout. This is the shape that
	// deadlocks when a killed child's pipes are held open by a grandchild:
	// CombinedOutput waits for the pipes, not for the process.
	cmd := `head -c 5000000 /dev/zero | tr '\0' 'x'; sleep 10`
	start := time.Now()
	res := run(bash, map[string]any{"command": cmd, "timeout": 1000})
	elapsed := time.Since(start)

	// CONTROL: the same big write with a generous timeout must SUCCEED and
	// return all 5 MB — otherwise "returned fast" could just mean "broken".
	controlStart := time.Now()
	controlRes := run(bash, map[string]any{"command": `head -c 5000000 /dev/zero | tr '\0' 'x'`, "timeout": 30000})
	controlElapsed := time.Since(controlStart)

	pass := res.IsError && elapsed < 6*time.Second && !controlRes.IsError && len(controlRes.Output) >= 5000000
	fmt.Printf("S3 big_output_timeout elapsed_ms=%d is_error=%v bytes_on_timeout=%d control_ok=%v control_bytes=%d control_ms=%d %s\n",
		elapsed.Milliseconds(), res.IsError, len(res.Output), !controlRes.IsError, len(controlRes.Output),
		controlElapsed.Milliseconds(), verdict(pass))
	return pass
}

type snapshot struct {
	goroutines int
	children   int
	fds        int
}

func take() snapshot {
	return snapshot{goroutines: runtime.NumGoroutine(), children: childCount(), fds: fdCount()}
}

func childCount() int {
	out, err := exec.Command("ps", "-eo", "pid=,ppid=").Output()
	if err != nil {
		return -1
	}
	self := os.Getpid()
	n := 0
	for _, line := range strings.Split(string(out), "\n") {
		parts := strings.Fields(line)
		if len(parts) == 2 {
			if ppid, err := strconv.Atoi(parts[1]); err == nil && ppid == self {
				n++
			}
		}
	}
	return n
}

func fdCount() int {
	out, err := exec.Command("lsof", "-p", strconv.Itoa(os.Getpid())).Output()
	if err != nil {
		return -1
	}
	return strings.Count(string(out), "\n")
}

func s4(dir string) bool {
	bash := tool(nil, "bash")
	var rounds []snapshot
	for r := 0; r < 3; r++ {
		var wg sync.WaitGroup
		for i := 0; i < 10; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				m := filepath.Join(dir, fmt.Sprintf("s4-%d-%d.marker", r, i))
				run(bash, map[string]any{"command": orphanCommand(m), "timeout": 300})
			}(i)
		}
		wg.Wait()
		time.Sleep(500 * time.Millisecond)
		rounds = append(rounds, take())
	}
	// Monotonic growth across identical rounds is the leak signal; one round's
	// absolute numbers say nothing on their own.
	leakingFDs := rounds[2].fds > rounds[0].fds+8
	leakingChildren := rounds[2].children > rounds[0].children+2
	leakingGoroutines := rounds[2].goroutines > rounds[0].goroutines+8
	pass := !leakingFDs && !leakingChildren && !leakingGoroutines
	fmt.Printf("S4 leaks goroutines=%d/%d/%d children=%d/%d/%d fds=%d/%d/%d %s\n",
		rounds[0].goroutines, rounds[1].goroutines, rounds[2].goroutines,
		rounds[0].children, rounds[1].children, rounds[2].children,
		rounds[0].fds, rounds[1].fds, rounds[2].fds, verdict(pass))
	return pass
}

func s5(dir string) bool {
	base := filepath.Join(dir, "confine")
	outside := filepath.Join(dir, "outside")
	_ = os.MkdirAll(filepath.Join(base, "sub"), 0o755)
	_ = os.MkdirAll(outside, 0o755)
	_ = os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600)
	_ = os.Symlink(outside, filepath.Join(base, "link"))
	for _, f := range []string{"a.txt", "sub/b.txt", "c.txt"} {
		_ = os.WriteFile(filepath.Join(base, f), []byte("x"), 0o644)
	}

	write := tool(tn.BuiltinsConfig{BaseDir: base, ConfineToBaseDir: true}, "write")

	legal := []string{
		"a.txt", "sub/b.txt", "./c.txt", "sub/new-file.txt",
		// `....` is a LITERAL directory name, not a parent reference — the
		// lookalike that catches a checker doing string surgery on dots. It
		// stays inside the base, so allowing it is correct. The first version of
		// this harness listed it as an escape and "found" a bug in the library
		// that did not exist.
		"....//x",
	}
	escapes := []string{
		"../x", "sub/../../x", filepath.Join(outside, "secret.txt"),
		"link/secret.txt", "sub/./../../outside/secret.txt",
		// A deep climb that lands somewhere WRITABLE: /etc/passwd would be
		// refused by the filesystem anyway, so it cannot tell a working
		// confinement check from a permission error.
		"../../../../../../../../tmp/tn-stress-escaped.txt",
	}

	var mu sync.Mutex
	allowedEscapes, refusedLegals := 0, 0
	perPath := map[string]int{}
	var wg sync.WaitGroup
	for round := 0; round < 50; round++ {
		for _, p := range legal {
			wg.Add(1)
			go func(p string) {
				defer wg.Done()
				res := run(write, map[string]any{"path": p, "content": "x"})
				if res.IsError {
					mu.Lock()
					refusedLegals++
					mu.Unlock()
				}
			}(p)
		}
		for _, p := range escapes {
			wg.Add(1)
			go func(p string) {
				defer wg.Done()
				res := run(write, map[string]any{"path": p, "content": "pwned"})
				if !res.IsError {
					mu.Lock()
					allowedEscapes++
					perPath[p]++
					mu.Unlock()
				}
			}(p)
		}
	}
	wg.Wait()

	// CONTROL: the same escapes with confinement OFF must all be allowed —
	// otherwise "zero escapes" could mean the writes were failing anyway.
	unconfined := tool(tn.BuiltinsConfig{BaseDir: base}, "write")
	controlAllowed := 0
	for _, p := range escapes {
		if !run(unconfined, map[string]any{"path": p, "content": "x"}).IsError {
			controlAllowed++
		}
	}

	total := 50 * (len(legal) + len(escapes))
	pass := allowedEscapes == 0 && refusedLegals == 0 && controlAllowed == len(escapes)
	fmt.Printf("S5 confinement_under_load attempts=%d allowed_escapes=%d refused_legals=%d control_allowed_without_confine=%d/%d %s\n",
		total, allowedEscapes, refusedLegals, controlAllowed, len(escapes), verdict(pass))
	for p, n := range perPath {
		full := p
		if !filepath.IsAbs(p) {
			full = filepath.Join(base, p)
		}
		resolved, _ := filepath.EvalSymlinks(filepath.Dir(full))
		fmt.Printf("   ALLOWED %-40q times=%d joined=%q dir_realpath=%q\n", p, n, full, resolved)
	}
	return pass
}

func verdict(ok bool) string {
	if ok {
		return "PASS"
	}
	return "FAIL"
}

func main() {
	dir, err := os.MkdirTemp("", "tn-stress-go-*")
	if err != nil {
		panic(err)
	}
	defer os.RemoveAll(dir)
	fmt.Printf("== go stress (%s/%s, pid %d)\n", runtime.GOOS, runtime.GOARCH, os.Getpid())

	all := true
	all = s1(dir) && all
	all = s2(dir) && all
	all = s3() && all
	all = s4(dir) && all
	all = s5(dir) && all
	fmt.Printf("== go stress %s\n", verdict(all))
	if !all {
		os.Exit(1)
	}
}
