// Replays conformance/serve_mcp/cases.json (SPEC §7C): the toolkit.serve MCP
// profile wire every port must match.

package toolnexus

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

type serveMcpCase struct {
	Name    string `json:"name"`
	Request struct {
		Method  string            `json:"method"`
		Path    string            `json:"path"`
		Headers map[string]string `json:"headers"`
	} `json:"request"`
	Expect struct {
		Status  int               `json:"status"`
		Headers map[string]string `json:"headers"`
	} `json:"expect"`
}

func TestServeMcpConformance(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "conformance", "serve_mcp", "cases.json"))
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Cases []serveMcpCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{echoTool}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	h, err := tk.Serve("127.0.0.1:0", ServeOptions{MCP: &MCPServeConfig{}})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Stop()
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			req, _ := http.NewRequest(c.Request.Method, h.URL+c.Request.Path, nil)
			for k, v := range c.Request.Headers {
				req.Header.Set(k, v)
			}
			res, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			res.Body.Close()
			if res.StatusCode != c.Expect.Status {
				t.Fatalf("status = %d, want %d", res.StatusCode, c.Expect.Status)
			}
			for k, v := range c.Expect.Headers {
				if got := res.Header.Get(k); got != v {
					t.Fatalf("header %s = %q, want %q", k, got, v)
				}
			}
		})
	}
}
