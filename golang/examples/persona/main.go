// Persona agent (SPEC §7E): the directory IS the agent. Mirrors js/examples/persona.ts.
//
// agents.FromDir composes a folder of bootstrap files (SOUL/USER/HEARTBEAT/MEMORY.md) into
// a frozen soul and wires a file-backed `memory` tool, so Ava can edit her own durable
// memory. This proves the two things that make a persona different from a one-shot worker:
//
//  1. a memory write lands on DISK (not just in the transcript), and
//  2. it loads at the START of the NEXT session (frozen-snapshot — the cache-stable rule).
//
// The committed bootstrap dir (examples/persona-agent/ava) is copied to a writable temp dir
// first, so a run never dirties the repo — the real pattern for a sandboxed host.
//
//	OPENROUTER_API_KEY=... go run ./examples/persona
package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"

	toolnexus "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

func copyDir(src, dst string) error {
	return filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		return os.WriteFile(target, b, 0o644)
	})
}

func main() {
	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		fmt.Println("no OPENROUTER_API_KEY — skipping live persona loop")
		return
	}
	model := os.Getenv("OPENROUTER_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}
	llm := &agents.LLMOptions{
		BaseURL: "https://openrouter.ai/api/v1",
		Style:   toolnexus.StyleOpenAI,
		Model:   model,
		APIKey:  key,
	}

	_, thisFile, _, _ := runtime.Caller(0)
	ava := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples", "persona-agent", "ava")

	// Copy the committed bootstrap dir to a writable temp home so the run never dirties the repo.
	home, err := os.MkdirTemp("", "ava-")
	if err != nil {
		log.Fatal(err)
	}
	defer os.RemoveAll(home)
	if err := copyDir(ava, home); err != nil {
		log.Fatal(err)
	}

	dark := regexp.MustCompile(`(?i)dark roast`)

	// Session 1 — Ava learns a stable preference and records it with her memory tool.
	a1 := agents.FromDir(home, agents.FromDirOptions{Name: "ava"})
	s1, _ := a1.Run(agents.Options{LLM: llm},
		"I take my coffee as a dark roast, no sugar. This is a stable preference — "+
			"call the memory tool now (action=add, target=user) to save it to my profile, "+
			"then reply 'noted'.")
	fmt.Printf("session 1: %s · %s\n", s1.Status, strings.ReplaceAll(s1.Text, "\n", " "))

	userMd, _ := os.ReadFile(filepath.Join(home, "USER.md"))
	persisted := dark.Match(userMd)
	fmt.Println("USER.md on disk now contains 'dark roast':", persisted)

	// Session 2 — a FRESH FromDir re-reads the folder: the snapshot now carries the write.
	a2 := agents.FromDir(home, agents.FromDirOptions{Name: "ava"})
	s2, _ := a2.Run(agents.Options{LLM: llm}, "How do I take my coffee? Answer from what you already know.")
	fmt.Printf("session 2: %s · %s\n", s2.Status, strings.ReplaceAll(s2.Text, "\n", " "))

	if !persisted || !dark.MatchString(s2.Text) {
		log.Fatal("persona did not persist + recall memory across sessions")
	}
	fmt.Println("\nGo persona memory verified — written to disk in session 1, recalled in session 2")
}
