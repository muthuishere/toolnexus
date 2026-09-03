// Live multimodal round trip against OpenRouter — proves an attached image and a
// tool-returned image both reach the model, on both wire styles (§1B parts, §8A
// emission + the tool-result relocation rule).
//
//	OPENROUTER_API_KEY=... go run ./examples/multimodal
//
// How arrival is proven: NOT by reading the answer. A model asked to name colours
// will happily name colours it never received — which is exactly how a silent
// image drop hides. Arrival is proven by the PROMPT-TOKEN DELTA between two
// otherwise identical requests, one with the image and one without. A model can
// guess a colour; it cannot fake prompt tokens.
//
// Cheap by construction: tiny models, the 82-byte shared fixture, max_tokens 40.
// The key is read from the environment and is never printed or logged.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

// minImageTokens is the floor a real image costs even at 82 bytes. Anything
// under this is the image having been dropped somewhere en route.
const minImageTokens = 20

// minRelocationTokens is the floor for the relocation measurement, which compares
// two multi-turn runs and so carries a few tokens of tokenizer noise. 85 is §1B's
// own per-part floor — below it, nothing that could be called an image arrived.
const minRelocationTokens = 85

const ask = "Name the four quadrant colours of this image, clockwise from top-left. Answer with four words only."

const toolAsk = "Call the show_fixture tool, then name the four quadrant colours of the image it returns. Answer with four words only."

var quadrantColours = []string{"red", "green", "blue", "white"}

type styleCase struct {
	style toolnexus.ClientStyle
	model string
}

func main() {
	if os.Getenv("OPENROUTER_API_KEY") == "" {
		log.Fatal("OPENROUTER_API_KEY not set")
	}

	_, thisFile, _, _ := runtime.Caller(0)
	fixture := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples", "media", "fixture.png")

	ctx := context.Background()

	// An empty toolkit for the attachment checks: no tools, so the two requests
	// differ by exactly one thing — the image.
	bare, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{Builtins: false})
	if err != nil {
		log.Fatal(err)
	}
	defer bare.Close()

	// Two toolkits identical in every advertised byte (same tool name, description
	// and schema, same Output text) except that one also returns the image in
	// ToolResult.Parts. Their prompt-token difference IS the relocated image.
	withImage, err := fixtureToolkit(ctx, toolnexus.File(fixture))
	if err != nil {
		log.Fatal(err)
	}
	defer withImage.Close()
	textOnly, err := fixtureToolkit(ctx)
	if err != nil {
		log.Fatal(err)
	}
	defer textOnly.Close()

	cases := []styleCase{
		{toolnexus.StyleOpenAI, envOr("OPENROUTER_OPENAI_MODEL", "openai/gpt-4o-mini")},
		{toolnexus.StyleAnthropic, envOr("OPENROUTER_ANTHROPIC_MODEL", "anthropic/claude-haiku-4.5")},
	}

	for _, sc := range cases {
		// Per-LLM-call prompt tokens. The relocation check compares the LAST call
		// of each run — the one made AFTER the tool result — so a difference in
		// turn count between two runs cannot masquerade as an image.
		var calls []int
		client := toolnexus.CreateClient(toolnexus.ClientOptions{
			BaseURL: "https://openrouter.ai/api/v1",
			Style:   sc.style,
			Model:   sc.model,
			// APIKey omitted on purpose: the client reads OPENROUTER_API_KEY from
			// the environment itself, so the value never passes through this file.
			SystemPrompt:  "Answer with the four colour words only.",
			RequestParams: map[string]any{"max_tokens": 40},
			OnMetric: func(ev toolnexus.MetricEvent) {
				if ev.Event == "llm" && ev.Status == "ok" {
					calls = append(calls, ev.PromptTokens)
				}
			},
		})

		// (1) the attachment check — same prompt, without then with the image.
		textRun, err := client.RunParts(ctx, []toolnexus.ContentPart{toolnexus.Text(ask)}, bare)
		if err != nil {
			fmt.Printf("RESULT golang style=%s model=%s error=%v\n", sc.style, sc.model, err)
			continue
		}
		imageRun, err := client.RunParts(ctx, []toolnexus.ContentPart{
			toolnexus.Text(ask),
			toolnexus.File(fixture),
		}, bare)
		if err != nil {
			fmt.Printf("RESULT golang style=%s model=%s error=%v\n", sc.style, sc.model, err)
			continue
		}
		ptokText := textRun.Usage.PromptTokens
		ptokImage := imageRun.Usage.PromptTokens
		delta := ptokImage - ptokText
		colours := countColours(imageRun.Text)

		// (2) the §8A relocation check — the same tool, with and without parts.
		relocation, relocDelta := relocationCheck(ctx, client, withImage, textOnly, &calls, delta >= minImageTokens)

		fmt.Printf("RESULT golang style=%s model=%s ptok_text=%d ptok_image=%d delta=%+d colours=%d/4 relocation=%s reloc_delta=%+d\n",
			sc.style, sc.model, ptokText, ptokImage, delta, colours, relocation, relocDelta)
		fmt.Printf("       attached answer: %s\n", oneLine(imageRun.Text))

		if delta < minImageTokens {
			fmt.Printf("       NOTE: +%d prompt tokens is too small to be the image — it was dropped en route.\n", delta)
		}
	}
}

