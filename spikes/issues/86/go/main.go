// Spike for issue #86 (Go): a toolkit-less completion. Go accepts a nil
// *Toolkit on Ask/Run; this asserts it reaches the wire with NO `tools` key.
package main

import (
	"context"
	"fmt"
	"os"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

func main() {
	base := os.Getenv("SPIKE86_BASE")
	c := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: base, Style: toolnexus.StyleOpenAI, Model: "mock", APIKey: "not-a-real-key",
	})
	// The whole point: the third argument is nil. It compiles and it runs.
	res, err := c.Ask(context.Background(), "write me a haiku", nil, "")
	if err != nil {
		fmt.Println("go: ERROR:", err)
		os.Exit(1)
	}
	fmt.Printf("go: ok, text=%q\n", res.Text)
}
