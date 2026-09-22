// OPT-IN, ONE live call. Reproduces issue #91 against the DEFAULT base with the
// DEFAULT model, using zero-value ClassifierOptions. Reads TYPESAFE_API_KEY by
// NAME through the library's own apiKeyEnv; the value never enters this program.
//
//	go run ./live
package main

import (
	"context"
	"fmt"
	"os"

	tn "github.com/muthuishere/toolnexus/golang"
)

func main() {
	if os.Getenv(tn.DefaultClassifierAPIKeyEnv) == "" {
		fmt.Printf("%s is not set — nothing to do.\n", tn.DefaultClassifierAPIKeyEnv)
		return
	}
	c, err := tn.CreateClassifier(tn.ClassifierOptions{}) // every default
	if err != nil {
		fmt.Println("construct:", err)
		return
	}
	_, err = c.Evaluate(context.Background(), map[string]any{"text": "ping"},
		map[string]tn.Question{"ok": tn.NoulQuestion{Instructions: "Is this a greeting?"}})
	fmt.Printf("base=%s model=%s\nerr=%v\n", tn.DefaultClassifierBaseURL, tn.DefaultClassifierModel, err)
}
