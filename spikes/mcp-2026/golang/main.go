package main

import (
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: run.sh server | client <url> | selftest")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "server":
		port := os.Getenv("PORT")
		if port == "" {
			port = "7103"
		}
		s := mustServer()
		fmt.Printf("listening on http://127.0.0.1:%s/mcp\n", port)
		hs := &http.Server{Addr: "127.0.0.1:" + port, Handler: s, ReadHeaderTimeout: 10 * time.Second,
			ReadTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 120 * time.Second}
		if err := hs.ListenAndServe(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "client":
		url := "http://127.0.0.1:7103/mcp"
		if len(os.Args) > 2 {
			url = os.Args[2]
		}
		if !runChecks(url) {
			os.Exit(1)
		}
	case "selftest":
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			panic(err)
		}
		go http.Serve(ln, mustServer())
		if !runChecks("http://" + ln.Addr().String() + "/mcp") {
			os.Exit(1)
		}
	}
}

func mustServer() *Server {
	fx := os.Getenv("FIXTURE")
	if fx == "" {
		_, here, _, _ := runtime.Caller(0)
		fx = filepath.Join(filepath.Dir(here), "..", "fixture.json")
	}
	s, err := LoadServer(fx)
	if err != nil {
		panic(err)
	}
	return s
}

// setup configures every client the checks create (e.g. TLS + OAuth for a protected server).
func runChecks(url string, setup ...func(*Client)) bool {
	ok := true
	step := func(name string, f func() error) {
		if err := f(); err == errSkip {
			fmt.Printf("SKIP %s (not applicable over stdio)\n", name)
		} else if err != nil {
			ok = false
			fmt.Printf("FAIL %s: %v\n", name, err)
		} else {
			fmt.Printf("PASS %s\n", name)
		}
	}
	mk := func() *Client {
		c := NewClient(url)
		for _, f := range setup {
			f(c)
		}
		return c
	}
	c := mk()
	expectCode := func(err error, code int) error {
		var e *rpcError
		if errors.As(err, &e) && e.Code == code {
			return nil
		}
		return fmt.Errorf("want error %d, got %v", code, err)
	}

	step("server/discover", func() error {
		r, err := c.Call("server/discover", nil)
		if err != nil {
			return err
		}
		caps, _ := r["capabilities"].(map[string]any)
		ext, _ := caps["extensions"].(map[string]any)
		if _, has := ext[SkillsExt]; !has {
			return fmt.Errorf("skills extension not advertised: %v", caps)
		}
		if vs, _ := r["supportedVersions"].([]any); len(vs) == 0 || !contains(vs, ProtocolVersion) {
			return fmt.Errorf("supportedVersions lacks %s: %v", ProtocolVersion, r["supportedVersions"])
		}
		return nil
	})
	step("unsupported version -> -32022", func() error {
		bad := mk()
		bad.Version = "1900-01-01"
		_, err := bad.Call("server/discover", nil)
		return expectCode(err, CodeUnsupportedProtocol)
	})
	step("header mismatch -> -32020", func() error {
		bad := mk()
		if bad.stdio != nil {
			return errSkip // stdio has no HTTP headers to mismatch
		}
		bad.headerOverride = map[string]string{"Mcp-Name": "greet"}
		_, err := bad.Call("tools/call", obj{"name": "echo", "arguments": obj{"text": "x"}})
		return expectCode(err, CodeHeaderMismatch)
	})
	step("tools/list", func() error {
		r, err := c.Call("tools/list", nil)
		if err != nil {
			return err
		}
		tools, _ := r["tools"].([]any)
		var names []any
		for _, t := range tools {
			names = append(names, t.(map[string]any)["name"])
		}
		if !contains(names, "echo") || !contains(names, "greet") {
			return fmt.Errorf("tools: %v", names)
		}
		if r["ttlMs"] == nil || r["cacheScope"] == nil || r["resultType"] != "complete" {
			return fmt.Errorf("missing ttlMs/cacheScope/resultType: %v", r)
		}
		return nil
	})
	step("tools/call echo", func() error {
		r, err := c.CallTool("echo", obj{"text": "hi toolnexus"}, nil)
		if err != nil {
			return err
		}
		if Text(r) != "hi toolnexus" {
			return fmt.Errorf("got %v", r)
		}
		return nil
	})
	step("tools/call greet (MRTR input_required round trip)", func() error {
		asked := 0
		r, err := c.CallTool("greet", obj{}, func(key string, req obj) (obj, error) {
			asked++
			if req["method"] != "elicitation/create" {
				return nil, fmt.Errorf("unexpected input request %v", req["method"])
			}
			return obj{"action": "accept", "content": obj{"name": "toolnexus"}}, nil
		})
		if err != nil {
			return err
		}
		if asked != 1 || Text(r) != "Hello, toolnexus!" {
			return fmt.Errorf("asked=%d result=%v", asked, r)
		}
		return nil
	})
	const uri = "skill://hello-world/SKILL.md"
	step("skills/list", func() error {
		r, err := c.Call("skills/list", nil)
		if err != nil {
			return err
		}
		for _, s := range r["skills"].([]any) {
			if s.(map[string]any)["uri"] == uri {
				return nil
			}
		}
		return fmt.Errorf("hello-world not listed: %v", r)
	})
	step("skills/get hello-world", func() error {
		r, err := c.Call("skills/get", obj{"uri": uri})
		if err != nil {
			return err
		}
		sk, _ := r["skill"].(map[string]any)
		fm, _ := sk["frontmatter"].(map[string]any)
		res, _ := sk["resources"].([]any)
		if fm["name"] != "hello-world" || len(res) == 0 {
			return fmt.Errorf("got %v", r)
		}
		return nil
	})
	return ok
}

var errSkip = errors.New("skip")

func contains(xs []any, v any) bool {
	for _, x := range xs {
		if x == v {
			return true
		}
	}
	return false
}

// Text returns the first text content of a tool result ("" if none).
func Text(r obj) string {
	c, _ := r["content"].([]any)
	if len(c) == 0 {
		return ""
	}
	t, _ := c[0].(map[string]any)["text"].(string)
	return t
}
