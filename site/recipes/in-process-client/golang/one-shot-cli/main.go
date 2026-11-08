package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

func main() {
	ctx := context.Background()
	add := tn.NativeToolReflect("add", "Add two numbers.", func(_ context.Context, in addArgs) (string, error) {
		return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
	})
	tk, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false, ExtraTools: []tn.Tool{add}})
	if err != nil {
		panic(err)
	}
	r, err := OneShotClient(os.Args[1:], 5*time.Minute).Run(ctx, "Use the add tool to add 19 and 23, then tell me the sum.", tk)
	if err != nil {
		panic(err)
	}
	fmt.Printf("text: %q calls: %+v\n", r.Text, r.ToolCalls)
}