// fixtureToolkit builds a toolkit holding one `show_fixture` tool. The advertised
// schema, name, description and text Output are identical whether or not parts
// are supplied — the only difference on the wire is the image itself.
func fixtureToolkit(ctx context.Context, parts ...toolnexus.ContentPart) (*toolnexus.Toolkit, error) {
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{Builtins: false})
	if err != nil {
		return nil, err
	}
	for _, p := range parts {
		if err := p.Err(); err != nil {
			return nil, err
		}
	}
	tk.Register(toolnexus.Tool{
		Name:        "show_fixture",
		Description: "Return the fixture image so it can be described.",
		InputSchema: toolnexus.JSONSchema{
			"type":                 "object",
			"properties":           map[string]any{},
			"additionalProperties": false,
		},
		Source: toolnexus.SourceNative,
		Execute: func(map[string]any, *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
			return toolnexus.ToolResult{Output: "the fixture image", Parts: parts}, nil
		},
	})
	return tk, nil
}

// relocationCheck runs the same tool-calling prompt twice — once where the tool
// returns the image in ToolResult.Parts, once where it returns only text — and
// reads the prompt-token difference. "ok" means the image survived the round trip
// into the next request (relocated into a synthetic user message on the openai
// style, emitted natively on the anthropic one).
// imageArrives says whether an ATTACHED image reached this model at all. When it
// did not, the route drops images whatever the shape, and no relocation
// measurement through it can be evidence either way — say so rather than scoring
// the noise.
func relocationCheck(ctx context.Context, client *toolnexus.Client, withImage, textOnly *toolnexus.Toolkit, calls *[]int, imageArrives bool) (string, int) {
	*calls = nil
	imgRun, err := client.Run(ctx, toolAsk, withImage)
	if err != nil {
		return "fail(" + err.Error() + ")", 0
	}
	imgLast := last(*calls)
	*calls = nil
	txtRun, err := client.Run(ctx, toolAsk, textOnly)
	if err != nil {
		return "fail(" + err.Error() + ")", 0
	}
	txtLast := last(*calls)
	if len(imgRun.ToolCalls) == 0 || len(txtRun.ToolCalls) == 0 {
		return "fail(tool-not-called)", 0
	}
	delta := imgLast - txtLast
	switch {
	case delta >= minRelocationTokens:
		return "ok", delta
	case !imageArrives:
		// The run completed and the tool's parts were emitted; the route ate them,
		// as it also ate the plain attachment above.
		return "upstream-drop", delta
	default:
		return "dropped", delta
	}
}

// last is the final element of a slice, or 0 when it is empty.
func last(xs []int) int {
	if len(xs) == 0 {
		return 0
	}
	return xs[len(xs)-1]
}

func countColours(answer string) int {
	lower := strings.ToLower(answer)
	n := 0
	for _, c := range quadrantColours {
		if strings.Contains(lower, c) {
			n++
		}
	}
	return n
}

func oneLine(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
