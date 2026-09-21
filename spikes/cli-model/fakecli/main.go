// Command fakecli is a scripted, hermetic stand-in for a one-shot agent CLI
// (devin/claude/codex/copilot-shaped). It never touches the network and never
// calls a real model — it plays back a fixed, ordered script of canned
// responses so the spike can reproduce specific hostile-model drifts
// deterministically.
//
// Flags:
//
//	-prompt-file PATH   read the prompt from a file (the "file channel")
//	-prompt STRING      the prompt was passed directly on argv (the "argv channel")
//	-out PATH           write the response here instead of stdout (codex-style)
//	-script PATH        JSON array of strings: the canned response bodies, in order
//	-state PATH         a counter file tracking which script entry is next
//	-echo-to PATH       if set, write the exact bytes of the received prompt here,
//	                    so a test can assert verbatim passthrough
//
// Each invocation is a fresh process (this is what a one-shot CLI is), so the
// "conversation" across repair attempts is carried entirely by -state on disk.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
)

func main() {
	promptFile := flag.String("prompt-file", "", "read prompt from this file")
	promptArg := flag.String("prompt", "", "prompt given directly on argv")
	outFile := flag.String("out", "", "write response to this file instead of stdout")
	scriptFile := flag.String("script", "", "JSON array of scripted response bodies")
	stateFile := flag.String("state", "", "counter file tracking script position")
	echoTo := flag.String("echo-to", "", "dump the exact received prompt bytes here")
	flag.Parse()

	var prompt string
	switch {
	case *promptFile != "":
		b, err := os.ReadFile(*promptFile)
		if err != nil {
			fatalf("fakecli: cannot read -prompt-file %s: %v", *promptFile, err)
		}
		prompt = string(b)
	case *promptArg != "":
		prompt = *promptArg
	default:
		fatalf("fakecli: one of -prompt-file or -prompt is required")
	}

	if *echoTo != "" {
		if err := os.WriteFile(*echoTo, []byte(prompt), 0o644); err != nil {
			fatalf("fakecli: cannot write -echo-to %s: %v", *echoTo, err)
		}
	}

	if *scriptFile == "" || *stateFile == "" {
		fatalf("fakecli: -script and -state are required")
	}

	scriptBytes, err := os.ReadFile(*scriptFile)
	if err != nil {
		fatalf("fakecli: cannot read -script %s: %v", *scriptFile, err)
	}
	var script []string
	if err := json.Unmarshal(scriptBytes, &script); err != nil {
		fatalf("fakecli: -script is not a JSON array of strings: %v", err)
	}
	if len(script) == 0 {
		fatalf("fakecli: -script must contain at least one entry")
	}

	idx := 0
	if b, err := os.ReadFile(*stateFile); err == nil {
		if n, err := strconv.Atoi(strings.TrimSpace(string(b))); err == nil {
			idx = n
		}
	}
	use := idx
	if use >= len(script) {
		use = len(script) - 1 // clamp: a CLI that "never complies" just repeats its last line
	}

	if err := os.WriteFile(*stateFile, []byte(strconv.Itoa(idx+1)), 0o644); err != nil {
		fatalf("fakecli: cannot write -state %s: %v", *stateFile, err)
	}

	response := script[use]
	if *outFile != "" {
		if err := os.WriteFile(*outFile, []byte(response), 0o644); err != nil {
			fatalf("fakecli: cannot write -out %s: %v", *outFile, err)
		}
		fmt.Println("fakecli: wrote response to", *outFile)
		return
	}
	fmt.Print(response)
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
