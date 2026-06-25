// toolnexus — a single binary wrapping the toolnexus library into an interactive
// agent loop (opencode's run loop, distilled). See ../../SPEC.md §9.
//
//	toolnexus run   --config mcp.json --skills ./skills --base-url URL --style openai --model M [--system S] [--api-key K] [--once "prompt"]
//	toolnexus tools --config mcp.json --skills ./skills
//	toolnexus --help
package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"os"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

const usage = `toolnexus — point any OpenAI- or Anthropic-style endpoint at mcp.json/skills and get a working agent.

Usage:
  toolnexus run   [flags]    Start an interactive agent loop (or a single --once prompt).
  toolnexus tools [flags]    List the resolved tools (name + source) and exit.
  toolnexus --help           Show this help.

run flags:
  --config string     Path to an MCP config file (optional).
  --skills value      Skills folder (optional, repeatable).
  --base-url string   LLM base URL, e.g. https://openrouter.ai/api/v1
  --style string      "openai" | "anthropic" (default "openai").
  --model string      Model id, e.g. openai/gpt-4o-mini
  --system string     System prompt prepended to the skills catalog.
  --api-key string    API key (else env: OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY).
  --once string       Run a single prompt then exit (no REPL).

tools flags:
  --config string     Path to an MCP config file (optional).
  --skills value      Skills folder (optional, repeatable).
`

// stringSlice is a repeatable string flag.
type stringSlice []string

func (s *stringSlice) String() string { return strings.Join(*s, ",") }
func (s *stringSlice) Set(v string) error {
	*s = append(*s, v)
	return nil
}

func main() {
	if len(os.Args) < 2 {
		fmt.Print(usage)
		os.Exit(2)
	}
	switch os.Args[1] {
	case "run":
		runCmd(os.Args[2:])
	case "tools":
		toolsCmd(os.Args[2:])
	case "-h", "--help", "help":
		fmt.Print(usage)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", os.Args[1])
		fmt.Print(usage)
		os.Exit(2)
	}
}

// buildToolkit resolves a Toolkit from a config path and skill dirs.
func buildToolkit(ctx context.Context, config string, skills []string) (*toolnexus.Toolkit, error) {
	opts := toolnexus.Options{SkillsDir: skills}
	if config != "" {
		opts.McpConfig = config
	}
	return toolnexus.CreateToolkit(ctx, opts)
}

func toolsCmd(args []string) {
	fs := flag.NewFlagSet("tools", flag.ExitOnError)
	config := fs.String("config", "", "Path to an MCP config file")
	var skills stringSlice
	fs.Var(&skills, "skills", "Skills folder (repeatable)")
	_ = fs.Parse(args)

	ctx := context.Background()
	tk, err := buildToolkit(ctx, *config, skills)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	defer tk.Close()

	for server, status := range tk.McpStatus() {
		fmt.Printf("# mcp %s: %s\n", server, status)
	}
	for _, t := range tk.Tools() {
		fmt.Printf("%s\t(%s)\n", t.Name, t.Source)
	}
}

func runCmd(args []string) {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	config := fs.String("config", "", "Path to an MCP config file")
	var skills stringSlice
	fs.Var(&skills, "skills", "Skills folder (repeatable)")
	baseURL := fs.String("base-url", "", "LLM base URL")
	style := fs.String("style", "openai", "openai | anthropic")
	model := fs.String("model", "", "Model id")
	system := fs.String("system", "", "System prompt")
	apiKey := fs.String("api-key", "", "API key (else from env)")
	once := fs.String("once", "", "Run a single prompt then exit")
	_ = fs.Parse(args)

	if *baseURL == "" || *model == "" {
		fmt.Fprintln(os.Stderr, "error: --base-url and --model are required")
		os.Exit(2)
	}

	ctx := context.Background()
	tk, err := buildToolkit(ctx, *config, skills)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	defer tk.Close()

	client := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL:      *baseURL,
		Style:        toolnexus.ClientStyle(*style),
		Model:        *model,
		APIKey:       *apiKey, // empty ⇒ resolved from env; never printed
		SystemPrompt: *system,
	})

	ask := func(prompt string) {
		res, err := client.Run(ctx, prompt, tk)
		if err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			return
		}
		fmt.Println(res.Text)
	}

	if *once != "" {
		ask(*once)
		return
	}

	fmt.Fprintln(os.Stderr, "toolnexus agent ready. Type a prompt (Ctrl-D or 'exit' to quit).")
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for {
		fmt.Fprint(os.Stderr, "> ")
		if !scanner.Scan() {
			break // EOF (Ctrl-D)
		}
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		if line == "exit" || line == "quit" {
			break
		}
		ask(line)
	}
}
