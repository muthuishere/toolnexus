// Package acpmodels is a spike for issue #110: model discovery + per-session
// selection over ACP, using ONLY what the ACP v1 schema defines
// (session/new configOptions + session/set_config_option), with a legacy
// fallback for pre-configOptions agents (session/new "models" +
// session/set_model, which is no longer in the ACP schema).
//
// Deliberately vendor-neutral: no "Free" flag. An option carries its raw
// value/name/description/group/_meta; a host decides what "free" means with
// its own predicate (see HasSuffix example in the test).
package acpmodels

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

// ConfigValue is one selectable value of an ACP select config option,
// exactly as the agent sent it. Nothing is derived.
type ConfigValue struct {
	Value       string          `json:"value"`
	Name        string          `json:"name"`
	Description string          `json:"description,omitempty"`
	Group       string          `json:"group,omitempty"` // SessionConfigSelectGroup.group, if grouped
	Meta        json.RawMessage `json:"_meta,omitempty"`
}

// ConfigOption is a SessionConfigOption, options flattened (groups kept as Group).
type ConfigOption struct {
	ID       string
	Name     string
	Category string // "model", "mode", "thought_level", "_x"... MUST NOT be required for correctness
	Type     string
	Current  string
	Values   []ConfigValue
}

// Source says which wire shape the model list came from.
type Source string

const (
	SourceConfigOptions Source = "configOptions" // ACP v1
	SourceLegacyModels  Source = "models"        // pre-v1 unstable shape
)

var ErrNoModelSelector = errors.New("acp: agent advertises no model selector (neither configOptions[category=model] nor models)")

// Info is the kept initialize result.
type Info struct {
	ProtocolVersion   int             `json:"protocolVersion"`
	AgentInfo         json.RawMessage `json:"agentInfo,omitempty"`
	AgentCapabilities json.RawMessage `json:"agentCapabilities,omitempty"`
	AuthMethods       json.RawMessage `json:"authMethods,omitempty"`
}

type Options struct {
	Command string
	Args    []string
	Env     []string
	Cwd     string
	Timeout time.Duration
}

type SessionOptions struct {
	Cwd   string
	Model string // "" = agent default
	Mode  string // "" = agent default
}

// ---- client -----------------------------------------------------------------

type Client struct {
	cmd     *exec.Cmd
	in      io.WriteCloser
	mu      sync.Mutex
	next    int
	pending map[int]chan msg
	sink    func(json.RawMessage)
	timeout time.Duration
	info    Info
	opts    Options
}

