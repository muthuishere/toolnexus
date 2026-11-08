// Spike: observe where an ACP agent (grok agent stdio) delivers a generated image.
// Logs every raw JSON-RPC line (both directions) to captures/<name>.jsonl and
// auto-allows session/request_permission. Usage: go run . <capture-name> <prompt>
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

func main() {
	name, prompt := os.Args[1], os.Args[2]
	cwd, _ := os.MkdirTemp("", "media-acp-")
	logf, _ := os.Create(filepath.Join("captures", name+".jsonl"))
	defer logf.Close()
	var mu sync.Mutex
	t0 := time.Now()
	logLine := func(dir string, raw []byte) {
		mu.Lock()
		defer mu.Unlock()
		fmt.Fprintf(logf, "{\"t_ms\":%d,\"dir\":%q,\"msg\":%s}\n", time.Since(t0).Milliseconds(), dir, raw)
	}
	cmd := exec.Command("grok", "agent", "stdio")
	cmd.Env = append(os.Environ(), "GROK_DEFAULT_SELECTED_PERMISSION=allow_always",
		`GROK_CONFIG={"features":{"image_gen":true}}`)
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	cmd.Stderr, _ = os.Create(filepath.Join("captures", name+".stderr.log"))
	if err := cmd.Start(); err != nil {
		panic(err)
	}
	send := func(v any) {
		b, _ := json.Marshal(v)
		logLine("out", b)
		mu.Lock()
		stdin.Write(append(b, '\n'))
		mu.Unlock()
	}
	replies := map[int]chan json.RawMessage{}
	for i := 1; i <= 3; i++ {
		replies[i] = make(chan json.RawMessage, 1)
	}
	go func() {
		sc := bufio.NewScanner(stdout)
		sc.Buffer(make([]byte, 64<<20), 64<<20)
		for sc.Scan() {
			raw := append([]byte(nil), sc.Bytes()...)
			logLine("in", raw)
			var m struct {
				ID     json.RawMessage `json:"id"`
				Method string          `json:"method"`
				Result json.RawMessage `json:"result"`
				Error  json.RawMessage `json:"error"`
				Params struct {
					Options []struct {
						OptionID string `json:"optionId"`
						Kind     string `json:"kind"`
					} `json:"options"`
				} `json:"params"`
			}
			if json.Unmarshal(raw, &m) != nil {
				continue
			}
			switch {
			case m.Method == "session/request_permission":
				opt := ""
				for _, o := range m.Params.Options {
					if opt == "" && (o.Kind == "allow_once" || o.Kind == "allow_always") {
						opt = o.OptionID
					}
				}
				send(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{
					"outcome": map[string]any{"outcome": "selected", "optionId": opt}}})
			case m.Method == "" && len(m.ID) > 0:
				var id int
				json.Unmarshal(m.ID, &id)
				if ch, ok := replies[id]; ok {
					if len(m.Error) > 0 {
						ch <- m.Error
					} else {
						ch <- m.Result
					}
				}
			case m.Method != "" && len(m.ID) > 0: // unsupported client method
				send(map[string]any{"jsonrpc": "2.0", "id": m.ID, "error": map[string]any{"code": -32601, "message": "not supported"}})
			}
		}
	}()
	wait := func(id int, d time.Duration) json.RawMessage {
		select {
		case r := <-replies[id]:
			return r
		case <-time.After(d):
			panic(fmt.Sprintf("timeout waiting for reply %d", id))
		}
	}
	send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": map[string]any{
		"protocolVersion": 1, "clientCapabilities": map[string]any{"fs": map[string]any{"readTextFile": false, "writeTextFile": false}, "terminal": false}}})
	wait(1, 60*time.Second)
	send(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "session/new", "params": map[string]any{"cwd": cwd, "mcpServers": []any{}}})
	var sn struct{ SessionID string `json:"sessionId"` }
	json.Unmarshal(wait(2, 60*time.Second), &sn)
	tp := time.Now()
	send(map[string]any{"jsonrpc": "2.0", "id": 3, "method": "session/prompt", "params": map[string]any{
		"sessionId": sn.SessionID, "prompt": []any{map[string]any{"type": "text", "text": prompt}}}})
	res := wait(3, 10*time.Minute)
	fmt.Printf("prompt turn: %s  result=%s\ncwd=%s\n", time.Since(tp).Round(time.Millisecond), res, cwd)
	filepath.Walk(cwd, func(p string, fi os.FileInfo, _ error) error {
		if fi != nil && !fi.IsDir() {
			fmt.Println("cwd file:", p, fi.Size())
		}
		return nil
	})
	stdin.Close()
	cmd.Process.Kill()
}
