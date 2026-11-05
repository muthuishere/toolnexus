package mcp2026

// stdio client transport (MCP 2026-07-28 basic/transports): spawn the server, one JSON-RPC
// message per line on stdin/stdout. stderr is the server's log stream: passed through
// (or captured via cmd.Stderr), never parsed. No headers, no auth — the _meta carries the version.

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"time"
)

type stdioConn struct {
	cmd     *exec.Cmd
	in      io.WriteCloser
	wmu     sync.Mutex
	mu      sync.Mutex
	pending map[string]chan []byte
	err     error // set once stdout closes
	done    chan struct{}
}

// NewStdioClient starts cmd and returns a Client with the same API as NewClient.
// cmd.Stderr nil = pass through to os.Stderr. Call Close to stop the process.
func NewStdioClient(cmd *exec.Cmd) (*Client, error) {
	if cmd.Stderr == nil {
		cmd.Stderr = os.Stderr
	}
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	s := &stdioConn{cmd: cmd, in: in, pending: map[string]chan []byte{}, done: make(chan struct{})}
	go s.read(out)
	c := NewClient("")
	c.stdio = s
	return c, nil
}

func (s *stdioConn) read(out io.Reader) {
	sc := bufio.NewScanner(out)
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	for sc.Scan() {
		var m struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
		}
		if json.Unmarshal(sc.Bytes(), &m) != nil || m.Method != "" || len(m.ID) == 0 {
			continue // not a response (notification / server request / noise): ignored in this spike
		}
		s.mu.Lock()
		ch := s.pending[string(m.ID)]
		delete(s.pending, string(m.ID))
		s.mu.Unlock()
		if ch != nil {
			ch <- append([]byte(nil), sc.Bytes()...)
		}
	}
	s.mu.Lock()
	s.err = errors.New("stdio server closed its stdout")
	if e := sc.Err(); e != nil {
		s.err = fmt.Errorf("stdio read: %w", e)
	}
	s.mu.Unlock()
	close(s.done)
}

// roundTrip writes one line and waits for the response with the same id. Never retried.
func (s *stdioConn) roundTrip(id int64, body []byte, msg *rpcMsg) error {
	key := strconv.FormatInt(id, 10)
	ch := make(chan []byte, 1)
	s.mu.Lock()
	if s.err != nil {
		s.mu.Unlock()
		return s.err
	}
	s.pending[key] = ch
	s.mu.Unlock()
	defer func() { s.mu.Lock(); delete(s.pending, key); s.mu.Unlock() }()
	s.wmu.Lock()
	_, err := s.in.Write(append(body, '\n')) // json.Marshal never emits a raw newline
	s.wmu.Unlock()
	if err != nil {
		return err
	}
	t := time.NewTimer(DefaultTimeout)
	defer t.Stop()
	select {
	case b := <-ch:
		if err := json.Unmarshal(b, msg); err != nil {
			return err
		}
		if msg.Result == nil && msg.Error == nil {
			return errors.New("response has neither result nor error")
		}
		return nil
	case <-s.done:
		s.mu.Lock()
		defer s.mu.Unlock()
		return s.err
	case <-t.C:
		return errors.New("stdio request timed out")
	}
}

// Close stops a stdio server (close stdin, then kill after 2s); no-op for HTTP clients.
func (c *Client) Close() error {
	s := c.stdio
	if s == nil {
		return nil
	}
	s.in.Close()
	select {
	case <-s.done:
	case <-time.After(2 * time.Second):
		s.cmd.Process.Kill()
	}
	return s.cmd.Wait()
}

// ServeStdio serves s as newline-delimited JSON-RPC over in/out (one message per line) until in
// closes. No auth and no HTTP headers exist on stdio; only the _meta protocol version is checked.
func ServeStdio(s *Server, in io.Reader, out io.Writer) {
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	enc := json.NewEncoder(out) // one message per line
	for sc.Scan() {
		var req struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params obj             `json:"params"`
		}
		if json.Unmarshal(sc.Bytes(), &req) != nil {
			enc.Encode(obj{"jsonrpc": "2.0", "id": nil, "error": rpcError{Code: -32700, Message: "Parse error"}})
			continue
		}
		if len(req.ID) == 0 {
			continue
		}
		meta, _ := req.Params["_meta"].(map[string]any)
		var res obj
		var e *rpcError
		if mv, _ := meta[MetaVersion].(string); mv != ProtocolVersion {
			e = &rpcError{Code: CodeUnsupportedProtocol, Message: "Unsupported protocol version",
				Data: obj{"supported": []string{ProtocolVersion}, "requested": mv}}
		} else {
			res, e = s.dispatch(context.Background(), req.Method, req.Params)
		}
		msg := obj{"jsonrpc": "2.0", "id": req.ID}
		if e != nil {
			msg["error"] = e
		} else {
			msg["result"] = res
		}
		enc.Encode(msg)
	}
}