type msg struct {
	ID     *int            `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

func Create(ctx context.Context, o Options) (*Client, error) {
	if o.Timeout == 0 {
		o.Timeout = 30 * time.Second
	}
	cmd := exec.Command(o.Command, o.Args...)
	cmd.Env = append(cmd.Environ(), o.Env...)
	in, _ := cmd.StdinPipe()
	out, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	c := &Client{cmd: cmd, in: in, pending: map[int]chan msg{}, timeout: o.Timeout, opts: o}
	go c.read(out)
	res, err := c.call(ctx, "initialize", map[string]any{"protocolVersion": 1,
		"clientCapabilities": map[string]any{"fs": map[string]any{"readTextFile": false, "writeTextFile": false}}})
	if err != nil {
		c.Close()
		return nil, fmt.Errorf("initialize: %w", err)
	}
	_ = json.Unmarshal(res, &c.info) // KEPT — LoadACP discards this today
	return c, nil
}

func (c *Client) Info() Info { return c.info }

func (c *Client) read(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(nil, 32<<20)
	for sc.Scan() {
		var m msg
		if json.Unmarshal(sc.Bytes(), &m) != nil {
			continue
		}
		switch {
		case m.Method == "session/update":
			c.mu.Lock()
			s := c.sink
			c.mu.Unlock()
			if s != nil {
				s(m.Params)
			}
		case m.Method == "session/request_permission" && m.ID != nil:
			c.write(map[string]any{"jsonrpc": "2.0", "id": *m.ID, "result": map[string]any{"outcome": map[string]any{"outcome": "cancelled"}}})
		case m.Method == "" && m.ID != nil:
			c.mu.Lock()
			ch := c.pending[*m.ID]
			delete(c.pending, *m.ID)
			c.mu.Unlock()
			if ch != nil {
				ch <- m
			}
		}
	}
}

func (c *Client) write(v any) {
	b, _ := json.Marshal(v)
	c.mu.Lock()
	defer c.mu.Unlock()
	_, _ = c.in.Write(append(b, '\n'))
}

func (c *Client) callT(ctx context.Context, t time.Duration, method string, params any) (json.RawMessage, error) {
	c.mu.Lock()
	c.next++
	id := c.next
	ch := make(chan msg, 1)
	c.pending[id] = ch
	c.mu.Unlock()
	c.write(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	select {
	case m := <-ch:
		if m.Error != nil {
			return nil, fmt.Errorf("%s: acp error %d: %s", method, m.Error.Code, m.Error.Message)
		}
		return m.Result, nil
	case <-time.After(t):
		return nil, fmt.Errorf("%s: timed out", method)
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (c *Client) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	return c.callT(ctx, c.timeout, method, params)
}

func (c *Client) Close() error { _ = c.in.Close(); _ = c.cmd.Process.Kill(); _ = c.cmd.Wait(); return nil }

// ---- parsing ------------------------------------------------------------------

type wireNew struct {
	SessionID     string            `json:"sessionId"`
	ConfigOptions []json.RawMessage `json:"configOptions"`
	Models        *struct {
		Current   string `json:"currentModelId"`
		Available []struct {
			ModelID     string          `json:"modelId"`
			Name        string          `json:"name"`
			Description string          `json:"description"`
			Meta        json.RawMessage `json:"_meta"`
		} `json:"availableModels"`
	} `json:"models"`
}

func parseOptions(raws []json.RawMessage) []ConfigOption {
	var out []ConfigOption
	for _, r := range raws {
		var w struct {
			ID, Name, Category, Type string
			CurrentValue             string            `json:"currentValue"`
			Options                  []json.RawMessage `json:"options"`
		}
		if json.Unmarshal(r, &w) != nil || w.Type != "select" { // skip-invalid / unknown types, per schema
			continue
		}
		o := ConfigOption{ID: w.ID, Name: w.Name, Category: w.Category, Type: w.Type, Current: w.CurrentValue}
		for _, x := range w.Options {
			var g struct {
				Group   string        `json:"group"`
				Options []ConfigValue `json:"options"`
			}
			if json.Unmarshal(x, &g) == nil && g.Group != "" {
				for _, v := range g.Options {
					v.Group = g.Group
					o.Values = append(o.Values, v)
				}
				continue
			}
			var v ConfigValue
			if json.Unmarshal(x, &v) == nil && v.Value != "" {
				o.Values = append(o.Values, v)
			}
		}
		out = append(out, o)
	}
	return out
}

// modelOption finds the model selector. category is a UX hint the schema says
// MUST NOT be required for correctness, so fall back to id=="model".
func modelOption(opts []ConfigOption, cat string) (ConfigOption, bool) {
	for _, o := range opts {
		if o.Category == cat {
			return o, true
		}
	}
	for _, o := range opts {
		if o.ID == cat {
			return o, true
		}
	}
	return ConfigOption{}, false
}

func modelsOf(n wireNew) (ConfigOption, Source, error) {
	if o, ok := modelOption(parseOptions(n.ConfigOptions), "model"); ok {
		return o, SourceConfigOptions, nil
	}
	if n.Models != nil {
		o := ConfigOption{ID: "model", Category: "model", Type: "select", Current: n.Models.Current}
		for _, m := range n.Models.Available {
			o.Values = append(o.Values, ConfigValue{Value: m.ModelID, Name: m.Name, Description: m.Description, Meta: m.Meta})
		}
		return o, SourceLegacyModels, nil
	}
	return ConfigOption{}, "", ErrNoModelSelector
}

// ---- API ---------------------------------------------------------------------

// Models opens a throwaway session (ACP only reports models per session) and
// returns the model selector. Costs one session/new (+ session/close if
// supported); on opencode that is ~0.3s.
func (c *Client) Models(ctx context.Context) (ConfigOption, Source, error) {
	s, err := c.NewSession(ctx, SessionOptions{})
	if err != nil {
		return ConfigOption{}, "", err
	}
	return s.Model, s.Source, s.err
}

type Session struct {
	c        *Client
	ID       string
	Options  []ConfigOption
	Model    ConfigOption // current selector state after selection
	Source   Source
	Verified bool // true when the agent confirmed the selected value
	err      error
}

func (c *Client) NewSession(ctx context.Context, so SessionOptions) (*Session, error) {
	cwd := so.Cwd
	if cwd == "" {
		cwd = c.opts.Cwd
	}
	res, err := c.call(ctx, "session/new", map[string]any{"cwd": cwd, "mcpServers": []any{}})
	if err != nil {
		return nil, err
	}
	var n wireNew
	if err := json.Unmarshal(res, &n); err != nil || n.SessionID == "" {
		return nil, fmt.Errorf("session/new: no sessionId")
	}
	s := &Session{c: c, ID: n.SessionID, Options: parseOptions(n.ConfigOptions)}
	s.Model, s.Source, s.err = modelsOf(n)
	if so.Model != "" {
		if s.err != nil {
			return nil, fmt.Errorf("model %q requested: %w", so.Model, s.err)
		}
		if err := s.set(ctx, "model", so.Model); err != nil {
			return nil, err
		}
	}
	if so.Mode != "" {
		if err := s.set(ctx, "mode", so.Mode); err != nil {
			return nil, err
		}
	}
	return s, nil
}

// set selects a value and REFUSES to continue unless the agent confirms it.
func (s *Session) set(ctx context.Context, cat, value string) error {
	var target ConfigOption
	if cat == "model" && s.Source == SourceLegacyModels {
		target = s.Model
	} else if o, ok := modelOption(s.Options, cat); ok {
		target = o
	} else if cat == "mode" {
		_, err := s.c.call(ctx, "session/set_mode", map[string]any{"sessionId": s.ID, "modeId": value})
		return err // legacy modes path: no confirmation available
	} else {
		return fmt.Errorf("no %s selector", cat)
	}
	known := false
	for _, v := range target.Values {
		known = known || v.Value == value
	}
	if !known {
		return fmt.Errorf("%s %q not offered by agent (%d values)", cat, value, len(target.Values))
	}
	if cat == "model" && s.Source == SourceLegacyModels {
		if _, err := s.c.call(ctx, "session/set_model", map[string]any{"sessionId": s.ID, "modelId": value}); err != nil {
			return err
		}
		s.Model.Current, s.Verified = value, false // {} reply: nothing to verify against
		return nil
	}
	res, err := s.c.call(ctx, "session/set_config_option", map[string]any{"sessionId": s.ID, "configId": target.ID, "value": value})
	if err != nil {
		return err
	}
	var r struct {
		ConfigOptions []json.RawMessage `json:"configOptions"`
	}
	_ = json.Unmarshal(res, &r)
	s.Options = parseOptions(r.ConfigOptions)
	got, ok := modelOption(s.Options, cat)
	if !ok || got.Current != value {
		return fmt.Errorf("%s: agent accepted %q but reports current=%q — refusing to run on the wrong %s", cat, value, got.Current, cat)
	}
	if cat == "model" {
		s.Model, s.Verified = got, true
	}
	return nil
}

// Prompt runs one turn and returns the agent's message text.
func (s *Session) Prompt(ctx context.Context, text string) (string, error) {
	var b strings.Builder
	s.c.mu.Lock()
	s.c.sink = func(p json.RawMessage) {
		var u struct {
			Update struct {
				Kind    string `json:"sessionUpdate"`
				Content struct {
					Text string `json:"text"`
				} `json:"content"`
			} `json:"update"`
		}
		if json.Unmarshal(p, &u) == nil && u.Update.Kind == "agent_message_chunk" {
			b.WriteString(u.Update.Content.Text)
		}
	}
	s.c.mu.Unlock()
	_, err := s.c.callT(ctx, 3*time.Minute, "session/prompt", map[string]any{"sessionId": s.ID,
		"prompt": []any{map[string]any{"type": "text", "text": text}}})
	return b.String(), err
}

// Generate has LoadACP's shape (last user message only; spike).
func (s *Session) Generate(req toolnexus.InProcessRequest) (toolnexus.InProcessResponse, error) {
	last := ""
	for _, m := range req.Messages {
		if mm, ok := m.(map[string]any); ok {
			if t, ok := mm["content"].(string); ok {
				last = t
			}
		}
	}
	out, err := s.Prompt(context.Background(), last)
	return toolnexus.InProcessResponse{Content: out}, err
}
