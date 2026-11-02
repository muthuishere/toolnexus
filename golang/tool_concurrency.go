package toolnexus

import (
	"os"
	"strconv"
	"sync"
)

// DefaultMaxToolConcurrency is the cap on concurrent safe tool calls when
// neither ClientOptions.MaxToolConcurrency nor TOOLNEXUS_MAX_TOOL_CONCURRENCY
// sets one.
const DefaultMaxToolConcurrency = 10

// maxToolConcurrency resolves the cap: option > env > default.
func (c *Client) maxToolConcurrency() int {
	if c.opts.MaxToolConcurrency > 0 {
		return c.opts.MaxToolConcurrency
	}
	if n, err := strconv.Atoi(os.Getenv("TOOLNEXUS_MAX_TOOL_CONCURRENCY")); err == nil && n > 0 {
		return n
	}
	return DefaultMaxToolConcurrency
}

// IsConcurrencySafe reports whether a call to t with args may run alongside
// other safe calls of the same response. A tool's own ConcurrencySafe predicate
// wins; otherwise a tool is safe only when its Hints mark it ReadOnly. A tool
// with no hints is treated as unsafe.
func IsConcurrencySafe(t Tool, args map[string]any) bool {
	if t.ConcurrencySafe != nil {
		return t.ConcurrencySafe(args)
	}
	return t.Hints != nil && t.Hints.ReadOnly
}

// callSafe decides one call's safety by name, looking through call_tool to the
// tool it calls. An unknown tool is unsafe (it fails, alone).
func (c *Client) callSafe(tk *Toolkit, name string, args map[string]any) bool {
	if c.opts.ToolTiers != nil && name == "tool_search" {
		return true
	}
	name, args, _ = c.unwrapCall(name, args)
	t, ok := tk.Get(name)
	if !ok {
		return false
	}
	return IsConcurrencySafe(t, args)
}

// toolSched runs the calls of one response in emitted order: consecutive safe
// calls overlap (at most cap at once); an unsafe call waits for everything
// before it, runs alone, and nothing after it starts until it finishes.
type toolSched struct {
	wg  sync.WaitGroup
	sem chan struct{}
}

func (c *Client) newToolSched() *toolSched {
	return &toolSched{sem: make(chan struct{}, c.maxToolConcurrency())}
}

// Go schedules one call. Call it in emitted order.
func (s *toolSched) Go(safe bool, fn func()) {
	if !safe {
		s.wg.Wait()
		fn()
		return
	}
	s.sem <- struct{}{}
	s.wg.Add(1)
	go func() {
		defer func() { <-s.sem; s.wg.Done() }()
		fn()
	}()
}

// Wait blocks until every scheduled call has finished.
func (s *toolSched) Wait() { s.wg.Wait() }
